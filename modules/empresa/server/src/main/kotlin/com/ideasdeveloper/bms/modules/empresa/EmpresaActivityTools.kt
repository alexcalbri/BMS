package com.ideasdeveloper.bms.modules.empresa

import com.ideasdeveloper.bms.ActivityLogPage
import com.ideasdeveloper.bms.ActivityLogRow
import com.ideasdeveloper.bms.ActivityPermissionIds
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityFilter
import com.ideasdeveloper.bms.server.activity.ActivityFilterException
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.activity.ActivityLogReader
import com.ideasdeveloper.bms.server.activity.CoreModuleId
import com.ideasdeveloper.bms.server.modules.McpSchema
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.McpToolException
import com.ideasdeveloper.bms.server.modules.ModuleMcp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Agent tools that read the company activity log (ACTIVITY_LOG.md §7): for the agent of the
 * business owner or of a user holding empresa.activity.view. Read only; every call leaves one row
 * in the log with the filters used, so reading through an agent is itself audited.
 */
object EmpresaActivityTools {
    private val json = Json { encodeDefaults = true }

    val mcp: ModuleMcp
        get() = ModuleMcp(Instructions, tools)

    private const val Instructions =
        "El registro de actividad dice quien hizo cada cambio en la empresa, cuando y en que registro (empresa_list_activity). " +
            "Es de solo lectura y cada consulta tuya queda registrada en el mismo registro. Las filas guardan el texto en espanol tal como se escribio; " +
            "\"Agente de ...\" en actor_display indica que el cambio lo hizo un agente de IA en nombre de esa persona, y \"(desde el servidor)\" " +
            "que lo hizo el dueno o un administrador del servidor entrando a la empresa."

    private val filters = mapOf(
        "user" to McpSchema.string("Id de usuario, o texto que coincide con su nombre o usuario."),
        "module" to McpSchema.string("Id del modulo (core para la administracion de la empresa)."),
        "entity_type" to McpSchema.string("Tipo de registro (p. ej. user, customer, lead)."),
        "entity_id" to McpSchema.string("Id del registro."),
        "action" to McpSchema.string("Accion: created, updated, deleted, settings_changed, signed_in, sign_in_failed, permission_changed..."),
        "text" to McpSchema.string("Texto que aparece en el resumen, el registro o quien lo hizo."),
        "from" to McpSchema.date("Desde este dia (incluido)."),
        "to" to McpSchema.date("Hasta este dia (incluido)."),
        "page" to McpSchema.integer("Pagina, desde 0.", 0, 1_000),
        "page_size" to McpSchema.integer("Filas por pagina (50 por defecto).", 1, ActivityLogReader.MaxPageSize),
    )

    private val tools = listOf(
        McpTool(
            name = "list_activity",
            title = "Registro de actividad",
            description = "Lista el registro de actividad de la empresa, lo mas reciente primero: quien (actor_display), modulo, registro, accion, " +
                "resumen y cambios campo a campo. Filtra por usuario, modulo, tipo e id de registro, accion, texto y fechas.",
            permission = ActivityPermissionIds.View,
            readOnly = true,
            inputSchema = McpSchema.obj(filters),
        ) { call -> list(call) },
        McpTool(
            name = "get_activity_entry",
            title = "Detalle de actividad",
            description = "Una fila del registro de actividad por su id (empresa_list_activity), con todos sus cambios.",
            permission = ActivityPermissionIds.View,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.integer("Id de la fila.", 1)), required = listOf("id")),
        ) { call -> entry(call) },
    )

    /** The filter an agent sent, with the same rules as the routes. */
    fun filterOf(call: McpToolCall): ActivityFilter = try {
        ActivityFilter(
            user = call.string("user", 200).ifEmpty { null },
            module = call.string("module", 63).ifEmpty { null },
            entityType = call.string("entity_type", 100).ifEmpty { null },
            entityId = call.string("entity_id", 200).ifEmpty { null },
            action = call.string("action", 64).ifEmpty { null },
            text = call.string("text", 200).ifEmpty { null },
            from = ActivityFilter.day(call.string("from", 10)),
            to = ActivityFilter.day(call.string("to", 10)),
            page = call.int("page", 0, 0..1_000),
            pageSize = call.int("page_size", ActivityLogReader.DefaultPageSize, 1..ActivityLogReader.MaxPageSize),
        )
    } catch (failure: ActivityFilterException) {
        throw McpToolException(failure.message.orEmpty())
    }

    private fun list(call: McpToolCall): JsonElement {
        val filter = filterOf(call)
        val page = ActivityLogReader.page(call.tenant, filter, call.access::activityModuleName)
        logRead(call, "Consulto el registro de actividad" + described(filter) + ".")
        return json.encodeToJsonElement(ActivityLogPage.serializer(), page)
    }

    private fun entry(call: McpToolCall): JsonElement {
        val id = (call.arguments["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()
            ?: throw McpToolException("El argumento 'id' debe ser un numero.")
        val row = ActivityLogReader.entry(call.tenant, id, call.access::activityModuleName)
            ?: throw McpToolException("No existe la fila $id del registro de actividad.")
        logRead(call, "Consulto la fila $id del registro de actividad.")
        return json.encodeToJsonElement(ActivityLogRow.serializer(), row)
    }

    // One row per call, in the same company, as the agent's user (ACTIVITY_LOG.md §7).
    private fun logRead(call: McpToolCall, summary: String) {
        ActivityLog.record(
            call.tenant, call.activityActor,
            ActivityEntry(CoreModuleId, "activity_log", null, "Registro de actividad", ActivityActions.Viewed, summary),
        )
    }

    private fun described(filter: ActivityFilter): String {
        val used = filter.describe()
        return ": " + used.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }
}
