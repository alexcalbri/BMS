package com.ideasdeveloper.bms.server.auth.infrastructure.database

import com.ideasdeveloper.bms.ServerPermissionIds
import com.ideasdeveloper.bms.ServerRoles
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.CoreActivity
import com.ideasdeveloper.bms.server.activity.FailedSignIns
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.admins.ServerAdminPasswords
import com.ideasdeveloper.bms.server.admins.ServerAdminRow
import com.ideasdeveloper.bms.server.admins.ServerAdminStore
import com.ideasdeveloper.bms.server.admins.ServerPermission
import com.ideasdeveloper.bms.server.auth.application.AuthenticationService
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.auditDetails
import com.ideasdeveloper.bms.server.auth.application.PasswordAttemptLimiter
import com.ideasdeveloper.bms.server.auth.application.SessionService
import com.ideasdeveloper.bms.server.auth.application.ScopedLoginService
import com.ideasdeveloper.bms.server.auth.application.UserProfileRules
import com.ideasdeveloper.bms.server.http.ClientAddresses
import com.ideasdeveloper.bms.server.auth.twofactor.CodeCheck
import com.ideasdeveloper.bms.server.auth.twofactor.EmailRecoveryCheck
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorEmailRecovery
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorChallenges
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorPolicy
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorSignInException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorStore
import com.ideasdeveloper.bms.server.security.MasterKeySecretBox
import com.ideasdeveloper.bms.server.security.SecretBox
import com.ideasdeveloper.bms.server.auth.domain.ApplicationUser
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.domain.LoginScope
import com.ideasdeveloper.bms.server.auth.domain.LoginSuccessResponse
import com.ideasdeveloper.bms.server.auth.domain.SwitchTarget
import com.ideasdeveloper.bms.server.auth.domain.SwitchTargetsResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.security.PostgresCredentialVerifier
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.SessionLanguages
import com.ideasdeveloper.bms.server.i18n.companyLanguage
import com.ideasdeveloper.bms.server.i18n.userLanguage
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

/** Credentials come only from server configuration, never from the client. */
@Serializable
class TenantDatabaseConfig(val databaseName: String, val jdbcUrl: String, val user: String, val password: String)

/** How ([method]: totp, recovery_code, inherited) and when a session passed its second step. */
data class SessionSecondFactor(val method: String, val at: java.time.Instant)

/** Who really opened a session: [Server] (central user [userId]) or [Company] (owner [userId] of [companyCode]). */
data class SessionOpener(val kind: String, val userId: java.util.UUID, val companyCode: String?) {
    companion object {
        const val Server = "server"
        const val Company = "company"
    }
}

/** A switch into an account that uses two-step verification from a session that did not pass a second step. */
class SwitchNeedsSecondFactorException : Exception(
    tr("Para entrar ahi tienes que haber iniciado sesion con tu codigo de verificacion en dos pasos. Cierra sesion y vuelve a entrar con tu codigo."),
)

/** A server owner or administrator (central user) as the activity log names them; [kind] is server_owner or server_admin. */
data class ServerPerson(val userId: java.util.UUID, val login: String, val name: String, val kind: String)
