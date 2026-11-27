package com.ideasdeveloper.bms.server.auth.twofactor

import com.ideasdeveloper.bms.ServerRoles
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.CoreActivity
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.auth.application.AccountException
import com.ideasdeveloper.bms.server.auth.application.PasswordAttemptLimiter
import com.ideasdeveloper.bms.server.auth.application.PasswordConfirmation
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.i18n.tr
import kotlinx.serialization.Serializable
import java.sql.Connection

@Serializable
data class TwoFactorStatusResponse(
    val enabled: Boolean,
    val pending: Boolean,
    val enabledAt: String?,
    val recoveryCodesLeft: Int,
    val requiredByServer: Boolean,
    val available: Boolean,
)

@Serializable
data class TwoFactorSetupResponse(
    val secret: String,
    val otpauthUri: String,
    val issuer: String,
    val accountName: String,
    val algorithm: String = "SHA1",
    val digits: Int = Totp.Digits,
    val period: Long = Totp.PeriodSeconds,
)

@Serializable
data class RecoveryCodesResponse(val recoveryCodes: List<String>)

/**
 * The signed-in user's own two-step verification (TWO_FACTOR.md §3): status, enrolment, recovery
 * codes and disabling. Changes are recorded without any secret or code: company accounts in the
 * company's activity log, server accounts in provisioning_audit_log, in the same transaction.
 */
class TwoFactorAccountService(
    private val loginDatabases: LoginDatabases,
    // Re-authentication like the login (LoginDatabases.verifyPassword).
    private val verifyPassword: (companyCode: String?, username: String, password: String) -> Boolean = loginDatabases::verifyPassword,
    // Shared with /auth/profile: failures of the current password add up in one counter.
    private val passwordAttempts: PasswordAttemptLimiter = PasswordAttemptLimiter(),
) {
    private val store get() = loginDatabases.twoFactor
    private val tokens = SessionTokenGenerator()

    private fun account(token: String, companyCode: String?): LoginDatabases.SessionAccount {
        val account = loginDatabases.sessionAccount(companyCode, token)
            ?: throw AccountException("INVALID_SESSION", tr("La sesion no es valida."))
        if (account.mustChangePassword) throw AccountException("PASSWORD_CHANGE_REQUIRED", tr("Cambia tu contrasena antes de continuar."))
        return account
    }

    private fun requiredByServer(account: LoginDatabases.SessionAccount): Boolean =
        account.serverKind == ServerRoles.Admin && TwoFactorPolicy.requireForAdmins(loginDatabases.centralSource)

    private fun confirmPassword(account: LoginDatabases.SessionAccount, currentPassword: String?) {
        PasswordConfirmation.require(
            currentPassword, passwordAttempts, "${account.companyCode.orEmpty()}/${account.username}",
            tr("Escribe tu contrasena actual."),
        ) { password -> verifyPassword(account.companyCode, account.username, password) }
    }

    fun status(token: String, companyCode: String?): TwoFactorStatusResponse {
        val account = account(token, companyCode)
        return account.source.connection.use { connection ->
            val state = store.state(connection, account.userId)
            TwoFactorStatusResponse(
                enabled = state.enabled,
                pending = state.pending,
                enabledAt = state.enabledAt?.toString(),
                recoveryCodesLeft = state.recoveryCodesLeft,
                requiredByServer = requiredByServer(account),
                available = store.available(connection) && loginDatabases.secretsAvailable,
            )
        }
    }

    fun setup(token: String, companyCode: String?, currentPassword: String?): TwoFactorSetupResponse {
        val account = account(token, companyCode)
        confirmPassword(account, currentPassword)
        val secret = account.source.connection.use { store.startSetup(it, account.userId) }
        val issuer = "BMS (" + (account.companyCode ?: loginDatabases.serverName()) + ")"
        return TwoFactorSetupResponse(secret, Totp.otpauthUri(issuer, account.username, secret), issuer, account.username)
    }

    /** Turns it on with a first code; the account's other sessions are closed (the current one stays). */
    fun enable(token: String, companyCode: String?, code: String): RecoveryCodesResponse {
        val account = account(token, companyCode)
        val codes = account.source.connection.use { connection ->
            store.enable(connection, account.userId, code) { inTransaction ->
                inTransaction.prepareStatement(
                    "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL AND token_hash <> ?"
                ).use { revoke ->
                    revoke.setObject(1, account.userId)
                    revoke.setString(2, tokens.hash(token))
                    revoke.executeUpdate()
                }
                record(inTransaction, account, token, "two_factor.enabled", ActivityActions.Enabled, "Activo la verificacion en dos pasos.")
            }
        }
        return RecoveryCodesResponse(codes)
    }

    fun regenerate(token: String, companyCode: String?, currentPassword: String?, code: String): RecoveryCodesResponse {
        val account = account(token, companyCode)
        confirmPassword(account, currentPassword)
        val codes = account.source.connection.use { connection ->
            if (!store.isEnabled(connection, account.userId)) throw store.notEnabled()
            // Only the authenticator: a recovery code does not make new recovery codes.
            requireCode(store.check(connection, account.userId, code, allowRecovery = false))
            store.regenerate(connection, account.userId) { inTransaction ->
                record(inTransaction, account, token, "two_factor.recovery_codes_regenerated", ActivityActions.SettingsChanged, "Genero nuevos codigos de recuperacion de la verificacion en dos pasos.")
            }
        }
        return RecoveryCodesResponse(codes)
    }

    fun disable(token: String, companyCode: String?, currentPassword: String?, code: String) {
        val account = account(token, companyCode)
        if (requiredByServer(account)) {
            throw TwoFactorException(TwoFactorException.RequiredByServer, tr("El dueno del servidor exige la verificacion en dos pasos a los administradores."))
        }
        confirmPassword(account, currentPassword)
        account.source.connection.use { connection ->
            if (!store.isEnabled(connection, account.userId)) throw store.notEnabled()
            val check = store.check(connection, account.userId, code, allowRecovery = true)
            requireCode(check)
            store.disable(connection, account.userId) { inTransaction ->
                record(inTransaction, account, token, "two_factor.disabled", ActivityActions.Disabled, "Desactivo la verificacion en dos pasos.")
            }
        }
    }

    private fun requireCode(check: CodeCheck) {
        when (check) {
            CodeCheck.Throttled -> throw TwoFactorException.tooManyAttempts()
            CodeCheck.Invalid -> throw TwoFactorException.invalidCode()
            else -> Unit
        }
    }

    // The row of the change, in the transaction of [connection] (the account's own database).
    private fun record(connection: Connection, account: LoginDatabases.SessionAccount, token: String, auditAction: String, activityAction: String, summary: String) {
        val companyCode = account.companyCode
        if (companyCode == null) {
            val kind = if (account.serverKind == ServerRoles.Owner) AuditActor.ServerOwnerKind else AuditActor.ServerAdminKind
            ProvisioningAudit.insert(connection, AuditActor(account.username, account.userId, kind), auditAction, null, null, "{}")
            return
        }
        val own = CoreActivity.tenantPerson(connection, account.userId) ?: ActivityActor.user(account.userId, account.username)
        val opener = loginDatabases.companySessionOpener(companyCode, token)
        val actor = if (opener == null) own else ActivityActor.switcher(account.userId, own.name, opener.kind, opener.userId, opener.name)
        CoreActivity.record(connection, actor, "user", account.userId.toString(), account.username, activityAction, summary)
    }
}
