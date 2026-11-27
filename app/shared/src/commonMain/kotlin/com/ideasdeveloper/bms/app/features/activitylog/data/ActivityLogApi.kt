package com.ideasdeveloper.bms.app.features.activitylog.data

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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

// Cliente del registro de actividad: ruta de la empresa (/companies/me/activity) y del servidor (/server/activity).
class ActivityLogApi(serverUrl: String) {
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
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
    }

    // Verdadero si el servidor declara Core >= 0.12.0; con version desconocida, falso.
    suspend fun supported(): Boolean {
        val versionApi = VersionApi(baseUrl)
        val serverVersion = try {
            versionApi.version()
        } finally {
            versionApi.close()
        }
        return serverVersion?.coreVersion?.let { isCoreAtLeast(it, 0, 12, 0) } == true
    }

    private fun HttpRequestBuilder.applyFilters(filters: ActivityFilters, includeCompany: Boolean) {
        if (includeCompany && filters.company.isNotBlank()) parameter("company", filters.company.trim())
        if (filters.user.isNotBlank()) parameter("user", filters.user.trim())
        if (filters.module.isNotBlank()) parameter("module", filters.module.trim())
        if (filters.action.isNotBlank()) parameter("action", filters.action.trim())
        if (filters.query.isNotBlank()) parameter("q", filters.query.trim())
        if (filters.from.isNotBlank()) parameter("from", filters.from.trim())
        if (filters.to.isNotBlank()) parameter("to", filters.to.trim())
    }

    // Registro de la empresa de la sesion, lo mas reciente primero; page null = primera pagina.
    suspend fun companyActivity(token: String, companyCode: String, filters: ActivityFilters, page: Int?): ActivityPage =
        call(tr("No se pudo cargar el registro de actividad.")) {
            client.get("$baseUrl/companies/me/activity") {
                bearerAuth(token)
                header("X-Company-Code", companyCode)
                applyFilters(filters, includeCompany = false)
                if (page != null) parameter("page", page)
            }
        }

    // Registro de las empresas a su alcance (o de una, con filters.company).
    suspend fun serverActivity(token: String, filters: ActivityFilters, page: Int?): ActivityPage =
        call(tr("No se pudo cargar el registro de actividad.")) {
            client.get("$baseUrl/server/activity") {
                bearerAuth(token)
                applyFilters(filters, includeCompany = true)
                if (page != null) parameter("page", page)
            }
        }

    // CSV con los mismos filtros (ruta de la empresa + /export).
    suspend fun exportCompany(token: String, companyCode: String, filters: ActivityFilters): String =
        callText(tr("No se pudo exportar el registro de actividad.")) {
            client.get("$baseUrl/companies/me/activity/export") {
                bearerAuth(token)
                header("X-Company-Code", companyCode)
                applyFilters(filters, includeCompany = false)
            }
        }

    // CSV de la ruta del servidor (+ /export).
    suspend fun exportServer(token: String, filters: ActivityFilters): String =
        callText(tr("No se pudo exportar el registro de actividad.")) {
            client.get("$baseUrl/server/activity/export") {
                bearerAuth(token)
                applyFilters(filters, includeCompany = true)
            }
        }

    // Verdadero si el usuario puede leer el registro de su empresa (el servidor responde 200); cualquier otra cosa, falso.
    suspend fun canReadCompany(token: String, companyCode: String): Boolean = try {
        client.get("$baseUrl/companies/me/activity") {
            bearerAuth(token)
            header("X-Company-Code", companyCode)
        }.status == HttpStatusCode.OK
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        false
    }

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

    private suspend fun callText(failure: String, request: () -> HttpResponse): String {
        try {
            val response = request()
            if (response.status.isSuccess()) return response.bodyAsText()
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

suspend inline fun <T> ActivityLogApi.use(block: (ActivityLogApi) -> T): T = try {
    block(this)
} finally {
    close()
}
