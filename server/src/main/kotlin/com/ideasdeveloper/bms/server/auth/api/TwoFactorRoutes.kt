package com.ideasdeveloper.bms.server.auth.api

import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.admins.serverActor
import com.ideasdeveloper.bms.server.auth.application.AccountException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorAccountService
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorPolicy
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorEmailRecovery
import com.ideasdeveloper.bms.server.i18n.I18n
import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import com.ideasdeveloper.bms.server.company.api.CompanyErrorResponse
import com.ideasdeveloper.bms.server.i18n.tr
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
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
data class TwoFactorReplaceRequest(val currentPassword: String? = null, val code: String? = null)

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
    delete("/auth/two-factor/email-recovery") {
        val token = call.twoFactorSession() ?: return@delete
        call.twoFactorAnswer(HttpStatusCode.NoContent) { service.cancelEmailRecovery(token, call.companyHeader()) }
    }
    // Replacing the authenticator (TWO_FACTOR.md §9).
    post("/auth/two-factor/replace/setup") {
        val token = call.twoFactorSession() ?: return@post
        val request = call.twoFactorBody<TwoFactorReplaceRequest>() ?: return@post
        call.twoFactorAnswer { service.replaceSetup(token, call.companyHeader(), request.currentPassword, request.code?.take(64)) }
    }
    post("/auth/two-factor/replace/confirm") {
        val token = call.twoFactorSession() ?: return@post
        val request = call.twoFactorBody<TwoFactorCodeRequest>() ?: return@post
        call.twoFactorAnswer { service.replaceConfirm(token, call.companyHeader(), request.code.take(64)) }
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
            TwoFactorException.AlreadyEnabled, TwoFactorException.NotPending, TwoFactorException.NotEnabled, "EMAIL_RECOVERY_NOT_PENDING" -> HttpStatusCode.Conflict
            TwoFactorException.RequiredByServer -> HttpStatusCode.Forbidden
            "TWO_FACTOR_CODE_REQUIRED" -> HttpStatusCode.BadRequest
            else -> HttpStatusCode.BadRequest
        }
        return respond(code, AuthErrorResponse(failure.code, failure.message.orEmpty()))
    } catch (_: SQLException) {
        return respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("ACCOUNT_UNAVAILABLE", tr("No se pudo completar la operacion. Intentalo mas tarde.")))
    }
    if (status == HttpStatusCode.NoContent) respond(HttpStatusCode.NoContent) else respond(status, result)
}

/**
 * The cancel link of the recovery email (TWO_FACTOR.md §7.2): works in any browser without signing in.
 * GET only shows a confirmation page (mail scanners that open links cancel nothing); the POST of its
 * button cancels. Minimal server-rendered HTML with strict headers; JSON with Accept: application/json.
 */
fun Route.twoFactorRecoveryLinkRoutes(recovery: () -> TwoFactorEmailRecovery?) {
    get("/auth/two-factor/recovery-cancel") {
        call.strictPageHeaders()
        val token = call.request.queryParameters["t"].orEmpty().trim()
        val company = call.request.queryParameters["c"]?.trim()?.takeIf { it.isNotEmpty() }
        val language = withContext(Dispatchers.IO) { runCatching { recovery()?.linkTarget(company, token) }.getOrNull() }
        if (call.wantsJson()) return@get call.respondLinkJson(RecoveryLinkResponse(valid = language != null))
        val page = if (language == null) I18n.withLanguage(I18n.language()) { invalidLinkPage() } else I18n.withLanguage(language) {
            recoveryPage(
                tr("Cancelar la recuperacion del acceso"),
                tr("Alguien inicio la recuperacion del acceso a tu cuenta por correo. Si no fuiste tu, cancelala y cambia tu contrasena."),
                """<form method="post" action="/auth/two-factor/recovery-cancel"><input type="hidden" name="t" value="${html(token)}">""" +
                    (company?.let { """<input type="hidden" name="c" value="${html(it)}">""" } ?: "") +
                    """<button type="submit">${html(tr("Cancelar la recuperacion"))}</button></form>""",
            )
        }
        call.respondText(page, ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
    post("/auth/two-factor/recovery-cancel") {
        call.strictPageHeaders()
        val form = runCatching { call.receiveParameters() }.getOrNull()
        val token = form?.get("t").orEmpty().trim()
        val company = form?.get("c")?.trim()?.takeIf { it.isNotEmpty() }
        val service = recovery()
        val language = withContext(Dispatchers.IO) { runCatching { service?.linkTarget(company, token) }.getOrNull() }
        val cancelled = language != null && withContext(Dispatchers.IO) { service?.cancelByLink(company, token) == true }
        if (call.wantsJson()) return@post call.respondLinkJson(RecoveryLinkResponse(cancelled = cancelled))
        val page = if (!cancelled) I18n.withLanguage(I18n.language()) { invalidLinkPage() } else I18n.withLanguage(language!!) {
            recoveryPage(tr("Recuperacion cancelada"), tr("Se cancelo la recuperacion del acceso. Si no la pediste tu, cambia tu contrasena."), "")
        }
        call.respondText(page, ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
}

@Serializable
data class RecoveryLinkResponse(val valid: Boolean? = null, val cancelled: Boolean? = null)

private suspend fun ApplicationCall.respondLinkJson(body: RecoveryLinkResponse) =
    respondText(kotlinx.serialization.json.Json.encodeToString(RecoveryLinkResponse.serializer(), body), ContentType.Application.Json)

private fun ApplicationCall.wantsJson(): Boolean = request.headers[HttpHeaders.Accept].orEmpty().contains("application/json")

private fun ApplicationCall.strictPageHeaders() {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Referrer-Policy", "no-referrer")
}

private fun html(value: String): String = buildString {
    value.forEach { char ->
        when (char) {
            '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;"); '"' -> append("&quot;"); '\'' -> append("&#39;")
            else -> append(char)
        }
    }
}

private fun invalidLinkPage(): String = recoveryPage(tr("Enlace no valido"), tr("Este enlace ya no es valido: la recuperacion termino, se cancelo o el enlace ya se uso."), "")

private fun recoveryPage(title: String, text: String, body: String): String =
    "<!doctype html><html lang=\"${I18n.language()}\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<title>${html(title)}</title><style>body{font-family:system-ui,sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem;line-height:1.5}" +
        "button{font-size:1rem;padding:.6rem 1.2rem;border-radius:.4rem;border:0;background:#b3261e;color:#fff;cursor:pointer}</style></head>" +
        "<body><h1>${html(title)}</h1><p>${html(text)}</p>$body</body></html>"
