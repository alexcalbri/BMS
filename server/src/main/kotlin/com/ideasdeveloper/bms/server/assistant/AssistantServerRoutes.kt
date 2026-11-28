package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.AssistantCompanyAllowanceRequest
import com.ideasdeveloper.bms.AssistantKeyRequest
import com.ideasdeveloper.bms.AssistantServerCompaniesResponse
import com.ideasdeveloper.bms.AssistantServerCompany
import com.ideasdeveloper.bms.AssistantServerCompanyUsage
import com.ideasdeveloper.bms.AssistantServerSettingsRequest
import com.ideasdeveloper.bms.AssistantServerSettingsResponse
import com.ideasdeveloper.bms.AssistantServerUsageResponse
import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.admins.isServerWorkspace
import com.ideasdeveloper.bms.server.admins.serverActor
import com.ideasdeveloper.bms.server.company.api.CompanyErrorResponse
import com.ideasdeveloper.bms.server.i18n.tr
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.sql.SQLException
import java.time.YearMonth
import java.time.ZoneOffset
import javax.sql.DataSource

private val ServerJson = Json { ignoreUnknownKeys = true }

/** What the server routes need besides the company service; null in the route matrix test. */
class AssistantServerAdmin(val central: DataSource, val service: AssistantService) {
    val config: AssistantServerConfig get() = service.serverConfig

    /**
     * Companies the actor may act on, by name. The server workspace comes first and only for the server owner: it is not a
     * company of any administrator's scope, but it has its own entry for the allowance and the monthly limit.
     */
    fun companies(actor: ServerActor): List<Pair<String, String>> = central.connection.use { connection ->
        connection.prepareStatement("SELECT code, name FROM companies WHERE NOT is_server_workspace ORDER BY lower(name), code").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
        }
    }.filter { (code, _) -> actor.mayActOn(code) }.let { companies ->
        if (actor.isOwner) listOfNotNull(workspace()) + companies else companies
    }

    /** The server workspace's code and name, when it exists. */
    fun workspace(): Pair<String, String>? = central.connection.use { connection ->
        connection.prepareStatement("SELECT code, name FROM companies WHERE is_server_workspace").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
        }
    }

    fun settings(): AssistantServerSettingsResponse {
        val settings = config.read()
        return AssistantServerSettingsResponse(
            enabled = settings.enabled, keySet = settings.keySet, keyHint = settings.keyHint, model = settings.model, effort = settings.effort,
            defaultMonthlyTokens = settings.defaultMonthlyTokens, limitOwnKeyCompanies = settings.limitOwnKeyCompanies,
            retentionDays = settings.retentionDays, secretsAvailable = config.secretsAvailable, disclosure = assistantDisclosure(),
        )
    }

    fun company(code: String, name: String, settings: AssistantServerSettings, allowance: AssistantAllowance): AssistantServerCompany {
        val month = YearMonth.now(ZoneOffset.UTC)
        val workspace = isServerWorkspace(code)
        // The workspace has no company switch or key: its switch is the server's and it only ever uses the server key.
        val limited = if (settings.limitOwnKeyCompanies && !workspace) setOf("server", "company") else setOf("server")
        val (company, used) = try {
            service.access.tenantConnectionForCompany(code)?.use { tenant ->
                (if (workspace) AssistantCompanySettings(enabled = settings.enabled) else AssistantCompanyConfig.read(tenant)) to service.store(code).monthUsage(month, limited)
            }
        } catch (_: SQLException) {
            null
        } ?: ((if (workspace) AssistantCompanySettings(enabled = settings.enabled) else AssistantCompanySettings()) to 0L)
        return AssistantServerCompany(
            code = code, name = name, allowed = allowance.allowed, monthlyTokens = allowance.monthlyTokens,
            effectiveMonthlyTokens = config.monthlyLimit(settings, allowance), enabled = company.enabled, companyEnabled = company.enabled, ownKeySet = company.ownKeySet,
            monthUsedTokens = used, serverWorkspace = workspace,
        )
    }

    /**
     * Turns the assistant of company [code] on or off as the server person [actor] (the same change the business owner makes:
     * the company's activity row names the person "(desde el servidor)", the disclosure acceptance is recorded as given by
     * them, and `company.assistant_changed` goes to the server audit in the same transaction).
     */
    fun setCompanyEnabled(code: String, enabled: Boolean, acceptDisclosure: Boolean?, actor: ServerActor) {
        val tenant = service.access.tenantConnectionForCompany(code)
            ?: throw AssistantSettingsException("COMPANY_NOT_FOUND", tr("Empresa no encontrada."), 404)
        tenant.use {
            val before = AssistantCompanyConfig.read(tenant)
            val details = if (before.enabled == enabled) emptyList() else listOf<Pair<String, Any?>>(
                "enabled.from" to before.enabled, "enabled.to" to enabled, "disclosureAccepted" to (acceptDisclosure == true), "via" to "server",
            )
            config.auditedCompanyChange(actor.audit, code, details) {
                AssistantCompanyConfig.update(tenant, service.access.secrets, service.access.serverPersonActor(actor.userId, code), enabled, acceptDisclosure, null, null)
            }
        }
    }

    fun usage(actor: ServerActor, month: YearMonth): AssistantServerUsageResponse = AssistantServerUsageResponse(
        month.toString(),
        companies(actor).map { (code, name) ->
            val rows = try {
                service.access.tenantConnectionForCompany(code)?.use { service.store(code).usage(month) }
            } catch (_: SQLException) {
                null
            }.orEmpty()
            AssistantServerCompanyUsage(
                code, name, rows.sumOf { it.requests }, rows.sumOf { it.countedTokens },
                rows.filter { it.keySource == "server" }.sumOf { it.countedTokens }, rows.filter { it.keySource == "company" }.sumOf { it.countedTokens },
            )
        },
    )
}

/**
 * Server routes of the assistant (GLOBAL_AGENT.md, Contract): server.assistant.manage in ServerRoutePolicies; the
 * per-company ones are scoped by the company of the path, the lists by the administrator's scope.
 */
fun Route.assistantServerRoutes(authorizer: ServerActorResolver, admin: AssistantServerAdmin?) {
    val notServerUser = tr("Debes iniciar sesion como server_owner.")

    suspend fun ApplicationCall.unavailable() =
        respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("ASSISTANT_UNAVAILABLE", tr("El asistente no esta disponible en este servidor.")))

    suspend fun ApplicationCall.settingsFailure(failure: AssistantSettingsException) =
        respond(HttpStatusCode.fromValue(failure.status), CompanyErrorResponse(failure.code, failure.message.orEmpty()))

    get("/server/assistant/settings") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.serverActor(authorizer, notServerUser) ?: return@get
        val assistant = admin ?: return@get call.unavailable()
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { assistant.settings() })
    }

    put("/server/assistant/settings") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, notServerUser) ?: return@put
        val assistant = admin ?: return@put call.unavailable()
        val request = call.json<AssistantServerSettingsRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
        try {
            withContext(Dispatchers.IO) {
                assistant.config.update(
                    request.enabled, request.acceptDisclosure, request.model, request.effort, request.defaultMonthlyTokens,
                    request.limitOwnKeyCompanies, request.retentionDays, actor.audit,
                )
            }
        } catch (failure: AssistantSettingsException) {
            return@put call.settingsFailure(failure)
        }
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { assistant.settings() })
    }

    put("/server/assistant/key") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, notServerUser) ?: return@put
        val assistant = admin ?: return@put call.unavailable()
        val request = call.json<AssistantKeyRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("API_KEY_INVALID", tr("Debes enviar apiKey.")))
        try {
            withContext(Dispatchers.IO) { assistant.config.setKey(request.apiKey, actor.audit) }
        } catch (failure: AssistantSettingsException) {
            return@put call.settingsFailure(failure)
        }
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { assistant.settings() })
    }

    delete("/server/assistant/key") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, notServerUser) ?: return@delete
        val assistant = admin ?: return@delete call.unavailable()
        withContext(Dispatchers.IO) { assistant.config.removeKey(actor.audit) }
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { assistant.settings() })
    }

    get("/server/assistant/companies") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, notServerUser) ?: return@get
        val assistant = admin ?: return@get call.unavailable()
        val answer = withContext(Dispatchers.IO) {
            val settings = assistant.config.read()
            val allowances = assistant.config.allowances()
            AssistantServerCompaniesResponse(
                assistant.companies(actor).map { (code, name) -> assistant.company(code, name, settings, allowances[code] ?: AssistantAllowance()) },
            )
        }
        call.respond(HttpStatusCode.OK, answer)
    }

    put("/server/assistant/companies/{code}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, notServerUser) ?: return@put
        val assistant = admin ?: return@put call.unavailable()
        val code = call.parameters["code"].orEmpty().trim()
        val body = call.json<AssistantCompanyAllowanceRequest>()
        val switch = body?.companyEnabled ?: body?.enabled
        val request = body?.takeIf { it.allowed != null || switch != null }
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar allowed o companyEnabled.")))
        val name = withContext(Dispatchers.IO) { assistant.companies(actor).firstOrNull { it.first == code }?.second }
            ?: return@put call.respond(HttpStatusCode.NotFound, CompanyErrorResponse("COMPANY_NOT_FOUND", tr("Empresa no encontrada.")))
        // The workspace has no switch of its own: it is the server's.
        if (switch != null && isServerWorkspace(code)) {
            return@put call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("USE_SERVER_SETTINGS", tr("En el espacio del servidor el asistente se configura en los ajustes del servidor.")))
        }
        val answer = try {
            withContext(Dispatchers.IO) {
                // The switch first: without the disclosure nothing else changes.
                switch?.let { assistant.setCompanyEnabled(code, it, request.acceptDisclosure, actor) }
                val allowance = request.allowed?.let { assistant.config.setAllowance(code, it, request.monthlyTokens, actor.audit) } ?: assistant.config.allowance(code)
                assistant.company(code, name, assistant.config.read(), allowance)
            }
        } catch (failure: AssistantSettingsException) {
            return@put call.settingsFailure(failure)
        } catch (_: SQLException) {
            return@put call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("ASSISTANT_UNAVAILABLE", tr("No se pudo abrir el asistente de la empresa.")))
        }
        call.respond(HttpStatusCode.OK, answer)
    }

    get("/server/assistant/usage") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, notServerUser) ?: return@get
        val assistant = admin ?: return@get call.unavailable()
        val period = month(call.request.queryParameters["month"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("MONTH_INVALID", tr("El mes debe tener el formato AAAA-MM.")))
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { assistant.usage(actor, period) })
    }
}

private suspend inline fun <reified T> ApplicationCall.json(): T? {
    val text = runCatching { receiveText() }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 10_000 } ?: return null
    return runCatching { ServerJson.decodeFromString<T>(text) }.getOrNull()
}
