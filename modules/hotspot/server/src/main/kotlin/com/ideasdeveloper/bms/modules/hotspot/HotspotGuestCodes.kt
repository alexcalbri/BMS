package com.ideasdeveloper.bms.modules.hotspot

import com.ideasdeveloper.bms.modules.hotspot.HotspotPortalPages.FormValues
import com.ideasdeveloper.bms.modules.hotspot.HotspotPortalPages.GuestContext
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * What the public form does in the database: the limits on emailed codes, the code as one session shared by
 * at most [MaxDevices] devices, and the reconnection wait. Kept apart from the portal so each rule can be tested on its own.
 *
 * A code is activated by the first device that enters it: from then on it is a session that ends [accessMinutes] later.
 * A second device that enters the same code joins that session and ends at the same moment, whenever it connects.
 * When the time is over the code cannot connect again, and the guest cannot get a new one until the reconnection wait
 * ([minutesUntilReconnect], counted from the first activation) has passed.
 */
object HotspotGuestCodes {
    // Without an active session, a code requested again within this interval is shown instead of sending another.
    val ResendInterval: Duration = Duration.ofSeconds(60)

    // Codes emailed in the last hour: per device and per destination address (protects a third party's mailbox).
    val SendWindow: Duration = Duration.ofHours(1)
    const val MaxPerDevice = 6
    const val MaxPerEmail = 12

    /** Devices that can use one code. */
    const val MaxDevices = 2

    /** New customers the form creates per origin address in an hour. */
    const val MaxNewCustomersPerIpHour = 600

    /** Wrong tries a code takes in total (each device also has its own limit). */
    const val MaxWrongTries = 30

    /** Largest form the public portal reads (fields are short; the continue URL is cut at 500). */
    const val MaxFormBytes = 16 * 1024

    /** A code of this address that is usable now: an active session, or one requested less than [ResendInterval] ago. */
    fun liveCode(connection: Connection, email: String, now: Instant = Instant.now()): LiveCode? = connection.prepareStatement(
        """
        SELECT codes.id, codes.activated_at IS NOT NULL
        FROM hotspot_codes codes
        JOIN hotspot_registrations registrations ON registrations.id = codes.registration_id
        WHERE lower(registrations.email) = lower(?)
          AND codes.attempts < ?
          AND (
              (codes.activated_at IS NOT NULL AND codes.ends_at > ?)
              OR (codes.activated_at IS NULL AND codes.used_at IS NULL AND codes.expires_at > ? AND codes.created_at > ?)
          )
        ORDER BY codes.activated_at IS NOT NULL DESC, codes.created_at DESC
        LIMIT 1
        """.trimIndent()
    ).use { query ->
        query.setString(1, email)
        query.setInt(2, MaxWrongTries)
        query.setTimestamp(3, Timestamp.from(now))
        query.setTimestamp(4, Timestamp.from(now))
        query.setTimestamp(5, Timestamp.from(now.minus(ResendInterval)))
        query.executeQuery().use { rows -> if (rows.next()) LiveCode(rows.getObject(1, UUID::class.java), rows.getBoolean(2)) else null }
    }

    data class LiveCode(val id: UUID, val active: Boolean)

    /** True when this device, or this destination address, already got its codes of the last hour. */
    fun overSendLimit(connection: Connection, email: String, mac: String, now: Instant = Instant.now()): Boolean = connection.prepareStatement(
        """
        SELECT count(*) FILTER (WHERE registrations.mac = ?),
               count(*) FILTER (WHERE lower(registrations.email) = lower(?))
        FROM hotspot_codes codes
        JOIN hotspot_registrations registrations ON registrations.id = codes.registration_id
        WHERE codes.created_at > ?
          AND (registrations.mac = ? OR lower(registrations.email) = lower(?))
        """.trimIndent()
    ).use { query ->
        query.setString(1, mac)
        query.setString(2, email)
        query.setTimestamp(3, Timestamp.from(now.minus(SendWindow)))
        query.setString(4, mac)
        query.setString(5, email)
        query.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1) >= MaxPerDevice || rows.getInt(2) >= MaxPerEmail
        }
    }

    /** The customer of [email] when it exists (the form reuses it), without creating anything. */
    fun existingCustomer(connection: Connection, email: String): UUID? = connection.prepareStatement(
        "SELECT id FROM customers WHERE lower(primary_email) = lower(?) AND status <> 'archived' AND ${com.ideasdeveloper.bms.server.modules.CustomerSql.Visible} ORDER BY created_at LIMIT 1"
    ).use { query ->
        query.setString(1, email)
        query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
    }

    /** Creates the registration of a form submission (its customer already exists) with its code, in one transaction. */
    fun insertCode(
        connection: Connection,
        registrationId: UUID,
        codeId: UUID,
        customerId: UUID,
        values: FormValues,
        birthday: LocalDate?,
        guest: GuestContext,
        codeHash: String,
        expiresAt: Instant,
    ) = transaction(connection) {
        insertRegistration(connection, registrationId, customerId, userId = null, userCompany = null, values, birthday, guest, HotspotNetworks.Form, roamedFrom = null)
        connection.prepareStatement("INSERT INTO hotspot_codes (id, registration_id, code_hash, expires_at) VALUES (?, ?, ?, ?)").use { insert ->
            insert.setObject(1, codeId)
            insert.setObject(2, registrationId)
            insert.setString(3, codeHash)
            insert.setTimestamp(4, Timestamp.from(expiresAt))
            insert.executeUpdate()
        }
    }

    /** How the entering of a code by a device ended. */
    sealed interface Activation {
        /** First device: the session starts now and ends at [endsAt]. */
        data class Started(val endsAt: Instant, val minutes: Int) : Activation

        /** Another device (or the same one again) joins the running session: it ends at [endsAt] too. */
        data class Joined(val endsAt: Instant, val minutes: Int, val sameDevice: Boolean) : Activation

        /** The session of this code is over. */
        data class Over(val endsAt: Instant) : Activation

        /** The code was not used in time. */
        data object Expired : Activation

        /** An old single-use code that was already used. */
        data object Used : Activation

        /** The code already runs on [MaxDevices] other devices. */
        data object DeviceLimit : Activation

        /** First use of the code, but the address connected recently: [minutes] to wait. */
        data class ReconnectWait(val minutes: Long) : Activation
    }

    /**
     * A device enters the code. The row of the code is locked for the whole decision, and the device slots (1 and 2 only) are a
     * primary key, so two devices that enter at the same moment can never make three. [reconnectWait] is asked only for the first use.
     */
    fun activate(connection: Connection, codeId: UUID, mac: String, accessMinutes: Int, now: Instant = Instant.now(), reconnectWait: () -> Long?): Activation =
        transaction(connection) {
            val state = connection.prepareStatement("SELECT expires_at, activated_at, ends_at, used_at FROM hotspot_codes WHERE id = ? FOR UPDATE").use { query ->
                query.setObject(1, codeId)
                query.executeQuery().use { rows ->
                    if (!rows.next()) return@transaction Activation.Expired
                    CodeState(rows.getTimestamp(1).toInstant(), rows.getTimestamp(2)?.toInstant(), rows.getTimestamp(3)?.toInstant(), rows.getTimestamp(4) != null)
                }
            }
            if (state.activatedAt != null) {
                val endsAt = state.endsAt!!
                if (!endsAt.isAfter(now)) return@transaction Activation.Over(endsAt)
                val minutes = minutesLeft(endsAt, now)
                val devices = devices(connection, codeId)
                if (mac in devices) return@transaction Activation.Joined(endsAt, minutes, sameDevice = true)
                if (devices.size >= MaxDevices || !insertDevice(connection, codeId, devices.size + 1, mac)) return@transaction Activation.DeviceLimit
                return@transaction Activation.Joined(endsAt, minutes, sameDevice = false)
            }
            if (state.used) return@transaction Activation.Used
            if (!state.expiresAt.isAfter(now)) return@transaction Activation.Expired
            reconnectWait()?.let { return@transaction Activation.ReconnectWait(it) }
            val endsAt = now.plus(Duration.ofMinutes(accessMinutes.coerceAtLeast(1).toLong()))
            connection.prepareStatement("UPDATE hotspot_codes SET activated_at = ?, ends_at = ? WHERE id = ?").use { update ->
                update.setTimestamp(1, Timestamp.from(now))
                update.setTimestamp(2, Timestamp.from(endsAt))
                update.setObject(3, codeId)
                update.executeUpdate()
            }
            insertDevice(connection, codeId, 1, mac)
            Activation.Started(endsAt, accessMinutes.coerceAtLeast(1))
        }

    /** The controller did not let the device in: gives its slot back, and the code its unused state when it was the only device. */
    fun release(connection: Connection, codeId: UUID, mac: String) = transaction(connection) {
        connection.prepareStatement("SELECT 1 FROM hotspot_codes WHERE id = ? FOR UPDATE").use { lock ->
            lock.setObject(1, codeId)
            lock.executeQuery().use { it.next() }
        }
        connection.prepareStatement("DELETE FROM hotspot_code_devices WHERE code_id = ? AND mac = ?").use { delete ->
            delete.setObject(1, codeId)
            delete.setString(2, mac)
            delete.executeUpdate()
        }
        if (devices(connection, codeId).isEmpty()) {
            connection.prepareStatement("UPDATE hotspot_codes SET activated_at = NULL, ends_at = NULL WHERE id = ?").use { update ->
                update.setObject(1, codeId)
                update.executeUpdate()
            }
        }
    }

    private class CodeState(val expiresAt: Instant, val activatedAt: Instant?, val endsAt: Instant?, val used: Boolean)

    // Whole minutes left, rounded up (the controller grants whole minutes): never more than a minute past the shared end.
    private fun minutesLeft(endsAt: Instant, now: Instant): Int = ((Duration.between(now, endsAt).toSeconds() + 59) / 60).toInt().coerceAtLeast(1)

    private fun devices(connection: Connection, codeId: UUID): List<String> =
        connection.prepareStatement("SELECT mac FROM hotspot_code_devices WHERE code_id = ? ORDER BY slot").use { query ->
            query.setObject(1, codeId)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }

    private fun insertDevice(connection: Connection, codeId: UUID, slot: Int, mac: String): Boolean =
        connection.prepareStatement("INSERT INTO hotspot_code_devices (code_id, slot, mac) VALUES (?, ?, ?) ON CONFLICT DO NOTHING").use { insert ->
            insert.setObject(1, codeId)
            insert.setInt(2, slot)
            insert.setString(3, mac)
            insert.executeUpdate() == 1
        }

    /**
     * Minutes until the address may start a new session: [reconnectMinutes] after the last time it connected with a first
     * activation (devices that joined a running session do not move it); null when it may connect.
     */
    fun minutesUntilReconnect(connection: Connection, email: String, reconnectMinutes: Int, now: Instant = Instant.now()): Long? {
        if (reconnectMinutes <= 0) return null
        val lastAccess = connection.prepareStatement(
            "SELECT max(created_at) FROM hotspot_accesses WHERE lower(email) = lower(?) AND authorized AND NOT joined"
        ).use { query ->
            query.setString(1, email)
            query.executeQuery().use { rows -> if (rows.next()) rows.getTimestamp(1)?.toInstant() else null }
        } ?: return null
        val remaining = Duration.between(now, lastAccess.plus(Duration.ofMinutes(reconnectMinutes.toLong())))
        return if (remaining.isNegative || remaining.isZero) null else (remaining.toSeconds() + 59) / 60
    }

    fun insertRegistration(
        connection: Connection,
        id: UUID,
        customerId: UUID?,
        userId: UUID?,
        userCompany: String?,
        values: FormValues,
        birthday: LocalDate?,
        guest: GuestContext,
        accessMode: String,
        // The company whose access this device continues here (shared networks).
        roamedFrom: String?,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO hotspot_registrations (id, customer_id, name, email, phone, birthday, mac, ap_mac, ssid, access_mode, user_id, user_company, roamed_from)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, id)
            insert.setObject(2, customerId)
            insert.setString(3, values.name)
            insert.setString(4, values.email.ifBlank { null })
            insert.setString(5, values.phone.ifBlank { null })
            insert.setObject(6, birthday)
            insert.setString(7, guest.mac)
            insert.setString(8, guest.apMac.takeIf { MacPattern.matches(it) })
            insert.setString(9, guest.ssid.take(64).takeIf { it.isNotBlank() })
            insert.setString(10, accessMode)
            insert.setObject(11, userId)
            insert.setString(12, userCompany)
            insert.setString(13, roamedFrom)
            insert.executeUpdate()
        }
    }

    val MacPattern = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    // Runs [block] in a transaction, or inside the caller's when there already is one.
    private fun <T> transaction(connection: Connection, block: () -> T): T {
        if (!connection.autoCommit) return block()
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    /** The submitted form (at most [MaxFormBytes], read in a bounded way), or null after answering 413. */
    suspend fun readForm(call: ApplicationCall): Parameters? {
        val declared = call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
        val bytes = if (declared != null && declared > MaxFormBytes) null
        else withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().use { it.readNBytes(MaxFormBytes + 1) } }
        if (bytes == null || bytes.size > MaxFormBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return null
        }
        return formOf(String(bytes, Charsets.UTF_8), 200)
    }
}

/** Pares clave=valor de un formulario application/x-www-form-urlencoded (el "+" es un espacio), a lo sumo [limit]. */
fun formOf(text: String, limit: Int): io.ktor.http.Parameters = io.ktor.http.Parameters.build {
    text.split('&').filter { it.isNotEmpty() }.take(limit).forEach { pair ->
        append(pair.substringBefore('=').decodeURLQueryComponent(plusIsSpace = true), pair.substringAfter('=', "").decodeURLQueryComponent(plusIsSpace = true))
    }
}
