package com.ideasdeveloper.bms.app.features.auth.presentation

import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.features.company.data.CompanyApi
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.core.company.serverIdentity
import com.ideasdeveloper.bms.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.bms.app.core.company.CompanyIdentity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideasdeveloper.bms.app.core.config.ClientConfiguration
import com.ideasdeveloper.bms.app.core.config.ClientConfigurationStore
import com.ideasdeveloper.bms.app.core.session.SessionStore
import com.ideasdeveloper.bms.app.features.auth.data.LoginApi
import com.ideasdeveloper.bms.app.features.auth.data.LoginException
import com.ideasdeveloper.bms.app.features.auth.data.LoginRequest
import com.ideasdeveloper.bms.app.features.auth.data.TwoFactorRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Segundo paso pendiente: el servidor acepto la contrasena y espera el codigo de dos pasos (o de recuperacion).
data class TwoFactorChallenge(val token: String, val expiresInSeconds: Long, val emailRecoveryOffered: Boolean = false)

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val succeeded: Boolean = false,
    // No nulo mientras se muestra el segundo paso; null en el formulario normal.
    val challenge: TwoFactorChallenge? = null,
    // Intentos que quedan con el reto actual tras un codigo incorrecto.
    val attemptsLeft: Int? = null,
    // Mensaje informativo del segundo paso (p. ej. la recuperacion por correo ya se inicio) y dias de espera que informo el servidor.
    val notice: String? = null,
    val waitDays: Long? = null,
)

// Datos del primer paso que el segundo necesita para terminar de iniciar sesion.
private class PendingSignIn(val serverUrl: String, val companyCode: String?, val keepSignedIn: Boolean)

// Coordina validacion, llamada de login y persistencia de sesion/configuracion.
class LoginViewModel : ViewModel() {

    // Estado observable por la pantalla de login.
    private val _state = MutableStateFlow(LoginUiState())
    val state = _state.asStateFlow()

    private var pendingSignIn: PendingSignIn? = null

    // Ejecuta el flujo completo de autenticacion contra el servidor configurado.
    // Marca de la sesion recien iniciada: el servidor, o la empresa (su perfil cuando lo puede leer).
    private suspend fun refreshIdentity(serverUrl: String, response: LoginResponse, companyCode: String?) {
        if (companyCode == null) {
            CompanyIdentityStore.save(serverIdentity(response.serverName))
            return
        }
        val profile = if (response.role == "business_owner") {
            runCatching {
                val api = CompanyApi(serverUrl)
                try { api.companyProfile(response.accessToken, companyCode) } finally { api.close() }
            }.getOrNull()
        } else null
        val identity = profile?.let { CompanyIdentity(it.code, it.code, it.name, it.logoUrl, it.primaryColor, it.secondaryColor, it.accentColor) }
            ?: CompanyIdentityStore.current().takeIf { it.code == companyCode }
            ?: serverIdentity(null).copy(id = companyCode, code = companyCode, name = companyCode)
        CompanyIdentityStore.save(identity)
    }

    // Guarda la sesion y la configuracion y deja lista la identidad visual (paso final de ambos pasos del login).
    private suspend fun finishSignIn(serverUrl: String, code: String?, keepSignedIn: Boolean, response: LoginResponse) {
        SessionStore.save(response, keepSignedIn = keepSignedIn)
        ClientConfigurationStore.save(
            ClientConfiguration(
                serverUrl = serverUrl.trim().trimEnd('/'),
                companyCode = code,
            )
        )
        refreshIdentity(serverUrl.trim().trimEnd('/'), response, code)
    }

    // Segundo paso: envia el codigo de 6 digitos (o de recuperacion) con el reto del primero.
    // [emailRecovery]: [code] es el que llego por correo tras la espera de la recuperacion (no el de la app).
    fun submitTwoFactor(code: String, emailRecovery: Boolean = false) {
        val challenge = _state.value.challenge ?: return
        val pending = pendingSignIn ?: return
        if (_state.value.isLoading || code.isBlank()) return

        val previous = _state.value
        _state.value = LoginUiState(isLoading = true, challenge = challenge, notice = previous.notice, waitDays = previous.waitDays)

        viewModelScope.launch {
            var api: LoginApi? = null

            try {
                api = LoginApi(pending.serverUrl)
                val response = api.completeTwoFactor(challenge.token, code, emailRecovery)
                finishSignIn(pending.serverUrl, pending.companyCode, pending.keepSignedIn, response)
                pendingSignIn = null
                _state.value = LoginUiState(succeeded = true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: LoginException) {
                if (exception.code == "TWO_FACTOR_CHALLENGE_EXPIRED") {
                    // El reto caduco o se agoto: de vuelta al primer paso con el mensaje del servidor.
                    pendingSignIn = null
                    _state.value = LoginUiState(error = exception.message)
                } else {
                    _state.value = LoginUiState(
                        challenge = challenge,
                        error = exception.message,
                        attemptsLeft = exception.attemptsLeft,
                        notice = previous.notice,
                        waitDays = previous.waitDays,
                    )
                }
            } catch (_: IllegalArgumentException) {
                pendingSignIn = null
                _state.value = LoginUiState(
                    error = tr("Revisa la URL del servidor. Debe iniciar con http:// o https:// y no llevar rutas.")
                )
            } catch (_: Exception) {
                _state.value = LoginUiState(
                    challenge = challenge,
                    error = tr("No se pudo completar el inicio de sesión."),
                    notice = previous.notice,
                    waitDays = previous.waitDays,
                )
            } finally {
                api?.close()
            }
        }
    }

    // Pide la recuperacion por correo: el servidor avisa al correo de la cuenta y el codigo llega tras la espera.
    fun startEmailRecovery() {
        val challenge = _state.value.challenge ?: return
        val pending = pendingSignIn ?: return
        if (_state.value.isLoading) return

        _state.value = LoginUiState(isLoading = true, challenge = challenge)

        viewModelScope.launch {
            var api: LoginApi? = null

            try {
                api = LoginApi(pending.serverUrl)
                val started = api.startEmailRecovery(challenge.token)
                _state.value = LoginUiState(challenge = challenge, notice = started.message, waitDays = started.waitDays)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: LoginException) {
                if (exception.code == "TWO_FACTOR_CHALLENGE_EXPIRED") {
                    pendingSignIn = null
                    _state.value = LoginUiState(error = exception.message)
                } else {
                    // Incluye MAIL_NOT_CONFIGURED: el servidor no puede enviar correo.
                    _state.value = LoginUiState(challenge = challenge, error = exception.message)
                }
            } catch (_: Exception) {
                _state.value = LoginUiState(challenge = challenge, error = tr("No se pudo completar el inicio de sesión."))
            } finally {
                api?.close()
            }
        }
    }

    // Vuelve del segundo paso al formulario de usuario y contrasena.
    fun cancelTwoFactor() {
        if (_state.value.isLoading) return
        pendingSignIn = null
        _state.value = LoginUiState()
    }

    fun login(
        serverUrl: String,
        username: String,
        password: String,
        companyCode: String?,
        keepSignedIn: Boolean,
    ) {
        if (_state.value.isLoading) return

        // Valida campos basicos antes de abrir una conexion HTTP.
        if (username.isBlank() || password.isEmpty()) {
            _state.value = LoginUiState(
                error = tr("Introduce usuario y contraseña.")
            )
            return
        }

        val code = companyCode?.trim()
        // Asegura que el codigo tenant use el mismo formato esperado por el servidor.
        if (code != null && !code.matches(Regex("[a-z][a-z0-9_]{0,62}"))) {
            _state.value = LoginUiState(
                error = tr("Introduce un código de empresa válido.")
            )
            return
        }

        _state.value = LoginUiState(isLoading = true)

        viewModelScope.launch {
            var api: LoginApi? = null

            try {
                api = LoginApi(serverUrl)

                // Solicita al servidor token, rol y modulos habilitados para esta sesion.
                val response = api.login(
                    LoginRequest(
                        username = username.trim(),
                        password = password,
                        companyCode = code,
                    )
                )

                // Guarda sesion y configuracion para que el resto de la app pueda navegar.
                finishSignIn(serverUrl, code, keepSignedIn, response)
                _state.value = LoginUiState(succeeded = true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: TwoFactorRequiredException) {
                // Contrasena correcta: se pide el segundo paso sin perder lo escrito en el primero.
                pendingSignIn = PendingSignIn(serverUrl, code, keepSignedIn)
                _state.value = LoginUiState(challenge = TwoFactorChallenge(exception.challengeToken, exception.expiresInSeconds, "email_recovery" in exception.methods))
            } catch (exception: LoginException) {
                _state.value = LoginUiState(error = exception.message)
            } catch (_: IllegalArgumentException) {
                _state.value = LoginUiState(
                    error = tr("Revisa la URL del servidor. Debe iniciar con http:// o https:// y no llevar rutas.")
                )
            } catch (_: Exception) {
                _state.value = LoginUiState(
                    error = tr("No se pudo completar el inicio de sesión.")
                )
            } finally {
                api?.close()
            }
        }
    }
}
