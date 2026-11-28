package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityDisplay
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.activity.ActivityNames
import com.ideasdeveloper.bms.server.modules.McpSchema
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.RegisteredMcpTool
import com.ideasdeveloper.bms.server.modules.RegisteredModuleMcp
import com.ideasdeveloper.bms.server.modules.ServerMcpModuleId
import com.ideasdeveloper.bms.server.modules.UserModuleAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The request sent to the SDK (shape required by GLOBAL_AGENT.md "Claude API rules"), the tool list and the actor. */
class AssistantRequestTest {
    private val history = listOf(
        ModelTurn("user", JsonArray(listOf(Scripted.text("hola")))),
        ModelTurn("assistant", JsonArray(listOf(Scripted.thinking("firma-1"), Scripted.toolUse("toolu_1", "clientes_search_customers", buildJsonObject { put("q", "Ana") })))),
        ModelTurn("user", JsonArray(listOf(AssistantEngine.toolResult("toolu_1", true, "{\"items\":[]}")))),
    )

    private fun request(model: String = "claude-opus-5-5", tools: List<ModelTool> = listOf(ModelTool("zz_last", "z", McpSchema.obj()), ModelTool("aa_first", "a", McpSchema.obj()))) =
        ModelRequest(model, "medium", AssistantModels.MaxTokens, "Eres el asistente.", tools, history)

    private fun betas(model: String = "claude-opus-5-5"): String =
        AnthropicRequests.build(request(model))._headers().values("anthropic-beta").joinToString(",")

    @Test
    fun theRequestHasTheRequiredShape() {
        val body = AnthropicRequests.bodyJson(AnthropicRequests.build(request()))
        assertEquals("claude-opus-5-5", (body["model"] as JsonPrimitive).content)
        assertEquals("32000", (body["max_tokens"] as JsonPrimitive).content)
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("adaptive", thinking.string("type"))
        assertNull(thinking["budget_tokens"])
        assertEquals("drop_block", thinking["block_binding"]!!.jsonObject.string("prefix_mismatch_behavior"))
        assertEquals("medium", body["output_config"]!!.jsonObject.string("effort"))
        assertEquals("auto", body["tool_choice"]!!.jsonObject.string("type"))
        // No sampling parameters, no forced tool choice, no prefill (the last message is the user's).
        listOf("temperature", "top_p", "top_k", "stop_sequences").forEach { assertNull(body[it], it) }
        assertEquals("user", body["messages"]!!.jsonArray.last().jsonObject.string("role"))
        // Tools sorted by name; the cache breakpoint on the (last) system block, plus automatic caching of the tail.
        assertEquals(listOf("aa_first", "zz_last"), body["tools"]!!.jsonArray.map { it.jsonObject.string("name") })
        assertTrue(body["tools"]!!.jsonArray.none { it.jsonObject.containsKey("cache_control") || it.jsonObject.containsKey("strict") })
        val system = body["system"]!!.jsonArray
        assertEquals("ephemeral", system.last().jsonObject["cache_control"]!!.jsonObject.string("type"))
        assertEquals("ephemeral", body["cache_control"]!!.jsonObject.string("type"))
        // Refusal fallback chosen by the server; compaction when the history grows.
        assertEquals("default", (body["fallbacks"] as JsonPrimitive).content)
        assertEquals("compact_20260112", body["context_management"]!!.jsonObject["edits"]!!.jsonArray[0].jsonObject.string("type"))
        val headers = betas()
        listOf("server-side-fallback-2026-07-01", "thinking-binding-controls-2026-08-01", "compact-2026-01-12").forEach { assertTrue(headers.contains(it), headers) }
    }

    @Test
    fun theHistoryIsReplayedUnchangedThinkingIncluded() {
        val body = AnthropicRequests.bodyJson(AnthropicRequests.build(request()))
        val messages = body["messages"]!!.jsonArray
        assertEquals(history.size, messages.size)
        history.forEachIndexed { index, turn ->
            assertEquals(turn.role, messages[index].jsonObject.string("role"))
            assertEquals(turn.content, messages[index].jsonObject["content"], "message $index")
        }
    }

    @Test
    fun haikuGetsNoFallbackAndNoToolsMeansNoToolChoice() {
        val body = AnthropicRequests.bodyJson(AnthropicRequests.build(request("claude-haiku-5-5", tools = emptyList())))
        assertNull(body["fallbacks"])
        assertNull(body["tool_choice"])
        assertNull(body["tools"])
        assertFalse(betas("claude-haiku-5-5").contains("server-side-fallback"))
    }

    @Test
    fun theResponseIsStoredAsTheApiExpectsItBackAndUsageSumsIterations() {
        val message = Scripted.message("tool_use", Scripted.thinking("firma-2"), Scripted.text("Busco."), Scripted.toolUse("toolu_2", "crm_search_leads"), input = 7, output = 3, cacheRead = 20, cacheWrite = 5)
        val response = AnthropicResponses.from(message)
        assertEquals("tool_use", response.stopReason)
        assertEquals(listOf("thinking", "text", "tool_use"), response.content.map { AnthropicResponses.blockType(it) })
        assertEquals("firma-2", response.content[0].jsonObject.string("signature"))
        assertEquals(ModelUsage(7, 3, 20, 5), response.usage)
        assertFalse(response.fallbackUsed)
        val refused = AnthropicResponses.from(Scripted.message("refusal", stopDetails = buildJsonObject { put("type", "refusal"); put("category", "bio") }))
        assertEquals("refusal", refused.stopReason)
        assertEquals("bio", refused.refusalCategory)
    }

    @Test
    fun onlyToolsTheUserMayUseNowAreOfferedAndNeverTheServersOwn() {
        fun tool(name: String, permission: String, readOnly: Boolean = true) = McpTool(name, name, "Hace $name.", permission, readOnly = readOnly) { JsonPrimitive("ok") }
        val modules = listOf(
            RegisteredModuleMcp("clientes", "Clientes", "Guia", listOf(
                RegisteredMcpTool("clientes", "Clientes", tool("search_customers", "clientes.view")),
                RegisteredMcpTool("clientes", "Clientes", tool("create_customer", "clientes.create", readOnly = false)),
            )),
            RegisteredModuleMcp("crm", "CRM", "", listOf(RegisteredMcpTool("crm", "CRM", tool("search_leads", "crm.view")))),
            RegisteredModuleMcp("hotspot", "Hotspot", "", listOf(RegisteredMcpTool("hotspot", "Hotspot", tool("reports", "hotspot.view")))),
            RegisteredModuleMcp(ServerMcpModuleId, "Servidor", "", listOf(RegisteredMcpTool(ServerMcpModuleId, "Servidor", tool("delete_company", "servidor.x", readOnly = false)))),
        )
        val user = UUID.randomUUID()
        val ana = UserModuleAccess("acme", user, false, false, setOf("clientes.view", "crm.view", "hotspot.view"), setOf("clientes", "crm")) { true }
        assertEquals(listOf("clientes_search_customers", "crm_search_leads"), AssistantTools.visible(modules, ana).map { it.name })
        val owner = UserModuleAccess("acme", user, true, false, emptySet(), setOf("clientes", "crm", "hotspot")) { true }
        val ownerTools = AssistantTools.visible(modules, owner)
        assertEquals(listOf("clientes_create_customer", "clientes_search_customers", "crm_search_leads", "hotspot_reports"), ownerTools.map { it.name })
        assertFalse(ownerTools.first().readOnly)
        assertEquals("Clientes: create_customer", ownerTools.first().label)
        // A module not installed on the server offers nothing even if enabled in the company.
        val notInstalled = UserModuleAccess("acme", user, true, false, emptySet(), setOf("clientes")) { it != "clientes" }
        assertTrue(AssistantTools.visible(modules, notInstalled).isEmpty())
    }

    @Test
    fun theActivityLogNamesTheAssistantAndNobodyCanPassForIt() {
        val ana = ActivityActor.user(UUID.randomUUID(), "Ana Ruiz")
        val assistant = ana.viaAssistant()
        assertEquals("Asistente de Ana Ruiz", assistant.display)
        assertEquals(ActivityActor.Origins.Assistant, assistant.origin)
        val shown = ActivityDisplay.of(assistant.kind, assistant.name, assistant.agentName, assistant.realKind, assistant.realName, assistant.origin)
        assertTrue(shown.viaAssistant && !shown.viaAgent)
        assertEquals("Asistente IA", shown.kindLabel)
        // An agent token called "Asistente" is still an agent.
        assertEquals("Agente de Ana Ruiz", ana.viaAgent("Asistente").display)
        // The marker is reserved like "Agente de": a person or agent named that is quoted.
        assertTrue(ActivityNames.hasMarker("Asistente de Luis"))
        assertEquals("«Asistente de Luis»", ActivityDisplay.of(ActivityActor.Kinds.User, "Asistente de Luis", null, null, null).display)
        // Switcher sessions keep the real person.
        val switcher = ActivityActor.switcher(UUID.randomUUID(), "Dueno", ActivityActor.Kinds.ServerOwner, UUID.randomUUID(), "Duena")
        assertEquals("Asistente de Duena (desde el servidor)", switcher.viaAssistant().display)
        // A module cannot claim the assistant origin for another actor, and the assistant keeps it.
        assertEquals(ActivityActor.Origins.App, ActivityLog.rowOrigin(ActivityActor.Origins.Assistant, ActivityActor.Origins.App))
        assertEquals(ActivityActor.Origins.Assistant, ActivityLog.rowOrigin(ActivityActor.Origins.Api, ActivityActor.Origins.Assistant))
        assertEquals(ActivityActor.Origins.Api, ActivityLog.rowOrigin(ActivityActor.Origins.Api, ActivityActor.Origins.App))
        // A row captured from a tool call carries the actor unchanged.
        val captured = mutableListOf<ActivityActor>()
        ActivityLog.capturing({ actor, _ -> captured += actor }) {
            ActivityLog.record(dummyConnection(), assistant, ActivityEntry("clientes", "customer", "1", "Ana", "created", "Creo el cliente Ana."))
        }
        assertEquals("Asistente de Ana Ruiz", captured.single().display)
    }

    @Test
    fun keysAreCheckedAndOnlyAHintIsShown() {
        assertEquals("sk-ant-…WXYZ", AssistantKeys.hint(AssistantKeys.validate("  sk-ant-api03-abcdefghijklmnopWXYZ ")))
        listOf("", "sk-live-abc", "sk-ant-short", "sk-ant-abc def ghi jkl mno pqr", "sk-ant-\u0000abcdefghijklmnopqrs").forEach { bad ->
            assertEquals("API_KEY_INVALID", runCatching { AssistantKeys.validate(bad) }.exceptionOrNull().let { (it as AssistantSettingsException).code })
        }
    }

    @Test
    fun theConfirmationSummaryUsesTheSchemaLabels() {
        val tool = FakeToolbox.tool("clientes_create_customer", readOnly = false)
        val summary = AssistantTools.summary(tool, buildJsonObject { put("nombre", "Ana"); put("otro", "x".repeat(400)) })
        assertEquals("Nombre del cliente.", summary[0].jsonObject.string("label"))
        assertEquals("otro", summary[1].jsonObject.string("label"))
        // Never cut (the card must show what will really be done).
        assertEquals(400, summary[1].jsonObject.string("value")!!.length)
    }

    private fun dummyConnection(): java.sql.Connection = java.lang.reflect.Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(java.sql.Connection::class.java),
    ) { _, _, _ -> throw UnsupportedOperationException() } as java.sql.Connection
}
