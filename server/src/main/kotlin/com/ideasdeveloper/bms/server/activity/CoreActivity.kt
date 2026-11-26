package com.ideasdeveloper.bms.server.activity

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Core's own rows of the company activity log (module id "core"): sign-ins, users, company settings... */
object CoreActivity {
    private val log = LoggerFactory.getLogger(CoreActivity::class.java)

    /** A tenant user of [connection] as the log names them (business owner or user); null when unknown. */
    fun tenantPerson(connection: Connection, userId: UUID): ActivityActor? =
        connection.prepareStatement(
            """
            SELECT users.display_name, users.postgres_role, EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id)
            FROM application_users users WHERE users.id = ?
            """.trimIndent()
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows ->
                if (!rows.next()) return null
                val name = rows.getString(1)?.takeIf { it.isNotBlank() } ?: rows.getString(2)
                if (rows.getBoolean(3)) ActivityActor.businessOwner(userId, name) else ActivityActor.user(userId, name)
            }
        }

    /** A tenant user by login, with their id; null when there is none. */
    fun tenantPersonByLogin(connection: Connection, login: String): ActivityActor? {
        val id = connection.prepareStatement("SELECT id FROM application_users WHERE postgres_role = ?").use { query ->
            query.setString(1, login)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        } ?: return null
        return tenantPerson(connection, id)
    }

    /**
     * Records a Core row on [connection], in the caller's transaction: any failure propagates and
     * stops the change, except one: a company whose database does not have the table yet (tenant
     * V011 is applied at startup and by update.sh) keeps working, with a warning, instead of locking
     * everyone out of company administration until then. Inside a transaction that case is isolated
     * with a savepoint so the caller's transaction stays usable. Module rows (ActivityLog.record) have
     * no such exception.
     */
    fun record(
        connection: Connection,
        actor: ActivityActor,
        entityType: String,
        entityId: String?,
        entityLabel: String,
        action: String,
        summary: String,
        changes: List<ActivityChange> = emptyList(),
    ) {
        val entry = ActivityEntry(CoreModuleId, entityType, entityId, entityLabel, action, summary, changes)
        val savepoint = if (connection.autoCommit) null else connection.setSavepoint()
        try {
            ActivityLog.record(connection, actor, entry)
            savepoint?.let { runCatching { connection.releaseSavepoint(it) } }
        } catch (failure: SQLException) {
            if (failure.sqlState != "42P01") throw failure
            savepoint?.let { connection.rollback(it) }
            log.warn("activity_log missing in {}: Core row {} not recorded until tenant V011 is applied", runCatching { connection.catalog }.getOrNull(), action)
        }
    }

    /** Same as [record]; for events that are not data changes (sign-in, sign-out, switching). */
    fun recordEvent(
        connection: Connection,
        actor: ActivityActor,
        entityType: String,
        entityId: String?,
        entityLabel: String,
        action: String,
        summary: String,
        changes: List<ActivityChange> = emptyList(),
    ) = record(connection, actor, entityType, entityId, entityLabel, action, summary, changes)
}

/**
 * Coalesces failed sign-ins so guessing cannot turn into a flood of rows (ACTIVITY_LOG.md §3): the
 * first failure of a (login, address) in [window] is written at once; later ones in that window are
 * only counted and written as one row with the count when the window ends (on the next failure or
 * success of that key, or the next sweep). Each address writes at most [maxRowsPerAddress] separate
 * rows per window; beyond that its failures, whatever the login, join one row of that address.
 * Bounded memory ([maxKeys]); counts still pending when the server stops are lost.
 */
class FailedSignIns(
    private val window: Duration = Duration.ofMinutes(5),
    private val maxRowsPerAddress: Int = 20,
    private val maxKeys: Int = 10_000,
    private val clock: () -> Instant = Instant::now,
    // Writes one row: login tried (null: several, the address bucket), address, failures, first and last time.
    private val write: (login: String?, address: String, count: Int, first: Instant, last: Instant) -> Unit,
) {
    private class Entry(val login: String?, val address: String, val start: Instant) {
        var pending = 0
        var last: Instant = start
    }

    private class AddressWindow(val start: Instant) {
        var rows = 0
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val addresses = HashMap<String, AddressWindow>()
    private var lastSweep: Instant = Instant.EPOCH

    /** A failed sign-in of [login] from [address]. */
    fun failed(login: String, address: String) {
        val now = clock()
        val due = mutableListOf<Entry>()
        val writeNow: Entry?
        synchronized(this) {
            sweepLocked(now, due)
            val name = login.trim().take(100)
            val key = "$address\u0000$name"
            val current = entries[key]?.takeIf { live(it, now) }
            if (current != null) {
                current.pending++
                current.last = now
                writeNow = null
            } else {
                entries.remove(key)?.let { if (it.pending > 0) due += it }
                val perAddress = addresses.getOrPut(address) { AddressWindow(now) }.let { if (it.start.plus(window).isAfter(now)) it else AddressWindow(now).also { fresh -> addresses[address] = fresh } }
                if (perAddress.rows >= maxRowsPerAddress) {
                    // Too many different logins from one address: one bucket row for the rest of the window.
                    val bucketKey = "$address\u0000\u0001"
                    val bucket = entries[bucketKey]?.takeIf { live(it, now) }
                        ?: Entry(null, address, now).also { entries[bucketKey] = it }
                    bucket.pending++
                    bucket.last = now
                    writeNow = null
                } else if (entries.size >= maxKeys) {
                    // Memory full: still write, never drop silently.
                    perAddress.rows++
                    writeNow = Entry(name, address, now)
                } else {
                    perAddress.rows++
                    val entry = Entry(name, address, now)
                    entries[key] = entry
                    writeNow = entry
                }
            }
        }
        due.forEach(::flush)
        writeNow?.let { entry ->
            try {
                write(entry.login, entry.address, 1, now, now)
            } catch (failure: Exception) {
                LoggerFactory.getLogger(FailedSignIns::class.java).warn("Failed sign-in of {} not recorded: {}", entry.address, failure.message)
            }
        }
    }

    /** A successful sign-in: the failures still counted for that key are written now. */
    fun succeeded(login: String, address: String) {
        val entry = synchronized(this) { entries.remove("$address\u0000${login.trim().take(100)}") }
        if (entry != null && entry.pending > 0) flush(entry)
    }

    /** Writes every window that has ended (also called by [failed]). */
    fun sweep() {
        val due = mutableListOf<Entry>()
        synchronized(this) { sweepLocked(clock(), due, force = true) }
        due.forEach(::flush)
    }

    private fun sweepLocked(now: Instant, due: MutableList<Entry>, force: Boolean = false) {
        if (!force && Duration.between(lastSweep, now) < Duration.ofSeconds(30)) return
        lastSweep = now
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (!live(entry, now)) {
                iterator.remove()
                if (entry.pending > 0) due += entry
            }
        }
        addresses.entries.removeIf { !it.value.start.plus(window).isAfter(now) }
    }

    private fun live(entry: Entry, now: Instant) = entry.start.plus(window).isAfter(now)

    private fun flush(entry: Entry) {
        try {
            write(entry.login, entry.address, entry.pending, entry.start, entry.last)
        } catch (failure: Exception) {
            LoggerFactory.getLogger(FailedSignIns::class.java).warn("Failed sign-ins of {} not recorded: {}", entry.address, failure.message)
        }
    }
}
