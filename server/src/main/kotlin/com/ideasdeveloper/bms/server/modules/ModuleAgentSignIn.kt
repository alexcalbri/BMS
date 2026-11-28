package com.ideasdeveloper.bms.server.modules

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.domain.LoginSuccessResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.application.CompanyLinks
import com.ideasdeveloper.bms.server.company.application.ServerWorkspace
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.i18n.CompanyLanguages
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.SessionLanguages
import com.ideasdeveloper.bms.server.i18n.TextTranslation
import com.ideasdeveloper.bms.server.i18n.TextTranslator
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.trIn
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

// Inicio de sesion de los agentes de IA (OAuth de modulos): verificacion de credenciales, segundo paso y agente de auditoria
// de un administrador del servidor. Colaborador interno de ModuleAccessService, que conserva la API publica.
internal class ModuleAgentSignIn(
    private val owner: ModuleAccessService,
    private val central: DataSource,
    private val loginDatabases: LoginDatabases,
    private val login: (LoginCredentials) -> LoginSuccessResponse?,
    private val moduleMcp: () -> List<RegisteredModuleMcp>,
    private val tokenGenerator: SessionTokenGenerator,
) {
    /**
     * Checks a user's password like the app's login and leaves no session behind, for module pages
     * that sign users in themselves (OAuth authorization of AI agents). Without [companyCode] it
     * signs in a server owner, who then acts in the server workspace. Null when rejected or when a
     * server owner has no server workspace, and for an account with two-step verification on (use
     * [signInForAgent], which asks for the code). Callers must rate limit attempts. [clientAddress]
     * is recorded with the sign-in and keys the per-login throttle (clientAddress(call) of the page).
     */
    fun verifyCredentials(companyCode: String?, username: String, password: String, allowAuditAdmin: Boolean = false, clientAddress: String = ""): VerifiedUser? =
        when (val result = signInForAgent(companyCode, username, password, null, clientAddress, allowAuditAdmin)) {
            is AgentSignIn.Verified -> result.user
            else -> null
        }

    /** [verifyCredentials] for the request [call] (its client address is recorded). */
    fun verifyCredentials(call: ApplicationCall, companyCode: String?, username: String, password: String, allowAuditAdmin: Boolean = false): VerifiedUser? =
        verifyCredentials(companyCode, username, password, allowAuditAdmin, com.ideasdeveloper.bms.server.http.ClientAddresses.of(call))

    /**
     * The sign-in of an AI agent's OAuth page with the second step (TWO_FACTOR.md §2): password and,
     * for an account with two-step verification on, [twoFactorCode] (authenticator or recovery code).
     * Without the code such an account gets [AgentSignIn.SecondFactorRequired] (ask for it and call
     * again with both); a wrong code [AgentSignIn.InvalidCode]; too many [AgentSignIn.TooManyAttempts].
     */
    fun signInForAgent(
        companyCode: String?,
        username: String,
        password: String,
        twoFactorCode: String?,
        clientAddress: String,
        allowAuditAdmin: Boolean = false,
    ): AgentSignIn {
        val session = try {
            login(LoginCredentials(username, password, companyCode, clientAddress))
        } catch (required: com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorRequiredException) {
            val code = twoFactorCode?.trim()?.takeIf { it.isNotEmpty() }
            if (code == null) {
                loginDatabases.finishTwoFactor(required.token)
                return AgentSignIn.SecondFactorRequired
            }
            try {
                loginDatabases.completeTwoFactor(required.token, code, clientAddress)
            } catch (_: com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorSignInException) {
                loginDatabases.finishTwoFactor(required.token)
                return AgentSignIn.InvalidCode
            } catch (refused: com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorException) {
                return if (refused.code == com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorException.TooManyAttempts) AgentSignIn.TooManyAttempts else AgentSignIn.Rejected
            }
        } ?: return AgentSignIn.Rejected
        return verifiedFrom(session, companyCode, allowAuditAdmin)?.let { AgentSignIn.Verified(it) } ?: AgentSignIn.Rejected
    }

    /**
     * [signInForAgent] for the request [call]: its client address (X-Real-IP behind the local Nginx, the same rule
     * as the app's sign-in) is recorded with the sign-in, keys the per-login throttle and binds the second step.
     */
    fun signInForAgent(
        call: ApplicationCall,
        companyCode: String?,
        username: String,
        password: String,
        twoFactorCode: String?,
        allowAuditAdmin: Boolean = false,
    ): AgentSignIn = signInForAgent(companyCode, username, password, twoFactorCode, com.ideasdeveloper.bms.server.http.ClientAddresses.of(call), allowAuditAdmin)

    private fun verifiedFrom(session: LoginSuccessResponse, companyCode: String?, allowAuditAdmin: Boolean): VerifiedUser? {
        val serverOwner = session.scope == "server"
        // Server administrators never act here with server rights: no agent or module does. The one
        // exception (ACTIVITY_LOG.md §7): with [allowAuditAdmin], an administrator holding
        // server.audit.view may connect an agent that gets ONLY the two audit read tools
        // (serverAuditAgent); the session is closed either way.
        if (serverOwner && session.role != com.ideasdeveloper.bms.ServerRoles.Owner) {
            central.connection.use { connection ->
                connection.prepareStatement("UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE token_hash = ?").use {
                    it.setString(1, tokenGenerator.hash(session.accessToken))
                    it.executeUpdate()
                }
            }
            if (!allowAuditAdmin || session.mustChangePassword || session.twoFactorSetupRequired) return null
            val adminId = UUID.fromString(session.userId)
            serverAuditAgent(adminId) ?: return null
            val workspace = activeServerWorkspace() ?: return null
            return VerifiedUser(adminId, session.username, workspace, serverOwner = false, serverAdmin = true)
        }
        val tokenHash = tokenGenerator.hash(session.accessToken)
        val revoke = "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE token_hash = ?"
        if (serverOwner) {
            central.connection.use { connection -> connection.prepareStatement(revoke).use { it.setString(1, tokenHash); it.executeUpdate() } }
        } else {
            tenantConnectionForCompany(companyCode!!)?.use { connection -> connection.prepareStatement(revoke).use { it.setString(1, tokenHash); it.executeUpdate() } }
        }
        val workspace = if (serverOwner) session.workspaceCompanyCode ?: return null else companyCode!!
        return VerifiedUser(UUID.fromString(session.userId), session.username, workspace, serverOwner)
    }

    /**
     * What the AI agent of server administrator [userId] may use now (ACTIVITY_LOG.md §7): only the
     * two audit read tools of the server MCP (list_server_audit, list_company_activity), never any
     * other server tool, module tool, company or serverOwner flag. Null unless the user is an active
     * administrator (not the owner) holding server.audit.view without a pending password change.
     * Read live: call it on every request and every tool call.
     */
    fun serverAuditAgent(userId: UUID): ServerAuditAgent? {
        val admin = loginDatabases.admins.find(userId)
            ?.takeIf { it.usable && !it.mustChangePassword && com.ideasdeveloper.bms.server.admins.ServerPermission.AuditView in it.permissions }
            ?: return null
        if (isActiveServerOwner(userId)) return null
        // The owner requires two-step verification and this administrator has not turned it on yet.
        if (loginDatabases.adminTwoFactorPending(userId)) return null
        val tools = moduleMcp().filter { it.moduleId == ServerMcpModuleId }.flatMap { it.tools }
            .filter { it.qualifiedName in com.ideasdeveloper.bms.server.activity.ServerAuditMcpTools.AdminAgentToolNames }
        val name = admin.displayName?.takeIf { it.isNotBlank() } ?: admin.login
        val permissions = ModulePermissions(
            ServerWorkspace.Code, accessToken = "", businessOwner = false, granted = emptySet(), userId = userId, serverOwner = false,
            activityActor = ActivityActor.serverAdmin(userId, name),
        )
        return ServerAuditAgent(userId, admin.login, name, tools, permissions)
    }

    /** Audits an event of a server administrator's agent (connected, revoked, refused) in provisioning_audit_log. */
    fun auditServerAdminAgent(userId: UUID, login: String, action: String, details: Map<String, Any?>) {
        central.connection.use { connection ->
            com.ideasdeveloper.bms.server.company.application.ProvisioningAudit.insert(
                connection, com.ideasdeveloper.bms.server.admins.AuditActor(login, userId, com.ideasdeveloper.bms.server.admins.AuditActor.ServerAdminKind),
                action, null, null, com.ideasdeveloper.bms.server.company.application.auditDetails(*details.toList().toTypedArray()),
            )
        }
    }

    private fun activeServerWorkspace(): String? = central.connection.use { connection ->
        connection.prepareStatement("SELECT code FROM companies WHERE is_server_workspace AND is_active").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    private fun tenantConnectionForCompany(companyCode: String): Connection? = owner.tenantConnectionForCompany(companyCode)

    private fun isActiveServerOwner(userId: UUID): Boolean = owner.isActiveServerOwner(userId)
}
