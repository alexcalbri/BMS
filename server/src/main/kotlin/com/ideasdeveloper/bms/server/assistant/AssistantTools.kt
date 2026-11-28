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

    /**
     * [text] with every invisible, reordering or data-smuggling character written as [U+XXXX] (newlines and tabs kept);
     * true when any. The rule:
     *  - always: control and format characters (bidi, zero-width, tags), line and paragraph separators, unassigned and
     *    private-use code points, and the blank-looking fillers (Hangul fillers, Khmer inherent vowels, Mongolian free
     *    variation selectors, combining grapheme joiner, Braille blank);
     *  - variation selectors (U+FE00-FE0F, U+E0100-E01EF) only where they mean something: FE0E/FE0F right after one
     *    symbol, digit, # or * (emoji or keycap presentation), the others right after one Han ideograph. Anywhere else
     *    (after a letter, repeated, stacked) they are how bytes are hidden in plain-looking text, so they are shown;
     *  - combining marks (accents, vowel signs): readable inside ordinary text, up to 3 on one base character. A mark
     *    with no base (at the start, after a space or after another non-letter) or more than 3 stacked ones are shown.
     */
    fun visible(text: String): Pair<String, Boolean> {
        val codes = text.codePoints().toArray()
        val hidden = BooleanArray(codes.size)
        var index = 0
        while (index < codes.size) {
            val code = codes[index]
            if ((alwaysHidden(code) && !(code == 0x200D && joinsEmoji(codes, index))) || (isVariationSelector(code) && !legitimateSelector(codes, index))) hidden[index] = true
            index++
        }
        // Marks: a run of combining marks belongs to the character before it.
        index = 0
        while (index < codes.size) {
            if (!isMark(codes[index])) { index++; continue }
            var end = index
            while (end < codes.size && isMark(codes[end])) end++
            // A selector that passed above is not a stacked mark.
            val run = (index until end).filter { !isVariationSelector(codes[it]) && !hidden[it] }
            val base = if (index == 0) null else codes[index - 1]
            val baseless = base == null || Character.isWhitespace(base) || isMarkless(base)
            if (run.size > 3 || (baseless && run.isNotEmpty())) run.forEach { hidden[it] = true }
            index = end
        }
        val out = StringBuilder(text.length)
        var found = false
        codes.forEachIndexed { at, code ->
            if (hidden[at]) {
                found = true
                out.append("[U+").append(String.format("%04X", code)).append(']')
            } else {
                out.appendCodePoint(code)
            }
        }
        return out.toString() to found
    }

    private fun alwaysHidden(code: Int): Boolean {
        if (code == '\n'.code || code == '\t'.code) return false
        val type = Character.getType(code)
        return type == Character.FORMAT.toInt() || type == Character.CONTROL.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() ||
            type == Character.UNASSIGNED.toInt() || type == Character.PRIVATE_USE.toInt() ||
            code == 0x115F || code == 0x1160 || code == 0x3164 || code == 0xFFA0 || code == 0x17B4 || code == 0x17B5 ||
            code in 0x180B..0x180F || code == 0x034F || code == 0x2800
    }

    private fun isEmojiBase(code: Int) = code in 0x1F000..0x1FAFF || code in 0x2190..0x2BFF || code in 0x2600..0x27BF || code == 0x2764

    /** A zero-width joiner between two emoji (one family or profession emoji), possibly with a presentation selector. */
    private fun joinsEmoji(codes: IntArray, index: Int): Boolean {
        var before = index - 1
        if (before >= 0 && (codes[before] == 0xFE0F || codes[before] == 0xFE0E)) before--
        return before >= 0 && index + 1 < codes.size && isEmojiBase(codes[before]) && isEmojiBase(codes[index + 1])
    }

    private fun isVariationSelector(code: Int) = code in 0xFE00..0xFE0F || code in 0xE0100..0xE01EF

    private fun isMark(code: Int): Boolean {
        val type = Character.getType(code)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
    }

    // A character that cannot carry a mark: nothing before it, or something that is not a letter, digit, symbol or mark.
    private fun isMarkless(code: Int): Boolean {
        val type = Character.getType(code)
        return when (type.toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    /** A variation selector at [index] that has a base it belongs to and is the only one on it. */
    private fun legitimateSelector(codes: IntArray, index: Int): Boolean {
        if (index == 0) return false
        val base = codes[index - 1]
        if (isVariationSelector(base) || (index + 1 < codes.size && isVariationSelector(codes[index + 1]))) return false
        val code = codes[index]
        return if (code == 0xFE0E || code == 0xFE0F) {
            val type = Character.getType(base)
            base == '#'.code || base == '*'.code || Character.isDigit(base) ||
                type == Character.OTHER_SYMBOL.toInt() || type == Character.MATH_SYMBOL.toInt() || type == Character.CURRENCY_SYMBOL.toInt() ||
                isEmojiBase(base)
        } else {
            Character.UnicodeScript.of(base) == Character.UnicodeScript.HAN
        }
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

    /**
     * The tool's own preview, run before the user decided, so it can change nothing and reach nothing else (GLOBAL_AGENT.md,
     * Preview sandbox). It gets a connection of its own, NOT from the pool, closed for good afterwards, whose every
     * transaction is read only for its whole life (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` before the first
     * transaction, plus `SET TRANSACTION READ ONLY`); the connection the module code sees refuses commit, autocommit and
     * read-only changes, close and unwrap; and the module service refuses to open any other connection meanwhile
     * ([ModuleAccessService.withoutNewConnections]). A preview makes no HTTP request of any kind either: that is the
     * module's rule (and its tests'), the JVM cannot enforce it here.
     */
    override fun preview(name: String, arguments: JsonObject): AssistantPreview {
        val connection = access.dedicatedTenantConnectionForCompany(companyCode) ?: return AssistantPreview(JsonArray(emptyList()), "No se pudo abrir la empresa.")
        try {
            val user = user(connection) ?: return AssistantPreview(JsonArray(emptyList()))
            val registered = access.moduleMcp().filter { it.moduleId != ServerMcpModuleId }.flatMap { it.tools }.firstOrNull { it.qualifiedName == name }
            val preview = registered?.tool?.preview ?: return AssistantPreview(JsonArray(emptyList()))
            val permissions = user.permissions(registered.moduleId)?.takeIf { it.has(registered.tool.permission) }
                ?: return AssistantPreview(JsonArray(emptyList()))
            // Resolved before the sandbox closes: it may open a connection of its own.
            val activity = actor().viaAssistant()
            connection.autoCommit = true
            connection.createStatement().use { it.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY") }
            connection.autoCommit = false
            connection.createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
            val call = McpToolCall(arguments, permissions.withActivityActor(activity), PreviewConnection.guard(connection), access, ActivityActor.AssistantAgentName, registered.moduleId)
            return AssistantPreview(JsonArray(access.withoutNewConnections { preview(call) }.map { AssistantTools.row(it.label, it.value) }))
        } catch (failure: McpToolException) {
            return AssistantPreview(JsonArray(emptyList()), failure.message.orEmpty())
        } catch (failure: Exception) {
            log.error("Assistant preview {} failed in company {}: {}", name, companyCode, failure.javaClass.name)
            return AssistantPreview(JsonArray(emptyList()), "No se pudo preparar la vista previa.")
        } finally {
            runCatching { connection.rollback() }
            runCatching { connection.close() }
        }
    }
}

/** The connection a preview works on: reads and rollbacks only. */
internal object PreviewConnection {
    private val refused = setOf("commit", "setAutoCommit", "setReadOnly", "close", "abort", "unwrap", "setTransactionIsolation", "releaseSavepoint", "setHoldability")

    fun guard(real: Connection): Connection = java.lang.reflect.Proxy.newProxyInstance(
        Connection::class.java.classLoader, arrayOf(Connection::class.java),
    ) { _, method, arguments ->
        if (method.name in refused) throw java.sql.SQLException("Una vista previa solo puede leer: no confirma ni cambia la transaccion.")
        try {
            method.invoke(real, *(arguments ?: emptyArray()))
        } catch (failure: java.lang.reflect.InvocationTargetException) {
            throw failure.targetException
        }
    } as Connection
}
