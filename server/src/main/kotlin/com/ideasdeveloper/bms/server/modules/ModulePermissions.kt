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

// Permissions of the current user in one module. business_owner holds every permission.
class ModulePermissions(
    val companyCode: String,
    internal val accessToken: String,
    val businessOwner: Boolean,
    private val granted: Set<String>,
    // Signed-in user: a tenant user, or for server owners their central user id.
    val userId: UUID? = null,
    // A server owner (a central user, not in the tenant's users): in the server workspace, or in any
    // company through their AI agent (ModuleAccessService.serverOwnerAccess).
    val serverOwner: Boolean = false,
    // Who acts, for the activity log; tests pass one, Core passes [actorResolver] (read on first use).
    activityActor: ActivityActor? = null,
    private val actorResolver: (() -> ActivityActor)? = null,
) {
    /**
     * The real person behind this request for the activity log (ACTIVITY_LOG.md §1.4): the user, the
     * server person of a switcher session, "Agente de <usuario>" for an AI agent... Resolved by Core
     * on first use; pass it to ActivityLog.record / access.activity.record unchanged.
     */
    val activityActor: ActivityActor by lazy {
        activityActor ?: actorResolver?.invoke() ?: fallbackActor(businessOwner, serverOwner, userId)
    }

    /** The same permissions acting as [actor] (the agent bridge uses it; modules never need it). */
    fun withActivityActor(actor: ActivityActor): ModulePermissions =
        ModulePermissions(companyCode, accessToken, businessOwner, granted, userId, serverOwner, actor)

    fun has(permissionId: String): Boolean = businessOwner || permissionId in granted

    /** Whether the user holds any permission whose id starts with [prefix] (e.g. "crm."). */
    fun hasAnyWithPrefix(prefix: String): Boolean = businessOwner || granted.any { it.startsWith(prefix) }
}

/** What a user may do in every module of a company, loaded once per request (see ModuleAccessService.userAccess). */
class UserModuleAccess internal constructor(
    val companyCode: String,
    val userId: UUID,
    val businessOwner: Boolean,
    val serverOwner: Boolean,
    private val granted: Set<String>,
    private val enabledModules: Set<String>,
    // Who this user is for the activity log, resolved once on first use.
    private val actorResolver: (() -> ActivityActor)? = null,
    private val installed: (String) -> Boolean,
) {
    private val actor: ActivityActor by lazy { actorResolver?.invoke() ?: fallbackActor(businessOwner, serverOwner, userId) }

    /** This user as the activity log names them (without any agent). */
    val activityActor: ActivityActor get() = actor

    // The user's permissions in the module, or null when the module is not installed or not enabled.
    // The server's own tools (ServerMcpModuleId) belong to server owners only.
    fun permissions(moduleId: String): ModulePermissions? {
        val usable = if (moduleId == ServerMcpModuleId) serverOwner else (moduleId in enabledModules || moduleId in AlwaysEnabledModules) && installed(moduleId)
        return if (usable) ModulePermissions(companyCode, accessToken = "", businessOwner, granted, userId, serverOwner, actorResolver = { actor }) else null
    }
    // Whether the user can use the tool somewhere: a server owner in every company of the server
    // (ModuleAccessService.serverOwnerAccess), anyone else where the module is enabled and permitted.
    // Modules enabled and installed in the company.
    fun enabledModuleIds(): Set<String> = enabledModules.filter(installed).toSet()

    fun mayUse(tool: RegisteredMcpTool): Boolean = serverOwner || permissions(tool.moduleId)?.has(tool.tool.permission) == true
}
