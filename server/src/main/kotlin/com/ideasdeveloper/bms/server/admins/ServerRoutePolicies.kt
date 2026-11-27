package com.ideasdeveloper.bms.server.admins

import com.ideasdeveloper.bms.server.company.api.CompanyErrorResponse
import com.ideasdeveloper.bms.server.i18n.tr
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a scoped route takes its company from. */
enum class CompanyFrom { None, Path, Header }

sealed interface RoutePolicy {
    /** Only the server owner (no permission exists). The default for any route not in the table. */
    data object OwnerOnly : RoutePolicy

    /** Any server owner or administrator; [duringPasswordChange] also lets in one who must change the password. */
    data class AnyServerUser(val duringPasswordChange: Boolean = false) : RoutePolicy

    /**
     * An administrator needs [permission] and, with [company] set, that company in scope. When the
     * company is the server workspace, [workspace] is required instead (the workspace is not a company).
     */
    data class Requires(
        val permission: ServerPermission,
        val company: CompanyFrom = CompanyFrom.None,
        val workspace: ServerPermission? = null,
    ) : RoutePolicy
}

/**
 * Every server-level route and what it needs (SERVER_ADMINS.md §3/§4). Keys are "METHOD /path" as
 * Ktor declares the route. Deny by default: a route that is not listed is owner only, and the route
 * matrix test fails for any server-level route missing here.
 */
object ServerRoutePolicies {
    private fun scoped(permission: ServerPermission, from: CompanyFrom = CompanyFrom.Path) = RoutePolicy.Requires(permission, from)
    private fun needs(permission: ServerPermission) = RoutePolicy.Requires(permission)
    private val owner = RoutePolicy.OwnerOnly

    private val extensionPaths = listOf(
        "GET" to "/extensions",
        "POST" to "/extensions/{id}/install",
        "PUT" to "/extensions/{id}/settings",
        "PUT" to "/extensions/{id}/enabled",
        "GET" to "/extensions/{id}/uninstall-preview",
        "POST" to "/extensions/{id}/uninstall",
        "POST" to "/extensions/{id}/group/preview",
        "POST" to "/extensions/{id}/group/apply",
        "POST" to "/extensions/{id}/group/operations/{operationId}/retry",
    )

    val all: Map<String, RoutePolicy> = buildMap {
        // Companies
        put("GET /companies", needs(ServerPermission.CompaniesView))
        put("POST /companies", needs(ServerPermission.CompaniesCreate))
        put("PUT /companies/{code}", scoped(ServerPermission.CompaniesEdit))
        put("PUT /companies/{code}/status", scoped(ServerPermission.CompaniesStatus))
        put("DELETE /companies/{code}", owner)
        put("PUT /companies/{code}/modules/{moduleId}", RoutePolicy.Requires(ServerPermission.CompaniesModules, CompanyFrom.Path, ServerPermission.Workspace))
        put("POST /companies/{code}/duplicate", scoped(ServerPermission.CompaniesCreate))
        put("PUT /companies/{code}/link", owner)
        put("GET /companies/{code}/business-owner", scoped(ServerPermission.CompaniesEdit))
        put("PUT /companies/{code}/business-owner", scoped(ServerPermission.CompaniesEdit))
        put("PUT /companies/{code}/owner-password", scoped(ServerPermission.CompaniesOwnerPassword))
        // Extensions inside a company (by path, or the company of X-Company-Code with a server session)
        extensionPaths.forEach { (method, path) ->
            put("$method /companies/{code}$path", scoped(ServerPermission.CompaniesExtensions))
            put("$method /companies/current$path", scoped(ServerPermission.CompaniesExtensions, CompanyFrom.Header))
        }
        put("GET /companies/current/group", scoped(ServerPermission.CompaniesExtensions, CompanyFrom.Header))
        put("GET /companies/{code}/group", scoped(ServerPermission.CompaniesExtensions))
        put("PUT /companies/{code}/group", owner)
        put("DELETE /companies/{code}/group", owner)
        put("POST /companies/{code}/group/review", owner)
        // Server modules
        put("GET /server/modules", needs(ServerPermission.ModulesView))
        put("GET /server/modules/catalog", needs(ServerPermission.ModulesView))
        put("PUT /server/modules/catalog", owner)
        put("POST /server/modules/{moduleId}/install", needs(ServerPermission.ModulesInstall))
        put("GET /server/modules/{moduleId}/inspect", needs(ServerPermission.ModulesInstall))
        put("POST /server/modules/{moduleId}/authorize-unsigned", needs(ServerPermission.ModulesAuthorizeUnsigned))
        put("DELETE /server/modules/{moduleId}", needs(ServerPermission.ModulesRemove))
        // Server workspace
        put("POST /server/workspace", needs(ServerPermission.Workspace))
        put("PUT /server/modules/{moduleId}/server-usage", needs(ServerPermission.Workspace))
        // Extension packages (catalog only; ZIP and URL packages are for the owner)
        put("GET /server/extensions/catalog", needs(ServerPermission.ExtensionsInstall))
        put("GET /server/extensions/{id}/status", needs(ServerPermission.ExtensionsInstall))
        put("GET /server/extensions/{id}/inspect", needs(ServerPermission.ExtensionsInstall))
        put("POST /server/extensions/{id}/install", needs(ServerPermission.ExtensionsInstall))
        put("DELETE /server/extensions/{id}", needs(ServerPermission.ExtensionsInstall))
        put("POST /server/extensions/{id}/authorize-unsigned", needs(ServerPermission.ExtensionsAuthorizeUnsigned))
        put("POST /server/extensions/upload", owner)
        put("POST /server/extensions/from-url", owner)
        put("POST /server/extensions/staged/{token}/authorize-unsigned", owner)
        put("POST /server/extensions/staged/{token}/install", owner)
        // Server
        put("GET /server/core-update", needs(ServerPermission.CoreUpdate))
        put("POST /server/core-update", needs(ServerPermission.CoreUpdate))
        put("GET /server/logs", needs(ServerPermission.LogsView))
        put("PUT /server/name", needs(ServerPermission.Settings))
        put("GET /server/audit", needs(ServerPermission.AuditView))
        // Companies' activity logs: the optional ?company= is checked against the scope by ServerActivity.
        put("GET /server/activity", needs(ServerPermission.AuditView))
        put("GET /server/activity/export", needs(ServerPermission.AuditView))
        // Integrity of the companies' logs (hash chains, anchors, hardening): the owner only.
        put("GET /server/activity/verify", owner)
        // Administrators: only the owner manages them.
        put("GET /server/admin-permissions", owner)
        put("GET /server/admins", owner)
        put("POST /server/admins", owner)
        put("GET /server/admins/{id}", owner)
        put("PUT /server/admins/{id}", owner)
        put("DELETE /server/admins/{id}", owner)
        put("PUT /server/admins/{id}/permissions", owner)
        put("PUT /server/admins/{id}/password", owner)
        put("PUT /server/admins/{id}/status", owner)
        // The caller itself
        put("GET /server/me", RoutePolicy.AnyServerUser(duringPasswordChange = true))
        put("PUT /auth/password", RoutePolicy.AnyServerUser(duringPasswordChange = true))
    }

    fun of(key: String?): RoutePolicy = key?.let { all[it] } ?: RoutePolicy.OwnerOnly
}

/** "METHOD /path" of a routing node, as the routes declare it (other selectors are ignored). */
fun routeKey(node: RoutingNode): String? {
    var method: String? = null
    val segments = ArrayDeque<String>()
    var current: RoutingNode? = node
    while (current != null) {
        when (val selector = current.selector) {
            is HttpMethodRouteSelector -> if (method == null) method = selector.method.value
            is PathSegmentConstantRouteSelector -> segments.addFirst(selector.value)
            is PathSegmentParameterRouteSelector -> segments.addFirst("${selector.prefix.orEmpty()}{${selector.name}}${selector.suffix.orEmpty()}")
            is PathSegmentOptionalParameterRouteSelector -> segments.addFirst("{${selector.name}?}")
            is PathSegmentWildcardRouteSelector -> segments.addFirst("*")
            else -> Unit
        }
        current = current.parent
    }
    return method?.let { "$it /" + segments.joinToString("/") }
}

/** Outcome of checking a server session against the policy of a route. */
sealed interface ServerGate {
    data class Allowed(val actor: ServerActor) : ServerGate
    data class Denied(val status: HttpStatusCode, val code: String, val message: String) : ServerGate
}

fun ApplicationCall.bearerToken(): String? = request.headers[HttpHeaders.Authorization]
    ?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Checks the server actor of [lookup] against [policy]. [notServerUser] is the message for a
 * caller that is no owner or administrator (the route's former SERVER_OWNER_REQUIRED text).
 */
fun ApplicationCall.checkServerGate(lookup: ServerActorLookup, policy: RoutePolicy, notServerUser: String): ServerGate {
    val actor = when (lookup) {
        ServerActorLookup.None -> return ServerGate.Denied(HttpStatusCode.Forbidden, "SERVER_OWNER_REQUIRED", notServerUser)
        ServerActorLookup.NoExecutor -> return ServerGate.Denied(HttpStatusCode.ServiceUnavailable, "SERVER_OWNER_UNAVAILABLE", tr("El servidor no tiene un dueno activo."))
        is ServerActorLookup.PasswordChangeRequired -> {
            if (policy is RoutePolicy.AnyServerUser && policy.duringPasswordChange) lookup.actor
            else return ServerGate.Denied(HttpStatusCode.Forbidden, "PASSWORD_CHANGE_REQUIRED", tr("Cambia tu contrasena antes de continuar."))
        }
        is ServerActorLookup.Found -> lookup.actor
    }
    return try {
        when (policy) {
            RoutePolicy.OwnerOnly -> actor.requireOwner()
            is RoutePolicy.AnyServerUser -> Unit
            is RoutePolicy.Requires -> {
                val company = when (policy.company) {
                    CompanyFrom.None -> null
                    CompanyFrom.Path -> parameters["code"].orEmpty().trim()
                    CompanyFrom.Header -> request.headers["X-Company-Code"].orEmpty().trim()
                }
                when {
                    company == null -> actor.require(policy.permission)
                    // The server workspace is never "a company in scope": only a policy that names the
                    // workspace permission opens it (an all-company administrator included).
                    isServerWorkspace(company) -> when {
                        actor.isOwner -> Unit
                        policy.workspace != null -> actor.require(policy.workspace)
                        else -> throw ServerPermissionException(policy.permission, company)
                    }
                    else -> actor.requireCompany(policy.permission, company)
                }
            }
        }
        ServerGate.Allowed(actor)
    } catch (refusal: ServerPermissionException) {
        ServerGate.Denied(HttpStatusCode.Forbidden, refusal.code, refusal.message.orEmpty())
    }
}

/** Policy of the route that is answering this call (owner only when it cannot be told). */
fun ApplicationCall.routePolicy(): RoutePolicy = ServerRoutePolicies.of((this as? RoutingCall)?.route?.let(::routeKey))

/**
 * The server actor allowed to use this route, or null after answering the refusal. Every
 * server-level handler calls this first; what the route needs comes from [ServerRoutePolicies].
 */
suspend fun ApplicationCall.serverActor(resolver: ServerActorResolver, notServerUser: String): ServerActor? {
    val token = bearerToken()
    val lookup = if (token == null) ServerActorLookup.None else withContext(Dispatchers.IO) { resolver.resolve(token) }
    return when (val gate = checkServerGate(lookup, routePolicy(), notServerUser)) {
        is ServerGate.Allowed -> gate.actor
        is ServerGate.Denied -> {
            respond(gate.status, CompanyErrorResponse(gate.code, gate.message))
            null
        }
    }
}
