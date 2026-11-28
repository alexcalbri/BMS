package com.ideasdeveloper.bms.server.assistant

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The turn engine with a scripted model (no network, no real key) and the store in memory: tool loop, confirmation
 * pause and resume, expiry, parallel calls, stop reasons, limits and accounting, and the owner rule.
 */
class AssistantEngineTest {
    private var now = Instant.parse("2026-11-27T10:00:00Z")
    private val store = MemoryAssistantStore()
    private val ana = AssistantCaller("acme", "Acme", UUID.randomUUID(), "self", "Ana Ruiz", "es", businessOwner = false)
    private val owner = AssistantCaller("acme", "Acme", UUID.randomUUID(), "self", "Dueno", "es", businessOwner = true)
    private val search = FakeToolbox.tool("clientes_search_customers", readOnly = true)
    private val create = FakeToolbox.tool("clientes_create_customer", readOnly = false)
    private val remove = FakeToolbox.tool("clientes_delete_customer", readOnly = false, destructive = true)

    private fun runtime(keySource: String = "server", limit: Long? = null, limited: Set<String> = setOf("server")) =
        AssistantRuntime("claude-opus-5-5", "medium", if (keySource == "server") "sk-ant-server-test-key-000000" else "sk-ant-company-test-key-0000", keySource, limit, limited)

    private fun engine(models: FakeModelClients) = AssistantEngine(store, models, { now })

    private fun conversation(caller: AssistantCaller = ana) = store.createConversation(caller.userId, caller.ownerKey, "", now)

    private fun send(models: FakeModelClients, toolbox: AssistantToolbox, text: String, caller: AssistantCaller = ana, runtime: AssistantRuntime = runtime(), id: UUID = conversation(caller).id): Pair<UUID, RecordingSink> {
        val engine = engine(models)
        val prepared = engine.prepareMessage(caller, id, text)
        val sink = RecordingSink()
        engine.runMessage(caller, runtime, toolbox, prepared, text, sink)
        return id to sink
    }

    private fun decide(models: FakeModelClients, toolbox: AssistantToolbox, confirmation: String, approve: Boolean, caller: AssistantCaller = ana): RecordingSink {
        val engine = engine(models)
        val decision = engine.prepareDecision(caller, UUID.fromString(confirmation), approve)
        return RecordingSink().also { engine.runDecision(caller, runtime(), toolbox, decision, it) }
    }

    private fun lastUser(id: UUID): JsonArray = store.messages(id).last { it.role == Roles.User }.content

    @Test
    fun readOnlyToolsRunAtOnceAndEveryResultGoesBackInOneMessage() {
        val toolbox = FakeToolbox(search, FakeToolbox.tool("crm_search_leads", readOnly = true))
        val models = FakeModelClients(
            Scripted.message("tool_use", Scripted.thinking("sig-1"), Scripted.toolUse("toolu_1", "clientes_search_customers"), Scripted.toolUse("toolu_2", "crm_search_leads")),
            Scripted.message("end_turn", Scripted.text("Hay 2 clientes y 1 prospecto.")),
        )
        val (id, sink) = send(models, toolbox, "Busca a Ana")
        assertEquals("end_turn", sink.done())
        assertEquals(listOf("clientes_search_customers", "crm_search_leads"), toolbox.executed.map { it.first })
        // Parallel calls: both tool_result blocks in ONE user message, in order, right after the assistant message.
        val messages = store.messages(id)
        assertEquals(listOf("user", "assistant", "user", "assistant"), messages.map { it.role })
        val results = messages[2].content
        assertEquals(listOf("toolu_1", "toolu_2"), results.map { it.jsonObject.string("tool_use_id") })
        assertTrue(results.all { it.jsonObject.string("type") == "tool_result" && it.jsonObject["is_error"] == null })
        assertEquals(listOf("Hay 2 clientes y 1 prospecto."), sink.all("text_delta").map { it.string("text") })
        assertEquals(2, sink.all("tool_finished").count { it.string("status") == "ok" })
        assertEquals(AssistantConversation.Idle, store.conversations[id]!!.state)
        // The second request replays the history unchanged: the thinking block comes back as the model sent it.
        val replayed = models.body(1)["messages"]!!.jsonArray
        assertEquals(3, replayed.size)
        assertEquals(messages[1].content, replayed[1].jsonObject["content"])
        assertEquals("sig-1", replayed[1].jsonObject["content"]!!.jsonArray[0].jsonObject.string("signature"))
    }

    @Test
    fun aToolTheUserLacksIsNeitherOfferedNorRunEvenWhenTheModelNamesIt() {
        val toolbox = FakeToolbox(search)
        val models = FakeModelClients(
            Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_delete_customer"), Scripted.toolUse("toolu_2", "servidor_delete_company")),
            Scripted.message("end_turn", Scripted.text("No puedo.")),
        )
        val (id, sink) = send(models, toolbox, "Borra todo")
        assertEquals(listOf("clientes_search_customers"), models.body(0)["tools"]!!.jsonArray.map { it.jsonObject.string("name") })
        assertTrue(toolbox.executed.isEmpty())
        val results = store.messages(id)[2].content
        assertTrue(results.all { it.jsonObject["is_error"]!!.jsonPrimitive.boolean && it.jsonObject.string("content") == AssistantTools.UnknownTool })
        assertEquals("end_turn", sink.done())
        assertTrue(sink.all("confirmation_required").isEmpty())
    }

    @Test
    fun aPermissionRemovedWhileWaitingStopsTheApprovedCall() {
        val toolbox = FakeToolbox(search, create)
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_create_customer", buildJsonObject { put("nombre", "Ana") })))
        val (_, sink) = send(models, toolbox, "Crea a Ana")
        val confirmation = sink.last("confirmation_required").string("confirmationId")!!
        toolbox.available.remove(create)
        models.add(Scripted.message("end_turn", Scripted.text("No tienes permiso.")))
        val resumed = decide(models, toolbox, confirmation, approve = true)
        assertTrue(toolbox.executed.isEmpty())
        assertEquals("error", resumed.all("tool_finished").single().string("status"))
    }

    @Test
    fun aWriteWaitsForTheUserAndRunsWhenApproved() {
        val toolbox = FakeToolbox(search, create)
        val models = FakeModelClients(
            Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_search_customers"), Scripted.toolUse("toolu_2", "clientes_create_customer", buildJsonObject { put("nombre", "Ana") })),
        )
        val (id, sink) = send(models, toolbox, "Crea a Ana si no existe")
        assertEquals("awaiting_confirmation", sink.done())
        assertEquals(AssistantConversation.Awaiting, store.conversations[id]!!.state)
        // The read ran; the write did not.
        assertEquals(listOf("clientes_search_customers"), toolbox.executed.map { it.first })
        val card = sink.last("confirmation_required")
        assertEquals("Clientes: create_customer", card.string("label"))
        assertEquals("Nombre del cliente.", card["summary"]!!.jsonArray[0].jsonObject.string("label"))
        assertEquals("Ana", card["summary"]!!.jsonArray[0].jsonObject.string("value"))
        // Nothing waits in memory: the pause is in the store (the HTTP stream is over).
        assertEquals(2, store.pending(id).size)
        // A new message is refused while a decision is open.
        val busy = assertFailsWith<AssistantRefusal> { engine(models).prepareMessage(ana, id, "hola") }
        assertEquals("CONFIRMATION_PENDING", busy.code)

        models.add(Scripted.message("end_turn", Scripted.text("Listo, cree a Ana.")))
        val resumed = decide(models, toolbox, card.string("confirmationId")!!, approve = true)
        assertEquals("end_turn", resumed.done())
        assertEquals(listOf("clientes_search_customers", "clientes_create_customer"), toolbox.executed.map { it.first })
        // One user message with both results, in the order of the calls.
        val results = store.messages(id)[2].content
        assertEquals(listOf("toolu_1", "toolu_2"), results.map { it.jsonObject.string("tool_use_id") })
        assertTrue(store.pending(id).isEmpty())
        assertEquals(AssistantConversation.Idle, store.conversations[id]!!.state)
        // Deciding again is refused.
        assertEquals("CONFIRMATION_NOT_FOUND", assertFailsWith<AssistantRefusal> { engine(models).prepareDecision(ana, UUID.fromString(card.string("confirmationId")), true) }.code)
    }

    @Test
    fun aRejectedWriteIsAnsweredAsNotAuthorizedAndNeverRuns() {
        val toolbox = FakeToolbox(create, remove)
        val models = FakeModelClients(
            Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_create_customer"), Scripted.toolUse("toolu_2", "clientes_delete_customer")),
        )
        val (id, sink) = send(models, toolbox, "Crea y borra")
        val cards = sink.all("confirmation_required")
        assertEquals(2, cards.size)
        assertTrue(cards[1]["destructive"]!!.jsonPrimitive.boolean)
        // First decision: the other still waits; the stream ends "waiting" with no model request.
        val first = decide(models, toolbox, cards[0].string("confirmationId")!!, approve = false)
        assertEquals("waiting", first.done())
        assertEquals(1, models.requests.size)
        models.add(Scripted.message("end_turn", Scripted.text("Entendido.")))
        val second = decide(models, toolbox, cards[1].string("confirmationId")!!, approve = false)
        assertEquals("end_turn", second.done())
        assertTrue(toolbox.executed.isEmpty())
        val results = store.messages(id)[2].content.map { it.jsonObject }
        assertTrue(results.all { it.string("content") == "El usuario no autorizo esta accion" && it["is_error"]!!.jsonPrimitive.boolean })
    }

    @Test
    fun anUnansweredConfirmationExpiresWithoutCallingTheModel() {
        val toolbox = FakeToolbox(create)
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_create_customer")))
        val (id, sink) = send(models, toolbox, "Crea a Ana")
        val confirmation = sink.last("confirmation_required").string("confirmationId")!!
        now = now.plus(Duration.ofHours(25))
        assertEquals("CONFIRMATION_EXPIRED", assertFailsWith<AssistantRefusal> { engine(models).prepareDecision(ana, UUID.fromString(confirmation), true) }.code)
        assertTrue(toolbox.executed.isEmpty())
        assertEquals(1, models.requests.size)
        assertEquals(AssistantConversation.Idle, store.conversations[id]!!.state)
        assertEquals(AssistantEngine.Expired, lastUser(id).single().jsonObject.string("content"))
        // The conversation goes on normally afterwards.
        models.add(Scripted.message("end_turn", Scripted.text("Hola")))
        val (_, next) = send(models, toolbox, "Sigues ahi?", id = id)
        assertEquals("end_turn", next.done())
        // The daily job finds nothing else to expire.
        assertEquals(0, engine(models).expireAll())
    }

    @Test
    fun theDailyJobExpiresDueConfirmations() {
        val toolbox = FakeToolbox(create)
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_create_customer")))
        val (id, _) = send(models, toolbox, "Crea a Ana")
        assertEquals(0, engine(models).expireAll())
        now = now.plus(Duration.ofHours(24))
        assertEquals(1, engine(models).expireAll())
        assertEquals(AssistantConversation.Idle, store.conversations[id]!!.state)
    }

    @Test
    fun aRefusalIsShownNeutrallyAndNothingOfItIsStored() {
        val models = FakeModelClients(
            Scripted.message("refusal", Scripted.text("parcial"), stopDetails = buildJsonObject { put("type", "refusal"); put("category", "cyber") }),
        )
        val (id, sink) = send(models, FakeToolbox(search), "algo")
        assertEquals("refusal", sink.done())
        assertEquals("REFUSAL", sink.last("notice").string("code"))
        assertEquals(listOf("user", "notice"), store.messages(id).map { it.role })
        // Usage is still counted.
        assertEquals(1, store.usageRows.values.single().requests)
        // The declined turn is left out of later requests (append-only: a notice names it), so it cannot poison the
        // conversation, and the next request never has two user turns in a row.
        models.add(Scripted.message("end_turn", Scripted.text("ok")))
        send(models, FakeToolbox(search), "otra cosa", id = id)
        val replayed = models.body(1)["messages"]!!.jsonArray
        assertEquals(listOf("user"), replayed.map { it.jsonObject.string("role") })
        assertFalse(replayed.toString().contains("algo"))
        assertTrue(replayed.toString().contains("otra cosa"))
    }

    @Test
    fun aRefusalAfterToolResultsClosesTheConversation() {
        val models = FakeModelClients(
            Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_search_customers")),
            Scripted.message("refusal"),
        )
        val (id, sink) = send(models, FakeToolbox(search), "busca")
        assertEquals("refusal", sink.done())
        assertEquals(listOf("REFUSAL", "CLOSED"), sink.all("notice").map { it.string("code") })
        assertEquals(AssistantConversation.Closed, store.conversations[id]!!.state)
        assertEquals("CONVERSATION_CLOSED", assertFailsWith<AssistantRefusal> { engine(models).prepareMessage(ana, id, "sigue") }.code)
    }

    @Test
    fun maxTokensStopsTheTurnAndACutToolCallIsAnsweredBeforeTheNextMessage() {
        val toolbox = FakeToolbox(create)
        val models = FakeModelClients(Scripted.message("max_tokens", Scripted.text("Voy a crear"), Scripted.toolUse("toolu_9", "clientes_create_customer")))
        val (id, sink) = send(models, toolbox, "Crea muchos")
        assertEquals("max_tokens", sink.done())
        assertEquals("MAX_TOKENS", sink.last("notice").string("code"))
        assertTrue(toolbox.executed.isEmpty())
        models.add(Scripted.message("end_turn", Scripted.text("ok")))
        send(models, toolbox, "sigue", id = id)
        val first = lastUser(id)[0].jsonObject
        assertEquals("tool_result", first.string("type"))
        assertEquals("toolu_9", first.string("tool_use_id"))
        assertEquals(AssistantEngine.NotRun, first.string("content"))
    }

    @Test
    fun aFailingToolIsAnErrorResultTheModelCanReadAndNotLogged() {
        val toolbox = FakeToolbox(search).apply { handlers["clientes_search_customers"] = { AssistantToolResult(false, "El cliente 42 no existe.") } }
        val models = FakeModelClients(
            Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_search_customers")),
            Scripted.message("end_turn", Scripted.text("No existe.")),
        )
        val (id, sink) = send(models, toolbox, "Busca el 42")
        val result = store.messages(id)[2].content.single().jsonObject
        assertTrue(result["is_error"]!!.jsonPrimitive.boolean)
        assertEquals("El cliente 42 no existe.", result.string("content"))
        assertEquals("error", sink.last("tool_finished").string("status"))
        assertEquals("end_turn", sink.done())
    }

    @Test
    fun theMonthlyLimitStopsNewRequestsAndOnlyCountsTheLimitedKeys() {
        val toolbox = FakeToolbox(search)
        // 100 input + 20 output + ceil(50 / 10) cache reads = 125 counted tokens.
        val models = FakeModelClients(Scripted.message("end_turn", Scripted.text("uno"), cacheRead = 50))
        send(models, toolbox, "uno", runtime = runtime(limit = 125))
        assertEquals(125, store.monthUsage(java.time.YearMonth.of(2026, 11), setOf("server")))
        val (_, limited) = send(models, toolbox, "dos", runtime = runtime(limit = 125))
        assertEquals("limit", limited.done())
        assertEquals("LIMIT_REACHED", limited.last("error").string("code"))
        assertEquals(1, models.requests.size)
        // The company's own key is not limited unless the server says so, and is counted apart.
        models.add(Scripted.message("end_turn", Scripted.text("tres")))
        val (_, own) = send(models, toolbox, "tres", runtime = runtime(keySource = "company", limit = 125))
        assertEquals("end_turn", own.done())
        assertEquals(listOf("sk-ant-server-test-key-000000", "sk-ant-company-test-key-0000"), models.keys)
        assertEquals(setOf("server", "company"), store.usageRows.values.map { it.keySource }.toSet())
        val (_, ownLimited) = send(models, toolbox, "cuatro", runtime = runtime(keySource = "company", limit = 125, limited = setOf("server", "company")))
        assertEquals("limit", ownLimited.done())
    }

    @Test
    fun modelErrorsAreClassifiedAndTheConversationIsReleased() {
        val models = FakeModelClients(AnthropicErrors.classify(401), AnthropicErrors.classify(529))
        val (id, sink) = send(models, FakeToolbox(), "hola")
        assertEquals("MODEL_KEY_INVALID", sink.last("error").string("code"))
        assertFalse(sink.last("error")["retryable"]!!.jsonPrimitive.boolean)
        assertEquals(AssistantConversation.Idle, store.conversations[id]!!.state)
        val (_, again) = send(models, FakeToolbox(), "hola", id = id)
        assertEquals("MODEL_OVERLOADED", again.last("error").string("code"))
        assertTrue(again.last("error")["retryable"]!!.jsonPrimitive.boolean)
        assertEquals("error", again.done())
    }

    @Test
    fun tooManyToolCallsInOneAnswerAreNotRun() {
        val toolbox = FakeToolbox(search)
        val uses = (1..26).map { Scripted.toolUse("toolu_$it", "clientes_search_customers") }.toTypedArray()
        val models = FakeModelClients(Scripted.message("tool_use", *uses))
        val (id, sink) = send(models, toolbox, "muchas")
        assertEquals("TOO_MANY_TOOL_CALLS", sink.last("error").string("code"))
        assertTrue(toolbox.executed.isEmpty())
        assertEquals(26, lastUser(id).size)
    }

    @Test
    fun closingTheStreamCancelsTheTurn() {
        val models = FakeModelClients(Scripted.message("end_turn", Scripted.text("largo")))
        val engine = engine(models)
        val id = conversation().id
        val prepared = engine.prepareMessage(ana, id, "hola")
        val sink = RecordingSink(cancelAfter = 1)
        engine.runMessage(ana, runtime(), FakeToolbox(), prepared, "hola", sink)
        assertEquals("cancelled", sink.done())
        assertEquals(AssistantConversation.Idle, store.conversations[id]!!.state)
        assertEquals(listOf("user"), store.messages(id).map { it.role })
    }

    @Test
    fun aConversationBelongsToItsOwnerOnlyNotEvenToTheBusinessOwner() {
        val id = conversation(ana).id
        val models = FakeModelClients(Scripted.message("end_turn", Scripted.text("hola")))
        send(models, FakeToolbox(), "mi secreto", id = id)
        assertNull(store.conversation(id, owner.userId, owner.ownerKey))
        assertTrue(store.conversations(owner.userId, owner.ownerKey, false, 50, null).isEmpty())
        assertEquals("CONVERSATION_NOT_FOUND", assertFailsWith<AssistantRefusal> { engine(models).prepareMessage(owner, id, "dame lo de Ana") }.code)
        // A switcher session of the same business-owner account is another person, with its own conversations.
        val mine = conversation(owner).id
        val switcher = AssistantCaller("acme", "Acme", owner.userId, "server:${UUID.randomUUID()}", "Dueno", "es", businessOwner = true)
        assertEquals("CONVERSATION_NOT_FOUND", assertFailsWith<AssistantRefusal> { engine(models).prepareMessage(switcher, mine, "hola") }.code)
        // Confirmations follow the same rule.
        val toolbox = FakeToolbox(create)
        models.add(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_create_customer")))
        val (_, sink) = send(models, toolbox, "crea", id = id)
        val confirmation = UUID.fromString(sink.last("confirmation_required").string("confirmationId"))
        assertEquals("CONFIRMATION_NOT_FOUND", assertFailsWith<AssistantRefusal> { engine(models).prepareDecision(owner, confirmation, true) }.code)
    }

    @Test
    fun aBusyConversationAndOverlongMessagesAreRefusedBeforeAnyStream() {
        val id = conversation().id
        val engine = engine(FakeModelClients())
        engine.prepareMessage(ana, id, "uno")
        assertEquals("CONVERSATION_BUSY", assertFailsWith<AssistantRefusal> { engine.prepareMessage(ana, id, "dos") }.code)
        // A crashed turn frees the conversation when its lease ends.
        now = now.plus(Duration.ofMinutes(13))
        engine.prepareMessage(ana, id, "tres")
        val other = conversation().id
        assertEquals(413, assertFailsWith<AssistantRefusal> { engine.prepareMessage(ana, other, "x".repeat(20_001)) }.status)
        assertEquals("MESSAGE_INVALID", assertFailsWith<AssistantRefusal> { engine.prepareMessage(ana, other, "   ") }.code)
    }

    @Test
    fun chatTextToolArgumentsAndKeysNeverReachTheLogs() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val loggers = listOf(AssistantEngine::class.java, ModuleAssistantToolbox::class.java, AnthropicModelClient::class.java, AssistantService::class.java)
            .map { LoggerFactory.getLogger(it) as Logger }
        loggers.forEach { it.addAppender(appender) }
        try {
            val toolbox = FakeToolbox(search, create).apply { handlers["clientes_search_customers"] = { throw IllegalStateException("fallo con dato-secreto-del-cliente") } }
            val models = FakeModelClients(
                Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_search_customers", buildJsonObject { put("nombre", "argumento-privado") })),
            )
            send(models, toolbox, "texto-privado-del-chat")
        } finally {
            loggers.forEach { it.detachAppender(appender) }
        }
        val logged = appender.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        listOf("texto-privado-del-chat", "argumento-privado", "dato-secreto-del-cliente", "sk-ant-server-test-key").forEach {
            assertFalse(logged.contains(it), "logged: $logged")
        }
    }

    @Test
    fun usageEventsCarryTheCountedTokensAndTheMonth() {
        val models = FakeModelClients(Scripted.message("end_turn", Scripted.text("ok"), input = 10, output = 5, cacheRead = 1000, cacheWrite = 40))
        val (_, sink) = send(models, FakeToolbox(), "hola", runtime = runtime(limit = 1_000_000))
        val usage = sink.last("usage")
        assertEquals("155", usage.string("countedTokens"))
        assertEquals("155", usage.string("monthUsedTokens"))
        assertEquals("1000000", usage.string("monthlyLimitTokens"))
    }

    @Test
    fun theDateGoesInTheUserTurnNotInTheCachedPrefix() {
        val models = FakeModelClients(Scripted.message("end_turn", Scripted.text("ok")), Scripted.message("end_turn", Scripted.text("ok")))
        val (id, _) = send(models, FakeToolbox(search), "hola")
        now = now.plus(Duration.ofDays(1))
        send(models, FakeToolbox(search), "y hoy?", id = id)
        val first = models.body(0)
        val second = models.body(1)
        // The prefix (tools, system) is byte-identical across days; the dates are in the user messages.
        assertEquals(first["tools"], second["tools"])
        assertEquals(first["system"], second["system"])
        assertFalse(first["system"].toString().contains("2026-11-27"))
        val context = second["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray[0].jsonObject.string("text")!!
        assertTrue(context.startsWith(AssistantEngine.ContextPrefix) && context.contains("2026-11-28"), context)
        // The user sees only what they wrote.
        val items = AssistantView.items(store.messages(id), emptyList(), emptyMap())
        assertEquals(listOf("hola", "ok", "y hoy?", "ok"), items.map { it.text })
    }

    @Test
    fun afterAServerSideFallbackTheBlocksBeforeItAreNotSentAgainNorRun() {
        val toolbox = FakeToolbox(search)
        val fallback = buildJsonObject {
            put("type", "fallback")
            put("from", buildJsonObject { put("model", "claude-opus-5-5") })
            put("to", buildJsonObject { put("model", "claude-opus-5") })
        }
        val content = JsonArray(listOf(Scripted.thinking("sig-a"), Scripted.toolUse("toolu_a", "clientes_search_customers"), Scripted.text("parcial"), fallback, Scripted.text("final")))
        val echoed = AnthropicResponses.echoable(content)
        assertEquals(listOf("text", "fallback", "text"), echoed.map { AnthropicResponses.blockType(it) })
        // Without a fallback block the content is sent back exactly as it came.
        val plain = JsonArray(content.filter { AnthropicResponses.blockType(it) != "fallback" })
        assertEquals(plain, AnthropicResponses.echoable(plain))
        assertTrue(toolbox.executed.isEmpty())
    }

    @Test
    fun countedTokensFormula() {
        assertEquals(0, ModelUsage().countedTokens)
        assertEquals(1 + 2 + 3 + 1, ModelUsage(inputTokens = 1, outputTokens = 2, cacheCreationInputTokens = 3, cacheReadInputTokens = 1).countedTokens)
        assertEquals(1 + 2 + 3 + 10, ModelUsage(1, 2, 100, 3).countedTokens)
    }

    @Test
    fun toolResultsAreCutAndJsonIsPassedAsText() {
        assertEquals("{\"a\":1}", AssistantTools.resultText(buildJsonObject { put("a", 1) }))
        assertEquals("hola", AssistantTools.resultText(JsonPrimitive("hola")))
        val long = AssistantTools.resultText(JsonPrimitive("x".repeat(200_000)))
        assertTrue(long.length < 100_100 && long.endsWith("[resultado recortado]"))
        assertEquals(JsonObject(emptyMap()), JsonObject(emptyMap()))
    }
}
