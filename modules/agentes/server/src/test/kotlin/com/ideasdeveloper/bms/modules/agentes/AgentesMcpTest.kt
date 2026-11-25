package com.ideasdeveloper.bms.modules.agentes

import com.ideasdeveloper.bms.server.company.application.ServerWorkspace
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.RegisteredMcpTool
import com.ideasdeveloper.bms.server.modules.RegisteredModuleMcp
import com.ideasdeveloper.bms.server.modules.ServerMcpModuleId
import com.ideasdeveloper.bms.server.modules.UserModuleAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentesMcpTest {
    private val userId = UUID.randomUUID()

    private fun tool(name: String, permission: String, readOnly: Boolean = true) =
        McpTool(name, name, "Hace $name.", permission, readOnly = readOnly) { JsonPrimitive("ok:$name") }

    private val catalog = listOf(
        RegisteredMcpTool("clientes", "Clientes", tool("search_customers", "clientes.view")),
        RegisteredMcpTool("clientes", "Clientes", tool("create_customer", "clientes.create", readOnly = false)),
        RegisteredMcpTool("crm", "CRM", tool("search_leads", "crm.view")),
        RegisteredMcpTool("hotspot", "Hotspot", tool("reports", "hotspot.reports.view")),
    )

    private fun token(modules: Set<String>? = null, readOnly: Boolean = false) = AgentToken(
        id = UUID.randomUUID(), userId = userId, serverOwner = false, userName = "ana", name = "Claude",
        hint = "abcdef", allowedModules = modules, readOnly = readOnly, createdAt = Instant.now(),
        expiresAt = null, lastUsedAt = null, revokedAt = null, revokedBy = null,
    )

    private fun access(granted: Set<String>, enabled: Set<String> = setOf("clientes", "crm"), owner: Boolean = false) =
        UserModuleAccess("acme", userId, owner, serverOwner = false, granted, enabled) { true }

    private fun names(tools: List<VisibleTool>) = tools.map { it.name }.toSet()

    @Test
    fun agentSeesOnlyToolsItsUserIsAllowedToUse() {
        val visible = AgentesMcp.visibleTools(catalog, token(), access(setOf("clientes.view", "crm.view", "hotspot.reports.view")))
        // No clientes.create permission, and hotspot is not enabled for the company.
        assertEquals(setOf("clientes_search_customers", "crm_search_leads"), names(visible))
    }

    @Test
    fun businessOwnerSeesEveryToolOfEnabledModules() {
        val visible = AgentesMcp.visibleTools(catalog, token(), access(emptySet(), owner = true))
        assertEquals(setOf("clientes_search_customers", "clientes_create_customer", "crm_search_leads"), names(visible))
    }

    @Test
    fun tokenNarrowsModulesAndWrites() {
        val owner = access(emptySet(), owner = true)
        assertEquals(setOf("crm_search_leads"), names(AgentesMcp.visibleTools(catalog, token(modules = setOf("crm")), owner)))
        assertEquals(setOf("clientes_search_customers", "crm_search_leads"), names(AgentesMcp.visibleTools(catalog, token(readOnly = true), owner)))
    }

    // The bridge only relays what each module says about itself, and only for modules the agent can use.
    @Test
    fun instructionsComeFromTheUsableModules() {
        val modules = catalog.groupBy { it.moduleId }.map { (id, tools) -> RegisteredModuleMcp(id, tools.first().moduleName, "Instrucciones de $id.", tools) }
        val visible = AgentesMcp.visibleTools(catalog, token(), access(setOf("clientes.view")))
        val text = AgentesMcp.instructions("Puente.", modules, visible)
        assertEquals("Puente.\n\n## Clientes\nInstrucciones de clientes.", text)
    }

    // A server owner's agent manages every company: all installed modules' tools plus the server's,
    // module tools taking acting_company; the module must be enabled where it acts (checked per call).
    @Test
    fun serverOwnerAgentReachesEveryCompany() {
        val server = RegisteredMcpTool(ServerMcpModuleId, "Servidor", tool("list_companies", "server_owner"))
        val all = catalog + server
        val ownerAccess = UserModuleAccess(ServerWorkspace.Code, userId, true, serverOwner = true, emptySet(), setOf("clientes")) { true }
        val visible = AgentesMcp.visibleTools(all, token().copy(serverOwner = true), ownerAccess)
        assertEquals(names(all.map { VisibleTool(it, null) }), names(visible))
        val byName = visible.associateBy { it.name }
        assertTrue(byName.getValue("crm_search_leads").anyCompany)
        assertNull(byName.getValue("crm_search_leads").permissions)
        assertTrue(byName.getValue("clientes_search_customers").permissions != null)
        assertTrue(!byName.getValue("servidor_list_companies").anyCompany)

        val listed = AgentesMcp("test", { visible }, { _, _ -> ToolOutcome.Success(JsonPrimitive("ok")) })
            .handle(request(1, "tools/list"))!!.jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            .associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject }
        assertTrue(AgentesMcp.ActingCompany in listed.getValue("crm_search_leads"))
        assertTrue(AgentesMcp.ActingCompany !in listed.getValue("servidor_list_companies"))

        // Anyone else never gets the server's tools nor acting_company.
        val staff = AgentesMcp.visibleTools(all, token(), access(setOf("crm.view")))
        assertEquals(setOf("crm_search_leads"), names(staff))
        assertTrue(staff.none { it.anyCompany })
        assertEquals("acme", AgentesMcp.actingCompany(Json.parseToJsonElement("""{"acting_company":" acme "}""").jsonObject))
        assertNull(AgentesMcp.actingCompany(JsonObject(emptyMap())))
    }

    // A business owner whose company shares its owner group: the module tools take acting_company for the
    // companies of the group; the group itself is checked on every call (ModuleAccessService.groupOwnerAccess).
    @Test
    fun groupOwnerAgentReachesTheCompaniesOfItsGroup() {
        val server = RegisteredMcpTool(ServerMcpModuleId, "Servidor", tool("list_companies", "server_owner"))
        val all = catalog + server
        val owner = access(emptySet(), enabled = setOf("clientes"), owner = true)
        val visible = AgentesMcp.visibleTools(all, token(), owner, ownerGroup = true)
        // Every module tool, also of modules that are only enabled in another company of the group; never the server's.
        assertEquals(names(catalog.map { VisibleTool(it, null) }), names(visible))
        val byName = visible.associateBy { it.name }
        assertTrue(visible.all { it.anyCompany && it.ownerGroup })
        assertNull(byName.getValue("crm_search_leads").permissions)
        assertTrue(byName.getValue("clientes_search_customers").permissions != null)

        val listed = AgentesMcp("test", { visible }, { _, _ -> ToolOutcome.Success(JsonPrimitive("ok")) })
            .handle(request(1, "tools/list"))!!.jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            .associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject }
        val hint = listed.getValue("crm_search_leads").getValue(AgentesMcp.ActingCompany).jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(AgentesMcp.GroupCompaniesTool in hint)
        assertTrue("servidor_list_companies" !in hint)
    }

    @Test
    fun groupScopeIsOnlyForBusinessOwnersAndRespectsTheToken() {
        val server = RegisteredMcpTool(ServerMcpModuleId, "Servidor", tool("list_companies", "server_owner"))
        val all = catalog + server
        // A user that is not the business owner gets nothing new, whatever the caller claims.
        val staff = AgentesMcp.visibleTools(all, token(), access(setOf("crm.view")), ownerGroup = true)
        assertEquals(setOf("crm_search_leads"), names(staff))
        assertTrue(staff.none { it.anyCompany || it.ownerGroup })
        // A business owner whose company is alone in its group keeps the tools of its own company only.
        val alone = AgentesMcp.visibleTools(all, token(), access(emptySet(), owner = true), ownerGroup = false)
        assertEquals(setOf("clientes_search_customers", "clientes_create_customer", "crm_search_leads"), names(alone))
        assertTrue(alone.none { it.anyCompany })
        // The token still narrows modules and writes in every company of the group.
        val owner = access(emptySet(), owner = true)
        assertEquals(setOf("crm_search_leads"), names(AgentesMcp.visibleTools(all, token(modules = setOf("crm")), owner, ownerGroup = true)))
        val readOnly = AgentesMcp.visibleTools(all, token(readOnly = true), owner, ownerGroup = true)
        assertEquals(setOf("clientes_search_customers", "crm_search_leads", "hotspot_reports"), names(readOnly))
        assertTrue(readOnly.none { !it.registered.tool.readOnly })
        // The server owner is unchanged by the flag: any company, and not marked as group scope.
        val serverOwner = UserModuleAccess(ServerWorkspace.Code, userId, true, serverOwner = true, emptySet(), setOf("clientes")) { true }
        val asServerOwner = AgentesMcp.visibleTools(all, token().copy(serverOwner = true), serverOwner, ownerGroup = true)
        assertEquals(names(all.map { VisibleTool(it, null) }), names(asServerOwner))
        assertTrue(asServerOwner.none { it.ownerGroup })
        assertTrue(!asServerOwner.first { it.name == "servidor_list_companies" }.anyCompany)
    }

    @Test
    fun actingCompanyThatIsNotTextIsMalformed() {
        fun args(json: String) = Json.parseToJsonElement(json).jsonObject
        assertTrue(AgentesMcp.malformedActingCompany(args("""{"acting_company":{"code":"b"}}""")))
        assertTrue(AgentesMcp.malformedActingCompany(args("""{"acting_company":["b"]}""")))
        assertTrue(AgentesMcp.malformedActingCompany(args("""{"acting_company":5}""")))
        assertTrue(AgentesMcp.malformedActingCompany(args("""{"acting_company":true}""")))
        assertTrue(!AgentesMcp.malformedActingCompany(args("""{"acting_company":"b"}""")))
        assertTrue(!AgentesMcp.malformedActingCompany(args("""{"acting_company":""}""")))
        assertTrue(!AgentesMcp.malformedActingCompany(args("""{"acting_company":null}""")))
        assertTrue(!AgentesMcp.malformedActingCompany(args("""{"id":"x"}""")))
    }

    @Test
    fun oversizedBatchesAreRefusedWhole() {
        var calls = 0
        val visible = AgentesMcp.visibleTools(catalog, token(), access(setOf("clientes.view")))
        val mcp = AgentesMcp("test", { visible }, { _, _ -> calls++; ToolOutcome.Success(JsonPrimitive("ok")) })
        val call = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"clientes_search_customers"}}"""
        val tooMany = Json.parseToJsonElement("[" + List(AgentesMcp.MaxBatch + 1) { call }.joinToString(",") + "]")
        val refused = mcp.handle(tooMany)!!.jsonObject
        assertEquals(AgentesMcp.InvalidRequest, refused["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(0, calls)
        val allowed = Json.parseToJsonElement("[" + List(AgentesMcp.MaxBatch) { call }.joinToString(",") + "]")
        assertEquals(AgentesMcp.MaxBatch, mcp.handle(allowed)!!.jsonArray.size)
        assertEquals(AgentesMcp.MaxBatch, calls)
    }

    @Test
    fun unknownToolNamesAreLoggedOnlyWhenTheyLookLikeOne() {
        assertEquals("crm_search_leads", AgentesMcp.logTool("crm_search_leads"))
        assertEquals("(nombre no valido)", AgentesMcp.logTool("crm_x @ empresa_b"))
        assertEquals("(nombre no valido)", AgentesMcp.logTool("x".repeat(300)))
    }

    @Test
    fun requestedCompaniesAreLoggedByCodeOnly() {
        assertEquals("otra_empresa", AgentesMcp.logTarget("otra_empresa"))
        assertEquals("(empresa no valida)", AgentesMcp.logTarget("otra'; DROP TABLE x; --"))
        assertEquals("(empresa no valida)", AgentesMcp.logTarget("a".repeat(200)))
        assertEquals("(empresa no valida)", AgentesMcp.logTarget("Servidor"))
    }

    private fun protocol(granted: Set<String>, denied: MutableList<String> = mutableListOf()): AgentesMcp {
        val visible = AgentesMcp.visibleTools(catalog, token(), access(granted))
        return AgentesMcp(
            instructions = "test",
            tools = { visible },
            invoke = { tool, arguments -> if (arguments.containsKey("fail")) ToolOutcome.Failure("fallo") else ToolOutcome.Success(JsonPrimitive(tool.name)) },
            denied = { denied += it },
        )
    }

    private fun request(id: Int, method: String, params: String = "{}") =
        Json.parseToJsonElement("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")

    @Test
    fun initializeNegotiatesTheProtocolVersion() {
        val known = protocol(emptySet()).handle(request(1, "initialize", """{"protocolVersion":"2025-03-26"}"""))!!.jsonObject
        assertEquals("2025-03-26", known["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        val unknown = protocol(emptySet()).handle(request(1, "initialize", """{"protocolVersion":"1999-01-01"}"""))!!.jsonObject
        assertEquals(AgentesMcp.SupportedVersions.first(), unknown["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun notificationsGetNoAnswer() {
        assertNull(protocol(emptySet()).handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")))
    }

    @Test
    fun toolsListReturnsVisibleToolsWithAnnotations() {
        val answer = protocol(setOf("clientes.view")).handle(request(2, "tools/list"))!!.jsonObject
        val tools = answer["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val tool = tools.first().jsonObject
        assertEquals("clientes_search_customers", tool["name"]!!.jsonPrimitive.content)
        assertTrue(tool["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun callingAToolOutsideThePermissionsIsRejectedAndRecorded() {
        val denied = mutableListOf<String>()
        val answer = protocol(setOf("clientes.view"), denied).handle(request(3, "tools/call", """{"name":"clientes_create_customer","arguments":{}}"""))!!.jsonObject
        assertEquals(AgentesMcp.InvalidParams, answer["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(listOf("clientes_create_customer"), denied)
    }

    @Test
    fun toolResultsAndToolErrors() {
        val ok = protocol(setOf("clientes.view")).handle(request(4, "tools/call", """{"name":"clientes_search_customers"}"""))!!.jsonObject["result"]!!.jsonObject
        assertEquals(false, ok["isError"]!!.jsonPrimitive.boolean)
        val failed = protocol(setOf("clientes.view")).handle(request(5, "tools/call", """{"name":"clientes_search_customers","arguments":{"fail":true}}"""))!!.jsonObject["result"]!!.jsonObject
        assertEquals(true, failed["isError"]!!.jsonPrimitive.boolean)
        assertEquals("fallo", failed["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownMethodsAndBatches() {
        val mcp = protocol(emptySet())
        val unknown = mcp.handle(request(6, "sampling/createMessage"))!!.jsonObject
        assertEquals(AgentesMcp.MethodNotFound, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val batch = mcp.handle(JsonArray(listOf(request(7, "ping"), Json.parseToJsonElement("""{"jsonrpc":"2.0","method":"notifications/x"}"""))))
        assertEquals(1, (batch as JsonArray).size)
        assertEquals(JsonObject(emptyMap()), batch.first().jsonObject["result"])
    }

    @Test
    fun tokenFormat() {
        val secret = "A".repeat(43)
        assertEquals("acme_2", AgentTokens.companyOf("bmsmcp.acme_2.$secret"))
        assertNull(AgentTokens.companyOf("bmsmcp.Acme.$secret"))
        assertNull(AgentTokens.companyOf("bmsmcp.acme.short"))
        assertNull(AgentTokens.companyOf("other.acme.$secret"))
        assertNull(AgentTokens.companyOf(""))
    }

    @Test
    fun newTokenForm() {
        val now = Instant.parse("2026-11-01T00:00:00Z")
        val all = AgentesScreens.parseNewToken(mapOf("name" to " Claude ", "m_crm" to "true", "m_clientes" to "true", "expires" to "30 dias"), listOf("clientes", "crm"), now)
        assertEquals("Claude", all.name)
        // Every module chosen: the agent also gets modules enabled later.
        assertNull(all.allowedModules)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), all.expiresAt)

        val some = AgentesScreens.parseNewToken(mapOf("name" to "x", "m_crm" to "true", "readOnly" to "true", "expires" to "Sin vencimiento"), listOf("clientes", "crm"), now)
        assertEquals(setOf("crm"), some.allowedModules)
        assertTrue(some.readOnly)
        assertNull(some.expiresAt)

        assertFailsWith<AgentesValidationException> { AgentesScreens.parseNewToken(mapOf("name" to "x", "expires" to "30 dias"), listOf("crm"), now) }
        assertFailsWith<AgentesValidationException> { AgentesScreens.parseNewToken(mapOf("name" to "", "m_crm" to "true", "expires" to "30 dias"), listOf("crm"), now) }
    }

    @Test
    fun rateLimiterResetsEveryMinute() {
        var now = 0L
        val limiter = AgentRateLimiter(perMinute = 2) { now }
        assertTrue(limiter.allow("a"))
        assertTrue(limiter.allow("a"))
        assertEquals(false, limiter.allow("a"))
        assertTrue(limiter.allow("b"))
        now = 60_000
        assertTrue(limiter.allow("a"))
    }
}
