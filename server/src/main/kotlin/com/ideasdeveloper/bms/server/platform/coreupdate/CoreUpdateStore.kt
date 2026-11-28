package com.ideasdeveloper.bms.server.platform.coreupdate

import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.auditDetails
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Implementacion JDBC sobre la base central. */
class JdbcCoreUpdateStore(private val central: DataSource) : CoreUpdateStore {
    override fun latest(): CoreUpdateRow? = central.connection.use { connection ->
        connection.prepareStatement("$Select ORDER BY id DESC LIMIT 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.toRow() else null }
        }
    }

    override fun insertPending(targetVersion: String, fromVersion: String, requestedBy: String, targetCommit: String): CoreUpdateRow =
        central.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val row = connection.prepareStatement(
                    """
                    INSERT INTO core_update_requests (target_version, from_version, requested_by, target_commit)
                    VALUES (?, ?, ?, ?)
                    RETURNING $Columns
                    """.trimIndent()
                ).use { insert ->
                    insert.setString(1, targetVersion)
                    insert.setString(2, fromVersion)
                    insert.setString(3, requestedBy)
                    insert.setString(4, targetCommit)
                    insert.executeQuery().use { rows ->
                        rows.next()
                        rows.toRow()
                    }
                }
                audit(connection, requestedBy, row)
                connection.commit()
                row
            } catch (failure: SQLException) {
                runCatching { connection.rollback() }
                // Indice unico: ya hay una solicitud pending o running.
                if (failure.sqlState == UniqueViolation) throw CoreUpdateActiveException()
                throw failure
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            } finally {
                runCatching { connection.autoCommit = previousAutoCommit }
            }
        }

    // [requestedBy] is the login of the server owner or administrator who asked (never the executing role).
    private fun audit(connection: Connection, requestedBy: String, row: CoreUpdateRow) {
        val (userId, owner) = connection.prepareStatement(
            "SELECT users.id, EXISTS (SELECT 1 FROM server_owners owners WHERE owners.user_id = users.id) FROM application_users users WHERE users.postgres_role = ?"
        ).use { query ->
            query.setString(1, requestedBy)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) to rows.getBoolean(2) else null to false }
        }
        val kind = when {
            userId == null -> com.ideasdeveloper.bms.server.admins.AuditActor.SystemKind
            owner -> com.ideasdeveloper.bms.server.admins.AuditActor.ServerOwnerKind
            else -> com.ideasdeveloper.bms.server.admins.AuditActor.ServerAdminKind
        }
        ProvisioningAudit.insert(
            connection, com.ideasdeveloper.bms.server.admins.AuditActor(requestedBy, userId, kind), "server.core_update_requested", null, null,
            auditDetails("from" to row.fromVersion, "to" to row.targetVersion, "commit" to row.targetCommit, "requestId" to row.id),
        )
    }

    private fun ResultSet.toRow() = CoreUpdateRow(
        id = getLong("id"),
        targetVersion = getString("target_version"),
        fromVersion = getString("from_version"),
        status = getString("status"),
        requestedBy = getString("requested_by"),
        requestedAt = getTimestamp("requested_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        finishedAt = getTimestamp("finished_at")?.toInstant(),
        message = getString("message"),
        logTail = getString("log_tail"),
        targetCommit = getString("target_commit"),
        signatureStatus = getString("signature_status"),
    )

    private companion object {
        const val UniqueViolation = "23505"
        const val Columns = "id, target_version, from_version, status, requested_by, requested_at, " +
            "started_at, finished_at, message, log_tail, target_commit, signature_status"
        const val Select = "SELECT $Columns FROM core_update_requests"
    }
}
