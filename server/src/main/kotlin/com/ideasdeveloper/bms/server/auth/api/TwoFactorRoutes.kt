package com.ideasdeveloper.bms.server.auth.api

import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.admins.serverActor
import com.ideasdeveloper.bms.server.auth.application.AccountException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorAccountService
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorPolicy
import com.ideasdeveloper.bms.server.company.api.CompanyErrorResponse
import com.ideasdeveloper.bms.server.i18n.tr
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.SQLException
import javax.sql.DataSource

@Serializable
data class TwoFactorPasswordRequest(val currentPassword: String? = null)

@Serializable
data class TwoFactorCodeRequest(val code: String = "")

@Serializable
data class TwoFactorPasswordCodeRequest(val currentPassword: String? = null, val code: String = "")

@Serializable
data class TwoFactorPolicyRequest(val requireForAdmins: Boolean)

@Serializable
data class TwoFactorPolicyResponse(val requireForAdmins: Boolean)

/** The signed-in user's own two-step verification (TWO_FACTOR.md §3); session as for /auth/profile. */
fun Route.twoFactorRoutes(service: TwoFactorAccountService) {
    get("/auth/two-factor") {
        val token = call.twoFactorSession() ?: return@get
        call.twoFactorAnswer { service.status(token, call.companyHeader()) }
    }
    post("/auth/two-factor/setup") {
        val token = call.twoFactorSession() ?: return@post
        val request = call.twoFactorBody<TwoFactorPasswordRequest>() ?: return@post
        call.twoFactorAnswer { service.setup(token, call.companyHeader(), request.currentPassword) }
    }
    post("/auth/two-factor/enable") {
        val token = call.twoFactorSession() ?: return@post
        val request = call.twoFactorBody<TwoFactorCodeRequest>() ?: return@post
        call.twoFactorAnswer { service.enable(token, call.companyHeader(), request.code.take(64)) }
    }
    post("/auth/two-factor/recovery-codes") {
        val token = call.twoFactorSession() ?: return@post
        val request = call.twoFactorBody<TwoFactorPasswordCodeRequest>() ?: return@post
        call.twoFactorAnswer { service.regenerate(token, call.companyHeader(), request.currentPassword, request.code.take(64)) }
    }
    post("/auth/two-factor/disable") {
        val token = call.twoFactorSession() ?: return@post
        val request = call.twoFactorBody<TwoFactorPasswordCodeRequest>() ?: return@post
        call.twoFactorAnswer(HttpStatusCode.NoContent) { service.disable(token, call.companyHeader(), request.currentPassword, request.code.take(64)) }
    }
}

/** The owner's rule for administrators (TWO_FACTOR.md §5): owner only (ServerRoutePolicies), audited. */
fun Route.twoFactorPolicyRoutes(resolver: ServerActorResolver, central: DataSource?) {
    val notOwner = tr("Debes iniciar sesion como server_owner para cambiar la verificacion en dos pasos del servidor.")
    get("/server/two-factor-policy") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.serverActor(resolver, notOwner) ?: return@get
        val source = central ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse(TwoFactorException.Unavailable, tr("La verificacion en dos pasos no esta disponible en este servidor.")))
        call.respond(HttpStatusCode.OK, TwoFactorPolicyResponse(withContext(Dispatchers.IO) { TwoFactorPolicy.requireForAdmins(source) }))
    }
    put("/server/two-factor-policy") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(resolver, notOwner) ?: return@put
        val source = central ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse(TwoFactorException.Unavailable, tr("La verificacion en dos pasos no esta disponible en este servidor.")))
        val request = runCatching { call.receive<TwoFactorPolicyRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar requireForAdmins.")))
        try {
            withContext(Dispatchers.IO) { TwoFactorPolicy.setRequireForAdmins(source, request.requireForAdmins, actor.audit) }
        } catch (refused: TwoFactorException) {
            return@put call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse(refused.code, refused.message.orEmpty()))
        }
        call.respond(HttpStatusCode.OK, TwoFactorPolicyResponse(request.requireForAdmins))
    }
}

private suspend fun ApplicationCall.twoFactorSession(): String? {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    val token = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substringAfter(' ')?.trim()?.takeIf { it.isNotEmpty() }
    if (token == null) respond(HttpStatusCode.Unauthorized, AuthErrorResponse("INVALID_SESSION", tr("La sesion es requerida.")))
    return token
}

private fun ApplicationCall.companyHeader(): String? = request.headers["X-Company-Code"]?.trim()?.takeIf { it.isNotEmpty() }

private suspend inline fun <reified T : Any> ApplicationCall.twoFactorBody(): T? = runCatching { receive<T>() }.getOrNull()
    ?: run {
        respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
        null
    }

private suspend inline fun <reified T : Any> ApplicationCall.twoFactorAnswer(status: HttpStatusCode = HttpStatusCode.OK, crossinline block: () -> T) {
    val result = try {
        withContext(Dispatchers.IO) { block() }
    } catch (failure: AccountException) {
        val code = when (failure.code) {
            "INVALID_SESSION" -> HttpStatusCode.Unauthorized
            "TOO_MANY_ATTEMPTS" -> HttpStatusCode.TooManyRequests
            "PASSWORD_CHANGE_REQUIRED" -> HttpStatusCode.Forbidden
            else -> HttpStatusCode.BadRequest
        }
        return respond(code, AuthErrorResponse(failure.code, failure.message.orEmpty()))
    } catch (failure: TwoFactorException) {
        val code = when (failure.code) {
            TwoFactorException.Unavailable -> HttpStatusCode.ServiceUnavailable
            TwoFactorException.TooManyAttempts -> HttpStatusCode.TooManyRequests
            TwoFactorException.AlreadyEnabled, TwoFactorException.NotPending, TwoFactorException.NotEnabled -> HttpStatusCode.Conflict
            TwoFactorException.RequiredByServer -> HttpStatusCode.Forbidden
            else -> HttpStatusCode.BadRequest
        }
        return respond(code, AuthErrorResponse(failure.code, failure.message.orEmpty()))
    } catch (_: SQLException) {
        return respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("ACCOUNT_UNAVAILABLE", tr("No se pudo completar la operacion. Intentalo mas tarde.")))
    }
    if (status == HttpStatusCode.NoContent) respond(HttpStatusCode.NoContent) else respond(status, result)
}
