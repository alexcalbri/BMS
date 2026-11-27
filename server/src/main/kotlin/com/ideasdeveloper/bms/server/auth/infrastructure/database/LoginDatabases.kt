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

class LoginDatabases(
    private val central: DataSource,
    centralDatabase: Database,
    centralUrl: String,
    tenantConfiguration: String,
    private val runtimeUser: String,
    private val runtimePassword: String,
    private val tenantJdbcUrlPrefix: String,
    private val lifetimeSeconds: Long,
    // Failed server sign-ins per login (owner and administrators), in memory; see authenticateOnServer.
    private val serverLoginAttempts: PasswordAttemptLimiter = PasswordAttemptLimiter(maxFailures = ServerLoginMaxFailures, window = ServerLoginWindow),
    // Failures per login across all addresses: never refuses, only slows (see authenticateOnServer).
    private val serverLoginGlobalAttempts: PasswordAttemptLimiter = PasswordAttemptLimiter(maxFailures = ServerLoginGlobalMaxFailures, window = ServerLoginWindow),
) : AutoCloseable {
    private val configurations = Json.decodeFromString<List<TenantDatabaseConfig>>(tenantConfiguration)
        .also { entries -> require(entries.map { it.databaseName }.distinct().size == entries.size) }
        .associateBy { it.databaseName }
        .toMutableMap()
    private val pools = mutableMapOf<String, HikariDataSource>()
    private val scopes = mutableMapOf<String, Scope>()
    private val tenantDatabaseNamePattern = Regex("[a-z][a-z0-9_]{0,62}")

    private val tokens = SessionTokenGenerator()
    private fun serverLoginKey(login: String, client: String) = "server-login/" + login.trim().lowercase() + "/" + client
    private fun serverLoginGlobalKey(login: String) = "server-login-global/" + login.trim().lowercase()
    private val logger = org.slf4j.LoggerFactory.getLogger(LoginDatabases::class.java)

    /**
     * Fixed delay to apply (non-blocking, by the route) before verifying a server sign-in whose login
     * is under a distributed guessing attack; 0 otherwise. The attempt is still verified afterwards.
     */
    fun serverLoginDelayMillis(credentials: LoginCredentials): Long =
        if (credentials.companyCode == null && serverLoginGlobalAttempts.isBlocked(serverLoginGlobalKey(credentials.username))) ServerLoginDelayMillis else 0L

    // Server administrators (control V015): read live on every sign-in, switch and company-session check.
    val admins = ServerAdminStore(central)
    private val serverScope = scope(central, centralDatabase, centralUrl, true)

    val service = ScopedLoginService(
        serverScope = serverScope,
        findActiveDatabase = { code ->
            central.connection.use { connection ->
                connection.prepareStatement("SELECT database_name FROM companies WHERE code = ? AND is_active = TRUE").use { query ->
                    query.setString(1, code)
                    query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            }
        },
        companyScope = { name -> tenantScope(name) },
        delayMillis = ::serverLoginDelayMillis,
    )

    /**
     * Whether [password] is right for [username] in [companyCode] (null: the server), with the same
     * mechanism as the login but without opening a session. For re-authenticating sensitive changes.
     */
    fun verifyPassword(companyCode: String?, username: String, password: String): Boolean {
        val scope = if (companyCode == null) {
            serverScope
        } else {
            if (!tenantDatabaseNamePattern.matches(companyCode)) return false
            val database = central.connection.use { connection ->
                connection.prepareStatement("SELECT database_name FROM companies WHERE code = ? AND is_active = TRUE").use { query ->
                    query.setString(1, companyCode)
                    query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            } ?: return false
            tenantScope(database) ?: return false
        }
        return scope.verifyCredentials(LoginCredentials(username, password, companyCode))
    }

    @Synchronized
    fun tenantConfig(databaseName: String): TenantDatabaseConfig? = configurations[databaseName]
        ?: databaseName.takeIf { it.matches(tenantDatabaseNamePattern) }?.let { inferredTenantConfig(it) }

    @Synchronized
    fun registerTenant(config: TenantDatabaseConfig) {
        configurations[config.databaseName] = config
        scopes.remove(config.databaseName)
        pools.remove(config.databaseName)?.close()
    }

    @Synchronized
    fun unregisterTenant(databaseName: String) {
        configurations.remove(databaseName)
        scopes.remove(databaseName)
        pools.remove(databaseName)?.close()
    }

    /**
     * Signs the business owner of [fromCode] (session [token]) into [toCode] as the business owner
     * of [toCode], without a password, when both companies are active and share the owner group
     * (control V008). Only the server owner puts companies in a group (duplicating with the same
     * owner or linking them), so the group says both owners are the same person even when each
     * company has its own user. Null when any of that does not hold.
     */
    fun switchCompany(fromCode: String, token: String, toCode: String): LoginSuccessResponse? {
        if (fromCode == toCode) return null
        val databases = central.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT target.database_name, origin.database_name
                FROM companies origin
                JOIN companies target ON target.owner_group = origin.owner_group
                WHERE origin.code = ? AND target.code = ?
                  AND origin.is_active AND target.is_active
                  AND NOT origin.is_server_workspace AND NOT target.is_server_workspace
                """.trimIndent()
            ).use { query ->
                query.setString(1, fromCode)
                query.setString(2, toCode)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
            }
        } ?: return null
        val origin = tenantScope(databases.second) ?: return null
        origin.ownerBySession(token) ?: return null
        // A session descended from a switcher session is itself a switcher session: the opener is
        // re-checked (for both companies, owner group included) and the new session is tracked too,
        // so it is re-checked on every use and revoked with its opener.
        val openerId = openerOf(tokens.hash(token), fromCode)
        val opener = openerId?.let { id ->
            val who = serverSwitcher(id)
            val allowed = when (who) {
                is Switcher.ServerOwner -> true
                is Switcher.ServerAdmin -> who.mayEnter(fromCode) && who.mayEnter(toCode)
                else -> false
            }
            if (!allowed) {
                if (who !is Switcher.ServerAdmin || !who.mayEnter(fromCode)) runCatching { origin.revoke(token) }
                return null
            }
            who
        }
        val target = tenantScope(databases.first) ?: return null
        val targetOwner = target.businessOwner()?.takeIf { it.isActive } ?: return null
        if (opener == null) {
            return target.respond(targetOwner, toCode)?.also { opened ->
                target.recordEntry(opened, null, null, "Entro a la empresa desde la empresa $fromCode del mismo dueno.")
            }
        }
        val user = when (opener) {
            is Switcher.ServerOwner -> opener.user
            is Switcher.ServerAdmin -> opener.user
            else -> return null
        }
        return target.respond(targetOwner, toCode, serverOwnerAccess = true)?.also { opened ->
            trackCompanySession(target, opened, user, admin = opener is Switcher.ServerAdmin, toCode, targetOwner.postgresRole, fromCode)
            target.recordEntry(opened, user, opener is Switcher.ServerAdmin, "Entro a la empresa desde la empresa $fromCode con el selector del servidor.")
        }
    }

    /**
     * Records the company session [opened] (of [toCode]) as opened from the switcher by [opener] and
     * audits it, in one transaction. The row outlives the session by [SwitcherRowMarginSeconds], so it
     * never expires while the session can still be used; rows are pruned only a day after that. If
     * the row cannot be written the session is revoked: untracked it would be a plain business owner
     * session nobody re-checks.
     */
    private fun trackCompanySession(target: Scope, opened: LoginSuccessResponse, opener: ApplicationUser, admin: Boolean, toCode: String, ownerRole: String, fromCode: String?) {
        val actorId = java.util.UUID.fromString(opener.id.toString())
        try {
            central.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO server_owner_company_sessions (token_hash, server_owner_user_id, company_code, expires_at)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP + make_interval(secs => ?))
                        """.trimIndent()
                    ).use { insert ->
                        insert.setString(1, tokens.hash(opened.accessToken))
                        insert.setObject(2, actorId)
                        insert.setString(3, toCode)
                        insert.setLong(4, lifetimeSeconds.coerceAtLeast(opened.expiresInSeconds) + SwitcherRowMarginSeconds)
                        insert.executeUpdate()
                    }
                    val actor = AuditActor(opener.postgresRole, actorId, if (admin) AuditActor.ServerAdminKind else AuditActor.ServerOwnerKind)
                    ProvisioningAudit.insert(
                        connection, actor, if (admin) "server_admin.entered_company" else "server_owner.entered_company", toCode, null,
                        auditDetails("businessOwnerUsername" to ownerRole, "from" to fromCode),
                    )
                    connection.prepareStatement(
                        "DELETE FROM server_owner_company_sessions WHERE expires_at <= CURRENT_TIMESTAMP - INTERVAL '1 day'"
                    ).use { it.executeUpdate() }
                    connection.commit()
                } catch (failure: Throwable) {
                    runCatching { connection.rollback() }
                    throw failure
                } finally {
                    runCatching { connection.autoCommit = true }
                }
            }
        } catch (failure: Throwable) {
            target.revoke(opened.accessToken)
            throw failure
        }
    }

    // Who is behind a session for the company switcher.
    private sealed interface Switcher {
        // A server owner: on the server ([fromCode] null) or inside a company they entered.
        class ServerOwner(val user: ApplicationUser) : Switcher
        // A server administrator: may enter only the companies of their scope, with server.companies.enter.
        class ServerAdmin(val user: ApplicationUser, val admin: ServerAdminRow, private val ownerGroup: (String) -> Set<String>) : Switcher {
            val mayEnter: Boolean get() = !admin.mustChangePassword && ServerPermission.CompaniesEnter in admin.permissions

            // The company and every company of its owner group must be in scope: inside, the business
            // owner reaches the whole group (owner switcher, agents with group scope).
            fun mayEnter(code: String): Boolean = mayEnter && (admin.allCompanies || (ownerGroup(code) + code).all { it in admin.companies })
        }
        class BusinessOwner : Switcher
    }

    // An active administrator behind [user], read now.
    private fun activeAdmin(user: ApplicationUser): ServerAdminRow? =
        admins.find(java.util.UUID.fromString(user.id.toString()))?.takeIf { it.usable }

    private fun switcher(fromCode: String?, token: String): Switcher? {
        if (fromCode == null) {
            val user = serverScope.sessionUser(token) ?: return null
            if (serverScope.isOwner(user)) return Switcher.ServerOwner(user)
            val admin = activeAdmin(user) ?: return null
            return if (admin.mustChangePassword) null else Switcher.ServerAdmin(user, admin, ::ownerGroupOf)
        }
        val scope = activeDatabase(fromCode)?.let { tenantScope(it) } ?: return null
        scope.sessionOwner(token) ?: return null
        val opener = openerOf(tokens.hash(token), fromCode) ?: return Switcher.BusinessOwner()
        return when (val who = serverSwitcher(opener)) {
            is Switcher.ServerAdmin -> if (who.mayEnter(fromCode)) who else null.also { scope.revoke(token) }
            null -> null.also { scope.revoke(token) }
            else -> who
        }
    }

    // The owner or administrator [userId] as a switcher, when still active.
    private fun serverSwitcher(userId: java.util.UUID): Switcher? {
        val user = serverScope.users.findById(kotlin.uuid.Uuid.parse(userId.toString()))?.takeIf { it.isActive } ?: return null
        if (serverScope.isOwner(user)) return Switcher.ServerOwner(user)
        return activeAdmin(user)?.let { Switcher.ServerAdmin(user, it, ::ownerGroupOf) }
    }

    /** Codes of the companies in the owner group of [companyCode] (itself included), active or not. */
    fun ownerGroupOf(companyCode: String): Set<String> = central.connection.use { connection ->
        connection.prepareStatement(
            "SELECT member.code FROM companies origin JOIN companies member ON member.owner_group = origin.owner_group WHERE origin.code = ?"
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    /**
     * Who opened the company session [tokenHash] of [companyCode] from the switcher, or null for any
     * other session. Not filtered by expiry: the session's own expiry decides whether it works, and a
     * row is never deleted while its session can be used (see [trackCompanySession]).
     */
    private fun openerOf(tokenHash: String, companyCode: String): java.util.UUID? = central.connection.use { connection ->
        connection.prepareStatement(
            "SELECT server_owner_user_id FROM server_owner_company_sessions WHERE token_hash = ? AND company_code = ?"
        ).use { query ->
            query.setString(1, tokenHash)
            query.setString(2, companyCode)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, java.util.UUID::class.java) else null }
        }
    }

    /**
     * Whether the company session [token] of [companyCode] may still be used. A session the company
     * switcher opened keeps working only while who opened it is an active server owner, or an active
     * administrator who still has server.companies.enter with that company in scope; otherwise it is
     * revoked now. Any other session is not affected (true). Call it on every use of a business
     * owner session of a company.
     */
    fun companySessionAllowed(companyCode: String, token: String): Boolean {
        val hash = tokens.hash(token)
        val opener = openerOf(hash, companyCode) ?: return true
        val allowed = when (val who = serverSwitcher(opener)) {
            is Switcher.ServerOwner -> true
            is Switcher.ServerAdmin -> who.mayEnter(companyCode)
            else -> false
        }
        if (!allowed) {
            // The row stays until it expires, so this session keeps failing here even if the revoke fails.
            runCatching { activeDatabase(companyCode)?.let { tenantScope(it) }?.revokeHash(hash) }
        }
        return allowed
    }

    /**
     * The server person who opened the company session [token] of [companyCode] from the switcher (the
     * real person behind it, for the activity log), or null for any other session. Read from the
     * tracking row; not a permission check (companySessionAllowed is).
     */
    fun companySessionOpener(companyCode: String, token: String): ServerPerson? {
        val opener = openerOf(tokens.hash(token), companyCode) ?: return null
        return serverPerson(opener)
    }

    /** A central user as the activity log names them: owner or administrator, display name (or login). Null when unknown. */
    fun serverPerson(userId: java.util.UUID): ServerPerson? {
        val row = central.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT users.display_name, users.postgres_role, EXISTS (SELECT 1 FROM server_owners owners WHERE owners.user_id = users.id)
                FROM application_users users WHERE users.id = ?
                """.trimIndent()
            ).use { query ->
                query.setObject(1, userId)
                query.executeQuery().use { rows ->
                    if (rows.next()) Triple(rows.getString(1), rows.getString(2), rows.getBoolean(3)) else null
                }
            }
        } ?: return null
        val name = row.first?.takeIf { it.isNotBlank() } ?: row.second
        return ServerPerson(userId, row.second, name, if (row.third) ServerRoles.Owner else ServerRoles.Admin)
    }

    /** True when the company session [token] was opened from the switcher by a server administrator (not the owner). */
    fun openedByServerAdmin(companyCode: String, token: String): Boolean {
        val opener = openerOf(tokens.hash(token), companyCode) ?: return false
        return serverSwitcher(opener) !is Switcher.ServerOwner
    }

    /** Revokes every company session that [userId] opened from the switcher (an administrator removed or deactivated). */
    fun revokeCompanySessionsOpenedBy(userId: java.util.UUID): Int {
        val rows = central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT token_hash, company_code FROM server_owner_company_sessions WHERE server_owner_user_id = ?"
            ).use { query ->
                query.setObject(1, userId)
                query.executeQuery().use { result -> buildList { while (result.next()) add(result.getString(1) to result.getString(2)) } }
            }
        }
        rows.forEach { (hash, code) ->
            // An inactive company cannot be used anyway; its sessions are revoked when it can be opened.
            central.connection.use { connection ->
                connection.prepareStatement("SELECT database_name FROM companies WHERE code = ?").use { query ->
                    query.setString(1, code)
                    query.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
                }
            }?.let { database -> tenantScope(database)?.revokeHash(hash) }
        }
        return rows.size
    }

    /**
     * Where the session can move from [fromCode] (null: the server). A server owner may go to the
     * server ([SwitchTargetsResponse.serverName] set) and to any active company; a business owner
     * to the companies of its owner group. Null when the session cannot switch.
     */
    fun switchTargets(fromCode: String?, token: String): SwitchTargetsResponse? = when (val who = switcher(fromCode, token)) {
        null -> null
        is Switcher.ServerOwner -> SwitchTargetsResponse(serverName(), fromCode, companies(null))
        // Only the companies of their scope, and none without server.companies.enter; back to the server always.
        is Switcher.ServerAdmin -> SwitchTargetsResponse(serverName(), fromCode, companies(null).filter { who.mayEnter(it.code) })
        is Switcher.BusinessOwner -> SwitchTargetsResponse(null, fromCode, companies(fromCode))
    }

    /**
     * Moves the session to [toCode] (null: back to the server, server owners only) and returns the
     * new session. A server owner enters a company as its business owner user: the session is
     * recorded in server_owner_company_sessions and audited, and the session it leaves is closed.
     */
    fun switchTo(fromCode: String?, token: String, toCode: String?): LoginSuccessResponse? {
        if (fromCode == toCode) return null
        return when (val who = switcher(fromCode, token)) {
            null -> null
            is Switcher.BusinessOwner -> toCode?.let { switchCompany(fromCode!!, token, it) }
            is Switcher.ServerOwner, is Switcher.ServerAdmin -> {
                val (user, admin) = when (who) {
                    is Switcher.ServerOwner -> who.user to null
                    is Switcher.ServerAdmin -> who.user to who
                    else -> return null
                }
                val response = if (toCode == null) {
                    serverScope.respond(user, null)
                } else {
                    // An administrator enters only companies of their scope, with server.companies.enter.
                    if (admin != null && !admin.mayEnter(toCode)) return null
                    val target = activeDatabase(toCode)?.let { tenantScope(it) } ?: return null
                    val owner = target.businessOwner()?.takeIf { it.isActive } ?: return null
                    target.respond(owner, toCode, serverOwnerAccess = true)?.also { opened ->
                        trackCompanySession(target, opened, user, admin = admin != null, toCode, owner.postgresRole, fromCode)
                        target.recordEntry(
                            opened, user, admin != null,
                            if (fromCode == null) "Entro a la empresa desde el servidor." else "Entro a la empresa desde la empresa $fromCode con el selector del servidor.",
                        )
                    }
                } ?: return null
                // Leaving a company: that session is not needed any more. Its switcher row stays until it
                // is pruned (after expiry), so the token keeps being re-checked even if the revoke failed.
                if (fromCode != null) {
                    activeDatabase(fromCode)?.let { tenantScope(it) }?.revoke(token)
                }
                response
            }
        }
    }

    /**
     * Cierra una sesion: la revoca en su base ([fromCode] null: el servidor) para que el token deje
     * de servir de inmediato. Es idempotente. La fila del selector (si la hay) se conserva hasta su poda.
     * Devuelve false solo si [fromCode] no corresponde a una empresa activa.
     */
    fun logout(fromCode: String?, token: String): Boolean {
        val scope = if (fromCode == null) serverScope else activeDatabase(fromCode)?.let { tenantScope(it) } ?: return false
        // The sign-out row is written first; the session is revoked whatever happens to it.
        try {
            if (fromCode != null) scope.recordSignOut(fromCode, token)
        } finally {
            scope.revoke(token)
        }
        // The switcher row (if any) is NOT deleted here: X-Company-Code is the caller's, and deleting
        // the row of a session of another company would turn it into an untracked business owner
        // session. Rows are pruned only after their session expired.
        return true
    }

    // Active companies (no server workspace): all of them, or those of the owner group of [groupOf].
    private fun companies(groupOf: String?): List<SwitchTarget> = central.connection.use { connection ->
        val filter = if (groupOf == null) "" else "AND owner_group = (SELECT owner_group FROM companies WHERE code = ?)"
        connection.prepareStatement("SELECT code, name FROM companies WHERE is_active AND NOT is_server_workspace $filter ORDER BY name, code").use { query ->
            groupOf?.let { query.setString(1, it) }
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(SwitchTarget(rows.getString(1), rows.getString(2))) } }
        }
    }

    private fun activeDatabase(code: String): String? = central.connection.use { connection ->
        connection.prepareStatement("SELECT database_name FROM companies WHERE code = ? AND is_active AND NOT is_server_workspace").use { query ->
            query.setString(1, code)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    // Name the server owner gave the server (Empresas > Nombre del servidor), or the default.
    fun serverName(): String = try {
        central.connection.use { connection ->
            connection.prepareStatement("SELECT setting_value FROM server_settings WHERE setting_key = ?").use { query ->
                query.setString(1, ServerNameSetting)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }
    } catch (_: java.sql.SQLException) {
        null
    }?.takeIf { it.isNotBlank() } ?: tr("Servidor")

    @Synchronized
    private fun tenantScope(name: String): Scope? {
        scopes[name]?.let { return it }
        val config = configurations[name]
            ?: name.takeIf { it.matches(tenantDatabaseNamePattern) }?.let { inferredTenantConfig(it) }
            ?: return null
        configurations[name] = config
        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            maximumPoolSize = 3
            minimumIdle = 0
            connectionTimeout = 5000
            connectionInitSql = "SET search_path TO public"
        })
        try {
            val result = scope(pool, Database.connect(pool), config.jdbcUrl, false)
            pools[name] = pool
            scopes[name] = result
            return result
        } catch (failure: Exception) {
            pool.close()
            throw failure
        }
    }

    private fun inferredTenantConfig(databaseName: String): TenantDatabaseConfig = TenantDatabaseConfig(
        databaseName = databaseName,
        jdbcUrl = tenantJdbcUrlPrefix + databaseName,
        user = runtimeUser,
        password = runtimePassword,
    )

    private fun scope(source: DataSource, database: Database, url: String, server: Boolean) = Scope(source, database, url, server)

    /** Sign-in of one database: the server (server owners) or a company. */
    private inner class Scope(private val source: DataSource, database: Database, url: String, private val server: Boolean) : LoginScope {
        private val UnknownLogin = "(usuario desconocido)"
        val users = ApplicationUserRepository(database)
        private val postgresCredentials = PostgresCredentialVerifier(url)
        private val authentication = AuthenticationService(postgresCredentials, users)
        private val sessions = SessionService(ApplicationSessionRepository(database), users, SessionTokenGenerator(), lifetimeSeconds)

        override fun login(credentials: LoginCredentials): LoginSuccessResponse? {
            val user = authenticate(credentials)
            if (user == null) {
                // Under the existing rate limits; repeated failures of one login and address become one row.
                failedSignIns.failed(credentials.username, credentials.clientAddress.ifBlank { "desconocida" })
                return null
            }
            failedSignIns.succeeded(credentials.username, credentials.clientAddress.ifBlank { "desconocida" })
            val response = respond(user, credentials.companyCode) ?: return null
            try {
                signedIn(user, credentials)
            } catch (failure: Throwable) {
                // Without its row the sign-in does not happen (ACTIVITY_LOG.md §1.2).
                runCatching { revoke(response.accessToken) }
                throw failure
            }
            return response
        }

        // Sign-ins: the company's activity log, or for the server provisioning_audit_log (ACTIVITY_LOG.md §3).
        private fun signedIn(user: ApplicationUser, credentials: LoginCredentials) {
            val userId = java.util.UUID.fromString(user.id.toString())
            val address = credentials.clientAddress.ifBlank { "desconocida" }
            if (server) {
                val kind = if (isOwner(user)) AuditActor.ServerOwnerKind else AuditActor.ServerAdminKind
                central.connection.use { connection ->
                    ProvisioningAudit.insert(connection, AuditActor(user.postgresRole, userId, kind), "server.signed_in", null, null, auditDetails("address" to address))
                }
                return
            }
            source.connection.use { connection ->
                val actor = CoreActivity.tenantPerson(connection, userId) ?: ActivityActor.user(userId, user.postgresRole)
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
                central.connection.use { connection ->
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
            if (server) authenticateOnServer(credentials) else authentication.authenticate(credentials)

        /**
         * Server sign-in: a server owner with their PostgreSQL role (as before), an active server
         * administrator with the PBKDF2 hash of server_admins. Every path computes one PBKDF2 hash, so
         * an unknown user, a wrong password and an inactive administrator take the same time and get
         * the same answer (null). The central database has no application_user_credentials.
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
            val throttleKey = user?.let { serverLoginKey(it.postgresRole, credentials.clientAddress) }
            if (throttleKey != null && serverLoginAttempts.isBlocked(throttleKey)) {
                ServerAdminPasswords.spendDummyCheck(credentials.password)
                return null
            }
            val result = if (user != null && isOwner(user)) {
                ServerAdminPasswords.spendDummyCheck(credentials.password)
                user.takeIf { postgresCredentials.verify(credentials) }
            } else {
                val admin = user?.let { activeAdmin(it) }
                if (admin == null) {
                    ServerAdminPasswords.spendDummyCheck(credentials.password)
                    null
                } else {
                    user.takeIf { ServerAdminPasswords.verify(credentials.password, admin.passwordHash) }
                }
            }
            if (throttleKey != null) {
                if (result != null) {
                    serverLoginAttempts.reset(throttleKey)
                } else {
                    serverLoginAttempts.recordFailure(throttleKey)
                    val globalKey = serverLoginGlobalKey(user.postgresRole)
                    serverLoginGlobalAttempts.recordFailure(globalKey)
                    if (serverLoginGlobalAttempts.failures(globalKey) == ServerLoginGlobalMaxFailures && isOwner(user)) {
                        logger.warn("Many failed server sign-ins for the owner login '{}'; attempts are now delayed", user.postgresRole)
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
            val opener = companySessionOpener(companyCode, token) ?: return own
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
                        val person = serverPerson(java.util.UUID.fromString(opener.id.toString()))
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
            val admin = if (server && !owner) activeAdmin(user) ?: return null else null
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
            val defaultLanguage = languageCompany?.let { code -> central.connection.use { connection -> companyLanguage(connection, code) } } ?: I18n.Source
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
                serverName = if (server) serverName() else null,
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
                mustChangePassword = admin?.mustChangePassword == true)
        }
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
        val (code, databaseName) = central.connection.use { connection ->
            connection.prepareStatement("SELECT code, database_name FROM companies WHERE is_server_workspace AND is_active").use { query ->
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
            }
        } ?: return null
        val tenant = tenantConfig(databaseName) ?: return null
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

    @Synchronized
    override fun close() {
        pools.values.forEach { it.close() }
        pools.clear()
        scopes.clear()
    }
}

const val ServerNameSetting = "server.name"

/** A server owner or administrator (central user) as the activity log names them; [kind] is server_owner or server_admin. */
data class ServerPerson(val userId: java.util.UUID, val login: String, val name: String, val kind: String)

/** Failed server sign-ins of one login allowed per [ServerLoginWindow] before it is throttled. */
const val ServerLoginMaxFailures = 10
val ServerLoginWindow: java.time.Duration = java.time.Duration.ofMinutes(5)

/** Failures of one login from any address per window after which each attempt is delayed (never refused). */
const val ServerLoginGlobalMaxFailures = 50
const val ServerLoginDelayMillis = 2000L

/** How long a switcher row outlives the company session it tracks (clock differences, rounding). */
const val SwitcherRowMarginSeconds = 3600L
const val DefaultServerName = "Servidor"
