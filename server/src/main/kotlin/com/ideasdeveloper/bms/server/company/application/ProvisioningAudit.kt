package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.admins.AuditActor
import java.sql.Connection
import java.util.UUID

/**
 * Writes provisioning_audit_log rows with who acted ([AuditActor]): actor_role is the actor's
 * login, actor_user_id and actor_kind (control V015) say who it is. Name and email are those of the
 * central user [AuditActor.userId] as they are now. Before V015 the two new columns are left out.
 * Safe inside a caller's transaction (the column check never fails).
 */
object ProvisioningAudit {
    @Volatile private var actorColumns = false

    private fun hasActorColumns(connection: Connection): Boolean {
        if (actorColumns) return true
        val found = connection.prepareStatement(
            """
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = current_schema() AND table_name = 'provisioning_audit_log' AND column_name = 'actor_kind'
            """.trimIndent()
        ).use { query -> query.executeQuery().use { it.next() } }
        if (found) actorColumns = true
        return found
    }

    fun insert(connection: Connection, actor: AuditActor, action: String, companyCode: String?, moduleId: String?, details: String) {
        val withActor = hasActorColumns(connection)
        val columns = if (withActor) ", actor_user_id, actor_kind" else ""
        val values = if (withActor) ", CAST(? AS uuid), CAST(? AS text)" else ""
        connection.prepareStatement(
            """
            INSERT INTO provisioning_audit_log (id, actor_role, action, company_code, module_id, details, actor_name, actor_email$columns)
            SELECT ?, ?, ?, ?, ?, CAST(? AS jsonb), users.display_name, users.email$values
            FROM (SELECT 1) AS one
            LEFT JOIN application_users users ON users.id = CAST(? AS uuid)
            """.trimIndent()
        ).use { insert ->
            var index = 1
            insert.setObject(index++, UUID.randomUUID())
            insert.setString(index++, actor.login.take(63))
            insert.setString(index++, action)
            insert.setString(index++, companyCode)
            insert.setString(index++, moduleId)
            insert.setString(index++, details)
            if (withActor) {
                insert.setUuid(index++, actor.userId)
                insert.setString(index++, actor.kind)
            }
            // Name and email only of a central user: a business owner's or the system's row has none.
            insert.setUuid(index, actor.userId)
            insert.executeUpdate()
        }
    }
}

private fun java.sql.PreparedStatement.setUuid(index: Int, value: UUID?) {
    if (value == null) setNull(index, java.sql.Types.OTHER) else setObject(index, value)
}
