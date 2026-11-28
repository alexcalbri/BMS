package com.ideasdeveloper.bms.server.auth.api

import com.ideasdeveloper.bms.server.auth.application.ScopedLoginService
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.domain.LoginSuccessResponse
import com.ideasdeveloper.bms.server.auth.domain.SwitchTargetsResponse
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorRequiredException
import com.ideasdeveloper.bms.server.http.ClientAddresses
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorSignInException
import com.ideasdeveloper.bms.server.i18n.tr
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException

// Step 2 of a sign-in with two-step verification (TWO_FACTOR.md §2).
@Serializable
data class TwoFactorLoginRequest(val challengeToken: String = "", val code: String = "")

@Serializable
data class TwoFactorRequiredResponse(
    val code: String,
    val message: String,
    val challengeToken: String,
    val expiresInSeconds: Long,
    val methods: List<String> = listOf("totp", "recovery_code"),
)

@Serializable
data class EmailRecoveryStartRequest(val challengeToken: String = "")

@Serializable
data class EmailRecoveryStartResponse(val status: String, val message: String, val waitDays: Long)

/** Days the delayed recovery waits (TwoFactorEmailRecovery.DefaultWait), as the contract shows it. */
val EmailRecoveryWaitDays: Long = com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorEmailRecovery.DefaultWait.toDays()

@Serializable
data class TwoFactorErrorResponse(val code: String, val message: String, val attemptsLeft: Int? = null)

// companyCode null: back to the server (server owners only).
@Serializable
data class SwitchCompanyRequest(val companyCode: String? = null)

fun Route.authRoutes(
    loginService: ScopedLoginService,
    // Company switcher (LoginDatabases.switchTargets / switchTo); fromCode null means the server.
    switchTargets: ((fromCode: String?, token: String) -> SwitchTargetsResponse?)? = null,
    switchTo: ((fromCode: String?, token: String, toCode: String?) -> LoginSuccessResponse?)? = null,
    // Cierra la sesion del token actual (LoginDatabases.logout); fromCode null es el servidor.
    logout: ((fromCode: String?, token: String) -> Boolean)? = null,
    // Permiten montar cada grupo bajo un limitador distinto sin cambiar las URLs.
    includeLogin: Boolean = true,
    includeSession: Boolean = true,
    // Step 2 of a sign-in (LoginDatabases.completeTwoFactor): challenge token, code, client address.
    completeTwoFactor: ((String, String, String) -> LoginSuccessResponse)? = null,
    // Delayed recovery by email (TWO_FACTOR.md §7): start (challenge, client address, public base) and finish (challenge, code, client address).
    startEmailRecovery: ((String, String, String?) -> Unit)? = null,
    // Hosts links in emails may use when BMS_PUBLIC_BASE_URL is not set (domains of this server).
    knownPublicHost: (String) -> Boolean = { false },
    completeEmailRecovery: ((String, String, String) -> LoginSuccessResponse)? = null,
) {
    val codePattern = Regex("[a-z][a-z0-9_]{0,62}")
    fun io.ktor.server.application.ApplicationCall.switchContext(): Pair<String?, String>? {
        val fromCode = request.headers["X-Company-Code"]?.trim()?.takeIf { it.isNotEmpty() }
        val token = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()
        if (token.isEmpty() || (fromCode != null && !codePattern.matches(fromCode))) return null
        return fromCode to token
    }

    // El cierre de sesion y el cambio de empresa van con un limitador mas holgado que el del login.
    if (includeSession) {
        get("/auth/switch-targets") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val targets = switchTargets ?: return@get call.respond(HttpStatusCode.NotFound)
            val (fromCode, token) = call.switchContext()
                ?: return@get call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", tr("Falta la sesion.")))
            val response = try {
                withContext(Dispatchers.IO) { targets(fromCode, token) }
            } catch (_: SQLException) {
                return@get call.respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("AUTH_UNAVAILABLE", tr("No se pudieron cargar las empresas.")))
            } ?: return@get call.respond(HttpStatusCode.Forbidden, AuthErrorResponse("SWITCH_REJECTED", tr("Tu sesion no puede cambiar de empresa.")))
            call.respond(HttpStatusCode.OK, response)
        }

        post("/auth/switch-company") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val switch = switchTo ?: return@post call.respond(HttpStatusCode.NotFound)
            val (fromCode, token) = call.switchContext()
                ?: return@post call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", tr("Falta la sesion.")))
            val target = runCatching { call.receive<SwitchCompanyRequest>() }.getOrNull()?.companyCode?.trim()?.takeIf { it.isNotEmpty() }
            if (target != null && !codePattern.matches(target)) {
                return@post call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", tr("Codigo de empresa invalido.")))
            }
            val response = try {
                withContext(Dispatchers.IO) { switch(fromCode, token, target) }
            } catch (refused: com.ideasdeveloper.bms.server.auth.infrastructure.database.SwitchNeedsSecondFactorException) {
                return@post call.respond(HttpStatusCode.Forbidden, AuthErrorResponse("TWO_FACTOR_REAUTH_REQUIRED", refused.message.orEmpty()))
            } catch (_: SQLException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("AUTH_UNAVAILABLE", tr("No se pudo cambiar de empresa. Intentalo mas tarde.")))
            } ?: return@post call.respond(HttpStatusCode.Forbidden, AuthErrorResponse("SWITCH_REJECTED", tr("No puedes entrar ahi con tu sesion.")))
            call.respond(HttpStatusCode.OK, response)
        }

        post("/auth/logout") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val close = logout ?: return@post call.respond(HttpStatusCode.NotFound)
            val (fromCode, token) = call.switchContext()
                ?: return@post call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", tr("Falta la sesion.")))
            try {
                withContext(Dispatchers.IO) { close(fromCode, token) }
            } catch (_: SQLException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("AUTH_UNAVAILABLE", tr("No se pudo cerrar la sesion. Intentalo mas tarde.")))
            }
            // Idempotente: 204 aunque el token ya no existiera, para no revelar si era valido.
            call.respond(HttpStatusCode.NoContent)
        }
    }

    if (includeLogin) {
        post("/auth/login") {
            call.response.headers.append(
                HttpHeaders.CacheControl,
                "no-store",
            )

            val credentials = try {
                call.receive<LoginCredentials>().withClientAddress(ClientAddresses.of(call))
            } catch (_: BadRequestException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    AuthErrorResponse(
                        code = "INVALID_REQUEST",
                        message = tr("Debes enviar un JSON válido con username y password."),
                    ),
                )
                return@post
            } catch (_: ContentTransformationException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    AuthErrorResponse(
                        code = "INVALID_REQUEST",
                        message = tr("Debes enviar un JSON válido con username y password."),
                    ),
                )
                return@post
            }

            val pause = loginService.delayMillisFor(credentials)
            if (pause > 0) kotlinx.coroutines.delay(pause)
            val response = try {
                withContext(Dispatchers.IO) {
                    loginService.login(credentials)
                }
            } catch (required: TwoFactorRequiredException) {
                // Right password, second step pending: no session yet (older apps show the message).
                call.respond(
                    HttpStatusCode.Unauthorized,
                    TwoFactorRequiredResponse(
                        "TWO_FACTOR_REQUIRED", required.message.orEmpty(), required.token, required.expiresInSeconds,
                        if (required.emailRecovery) listOf("totp", "recovery_code", "email_recovery") else listOf("totp", "recovery_code"),
                    ),
                )
                return@post
            } catch (_: TwoFactorException) {
                call.respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("AUTH_UNAVAILABLE", tr("No se pudo procesar el inicio de sesión. Inténtalo más tarde.")))
                return@post
            } catch (_: SQLException) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    AuthErrorResponse(
                        code = "AUTH_UNAVAILABLE",
                        message = tr("No se pudo procesar el inicio de sesión. Inténtalo más tarde."),
                    ),
                )
                return@post
            }

            if (response == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AuthErrorResponse(
                        code = "LOGIN_REJECTED",
                        message = tr("No se pudo iniciar sesión con las credenciales proporcionadas."),
                    ),
                )
                return@post
            }

            call.respond(HttpStatusCode.OK, response)
        }

        post("/auth/login/two-factor/email-recovery") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val start = startEmailRecovery ?: return@post call.respond(HttpStatusCode.NotFound)
            val request = runCatching { call.receive<EmailRecoveryStartRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            try {
                withContext(Dispatchers.IO) { start(request.challengeToken.trim(), ClientAddresses.of(call), com.ideasdeveloper.bms.server.app.publicBase(call, knownPublicHost)) }
            } catch (refused: TwoFactorSignInException) {
                return@post call.respond(HttpStatusCode.Unauthorized, TwoFactorErrorResponse(refused.code, refused.message.orEmpty()))
            } catch (refused: TwoFactorException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, TwoFactorErrorResponse(refused.code, refused.message.orEmpty()))
            } catch (_: SQLException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("AUTH_UNAVAILABLE", tr("No se pudo procesar el inicio de sesión. Inténtalo más tarde.")))
            }
            // The same answer whatever happened (TWO_FACTOR.md §7.1).
            call.respond(HttpStatusCode.Accepted, EmailRecoveryStartResponse("requested", tr("Si la cuenta tiene un correo valido, enviamos un aviso. El codigo para entrar llegara a ese correo dentro de {0} dias; desde el aviso se puede cancelar.", EmailRecoveryWaitDays), EmailRecoveryWaitDays))
        }

        post("/auth/login/two-factor/email-recovery/complete") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val complete = completeEmailRecovery ?: return@post call.respond(HttpStatusCode.NotFound)
            val request = runCatching { call.receive<TwoFactorLoginRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            val response = try {
                withContext(Dispatchers.IO) { complete(request.challengeToken.trim(), request.code.take(64), ClientAddresses.of(call)) }
            } catch (refused: TwoFactorSignInException) {
                return@post call.respond(HttpStatusCode.Unauthorized, TwoFactorErrorResponse(refused.code, refused.message.orEmpty(), refused.attemptsLeft))
            } catch (refused: TwoFactorException) {
                val status = if (refused.code == TwoFactorException.TooManyAttempts) HttpStatusCode.TooManyRequests else HttpStatusCode.ServiceUnavailable
                return@post call.respond(status, TwoFactorErrorResponse(refused.code, refused.message.orEmpty()))
            } catch (_: SQLException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("AUTH_UNAVAILABLE", tr("No se pudo procesar el inicio de sesión. Inténtalo más tarde.")))
            }
            call.respond(HttpStatusCode.OK, response)
        }

        post("/auth/login/two-factor") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val complete = completeTwoFactor ?: return@post call.respond(HttpStatusCode.NotFound)
            val request = runCatching { call.receive<TwoFactorLoginRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, AuthErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            val response = try {
                withContext(Dispatchers.IO) { complete(request.challengeToken.trim(), request.code.take(64), ClientAddresses.of(call)) }
            } catch (refused: TwoFactorSignInException) {
                return@post call.respond(HttpStatusCode.Unauthorized, TwoFactorErrorResponse(refused.code, refused.message.orEmpty(), refused.attemptsLeft))
            } catch (refused: TwoFactorException) {
                val status = if (refused.code == TwoFactorException.TooManyAttempts) HttpStatusCode.TooManyRequests else HttpStatusCode.ServiceUnavailable
                return@post call.respond(status, TwoFactorErrorResponse(refused.code, refused.message.orEmpty()))
            } catch (_: SQLException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, AuthErrorResponse("AUTH_UNAVAILABLE", tr("No se pudo procesar el inicio de sesión. Inténtalo más tarde.")))
            }
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
