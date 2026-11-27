package com.ideasdeveloper.bms.app.features.auth.data

import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.session.SessionExpiry
import com.ideasdeveloper.bms.app.core.version.VersionApi
import com.ideasdeveloper.bms.app.core.version.installAppVersionHeader
import com.ideasdeveloper.bms.app.core.version.isCoreAtLeast
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AccountProfile(
    val username: String,
    val displayName: String? = null,
    val email: String? = null,
    val profileComplete: Boolean = false,
    val userCode: String = "",
    // Idioma propio del usuario (null = sigue al de la empresa) y el de su empresa; null en servidores antiguos.
    val language: String? = null,
    val companyLanguage: String? = null,
)

@Serializable
private data class UpdateAccountProfileRequest(
    val displayName: String,
    val email: String,
    // "es", "en" o null (seguir al idioma de la empresa); sin valor por defecto para que null tambien se envie.
    val language: String?,
    // Solo se envia al cambiar el correo; nunca se guarda ni se registra.
    val currentPassword: String? = null,
)

// Para servidores con Core anterior a 0.10.0, cuyo Json estricto rechaza el campo "language" desconocido.
@Serializable
private data class LegacyUpdateAccountProfileRequest(
    val displayName: String,
    val email: String,
    val currentPassword: String? = null,
)

@Serializable
private data class PasswordResetRequest(val username: String, val companyCode: String? = null)

@Serializable
private data class PasswordResetConfirmation(
    val username: String,
    val companyCode: String? = null,
    val code: String,
    val newPassword: String,
    // Solo si el servidor lo pidio (TWO_FACTOR_REQUIRED): codigo de la aplicacion o de recuperacion.
    val twoFactorCode: String? = null,
)

@Serializable
private data class TwoFactorPasswordBody(val currentPassword: String)

@Serializable
private data class TwoFactorCodeBody(val code: String)

// [code] solo se envia fuera de la ventana de cambio (autenticador actual o un codigo de recuperacion).
@Serializable
private data class TwoFactorReplaceSetupBody(val currentPassword: String, val code: String? = null)

@Serializable
private data class TwoFactorPasswordCodeBody(val currentPassword: String, val code: String)

@Serializable
private data class AccountMessage(val message: String)

// Cuenta propia: nombre y correo del usuario, y recuperacion de contrasena con un codigo enviado por correo.
class AccountApi(serverUrl: String) {
    private val baseUrl = serverUrl.trim().trimEnd('/')

    private val client = HttpClient {
        installAppVersionHeader()
        expectSuccess = false
        followRedirects = false
        install(SessionExpiry)
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    // Sin codigo de empresa la sesion es del dueno del servidor.
    suspend fun profile(accessToken: String, companyCode: String?): AccountProfile = call {
        client.get("$baseUrl/auth/profile") {
            bearerAuth(accessToken)
            companyCode?.let { header("X-Company-Code", it) }
        }
    }

    suspend fun updateProfile(
        accessToken: String,
        companyCode: String?,
        displayName: String,
        email: String,
        language: String?,
        currentPassword: String? = null,
    ): AccountProfile {
        val password = currentPassword?.takeIf { it.isNotEmpty() }
        // El idioma solo se envia si el servidor declara Core >= 0.10.0; con version desconocida se omite.
        val versionApi = VersionApi(baseUrl)
        val serverVersion = try {
            versionApi.version()
        } finally {
            versionApi.close()
        }
        val sendLanguage = serverVersion?.coreVersion?.let { isCoreAtLeast(it, 0, 10, 0) } == true
        return call {
            client.put("$baseUrl/auth/profile") {
                bearerAuth(accessToken)
                companyCode?.let { header("X-Company-Code", it) }
                contentType(ContentType.Application.Json)
                if (sendLanguage) {
                    setBody(UpdateAccountProfileRequest(displayName, email, language, password))
                } else {
                    setBody(LegacyUpdateAccountProfileRequest(displayName, email, password))
                }
            }
        }
    }

    // Devuelve el mensaje del servidor; nunca dice si el usuario existe.
    suspend fun requestPasswordReset(username: String, companyCode: String?): String = call<AccountMessage> {
        client.post("$baseUrl/auth/password-reset/request") {
            contentType(ContentType.Application.Json)
            setBody(PasswordResetRequest(username.trim(), companyCode))
        }
    }.message

    // [twoFactorCode] se envia solo cuando el servidor respondio TWO_FACTOR_REQUIRED a un primer intento.
    suspend fun confirmPasswordReset(
        username: String,
        companyCode: String?,
        code: String,
        newPassword: String,
        twoFactorCode: String? = null,
    ): String = call<AccountMessage> {
        client.post("$baseUrl/auth/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody(PasswordResetConfirmation(username.trim(), companyCode, code.trim(), newPassword, twoFactorCode?.trim()?.takeIf { it.isNotEmpty() }))
        }
    }.message

    // Estado de la verificacion en dos pasos de la cuenta; null si el servidor es anterior y no tiene la ruta (404).
    suspend fun twoFactorStatus(accessToken: String, companyCode: String?): TwoFactorStatus? {
        try {
            val response = client.get("$baseUrl/auth/two-factor") {
                bearerAuth(accessToken)
                companyCode?.let { header("X-Company-Code", it) }
            }
            if (response.status == HttpStatusCode.NotFound) return null
            if (response.status == HttpStatusCode.OK) return response.body<TwoFactorStatus>()
            throw failure(response)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: LoginException) {
            throw exception
        } catch (_: Exception) {
            throw LoginException(tr("No se pudo comunicar con el servidor."))
        }
    }

    // Empieza (o reinicia) la activacion: pide la contrasena actual y devuelve el secreto y la URI otpauth.
    suspend fun twoFactorSetup(accessToken: String, companyCode: String?, currentPassword: String): TwoFactorSetup = call<TwoFactorSetup> {
        client.post("$baseUrl/auth/two-factor/setup") {
            bearerAuth(accessToken)
            companyCode?.let { header("X-Company-Code", it) }
            contentType(ContentType.Application.Json)
            setBody(TwoFactorPasswordBody(currentPassword))
        }
    }

    // Confirma la activacion con un codigo; devuelve los codigos de recuperacion (solo se ven esta vez).
    suspend fun twoFactorEnable(accessToken: String, companyCode: String?, code: String): TwoFactorRecoveryCodes = call<TwoFactorRecoveryCodes> {
        client.post("$baseUrl/auth/two-factor/enable") {
            bearerAuth(accessToken)
            companyCode?.let { header("X-Company-Code", it) }
            contentType(ContentType.Application.Json)
            setBody(TwoFactorCodeBody(code.trim()))
        }
    }

    // Cambio de autenticador (TWO_FACTOR.md 9): el actual sigue valiendo hasta confirmar. Sin [code] fuera de la ventana el
    // servidor responde 400 TWO_FACTOR_CODE_REQUIRED.
    suspend fun twoFactorReplaceSetup(accessToken: String, companyCode: String?, currentPassword: String, code: String?): TwoFactorSetup = call<TwoFactorSetup> {
        client.post("$baseUrl/auth/two-factor/replace/setup") {
            bearerAuth(accessToken)
            companyCode?.let { header("X-Company-Code", it) }
            contentType(ContentType.Application.Json)
            setBody(TwoFactorReplaceSetupBody(currentPassword, code?.trim()?.takeIf { it.isNotEmpty() }))
        }
    }

    // Confirma el autenticador nuevo con un codigo suyo; devuelve los 10 codigos de recuperacion nuevos y el aviso.
    suspend fun twoFactorReplaceConfirm(accessToken: String, companyCode: String?, code: String): TwoFactorRecoveryCodes = call<TwoFactorRecoveryCodes> {
        client.post("$baseUrl/auth/two-factor/replace/confirm") {
            bearerAuth(accessToken)
            companyCode?.let { header("X-Company-Code", it) }
            contentType(ContentType.Application.Json)
            setBody(TwoFactorCodeBody(code.trim()))
        }
    }

    // Genera 10 codigos nuevos (los anteriores dejan de valer); [code] es el de la aplicacion, no uno de recuperacion.
    suspend fun twoFactorRegenerateRecoveryCodes(accessToken: String, companyCode: String?, currentPassword: String, code: String): List<String> =
        call<TwoFactorRecoveryCodes> {
            client.post("$baseUrl/auth/two-factor/recovery-codes") {
                bearerAuth(accessToken)
                companyCode?.let { header("X-Company-Code", it) }
                contentType(ContentType.Application.Json)
                setBody(TwoFactorPasswordCodeBody(currentPassword, code.trim()))
            }
        }.recoveryCodes

    // Desactiva la verificacion: contrasena actual y un codigo (de la aplicacion o de recuperacion). Responde 204.
    suspend fun twoFactorDisable(accessToken: String, companyCode: String?, currentPassword: String, code: String) {
        try {
            val response = client.post("$baseUrl/auth/two-factor/disable") {
                bearerAuth(accessToken)
                companyCode?.let { header("X-Company-Code", it) }
                contentType(ContentType.Application.Json)
                setBody(TwoFactorPasswordCodeBody(currentPassword, code.trim()))
            }
            if (response.status.isSuccess()) return
            throw failure(response)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: LoginException) {
            throw exception
        } catch (_: Exception) {
            throw LoginException(tr("No se pudo comunicar con el servidor."))
        }
    }

    // Cancela la recuperacion por correo abierta (204). Sin ninguna abierta responde 409 EMAIL_RECOVERY_NOT_PENDING.
    suspend fun cancelEmailRecovery(accessToken: String, companyCode: String?) {
        try {
            val response = client.delete("$baseUrl/auth/two-factor/email-recovery") {
                bearerAuth(accessToken)
                companyCode?.let { header("X-Company-Code", it) }
            }
            if (response.status.isSuccess()) return
            throw failure(response)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: LoginException) {
            throw exception
        } catch (_: Exception) {
            throw LoginException(tr("No se pudo comunicar con el servidor."))
        }
    }

    // Politica del dueno del servidor: exigir la verificacion a los administradores (GET /server/two-factor-policy).
    // Null si el servidor es anterior y no tiene la ruta.
    suspend fun twoFactorPolicy(accessToken: String): TwoFactorPolicy? {
        try {
            val response = client.get("$baseUrl/server/two-factor-policy") { bearerAuth(accessToken) }
            if (response.status == HttpStatusCode.NotFound) return null
            if (response.status == HttpStatusCode.OK) return response.body<TwoFactorPolicy>()
            throw failure(response)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: LoginException) {
            throw exception
        } catch (_: Exception) {
            throw LoginException(tr("No se pudo comunicar con el servidor."))
        }
    }

    suspend fun setTwoFactorPolicy(accessToken: String, requireForAdmins: Boolean): TwoFactorPolicy = call<TwoFactorPolicy> {
        client.put("$baseUrl/server/two-factor-policy") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(TwoFactorPolicy(requireForAdmins))
        }
    }

    private suspend inline fun <reified T> call(request: () -> HttpResponse): T {
        try {
            val response = request()
            if (response.status == HttpStatusCode.OK) return response.body()
            throw failure(response)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: LoginException) {
            throw exception
        } catch (_: Exception) {
            throw LoginException(tr("No se pudo comunicar con el servidor."))
        }
    }

    // Error del servidor como LoginException con su mensaje y codigo; el 429 usa el mensaje del servidor si lo trae
    // (p. ej. TWO_FACTOR_TOO_MANY_ATTEMPTS) y, si no, el generico.
    private suspend fun failure(response: HttpResponse): LoginException {
        val error = runCatching { response.body<LoginErrorResponse>() }.getOrNull()
        if (response.status == HttpStatusCode.TooManyRequests) {
            return LoginException(error?.message ?: tr("Demasiados intentos. Espera un minuto."), error?.code)
        }
        return LoginException(error?.message ?: tr("El servidor no pudo completar la operacion."), error?.code)
    }

    fun close() {
        client.close()
    }
}

suspend inline fun <T> AccountApi.use(block: (AccountApi) -> T): T = try {
    block(this)
} finally {
    close()
}
