package com.ideasdeveloper.bms.server.assistant

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.ideasdeveloper.bms.server.i18n.I18n
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Approval bound to what runs (preview fingerprint), conversations a crash left awaiting, usage of broken responses,
 * the turn given back when its request fails, links and hidden characters.
 */
class AssistantBindingTest {
    private var now = Instant.parse("2026-11-27T10:00:00Z")
    private val store = MemoryAssistantStore()
    private val ana = AssistantCaller("acme", "Acme", UUID.randomUUID(), "self", "Ana Ruiz", "es", businessOwner = false)
    private val send = FakeToolbox.tool("crm_send_message", readOnly = false, outbound = true)
    private val runtime = AssistantRuntime("claude-opus-5-5", "medium", "sk-ant-test-key-00000000", "server", null, setOf("server"))
    private val args = buildJsonObject { put("nombre", "Luis") }

    private fun engine(models: FakeModelClients, parse: ((com.anthropic.models.beta.messages.BetaMessage) -> ModelResponse)? = null) =
        if (parse == null) AssistantEngine(store, models, { now }) else AssistantEngine(store, models, { now }, parse = parse)

    private fun message(models: FakeModelClients, toolbox: AssistantToolbox, text: String = "Envia", id: UUID = store.createConversation(ana.userId, "self", "", now).id): Pair<UUID, RecordingSink> {
        val engine = engine(models)
        val turn = engine.prepareMessage(ana, id, text)
        return id to RecordingSink().also { engine.runMessage(ana, runtime, toolbox, turn, text, it) }
    }

    private fun decide(models: FakeModelClients, toolbox: AssistantToolbox, confirmation: String, approve: Boolean = true): RecordingSink {
        val engine = engine(models)
        val decision = engine.prepareDecision(ana, UUID.fromString(confirmation), approve)
        return RecordingSink().also { engine.runDecision(ana, runtime, toolbox, decision, it) }
    }

    private fun rows(vararg pairs: Pair<String, String>) = AssistantPreview(JsonArray(pairs.map { AssistantTools.row(it.first, it.second) }))

    private fun counted() = store.usageRows.values.sumOf { it.countedTokens }

    // ---- B2: the approval is bound to the card ----

    @Test
    fun anApprovedCallThatNoLongerMatchesItsCardDoesNotRunAndTheModelAsksAgain() {
        val toolbox = FakeToolbox(send)
        var address = "luis@example.com"
        toolbox.previews["crm_send_message"] = { rows("Se envia a" to address, "Mensaje (completo)" to "Hola") }
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "crm_send_message", args)))
        val (id, sink) = message(models, toolbox)
        val card = sink.last("confirmation_required")
        assertEquals("luis@example.com", card["preview"]!!.jsonArray[0].jsonObject.string("value"))
        assertNotNull(store.pending(id).single().previewHash)
        // The lead's address changes while the card waits.
        address = "otro@example.net"
        models.add(
            Scripted.message("tool_use", Scripted.toolUse("toolu_2", "crm_send_message", args)),
        )
        val resumed = decide(models, toolbox, card.string("confirmationId")!!)
        // Nothing was sent; the model was told, and its new proposal is a new card with the new address.
        assertTrue(toolbox.executed.isEmpty())
        val finished = resumed.all("tool_finished").single()
        assertEquals("error", finished.string("status"))
        assertEquals(AssistantEngine.DataChanged, finished.string("message"))
        assertEquals("CONFIRMATION_CHANGED", resumed.all("notice").single().string("code"))
        val result = store.messages(id).first { it.role == Roles.User && it.content.any { block -> block.jsonObject.string("type") == "tool_result" } }.content.single().jsonObject
        assertEquals(AssistantEngine.DataChanged, result.string("content"))
        assertTrue(result["is_error"]!!.jsonPrimitive.boolean)
        assertEquals("awaiting_confirmation", resumed.done())
        val fresh = resumed.last("confirmation_required")
        assertEquals("otro@example.net", fresh["preview"]!!.jsonArray[0].jsonObject.string("value"))
        // Approving the fresh card runs it, because it matches.
        models.add(Scripted.message("end_turn", Scripted.text("Enviado.")))
        val done = decide(models, toolbox, fresh.string("confirmationId")!!)
        assertEquals("end_turn", done.done())
        assertEquals(listOf("crm_send_message"), toolbox.executed.map { it.first })
    }

    @Test
    fun anUnchangedCardRunsAndAToolWithoutPreviewIsNotChecked() {
        val toolbox = FakeToolbox(send, FakeToolbox.tool("clientes_create_customer", readOnly = false))
        toolbox.previews["crm_send_message"] = { rows("Se envia a" to "luis@example.com") }
        val models = FakeModelClients(
            Scripted.message("tool_use", Scripted.toolUse("toolu_1", "crm_send_message", args), Scripted.toolUse("toolu_2", "clientes_create_customer", args)),
            Scripted.message("end_turn", Scripted.text("Listo")),
        )
        val (id, sink) = message(models, toolbox)
        val calls = store.pending(id)
        assertNotNull(calls[0].previewHash)
        assertNull(calls[1].previewHash)
        sink.all("confirmation_required").forEach { decide(models, toolbox, it.string("confirmationId")!!) }
        assertEquals(listOf("crm_send_message", "clientes_create_customer"), toolbox.executed.map { it.first })
    }

    @Test
    fun theFingerprintDoesNotDependOnTheLanguageOfTheRequest() {
        val toolbox = FakeToolbox(send)
        // A preview whose wording follows the language of the request.
        toolbox.previews["crm_send_message"] = { rows("Se envia a" to if (I18n.language() == "en") "luis@example.com (to)" else "luis@example.com") }
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "crm_send_message", args)), Scripted.message("end_turn", Scripted.text("Listo")))
        val (_, sink) = I18n.withLanguage("en") { message(models, toolbox) }
        assertEquals("luis@example.com (to)", sink.last("confirmation_required")["preview"]!!.jsonArray[0].jsonObject.string("value"))
        // Decided in Spanish: the same card by its source-language fingerprint, so it runs.
        decide(models, toolbox, sink.last("confirmation_required").string("confirmationId")!!)
        assertEquals(listOf("crm_send_message"), toolbox.executed.map { it.first })
    }

    @Test
    fun aPreviewThatFailedWhenShownDoesNotLetItRunOnceItResolves() {
        val toolbox = FakeToolbox(send)
        var fails = true
        toolbox.previews["crm_send_message"] = { if (fails) AssistantPreview(JsonArray(emptyList()), "Lead no encontrado") else rows("Se envia a" to "luis@example.com") }
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "crm_send_message", args)), Scripted.message("end_turn", Scripted.text("Ok")))
        val (_, sink) = message(models, toolbox)
        assertEquals("Lead no encontrado", sink.last("confirmation_required").string("previewError"))
        fails = false
        decide(models, toolbox, sink.last("confirmation_required").string("confirmationId")!!)
        // The user never saw what would run now.
        assertTrue(toolbox.executed.isEmpty())
    }

    // ---- F2: a conversation left awaiting with everything decided ----

    private fun stuck(approved: Boolean): Triple<UUID, FakeToolbox, FakeModelClients> {
        val toolbox = FakeToolbox(send)
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "crm_send_message", args)))
        val (id, sink) = message(models, toolbox)
        // The decision was recorded and the process died before the turn resumed.
        assertTrue(store.decide(UUID.fromString(sink.last("confirmation_required").string("confirmationId")), if (approved) AssistantPendingCall.Approved else AssistantPendingCall.Rejected, now))
        assertEquals(AssistantConversation.Awaiting, store.conversations[id]!!.state)
        return Triple(id, toolbox, models)
    }

    @Test
    fun retryingTheDecisionOfAStuckConversationResumesItAndRunsTheApprovedCallOnce() {
        val (id, toolbox, models) = stuck(approved = true)
        models.add(Scripted.message("end_turn", Scripted.text("Enviado.")))
        val confirmation = store.pending(id).single().id.toString()
        val sink = decide(models, toolbox, confirmation)
        assertEquals("end_turn", sink.done())
        assertEquals(listOf("crm_send_message"), toolbox.executed.map { it.first })
        assertEquals(AssistantConversation.Idle, store.conversations[id]!!.state)
    }

    @Test
    fun aNewMessageTakesOverAStuckConversationAndTheDecidedCallsAreAnsweredTruthfully() {
        val (id, toolbox, models) = stuck(approved = true)
        models.add(Scripted.message("end_turn", Scripted.text("Hola")))
        val (_, sink) = message(models, toolbox, "Sigues ahi?", id = id)
        assertEquals("end_turn", sink.done())
        // The approved call was never run, and the history says so.
        assertTrue(toolbox.executed.isEmpty())
        val result = store.messages(id).first { it.role == Roles.User && it.content.any { block -> block.jsonObject.string("type") == "tool_result" } }.content.first().jsonObject
        assertEquals(AssistantEngine.NotRun, result.string("content"))
        assertTrue(store.pending(id).isEmpty())
    }

    @Test
    fun aNewMessageIsStillRefusedWhileACallStillWaits() {
        val toolbox = FakeToolbox(send)
        val models = FakeModelClients(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "crm_send_message", args)))
        val (id, _) = message(models, toolbox)
        assertEquals("CONFIRMATION_PENDING", assertFailsWith<AssistantRefusal> { engine(models).prepareMessage(ana, id, "hola") }.code)
    }

    @Test
    fun theDailyJobReturnsAStuckConversationToIdleWithoutAModelRequest() {
        val (id, _, models) = stuck(approved = false)
        // Just decided: a decision may be resuming right now.
        assertEquals(0, engine(models).expireAll())
        now = now.plus(AssistantEngine.StuckAfter).plusSeconds(1)
        assertEquals(1, engine(models).expireAll())
        assertEquals(AssistantConversation.Idle, store.conversations[id]!!.state)
        assertEquals(AssistantEngine.Rejected, store.messages(id).last().content.single().jsonObject.string("content"))
        assertEquals(1, models.requests.size)
        assertEquals(0, engine(models).expireAll())
    }

    // ---- F7: a turn taken but never run is given back ----

    @Test
    fun anAbandonedTurnFreesTheConversationAtOnce() {
        val models = FakeModelClients()
        val id = store.createConversation(ana.userId, "self", "", now).id
        val engine = engine(models)
        val turn = engine.prepareMessage(ana, id, "hola")
        assertEquals("CONVERSATION_BUSY", assertFailsWith<AssistantRefusal> { engine.prepareMessage(ana, id, "otra") }.code)
        engine.abandon(id, turn.token, AssistantConversation.Idle)
        assertEquals(AssistantConversation.Idle, store.conversations[id]!!.state)
        assertNotNull(engine.prepareMessage(ana, id, "otra"))
    }

    @Test
    fun theConversationCapIsCheckedAndTheConversationCreatedInOneStep() {
        repeat(2) { assertNotNull(store.createConversationCapped(ana.userId, "self", "", now, 2)) }
        assertNull(store.createConversationCapped(ana.userId, "self", "", now, 2))
        assertEquals(2, store.conversationCount(ana.userId, "self"))
    }

    @Test
    fun retentionAlsoDeletesConversationsStuckRunning() {
        val old = now.minus(Duration.ofDays(100))
        val stuckRunning = store.createConversation(ana.userId, "self", "", old)
        store.startTurn(stuckRunning.id, AssistantConversation.Idle, old.plusSeconds(600), old)
        val live = store.createConversation(ana.userId, "self", "", now)
        store.startTurn(live.id, AssistantConversation.Idle, now.plusSeconds(600), now)
        assertEquals(1, store.purge(now.minus(Duration.ofDays(90)), java.time.LocalDate.of(2025, 1, 1)))
        assertNull(store.conversations[stuckRunning.id])
        assertNotNull(store.conversations[live.id])
    }

    // ---- F1: usage ----

    private fun event(json: String): BetaRawMessageStreamEvent = jsonMapper().readValue(json, BetaRawMessageStreamEvent::class.java)

    private val start = """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,
        "usage":{"input_tokens":2000,"output_tokens":1,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}"""

    private fun delta(kind: String, field: String, text: String) =
        """{"type":"content_block_delta","index":0,"delta":{"type":"$kind","$field":${JsonPrimitive(text)}}}"""

    @Test
    fun aStoppedStreamIsChargedTheEstimatedOutputWhenTheReportedOneIsTiny() {
        val progress = AnthropicModelClient.StreamProgress()
        progress.onEvent(event(start))
        // 300 characters of text, 150 of thinking and 150 of tool input: 600 / 3 = 200 tokens.
        progress.onEvent(event(delta("text_delta", "text", "a".repeat(300))))
        progress.onEvent(event(delta("thinking_delta", "thinking", "b".repeat(150))))
        progress.onEvent(event(delta("input_json_delta", "partial_json", "c".repeat(150))))
        assertEquals(ModelUsage(inputTokens = 2000, outputTokens = 200), progress.estimatedUsage())
        // What the provider reported wins when it is larger (the final message_delta).
        progress.onEvent(event("""{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":500}}"""))
        assertEquals(500, progress.estimatedUsage()!!.outputTokens)
        assertNull(AnthropicModelClient.StreamProgress().estimatedUsage())
    }

    @Test
    fun aResponseThatCannotBeReadIsStillChargedAndEndsTheTurnWithAnError() {
        val models = FakeModelClients(Scripted.message("end_turn", Scripted.text("hola"), input = 300, output = 40))
        val engine = engine(models) { error("unreadable") }
        val id = store.createConversation(ana.userId, "self", "", now).id
        val turn = engine.prepareMessage(ana, id, "hola")
        val sink = RecordingSink()
        engine.runMessage(ana, runtime, FakeToolbox(), turn, "hola", sink)
        assertEquals("INTERNAL", sink.last("error").string("code"))
        assertEquals("error", sink.done())
        assertEquals(340, counted())
        assertEquals(AssistantConversation.Idle, store.conversations[id]!!.state)
    }

    // ---- F6: links ----

    private val own = setOf("acme.com", "bms.example.org")

    private fun foreign(text: String) = AssistantLinks.foreignHosts(text, own)

    @Test
    fun linksAreReadTheWayABrowserReadsThem() {
        assertEquals(emptyList(), foreign("Mira https://acme.com/pagos y https://app.acme.com:8443/x y www.acme.com y ana@acme.com"))
        // User info: the host is what comes after the last @.
        assertEquals(listOf("evil.com"), foreign("https://acme.com@evil.com/login"))
        assertEquals(listOf("evil.com"), foreign("https://acme.com:pass@evil.com"))
        // Look-alikes of a known domain.
        assertEquals(listOf("acme.com.evil.com"), foreign("https://acme.com.evil.com"))
        assertEquals(listOf("evilacme.com"), foreign("https://evilacme.com"))
        // Scheme-less hosts with a path or a common TLD, but not an e-mail address nor a file name.
        assertEquals(listOf("evil.com"), foreign("entra a evil.com/x"))
        assertEquals(listOf("evil.com"), foreign("entra a evil.com"))
        assertEquals(emptyList(), foreign("escribe a ana@evil.com o abre informe.pdf"))
        // Another scheme and a % escape that hides the real host.
        assertEquals(listOf("evil.io"), foreign("ftp://evil.io/f"))
        assertEquals(listOf("evil.com%2f.acme.com"), foreign("https://evil.com%2f.acme.com/"))
    }

    @Test
    fun nonAsciiHostsAreAlwaysExternalUnlessWrittenAsAKnownPunycodeDomain() {
        // Cyrillic "а" in place of the Latin one.
        val lookAlike = foreign("https://аcme.com/login")
        assertEquals(1, lookAlike.size)
        assertTrue(lookAlike.single().contains("xn--"), lookAlike.toString())
        assertEquals(1, foreign("entra a аcme.com/login").size)
        // Invisible characters inside the host are removed first: it is the same plain host.
        assertEquals(emptyList(), foreign("https://ac​me.com/x"))
        // A punycode host that is exactly a known domain is known.
        assertEquals(emptyList(), AssistantLinks.foreignHosts("https://xn--bcher-kva.example/x", setOf("xn--bcher-kva.example")))
        assertEquals(1, AssistantLinks.foreignHosts("https://bücher.example/x", setOf("xn--bcher-kva.example")).size)
    }

    // ---- F3: hidden characters ----

    @Test
    fun hiddenCharactersIncludeVariationSelectorsAndStackedMarksButNotAccentsOrEmoji() {
        fun shown(text: String) = AssistantTools.visible(text)
        // Ordinary text: accents (precomposed and combining), emoji with presentation selector, keycaps, CJK, a family emoji.
        listOf("María García", "María", "❤️ gracias", "1️⃣", "漢字", "👨‍👩‍👧 familia", "ñ").forEach { text ->
            assertEquals(text to false, shown(text), text)
        }
        // Bytes hidden in variation selectors after plain letters (both ranges), repeated ones, and Hangul/Braille blanks.
        assertEquals("a[U+FE01]b" to true, shown("a︁b"))
        assertEquals("a[U+E0105]b" to true, shown("a󠄅b"))
        assertEquals("a[U+FE0F][U+FE0F]b" to true, shown("a️️b"))
        assertEquals("x[U+2800]y" to true, shown("x⠀y"))
        assertEquals("x[U+FFA0]y" to true, shown("xﾠy"))
        assertEquals("x[U+034F]y" to true, shown("x͏y"))
        // Ideographic variation sequences after one Han character are real; the same selector after a letter is not.
        assertEquals("辻󠄀" to false, shown("辻󠄀"))
        // Stacked marks (zalgo) and a mark with no base.
        val zalgo = "e" + "́̂̃̄̅"
        assertTrue(shown(zalgo).second && shown(zalgo).first.contains("[U+0301]"))
        assertEquals("[U+0301]hola" to true, shown("́hola"))
        assertEquals("hola [U+0301]x" to true, shown("hola ́x"))
        // What was hidden before stays hidden.
        assertEquals("a[U+202E]b[U+200B]c" to true, shown("a‮b​c"))
    }

    @Test
    fun theRowFlagsEveryHiddenCharacter() {
        assertTrue(AssistantTools.row("Texto", "hola︁").get("hiddenCharacters")!!.jsonPrimitive.boolean)
        assertFalse(AssistantTools.row("Texto", "hola é").containsKey("hiddenCharacters"))
    }
}
