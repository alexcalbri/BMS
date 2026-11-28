package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.AssistantCompanySettingsRequest
import com.ideasdeveloper.bms.AssistantCompanySettingsResponse
import com.ideasdeveloper.bms.AssistantCompanyUsageResponse
import com.ideasdeveloper.bms.AssistantConversationDetailResponse
import com.ideasdeveloper.bms.AssistantConversationListResponse
import com.ideasdeveloper.bms.AssistantCreateConversationRequest
import com.ideasdeveloper.bms.AssistantDayUsage
import com.ideasdeveloper.bms.AssistantDecisionRequest
import com.ideasdeveloper.bms.AssistantIds
import com.ideasdeveloper.bms.AssistantSendMessageRequest
import com.ideasdeveloper.bms.AssistantStatusResponse
import com.ideasdeveloper.bms.AssistantUpdateConversationRequest
import com.ideasdeveloper.bms.AssistantUserUsage
import com.ideasdeveloper.bms.server.company.api.CompanyErrorResponse
import com.ideasdeveloper.bms.server.company.application.ServerWorkspace
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleAccessDecision
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.UserSessionLookup
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeStringUtf8
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val AssistantJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// Request bodies of these routes are small JSON (a message is at most 20 000 characters).
private const val MaxBodyChars = 100_000

/** The JSON answer of a company request, or a refusal to send instead. */
private sealed interface Answer {
    data class Ok(val status: HttpStatusCode, val body: Any?) : Answer
    data class Refused(val status: HttpStatusCode, val code: String, val message: String) : Answer
}

private fun refused(status: Int, code: String, message: String) = Answer.Refused(HttpStatusCode.fromValue(status), code, message)

private fun ApplicationCall.sessionToken(): String =
    (request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substringAfter(' ')
        ?: request.cookies[ModuleAccessService.TokenCookie]).orEmpty().trim()

/**
 * Company routes of the assistant (GLOBAL_AGENT.md, Contract): the session of the request decides the company and the
 * user; nothing in a body or path can name another user. [service] is null in the route matrix test.
 */
fun Route.assistantRoutes(service: AssistantService?) {
    val notFoundConversation = { refused(404, "CONVERSATION_NOT_FOUND", tr("Esa conversacion no existe.")) }

    /** Validates the session, opens the company and runs [block] on an IO thread; answers JSON. */
    suspend fun ApplicationCall.company(statusRoute: Boolean = false, block: (AssistantService, AssistantContext, Connection) -> Answer) {
        response.headers.append(HttpHeaders.CacheControl, "no-store")
        val assistant = service ?: return respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("ASSISTANT_UNAVAILABLE", tr("El asistente no esta disponible en este servidor.")))
        val answer = try {
            withContext(Dispatchers.IO) { resolve(assistant, this@company, statusRoute, block) }
        } catch (_: SQLException) {
            refused(503, "ASSISTANT_UNAVAILABLE", tr("No se pudo abrir el asistente de la empresa."))
        }
        when (answer) {
            is Answer.Ok -> if (answer.body == null) respond(answer.status, "") else respond(answer.status, answer.body)
            is Answer.Refused -> respond(answer.status, CompanyErrorResponse(answer.code, answer.message))
        }
    }

    get("/companies/me/assistant/status") {
        call.company(statusRoute = true) { assistant, context, tenant -> Answer.Ok(HttpStatusCode.OK, assistant.status(assistant.availability(tenant, context), context)) }
    }

    get("/companies/me/assistant/conversations") {
        val archived = call.request.queryParameters["archived"] == "true"
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        val before = call.request.queryParameters["before"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        call.company { assistant, context, _ ->
            val rows = context.store.conversations(context.caller.userId, context.caller.ownerKey, archived, limit, before)
            val engine = assistant.engine(context.store)
            val listed = rows.map { conversation ->
                if (engine.expireIfDue(conversation.id)) context.store.conversation(conversation.id, context.caller.userId, context.caller.ownerKey) ?: conversation else conversation
            }
            Answer.Ok(
                HttpStatusCode.OK,
                AssistantConversationListResponse(
                    listed.map { AssistantView.conversation(it, if (it.state == AssistantConversation.Awaiting) context.store.pending(it.id).count { p -> p.status == AssistantPendingCall.Pending } else 0) },
                    nextBefore = rows.takeIf { it.size == limit }?.lastOrNull()?.updatedAt?.toString(),
                ),
            )
        }
    }

    post("/companies/me/assistant/conversations") {
        val request = call.bodyAs<AssistantCreateConversationRequest>() ?: AssistantCreateConversationRequest()
        call.company { assistant, context, tenant ->
            val availability = assistant.availability(tenant, context)
            if (!context.mayUse) return@company refused(403, AssistantIds.ReasonPermissionRequired, availability.unavailable?.takeIf { it.reason == AssistantIds.ReasonPermissionRequired }?.message ?: tr("No tienes permiso para usar el asistente."))
            if (!availability.visible) return@company refused(403, availability.unavailable?.reason ?: AssistantIds.ReasonCompanyDisabled, availability.unavailable?.message.orEmpty())
            val key = "${context.caller.companyCode}/${context.caller.userId}/${context.caller.ownerKey}"
            if (!assistant.limits.allowNewConversation(key)) return@company refused(429, "ASSISTANT_RATE_LIMITED", tr("Demasiadas conversaciones nuevas; espera un minuto."))
            // Counted and created in one step: two requests at once cannot both take the last place.
            val conversation = context.store.createConversationCapped(
                context.caller.userId, context.caller.ownerKey, request.title?.trim()?.take(80).orEmpty(), Instant.now(), AssistantService.MaxConversationsPerUser,
            ) ?: return@company refused(409, "TOO_MANY_CONVERSATIONS", tr("Tienes demasiadas conversaciones guardadas; borra algunas para empezar otra."))
            Answer.Ok(HttpStatusCode.Created, AssistantView.conversation(conversation, 0))
        }
    }

    get("/companies/me/assistant/conversations/{id}") {
        val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        call.company { assistant, context, _ ->
            id ?: return@company notFoundConversation()
            // Only the owner's conversation is touched (expiry included).
            context.store.conversation(id, context.caller.userId, context.caller.ownerKey) ?: return@company notFoundConversation()
            assistant.engine(context.store).expireIfDue(id)
            val conversation = context.store.conversation(id, context.caller.userId, context.caller.ownerKey) ?: return@company notFoundConversation()
            val pending = context.store.pending(id)
            val labels = assistant.access.mcpTools().associate { it.qualifiedName to "${it.moduleName}: ${it.tool.title}" }
            Answer.Ok(
                HttpStatusCode.OK,
                AssistantConversationDetailResponse(
                    AssistantView.conversation(conversation, pending.count { it.status == AssistantPendingCall.Pending }),
                    AssistantView.items(context.store.messages(id), pending, labels),
                    pending.filter { it.status == AssistantPendingCall.Pending }.map(AssistantView::confirmation),
                ),
            )
        }
    }

    put("/companies/me/assistant/conversations/{id}") {
        val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val request = call.bodyAs<AssistantUpdateConversationRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
        call.company { _, context, _ ->
            id ?: return@company notFoundConversation()
            val updated = context.store.updateConversation(id, context.caller.userId, context.caller.ownerKey, request.title?.trim()?.take(80), request.archived, Instant.now())
                ?: return@company notFoundConversation()
            Answer.Ok(HttpStatusCode.OK, AssistantView.conversation(updated, 0))
        }
    }

    delete("/companies/me/assistant/conversations/{id}") {
        val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        call.company { _, context, _ ->
            id ?: return@company notFoundConversation()
            val conversation = context.store.conversation(id, context.caller.userId, context.caller.ownerKey) ?: return@company notFoundConversation()
            if (conversation.state == AssistantConversation.Running && (conversation.leaseUntil?.isAfter(Instant.now()) == true)) {
                return@company refused(409, "CONVERSATION_BUSY", tr("El asistente todavia esta respondiendo en esta conversacion."))
            }
            if (!context.store.deleteConversation(id, context.caller.userId, context.caller.ownerKey)) return@company notFoundConversation()
            Answer.Ok(HttpStatusCode.NoContent, null)
        }
    }

    post("/companies/me/assistant/conversations/{id}/messages") {
        val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val request = call.bodyAs<AssistantSendMessageRequest>()
            ?: return@post call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("MESSAGE_INVALID", tr("Escribe un mensaje.")))
        call.turn(service) { assistant, context, tenant ->
            id ?: throw AssistantRefusal(404, "CONVERSATION_NOT_FOUND", tr("Esa conversacion no existe."))
            val runtime = assistant.runtime(tenant, assistant.availability(tenant, context))
            val engine = assistant.engine(context.store)
            val turn = engine.prepareMessage(context.caller, id, request.text)
            val toolbox = assistant.toolbox(context);
            PreparedTurn({ sink: AssistantSink -> engine.runMessage(context.caller, runtime, toolbox, turn, request.text, sink) }) {
                engine.abandon(turn.conversation.id, turn.token, AssistantConversation.Idle)
            }
        }
    }

    post("/companies/me/assistant/confirmations/{id}") {
        val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val request = call.bodyAs<AssistantDecisionRequest>()
        val approve = when (request?.decision) {
            "approve" -> true
            "reject" -> false
            else -> return@post call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("DECISION_INVALID", tr("La decision debe ser approve o reject.")))
        }
        call.turn(service) { assistant, context, tenant ->
            id ?: throw AssistantRefusal(404, "CONFIRMATION_NOT_FOUND", tr("Esa confirmacion no existe."))
            val availability = assistant.availability(tenant, context)
            // Rejecting needs no model request when other calls still wait; approving always needs the assistant available.
            val runtime = assistant.runtime(tenant, availability)
            val engine = assistant.engine(context.store)
            val decision = engine.prepareDecision(context.caller, id, approve)
            val toolbox = assistant.toolbox(context);
            PreparedTurn({ sink: AssistantSink -> engine.runDecision(context.caller, runtime, toolbox, decision, sink) }) {
                decision.token?.let { engine.abandon(decision.conversationId, it, AssistantConversation.Awaiting) }
            }
        }
    }

    get("/companies/me/assistant/settings") {
        call.company { assistant, context, tenant ->
            if (!context.mayManage) return@company manageRefusal(context, tr("Solo el dueno de la empresa configura el asistente."))
            Answer.Ok(HttpStatusCode.OK, companySettings(assistant.availability(tenant, context)))
        }
    }

    put("/companies/me/assistant/settings") {
        val request = call.bodyAs<AssistantCompanySettingsRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
        call.company { assistant, context, tenant ->
            if (!context.mayManage) return@company manageRefusal(context, tr("Solo el dueno de la empresa configura el asistente."))
            // The workspace has no switch and no key of its own: they are the server's.
            if (context.workspace) return@company refused(409, "USE_SERVER_SETTINGS", tr("En el espacio del servidor el asistente se configura en los ajustes del servidor."))
            try {
                val before = AssistantCompanyConfig.read(tenant)
                // A server person (owner, or administrator with server.assistant.manage) is audited in the server audit too,
                // in the same transaction as the change; a business owner's own change only goes to the company's log.
                val server = context.serverPerson
                val audited = if (server == null) emptyList() else buildList<Pair<String, Any?>> {
                    if (request.enabled != null && request.enabled != before.enabled) {
                        add("enabled.from" to before.enabled)
                        add("enabled.to" to request.enabled)
                        add("disclosureAccepted" to (request.acceptDisclosure == true))
                    }
                    if (!request.apiKey.isNullOrBlank()) add("key" to "(cambiada)")
                    else if (request.removeApiKey == true && before.ownKeySet) add("key" to "(quitada)")
                    if (isNotEmpty()) add("via" to "switcher")
                }
                val apply = { AssistantCompanyConfig.update(tenant, assistant.access.secrets, context.actor, request.enabled, request.acceptDisclosure, request.apiKey, request.removeApiKey) }
                if (server == null) apply() else assistant.serverConfig.auditedCompanyChange(server.audit, context.caller.companyCode, audited, apply)
            } catch (invalid: AssistantSettingsException) {
                return@company refused(invalid.status, invalid.code, invalid.message.orEmpty())
            }
            Answer.Ok(HttpStatusCode.OK, companySettings(assistant.availability(tenant, context)))
        }
    }

    get("/companies/me/assistant/usage") {
        val month = call.request.queryParameters["month"]
        call.company { assistant, context, tenant ->
            if (!context.mayManage) return@company manageRefusal(context, tr("Solo el dueno de la empresa ve el consumo del asistente."))
            val period = month(month) ?: return@company refused(400, "MONTH_INVALID", tr("El mes debe tener el formato AAAA-MM."))
            val availability = assistant.availability(tenant, context)
            val rows = context.store.usage(period)
            val names = if (context.workspace) rows.map { it.userId }.toSet().associateWith { assistant.access.serverPersonActor(it, context.caller.companyCode).name }
            else names(tenant, rows.map { it.userId }.toSet())
            val users = rows.groupBy { it.userId to it.keySource }.map { (key, list) ->
                AssistantUserUsage(
                    userId = key.first.toString(), name = names[key.first] ?: "Usuario", requests = list.sumOf { it.requests },
                    inputTokens = list.sumOf { it.usage.inputTokens }, outputTokens = list.sumOf { it.usage.outputTokens },
                    cacheReadInputTokens = list.sumOf { it.usage.cacheReadInputTokens }, cacheCreationInputTokens = list.sumOf { it.usage.cacheCreationInputTokens },
                    countedTokens = list.sumOf { it.countedTokens }, keySource = key.second,
                )
            }.sortedByDescending { it.countedTokens }
            val days = rows.groupBy { it.day }.map { (day, list) -> AssistantDayUsage(day.toString(), list.sumOf { it.countedTokens }, list.sumOf { it.requests }) }
            Answer.Ok(
                HttpStatusCode.OK,
                AssistantCompanyUsageResponse(period.toString(), availability.monthlyLimit, rows.filter { it.keySource in availability.limitedSources }.sumOf { it.countedTokens }, users, days),
            )
        }
    }
}

/** Why this session may not change or read the assistant's settings here (the person's own right, not the empresa permission). */
private fun manageRefusal(context: AssistantContext, plain: String): Answer =
    if (context.serverPerson != null) refused(403, "SERVER_PERMISSION_REQUIRED", tr("Necesitas el permiso {0}.", AssistantIds.ManagePermission))
    else refused(403, "BUSINESS_OWNER_REQUIRED", plain)

private fun companySettings(availability: AssistantAvailability) = AssistantCompanySettingsResponse(
    enabled = availability.company.enabled,
    serverEnabled = availability.server.enabled,
    companyAllowed = availability.allowance.allowed,
    ownKeySet = availability.company.ownKeySet,
    ownKeyHint = availability.company.ownKeyHint,
    serverKeySet = availability.server.keySet,
    monthlyLimitTokens = availability.monthlyLimit,
    limitAppliesToOwnKey = availability.server.limitOwnKeyCompanies,
    model = availability.server.model,
    retentionDays = availability.server.retentionDays,
    disclosure = assistantDisclosure(),
)

internal fun month(value: String?): YearMonth? = if (value.isNullOrBlank()) YearMonth.now(java.time.ZoneOffset.UTC) else try {
    YearMonth.parse(value)
} catch (_: DateTimeParseException) {
    null
}

private fun names(tenant: Connection, ids: Set<UUID>): Map<UUID, String> {
    if (ids.isEmpty()) return emptyMap()
    return tenant.prepareStatement("SELECT id, COALESCE(NULLIF(display_name, ''), postgres_role) FROM application_users WHERE id = ANY (?)").use { query ->
        query.setArray(1, tenant.createArrayOf("uuid", ids.toTypedArray()))
        query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getString(2)) } }
    }
}

private suspend inline fun <reified T> ApplicationCall.bodyAs(): T? {
    val text = runCatching { receiveText() }.getOrNull() ?: return null
    if (text.length > MaxBodyChars) return null
    if (text.isBlank()) return null
    return runCatching { AssistantJson.decodeFromString<T>(text) }.getOrNull()
}

private fun resolve(assistant: AssistantService, call: ApplicationCall, statusRoute: Boolean, block: (AssistantService, AssistantContext, Connection) -> Answer): Answer {
    // The server workspace: its people (the server owner and the administrators) have a central session, not a company one.
    if (assistant.access.companyCode(call)?.equals(ServerWorkspace.Code, ignoreCase = true) == true) {
        val person = assistant.serverPerson(call.sessionToken())
        if (person != null) {
            val tenant = assistant.access.tenantConnectionForCompany(ServerWorkspace.Code)
                ?: return refused(403, "company_not_found", tr("La empresa no esta activa."))
            return tenant.use { connection ->
                val context = assistant.serverContext(connection, person) ?: return@use unsupportedWorkspace(statusRoute)
                block(assistant, context, connection)
            }
        }
    }
    val (companyCode, userId) = when (val lookup = assistant.access.userSession(call)) {
        is UserSessionLookup.Denied -> return when (val decision = lookup.decision) {
            is ModuleAccessDecision.Unauthorized -> Answer.Refused(HttpStatusCode.Unauthorized, decision.code, decision.message)
            is ModuleAccessDecision.Forbidden -> Answer.Refused(HttpStatusCode.Forbidden, decision.code, decision.message)
            ModuleAccessDecision.Allowed -> Answer.Refused(HttpStatusCode.Forbidden, "forbidden", "")
        }
        is UserSessionLookup.Granted -> lookup.companyCode to lookup.userId
    }
    val tenant = assistant.access.tenantConnectionForCompany(companyCode)
        ?: return refused(403, "company_not_found", tr("La empresa no esta activa."))
    return tenant.use { connection ->
        val context = try {
            assistant.context(connection, companyCode, userId, call.sessionToken())
        } catch (refusal: AssistantRefusal) {
            return@use refused(refusal.status, refusal.code, refusal.message.orEmpty())
        }
            ?: return@use unsupportedWorkspace(statusRoute)
        block(assistant, context, connection)
    }
}

/** What is left out: the workspace's staff accounts (company users that sign in with its code). */
private fun unsupportedWorkspace(statusRoute: Boolean): Answer =
    if (statusRoute) {
        Answer.Ok(
            HttpStatusCode.OK,
            AssistantStatusResponse(
                visible = false, available = false, reason = AssistantIds.ReasonServerWorkspace,
                message = tr("El asistente no esta disponible en el espacio del servidor."), workspace = true, disclosure = assistantDisclosure(),
            ),
        )
    } else {
        refused(403, AssistantIds.ReasonServerWorkspace, tr("El asistente no esta disponible en el espacio del servidor."))
    }

/** A turn taken but not started: what runs it and how to give the conversation back when it never runs. */
internal class PreparedTurn(val run: (AssistantSink) -> Unit, val abandon: () -> Unit)

/**
 * A turn: everything that can be refused is checked first and answered as JSON; then the stream starts and the turn
 * runs on the assistant's own threads, its events going out as they come (Server-Sent Events, a ping every 15 s).
 * Closing the connection, or a client too slow to read, cancels the turn. [prepare] returns the turn to run.
 */
private suspend fun ApplicationCall.turn(
    service: AssistantService?,
    prepare: (AssistantService, AssistantContext, Connection) -> PreparedTurn,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    val assistant = service ?: return respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("ASSISTANT_UNAVAILABLE", tr("El asistente no esta disponible en este servidor.")))
    var slot: Pair<String, String>? = null
    var prepared: PreparedTurn? = null
    val answer = try {
        withContext(Dispatchers.IO) {
            resolve(assistant, this@turn, statusRoute = false) { _, context, tenant ->
                val user = "${context.caller.companyCode}/${context.caller.userId}/${context.caller.ownerKey}"
                if (!assistant.limits.allow(user)) return@resolve refused(429, "ASSISTANT_RATE_LIMITED", tr("Demasiadas solicitudes al asistente; espera un minuto."))
                assistant.limits.openStream(user, context.caller.companyCode)?.let { return@resolve refused(it.status, it.code, it.message.orEmpty()) }
                try {
                    prepared = prepare(assistant, context, tenant)
                    slot = user to context.caller.companyCode
                    Answer.Ok(HttpStatusCode.OK, null)
                } catch (refusal: AssistantRefusal) {
                    assistant.limits.release(user, context.caller.companyCode)
                    refused(refusal.status, refusal.code, refusal.message.orEmpty())
                } catch (failure: Throwable) {
                    assistant.limits.release(user, context.caller.companyCode)
                    throw failure
                }
            }
        }
    } catch (_: SQLException) {
        // Even closing the connection after the conversation was taken can fail: it must not keep its lease.
        prepared?.abandon?.invoke()
        refused(503, "ASSISTANT_UNAVAILABLE", tr("No se pudo abrir el asistente de la empresa."))
    } catch (failure: Throwable) {
        prepared?.abandon?.invoke()
        slot?.let { (user, company) -> assistant.limits.release(user, company) }
        throw failure
    }
    val turn = prepared?.run
    if (answer is Answer.Refused || turn == null) {
        if (answer is Answer.Refused) prepared?.abandon?.invoke()
        slot?.let { (user, company) -> assistant.limits.release(user, company) }
        val refusal = answer as? Answer.Refused ?: refused(500, "INTERNAL", tr("No se pudo completar la respuesta."))
        if (refusal.status == HttpStatusCode.TooManyRequests || refusal.status == HttpStatusCode.ServiceUnavailable) response.header(HttpHeaders.RetryAfter, "60")
        return respond(refusal.status, CompanyErrorResponse(refusal.code, refusal.message))
    }
    val language = I18n.language()
    // The slot goes back when the stream really ends (the engine may write it after stream() returns).
    stream(language, assistant.dispatcher, onEnd = { slot?.let { (user, company) -> assistant.limits.release(user, company) } }, turn = turn)
}

/**
 * Writes the events of [turn] as SSE frames. Nothing ever blocks on the client: the turn hands frames to a bounded
 * buffer without waiting (a full buffer means the client does not read: the turn is cancelled), and each write waits
 * at most [writeTimeout]. When the client goes away the buffer is closed and the turn stops at its next check.
 *
 * The engine may run the writer after this function returns, so the end of the stream is reported through [onEnd],
 * called exactly once: when the writer is done AND the turn has ended (it gave its conversation back), when responding
 * fails before the writer runs, or at the latest after [watchdogMillis] (a writer the engine never started).
 */
internal suspend fun ApplicationCall.stream(
    language: String,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    writeTimeout: Long = WriteTimeoutMillis,
    pingMillis: Long = PingMillis,
    watchdogMillis: Long = WatchdogMillis,
    onEnd: () -> Unit = {},
    turn: (AssistantSink) -> Unit,
) {
    // Nginx must pass every frame at once (scripts/ubuntu/nginx-site.sh also turns buffering off for these routes).
    response.header("X-Accel-Buffering", "no")
    val frames = Channel<String>(capacity = FrameBuffer)
    val closed = AtomicBoolean(false)
    val ended = AtomicBoolean(false)
    var watchdog: kotlinx.coroutines.Job? = null
    fun end() {
        if (ended.compareAndSet(false, true)) {
            watchdog?.cancel()
            onEnd()
        }
    }
    val sink = object : AssistantSink {
        override fun event(name: String, data: JsonObject) {
            if (closed.get()) return
            // Never wait for the client: when it does not keep up, the turn stops.
            if (!frames.trySend("event: $name\ndata: $data\n\n").isSuccess) {
                closed.set(true)
                frames.close()
            }
        }
        override val cancelled: Boolean get() = closed.get()
    }
    watchdog = application.launch {
        kotlinx.coroutines.delay(watchdogMillis)
        closed.set(true)
        frames.cancel()
        end()
    }
    try {
        respondBytesWriter(ContentType.Text.EventStream.withCharset(Charsets.UTF_8), HttpStatusCode.OK) {
            var job: kotlinx.coroutines.Job? = null
            try {
                coroutineScope {
                    job = launch(dispatcher + I18n.contextElement(language)) {
                        try {
                            turn(sink)
                        } finally {
                            frames.close()
                        }
                    }
                    // A failed write must stop the turn BEFORE coroutineScope waits for its (blocking) child, so the flag is
                    // set here, inside the scope, and not only in the outer catch that runs after the child has ended.
                    try {
                        while (true) {
                            val next = withTimeoutOrNull(pingMillis) { frames.receiveCatching() }
                            val text = when {
                                next == null -> ": ping\n\n"
                                else -> next.getOrNull() ?: break
                            }
                            withTimeout(writeTimeout) {
                                writeStringUtf8(text)
                                flush()
                            }
                        }
                    } finally {
                        closed.set(true)
                        frames.cancel()
                    }
                }
            } catch (failure: Throwable) {
                // The client went away or stopped reading: the turn stops at its next check and gives the conversation back.
                closed.set(true)
                frames.cancel()
                throw failure
            } finally {
                closed.set(true)
                frames.cancel()
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { job?.join() }
                end()
            }
        }
    } catch (failure: Throwable) {
        closed.set(true)
        frames.cancel()
        end()
        throw failure
    }
}

private const val PingMillis = 15_000L
private const val WriteTimeoutMillis = 30_000L
private const val FrameBuffer = 1024
// Longer than any turn can last (10 min) plus one model request (about 8 min).
private const val WatchdogMillis = 30L * 60_000
