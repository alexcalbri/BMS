package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ServerMcpModuleId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * What the assistant adds to ONE call of a tool, on top of what the tool declares (read only, destructive, outbound): the
 * toolbox asks for it by tool name and arguments before the engine decides to run the call or to show a card.
 *  - [confirm]: a call that would run by itself (a read) waits for the user's card instead, exactly like a write
 *    (same pending-call machinery, same approval);
 *  - [outbound], [destructive]: the card of this call carries the flag (the tool-level flag is OR-ed with it);
 *  - [rows]: label/value rows the card shows first (why the call waits).
 * Never applied to the MCP agents: it lives in the assistant's toolbox only.
 */
class AssistantCallPolicy(
    val confirm: Boolean = false,
    val outbound: Boolean = false,
    val destructive: Boolean = false,
    val rows: List<Pair<String, String>> = emptyList(),
) {
    companion object {
        val None = AssistantCallPolicy()
    }
}

/** The hook of a toolbox: the policy of the call [tool] (qualified name) with [arguments]. */
fun interface AssistantCallPolicyHook {
    fun evaluate(tool: String, arguments: JsonObject): AssistantCallPolicy

    companion object {
        val None = AssistantCallPolicyHook { _, _ -> AssistantCallPolicy.None }
    }
}

/**
 * The policy of the server workspace (GLOBAL_AGENT.md, "Server workspace policy"), keyed by tool name and arguments:
 *  1. servidor_list_company_activity and servidor_list_extensions read the data of a company that may never have opted in to
 *     the assistant: they run by themselves only for a company where the server allowance is `allowed` AND the company's own
 *     switch is on ([optedIn]); for any other company, and for "all companies" (no company argument), the call waits for a
 *     card that says so and is flagged outbound (the data goes to the AI provider);
 *  2. servidor_update_company and servidor_create_company with a logo_url: every client of the company loads that URL, so the
 *     call is outbound;
 *  3. servidor_apply_extension_to_group with operation apply (the default) and confirm=true: outbound;
 *  4. servidor_set_company_active with active=false (also when it is missing or unreadable, which the tool takes as false): destructive.
 */
class ServerCallPolicy(
    private val optedIn: (String) -> Boolean,
    private val companyName: (String) -> String?,
) : AssistantCallPolicyHook {
    override fun evaluate(tool: String, arguments: JsonObject): AssistantCallPolicy = when (tool) {
        qualified(ActivityTool) -> companyRead(arguments, "company") { company ->
            if (company == null) tr("Lee la actividad de todas las empresas a tu alcance y la envia al proveedor de IA.")
            else tr("Lee la actividad de {0} (no ha activado el asistente) y la envia al proveedor de IA.", label(company))
        }
        qualified(ExtensionsTool) -> companyRead(arguments, "code") { company ->
            if (company == null) tr("Lee las extensiones y los ajustes de todas las empresas y los envia al proveedor de IA.")
            else tr("Lee las extensiones y los ajustes de {0} (no ha activado el asistente) y los envia al proveedor de IA.", label(company))
        }
        qualified(UpdateCompanyTool), qualified(CreateCompanyTool) ->
            if (arguments.containsKey("logo_url")) AssistantCallPolicy(outbound = true) else AssistantCallPolicy.None
        qualified(ApplyToGroupTool) -> {
            val operation = text(arguments, "operation")?.takeIf { it.isNotEmpty() } ?: "apply"
            if (flag(arguments, "confirm") && !operation.equals("uninstall", ignoreCase = true)) AssistantCallPolicy(outbound = true) else AssistantCallPolicy.None
        }
        qualified(SetActiveTool) -> if (flag(arguments, "active")) AssistantCallPolicy.None else AssistantCallPolicy(destructive = true)
        else -> AssistantCallPolicy.None
    }

    private fun companyRead(arguments: JsonObject, key: String, says: (String?) -> String): AssistantCallPolicy {
        // The same reading the tools do (McpToolCall.string): trimmed, at most 63 characters; anything else counts as "no company".
        val company = text(arguments, key)?.take(63)?.takeIf { it.isNotEmpty() }
        if (company != null && optedIn(company)) return AssistantCallPolicy.None
        return AssistantCallPolicy(confirm = true, outbound = true, rows = listOf(tr("Que pasa") to says(company)))
    }

    private fun label(code: String) = companyName(code)?.let { "$it ($code)" } ?: code

    companion object {
        const val ActivityTool = "list_company_activity"
        const val ExtensionsTool = "list_extensions"
        const val UpdateCompanyTool = "update_company"
        const val CreateCompanyTool = "create_company"
        const val ApplyToGroupTool = "apply_extension_to_group"
        const val SetActiveTool = "set_company_active"

        /** The read tools the policy may hold back, and the write tools whose card it may flag (checked against the registry in ToolRegistryContractTest). */
        val HeldReads = listOf(ActivityTool, ExtensionsTool)
        val FlaggedWrites = listOf(UpdateCompanyTool, CreateCompanyTool, ApplyToGroupTool, SetActiveTool)

        private fun qualified(name: String) = "${ServerMcpModuleId}_$name"

        private fun text(arguments: JsonObject, key: String): String? = (arguments[key] as? JsonPrimitive)?.contentOrNull?.trim()

        // As McpToolCall.boolean: a boolean, or the text "true"/"false"; anything else is false.
        private fun flag(arguments: JsonObject, key: String): Boolean =
            (arguments[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() } ?: false
    }
}
