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
    // Delayed recovery by email (TWO_FACTOR.md §7.3).
    val emailRecovery: EmailRecoveryStatus = EmailRecoveryStatus(false, null),
    // How THIS session passed its second step ("totp", "recovery_code", "inherited"; null: none) (§9).
    val secondFactorMethod: String? = null,
    // Until when this session (opened with a recovery code) may replace the authenticator with the password alone.
    val replaceWindowUntil: String? = null,
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
data class RecoveryCodesResponse(
    val recoveryCodes: List<String>,
    // Enabling ended the earlier access (TWO_FACTOR.md §3): other sessions, AI agents (they must be reconnected).
    val agentsDisconnected: Int? = null,
    val notice: String? = null,
    // Some agents could not be disconnected (the owner sees it in the server audit): revoke them by hand.
    val agentsDisconnectFailed: Boolean = false,
)

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
    // Notices of a replaced authenticator (TWO_FACTOR.md §9): email and in-app.
    private val mail: () -> com.ideasdeveloper.bms.server.modules.SmtpSettings? = { null },
    private val notify: (companyCode: String?, userId: java.util.UUID, title: String, text: String) -> Unit = { _, _, _, _ -> },
    private val sendMail: (com.ideasdeveloper.bms.server.modules.SmtpSettings, String, String, String) -> Unit = { settings, to, subject, body ->
        com.ideasdeveloper.bms.server.modules.MailSender.sendText(settings, to, subject, body)
    },
    private val clock: () -> java.time.Instant = java.time.Instant::now,
) {
    companion object {
        /** After a sign-in with a recovery code, how long the session may replace the authenticator with the password alone. */
        val ReplaceWindow: java.time.Duration = java.time.Duration.ofMinutes(15)
    }

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
                emailRecovery = loginDatabases.emailRecovery?.status(connection, account.userId) ?: EmailRecoveryStatus(false, null),
                secondFactorMethod = loginDatabases.sessionSecondFactor(account.companyCode, token)?.method,
                replaceWindowUntil = replaceWindowUntil(account, token)?.toString(),
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
        // Access granted before, without the second step, ends: company sessions a server account opened with the
        // switcher, and the account's AI agents (their tokens belong to the Agentes module, told through the hook).
        if (account.companyCode == null) {
            runCatching { loginDatabases.revokeCompanySessionsOpenedBy(account.userId) }
                .onFailure { org.slf4j.LoggerFactory.getLogger(TwoFactorAccountService::class.java).error("Company sessions of {} could not be revoked", account.username, it) }
        }
        val event = com.ideasdeveloper.bms.server.auth.application.CredentialsChanged(account.companyCode, account.userId, com.ideasdeveloper.bms.server.auth.application.CredentialsChanged.TwoFactorEnabled)
        val agents = com.ideasdeveloper.bms.server.auth.application.AccountSecurityEvents.credentialsChanged(event)
        if (!agents.complete) {
            com.ideasdeveloper.bms.server.auth.application.recordGrantsNotEnded(loginDatabases.centralSource, event, account.username, agents)
            return RecoveryCodesResponse(codes, agents.ended, tr("Se cerraron tus otras sesiones, pero no se pudieron desconectar tus agentes de IA: revocalos a mano en Agentes IA."), agentsDisconnectFailed = true)
        }
        return RecoveryCodesResponse(codes, agents.ended, tr("Se cerraron tus otras sesiones y se desconectaron tus agentes de IA: vuelve a conectarlos."))
    }

    // The end of the replacement window of this session, when it is open now.
    private fun replaceWindowUntil(account: LoginDatabases.SessionAccount, token: String): java.time.Instant? {
        val mark = loginDatabases.sessionSecondFactor(account.companyCode, token) ?: return null
        if (mark.method != com.ideasdeveloper.bms.server.auth.infrastructure.database.SecondFactorRecoveryCode) return null
        return mark.at.plus(ReplaceWindow).takeIf { it.isAfter(clock()) }
    }

    /**
     * Step 1 of replacing a lost (or any) authenticator (TWO_FACTOR.md §9): the password, plus a current code
     * (authenticator or recovery code) unless this session was opened with a recovery code less than
     * [ReplaceWindow] ago. Returns the new secret; the current one keeps working until [replaceConfirm].
     */
    fun replaceSetup(token: String, companyCode: String?, currentPassword: String?, code: String?): TwoFactorSetupResponse {
        val account = account(token, companyCode)
        confirmPassword(account, currentPassword)
        val secret = account.source.connection.use { connection ->
            if (!store.isEnabled(connection, account.userId)) throw store.notEnabled()
            if (replaceWindowUntil(account, token) == null) {
                val typed = code?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw TwoFactorException("TWO_FACTOR_CODE_REQUIRED", tr("Escribe un codigo de tu autenticador o un codigo de recuperacion."))
                requireCode(store.check(connection, account.userId, typed.take(64), allowRecovery = true))
            }
            store.startReplace(connection, account.userId)
        }
        val issuer = "BMS (" + (account.companyCode ?: loginDatabases.serverName()) + ")"
        return TwoFactorSetupResponse(secret, Totp.otpauthUri(issuer, account.username, secret), issuer, account.username)
    }

    /**
     * Step 2: a code of the NEW authenticator replaces the old one; new recovery codes (the old ones stop working).
     * A credentials change: the other sessions close, the AI agents are disconnected, and the account is told.
     */
    fun replaceConfirm(token: String, companyCode: String?, code: String): RecoveryCodesResponse {
        val account = account(token, companyCode)
        val codes = account.source.connection.use { connection ->
            store.confirmReplace(connection, account.userId, code) { inTransaction ->
                inTransaction.prepareStatement(
                    "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL AND token_hash <> ?"
                ).use { revoke ->
                    revoke.setObject(1, account.userId)
                    revoke.setString(2, tokens.hash(token))
                    revoke.executeUpdate()
                }
                record(inTransaction, account, token, "two_factor.authenticator_replaced", ActivityActions.SettingsChanged, "Cambio el autenticador de la verificacion en dos pasos.")
            }
        }
        if (account.companyCode == null) {
            runCatching { loginDatabases.revokeCompanySessionsOpenedBy(account.userId) }
                .onFailure { org.slf4j.LoggerFactory.getLogger(TwoFactorAccountService::class.java).error("Company sessions of {} could not be revoked", account.username, it) }
        }
        val event = com.ideasdeveloper.bms.server.auth.application.CredentialsChanged(account.companyCode, account.userId, com.ideasdeveloper.bms.server.auth.application.CredentialsChanged.TwoFactorReplaced)
        val agents = com.ideasdeveloper.bms.server.auth.application.AccountSecurityEvents.credentialsChanged(event)
        com.ideasdeveloper.bms.server.auth.application.recordGrantsNotEnded(loginDatabases.centralSource, event, account.username, agents)
        tellReplaced(account)
        return if (agents.complete) {
            RecoveryCodesResponse(codes, agents.ended, tr("Se cambio el autenticador. Se cerraron tus otras sesiones y se desconectaron tus agentes de IA: vuelve a conectarlos."))
        } else {
            RecoveryCodesResponse(codes, agents.ended, tr("Se cambio el autenticador y se cerraron tus otras sesiones, pero no se pudieron desconectar tus agentes de IA: revocalos a mano en Agentes IA."), agentsDisconnectFailed = true)
        }
    }

    // The account hears that its authenticator changed (email in its language, and in the app).
    private fun tellReplaced(account: LoginDatabases.SessionAccount) {
        runCatching {
            val (email, language) = account.source.connection.use { connection ->
                val email = connection.prepareStatement("SELECT email FROM application_users WHERE id = ?").use { q ->
                    q.setObject(1, account.userId); q.executeQuery().use { if (it.next()) it.getString(1) else null }
                }
                email to (com.ideasdeveloper.bms.server.i18n.userLanguage(connection, account.userId) ?: com.ideasdeveloper.bms.server.i18n.I18n.Source)
            }
            com.ideasdeveloper.bms.server.i18n.I18n.withLanguage(language) {
                val subject = tr("Se cambio el autenticador de tu cuenta de BMS")
                val text = tr("El autenticador de la verificacion en dos pasos de tu cuenta {0} se cambio y se generaron codigos de recuperacion nuevos. Si no fuiste tu, avisa a tu administrador y cambia tu contrasena.", account.username)
                val settings = mail()?.takeIf { it.isUsable }
                val address = email?.trim()?.takeIf { com.ideasdeveloper.bms.server.auth.application.UserProfileRules.validEmail(it) }
                if (settings != null && address != null) sendMail(settings, address, subject, tr("Hola {0}:", account.username) + "\n\n" + text + "\n")
                notify(account.companyCode, account.userId, subject, text)
            }
        }.onFailure { org.slf4j.LoggerFactory.getLogger(TwoFactorAccountService::class.java).warn("The replaced-authenticator notice for {} failed", account.username, it) }
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
            loginDatabases.emailRecovery?.cancelOpen(connection, account.companyCode, account.userId, "disabled")
        }
    }

    /** Cancels the pending recovery by email of the signed-in account (TWO_FACTOR.md §7.3). */
    fun cancelEmailRecovery(token: String, companyCode: String?) {
        val account = account(token, companyCode)
        val cancelled = account.source.connection.use { connection ->
            loginDatabases.emailRecovery?.cancelOpen(connection, account.companyCode, account.userId, "user") == true
        }
        if (!cancelled) throw TwoFactorException("EMAIL_RECOVERY_NOT_PENDING", tr("No hay ninguna recuperacion por correo en curso."))
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
