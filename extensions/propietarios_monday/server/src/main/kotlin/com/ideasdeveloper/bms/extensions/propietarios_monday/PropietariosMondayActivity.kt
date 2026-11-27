package com.ideasdeveloper.bms.extensions.propietarios_monday

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.sql.Connection
import java.util.UUID

/**
 * Lo que la extension propietarios_monday escribe en el registro de actividad de la empresa (ACTIVITY_LOG.md §4): el token de
 * monday (solo que cambio, nunca su valor), el inicio, la confirmacion y la cancelacion de una importacion, la limpieza de
 * importaciones antiguas y la fila de totales. Las filas de cada desarrollo, lote, titular, cliente y archivo las escribe el
 * modulo, a nombre de quien empezo la importacion. Los textos son castellano fijo (nunca `tr`). Cada fila va en la misma
 * transaccion que el cambio.
 */
internal object PropietariosMondayActivity {
    const val ModuleId = "propietarios"
    const val SettingsType = "settings"
    const val ImportType = "importacion"
    private const val Name = "Monday"

    // Quien figura por la limpieza y por las cancelaciones que hace la propia extension.
    private val CleanupActor: ActivityActor get() = ActivityActor.job("Limpieza de importaciones de monday")
    private val RulesActor: ActivityActor get() = ActivityActor.job("Importacion de monday")

    /** Una importacion antigua que la limpieza va a borrar. */
    class PurgedJob(val id: UUID, val board: String, val status: String)

    private fun write(tenant: Connection, actor: ActivityActor, type: String, id: UUID?, label: String, action: String, summary: String, changes: List<ActivityChange> = emptyList()) {
        ActivityLog.record(tenant, actor, ActivityEntry(ModuleId, type, id?.toString(), label, action, summary, changes))
    }

    /** El token de monday se guardo (nuevo o cambiado) o se borro: solo queda que cambio, el valor nunca. */
    fun token(tenant: Connection, actor: ActivityActor, summary: String) {
        write(tenant, actor, SettingsType, null, Name, ActivityActions.SettingsChanged, summary, listOf(ActivityChange.secret("mondayToken", "Token de monday")))
    }

    fun importQueued(tenant: Connection, actor: ActivityActor, jobId: UUID, board: String, newDevelopment: Boolean) {
        write(
            tenant, actor, ImportType, jobId, board, ActivityActions.Created, "Inicio la lectura del tablero de monday $board para importar lotes.",
            listOf(
                ActivityChange("board", "Tablero", null, board),
                ActivityChange("target", "Destino", null, if (newDevelopment) "Desarrollo nuevo" else "Desarrollo existente"),
                ActivityChange("status", "Estado", null, statusName(MondayJobStatus.Reading)),
            ),
        )
    }

    fun applyRequested(tenant: Connection, actor: ActivityActor, job: MondayJob) {
        write(
            tenant, actor, ImportType, job.id, job.boardName, ActivityActions.StatusChanged, "Confirmo la importacion del tablero de monday ${job.boardName} (${job.total} filas).",
            listOf(ActivityChange("status", "Estado", statusName(MondayJobStatus.Preview), statusName(MondayJobStatus.Applying)), ActivityChange("rows", "Filas", null, job.total.toString())),
        )
    }

    fun cancelled(tenant: Connection, actor: ActivityActor, job: MondayJob) {
        write(
            tenant, actor, ImportType, job.id, job.boardName, ActivityActions.StatusChanged, "Cancelo la importacion del tablero de monday ${job.boardName}.",
            listOf(ActivityChange("status", "Estado", statusName(job.status), statusName(MondayJobStatus.Cancelled)), ActivityChange("applied", "Filas ya procesadas", null, job.applied.toString())),
        )
    }

    /** La importacion se cancelo sola porque quien la empezo ya no puede importar. */
    fun cancelledByRules(tenant: Connection, job: MondayJob, reason: String) {
        write(
            tenant, RulesActor, ImportType, job.id, job.boardName, ActivityActions.StatusChanged, "Se cancelo la importacion del tablero de monday ${job.boardName}: $reason",
            listOf(ActivityChange("status", "Estado", statusName(job.status), statusName(MondayJobStatus.Cancelled)), ActivityChange("applied", "Filas ya procesadas", null, job.applied.toString())),
        )
    }

    /** Una importacion antigua que la limpieza borra: la fila se escribe antes del borrado. */
    fun purged(tenant: Connection, job: PurgedJob, retentionDays: Int) {
        write(
            tenant, CleanupActor, ImportType, job.id, job.board, ActivityActions.Deleted, "Borro la importacion del tablero de monday ${job.board} (mas de $retentionDays dias).",
            listOf(ActivityChange("board", "Tablero", job.board, null), ActivityChange("status", "Estado", statusName(job.status), null)),
        )
    }

    /** Una fila con los totales de la importacion, a nombre de [importer]; [outcome] explica una importacion que se detuvo. */
    fun total(tenant: Connection, importer: ActivityActor, job: MondayJob, outcome: String = "") {
        write(
            tenant, importer, ImportType, job.id, job.boardName, ActivityActions.Imported,
            "Importo ${job.created} lotes de monday desde ${job.boardName}.${if (outcome.isEmpty()) "" else " $outcome"}",
            listOf(
                ActivityChange("lots", "Lotes", null, "creados ${job.created}, actualizados ${job.updated}, omitidos ${job.skipped}, con error ${job.failed}"),
                ActivityChange("files", "Archivos", null, "importados ${job.filesDone}, con error ${job.filesFailed}"),
            ),
        )
    }

    /** Nombre del estado guardado, para las filas. */
    fun statusName(status: String): String = when (status) {
        MondayJobStatus.Reading -> "Leyendo"
        MondayJobStatus.Preview -> "Por revisar"
        MondayJobStatus.Applying -> "Aplicando"
        MondayJobStatus.Done -> "Terminada"
        MondayJobStatus.Failed -> "Con error"
        MondayJobStatus.Cancelled -> "Cancelada"
        else -> status
    }
}
