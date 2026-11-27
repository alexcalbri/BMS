package com.ideasdeveloper.bms.app.features.company.data

import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.company.CompanyIdentity
import com.ideasdeveloper.bms.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.bms.app.core.company.serverIdentity
import com.ideasdeveloper.bms.app.core.config.ClientConfiguration
import com.ideasdeveloper.bms.app.core.config.ClientConfigurationStore
import com.ideasdeveloper.bms.app.core.session.PersistentSessionStore
import com.ideasdeveloper.bms.app.core.session.SessionStore
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cambia la sesion a otra empresa o, con [target] null, al servidor, sin volver a iniciar sesion.
 * El business owner elige entre sus empresas vinculadas; el dueno del servidor entre todas.
 */
object CompanySwitch {
    // Mensaje del servidor cuando el cambio exige haber iniciado sesion con el codigo de dos pasos (403
    // TWO_FACTOR_REAUTH_REQUIRED). App() lo muestra en un dialogo con "Cerrar sesion e iniciar de nuevo".
    private val _reauthRequired = MutableStateFlow<String?>(null)
    val reauthRequired: StateFlow<String?> = _reauthRequired.asStateFlow()

    fun clearReauthRequired() {
        _reauthRequired.value = null
    }

    /** Devuelve null si cambio, o el mensaje de error para mostrar. */
    suspend fun switchTo(serverUrl: String, session: LoginResponse, target: OwnedCompany?): String? = try {
        CompanyApi(serverUrl).use { api ->
            val next = api.switchCompany(session.accessToken, session.companyCode, target?.code)
            // La nueva sesion se guarda igual que la anterior: recordada solo si el usuario lo pidio al entrar.
            SessionStore.save(next, keepSignedIn = PersistentSessionStore.load() != null)
            ClientConfigurationStore.save(ClientConfiguration(serverUrl = serverUrl, companyCode = target?.code))
            CompanyIdentityStore.save(
                if (target == null) serverIdentity(next.serverName)
                else runCatching { api.companyProfile(next.accessToken, target.code) }.getOrNull()?.let { profile ->
                    CompanyIdentity(profile.code, profile.code, profile.name, profile.logoUrl, profile.primaryColor, profile.secondaryColor, profile.accentColor)
                } ?: CompanyIdentityStore.current().copy(id = target.code, code = target.code, name = target.name)
            )
        }
        null
    } catch (exception: CompanyException) {
        val message = exception.message ?: tr("No se pudo cambiar de empresa.")
        if (exception.code == "TWO_FACTOR_REAUTH_REQUIRED") _reauthRequired.value = message
        message
    }
}

private suspend inline fun <T> CompanyApi.use(block: (CompanyApi) -> T): T = try {
    block(this)
} finally {
    close()
}
