package com.ideasdeveloper.bms.server.admins

import com.ideasdeveloper.bms.ChangeServerPasswordRequest
import com.ideasdeveloper.bms.CreateServerAdminRequest
import com.ideasdeveloper.bms.ServerAdminPasswordRequest
import com.ideasdeveloper.bms.ServerAdminPermissionsRequest
import com.ideasdeveloper.bms.ServerAdminStatusRequest
import com.ideasdeveloper.bms.ServerMeResponse
import com.ideasdeveloper.bms.UpdateServerAdminRequest
import com.ideasdeveloper.bms.server.company.api.CompanyErrorResponse
import com.ideasdeveloper.bms.server.i18n.tr
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException

/**
 * Server administrators (SERVER_ADMINS.md §4): management routes for the owner only, the audit log
 * (owner or server.audit.view), and the caller's own view and password. What each route needs is in
 * [ServerRoutePolicies]; the services check the owner again.
 */
fun Route.serverAdminRoutes(
    resolver: ServerActorResolver,
    admins: ServerAdminService?,
    audit: ServerAudit?,
    // The companies' activity logs (ACTIVITY_LOG.md §3): owner, or server.audit.view within the scope.
    activity: com.ideasdeveloper.bms.server.activity.ServerActivity? = null,
) {
    val notServer = tr("Debes iniciar sesion como server_owner.")

    get("/server/me") {
        call.noStore()
        val actor = call.serverActor(resolver, notServer) ?: return@get
        call.respond(
            HttpStatusCode.OK,
            ServerMeResponse(
                userId = actor.userId.toString(),
                login = actor.login,
                kind = actor.kind.value,
                displayName = actor.displayName,
                email = actor.email,
                permissions = actor.permissions.map { it.id }.sortedBy { id -> ServerPermission.entries.indexOfFirst { it.id == id } },
                allCompanies = actor.companyScope == null,
                companies = actor.companyScope?.sorted().orEmpty(),
                mustChangePassword = actor.mustChangePassword,
            ),
        )
    }

    // The administrator's own password: the only route, with logout and GET /server/me, open while it must change.
    put("/auth/password") {
        call.noStore()
        val actor = call.serverActor(resolver, notServer) ?: return@put
        val service = admins ?: return@put call.unavailable()
        val request = call.bodyOrNull<ChangeServerPasswordRequest>() ?: return@put call.invalid()
        call.answer(HttpStatusCode.NoContent) {
            service.changeOwnPassword(actor, call.bearerToken().orEmpty(), request.currentPassword, request.newPassword)
            null
        }
    }

    get("/server/admin-permissions") {
        call.noStore()
        call.serverActor(resolver, notServer)?.requireOwner() ?: return@get
        call.respond(HttpStatusCode.OK, ServerPermission.entries.map { it.info() })
    }

    get("/server/admins") {
        call.noStore()
        val actor = call.serverActor(resolver, notServer) ?: return@get
        val service = admins ?: return@get call.unavailable()
        call.answer { service.list(actor) }
    }

    post("/server/admins") {
        call.noStore()
        val actor = call.serverActor(resolver, notServer) ?: return@post
        val service = admins ?: return@post call.unavailable()
        val request = call.bodyOrNull<CreateServerAdminRequest>() ?: return@post call.invalid()
        call.answer(HttpStatusCode.Created) {
            service.create(actor, request.login, request.displayName, request.email, request.password, request.permissions, request.allCompanies, request.companies)
        }
    }

    get("/server/admins/{id}") {
        call.noStore()
        val actor = call.serverActor(resolver, notServer) ?: return@get
        val service = admins ?: return@get call.unavailable()
        call.answer { service.get(actor, call.parameters["id"].orEmpty()) }
    }

    put("/server/admins/{id}") {
        call.noStore()
        val actor = call.serverActor(resolver, notServer) ?: return@put
        val service = admins ?: return@put call.unavailable()
        val request = call.bodyOrNull<UpdateServerAdminRequest>() ?: return@put call.invalid()
        call.answer { service.update(actor, call.parameters["id"].orEmpty(), request.displayName, request.email, request.allCompanies, request.companies) }
    }

    put("/server/admins/{id}/permissions") {
        call.noStore()
        val actor = call.serverActor(resolver, notServer) ?: return@put
        val service = admins ?: return@put call.unavailable()
        val request = call.bodyOrNull<ServerAdminPermissionsRequest>() ?: return@put call.invalid()
        call.answer { service.setPermissions(actor, call.parameters["id"].orEmpty(), request.permissions, request.allCompanies, request.companies) }
    }

    put("/server/admins/{id}/password") {
        call.noStore()
        val actor = call.serverActor(resolver, notServer) ?: return@put
        val service = admins ?: return@put call.unavailable()
        val request = call.bodyOrNull<ServerAdminPasswordRequest>() ?: return@put call.invalid()
        call.answer(HttpStatusCode.NoContent) {
            service.resetPassword(actor, call.parameters["id"].orEmpty(), request.password)
            null
        }
    }

    put("/server/admins/{id}/status") {
        call.noStore()
        val actor = call.serverActor(resolver, notServer) ?: return@put
        val service = admins ?: return@put call.unavailable()
        val request = call.bodyOrNull<ServerAdminStatusRequest>() ?: return@put call.invalid()
        call.answer { service.setActive(actor, call.parameters["id"].orEmpty(), request.active) }
    }

    delete("/server/admins/{id}") {
        call.noStore()
        val actor = call.serverActor(resolver, notServer) ?: return@delete
        val service = admins ?: return@delete call.unavailable()
        call.answer(HttpStatusCode.NoContent) {
            service.delete(actor, call.parameters["id"].orEmpty())
            null
        }
    }

    get("/server/audit") {
        call.noStore()
        val actor = call.serverActor(resolver, notServer) ?: return@get
        val log = audit ?: return@get call.unavailable()
        val parameters = call.request.queryParameters
        call.answer {
            log.page(
                actor,
                AuditFilter(
                    company = parameters["company"],
                    action = parameters["action"],
                    actor = parameters["actor"],
                    from = parseAuditDay(parameters["from"]),
                    to = parseAuditDay(parameters["to"]),
                    page = parameters["page"]?.toIntOrNull() ?: 0,
                    pageSize = parameters["pageSize"]?.toIntOrNull() ?: ServerAudit.DefaultPageSize,
                ),
            )
        }
    }

    serverActivityRoutes(resolver, activity, notServer)
}

/** GET /server/activity (+ /export): the company filter is checked against the scope by [ServerActivity]. */
private fun Route.serverActivityRoutes(resolver: ServerActorResolver, activity: com.ideasdeveloper.bms.server.activity.ServerActivity?, notServer: String) {
    suspend fun ApplicationCall.read(block: (ServerActor, com.ideasdeveloper.bms.server.activity.ActivityFilter, String?) -> Any) {
        noStore()
        val actor = serverActor(resolver, notServer) ?: return
        val service = activity ?: return unavailable()
        val parameters = request.queryParameters
        val result = try {
            val filter = com.ideasdeveloper.bms.server.activity.ActivityFilter.of { parameters[it] }
            withContext(Dispatchers.IO) { block(actor, filter, parameters["company"]) }
        } catch (failure: Exception) {
            val refusal = com.ideasdeveloper.bms.server.activity.activityRefusal(failure)
                ?: if (failure is SQLException) Triple(503, "DATABASE_UNAVAILABLE", tr("La operacion no se pudo completar.")) else throw failure
            return respond(HttpStatusCode.fromValue(refusal.first), CompanyErrorResponse(refusal.second, refusal.third))
        }
        when (result) {
            is String -> {
                response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"actividad.csv\"")
                respondText(result, io.ktor.http.ContentType.Text.CSV.withParameter("charset", "utf-8"))
            }
            is com.ideasdeveloper.bms.ActivityLogPage -> respond(HttpStatusCode.OK, result)
            else -> respond(HttpStatusCode.OK)
        }
    }
    get("/server/activity") { call.read { actor, filter, company -> activity!!.page(actor, company, filter) } }
    get("/server/activity/export") { call.read { actor, filter, company -> activity!!.export(actor, company, filter) } }
}

private fun ApplicationCall.noStore() = response.headers.append(HttpHeaders.CacheControl, "no-store")

private suspend fun ApplicationCall.unavailable() =
    respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("SERVER_ADMINS_NOT_CONFIGURED", tr("El servidor no tiene configuracion de provisioning disponible.")))

private suspend fun ApplicationCall.invalid() =
    respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))

private suspend inline fun <reified T : Any> ApplicationCall.bodyOrNull(): T? = runCatching { receive<T>() }.getOrNull()

private suspend inline fun <reified T : Any> ApplicationCall.answer(status: HttpStatusCode = HttpStatusCode.OK, crossinline block: () -> T?) {
    val result = try {
        withContext(Dispatchers.IO) { block() }
    } catch (refusal: ServerPermissionException) {
        return respond(HttpStatusCode.Forbidden, CompanyErrorResponse(refusal.code, refusal.message.orEmpty()))
    } catch (refusal: ServerAdminException) {
        return respond(HttpStatusCode.fromValue(refusal.status), CompanyErrorResponse(refusal.code, refusal.message.orEmpty()))
    } catch (failure: SQLException) {
        // Unique login, a company deleted meanwhile, or a trigger of V015: never the database text.
        val code = if (failure.sqlState?.startsWith("23") == true) "DATA_CONFLICT" else "DATABASE_UNAVAILABLE"
        val status = if (code == "DATA_CONFLICT") HttpStatusCode.Conflict else HttpStatusCode.ServiceUnavailable
        return respond(status, CompanyErrorResponse(code, tr("La operacion no se pudo completar.")))
    }
    if (result == null) respond(status) else respond(status, result)
}
