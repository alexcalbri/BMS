package com.ideasdeveloper.bms.server.auth.twofactor

import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.auditDetails
import com.ideasdeveloper.bms.server.security.MasterKeySecretBox
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * The server owner's rule "server administrators must use two-step verification" (server_settings,
 * no migration) and whether an administrator still has to enrol (TWO_FACTOR.md §5).
 */
object TwoFactorPolicy {
    const val RequireAdminsKey = "security.two_factor.require_admins"
    private val store = TwoFactorStore(MasterKeySecretBox(null))

    fun requireForAdmins(central: DataSource): Boolean = try {
        central.connection.use { connection ->
            connection.prepareStatement("SELECT setting_value FROM server_settings WHERE setting_key = ?").use { query ->
                query.setString(1, RequireAdminsKey)
                query.executeQuery().use { rows -> rows.next() && rows.getString(1).trim().equals("true", ignoreCase = true) }
            }
        }
    } catch (_: SQLException) {
        false
    }

    /** Whether the central user [userId] has it on (false before control V017). */
    fun enabledOnServer(central: DataSource, userId: UUID): Boolean = central.connection.use { store.isEnabled(it, userId) }

    /** An administrator the owner requires to use it and who has not turned it on yet. Call it only for administrators. */
    fun adminSetupPending(central: DataSource, userId: UUID): Boolean = requireForAdmins(central) && !enabledOnServer(central, userId)

    /** Sets the rule (audited in the same transaction). Refused before control V017: nobody could enrol. */
    fun setRequireForAdmins(central: DataSource, require: Boolean, actor: AuditActor) {
        central.connection.use { connection ->
            if (require && !store.available(connection)) throw TwoFactorException.unavailable()
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO server_settings (setting_key, setting_value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = CURRENT_TIMESTAMP
                    """.trimIndent()
                ).use { upsert ->
                    upsert.setString(1, RequireAdminsKey)
                    upsert.setString(2, require.toString())
                    upsert.executeUpdate()
                }
                ProvisioningAudit.insert(connection, actor, "server.two_factor_policy_changed", null, null, auditDetails("requireForAdmins" to require))
                connection.commit()
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            } finally {
                runCatching { connection.autoCommit = true }
            }
        }
    }
}
