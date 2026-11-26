package com.ideasdeveloper.bms.app.core.version

import com.ideasdeveloper.bms.ModuleVersion
import com.ideasdeveloper.bms.app.core.i18n.AppLanguage
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

// Header con la version de la app; el servidor lo usa para validar la version minima de los modulos.
const val AppVersionHeader = "X-App-Version"

// Agrega el header de version y el idioma de la app (Accept-Language) a todas las peticiones del cliente.
// Se usa en cada HttpClient que habla con el servidor; el idioma se lee en cada peticion, asi que un cambio aplica de inmediato.
fun HttpClientConfig<*>.installAppVersionHeader() {
    defaultRequest {
        header(AppVersionHeader, ClientVersion)
        header(HttpHeaders.AcceptLanguage, AppLanguage.current)
    }
}

// True si la version indicada es mayor que la de esta app (es decir, la app es demasiado vieja).
fun isAppOlderThan(minAppVersion: String?): Boolean =
    !minAppVersion.isNullOrBlank() && ModuleVersion.compare(ClientVersion, minAppVersion) < 0
