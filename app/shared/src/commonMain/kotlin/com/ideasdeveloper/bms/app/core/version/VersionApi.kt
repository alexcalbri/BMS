package com.ideasdeveloper.bms.app.core.version

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ClientVersion is generated from app/Version.xcconfig (app/shared/build.gradle.kts).

class VersionApi(serverUrl: String) {
    private val baseUrl = serverUrl.trim().trimEnd('/')
    private val client = HttpClient {
        installAppVersionHeader()
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 8_000
            connectTimeoutMillis = 5_000
        }
    }

    // Sin sesion el servidor solo devuelve coreVersion y latestAppVersion (lo que necesita la comprobacion de
    // compatibilidad antes de iniciar sesion). latestCoreVersion y moduleUpdates los da solo a quien manda el token de
    // una sesion del servidor con permiso para actualizar o ver modulos: la pantalla de inicio lo pasa en [accessToken].
    suspend fun version(accessToken: String? = null): VersionResponse? {
        return try {
            val response = client.get("$baseUrl/version") {
                accessToken?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
            }
            if (response.status == HttpStatusCode.OK) response.body() else null
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        client.close()
    }
}

// Verdadero si [version] es un X.Y.Z valido mayor o igual que major.minor.patch; falso si no se puede interpretar.
fun isCoreAtLeast(version: String, major: Int, minor: Int, patch: Int): Boolean {
    val numbers = version.trim().split('.').map { it.toIntOrNull() ?: return false }
    if (numbers.size != 3 || numbers.any { it < 0 }) return false
    val required = listOf(major, minor, patch)
    for (i in 0..2) {
        if (numbers[i] != required[i]) return numbers[i] > required[i]
    }
    return true
}

@Serializable
// Todos los campos tienen valor por defecto: sin sesion (o con un servidor anterior) faltan latestCoreVersion y moduleUpdates.
data class VersionResponse(
    val coreVersion: String = ClientVersion,
    val latestCoreVersion: String? = null,
    val latestAppVersion: String? = null,
    val moduleUpdates: List<ModuleVersionUpdate> = emptyList(),
)

@Serializable
data class ModuleVersionUpdate(
    val moduleId: String = "",
    val displayName: String = "",
    val currentVersion: String = "",
    val latestVersion: String = "",
)
