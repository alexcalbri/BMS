package com.ideasdeveloper.bms.app.features.auth.data

import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.version.installAppVersionHeader
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Segundo paso del inicio de sesion (TWO_FACTOR.md seccion 2): el codigo puede ser el de 6 digitos o uno de recuperacion.
@Serializable
private data class TwoFactorLoginRequest(val challengeToken: String, val code: String)

// Cliente HTTP encargado solo del inicio de sesion.
class LoginApi(serverUrl: String) {

    // Normaliza la URL base para construir endpoints sin dobles barras.
    private val baseUrl = serverUrl.trim().trimEnd('/')

    init {
        // Acepta solo URLs raiz http/https para evitar rutas inesperadas en la configuracion.
        val url = Url(baseUrl)
        require(
            url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) &&
                    url.host.isNotBlank() &&
                    url.user == null &&
                    url.password == null &&
                    url.parameters.isEmpty() &&
                    url.fragment.isEmpty() &&
                    url.encodedPath in listOf("", "/")
        ) {
            tr("Introduce la URL del servidor con http:// o https://, sin rutas.")
        }
    }

    // Cliente Ktor con JSON tolerante a campos nuevos enviados por el servidor.
    private val client = HttpClient {
        installAppVersionHeader()
        expectSuccess = false
        followRedirects = false

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    // Envia credenciales al endpoint de login y traduce estados HTTP a mensajes de UI.
    suspend fun login(request: LoginRequest): LoginResponse {
        try {
            val response = client.post("$baseUrl/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status == HttpStatusCode.OK) {
                return response.body<LoginResponse>()
            }

            if (response.status == HttpStatusCode.TooManyRequests) {
                throw LoginException(
                    tr("Demasiados intentos. Espera un minuto.")
                )
            }

            if (
                response.status == HttpStatusCode.BadRequest ||
                response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.ServiceUnavailable
            ) {
                val error = response.body<LoginErrorResponse>()
                // Contrasena correcta con verificacion en dos pasos: aun no hay sesion, falta el segundo paso.
                val challenge = error.challengeToken
                if (response.status == HttpStatusCode.Unauthorized && error.code == "TWO_FACTOR_REQUIRED" && !challenge.isNullOrBlank()) {
                    throw TwoFactorRequiredException(error.message, challenge, error.expiresInSeconds ?: 300L)
                }
                throw LoginException(error.message, error.code)
            }

            throw LoginException(
                tr("El servidor no pudo completar el inicio de sesión.")
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: TwoFactorRequiredException) {
            throw exception
        } catch (exception: LoginException) {
            throw exception
        } catch (_: Exception) {
            throw LoginException(
                tr("No se pudo comunicar con el servidor o su respuesta no es válida.")
            )
        }
    }

    // Segundo paso: cambia el reto de /auth/login y el codigo por la sesion. Errores: TWO_FACTOR_INVALID_CODE
    // (con attemptsLeft), TWO_FACTOR_CHALLENGE_EXPIRED (volver al paso 1) y TWO_FACTOR_TOO_MANY_ATTEMPTS (esperar).
    suspend fun completeTwoFactor(challengeToken: String, code: String): LoginResponse {
        try {
            val response = client.post("$baseUrl/auth/login/two-factor") {
                contentType(ContentType.Application.Json)
                setBody(TwoFactorLoginRequest(challengeToken, code.trim()))
            }

            if (response.status == HttpStatusCode.OK) {
                return response.body<LoginResponse>()
            }

            val error = runCatching { response.body<LoginErrorResponse>() }.getOrNull()
            if (response.status == HttpStatusCode.TooManyRequests) {
                throw LoginException(
                    error?.message ?: tr("Demasiados intentos. Espera unos minutos antes de volver a probar."),
                    error?.code ?: "TWO_FACTOR_TOO_MANY_ATTEMPTS",
                )
            }
            if (error != null) {
                throw LoginException(error.message, error.code, error.attemptsLeft)
            }
            throw LoginException(tr("El servidor no pudo completar el inicio de sesión."))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: LoginException) {
            throw exception
        } catch (_: Exception) {
            throw LoginException(
                tr("No se pudo comunicar con el servidor o su respuesta no es válida.")
            )
        }
    }

    // Cierra la sesion en el servidor para invalidar el token de inmediato (no solo en el cliente).
    // Best-effort: si falla la red, el cierre local ocurre igual y no bloqueamos al usuario.
    suspend fun logout(companyCode: String?, token: String) {
        if (token.isBlank()) return
        try {
            client.post("$baseUrl/auth/logout") {
                header(HttpHeaders.Authorization, "Bearer $token")
                if (!companyCode.isNullOrBlank()) header("X-Company-Code", companyCode)
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (_: Exception) {
            // Ignorado a proposito: el token vencera por tiempo y la sesion local ya se limpio.
        }
    }

    // Libera recursos del cliente cuando termina el flujo de login.
    fun close() {
        client.close()
    }
}

// [code] es el codigo de error del servidor cuando lo hay (p. ej. PASSWORD_REQUIRED); [attemptsLeft] solo en un
// codigo de dos pasos incorrecto (intentos que quedan con ese reto).
class LoginException(message: String, val code: String? = null, val attemptsLeft: Int? = null) : Exception(message)

// El servidor acepto la contrasena pero la cuenta tiene verificacion en dos pasos: no hay sesion hasta
// enviar el codigo con [challengeToken] (valido [expiresInSeconds] segundos).
class TwoFactorRequiredException(message: String, val challengeToken: String, val expiresInSeconds: Long) : Exception(message)
