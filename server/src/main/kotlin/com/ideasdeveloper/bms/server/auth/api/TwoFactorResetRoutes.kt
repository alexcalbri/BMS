package com.ideasdeveloper.bms.server.auth.api

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.admins.ServerPermission
import com.ideasdeveloper.bms.server.admins.bearerToken
import com.ideasdeveloper.bms.server.admins.serverActor
import com.ideasdeveloper.bms.server.auth.application.AccountException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetResponse
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetService
import com.ideasdeveloper.bms.server.company.api.CompanyErrorResponse
import com.ideasdeveloper.bms.server.i18n.tr
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException
import java.util.UUID

/** What the server routes need to reset another account's two-step verification (TWO_FACTOR.md §8); null in the matrix test. */
class ServerTwoFactorResets(
    val service: TwoFactorResetService,
    // Codes of the companies of the owner group of a company (itself included).
    val ownerGroupOf: (String) -> Set<String>,
    // The business owner user id of an active company, or null.
    val businessOwnerOf: (String) -> UUID?,
    // Whether a central user is an active administrator (not the owner).
    val isAdmin: (UUID) -> Boolean,
)

/** Server routes of TWO_FACTOR.md §8: permission and scope come from ServerRoutePolicies. */
fun Route.twoFactorResetRoutes(resolver: ServerActorResolver, resets: ServerTwoFactorResets?) {
    val notAllowed = tr("Debes iniciar sesion en el servidor con permiso para desactivar la verificacion en dos pasos.")

    post("/server/companies/{code}/users/{userId}/two-factor/disable") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(resolver, notAllowed) ?: return@post
        val target = resets ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("TWO_FACTOR_UNAVAILABLE", tr("La verificacion en dos pasos no esta disponible en este servidor.")))
        val code = call.parameters["code"].orEmpty().trim()
        val userId = runCatching { UUID.fromString(call.parameters["userId"]) }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.NotFound, CompanyErrorResponse("USER_NOT_FOUND", tr("Usuario no encontrado.")))
        val request = runCatching { call.receive<TwoFactorPasswordRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
        call.twoFactorResetAnswer {
            // A business owner reaches every company of its owner group: all of them must be in scope.
            if (!actor.isOwner && actor.companyScope != null && target.businessOwnerOf(code) == userId &&
                !(target.ownerGroupOf(code) + code).all { actor.mayActOn(it) }
            ) {
                throw TwoFactorResetException("SERVER_PERMISSION_REQUIRED", 403, tr("Una empresa de este business owner no esta en tu alcance."))
            }
            val who = actor.resetActor()
            target.service.checkActor(who, call.bearerToken().orEmpty(), request.currentPassword)
            target.service.reset(who, code, userId)
        }
    }

    post("/server/admins/{id}/two-factor/disable") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(resolver, notAllowed) ?: return@post
        val target = resets ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("TWO_FACTOR_UNAVAILABLE", tr("La verificacion en dos pasos no esta disponible en este servidor.")))
        val adminId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.NotFound, CompanyErrorResponse("USER_NOT_FOUND", tr("Usuario no encontrado.")))
        val request = runCatching { call.receive<TwoFactorPasswordRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
        call.twoFactorResetAnswer {
            // Only administrators: never the server owner (recovery codes and the email recovery are theirs).
            if (adminId == actor.userId || !target.isAdmin(adminId)) {
                throw TwoFactorResetException("SERVER_OWNER_REQUIRED", 403, tr("Solo se puede desactivar asi la verificacion en dos pasos de un administrador del servidor."))
            }
            val who = actor.resetActor()
            target.service.checkActor(who, call.bearerToken().orEmpty(), request.currentPassword)
            target.service.reset(who, null, adminId)
        }
    }
}

private fun ServerActor.resetActor(): TwoFactorResetService.Actor {
    val name = displayName?.takeIf { it.isNotBlank() } ?: login
    val activity = if (isOwner) ActivityActor.serverOwner(userId, name) else ActivityActor.serverAdmin(userId, name)
    return TwoFactorResetService.Actor(audit, userId, login, name, null, activity)
}

/** Runs a reset and answers it (shared by the company and the server routes). */
internal suspend fun ApplicationCall.twoFactorResetAnswer(block: () -> TwoFactorResetResponse) {
    val result = try {
        withContext(Dispatchers.IO) { block() }
    } catch (refused: TwoFactorResetException) {
        return respond(HttpStatusCode.fromValue(refused.status), CompanyErrorResponse(refused.code, refused.message.orEmpty()))
    } catch (refused: AccountException) {
        val status = if (refused.code == "TOO_MANY_ATTEMPTS") HttpStatusCode.TooManyRequests else HttpStatusCode.BadRequest
        return respond(status, CompanyErrorResponse(refused.code, refused.message.orEmpty()))
    } catch (_: SQLException) {
        return respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("TWO_FACTOR_UNAVAILABLE", tr("La verificacion en dos pasos no esta disponible en este servidor.")))
    }
    respond(HttpStatusCode.OK, result)
}
