package com.ideasdeveloper.bms.server.auth.application

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
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationSessionRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationUserRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.database.SecondFactorInherited
import com.ideasdeveloper.bms.server.auth.infrastructure.database.SecondFactorRecoveryCode
import com.ideasdeveloper.bms.server.auth.infrastructure.database.SecondFactorTotp
import com.ideasdeveloper.bms.server.auth.infrastructure.database.SessionSecondFactor
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ServerLoginMaxFailures
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ServerLoginWindow
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ServerLoginGlobalMaxFailures

/** Sign-in of one database: the server (server owners) or a company. */
internal class DatabaseLoginScope(
private val databases: LoginDatabases,
private val source: DataSource,
database: Database,
url: String,
private val server: Boolean,
) : LoginScope {
    val dataSource: DataSource get() = source
    private val UnknownLogin = "(usuario desconocido)"
    val users = ApplicationUserRepository(database)
    private val postgresCredentials = PostgresCredentialVerifier(url)
    private val authentication = AuthenticationService(postgresCredentials, users)
    private val sessions = SessionService(ApplicationSessionRepository(database), users, SessionTokenGenerator(), databases.lifetimeSeconds)

    override fun login(credentials: LoginCredentials): LoginSuccessResponse? {
        val user = authenticate(credentials)
        if (user == null) {
            // Under the existing rate limits; repeated failures of one login and address become one row.
            failedSignIns.failed(credentials.username, credentials.clientAddress.ifBlank { "desconocida" })
            return null
        }
        failedSignIns.succeeded(credentials.username, credentials.clientAddress.ifBlank { "desconocida" })
        // Two-step verification on: no session yet, the caller must send a code (TWO_FACTOR.md §2).
        if (twoFactorEnabled(user)) {
            throw databases.challenges.issue(java.util.UUID.fromString(user.id.toString()), user.postgresRole, credentials.companyCode, credentials.clientAddress,
                emailRecovery = databases.emailRecovery?.offered() == true)
        }
        return open(user, credentials.companyCode, credentials.clientAddress, null)
    }

    /** Whether the session [token] passed a second step (sign-in with a code, or inherited by a switch). False before the migration. */
    fun passedSecondFactor(token: String): Boolean = try {
        source.connection.use { connection ->
            connection.prepareStatement("SELECT second_factor_at IS NOT NULL FROM application_sessions WHERE token_hash = ? AND revoked_at IS NULL").use { query ->
                query.setString(1, databases.tokens.hash(token))
                query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
        }
    } catch (failure: java.sql.SQLException) {
        if (failure.sqlState == "42703") false else throw failure
    }

    /** The (company, user) that opened [token] with the business-owner switcher, or null (or before tenant V015). */
    fun switchOrigin(token: String): Pair<String, java.util.UUID>? = try {
        source.connection.use { connection ->
            connection.prepareStatement("SELECT switched_from_company, switched_from_user FROM application_sessions WHERE token_hash = ?").use { query ->
                query.setString(1, databases.tokens.hash(token))
                query.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val company = rows.getString(1)
                        val user = rows.getObject(2, java.util.UUID::class.java)
                        if (company != null && user != null) company to user else null
                    }
                }
            }
        }
    } catch (failure: java.sql.SQLException) {
        if (failure.sqlState == "42703") null else throw failure
    }

    fun recordSwitchOrigin(token: String, company: String, userId: java.util.UUID) {
        try {
            source.connection.use { connection ->
                connection.prepareStatement("UPDATE application_sessions SET switched_from_company = ?, switched_from_user = ? WHERE token_hash = ?").use { update ->
                    update.setString(1, company); update.setObject(2, userId); update.setString(3, databases.tokens.hash(token))
                    update.executeUpdate()
                }
            }
        } catch (failure: java.sql.SQLException) {
            if (failure.sqlState != "42703") throw failure
            databases.logger.warn("Sessions have no switched_from_company yet (tenant V015 pending): the switch origin is not recorded")
        }
    }

    /** Marks [token] as having passed a second step; without the column (before the migration) it is not marked. */
    fun markSecondFactor(token: String, method: String = SecondFactorInherited) {
        try {
            source.connection.use { connection ->
                connection.prepareStatement("UPDATE application_sessions SET second_factor_at = CURRENT_TIMESTAMP WHERE token_hash = ?").use { update ->
                    update.setString(1, databases.tokens.hash(token))
                    update.executeUpdate()
                }
                // How it was passed (V020/V016); without the column the mark alone is kept.
                try {
                    connection.prepareStatement("UPDATE application_sessions SET second_factor_method = ? WHERE token_hash = ?").use { update ->
                        update.setString(1, method)
                        update.setString(2, databases.tokens.hash(token))
                        update.executeUpdate()
                    }
                } catch (failure: java.sql.SQLException) {
                    if (failure.sqlState != "42703") throw failure
                }
            }
        } catch (failure: java.sql.SQLException) {
            if (failure.sqlState != "42703") throw failure
            databases.logger.warn("Sessions have no second_factor_at yet (migration pending): the session is not marked")
        }
    }

    /** How and when the session [token] passed its second step, or null (none, or before the migration). */
    fun secondFactorOf(token: String): SessionSecondFactor? = try {
        source.connection.use { connection ->
            connection.prepareStatement("SELECT second_factor_method, second_factor_at FROM application_sessions WHERE token_hash = ? AND revoked_at IS NULL AND second_factor_at IS NOT NULL").use { query ->
                query.setString(1, databases.tokens.hash(token))
                query.executeQuery().use { rows ->
                    if (rows.next()) SessionSecondFactor(rows.getString(1) ?: SecondFactorInherited, rows.getObject(2, java.time.OffsetDateTime::class.java).toInstant()) else null
                }
            }
        }
    } catch (failure: java.sql.SQLException) {
        if (failure.sqlState == "42703") null else throw failure
    }

    fun twoFactorEnabled(user: ApplicationUser): Boolean =
        source.connection.use { connection -> databases.twoFactor.isEnabled(connection, java.util.UUID.fromString(user.id.toString())) }

    // Opens the session of a finished sign-in and records it; without its row the sign-in does not happen.
    private fun open(user: ApplicationUser, companyCode: String?, clientAddress: String, recovery: CodeCheck.Recovery?): LoginSuccessResponse? {
        val response = respond(user, companyCode) ?: return null
        try {
            signedIn(user, clientAddress, recovery)
        } catch (failure: Throwable) {
            // Without its row the sign-in does not happen (ACTIVITY_LOG.md §1.2).
            runCatching { revoke(response.accessToken) }
            throw failure
        }
        return if (recovery == null) response else response.copy(recoveryCodeUsed = true, recoveryCodesLeft = recovery.left, lastRecoveryCodeUsed = recovery.left == 0)
    }

    /** Step 2 of a sign-in of this database (see [completeTwoFactor]). */
    fun secondStep(challengeToken: String, challenge: TwoFactorChallenges.Challenge, code: String): LoginSuccessResponse {
        val user = users.findById(kotlin.uuid.Uuid.parse(challenge.userId.toString()))?.takeIf { it.isActive }
            // Still an owner or an active administrator on the server.
            ?.takeIf { !server || isOwner(it) || databases.activeAdmin(it) != null }
        if (user == null || user.postgresRole != challenge.username) {
            databases.challenges.finish(challengeToken)
            throw TwoFactorSignInException.expired()
        }
        val result = try {
            source.connection.use { connection -> databases.twoFactor.check(connection, challenge.userId, code, allowRecovery = true) }
        } catch (failure: Throwable) {
            databases.challenges.finish(challengeToken)
            throw failure
        }
        return when (result) {
            CodeCheck.Throttled -> {
                databases.challenges.finish(challengeToken)
                throw TwoFactorException.tooManyAttempts()
            }
            CodeCheck.Invalid -> {
                failedSignIns.failed(challenge.username, challenge.clientAddress.ifBlank { "desconocida" })
                val left = databases.challenges.attemptsLeft(challenge)
                if (left == 0) databases.challenges.finish(challengeToken)
                throw TwoFactorSignInException(TwoFactorException.InvalidCode, tr("El codigo no es valido."), left)
            }
            is CodeCheck.Recovery, CodeCheck.Authenticator -> {
                databases.challenges.finish(challengeToken)
                val opened = open(user, challenge.companyCode, challenge.clientAddress, result as? CodeCheck.Recovery)
                    ?: throw TwoFactorSignInException.expired()
                // This session passed a second step: it may switch into accounts that use one.
                markSecondFactor(opened.accessToken, if (result is CodeCheck.Recovery) SecondFactorRecoveryCode else SecondFactorTotp)
                // The owner of the account clearly still has access: a pending recovery by email ends (§7.5).
                val cancelled = databases.emailRecovery?.let { recovery ->
                    runCatching { source.connection.use { recovery.cancelOpen(it, challenge.companyCode, challenge.userId, "sign_in", notifyByMail = true) } }
                        .onFailure { databases.logger.error("A pending email recovery could not be cancelled after a sign-in", it) }
                        .getOrDefault(false)
                } == true
                if (cancelled) opened.copy(emailRecoveryCancelled = true) else opened
            }
        }
    }

    /** Step 2 with the emailed recovery code (see [completeEmailRecovery]). */
    fun emailRecoveryStep(challengeToken: String, challenge: TwoFactorChallenges.Challenge, code: String, recovery: TwoFactorEmailRecovery): LoginSuccessResponse {
        val user = users.findById(kotlin.uuid.Uuid.parse(challenge.userId.toString()))?.takeIf { it.isActive }
            ?.takeIf { !server || isOwner(it) || databases.activeAdmin(it) != null }
        if (user == null || user.postgresRole != challenge.username) {
            databases.challenges.finish(challengeToken)
            throw TwoFactorSignInException.expired()
        }
        val result = source.connection.use { connection -> recovery.complete(challenge.companyCode, connection, challenge.userId, code) }
        return when (result) {
            EmailRecoveryCheck.Throttled -> {
                databases.challenges.finish(challengeToken)
                throw TwoFactorException.tooManyAttempts()
            }
            EmailRecoveryCheck.Invalid -> {
                failedSignIns.failed(challenge.username, challenge.clientAddress.ifBlank { "desconocida" })
                val left = databases.challenges.attemptsLeft(challenge)
                if (left == 0) databases.challenges.finish(challengeToken)
                throw TwoFactorSignInException(TwoFactorException.InvalidCode, tr("El codigo no es valido."), left)
            }
            EmailRecoveryCheck.Completed -> {
                databases.challenges.finish(challengeToken)
                (open(user, challenge.companyCode, challenge.clientAddress, null) ?: throw TwoFactorSignInException.expired())
                    .copy(twoFactorDisabled = true)
            }
        }
    }

    // Sign-ins: the company's activity log, or for the server provisioning_audit_log (ACTIVITY_LOG.md §3).
    private fun signedIn(user: ApplicationUser, clientAddress: String, recovery: CodeCheck.Recovery?) {
        val userId = java.util.UUID.fromString(user.id.toString())
        val address = clientAddress.ifBlank { "desconocida" }
        if (server) {
            val kind = if (isOwner(user)) AuditActor.ServerOwnerKind else AuditActor.ServerAdminKind
            databases.central.connection.use { connection ->
                val actor = AuditActor(user.postgresRole, userId, kind)
                if (recovery != null) {
                    ProvisioningAudit.insert(connection, actor, "two_factor.recovery_code_used", null, null, auditDetails("address" to address, "recoveryCodesLeft" to recovery.left))
                }
                ProvisioningAudit.insert(connection, actor, "server.signed_in", null, null, auditDetails("address" to address))
            }
            return
        }
        source.connection.use { connection ->
            val actor = CoreActivity.tenantPerson(connection, userId) ?: ActivityActor.user(userId, user.postgresRole)
            if (recovery != null) {
                CoreActivity.recordEvent(
                    connection, actor, "user", userId.toString(), user.postgresRole, ActivityActions.SettingsChanged,
                    "Inicio sesion con un codigo de recuperacion (le quedan ${recovery.left}).",
                )
            }
            CoreActivity.recordEvent(
                connection, actor, "user", userId.toString(), user.postgresRole, ActivityActions.SignedIn,
                "Inicio sesion desde $address.",
            )
        }
    }

    // Failed sign-ins of this database, coalesced (FailedSignIns).
    val failedSignIns = FailedSignIns { login, address, count, first, last ->
        // What was typed is never stored (it may be a password typed in the wrong box): only an account that
        // exists is named, by its own login; anything else is "(usuario desconocido)".
        fun summary(account: String) = if (count == 1) "Inicio de sesion fallido para $account desde $address."
            else "$count inicios de sesion fallidos para $account desde $address entre $first y $last."
        if (server) {
            val known = login?.trim()?.takeIf { it.isNotEmpty() }?.let { users.findByPostgresRole(it)?.postgresRole }
            databases.central.connection.use { connection ->
                ProvisioningAudit.insert(
                    connection, AuditActor.system(), "server.sign_in_failed", null, null,
                    auditDetails("login" to (known ?: if (login == null) "(varios usuarios)" else UnknownLogin), "address" to address, "count" to count,
                        "first" to first.toString(), "last" to last.toString()),
                )
            }
        } else {
            source.connection.use { connection ->
                // A known login names its account; an unknown one is the system's (never the password, never the text).
                val typed = login?.trim()?.takeIf { it.isNotEmpty() }
                val known = typed?.let { CoreActivity.tenantPersonByLogin(connection, it) }
                val actor = known?.let { ActivityActor(ActivityActor.Kinds.System, it.userId, "Sistema", "Sistema") } ?: ActivityActor.system()
                val account = when {
                    known != null -> "el usuario '$typed'"
                    login == null -> "varios usuarios"
                    else -> UnknownLogin
                }
                CoreActivity.recordEvent(
                    connection, actor, "user", known?.userId?.toString(), if (known != null) typed.orEmpty() else UnknownLogin,
                    ActivityActions.SignInFailed, summary(account),
                    listOf(ActivityChange("attempts", "Intentos", null, count.toString())),
                )
            }
        }
    }

    // Misma comprobacion que el login (rol de PostgreSQL para duenos, contrasena de aplicacion para el resto), sin abrir sesion.
    fun verifyCredentials(credentials: LoginCredentials): Boolean = authenticate(credentials) != null

    private fun authenticate(credentials: LoginCredentials): ApplicationUser? =
        if (server) authenticateOnServer(credentials) else authenticateInCompany(credentials)

    /**
     * Company sign-in with the per-login throttle of the server sign-in (B1): after
     * [ServerLoginMaxFailures] failures of an existing login from one client (an IPv6 /64 is one
     * client) in [ServerLoginWindow] the login is refused without checking the password (same work,
     * same answer); a success resets it. A second counter per login across addresses only delays
     * (serverLoginDelayMillis). Only existing logins are counted (bounded memory).
     */
    private fun authenticateInCompany(credentials: LoginCredentials): ApplicationUser? {
        val company = credentials.companyCode.orEmpty()
        val existing = credentials.username.takeIf { it.isNotBlank() }?.let { users.findByPostgresRole(it) }
        val throttleKey = existing?.let { databases.companyLoginKey(company, it.postgresRole, credentials.clientAddress) }
        if (throttleKey != null && databases.companyLoginAttempts.isBlocked(throttleKey)) {
            databases.spendSignInWork(credentials.password)
            return null
        }
        val result = authentication.authenticate(credentials)
        if (throttleKey != null) {
            if (result != null) {
                databases.companyLoginAttempts.reset(throttleKey)
            } else {
                databases.companyLoginAttempts.recordFailure(throttleKey)
                databases.companyLoginGlobalAttempts.recordFailure(databases.companyLoginGlobalKey(company, existing.postgresRole))
            }
        }
        return result
    }

    /**
     * Server sign-in: a server owner with their PostgreSQL role (as before), an active server
     * administrator with the PBKDF2 hash of server_admins. Every path computes one PBKDF2 hash, so
     * an unknown user, a wrong password and an inactive administrator take the same time and get
     * the same answer (null). The databases.central database has no application_user_credentials.
     */
    private fun authenticateOnServer(credentials: LoginCredentials): ApplicationUser? {
        val name = credentials.username.trim()
        val user = if (name.isEmpty() || credentials.password.isEmpty()) null else users.findByPostgresRole(name)?.takeIf { it.isActive }
        // Per-login throttle (owner and administrators): after too many failures the login is
        // refused without checking the password until the window ends. It throttles, never locks:
        // nothing is stored, attempts while throttled do not extend the window, and the right
        // password works again once it ends. Only existing users are counted (bounded memory).
        // Keyed by login AND client address, so someone who knows the owner's login cannot keep the
        // owner out from another address; a success resets the key.
        val throttleKey = user?.let { databases.serverLoginKey(it.postgresRole, credentials.clientAddress) }
        if (throttleKey != null && databases.serverLoginAttempts.isBlocked(throttleKey)) {
            databases.spendPostgresCheck(credentials.password)
            ServerAdminPasswords.spendDummyCheck(credentials.password)
            return null
        }
        // Every path also does one PostgreSQL authentication (B2): otherwise the owner's login, the only
        // one checked against PostgreSQL, would take longer than an unknown one.
        val result = if (user != null && isOwner(user)) {
            ServerAdminPasswords.spendDummyCheck(credentials.password)
            user.takeIf { postgresCredentials.verify(credentials) }
        } else {
            databases.spendPostgresCheck(credentials.password)
            val admin = user?.let { databases.activeAdmin(it) }
            if (admin == null) {
                ServerAdminPasswords.spendDummyCheck(credentials.password)
                null
            } else {
                user.takeIf { ServerAdminPasswords.verify(credentials.password, admin.passwordHash) }
            }
        }
        if (throttleKey != null) {
            if (result != null) {
                databases.serverLoginAttempts.reset(throttleKey)
            } else {
                databases.serverLoginAttempts.recordFailure(throttleKey)
                val globalKey = databases.serverLoginGlobalKey(user.postgresRole)
                databases.serverLoginGlobalAttempts.recordFailure(globalKey)
                if (databases.serverLoginGlobalAttempts.failures(globalKey) == ServerLoginGlobalMaxFailures && isOwner(user)) {
                    databases.logger.warn("Many failed server sign-ins for the owner login '{}'; attempts are now delayed", user.postgresRole)
                }
            }
        }
        return result
    }

    // The active user of a valid session of this database.
    fun sessionUser(token: String): ApplicationUser? = sessions.validateSession(token)

    // Who a company session [token] of [companyCode] is for the activity log: its user, or for a switcher session the server person too.
    private fun sessionActor(connection: java.sql.Connection, companyCode: String, user: ApplicationUser, token: String): ActivityActor {
        val userId = java.util.UUID.fromString(user.id.toString())
        val own = CoreActivity.tenantPerson(connection, userId) ?: ActivityActor.user(userId, user.postgresRole)
        val opener = databases.companySessionOpener(companyCode, token) ?: return own
        return ActivityActor.switcher(userId, own.name, opener.kind, opener.userId, opener.name)
    }

    fun recordSignOut(companyCode: String, token: String) {
        val user = sessions.validateSession(token) ?: return
        source.connection.use { connection ->
            CoreActivity.recordEvent(
                connection, sessionActor(connection, companyCode, user, token), "user", user.id.toString(), user.postgresRole,
                ActivityActions.SignedOut, "Cerro sesion.",
            )
        }
    }

    /**
     * Records that [opened] (a session of this company) entered it from elsewhere: [opener] is the
     * server person of a switcher session (null: the business owner moving within the owner group).
     * Without the row the new session is revoked and the switch fails.
     */
    fun recordEntry(opened: LoginSuccessResponse, opener: ApplicationUser?, admin: Boolean?, summary: String) {
        try {
            source.connection.use { connection ->
                val ownerId = java.util.UUID.fromString(opened.userId)
                val own = CoreActivity.tenantPerson(connection, ownerId) ?: ActivityActor.businessOwner(ownerId, opened.username)
                val actor = if (opener == null) own else {
                    val person = databases.serverPerson(java.util.UUID.fromString(opener.id.toString()))
                    ActivityActor.switcher(
                        ownerId, own.name, if (admin == true) AuditActor.ServerAdminKind else AuditActor.ServerOwnerKind,
                        person?.userId, person?.name ?: opener.postgresRole,
                    )
                }
                CoreActivity.recordEvent(connection, actor, "company", opened.companyCode, opened.companyCode.orEmpty(), ActivityActions.SwitchedCompany, summary)
            }
        } catch (failure: Throwable) {
            runCatching { revoke(opened.accessToken) }
            throw failure
        }
    }

    fun revokeHash(tokenHash: String) {
        sessions.revokeHash(tokenHash)
    }

    fun isOwner(user: ApplicationUser): Boolean = source.connection.use { connection ->
        val table = if (server) "server_owners" else "business_owner"
        connection.prepareStatement("SELECT 1 FROM $table WHERE user_id = ?").use { query ->
            query.setObject(1, java.util.UUID.fromString(user.id.toString()))
            query.executeQuery().use { it.next() }
        }
    }

    // The business owner user of this company.
    fun businessOwner(): ApplicationUser? {
        val role = source.connection.use { connection ->
            connection.prepareStatement(
                "SELECT users.postgres_role FROM business_owner owner JOIN application_users users ON users.id = owner.user_id LIMIT 1"
            ).use { query -> query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null } }
        } ?: return null
        return users.findByPostgresRole(role)
    }

    // The owner (server owner or business owner) behind a valid session of this database.
    fun sessionOwner(token: String): ApplicationUser? = sessions.validateSession(token)?.takeIf { isOwner(it) }

    fun ownerBySession(token: String): ApplicationUser? = sessionOwner(token)?.takeIf { !server }

    fun revoke(token: String) {
        sessions.revokeSession(token)
    }

    // Opens a session for [user] and describes it as the app expects after signing in.
    fun respond(user: ApplicationUser, companyCode: String?, serverOwnerAccess: Boolean = false): LoginSuccessResponse? {
        val owner = isOwner(user)
        // On the server: the owner, or an active administrator (never anyone else).
        val admin = if (server && !owner) databases.activeAdmin(user) ?: return null else null
        val session = sessions.createSession(user)
        // Administrators get no workspace modules (they have no implicit rights there); with
        // server.workspace they still get its code to manage its profile, mail and users.
        val workspace = if (server) serverWorkspace()?.let { found ->
            when {
                admin == null -> found
                ServerPermission.Workspace in admin.permissions -> found.first to emptyList()
                else -> null
            }
        } else null
        val userId = java.util.UUID.fromString(user.id.toString())
        val profile = profile(source, userId)
        // Language: the user's own (core V006), else the company default (control V014), else the source.
        val ownLanguage = source.connection.use { connection -> userLanguage(connection, userId) }
        val languageCompany = if (server) workspace?.first else companyCode
        val defaultLanguage = languageCompany?.let { code -> databases.central.connection.use { connection -> companyLanguage(connection, code) } } ?: I18n.Source
        val userKey = SessionLanguages.userKey(if (server) null else companyCode, userId)
        SessionLanguages.rememberUser(userKey, ownLanguage)
        SessionLanguages.rememberSession(session.accessToken, userKey)
        val effective = ownLanguage ?: defaultLanguage
        // The rest of this request (and its messages) already uses the language of the user.
        I18n.overrideRequestLanguage(effective)
        return LoginSuccessResponse(session.userId, session.username, session.accessToken, session.expiresInSeconds,
            scope = if (server) "server" else "company",
            companyCode = if (server) null else companyCode,
            role = if (server) (if (admin == null) ServerRoles.Owner else ServerRoles.Admin) else if (owner) "business_owner" else "user",
            enabledModules = if (server) workspace?.second.orEmpty() else visibleModules(source, java.util.UUID.fromString(user.id.toString()), owner),
            workspaceCompanyCode = workspace?.first,
            displayName = profile?.first,
            email = profile?.second,
            profileComplete = profile == null || UserProfileRules.isComplete(profile.first, profile.second),
            serverName = if (server) databases.serverName() else null,
            serverOwnerAccess = serverOwnerAccess,
            language = effective,
            companyLanguage = defaultLanguage,
            userLanguage = ownLanguage,
            serverPermissions = when {
                !server -> emptyList()
                admin == null -> ServerPermissionIds.All
                else -> admin.permissions.map { it.id }.sorted()
            },
            serverCompanies = if (admin == null || admin.allCompanies) null else admin.companies.sorted(),
            mustChangePassword = admin?.mustChangePassword == true,
            twoFactorSetupRequired = admin != null && databases.adminTwoFactorPending(userId))
    }

// Name and email of the user, or null before core migration V003 (sign-in then does not ask for them).
private fun profile(source: DataSource, userId: java.util.UUID): Pair<String?, String?>? = try {
    source.connection.use { connection ->
        connection.prepareStatement("SELECT display_name, email FROM application_users WHERE id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
        }
    }
} catch (_: java.sql.SQLException) {
    null
}

// Code and enabled modules of the server workspace, or null when it has not been created.
private fun serverWorkspace(): Pair<String, List<String>>? = try {
    val (code, databaseName) = databases.central.connection.use { connection ->
        connection.prepareStatement("SELECT code, database_name FROM companies WHERE is_server_workspace AND is_active").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
        }
    } ?: return null
    val tenant = databases.tenantConfig(databaseName) ?: return null
    val modules = org.postgresql.ds.PGSimpleDataSource().apply {
        setURL(tenant.jdbcUrl)
        user = tenant.user
        password = tenant.password
    }.connection.use { connection ->
        connection.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled' ORDER BY module_id").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }
    code to modules
} catch (_: java.sql.SQLException) {
    // Before control migration V006, or if the workspace database is unavailable, sign-in still works.
    null
}

private fun visibleModules(source: DataSource, userId: java.util.UUID, owner: Boolean): List<String> {
    return source.connection.use { connection ->
        val sql = if (owner) {
            "SELECT module_id FROM tenant_modules WHERE status = 'enabled' ORDER BY module_id"
        } else {
            """
            SELECT modules.module_id
            FROM tenant_modules modules
            WHERE modules.status = 'enabled'
              AND EXISTS (
                  SELECT 1
                  FROM application_user_permissions user_permissions
                  WHERE user_permissions.user_id = ?
                    AND user_permissions.permission_id = modules.module_id || '.view'
              )
            ORDER BY modules.module_id
            """.trimIndent()
        }
        connection.prepareStatement(sql).use { query ->
            if (!owner) query.setObject(1, userId)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(rows.getString(1))
                    }
                }
            }
        }
    }
}
}
