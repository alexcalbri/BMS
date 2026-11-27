package com.ideasdeveloper.bms.extensions.monday

import com.ideasdeveloper.bms.modules.crm.CrmAudit
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.outbound.monday.MondayOutboundConfig
import com.ideasdeveloper.bms.server.outbound.monday.MondayOutboundConfigStore
import java.sql.Connection

/**
 * Who changed the configuration of the sending to monday, for the company activity log. The store of the Core
 * ([MondayOutboundConfigStore]) knows nothing of people: the screen action and the MCP tools say who is acting for the
 * length of their call ([acting]) and [AuditedMondayConfigStore] records the change in the same transaction.
 */
object MondayAudit {
    private val current = ThreadLocal<ActivityActor?>()

    /** Runs [block] with [actor] as the one the configuration changes belong to. */
    fun <T> acting(actor: ActivityActor, block: () -> T): T {
        val previous = current.get()
        current.set(actor)
        return try {
            block()
        } finally {
            current.set(previous)
        }
    }

    /** Without an actor (a path that did not say) the row is the server's. */
    fun actor(): ActivityActor = current.get() ?: ActivityActor.system()

    /** The write tools of the sending to monday (Core's MondayOutboundMcp) with the agent as the actor of what they save. */
    fun guarded(tool: McpTool): McpTool = if (tool.readOnly) tool else McpTool(
        tool.name, tool.title, tool.description, tool.permission, tool.inputSchema, tool.readOnly, tool.destructive,
    ) { call -> acting(call.activityActor) { tool.handler(call) } }
}

/** [MondayOutboundConfigStore] that leaves a "settings changed" row (old -> new) with each configuration it saves. */
class AuditedMondayConfigStore(private val inner: MondayOutboundConfigStore) : MondayOutboundConfigStore by inner {
    private val labels = linkedMapOf(
        "enabled" to "Envio activado", "boardId" to "Tablero", "groupId" to "Grupo", "sendCreated" to "Enviar altas", "sendUpdated" to "Enviar cambios",
        "sendNotes" to "Enviar notas", "sendFiles" to "Enviar archivos", "sendImports" to "Enviar lo importado", "columns" to "Columnas",
        "filesColumn" to "Columna de archivos", "keyColumn" to "Columna clave", "maxFileMb" to "Tamano maximo de archivo (MB)", "phoneCountry" to "Pais del telefono",
    )

    private fun values(config: MondayOutboundConfig?) = mapOf(
        "enabled" to (config?.enabled ?: false), "boardId" to config?.boardId, "groupId" to config?.groupId, "sendCreated" to config?.sendCreated,
        "sendUpdated" to config?.sendUpdated, "sendNotes" to config?.sendNotes, "sendFiles" to config?.sendFiles, "sendImports" to config?.sendImports,
        "columns" to config?.columns?.entries?.sortedBy { it.key }?.joinToString("; ") { "${it.key} -> ${it.value.id}" }?.ifEmpty { null },
        "filesColumn" to config?.filesColumn, "keyColumn" to config?.keyColumn, "maxFileMb" to config?.maxFileMb, "phoneCountry" to config?.phoneCountry,
    )

    override fun save(tenant: Connection, config: MondayOutboundConfig) = CrmAudit.atomically(tenant) {
        val before = inner.get(tenant, config.recordType)
        inner.save(tenant, config)
        CrmAudit.settingsChanged(
            tenant, MondayAudit.actor(), "Envio a monday (${config.recordType})", "Cambio la configuracion del envio a monday de ${config.recordType}.",
            values(before), values(config), labels,
        )
    }
}
