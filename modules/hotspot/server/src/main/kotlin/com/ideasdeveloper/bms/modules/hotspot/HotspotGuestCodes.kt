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
 * What the public form does in the database before and after the guest proves the address:
 * the pending registration (no customer yet), the limits on emailed codes and the customer created
 * once the code is entered. Kept apart from the portal so each rule can be tested on its own.
 */
object HotspotGuestCodes {
    // A new code is not emailed while the previous one for the same address AND the same device is younger than this.
    val ResendInterval: Duration = Duration.ofSeconds(60)

    // Codes emailed in the last hour: per device (the guest's own limit) and per destination address (protects a third party's mailbox).
    // The address limit is higher than the device one and neither is shared by guests on other devices, so one person
    // typing someone else's address cannot take away the rest of that person's attempts from the device they use.
    val SendWindow: Duration = Duration.ofHours(1)
    const val MaxPerDevice = 6
    const val MaxPerEmail = 12

    /** Largest form the public portal reads (fields are short; the continue URL is cut at 500). */
    const val MaxFormBytes = 16 * 1024

    /** A code of this address requested from this same device, still valid and recent enough not to send another. */
    fun recentPending(connection: Connection, email: String, mac: String, now: Instant = Instant.now()): UUID? = connection.prepareStatement(
        """
        SELECT codes.id
        FROM hotspot_codes codes
        JOIN hotspot_registrations registrations ON registrations.id = codes.registration_id
        WHERE lower(registrations.email) = lower(?)
          AND registrations.mac = ?
          AND codes.used_at IS NULL
          AND codes.expires_at > ?
          AND codes.created_at > ?
        ORDER BY codes.created_at DESC
        LIMIT 1
        """.trimIndent()
    ).use { query ->
        query.setString(1, email)
        query.setString(2, mac)
        query.setTimestamp(3, Timestamp.from(now))
        query.setTimestamp(4, Timestamp.from(now.minus(ResendInterval)))
        query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
    }

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

    /** The registration of a form submission, with no customer and no user: it becomes the guest's only when the code is entered. */
    fun insertPending(
        connection: Connection,
        registrationId: UUID,
        codeId: UUID,
        values: FormValues,
        birthday: LocalDate?,
        guest: GuestContext,
        codeHash: String,
        expiresAt: Instant,
    ) = transaction(connection) {
        insertRegistration(connection, registrationId, customerId = null, userId = null, userCompany = null, values, birthday, guest, HotspotNetworks.Form, roamedFrom = null)
        connection.prepareStatement("INSERT INTO hotspot_codes (id, registration_id, code_hash, expires_at) VALUES (?, ?, ?, ?)").use { insert ->
            insert.setObject(1, codeId)
            insert.setObject(2, registrationId)
            insert.setString(3, codeHash)
            insert.setTimestamp(4, Timestamp.from(expiresAt))
            insert.executeUpdate()
        }
    }

    /** The customer of a guest whose address is now proven (an existing one with that address is reused), linked to the registration. */
    fun linkVerifiedCustomer(connection: Connection, registrationId: UUID, name: String, email: String, phone: String): UUID = transaction(connection) {
        val customerId = HotspotActivity.findOrCreateGuestCustomer(connection, name, email, phone)
        connection.prepareStatement("UPDATE hotspot_registrations SET customer_id = ? WHERE id = ? AND customer_id IS NULL AND user_id IS NULL AND roamed_from IS NULL").use { update ->
            update.setObject(1, customerId)
            update.setObject(2, registrationId)
            update.executeUpdate()
        }
        customerId
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
