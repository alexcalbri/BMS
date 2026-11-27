package com.ideasdeveloper.bms.extensions.compras_monday

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.sql.Connection
import java.util.UUID

/**
 * Lo que la extension compras_monday escribe en el registro de actividad de la empresa (ACTIVITY_LOG.md §4): el token de monday
 * (solo que cambio, nunca su valor), el inicio, la confirmacion y la cancelacion de una importacion, la limpieza de importaciones
 * antiguas y la fila de totales. Las filas de cada solicitud, cotizacion y archivo las escribe el modulo, a nombre de quien
 * confirmo la importacion. Los textos son castellano fijo (nunca `tr`). Cada fila va en la misma transaccion que el cambio.
 */
internal object ComprasMondayActivity {
    const val ModuleId = "compras"
    const val SettingsType = "settings"
    const val ImportType = "importacion"
    private const val Name = "Monday"

    // Quien figura por la limpieza que hace el trabajo periodico de la extension.
    private val CleanupActor: ActivityActor get() = ActivityActor.job("Limpieza de importaciones de monday")

    /** Una importacion antigua que la limpieza va a borrar. */
    class PurgedJob(val id: UUID, val board: String, val status: String, val created: Int, val existing: Int, val failed: Int)

    private fun write(tenant: Connection, actor: ActivityActor, id: UUID?, label: String, action: String, summary: String, changes: List<ActivityChange> = emptyList(), type: String = ImportType) {
        ActivityLog.record(tenant, actor, ActivityEntry(ModuleId, type, id?.toString(), label, action, summary, changes))
    }

    /** El token de monday se guardo (nuevo o cambiado) o se borro: solo queda que cambio, el valor nunca. */
    fun token(tenant: Connection, actor: ActivityActor, summary: String) {
        write(tenant, actor, null, Name, ActivityActions.SettingsChanged, summary, listOf(ActivityChange.secret("mondayToken", "Token de monday")), SettingsType)
    }

    fun importQueued(tenant: Connection, actor: ActivityActor, jobId: UUID, board: String, groups: Int) {
        write(
            tenant, actor, jobId, board, ActivityActions.Created, "Inicio la lectura del tablero de monday $board para importar solicitudes.",
            listOf(ActivityChange("board", "Tablero", null, board), ActivityChange("groups", "Grupos", null, groups.toString()), ActivityChange("status", "Estado", null, "Leyendo")),
        )
    }

    fun applyRequested(tenant: Connection, actor: ActivityActor, job: MondayJob) {
        write(
            tenant, actor, job.id, job.boardName, ActivityActions.StatusChanged, "Confirmo la importacion del tablero de monday ${job.boardName} (${job.readCount} elementos).",
            listOf(ActivityChange("status", "Estado", statusName("ready"), statusName("applying")), ActivityChange("rows", "Elementos", null, job.readCount.toString())),
        )
    }

    fun cancelled(tenant: Connection, actor: ActivityActor, job: MondayJob) {
        write(
            tenant, actor, job.id, job.boardName, ActivityActions.StatusChanged, "Cancelo la importacion del tablero de monday ${job.boardName}.",
            listOf(ActivityChange("status", "Estado", statusName(job.status), statusName("cancelled")), ActivityChange("created", "Solicitudes ya creadas", null, job.createdCount.toString())),
        )
    }

    /** Una importacion antigua que la limpieza borra: la fila se escribe antes del borrado. */
    fun purged(tenant: Connection, job: PurgedJob, retentionDays: Int) {
        write(
            tenant, CleanupActor, job.id, job.board, ActivityActions.Deleted, "Borro la importacion del tablero de monday ${job.board} (mas de $retentionDays dias).",
            listOf(ActivityChange("board", "Tablero", job.board, null), ActivityChange("status", "Estado", statusName(job.status), null)),
        )
    }

    /** Una fila con los totales de la importacion de monday, a nombre de [importer]; [outcome] explica una importacion que se detuvo. */
    fun total(tenant: Connection, importer: ActivityActor, jobId: UUID, board: String, created: Int, existing: Int, failed: Int, outcome: String = "") {
        write(
            tenant, importer, jobId, board, ActivityActions.Imported,
            "Importo $created solicitudes de monday desde $board.${if (outcome.isEmpty()) "" else " $outcome"}",
            listOf(ActivityChange("totals", "Totales", null, "creadas $created, ya existian $existing, con error $failed")),
            ImportType,
        )
    }

    /** Nombre del estado guardado, para las filas. */
    fun statusName(status: String): String = when (status) {
        "reading" -> "Leyendo"
        "ready" -> "Lista para importar"
        "applying" -> "Importando"
        "done" -> "Terminada"
        "failed" -> "Con error"
        "cancelled" -> "Cancelada"
        else -> status
    }
}
