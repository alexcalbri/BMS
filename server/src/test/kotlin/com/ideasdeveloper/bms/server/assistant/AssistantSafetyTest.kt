package com.ideasdeveloper.bms.server.assistant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
 * The review fixes of the turn engine: usage of stopped answers, leases, pending-call races, the confirmation card,
 * links, history alternation and the client cache.
 */
class AssistantSafetyTest {
    private var now = Instant.parse("2026-11-27T10:00:00Z")
    private val store = MemoryAssistantStore()
    private val ana = AssistantCaller("acme", "Acme", UUID.randomUUID(), "self", "Ana Ruiz", "es", businessOwner = false, ownHosts = setOf("acme.example.com"))
    private val search = FakeToolbox.tool("clientes_search_customers", readOnly = true)
    private val create = FakeToolbox.tool("clientes_create_customer", readOnly = false)
    private val send = FakeToolbox.tool("crm_send_message", readOnly = false, outbound = true)
    private val runtime = AssistantRuntime("claude-opus-5-5", "medium", "sk-ant-test-key-00000000", "server", null, setOf("server"))

    private fun engine(models: FakeModelClients) = AssistantEngine(store, models, { now })

    private fun message(models: FakeModelClients, toolbox: AssistantToolbox, text: String, id: UUID = store.createConversation(ana.userId, "self", "", now).id): Pair<UUID, RecordingSink> {
        val engine = engine(models)
        val turn = engine.prepareMessage(ana, id, text)
        return id to RecordingSink().also { engine.runMessage(ana, runtime, toolbox, turn, text, it) }
    }

    private fun decide(models: FakeModelClients, toolbox: AssistantToolbox, confirmation: String, approve: Boolean = true): RecordingSink {
        val engine = engine(models)
        val decision = engine.prepareDecision(ana, UUID.fromString(confirmation), approve)
        return RecordingSink().also { engine.runDecision(ana, runtime, toolbox, decision, it) }
    }

    private fun counted() = store.usageRows.values.sumOf { it.countedTokens }

    // ---- 2. usage of stopped and failed answers ----

    @Test
    fun aStoppedAnswerIsStillCounted() {
        val models = FakeModelClients(Partial(AssistantCancelledException(ModelUsage(inputTokens = 5000, outputTokens = 700)), "hola"))
        val (_, sink) = message(models, FakeToolbox(), "hola")
        assertEquals("cancelled", sink.done())
        assertEquals(5700, counted())
    }

    @Test
    fun aStopBeforeAnyUsageIsChargedAtThePreviousPromptSize() {
        val models = FakeModelClients(Scripted.message("end_turn", Scripted.text("uno"), input = 3000, cacheRead = 1000), Partial(AssistantCancelledException(null)))
        val (id, _) = message(models, FakeToolbox(), "uno")
        val before = counted()
        message(models, FakeToolbox(), "dos", id = id)
        assertEquals(4000, counted() - before)
    }

    @Test
    fun aFailedStreamCountsWhatTheProviderReported() {
        val models = FakeModelClients(Partial(AnthropicErrors.unavailable().withUsage(ModelUsage(inputTokens = 900, outputTokens = 100))))
        val (_, sink) = message(models, FakeToolbox(), "hola")
        assertEquals("MODEL_UNAVAILABLE", sink.last("error").string("code"))
        assertEquals(1000, counted())
    }

    // ---- 5. leases ----

    @Test
    fun aTurnThatLostItsLeaseStopsWritingAndDoesNotFreeTheNewHolder() {
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_search_customers")), Scripted.message("end_turn", Scripted.text("ok")))
        val id = store.createConversation(ana.userId, "self", "", now).id
        val engine = engine(models)
        val turn = engine.prepareMessage(ana, id, "hola")
        var writes = 0
        var newHolder: UUID? = null
        store.beforeWrite = {
            writes++
            // After the first write another request takes the conversation (as if the lease had run out).
            if (writes == 2 && newHolder == null) {
                store.conversations[id] = store.conversations[id]!!.copy(leaseUntil = now.minusSeconds(1))
                newHolder = store.startTurn(id, AssistantConversation.Idle, now.plusSeconds(600), now)
            }
        }
        val sink = RecordingSink()
        engine.runMessage(ana, runtime, FakeToolbox(search), turn, "hola", sink)
        assertEquals("CONVERSATION_BUSY", sink.last("error").string("code"))
        // The new holder keeps the conversation.
        assertEquals(newHolder, store.tokens[id])
        assertEquals(AssistantConversation.Running, store.conversations[id]!!.state)
    }

    @Test
    fun theLeaseOutlastsTheLongestModelRequest() {
        val limits = AssistantTurnLimits()
        val longest = AnthropicModelClients.RequestTimeout.multipliedBy(2).plusSeconds(5)
        assertTrue(limits.lease > longest, "lease ${limits.lease} vs request $longest")
    }

    // ---- 6. pending calls ----

    @Test
    fun anApprovedWriteRunsOnceAndIsReportedTruthfullyAfterACrash() {
        val toolbox = FakeToolbox(create)
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_create_customer")))
        val (id, sink) = message(models, toolbox, "crea")
        val confirmation = sink.last("confirmation_required").string("confirmationId")!!
        // The resumed turn crashes right after the tool ran (its user message is never written).
        var failOnce = true
        store.beforeWrite = { if (store.pendingCalls.values.any { it.status == AssistantPendingCall.Done && it.result?.string("content") == "{\"ok\":true}" } && failOnce) { failOnce = false; error("crash") } }
        val crashed = decide(models, toolbox, confirmation)
        assertEquals("error", crashed.done())
        assertEquals(1, toolbox.executed.size)
        store.beforeWrite = {}
        // Later (the crashed turn's lease is over) the user writes again: the write is not run again and its real result is sent.
        now = now.plus(Duration.ofMinutes(13))
        store.conversations[id] = store.conversations[id]!!.copy(state = AssistantConversation.Running, leaseUntil = now.minusSeconds(60))
        models.add(Scripted.message("end_turn", Scripted.text("ok")))
        message(models, toolbox, "y?", id = id)
        assertEquals(1, toolbox.executed.size)
        val answer = store.messages(id).last { it.role == Roles.User }.content[0].jsonObject
        assertEquals("toolu_1", answer.string("tool_use_id"))
        assertEquals("{\"ok\":true}", answer.string("content"))
        assertTrue(store.pending(id).isEmpty())
    }

    @Test
    fun aCallClaimedByACrashedTurnIsNeverRunAgainAndSaysSo() {
        val toolbox = FakeToolbox(create)
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_create_customer")))
        val (id, sink) = message(models, toolbox, "crea")
        val confirmation = UUID.fromString(sink.last("confirmation_required").string("confirmationId"))
        // Claimed, then the process died before the result was stored.
        store.pendingCalls[confirmation] = store.pendingCalls[confirmation]!!.copy(status = AssistantPendingCall.Executing)
        store.conversations[id] = store.conversations[id]!!.copy(state = AssistantConversation.Running, leaseUntil = now.minusSeconds(1))
        models.add(Scripted.message("end_turn", Scripted.text("ok")))
        message(models, toolbox, "y?", id = id)
        assertTrue(toolbox.executed.isEmpty())
        assertEquals(AssistantEngine.UnknownOutcome, store.messages(id).last { it.role == Roles.User }.content[0].jsonObject.string("content"))
    }

    @Test
    fun expiryNeverRacesAResumedTurnAndNeverWritesAnEmptyMessage() {
        val toolbox = FakeToolbox(create)
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_create_customer")))
        val (id, sink) = message(models, toolbox, "crea")
        val confirmation = sink.last("confirmation_required").string("confirmationId")!!
        val engine = engine(models)
        val decision = engine.prepareDecision(ana, UUID.fromString(confirmation), true)
        // While the resumed turn holds the conversation, the call's time runs out: expiry does nothing.
        now = now.plus(Duration.ofHours(25))
        assertFalse(engine.expireIfDue(id))
        models.add(Scripted.message("end_turn", Scripted.text("ok")))
        engine.runDecision(ana, runtime, toolbox, decision, RecordingSink())
        assertEquals(1, toolbox.executed.size)
        val ids = store.messages(id).flatMap { message -> message.content.mapNotNull { it.jsonObject.string("tool_use_id") } }
        assertEquals(listOf("toolu_1"), ids)
        assertTrue(store.messages(id).all { it.content.isNotEmpty() })
        // Rows of an earlier response are ignored: only the calls of the last assistant message are answered.
        assertEquals(0, engine.expireAll())
    }

    @Test
    fun aDecisionAfterTheExpiryIsRefused() {
        val toolbox = FakeToolbox(create)
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_create_customer")))
        val (id, sink) = message(models, toolbox, "crea")
        val confirmation = UUID.fromString(sink.last("confirmation_required").string("confirmationId"))
        now = now.plus(Duration.ofHours(24))
        assertFalse(store.decide(confirmation, AssistantPendingCall.Approved, now))
        assertEquals("CONFIRMATION_EXPIRED", assertFailsWith<AssistantRefusal> { engine(models).prepareDecision(ana, confirmation, true) }.code)
        assertEquals(1, store.messages(id).count { message -> message.content.any { it.jsonObject.string("type") == "tool_result" } })
    }

    @Test
    fun theRequestNeverHasTwoTurnsOfTheSameRoleInARow() {
        val toolbox = FakeToolbox(create)
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "clientes_create_customer")))
        val (id, _) = message(models, toolbox, "crea")
        now = now.plus(Duration.ofHours(25))
        models.add(Scripted.message("end_turn", Scripted.text("ok")))
        message(models, toolbox, "otra cosa", id = id)
        val roles = models.body(1)["messages"]!!.jsonArray.map { it.jsonObject.string("role") }
        assertEquals(listOf("user", "assistant", "user"), roles)
        // The expired results and the new text go in the same user turn, results first.
        val last = models.body(1)["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals("tool_result", last[0].jsonObject.string("type"))
    }

    // ---- 3. the confirmation card ----

    @Test
    fun theCardCarriesEveryArgumentInFullFlagsHiddenTextAndShowsTheResolvedTarget() {
        val toolbox = FakeToolbox(send).apply {
            previews["crm_send_message"] = { AssistantPreview(JsonArray(listOf(AssistantTools.row("Destinatario (lead)", "Luis Mora"), AssistantTools.row("Se envia a", "luis@example.com")))) }
        }
        val long = "x".repeat(20_001)
        val arguments = buildJsonObject {
            (1..40).forEach { put("a$it", "valor $it") }
            put("text", "$long hola‮oculto​")
        }
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "crm_send_message", arguments)))
        val (_, sink) = message(models, toolbox, "escribele")
        val card = sink.last("confirmation_required")
        assertTrue(card["outbound"]!!.jsonPrimitive.boolean)
        val summary = card["summary"]!!.jsonArray.map { it.jsonObject }
        assertEquals(41, summary.size)
        val text = summary.last()
        assertTrue(text.string("value")!!.startsWith(long), "the value is not cut")
        assertTrue(text.string("value")!!.endsWith("hola[U+202E]oculto[U+200B]"))
        assertTrue(text["hiddenCharacters"]!!.jsonPrimitive.boolean)
        assertNull(summary.first()["hiddenCharacters"])
        assertEquals(listOf("Luis Mora", "luis@example.com"), card["preview"]!!.jsonArray.map { it.jsonObject.string("value") })
        // The detail of the conversation carries the same.
        val confirmation = AssistantView.confirmation(store.pendingCalls.values.single())
        assertTrue(confirmation.outbound && confirmation.preview.size == 2 && confirmation.summary.last().hiddenCharacters)
    }

    // ---- 8. links and prompt ----

    @Test
    fun linksToOtherSitesGetANotice() {
        val models = FakeModelClients(Scripted.message("end_turn", Scripted.text("Mira https://evil.example.net/x?d=1 y https://www.acme.example.com/ok")))
        val (_, sink) = message(models, FakeToolbox(), "hola")
        val notice = sink.all("notice").single { it.string("code") == "EXTERNAL_LINK" }
        assertTrue(notice.string("message")!!.contains("evil.example.net"))
        assertFalse(notice.string("message")!!.contains("acme.example.com"))
        assertEquals(emptyList(), AssistantLinks.foreignHosts("sin enlaces", emptySet()))
    }

    @Test
    fun thePromptWarnsAboutPublicDataAndKeepsTheLanguageOutOfTheCachedPrefix() {
        val system = AssistantPrompt.system(ana, emptyList())
        assertTrue(system.contains("vienen del publico"))
        assertTrue(system.contains("No construyas direcciones web"))
        val english = AssistantCaller("acme", "Acme", ana.userId, "self", "Ana Ruiz", "en", businessOwner = false)
        assertEquals(system, AssistantPrompt.system(english, emptyList()))
        assertTrue(AssistantEngine.contextBlock(english, now).toString().contains("ingles"))
    }

    // ---- LOW: the client cache ----

    @Test
    fun theClientCacheNeverClosesAClientInUse() {
        class Client(val key: String) { var closed = false }
        val cache = ClientCache<Client>(max = 1) { it.closed = true }
        var first: Client? = null
        cache.use("a", { Client("a") }) { a ->
            first = a
            // While "a" is busy, other keys come and go.
            cache.use("b", { Client("b") }) { }
            cache.use("c", { Client("c") }) { }
            assertFalse(a.closed)
        }
        // The idle ones were closed to keep the limit; the busy one survived and is still the cached client.
        assertFalse(first!!.closed)
        assertEquals(1, cache.size)
        cache.use("a", { error("must be reused") }) { assertTrue(it === first) }
        // A client evicted while in use is closed when its request ends, not before.
        var busy: Client? = null
        cache.use("e", { Client("e") }) { e ->
            busy = e
            cache.use("f", { Client("f") }) { }
            cache.use("g", { Client("g") }) { }
            assertFalse(e.closed)
        }
        assertTrue(cache.size <= 1)
        assertTrue(busy!!.closed || cache.size == 1)
    }
}
