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
import com.ideasdeveloper.bms.server.auth.application.DatabaseLoginScope
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
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.database.SwitchNeedsSecondFactorException
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ServerPerson
import com.ideasdeveloper.bms.server.auth.infrastructure.database.SwitcherRowMarginSeconds

/** Company switcher of [LoginDatabases]: who may move between the server and the companies, and the tracking of the sessions it opens. */
internal class CompanySwitcher(private val facade: LoginDatabases) {
    /**
     * Signs the business owner of [fromCode] (session [token]) into [toCode] as the business owner
     * of [toCode], without a password, when both companies are active and share the owner group
     * (control V008). Only the server owner puts companies in a group (duplicating with the same
     * owner or linking them), so the group says both owners are the same person even when each
     * company has its own user. Null when any of that does not hold.
     */
    internal fun switchCompany(fromCode: String, token: String, toCode: String): LoginSuccessResponse? {
        if (fromCode == toCode) return null
        val databases = facade.central.connection.use { connection ->
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
        val origin = facade.tenantScope(databases.second) ?: return null
        val originOwner = origin.ownerBySession(token) ?: return null
        // A session descended from a switcher session is itself a switcher session: the opener is
        // re-checked (for both companies, owner group included) and the new session is tracked too,
        // so it is re-checked on every use and revoked with its opener.
        val openerId = facade.openerOf(facade.tokens.hash(token), fromCode)
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
        val target = facade.tenantScope(databases.first) ?: return null
        val targetOwner = target.businessOwner()?.takeIf { it.isActive } ?: return null
        // Never a way around the second step (TWO_FACTOR.md §2): when the target owner (or, for a switcher
        // session, the server person) uses two-step verification, THIS session must have passed one.
        val passed = origin.passedSecondFactor(token)
        if (opener == null) {
            if (target.twoFactorEnabled(targetOwner) && !passed) throw SwitchNeedsSecondFactorException()
            return target.respond(targetOwner, toCode)?.also { opened ->
                if (passed) target.markSecondFactor(opened.accessToken)
                // Who is really behind the new session: the first owner of the chain of switches (agents opened from it).
                val first = origin.switchOrigin(token) ?: (fromCode to java.util.UUID.fromString(originOwner.id.toString()))
                target.recordSwitchOrigin(opened.accessToken, first.first, first.second)
                target.recordEntry(opened, null, null, "Entro a la empresa desde la empresa $fromCode del mismo dueno.")
            }
        }
        val user = when (opener) {
            is Switcher.ServerOwner -> opener.user
            is Switcher.ServerAdmin -> opener.user
            else -> return null
        }
        if ((target.twoFactorEnabled(targetOwner) || facade.serverScope.twoFactorEnabled(user)) && !passed) throw SwitchNeedsSecondFactorException()
        return target.respond(targetOwner, toCode, serverOwnerAccess = true)?.also { opened ->
            if (passed) target.markSecondFactor(opened.accessToken)
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
    internal fun trackCompanySession(target: DatabaseLoginScope, opened: LoginSuccessResponse, opener: ApplicationUser, admin: Boolean, toCode: String, ownerRole: String, fromCode: String?) {
        val actorId = java.util.UUID.fromString(opener.id.toString())
        try {
            facade.central.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO server_owner_company_sessions (token_hash, server_owner_user_id, company_code, expires_at)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP + make_interval(secs => ?))
                        """.trimIndent()
                    ).use { insert ->
                        insert.setString(1, facade.tokens.hash(opened.accessToken))
                        insert.setObject(2, actorId)
                        insert.setString(3, toCode)
                        insert.setLong(4, facade.lifetimeSeconds.coerceAtLeast(opened.expiresInSeconds) + SwitcherRowMarginSeconds)
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
    internal sealed interface Switcher {
        // A server owner: on the server ([fromCode] null) or inside a company they entered.
        class ServerOwner(val user: ApplicationUser) : Switcher
        // A server administrator: may enter only the companies of their scope, with server.companies.enter.
        class ServerAdmin(
            val user: ApplicationUser,
            val admin: ServerAdminRow,
            private val ownerGroup: (String) -> Set<String>,
            // The owner requires two-step verification and this administrator has not turned it on.
            private val twoFactorPending: Boolean = false,
        ) : Switcher {
            val mayEnter: Boolean get() = !admin.mustChangePassword && !twoFactorPending && ServerPermission.CompaniesEnter in admin.permissions

            // The company and every company of its owner group must be in scope: inside, the business
            // owner reaches the whole group (owner switcher, agents with group scope).
            fun mayEnter(code: String): Boolean = mayEnter && (admin.allCompanies || (ownerGroup(code) + code).all { it in admin.companies })
        }
        class BusinessOwner : Switcher
    }

    // An active administrator behind [user], read now.
    internal fun activeAdmin(user: ApplicationUser): ServerAdminRow? =
        facade.admins.find(java.util.UUID.fromString(user.id.toString()))?.takeIf { it.usable }

    internal fun switcher(fromCode: String?, token: String): Switcher? {
        if (fromCode == null) {
            val user = facade.serverScope.sessionUser(token) ?: return null
            if (facade.serverScope.isOwner(user)) return Switcher.ServerOwner(user)
            val admin = activeAdmin(user) ?: return null
            return if (admin.mustChangePassword) null else Switcher.ServerAdmin(user, admin, facade::ownerGroupOf, facade.adminTwoFactorPending(admin.userId))
        }
        val scope = activeDatabase(fromCode)?.let { facade.tenantScope(it) } ?: return null
        scope.sessionOwner(token) ?: return null
        val opener = facade.openerOf(facade.tokens.hash(token), fromCode) ?: return Switcher.BusinessOwner()
        return when (val who = serverSwitcher(opener)) {
            is Switcher.ServerAdmin -> if (who.mayEnter(fromCode)) who else null.also { scope.revoke(token) }
            null -> null.also { scope.revoke(token) }
            else -> who
        }
    }

    // The owner or administrator [userId] as a switcher, when still active.
    internal fun serverSwitcher(userId: java.util.UUID): Switcher? {
        val user = facade.serverScope.users.findById(kotlin.uuid.Uuid.parse(userId.toString()))?.takeIf { it.isActive } ?: return null
        if (facade.serverScope.isOwner(user)) return Switcher.ServerOwner(user)
        return activeAdmin(user)?.let { Switcher.ServerAdmin(user, it, facade::ownerGroupOf, facade.adminTwoFactorPending(it.userId)) }
    }

    /**
     * Where the session can move from [fromCode] (null: the server). A server owner may go to the
     * server ([SwitchTargetsResponse.serverName] set) and to any active company; a business owner
     * to the companies of its owner group. Null when the session cannot switch.
     */
    fun switchTargets(fromCode: String?, token: String): SwitchTargetsResponse? = when (val who = switcher(fromCode, token)) {
        null -> null
        is Switcher.ServerOwner -> SwitchTargetsResponse(facade.serverName(), fromCode, companies(null))
        // Only the companies of their scope, and none without server.companies.enter; back to the server always.
        is Switcher.ServerAdmin -> SwitchTargetsResponse(facade.serverName(), fromCode, companies(null).filter { who.mayEnter(it.code) })
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
                // The session that acts: the server session, or the company session the switcher opened.
                val acting = if (fromCode == null) facade.serverScope else activeDatabase(fromCode)?.let { facade.tenantScope(it) } ?: return null
                val passed = acting.passedSecondFactor(token)
                val serverTwoFactor = facade.serverScope.twoFactorEnabled(user)
                val response = if (toCode == null) {
                    if (serverTwoFactor && !passed) throw SwitchNeedsSecondFactorException()
                    facade.serverScope.respond(user, null)?.also { opened -> if (passed) facade.serverScope.markSecondFactor(opened.accessToken) }
                } else {
                    // An administrator enters only companies of their scope, with server.companies.enter.
                    if (admin != null && !admin.mayEnter(toCode)) return null
                    val target = activeDatabase(toCode)?.let { facade.tenantScope(it) } ?: return null
                    val owner = target.businessOwner()?.takeIf { it.isActive } ?: return null
                    if ((serverTwoFactor || target.twoFactorEnabled(owner)) && !passed) throw SwitchNeedsSecondFactorException()
                    target.respond(owner, toCode, serverOwnerAccess = true)?.also { opened ->
                        if (passed) target.markSecondFactor(opened.accessToken)
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
                    activeDatabase(fromCode)?.let { facade.tenantScope(it) }?.revoke(token)
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
        val scope = if (fromCode == null) facade.serverScope else activeDatabase(fromCode)?.let { facade.tenantScope(it) } ?: return false
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
    internal fun companies(groupOf: String?): List<SwitchTarget> = facade.central.connection.use { connection ->
        val filter = if (groupOf == null) "" else "AND owner_group = (SELECT owner_group FROM companies WHERE code = ?)"
        connection.prepareStatement("SELECT code, name FROM companies WHERE is_active AND NOT is_server_workspace $filter ORDER BY name, code").use { query ->
            groupOf?.let { query.setString(1, it) }
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(SwitchTarget(rows.getString(1), rows.getString(2))) } }
        }
    }

    internal fun activeDatabase(code: String): String? = facade.central.connection.use { connection ->
        connection.prepareStatement("SELECT database_name FROM companies WHERE code = ? AND is_active AND NOT is_server_workspace").use { query ->
            query.setString(1, code)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }
}
