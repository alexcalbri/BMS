package com.ideasdeveloper.bms.modules.agentes

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.i18n.localizedPayload
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.McpToolException
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModuleDefinition
import com.ideasdeveloper.bms.server.modules.ModuleMcp
import com.ideasdeveloper.bms.server.modules.ModuleJob
import com.ideasdeveloper.bms.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.server.modules.ServerMcpModuleId
import com.ideasdeveloper.bms.server.modules.ServerModule
import com.ideasdeveloper.bms.server.modules.ServerModuleProvider
import com.ideasdeveloper.bms.server.modules.UserModuleAccess
import com.ideasdeveloper.bms.server.modules.requireModuleAccess
import com.ideasdeveloper.bms.ui.ModuleUiEntry
import com.ideasdeveloper.bms.ui.UiJson
import com.ideasdeveloper.bms.ui.UiNotice
import com.ideasdeveloper.bms.ui.UiScreen
import com.ideasdeveloper.bms.ui.UiTone
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AI agents (MCP clients) connected by each user. The endpoint authenticates the agent token, loads
 * the permissions of the token's user at that moment and exposes only the tools of enabled modules
 * that the user may use, further narrowed by the token (modules, read only).
 */
object AgentesServerModule : ServerModule {
    private val log = LoggerFactory.getLogger(AgentesServerModule::class.java)
    // One answer for every company a business owner's agent may not act in, so it never tells whether the company exists.
    private val OutOfReach = msg("No puedes actuar en esa empresa: no pertenece a tu grupo o no esta disponible.")
    // What this company's log keeps when a call in another company of the group fails; the agent gets the real message.
    private val ElsewhereDetail = msg("No se completo en la empresa de destino.")
    private val rateLimiter = AgentRateLimiter(perMinute = 120)
    private const val ActivityDays = 180
    private const val MaxBodyBytes = 1_000_000

    override val migrationPaths = listOf(
        "modules/agentes/migrations/V001__create_agent_tokens.sql",
        "modules/agentes/migrations/V002__refresh_reuse_detection.sql",
        "modules/agentes/migrations/V003__refresh_history.sql",
        "modules/agentes/migrations/V004__group_scope.sql",
        "modules/agentes/migrations/V005__server_admin_agents.sql",
        "modules/agentes/migrations/V006__token_opener.sql",
    )

    override val runtimeTables = listOf("agent_tokens", "agent_activity", "agent_refresh_history", "agent_group_activity")

    // Its own MCP (managing connections), offered through the same bridge as every module.
    override val mcp: ModuleMcp
        get() = AgentesMcpTools.mcp

    override val definition = ModuleDefinition(
        id = AgentesModule.id,
        displayName = AgentesModule.displayName,
        description = "Cada usuario conecta su propio modelo o agente de IA por MCP, limitado a las funciones que ese usuario tiene permitidas.",
        version = "0.7.2",
        minCoreVersion = "0.13.0",
        permissions = listOf(
            ModulePermissionDefinition(
                AgentesScreens.Connect,
                "Conectar agentes IA",
                "Permite conectar su propio modelo o agente de IA. El agente solo puede usar las funciones que el usuario tiene permitidas.",
            ),
            ModulePermissionDefinition(
                AgentesScreens.Manage,
                "Administrar agentes IA",
                "Permite ver y revocar los agentes que conecto cualquier usuario de la empresa, con su actividad.",
            ),
        ),
        ui = ModuleUiEntry(type = "native", entry = AgentesScreens.UiRoute, home = "home"),
    )

    override val jobs = listOf(
        ModuleJob("purge-activity", Duration.ofDays(1)) { context ->
            AgentTokens.purgeActivity(context.tenant, ActivityDays)
            AgentTokens.purgeRefreshHistory(context.tenant, AgentTokens.RefreshHistoryRetentionDays)
        },
    )

    override fun routes(route: Route, access: ModuleAccessService) {
        AgentOAuth.routes(route, access)
        // Core tells when an account's credentials change (two-step verification on, password reset): its agents
        // (access and refresh tokens) stop working and must be connected again (SECURITY_FIXES review, item 2).
        com.ideasdeveloper.bms.server.auth.application.AccountSecurityEvents.subscribe(AgentesModule.id) { event ->
            AgentTokens.revokeForCredentialsChange(access, event)
        }

        route.route(AgentesScreens.McpRoute) {
            post { call.handleMcp(access) }
            // No server-initiated stream: this server answers every request with JSON.
            get { call.methodNotAllowed() }
            delete { call.methodNotAllowed() }
        }

        route.route(AgentesScreens.UiRoute) {
            get("/{screen}") {
                val permissions = call.requireModuleAccess(access, AgentesModule.id) ?: return@get
                val screen = call.screenId(permissions) ?: return@get
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                call.respondScreen(access, permissions) { context -> AgentesScreens.build(screen, context, params) }
            }

            post("/{screen}") {
                val permissions = call.requireModuleAccess(access, AgentesModule.id) ?: return@post
                val screen = call.screenId(permissions) ?: return@post
                val params = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
                val values = runCatching {
                    UiJson.parseToJsonElement(call.receiveText()).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
                }.getOrNull().orEmpty()
                if (!permissions.has(AgentesScreens.Connect) && !permissions.has(AgentesScreens.Manage)) {
                    return@post call.respond(HttpStatusCode.Forbidden, AgentesErrorResponse("missing_permission", tr("No tienes permiso para esta accion.")))
                }
                call.respondScreen(access, permissions) { context -> action(context, screen, params, values) }
            }
        }
    }

    private fun action(context: AgentesScreens.Context, screen: String, params: Map<String, String>, values: Map<String, String>): UiScreen {
        fun show(target: String, notice: UiNotice, targetParams: Map<String, String> = params - "op", submitted: Map<String, String>? = null) =
            AgentesScreens.build(target, context, targetParams, notice, submitted)

        return when ("$screen:${params["op"]}") {
            "new:create" -> {
                if (!context.permissions.has(AgentesScreens.Connect)) return show("team", UiNotice(tr("No tienes permiso para conectar agentes."), UiTone.Danger), emptyMap())
                val request = try {
                    AgentesScreens.parseNewToken(values, context.toolModules.map { it.first })
                } catch (failure: AgentesValidationException) {
                    return show("new", UiNotice(failure.message.orEmpty(), UiTone.Danger), emptyMap(), values)
                }
                val (token, value) = AgentTokens.create(
                    context.tenant, context.permissions.companyCode, context.owner,
                    request.name, request.allowedModules, request.readOnly, request.expiresAt,
                    groupScope = request.groupScope && context.permissions.businessOwner && !context.owner.serverOwner,
                    // Who is really behind this session (switchers): the connection follows that person's credentials.
                    opener = context.opener,
                )
                AgentesScreens.created(context, token, value)
            }
            "token:revoke" -> {
                val token = params["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { AgentTokens.find(context.tenant, it) }
                    ?.takeIf { context.owns(it) && context.permissions.has(AgentesScreens.Connect) || context.canManage }
                    ?: return show(AgentesScreens.home(context.permissions), UiNotice(tr("Ese agente no existe o no es tuyo."), UiTone.Warning), emptyMap())
                AgentTokens.revoke(context.tenant, token.id, context.owner.name)
                show("token", UiNotice(tr("Acceso revocado. El agente ya no puede usar BMS."), UiTone.Success))
            }
            else -> show(screen, UiNotice(tr("Accion no reconocida."), UiTone.Warning))
        }
    }

    // ---- MCP endpoint ----

    private suspend fun ApplicationCall.handleMcp(access: ModuleAccessService) {
        if (!access.isInstalled(AgentesModule.id)) return respond(HttpStatusCode.NotFound)
        if (!sameOrigin()) return respond(HttpStatusCode.Forbidden, AgentesErrorResponse("invalid_origin", "Origen no permitido."))
        val token = request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
            .orEmpty()
        val companyCode = AgentTokens.companyOf(token) ?: return unauthorized("Falta el token del agente o no es valido.")
        if (!rateLimiter.allow(AgentTokens.hash(token))) {
            response.header(HttpHeaders.RetryAfter, "60")
            return respond(HttpStatusCode.TooManyRequests, AgentesErrorResponse("rate_limited", "Demasiadas solicitudes; espera un minuto."))
        }
        val body = readBoundedText(MaxBodyBytes) ?: return respond(HttpStatusCode.PayloadTooLarge)
        val message = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: return respondJson(HttpStatusCode.BadRequest, AgentesMcp.error(JsonNull, AgentesMcp.ParseError, "JSON invalido."))

        val outcome = withContext(Dispatchers.IO) {
            val tenant = access.tenantConnectionForCompany(companyCode) ?: return@withContext null
            tenant.use { connection ->
                try {
                    // A server administrator's audit-only agent never takes the path of the other tokens.
                    adminProtocol(connection, access, companyCode, token)?.let { protocol ->
                        return@use protocol.map { it.handle(message) }
                    }
                    val session = authenticate(connection, access, companyCode, token) ?: return@use null
                    AgentTokens.touch(connection, session.token.id)
                    Result.success(session.protocol(connection, access).handle(message))
                } catch (failure: SQLException) {
                    log.warn("Agent request for company {} failed: {}", companyCode, failure.message)
                    Result.failure(failure)
                }
            }
        } ?: return unauthorized("El token no es valido, vencio, fue revocado o el usuario ya no tiene acceso.")

        val answer = outcome.getOrElse { failure ->
            if (failure is AdminAgentRevoked) return unauthorized("El token no es valido, vencio, fue revocado o el usuario ya no tiene acceso.")
            return respond(HttpStatusCode.ServiceUnavailable, AgentesErrorResponse("tenant_error", "No se pudo abrir la empresa."))
        }
        if (answer == null) respond(HttpStatusCode.Accepted) else respondJson(HttpStatusCode.OK, answer)
    }

    private class AgentSession(val token: AgentToken, val access: UserModuleAccess)

    // Re-check of switcher openers per use, cached a few seconds (busy agents call many times a minute).
    private val openerChecks = OpenerChecks()

    /**
     * The protocol of a server administrator's agent (ACTIVITY_LOG.md §7), or null when [value] is not
     * such a token (the other paths handle it). Its tools are exactly ModuleAccessService.serverAuditAgent's
     * (the two audit read tools), checked again on every call: when the administrator lost
     * server.audit.view, was deactivated or must change the password, the connection is revoked, audited
     * and the request refused (failure result, answered as 401). A token of another company is refused.
     */
    private fun adminProtocol(tenant: Connection, access: ModuleAccessService, companyCode: String, value: String): Result<AgentesMcp>? {
        if (!AgentTokens.moduleEnabled(tenant)) return null
        val token = AgentTokens.resolve(tenant, value)?.takeIf { it.serverAdmin } ?: return null
        val agent = (if (companyCode == com.ideasdeveloper.bms.server.company.application.ServerWorkspace.Code) access.serverAuditAgent(token.userId) else null)
            ?: return Result.failure(AdminAgentRevoked(revokeAdminAgent(tenant, access, token, "lost_access")))
        AgentTokens.touch(tenant, token.id)
        val visible = agent.tools.map { VisibleTool(it, agent.permissions) }
        return Result.success(
            AgentesMcp(
                instructions = adminInstructions(agent.name),
                tools = { visible },
                invoke = { tool, arguments -> runAdminTool(tenant, access, token, tool, arguments) },
                denied = { name ->
                    val logged = if (name.isBlank()) "(sin nombre)" else AgentesMcp.logTool(name)
                    AgentTokens.log(tenant, token.id, logged, "denied", msg("Herramienta desconocida o sin permiso."))
                    runCatching { access.auditServerAdminAgent(token.userId, agent.login, "server_admin.agent_denied", mapOf("agent" to token.name, "tool" to logged)) }
                        .onFailure { log.warn("Refused call of an administrator agent not audited: {}", it.message) }
                },
            ),
        )
    }

    private class AdminAgentRevoked(val revoked: Boolean) : Exception("revoked")

    // Revokes an administrator's agent and audits it; true when it was active.
    private fun revokeAdminAgent(tenant: Connection, access: ModuleAccessService, token: AgentToken, reason: String): Boolean {
        val revoked = AgentTokens.revoke(tenant, token.id, "Sistema (el administrador perdio el acceso)")
        if (revoked) {
            runCatching {
                access.auditServerAdminAgent(token.userId, token.userName, "server_admin.agent_revoked", mapOf("agent" to token.name, "tokenId" to token.id.toString(), "reason" to reason))
            }.onFailure { log.warn("Revocation of an administrator agent not audited: {}", it.message) }
        }
        return revoked
    }

    // One tool call of an administrator's agent: its rights are read again first; nothing but the audit tools ever runs.
    private fun runAdminTool(tenant: Connection, access: ModuleAccessService, token: AgentToken, tool: VisibleTool, arguments: JsonObject): ToolOutcome {
        if (!AgentTokens.stillActive(tenant, token.id)) return ToolOutcome.Failure("Esta conexion fue revocada o vencio.")
        val agent = access.serverAuditAgent(token.userId)
        if (agent == null) {
            revokeAdminAgent(tenant, access, token, "lost_access")
            return ToolOutcome.Failure("Tu usuario ya no puede leer la auditoria del servidor; esta conexion fue revocada.")
        }
        val allowed = agent.tools.firstOrNull { it.qualifiedName == tool.name && it.qualifiedName in com.ideasdeveloper.bms.server.activity.ServerAuditMcpTools.AdminAgentToolNames }
            ?: return ToolOutcome.Failure("Herramienta desconocida o sin permiso: ${tool.name}")
        if (arguments.containsKey(AgentesMcp.ActingCompany)) {
            AgentTokens.log(tenant, token.id, tool.name, "denied", msg("Herramienta desconocida o sin permiso."))
            return ToolOutcome.Failure("Esta herramienta no admite ${AgentesMcp.ActingCompany}.")
        }
        val call = McpToolCall(arguments, agent.permissions.withActivityActor(agent.permissions.activityActor.viaAgent(token.name)), tenant, access, token.name, allowed.moduleId)
        return try {
            val result = allowed.tool.handler(call)
            AgentTokens.log(tenant, token.id, tool.name, "ok")
            ToolOutcome.Success(result)
        } catch (failure: McpToolException) {
            AgentTokens.log(tenant, token.id, tool.name, "error", failure.message.orEmpty())
            ToolOutcome.Failure(failure.message.orEmpty())
        } catch (failure: Exception) {
            log.error("Administrator agent tool {} failed", tool.name, failure)
            runCatching { AgentTokens.log(tenant, token.id, tool.name, "error", msg("Error interno")) }
            ToolOutcome.Failure("No se pudo completar la operacion.")
        }
    }

    private fun adminInstructions(name: String): String =
        "Estas conectado a BMS en nombre de $name, administrador del servidor. Solo puedes leer la auditoria del servidor " +
            "(servidor_list_server_audit) y el registro de actividad de las empresas a su alcance (servidor_list_company_activity). " +
            "No puedes cambiar nada ni entrar a ninguna empresa. Cada consulta queda registrada a nombre del administrador."

    /** The token's session, when the token is active, its user still active and allowed to connect agents. */
    private fun authenticate(tenant: Connection, access: ModuleAccessService, companyCode: String, value: String): AgentSession? {
        // Companies that never enabled the module have no token tables.
        if (!AgentTokens.moduleEnabled(tenant)) return null
        val token = AgentTokens.resolve(tenant, value)?.takeIf { !it.serverAdmin } ?: return null
        // A connection opened through a switcher session works only while that person may still enter the company.
        if (token.openedThroughSwitcher && !openerChecks.allowed(token, companyCode) { access.openerMayEnter(companyCode, it) }) return null
        val userAccess = access.userAccess(tenant, companyCode, token.userId, token.serverOwner) ?: return null
        val own = userAccess.permissions(AgentesModule.id) ?: return null
        if (!own.has(AgentesScreens.Connect)) return null
        return AgentSession(token, userAccess)
    }

    private fun AgentSession.protocol(tenant: Connection, access: ModuleAccessService): AgentesMcp {
        // Each module's own MCP (instructions and tools), filtered to what this agent may use.
        val modules = access.moduleMcp()
        // A business owner's connection made for the group may act in the other companies of its owner group:
        // the ones it reaches now, and with the tools of the modules enabled in some of them.
        val reach = if (token.groupScope && this.access.businessOwner && !token.serverOwner && !this.access.serverOwner) {
            groupReach(tenant, access, this.access.companyCode, token.userId)
        } else {
            null
        }
        val ownerGroup = reach != null
        val visible = AgentesMcp.visibleTools(modules.flatMap { it.tools }, token, this.access, ownerGroup, reach.orEmpty())
        return AgentesMcp(
            instructions = AgentesMcp.instructions(instructions(token, this.access.companyCode, ownerGroup), modules, visible),
            tools = { visible },
            invoke = { tool, arguments -> runTool(tenant, access, tool, arguments) },
            denied = { name -> AgentTokens.log(tenant, token.id, if (name.isBlank()) "(sin nombre)" else AgentesMcp.logTool(name), "denied", msg("Herramienta desconocida o sin permiso.")) },
        )
    }

    /**
     * Runs a tool in the agent's own company, or in the company named by [AgentesMcp.ActingCompany]:
     * a server owner's agent in any company with the server owner's live access there, a business
     * owner's agent only in another company of its owner group and as the business owner of that
     * company (ModuleAccessService.groupOwnerAccess checks the group on every call). The activity log
     * stays in the agent's own company, refusals included.
     */
    private fun AgentSession.runTool(tenant: Connection, access: ModuleAccessService, tool: VisibleTool, arguments: JsonObject): ToolOutcome {
        // A request may carry several calls: one of them may have revoked this very connection.
        if (!AgentTokens.stillActive(tenant, token.id)) return ToolOutcome.Failure("Esta conexion fue revocada o vencio.")
        val own = this.access.companyCode
        val requested = AgentesMcp.actingCompany(arguments)?.takeIf { it != own }
        val toolArguments = JsonObject(arguments - AgentesMcp.ActingCompany)
        val logName = if (requested == null) tool.name else "${tool.name} @ ${AgentesMcp.logTarget(requested)}"
        // What the agent reads and what is kept in the log may differ: the log of this company never
        // carries details of another one.
        fun refuse(message: String, status: String = "error", logged: String = message): ToolOutcome {
            AgentTokens.log(tenant, token.id, logName, status, logged)
            return ToolOutcome.Failure(message)
        }
        // Asked for another company but not honoured: refuse, never run it here instead.
        if (AgentesMcp.malformedActingCompany(arguments)) return refuse(OutOfReach, "denied")
        if (requested != null && !tool.anyCompany) return refuse("Esta herramienta no admite ${AgentesMcp.ActingCompany}.", "denied")
        val target = requested
        if (target == null) {
            val permissions = tool.permissions
                ?: return refuse(
                    if (tool.ownerGroup) "${tool.registered.moduleName} no esta activo en tu empresa. Indica otra empresa de tu grupo en ${AgentesMcp.ActingCompany}."
                    else "${tool.registered.moduleName} no esta activo en el espacio del servidor. Indica la empresa en ${AgentesMcp.ActingCompany}."
                )
            return execute(tenant, tenant, access, tool, permissions, toolArguments, logName, elsewhere = false)
        }
        // The server's own tools never run in another company, whatever the caller sends.
        if (tool.registered.moduleId == ServerMcpModuleId) return refuse(OutOfReach, "denied")
        val asServerOwner = token.serverOwner && this.access.serverOwner
        val opened = try {
            when {
                asServerOwner -> access.serverOwnerAccess(target, token.userId)
                tool.ownerGroup && token.groupScope && this.access.businessOwner -> access.groupOwnerAccess(tenant, own, token.userId, target)
                else -> null
            }
        } catch (failure: Exception) {
            log.warn("Agent of company {} could not open company {}: {}", own, AgentesMcp.logTarget(target), failure.message)
            null
        }
        val (company, companyAccess) = opened
            ?: return if (asServerOwner) refuse("La empresa ${AgentesMcp.logTarget(target)} no existe, esta desactivada o no se pudo abrir.") else refuse(OutOfReach, "denied")
        return company.use { connection ->
            if (!asServerOwner) {
                // Agents must be allowed in the company where it acts too: the module enabled there and its owner allowed to connect them.
                val agents = companyAccess.permissions(AgentesModule.id)
                if (agents?.has(AgentesScreens.Connect) != true) {
                    return@use refuse("Agentes IA no esta activo en la empresa $target; activalo alli para que tu agente pueda actuar.", "denied", ElsewhereDetail)
                }
            }
            val permissions = companyAccess.permissions(tool.registered.moduleId)
                ?: return@use refuse(
                    "${tool.registered.moduleName} no esta activo en la empresa $target.",
                    logged = if (asServerOwner) "${tool.registered.moduleName} no esta activo en la empresa $target." else ElsewhereDetail,
                )
            if (!permissions.has(tool.registered.tool.permission)) return@use refuse("No tienes permiso para esta herramienta en la empresa $target.", "denied", ElsewhereDetail)
            execute(tenant, connection, access, tool, permissions, toolArguments, logName, elsewhere = !asServerOwner)
        }
    }

    private fun AgentSession.execute(
        logTenant: Connection,
        tenant: Connection,
        access: ModuleAccessService,
        tool: VisibleTool,
        permissions: ModulePermissions,
        arguments: JsonObject,
        logName: String,
        // A business owner's agent acting in another company of its group: the error text stays out of this company's log.
        elsewhere: Boolean,
    ): ToolOutcome {
        // The activity log names the real person: "Agente de <usuario>", with the company of origin
        // when a business owner's agent acts in another company of its group (ACTIVITY_LOG.md §1.4).
        val actor = if (elsewhere) {
            ActivityActor.groupAgent(permissions.activityActor, this.access.activityActor, this.access.companyCode, token.name)
        } else {
            permissions.activityActor.viaAgent(token.name)
        }
        val call = McpToolCall(arguments, permissions.withActivityActor(actor), tenant, access, token.name, tool.registered.moduleId)
        // The company where it acted keeps its own row too, so its people see who came from outside.
        fun there(outcome: String) {
            if (!elsewhere) return
            runCatching { AgentTokens.logGroupCall(tenant, this.access.companyCode, token.name, token.userName, tool.name, outcome) }
                .onFailure { log.warn("Group call {} not recorded in company {}: {}", tool.name, permissions.companyCode, it.message) }
        }
        return try {
            val result: JsonElement = tool.registered.tool.handler(call)
            AgentTokens.log(logTenant, token.id, logName, "ok")
            there("ok")
            ToolOutcome.Success(result)
        } catch (failure: McpToolException) {
            AgentTokens.log(logTenant, token.id, logName, "error", if (elsewhere) ElsewhereDetail else failure.message.orEmpty())
            there("error")
            ToolOutcome.Failure(failure.message.orEmpty())
        } catch (failure: Exception) {
            log.error("Agent tool {} failed for company {}", tool.name, permissions.companyCode, failure)
            runCatching { AgentTokens.log(logTenant, token.id, logName, "error", msg("Error interno")) }
            there("error")
            ToolOutcome.Failure("No se pudo completar la operacion.")
        }
    }

    private class Reach(val modules: Set<String>, val at: Long)

    // Modules enabled in the companies a business owner's agent reaches, per company of origin. Short lived:
    // it only decides which tools are offered; every call checks the group again.
    private val reachCache = ConcurrentHashMap<String, Reach>()
    private const val ReachMillis = 60_000L

    /**
     * Modules enabled in the other companies of the owner group that the business owner of [companyCode]
     * reaches now (same owner, Agentes IA enabled there), or null when it reaches none.
     */
    private fun groupReach(tenant: Connection, access: ModuleAccessService, companyCode: String, userId: UUID): Set<String>? {
        val now = System.currentTimeMillis()
        val key = "$companyCode:$userId"
        reachCache[key]?.takeIf { now - it.at < ReachMillis }?.let { return it.modules.takeIf(Set<String>::isNotEmpty) }
        if (reachCache.size > 5_000) reachCache.clear()
        val modules = try {
            access.ownerGroupCompanies(companyCode).filter { it.code != companyCode }.take(MaxGroupCompanies).flatMap { company ->
                access.groupOwnerAccess(tenant, companyCode, userId, company.code)?.let { (connection, there) ->
                    connection.use { if (there.permissions(AgentesModule.id)?.has(AgentesScreens.Connect) == true) there.enabledModuleIds() else emptySet() }
                }.orEmpty()
            }.toSet()
        } catch (failure: Exception) {
            log.warn("Could not read the owner group of company {}: {}", companyCode, failure.message)
            return null
        }
        reachCache[key] = Reach(modules, now)
        return modules.takeIf { it.isNotEmpty() }
    }

    private const val MaxGroupCompanies = 50

    private fun instructions(token: AgentToken, companyCode: String, ownerGroup: Boolean = false): String =
        "Estas conectado a BMS en nombre de ${token.userName} (empresa $companyCode). " +
            "Solo tienes las herramientas que ese usuario tiene permitidas" +
            (if (token.readOnly) ", en modo solo lectura" else "") +
            ". Si necesitas algo que no aparece en tus herramientas, pide al usuario que solicite el permiso a su administrador. " +
            (if (token.serverOwner) "Como dueno del servidor puedes usar las herramientas de los modulos en cualquier empresa del servidor indicando su codigo en ${AgentesMcp.ActingCompany}. " else "") +
            (if (ownerGroup) "Como dueno de la empresa puedes usar las herramientas de los modulos en las otras empresas de tu grupo indicando su codigo en ${AgentesMcp.ActingCompany}; " +
                "consulta cuales son con ${AgentesMcp.GroupCompaniesTool} y di siempre al usuario en que empresa vas a actuar. " else "") +
            "Todo lo que hagas queda registrado a nombre del usuario; confirma con el antes de crear, modificar o eliminar datos."

    // Browsers send Origin; a page on another site must not reach the endpoint with a user's token.
    private fun ApplicationCall.sameOrigin(): Boolean {
        val origin = request.header(HttpHeaders.Origin)?.takeIf { it.isNotBlank() && it != "null" } ?: return true
        val originHost = runCatching { URI(origin).host }.getOrNull() ?: return false
        val host = (request.header("X-Forwarded-Host") ?: request.header(HttpHeaders.Host) ?: request.origin.serverHost)
            .substringBefore(',').trim().substringBefore(':')
        return originHost.equals(host, ignoreCase = true)
    }

    // Points OAuth clients (claude.ai) to the metadata that tells them how to get a token.
    private suspend fun ApplicationCall.unauthorized(message: String) {
        val metadata = publicBase() + AgentOAuth.ProtectedResourcePath + AgentesScreens.McpRoute
        response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"BMS\", resource_metadata=\"$metadata\"")
        respond(HttpStatusCode.Unauthorized, AgentesErrorResponse("invalid_token", message))
    }

    private suspend fun ApplicationCall.methodNotAllowed() {
        response.header(HttpHeaders.Allow, "POST")
        respond(HttpStatusCode.MethodNotAllowed)
    }

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, element: JsonElement) =
        respondText(element.toString(), ContentType.Application.Json, status)

    // ---- Screens ----

    private suspend fun ApplicationCall.screenId(permissions: ModulePermissions): String? {
        val requested = parameters["screen"].orEmpty()
        val screen = if (requested == "home") AgentesScreens.home(permissions) else requested.takeIf { AgentesScreens.isScreen(it) }
        if (screen == null) respond(HttpStatusCode.NotFound, AgentesErrorResponse("UNKNOWN_SCREEN", tr("Seccion no encontrada.")))
        return screen
    }

    private suspend fun ApplicationCall.respondScreen(access: ModuleAccessService, permissions: ModulePermissions, block: (AgentesScreens.Context) -> UiScreen) {
        val endpoint = publicBase() + AgentesScreens.McpRoute
        val connection = withContext(Dispatchers.IO) { access.tenantConnectionForCompany(permissions.companyCode) }
            ?: return respond(HttpStatusCode.Forbidden, AgentesErrorResponse("TENANT_UNAVAILABLE", tr("No se pudo abrir la empresa.")))
        try {
            val screen = connection.use { tenant ->
                withContext(Dispatchers.IO) {
                    val owner = owner(tenant, permissions, access)
                    val userAccess = access.userAccess(tenant, permissions.companyCode, owner.userId, owner.serverOwner)
                    val userTools = userAccess?.let { user -> access.mcpTools().filter(user::mayUse) }.orEmpty()
                    val opener = TokenOpener.of(runCatching { access.sessionOpener(permissions) }.getOrNull())
                    block(AgentesScreens.Context(tenant, permissions, owner, userTools, endpoint, opener))
                }
            }
            respondText(UiJson.encodeToString(UiScreen.serializer(), localizedPayload(screen)), ContentType.Application.Json)
        } catch (failure: SQLException) {
            log.error("Agentes screen failed for company {}", permissions.companyCode, failure)
            respond(HttpStatusCode.InternalServerError, AgentesErrorResponse("DATABASE_ERROR", tr("No se pudo completar la operacion.")))
        }
    }

    private fun owner(tenant: Connection, permissions: ModulePermissions, access: ModuleAccessService): AgentOwner {
        val userId = permissions.userId ?: throw SQLException("Session without user")
        val identity = access.userIdentity(tenant, permissions)
        if (permissions.serverOwner) return AgentOwner(userId, serverOwner = true, name = identity?.name ?: "Dueno del servidor")
        identity?.let { return AgentOwner(userId, serverOwner = false, name = it.name) }
        val name = tenant.prepareStatement("SELECT postgres_role FROM application_users WHERE id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        return AgentOwner(userId, serverOwner = false, name = name ?: "Usuario")
    }
}

@Serializable
data class AgentesErrorResponse(val code: String, val message: String)

class AgentesServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = AgentesServerModule
}

/** Per-use re-check of the person behind a switcher-opened connection, cached [ttlMillis] per token and company. */
class OpenerChecks(private val ttlMillis: Long = 30_000, private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry(val allowed: Boolean, val at: Long)
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun allowed(token: AgentToken, companyCode: String, check: (com.ideasdeveloper.bms.server.auth.infrastructure.database.SessionOpener) -> Boolean): Boolean {
        val user = token.openerUser ?: return false
        val kind = if (token.openerKind == TokenOpener.Server) com.ideasdeveloper.bms.server.auth.infrastructure.database.SessionOpener.Server
            else com.ideasdeveloper.bms.server.auth.infrastructure.database.SessionOpener.Company
        val key = "${token.id}/$companyCode"
        val now = clock()
        entries[key]?.takeIf { now - it.at < ttlMillis }?.let { return it.allowed }
        if (entries.size > 10_000) entries.entries.removeIf { now - it.value.at >= ttlMillis }
        val allowed = runCatching { check(com.ideasdeveloper.bms.server.auth.infrastructure.database.SessionOpener(kind, user, token.openerCompany)) }.getOrDefault(false)
        entries[key] = Entry(allowed, now)
        return allowed
    }
}
