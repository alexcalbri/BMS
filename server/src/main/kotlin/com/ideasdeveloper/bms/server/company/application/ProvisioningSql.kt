package com.ideasdeveloper.bms.server.company.application

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat
import javax.sql.DataSource

private val log = LoggerFactory.getLogger(CompanyProvisioningService::class.java)

internal fun Connection.exists(table: String, column: String, value: String): Boolean {
    prepareStatement("SELECT 1 FROM $table WHERE $column = ?").use { query ->
        query.setString(1, value)
        query.executeQuery().use { rows -> return rows.next() }
    }
}

internal fun Connection.existsDatabase(name: String): Boolean =
    prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use { query ->
        query.setString(1, name)
        query.executeQuery().use { rows -> rows.next() }
    }

internal fun Connection.existsRole(name: String): Boolean =
    prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?").use { query ->
        query.setString(1, name)
        query.executeQuery().use { rows -> rows.next() }
    }

internal fun quoteIdentifier(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

internal fun quoteLiteral(value: String): String = "'" + value.replace("'", "''") + "'"


/**
 * Ends the sessions on [database] before it is dropped. The provisioning connection runs as the server owner's role,
 * which may only signal sessions of roles whose privileges it has: asking PostgreSQL to end any other one (the
 * runtime role's pool, or a runtime connection closed a moment ago that is still exiting) fails the whole call with
 * "permission denied to terminate process". So each pass only ends what the current role may end, and the runtime
 * role's sessions are ended as the runtime role (the provisioning login may SET ROLE to it), then the role is set back.
 */
/**
 * Drops [database] as the role that owns it (the provisioning connection's role). WITH (FORCE) (PostgreSQL 13+)
 * ends every connection still on it in the same command, so a pool that reconnects between ending its sessions and
 * the drop cannot make the deletion fail. When FORCE is refused (a session the owner may not end, or an older
 * server), the sessions are ended as before (as the owner and as the runtime role) and the database is dropped.
 */
internal fun dropDatabaseForced(statement: java.sql.Statement, database: String, runtimeUser: String) {
    try {
        statement.executeUpdate("DROP DATABASE IF EXISTS ${quoteIdentifier(database)} WITH (FORCE)")
        return
    } catch (failure: java.sql.SQLException) {
        log.warn("DROP DATABASE {} WITH (FORCE) was refused ({}); ending its sessions first", database, failure.sqlState)
    }
    terminateSessions(statement, database, runtimeUser)
    statement.executeUpdate("DROP DATABASE IF EXISTS ${quoteIdentifier(database)}")
}

internal fun terminateSessions(statement: java.sql.Statement, database: String, runtimeUser: String) {
    val sql = "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ${quoteLiteral(database)} " +
        "AND pid <> pg_backend_pid() AND usesysid IS NOT NULL AND pg_has_role(current_user, usesysid, 'USAGE')"
    statement.executeQuery(sql).use { }
    val current = statement.executeQuery("SELECT current_user").use { rows -> rows.next(); rows.getString(1) }
    if (current == runtimeUser) return
    statement.execute("SET ROLE ${quoteIdentifier(runtimeUser)}")
    try {
        statement.executeQuery(sql).use { }
    } finally {
        statement.execute("SET ROLE ${quoteIdentifier(current)}")
    }
}

internal fun Connection.setProvisioningRole(role: String) {
    createStatement().use { statement ->
        statement.execute("SET ROLE ${quoteIdentifier(role)}")
    }
}

internal fun sha256(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return HexFormat.of().formatHex(bytes)
}

internal fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return HexFormat.of().formatHex(digest)
}

internal fun <T> DataSource.inCentralTransaction(block: (Connection) -> T): T = connection.use { connection ->
    connection.autoCommit = false
    try {
        block(connection).also { connection.commit() }
    } catch (failure: Throwable) {
        runCatching { connection.rollback() }
        throw failure
    } finally {
        runCatching { connection.autoCommit = true }
    }
}
