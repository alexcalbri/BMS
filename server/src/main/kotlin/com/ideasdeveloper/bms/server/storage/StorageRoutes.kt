package com.ideasdeveloper.bms.server.storage

import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.admins.serverActor
import com.ideasdeveloper.bms.server.company.api.CompanyErrorResponse
import com.ideasdeveloper.bms.server.i18n.tr
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Path
import javax.sql.DataSource

@Serializable
data class CompanyStorageResponse(val code: String, val name: String, val usedBytes: Long, val quotaBytes: Long, val customQuotaBytes: Long?)

@Serializable
data class ServerStorageResponse(
    val defaultQuotaBytes: Long,
    val minFreeDiskBytes: Long,
    val freeDiskBytes: Long?,
    val totalDiskBytes: Long?,
    val companies: List<CompanyStorageResponse>,
)

@Serializable
data class DefaultQuotaRequest(val defaultQuotaBytes: Long)

@Serializable
data class CompanyQuotaRequest(val quotaBytes: Long? = null)

/** What the storage routes need (STORAGE_QUOTA.md); null in the route matrix test. */
class StorageAdmin(val central: DataSource, val quotas: StorageQuotas, val guard: StorageGuard, val filesRoot: Path) {
    fun companies(): List<Pair<String, String>> = central.connection.use { connection ->
        connection.prepareStatement("SELECT code, name FROM companies ORDER BY name, code").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
        }
    }

    fun company(code: String): CompanyStorageResponse? = companies().firstOrNull { it.first == code }?.let { (found, name) -> describe(found, name) }

    fun describe(code: String, name: String) = CompanyStorageResponse(
        code, name, guard.usage(code).usedBytes, quotas.effectiveQuotaBytes(code) ?: 0, quotas.customQuotaBytes(code),
    )

    fun server() = ServerStorageResponse(
        defaultQuotaBytes = quotas.defaultQuotaBytes(),
        minFreeDiskBytes = guard.minFreeBytes,
        freeDiskBytes = StorageGuard.usableSpace(filesRoot),
        totalDiskBytes = StorageGuard.totalSpace(filesRoot),
        companies = companies().map { (code, name) -> describe(code, name) },
    )
}

/** Storage quotas of the companies: the server owner only (ServerRoutePolicies), audited. */
fun Route.storageRoutes(authorizer: ServerActorResolver, storage: StorageAdmin?) {
    val notOwner = tr("Debes iniciar sesion como server_owner para gestionar el almacenamiento.")

    get("/server/storage") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.serverActor(authorizer, notOwner) ?: return@get
        val admin = storage ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("STORAGE_UNAVAILABLE", tr("El almacenamiento no esta disponible.")))
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { admin.server() })
    }

    put("/server/storage") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, notOwner) ?: return@put
        val admin = storage ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("STORAGE_UNAVAILABLE", tr("El almacenamiento no esta disponible.")))
        val request = runCatching { call.receive<DefaultQuotaRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_QUOTA", tr("Debes enviar defaultQuotaBytes.")))
        try {
            withContext(Dispatchers.IO) { admin.quotas.setDefault(request.defaultQuotaBytes, actor.audit) }
        } catch (invalid: IllegalArgumentException) {
            return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_QUOTA", invalid.message.orEmpty()))
        }
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { admin.server() })
    }

    put("/companies/{code}/storage-quota") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, notOwner) ?: return@put
        val admin = storage ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("STORAGE_UNAVAILABLE", tr("El almacenamiento no esta disponible.")))
        val code = call.parameters["code"].orEmpty().trim()
        val request = runCatching { call.receive<CompanyQuotaRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_QUOTA", tr("Debes enviar quotaBytes (null vuelve al valor por defecto).")))
        val exists = withContext(Dispatchers.IO) { admin.companies().any { it.first == code } }
        if (!exists) return@put call.respond(HttpStatusCode.NotFound, CompanyErrorResponse("COMPANY_NOT_FOUND", tr("Empresa no encontrada.")))
        try {
            withContext(Dispatchers.IO) { admin.quotas.setCompany(code, request.quotaBytes, actor.audit) }
        } catch (invalid: IllegalArgumentException) {
            return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_QUOTA", invalid.message.orEmpty()))
        }
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { admin.company(code)!! })
    }
}
