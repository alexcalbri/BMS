package com.ideasdeveloper.bms.extensions.membresia_monday

import com.ideasdeveloper.bms.modules.membresia.MembresiaModule
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.sql.Connection
import java.util.UUID

/**
 * Lo que la extension membresia_monday escribe en el registro de actividad de la empresa (ACTIVITY_LOG.md §4): el token de
 * monday (solo que cambio, nunca su valor), el inicio, la confirmacion y la cancelacion de una importacion y la limpieza
 * de importaciones antiguas. Las filas de cada registro importado y la fila de totales las escribe el modulo (con quien
 * empezo la importacion). Los textos son castellano fijo (nunca `tr`). Cada fila va en la misma transaccion que el cambio.
 */
internal object MembresiaMondayActivity {
    const val SettingsType = "settings"
    const val ImportJobType = "import_job"
    private const val Name = "Monday"

    // Quien figura por la limpieza que hace el trabajo periodico de la extension.
    private val CleanupActor: ActivityActor get() = ActivityActor.job("Limpieza de importaciones de monday")

    private fun write(tenant: Connection, actor: ActivityActor, type: String, id: UUID?, label: String, action: String, summary: String, changes: List<ActivityChange> = emptyList()) {
        ActivityLog.record(tenant, actor, ActivityEntry(MembresiaModule.Id, type, id?.toString(), label, action, summary, changes))
    }

    /** El token de monday se guardo (nuevo o cambiado): solo queda que cambio, el valor nunca. */
    fun tokenSaved(tenant: Connection, actor: ActivityActor, hadToken: Boolean) {
        write(
            tenant, actor, SettingsType, null, Name, ActivityActions.SettingsChanged,
            if (hadToken) "Cambia el token de la API de monday." else "Guarda el token de la API de monday.",
            listOf(ActivityChange.secret("token", "Token de monday")),
        )
    }

    fun tokenForgotten(tenant: Connection, actor: ActivityActor) {
        write(tenant, actor, SettingsType, null, Name, ActivityActions.SettingsChanged, "Borra el token de la API de monday.", listOf(ActivityChange.secret("token", "Token de monday")))
    }

    fun importQueued(tenant: Connection, actor: ActivityActor, jobId: UUID, board: String) {
        write(
            tenant, actor, ImportJobType, jobId, board, ActivityActions.Created, "Inicia la lectura del tablero de monday $board para importar membresías.",
            listOf(ActivityChange("board", "Tablero", null, board), ActivityChange("status", "Estado", null, "Leyendo")),
        )
    }

    fun applyRequested(tenant: Connection, actor: ActivityActor, jobId: UUID, board: String, rows: Int) {
        write(
            tenant, actor, ImportJobType, jobId, board, ActivityActions.StatusChanged, "Confirma la importación del tablero de monday $board ($rows filas).",
            listOf(ActivityChange("status", "Estado", "Por revisar", "Aplicando"), ActivityChange("rows", "Filas", null, rows.toString())),
        )
    }

    fun cancelled(tenant: Connection, actor: ActivityActor, jobId: UUID, board: String, from: String, applied: Int) {
        write(
            tenant, actor, ImportJobType, jobId, board, ActivityActions.StatusChanged, "Cancela la importación del tablero de monday $board.",
            listOf(ActivityChange("status", "Estado", from, "Cancelada"), ActivityChange("applied", "Filas ya procesadas", null, applied.toString())),
        )
    }

    /** Una importacion antigua que la limpieza borra: la fila se escribe antes del borrado. */
    fun purged(tenant: Connection, jobId: UUID, board: String, status: String, retentionDays: Int) {
        write(
            tenant, CleanupActor, ImportJobType, jobId, board, ActivityActions.Deleted,
            "Borra la importación del tablero de monday $board (más de $retentionDays días).",
            listOf(ActivityChange("board", "Tablero", board, null), ActivityChange("status", "Estado", status, null)),
        )
    }

    /** Nombre del estado guardado, para las filas. */
    fun statusName(status: String): String = when (status) {
        "reading" -> "Leyendo"
        "preview" -> "Por revisar"
        "applying" -> "Aplicando"
        "done" -> "Terminada"
        "failed" -> "Con error"
        "cancelled" -> "Cancelada"
        else -> status
    }
}
