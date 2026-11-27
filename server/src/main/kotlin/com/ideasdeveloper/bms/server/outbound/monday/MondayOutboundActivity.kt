package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.sql.Connection

/**
 * What the sending to monday writes in the company activity log (ACTIVITY_LOG.md), shared by its MCP tools
 * ([MondayOutboundMcp]) and the screens of the module that mounts it: the configuration of a record type (old -> new, the
 * token is not part of it and any secret-looking field would be stored only as "changed"), a failed notice put back in the
 * queue, and one discarded. Each row is written in the same transaction as the change.
 */
object MondayOutboundActivity {
    const val SettingsType = "settings"
    const val NoticeType = "outbound"

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

    /** Saves [config] in [sink] and leaves a "settings changed" row (only when something changed) in the same transaction. */
    fun saveConfig(sink: MondayOutboundSink, tenant: Connection, actor: ActivityActor, config: MondayOutboundConfig) =
        ActivityLog.atomically(tenant) {
            val before = sink.config.get(tenant, config.recordType)
            sink.config.save(tenant, config)
            val changes = ActivityChanges.diff(values(before), values(config), labels)
            if (changes.isNotEmpty()) {
                val area = "Envio a monday (${config.recordType})"
                ActivityLog.record(
                    tenant, actor,
                    ActivityEntry(sink.moduleId, SettingsType, area, area, ActivityActions.SettingsChanged, "Cambio la configuracion del envio a monday de ${config.recordType}.", changes),
                )
            }
        }

    /** Puts one failed notice (or, without [id], all of them) back in the queue; how many were put back. One row when any. */
    fun retry(sink: MondayOutboundSink, view: MondayQueueView, tenant: Connection, actor: ActivityActor, id: Long?): Int =
        ActivityLog.atomically(tenant) {
            val count = if (id == null) view.retryFailed(tenant) else if (view.retry(tenant, id)) 1 else 0
            if (count > 0) {
                val summary = if (id == null) "Reintento $count envio(s) fallido(s) a monday." else "Reintento el envio a monday $id."
                ActivityLog.record(
                    tenant, actor,
                    ActivityEntry(
                        sink.moduleId, NoticeType, id?.toString(), id?.let { "Envio $it" }.orEmpty(), ActivityActions.StatusChanged, summary,
                        listOf(ActivityChange("status", "Estado", "Fallido", "Pendiente")) + if (id == null) listOf(ActivityChange("count", "Envios", null, count.toString())) else emptyList(),
                    ),
                )
            }
            count
        }

    /** Discards one failed notice (it is never sent) and leaves its row; false when there is none. */
    fun discard(sink: MondayOutboundSink, view: MondayQueueView, tenant: Connection, actor: ActivityActor, id: Long): Boolean =
        ActivityLog.atomically(tenant) {
            val done = view.discard(tenant, id)
            if (done) {
                ActivityLog.record(
                    tenant, actor,
                    ActivityEntry(
                        sink.moduleId, NoticeType, id.toString(), "Envio $id", ActivityActions.StatusChanged, "Descarto el envio a monday $id: no se enviara.",
                        listOf(ActivityChange("status", "Estado", "Fallido", "Descartado")),
                    ),
                )
            }
            done
        }
}
