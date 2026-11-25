package com.ideasdeveloper.bms.modules.agentes

import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.server.modules.RegisteredMcpTool
import com.ideasdeveloper.bms.server.modules.RegisteredModuleMcp
import com.ideasdeveloper.bms.server.modules.ServerMcpModuleId
import com.ideasdeveloper.bms.server.modules.UserModuleAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A tool the connected agent may use, with the user's permissions in its module in the agent's own
 * company. [anyCompany]: the agent may also run it in another company (argument
 * [AgentesMcp.ActingCompany]): a server owner's agent in any company of the server, or with
 * [ownerGroup] a business owner's agent in the other companies of its owner group. [permissions] is
 * then null when the module is not enabled in the agent's own company.
 */
class VisibleTool(
    val registered: RegisteredMcpTool,
    val permissions: ModulePermissions?,
    val anyCompany: Boolean = false,
    val ownerGroup: Boolean = false,
) {
    val name: String get() = registered.qualifiedName
}

// Result of running a tool: its output for the agent, or an error message the agent can act on.
sealed class ToolOutcome {
    data class Success(val result: JsonElement) : ToolOutcome()
    data class Failure(val message: String) : ToolOutcome()
}

/**
 * Model Context Protocol (JSON-RPC 2.0 over Streamable HTTP, JSON responses only) for one agent
 * request. Database and permission work is injected so the protocol can be tested on its own.
 */
class AgentesMcp(
    private val instructions: String,
    private val tools: () -> List<VisibleTool>,
    private val invoke: (VisibleTool, JsonObject) -> ToolOutcome,
    // Records tools/call requests for names the agent may not use.
    private val denied: (String) -> Unit = {},
) {
    /** Answers a JSON-RPC message or batch; null when nothing must be returned (only notifications). */
    fun handle(message: JsonElement): JsonElement? = when (message) {
        // A batch is answered by one request of the rate limit, so it stays small.
        is JsonArray ->
            if (message.size > MaxBatch) error(JsonNull, InvalidRequest, "Lote demasiado grande: maximo $MaxBatch mensajes.")
            else message.mapNotNull { handleOne(it) }.takeIf { it.isNotEmpty() }?.let(::JsonArray)
        else -> handleOne(message)
    }

    private fun handleOne(message: JsonElement): JsonObject? {
        val request = message as? JsonObject ?: return error(JsonNull, InvalidRequest, "La solicitud no es un objeto JSON-RPC.")
        val id = request["id"]
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull
        // Responses from the client (we never send requests) and notifications get no answer.
        if (method == null) return if (id == null) null else error(id, InvalidRequest, "Falta el metodo.")
        if (id == null) return null
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(tools().map(::toolJson))) })
            "tools/call" -> callTool(id, params)
            "resources/list" -> result(id, buildJsonObject { putJsonArray("resources") {} })
            "prompts/list" -> result(id, buildJsonObject { putJsonArray("prompts") {} })
            else -> error(id, MethodNotFound, "Metodo no soportado: $method")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in SupportedVersions } ?: SupportedVersions.first())
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", "bms")
                put("title", "BMS")
                put("version", AgentesServerModule.definition.version)
            }
            put("instructions", instructions)
        }
    }

    private fun callTool(id: JsonElement, params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val arguments = when (val raw = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> return error(id, InvalidParams, "Los argumentos deben ser un objeto.")
        }
        val tool = tools().firstOrNull { it.name == name } ?: run {
            denied(name)
            return error(id, InvalidParams, "Herramienta desconocida o sin permiso: $name")
        }
        val (text, isError) = when (val outcome = invoke(tool, arguments)) {
            is ToolOutcome.Success -> outcome.result.toString() to false
            is ToolOutcome.Failure -> outcome.message to true
        }
        return result(id, buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            put("isError", isError)
        })
    }

    private fun toolJson(visible: VisibleTool): JsonObject {
        val tool = visible.registered.tool
        val title = "${visible.registered.moduleName}: ${tool.title}"
        return buildJsonObject {
            put("name", visible.name)
            put("title", title)
            put("description", tool.description)
            put("inputSchema", if (visible.anyCompany) withActingCompany(tool.inputSchema, visible.ownerGroup) else tool.inputSchema)
            putJsonObject("annotations") {
                put("title", title)
                put("readOnlyHint", tool.readOnly)
                put("destructiveHint", tool.destructive)
                put("openWorldHint", false)
            }
        }
    }

    // Adds the bridge's own argument to a module tool's schema; the bridge removes it before the tool runs.
    private fun withActingCompany(schema: JsonObject, ownerGroup: Boolean): JsonObject {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val acting = buildJsonObject {
            put("type", "string")
            put(
                "description",
                if (ownerGroup) "Codigo de otra empresa de tu grupo donde actuar ($GroupCompaniesTool). Si se omite, tu empresa."
                else "Codigo de la empresa donde actuar (servidor_list_companies). Si se omite, el espacio del servidor.",
            )
        }
        return JsonObject(schema + ("properties" to JsonObject(properties + (ActingCompany to acting))))
    }

    companion object {
        // Argument of a server owner's or a group owner's agent to run a module tool in another company. Modules must not use it.
        const val ActingCompany = "acting_company"
        // This module's tool that lists the companies a business owner's agent may act in.
        const val GroupCompaniesTool = "agentes_list_group_companies"
        private val CompanyCode = Regex("[a-z][a-z0-9_]{0,62}")
        private val ToolName = Regex("[A-Za-z0-9_.-]{1,96}")
        const val MaxBatch = 20
        val SupportedVersions = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        const val ParseError = -32700
        const val InvalidRequest = -32600
        const val MethodNotFound = -32601
        const val InvalidParams = -32602

        /**
         * The bridge's own text ([base]) followed by the instructions each module wrote for agents,
         * only for the modules the agent can use some tool of. The bridge adds nothing about modules.
         */
        fun instructions(base: String, modules: List<RegisteredModuleMcp>, visible: List<VisibleTool>): String {
            val usable = visible.map { it.registered.moduleId }.toSet()
            return (listOf(base) + modules.filter { it.moduleId in usable && it.instructions.isNotEmpty() }.map { "## ${it.moduleName}\n${it.instructions}" })
                .joinToString("\n\n")
        }

        /**
         * Tools the agent may use now: allowed by its token and permitted to its user in an enabled module.
         * A server owner's agent gets every installed module's tools, to use in any company; whether the
         * module is enabled there is checked on each call. With [ownerGroup] (the business owner of a
         * company that shares its owner group with others, never a server owner) the same holds for the
         * companies of that group. The server's own tools stay with server owners.
         */
        fun visibleTools(
            all: List<RegisteredMcpTool>,
            token: AgentToken,
            access: UserModuleAccess,
            ownerGroup: Boolean = false,
            // Modules enabled in some other company of the group: only their tools are offered for those companies.
            groupModules: Set<String> = emptySet(),
        ): List<VisibleTool> {
            val groupScope = ownerGroup && access.businessOwner && !access.serverOwner
            return all.mapNotNull { registered ->
                if (!token.allowsModule(registered.moduleId)) return@mapNotNull null
                if (token.readOnly && !registered.tool.readOnly) return@mapNotNull null
                val anyCompany = (access.serverOwner || (groupScope && registered.moduleId in groupModules)) && registered.moduleId != ServerMcpModuleId
                val permissions = access.permissions(registered.moduleId)
                if (permissions == null && !anyCompany) return@mapNotNull null
                if (permissions != null && !permissions.has(registered.tool.permission)) return@mapNotNull null
                VisibleTool(registered, permissions, anyCompany, ownerGroup = anyCompany && groupScope)
            }
        }

        // The company the agent asked to act on, or null for its own company.
        fun actingCompany(arguments: JsonObject): String? =
            (arguments[ActingCompany] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * How a requested company is written in the activity log: its code, or a fixed text when it
         * does not look like one, so whatever the agent sent never reaches the log as is.
         */
        fun logTarget(target: String): String = if (CompanyCode.matches(target)) target else "(empresa no valida)"

        /** A requested tool name as written in the activity log: itself, or a fixed text when it does not look like one. */
        fun logTool(name: String): String = if (ToolName.matches(name)) name else "(nombre no valido)"

        /**
         * Whether [ActingCompany] was sent with something that is not text. Such a call is refused
         * instead of running in the agent's own company, where the agent did not mean to act.
         */
        fun malformedActingCompany(arguments: JsonObject): Boolean =
            arguments[ActingCompany]?.let { it !is JsonNull && (it !is JsonPrimitive || !it.isString) } ?: false

        fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

        fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
    }
}

/** Requests per minute per agent token, so a looping agent cannot saturate the server. */
class AgentRateLimiter(private val perMinute: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private class Window(var start: Long, var count: Int)
    private val windows = ConcurrentHashMap<String, Window>()

    fun allow(key: String): Boolean {
        val now = clock()
        if (windows.size > 10_000) windows.entries.removeIf { now - it.value.start >= 60_000 }
        val window = windows.computeIfAbsent(key) { Window(now, 0) }
        synchronized(window) {
            if (now - window.start >= 60_000) {
                window.start = now
                window.count = 0
            }
            window.count++
            return window.count <= perMinute
        }
    }
}
