package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.McpToolException
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
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
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

/** A module tool as the assistant offers it: the module's own definition, nothing added. */
class AssistantTool(
    val name: String,
    val moduleId: String,
    val moduleName: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
    val destructive: Boolean,
    // Sends something out of the company (McpTool.outbound).
    val outbound: Boolean = false,
) {
    val label: String get() = "$moduleName: $title"
}

/** What the module resolved for the confirmation card ([McpTool.preview]): rows, or why it could not. */
class AssistantPreview(val rows: JsonArray, val error: String? = null)

/** What running a tool gave: its output for the model, or an error message it can act on. */
class AssistantToolResult(val ok: Boolean, val text: String)

/**
 * The tools of one user in one company, read live (GLOBAL_AGENT.md, Contract, "How the assistant gets its tools").
 * Implementations must compute [tools] from the user's current permissions every time and refuse in [execute] any
 * name that is not in that list.
 */
interface AssistantToolbox {
    fun tools(): List<AssistantTool>

    /** Module name and the guidance it wrote, for the modules of [tools]. */
    fun instructions(): List<Pair<String, String>>

    fun execute(name: String, arguments: JsonObject): AssistantToolResult

    /** The tool's own preview of the call (read only, the user's permissions now); empty rows when it has none. */
    fun preview(name: String, arguments: JsonObject): AssistantPreview = AssistantPreview(JsonArray(emptyList()))
}

object AssistantTools {
    const val MaxResultChars = 100_000

    val UnknownTool: String get() = "Herramienta desconocida o sin permiso."

    /**
     * Every registered tool the user may use now in its own company: module enabled there and the tool's permission
     * held (UserModuleAccess.permissions, the same rule as the MCP bridge). The server's own tools are never offered.
     * Sorted by name, so the request prefix stays byte-identical.
     */
    fun visible(modules: List<RegisteredModuleMcp>, user: UserModuleAccess): List<AssistantTool> =
        modules.filter { it.moduleId != ServerMcpModuleId }.flatMap { it.tools }
            .filter { registered -> user.permissions(registered.moduleId)?.has(registered.tool.permission) == true }
            .map(::of)
            .sortedBy { it.name }

    fun of(registered: RegisteredMcpTool) = AssistantTool(
        name = registered.qualifiedName,
        moduleId = registered.moduleId,
        moduleName = registered.moduleName,
        title = registered.tool.title,
        description = registered.tool.description,
        inputSchema = registered.tool.inputSchema,
        readOnly = registered.tool.readOnly,
        destructive = registered.tool.destructive,
        outbound = registered.tool.outbound,
    )

    /** The tool output as tool_result text, cut to [MaxResultChars]. */
    fun resultText(result: JsonElement): String {
        val text = if (result is JsonPrimitive && result.isString) result.content else result.toString()
        return if (text.length <= MaxResultChars) text else text.take(MaxResultChars) + "\n[resultado recortado]"
    }

    /**
     * What the confirmation card shows: one row per argument, every argument and its whole value (never cut), labelled
     * with the schema's description of that argument (as the module wrote it). Characters that can hide or reorder
     * text (bidi controls, zero-width and other format or control characters) are shown as [U+XXXX] and the row is
     * flagged.
     */
    fun summary(tool: AssistantTool?, arguments: JsonObject): JsonArray {
        val properties = tool?.inputSchema?.get("properties") as? JsonObject
        return JsonArray(
            arguments.entries.map { (key, value) ->
                val description = ((properties?.get(key) as? JsonObject)?.get("description") as? JsonPrimitive)?.contentOrNull
                val shown = when (value) {
                    is JsonNull -> "-"
                    is JsonPrimitive -> value.content
                    else -> value.toString()
                }
                row(description?.takeIf { it.isNotBlank() } ?: key, shown)
            },
        )
    }

    /** A card row with [visible] applied to both texts. */
    fun row(label: String, value: String): JsonObject {
        val (cleanLabel, labelHidden) = visible(label)
        val (cleanValue, valueHidden) = visible(value)
        return buildJsonObject {
            put("label", cleanLabel)
            put("value", cleanValue)
            if (labelHidden || valueHidden) put("hiddenCharacters", true)
        }
    }

    /** [text] with every invisible or reordering character written as [U+XXXX] (newlines and tabs kept); true when any. */
    fun visible(text: String): Pair<String, Boolean> {
        var found = false
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val code = text.codePointAt(index)
            val type = Character.getType(code)
            val hidden = code != '\n'.code && code != '\t'.code && (
                type == Character.FORMAT.toInt() || type == Character.CONTROL.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                    type == Character.UNASSIGNED.toInt() || type == Character.PRIVATE_USE.toInt() || code == 0x115F || code == 0x1160 || code == 0x3164
                )
            if (hidden) {
                found = true
                out.append("[U+").append(String.format("%04X", code)).append(']')
            } else {
                out.appendCodePoint(code)
            }
            index += Character.charCount(code)
        }
        return out.toString() to found
    }
}

/**
 * The real toolbox: the module registry of [access] and the user's live access in [companyCode]. Each call opens its
 * own tenant connection; a tool runs with the same ModulePermissions a screen gets for that user, acting as
 * "Asistente de <usuario>" ([actor] is the session's actor, switcher-aware).
 */
class ModuleAssistantToolbox(
    private val access: ModuleAccessService,
    private val companyCode: String,
    private val userId: UUID,
    private val actor: () -> ActivityActor,
) : AssistantToolbox {
    private val log = LoggerFactory.getLogger(ModuleAssistantToolbox::class.java)

    private fun <T> tenant(block: (Connection) -> T): T? = access.tenantConnectionForCompany(companyCode)?.use(block)

    private fun user(tenant: Connection): UserModuleAccess? = access.userAccess(tenant, companyCode, userId, serverOwner = false)

    override fun tools(): List<AssistantTool> = tenant { connection ->
        user(connection)?.let { AssistantTools.visible(access.moduleMcp(), it) }
    }.orEmpty()

    override fun instructions(): List<Pair<String, String>> {
        val modules = access.moduleMcp()
        val usable = tools().map { it.moduleId }.toSet()
        return modules.filter { it.moduleId in usable && it.instructions.isNotBlank() }.sortedBy { it.moduleId }.map { it.moduleName to it.instructions }
    }

    override fun execute(name: String, arguments: JsonObject): AssistantToolResult = tenant { connection ->
        val user = user(connection) ?: return@tenant AssistantToolResult(false, AssistantTools.UnknownTool)
        // Read again now: a permission removed since the model saw the list stops the call here.
        val registered = access.moduleMcp().filter { it.moduleId != ServerMcpModuleId }.flatMap { it.tools }.firstOrNull { it.qualifiedName == name }
        val permissions = registered?.let { user.permissions(it.moduleId) }?.takeIf { it.has(registered.tool.permission) }
        if (registered == null || permissions == null) return@tenant AssistantToolResult(false, AssistantTools.UnknownTool)
        val call = McpToolCall(arguments, permissions.withActivityActor(actor().viaAssistant()), connection, access, ActivityActor.AssistantAgentName, registered.moduleId)
        try {
            AssistantToolResult(true, AssistantTools.resultText(registered.tool.handler(call)))
        } catch (failure: McpToolException) {
            AssistantToolResult(false, failure.message.orEmpty())
        } catch (failure: Exception) {
            // Never the arguments or the message: they may carry customer data.
            log.error("Assistant tool {} failed in company {}: {}", name, companyCode, failure.javaClass.name)
            AssistantToolResult(false, "No se pudo completar la operacion.")
        }
    } ?: AssistantToolResult(false, "No se pudo abrir la empresa.")

    override fun preview(name: String, arguments: JsonObject): AssistantPreview = tenant { connection ->
        val user = user(connection) ?: return@tenant AssistantPreview(JsonArray(emptyList()))
        val registered = access.moduleMcp().filter { it.moduleId != ServerMcpModuleId }.flatMap { it.tools }.firstOrNull { it.qualifiedName == name }
        val preview = registered?.tool?.preview ?: return@tenant AssistantPreview(JsonArray(emptyList()))
        val permissions = user.permissions(registered.moduleId)?.takeIf { it.has(registered.tool.permission) }
            ?: return@tenant AssistantPreview(JsonArray(emptyList()))
        val call = McpToolCall(arguments, permissions.withActivityActor(actor().viaAssistant()), connection, access, ActivityActor.AssistantAgentName, registered.moduleId)
        // Read only, whatever the module does: the preview runs before the user decided.
        val autoCommit = connection.autoCommit
        try {
            connection.autoCommit = false
            connection.createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
            AssistantPreview(JsonArray(preview(call).map { AssistantTools.row(it.label, it.value) }))
        } catch (failure: McpToolException) {
            AssistantPreview(JsonArray(emptyList()), failure.message.orEmpty())
        } catch (failure: Exception) {
            log.error("Assistant preview {} failed in company {}: {}", name, companyCode, failure.javaClass.name)
            AssistantPreview(JsonArray(emptyList()), "No se pudo preparar la vista previa.")
        } finally {
            runCatching { connection.rollback() }
            runCatching { connection.autoCommit = autoCommit }
        }
    } ?: AssistantPreview(JsonArray(emptyList()), "No se pudo abrir la empresa.")
}
