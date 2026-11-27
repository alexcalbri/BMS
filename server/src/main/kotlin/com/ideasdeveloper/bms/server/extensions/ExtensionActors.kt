package com.ideasdeveloper.bms.server.extensions

import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerActorLookup
import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.admins.ServerGate
import com.ideasdeveloper.bms.server.admins.bearerToken
import com.ideasdeveloper.bms.server.admins.checkServerGate
import com.ideasdeveloper.bms.server.admins.routePolicy
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleAccessDecision
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.UserSessionLookup
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of recognising who calls an extension route: an actor, or the refusal to answer with. */
sealed class ExtensionActorLookup {
    data class Found(val actor: ExtensionActor) : ExtensionActorLookup()
    data class Denied(val decision: ModuleAccessDecision) : ExtensionActorLookup()
}

/**
 * Recognises server owners and server administrators (their central session, checked against
 * ServerRoutePolicies like the routes of /companies) and business owners (the session of the
 * company in X-Company-Code, which must belong to a business_owner).
 */
class ExtensionActors(
    private val userSession: (ApplicationCall) -> UserSessionLookup,
    private val resolver: ServerActorResolver,
    // PostgreSQL role of the user when it is an active business owner of the company, else null.
    private val businessOwnerRole: (companyCode: String, userId: java.util.UUID) -> String?,
    // Who the business owner session is for the activity log (switcher sessions name the server person).
    private val sessionActor: (companyCode: String, userId: java.util.UUID, token: String) -> com.ideasdeveloper.bms.server.activity.ActivityActor? = { _, _, _ -> null },
) {
    constructor(access: ModuleAccessService, resolver: ServerActorResolver, connections: ExtensionConnections) : this(
        { call -> access.userSession(call) },
        resolver,
        { companyCode, userId -> businessOwnerRoleIn(connections, companyCode, userId) },
        { companyCode, userId, token -> access.sessionActor(companyCode, token, userId, businessOwner = true) },
    )

    /** The server owner, or an administrator the route's policy lets in, behind the Bearer token; or a refusal. */
    suspend fun serverOwner(call: ApplicationCall): ExtensionActorLookup {
        val lookup = serverLookup(call)
        return when (val gate = call.checkServerGate(lookup, call.routePolicy(), tr("Debes iniciar sesion como server_owner."))) {
            is ServerGate.Allowed -> ExtensionActorLookup.Found(gate.actor.toExtensionActor(null))
            is ServerGate.Denied -> ExtensionActorLookup.Denied(ModuleAccessDecision.Forbidden(gate.code, gate.message))
        }
    }

    /**
     * Who acts in the company of X-Company-Code: the business owner of that company, or a server
     * owner (who acts in any company), or an administrator with the permission and that company in
     * scope. The actor's companyCode is that company.
     */
    suspend fun currentCompany(call: ApplicationCall): ExtensionActorLookup {
        val token = call.bearerToken()
            ?: return ExtensionActorLookup.Denied(ModuleAccessDecision.Unauthorized("missing_token", tr("La sesion es requerida.")))
        val company = call.request.headers["X-Company-Code"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ExtensionActorLookup.Denied(ModuleAccessDecision.Unauthorized("missing_company", tr("La empresa es requerida.")))
        val server = withContext(Dispatchers.IO) { resolver.resolve(token) }
        if (server != ServerActorLookup.None) {
            return when (val gate = call.checkServerGate(server, call.routePolicy(), tr("Debes iniciar sesion como server_owner."))) {
                is ServerGate.Allowed -> ExtensionActorLookup.Found(gate.actor.toExtensionActor(company))
                is ServerGate.Denied -> ExtensionActorLookup.Denied(ModuleAccessDecision.Forbidden(gate.code, gate.message))
            }
        }
        return when (val session = withContext(Dispatchers.IO) { userSession(call) }) {
            is UserSessionLookup.Denied -> ExtensionActorLookup.Denied(session.decision)
            is UserSessionLookup.Granted -> {
                val role = withContext(Dispatchers.IO) { businessOwnerRole(session.companyCode, session.userId) }
                    ?: return ExtensionActorLookup.Denied(ModuleAccessDecision.Forbidden("BUSINESS_OWNER_REQUIRED", tr("Solo el business owner puede administrar las extensiones.")))
                val activity = withContext(Dispatchers.IO) { sessionActor(session.companyCode, session.userId, token) }
                ExtensionActorLookup.Found(ExtensionActor(role, serverOwner = false, companyCode = session.companyCode, audit = AuditActor.businessOwner(role), activity = activity))
            }
        }
    }

    private suspend fun serverLookup(call: ApplicationCall): ServerActorLookup {
        val token = call.bearerToken() ?: return ServerActorLookup.None
        return withContext(Dispatchers.IO) { resolver.resolve(token) }
    }

    private fun ServerActor.toExtensionActor(company: String?): ExtensionActor =
        if (isOwner) ExtensionActor(login, serverOwner = true, companyCode = company, audit = audit, activity = com.ideasdeveloper.bms.server.activity.ActivityActor.fromAudit(audit, displayName))
        else ExtensionActor(
            login, serverOwner = false, companyCode = company, audit = audit, serverAdmin = ServerAdminScope(companyScope),
            activity = com.ideasdeveloper.bms.server.activity.ActivityActor.fromAudit(audit, displayName),
        )

    private companion object {
        fun businessOwnerRoleIn(connections: ExtensionConnections, companyCode: String, userId: java.util.UUID): String? =
            connections.tenant(companyCode)?.use { tenant ->
                tenant.prepareStatement(
                    """
                    SELECT users.postgres_role
                    FROM application_users users
                    JOIN business_owner owner ON owner.user_id = users.id
                    WHERE users.id = ? AND users.is_active = TRUE
                    """.trimIndent()
                ).use { query ->
                    query.setObject(1, userId)
                    query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            }
    }
}
