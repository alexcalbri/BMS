package com.ideasdeveloper.bms.app.features.serveradmin.data

import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.session.SessionExpiry
import com.ideasdeveloper.bms.app.core.version.VersionApi
import com.ideasdeveloper.bms.app.core.version.installAppVersionHeader
import com.ideasdeveloper.bms.app.core.version.isCoreAtLeast
import com.ideasdeveloper.bms.app.features.company.data.CompanyErrorResponse
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

// Codigos de error del servidor que la app interpreta.
const val PermissionRequiredCode = "SERVER_PERMISSION_REQUIRED"
const val PasswordChangeRequiredCode = "PASSWORD_CHANGE_REQUIRED"

// Cliente HTTP de administradores del servidor, auditoria y la sesion propia del nivel servidor.
// Los errores se lanzan como CompanyException con status y code del servidor.
class ServerAdminApi(serverUrl: String) {
    private val baseUrl = serverUrl.trim().trimEnd('/')

    init {
        Url(baseUrl)
    }

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

    // Verdadero si el servidor declara Core >= 0.11.0 (administradores del servidor); con version desconocida, falso.
    suspend fun supportsServerAdmins(): Boolean {
        val versionApi = VersionApi(baseUrl)
        val serverVersion = try {
            versionApi.version()
        } finally {
            versionApi.close()
        }
        return serverVersion?.coreVersion?.let { isCoreAtLeast(it, 0, 11, 0) } == true
    }

    suspend fun me(token: String): ServerMeResponse = call(tr("No se pudo consultar tu sesion del servidor.")) {
        client.get("$baseUrl/server/me") { bearerAuth(token) }
    }

    suspend fun permissions(token: String): List<ServerPermissionInfo> = call(tr("No se pudieron cargar los permisos.")) {
        client.get("$baseUrl/server/admin-permissions") { bearerAuth(token) }
    }

    suspend fun admins(token: String): List<ServerAdminResponse> = call(tr("No se pudieron cargar los administradores.")) {
        client.get("$baseUrl/server/admins") { bearerAuth(token) }
    }

    suspend fun createAdmin(token: String, request: CreateServerAdminRequest) {
        send(tr("No se pudo crear el administrador.")) {
            client.post("$baseUrl/server/admins") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    suspend fun updateAdmin(token: String, id: String, request: UpdateServerAdminRequest) {
        send(tr("No se pudo guardar el administrador.")) {
            client.put("$baseUrl/server/admins/${id.encodeURLPathPart()}") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    suspend fun updatePermissions(token: String, id: String, permissions: List<String>) {
        send(tr("No se pudieron guardar los permisos.")) {
            client.put("$baseUrl/server/admins/${id.encodeURLPathPart()}/permissions") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(UpdateServerAdminPermissionsRequest(permissions))
            }
        }
    }

    suspend fun resetPassword(token: String, id: String, password: String) {
        send(tr("No se pudo cambiar la contrasena.")) {
            client.put("$baseUrl/server/admins/${id.encodeURLPathPart()}/password") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(ServerAdminPasswordRequest(password))
            }
        }
    }

    suspend fun setActive(token: String, id: String, active: Boolean) {
        send(tr("No se pudo cambiar el estado del administrador.")) {
            client.put("$baseUrl/server/admins/${id.encodeURLPathPart()}/status") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(ServerAdminStatusRequest(active))
            }
        }
    }

    suspend fun deleteAdmin(token: String, id: String) {
        send(tr("No se pudo eliminar el administrador.")) {
            client.delete("$baseUrl/server/admins/${id.encodeURLPathPart()}") { bearerAuth(token) }
        }
    }

    // Registro de auditoria, el mas reciente primero. Los filtros vacios no se envian; page null = primera pagina.
    suspend fun audit(
        token: String,
        company: String,
        action: String,
        actor: String,
        from: String,
        to: String,
        page: Int?,
    ): AuditPage = call(tr("No se pudo cargar el registro de auditoria.")) {
        client.get("$baseUrl/server/audit") {
            bearerAuth(token)
            if (company.isNotBlank()) parameter("company", company.trim())
            if (action.isNotBlank()) parameter("action", action.trim())
            if (actor.isNotBlank()) parameter("actor", actor.trim())
            if (from.isNotBlank()) parameter("from", from.trim())
            if (to.isNotBlank()) parameter("to", to.trim())
            if (page != null) parameter("page", page)
        }
    }

    // Cambio de la contrasena propia (obligatorio en el primer inicio de sesion de un administrador).
    suspend fun changeOwnPassword(token: String, currentPassword: String, newPassword: String) {
        send(tr("No se pudo cambiar la contrasena.")) {
            client.put("$baseUrl/auth/password") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(ChangeOwnPasswordRequest(currentPassword, newPassword))
            }
        }
    }

    // Respuesta 2xx como [T]; si no, CompanyException con el mensaje y el codigo del servidor.
    private suspend inline fun <reified T> call(failure: String, request: () -> HttpResponse): T {
        try {
            val response = request()
            if (response.status.isSuccess()) return response.body()
            throw failed(response, failure)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException(tr("No se pudo comunicar con el servidor."), network = true)
        }
    }

    // Igual que [call] cuando no se necesita el cuerpo de la respuesta.
    private suspend inline fun send(failure: String, request: () -> HttpResponse) {
        try {
            val response = request()
            if (response.status.isSuccess()) return
            throw failed(response, failure)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CompanyException) {
            throw exception
        } catch (_: Exception) {
            throw CompanyException(tr("No se pudo comunicar con el servidor."), network = true)
        }
    }

    private suspend fun failed(response: HttpResponse, failure: String): CompanyException {
        val error = runCatching { response.body<CompanyErrorResponse>() }.getOrNull()
        val message = error?.message
            ?: if (response.status == HttpStatusCode.Forbidden) tr("No tienes permiso para esta accion.") else failure
        return CompanyException(message, status = response.status.value, code = error?.code)
    }

    fun close() {
        client.close()
    }
}

suspend inline fun <T> ServerAdminApi.use(block: (ServerAdminApi) -> T): T = try {
    block(this)
} finally {
    close()
}
