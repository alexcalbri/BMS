package com.ideasdeveloper.bms.server.activity

import com.ideasdeveloper.bms.server.company.api.CompanyErrorResponse
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ActivityReaderLookup
import com.ideasdeveloper.bms.server.modules.ModuleAccessDecision
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException

/**
 * GET /companies/me/activity (+ /export, CSV with the same filters): the activity log of the
 * company of X-Company-Code, for its business owner or a user holding empresa.activity.view (the
 * server owner too in the server workspace). Reading is not logged; exporting is (a bulk read).
 */
fun Route.companyActivityRoutes(access: ModuleAccessService, moduleName: (String) -> String) {
    suspend fun ApplicationCall.read(block: (ActivityReaderLookup.Granted, ActivityFilter, java.sql.Connection) -> Any) {
        response.headers.append(HttpHeaders.CacheControl, "no-store")
        val result = try {
            withContext(Dispatchers.IO) {
                when (val reader = access.activityReader(this@read)) {
                    is ActivityReaderLookup.Denied -> reader.decision
                    is ActivityReaderLookup.Granted -> {
                        val filter = ActivityFilter.of { request.queryParameters[it] }
                        val tenant = access.tenantConnectionForCompany(reader.companyCode)
                            ?: return@withContext ModuleAccessDecision.Forbidden("company_not_found", tr("La empresa no esta activa."))
                        tenant.use { block(reader, filter, it) }
                    }
                }
            }
        } catch (failure: ActivityFilterException) {
            return respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_FILTER", failure.message.orEmpty()))
        } catch (_: SQLException) {
            return respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("ACTIVITY_UNAVAILABLE", tr("No se pudo leer el registro de actividad.")))
        }
        when (result) {
            is ModuleAccessDecision.Unauthorized -> respond(HttpStatusCode.Unauthorized, CompanyErrorResponse(result.code, result.message))
            is ModuleAccessDecision.Forbidden -> respond(HttpStatusCode.Forbidden, CompanyErrorResponse(result.code, result.message))
            is String -> {
                response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"actividad.csv\"")
                respondText(result, ContentType.Text.CSV.withParameter("charset", "utf-8"))
            }
            else -> respond(HttpStatusCode.OK, result)
        }
    }

    get("/companies/me/activity") {
        call.read { _, filter, tenant -> ActivityLogReader.page(tenant, filter, moduleName) }
    }

    get("/companies/me/activity/export") {
        call.read { reader, filter, tenant ->
            val rows = ActivityLogReader.rows(tenant, filter, ActivityLogReader.MaxExportRows, 0, moduleName)
            // A bulk read is the one read that is logged (ACTIVITY_LOG.md §4); without the row there is no export.
            ActivityLog.record(
                tenant, reader.actor,
                ActivityEntry(
                    moduleId = CoreModuleId, entityType = "activity_log", entityId = null, entityLabel = "Registro de actividad",
                    action = ActivityActions.Exported,
                    summary = "Exporto el registro de actividad (${rows.size} filas" + describe(filter) + ").",
                ),
            )
            ActivityLogReader.csv(rows, withCompany = false)
        }
    }
}

/** Module id of Core's own events (company administration). */
const val CoreModuleId = "core"

/** ", filtros: modulo=crm, desde=2026-01-01" for summaries of reads. */
internal fun describe(filter: ActivityFilter): String {
    val used = filter.describe().filterKeys { it != "pagina" }
    return if (used.isEmpty()) "" else ", filtros: " + used.entries.joinToString(", ") { "${it.key}=${it.value}" }
}
