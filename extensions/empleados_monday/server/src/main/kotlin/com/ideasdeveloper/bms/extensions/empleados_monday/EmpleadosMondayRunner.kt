package com.ideasdeveloper.bms.extensions.empleados_monday

import com.ideasdeveloper.bms.modules.empleados.EmpleadosImportApply
import com.ideasdeveloper.bms.modules.empleados.EmpleadosImportJobContext
import com.ideasdeveloper.bms.modules.empleados.EmpleadosImportOutcome
import com.ideasdeveloper.bms.server.i18n.tr
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Avanza las importaciones: lee un tablero pagina por pagina (cursor guardado en el trabajo, hasta 2 000 elementos) y
 * aplica lotes de 100 filas hasta agotar el tiempo de la corrida. Lo llama el modulo Empleados desde su trabajo "import-runner" con un presupuesto de tiempo
 * estricto: no se empieza una llamada con menos de 5 s por delante y cada una se corta a lo que queda. Todo paso es corto
 * e idempotente (la clave de cada empleado es "monday:<id del elemento>"): si se repite no duplica nada. Los fallos
 * pasajeros de monday reintentan (hasta [MaxRetries] seguidos); los permanentes y todo error de una fila quedan en el
 * trabajo o en la fila y en el registro del servidor, nunca se pierden en silencio.
 */
object EmpleadosMondayRunner {
    const val BatchSize = 100
    const val MaxRetries = 5
    const val MaxCursorLength = 2000
    const val MaxPages = EmpleadosMondayClient.MaxItems / EmpleadosMondayClient.PageSize * 3
    private const val CancelCheckEvery = 20
    private const val SummaryBatch = 500
    private val log = LoggerFactory.getLogger(EmpleadosMondayRunner::class.java)

    /** Estado de la lectura dentro de una corrida: el tamano de pagina vigente (se guarda en el trabajo al bajarlo) y las relecturas ya hechas. */
    private class ReadState(var pageSize: Int, val restarts: Int)

    /** Devuelve las filas leidas o aplicadas en esta corrida. */
    fun run(context: EmpleadosImportJobContext, budget: Duration): Int {
        val tenant = context.tenant
        if (!EmpleadosMondaySettings.extensionEnabled(tenant)) return 0
        val deadline = System.nanoTime() + budget.toNanos()
        // Una sola corrida a la vez por empresa: si otra tiene el bloqueo, esta no hace nada.
        if (!EmpleadosMondayJobs.tryLockRunner(tenant)) return 0
        try {
            var processed = 0
            runCatching { EmpleadosMondayJobs.purgeOld(tenant) }
                .onFailure { log.warn("No se pudieron borrar importaciones antiguas de monday", it) }
            EmpleadosMondayJobs.withStatus(tenant, MondayJobStatus.Reading).forEach { job ->
                if (EmpleadosMondayClient.hasTime(deadline) && MondayReading.retryReady(job.retries, job.updatedAt)) processed += guarded(tenant, job) { readPages(context, job, deadline) }
            }
            EmpleadosMondayJobs.withStatus(tenant, MondayJobStatus.Applying).firstOrNull()?.let { job ->
                // Lotes de [BatchSize] filas hasta agotar el tiempo de la pasada; un lote que no avanza (fallo, cancelacion, fin) corta el ciclo.
                while (EmpleadosMondayClient.hasTime(deadline) && MondayReading.retryReady(job.retries, job.updatedAt)) {
                    val done = guarded(tenant, job) { applyBatch(tenant, job, deadline) }
                    processed += done
                    if (done == 0) break
                }
            }
            return processed
        } finally {
            runCatching { if (!tenant.autoCommit) tenant.rollback() }
            runCatching { EmpleadosMondayJobs.unlockRunner(tenant) }
                .onFailure { log.warn("No se pudo liberar el bloqueo de la importacion de monday: {}", it.javaClass.simpleName) }
        }
    }

    /** Solo el tipo de fallo y su sqlState: el mensaje y la traza de una excepcion SQL pueden traer valores (datos personales). */
    private fun describe(failure: Throwable): String =
        failure.javaClass.simpleName + ((failure as? SQLException)?.sqlState?.let { " sqlState=$it" } ?: "")

    // Un fallo pasajero de monday reintenta; el resto deja el trabajo en "failed" con un mensaje legible. Todo se registra.
    private fun guarded(tenant: Connection, job: MondayJob, block: () -> Int): Int {
        try {
            return block()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: java.util.concurrent.CancellationException) {
            // En la JVM, kotlinx.coroutines.CancellationException es este mismo tipo.
            throw failure
        } catch (failure: EmpleadosMondayException) {
            if (failure.transient) {
                log.warn("Importacion de monday {}: error temporal: {}", job.id, failure.message)
                retry(tenant, job.id, failure.message.orEmpty())
            } else {
                log.warn("Importacion de monday {} fallo: {}", job.id, failure.message)
                fail(tenant, job.id, failure.message.orEmpty())
            }
        } catch (failure: Exception) {
            log.error("Importacion de monday {} fallo: {}", job.id, describe(failure))
            fail(tenant, job.id, "No se pudo completar la importacion; revisa el registro del servidor.")
        }
        return 0
    }

    private fun retry(tenant: Connection, id: UUID, message: String) {
        runCatching {
            if (!tenant.autoCommit) tenant.rollback()
            val attempts = EmpleadosMondayJobs.bumpRetries(tenant, id, "Error temporal de monday, se reintenta (intento de $MaxRetries): $message")
            if (attempts >= MaxRetries) fail(tenant, id, "monday no respondio despues de $MaxRetries intentos: $message")
        }.onFailure { log.error("No se pudo registrar el reintento de la importacion de monday {}", id, it) }
    }

    private fun fail(tenant: Connection, id: UUID, message: String) {
        runCatching {
            if (!tenant.autoCommit) tenant.rollback()
            EmpleadosMondayJobs.changeStatus(tenant, id, MondayJobStatus.Failed, message, from = listOf(MondayJobStatus.Reading, MondayJobStatus.Applying))
        }.onFailure { log.error("No se pudo marcar como fallida la importacion de monday {}", id, it) }
    }

    // ---- Lectura ----

    private fun readPages(context: EmpleadosImportJobContext, job: MondayJob, deadline: Long): Int {
        val tenant = context.tenant
        val token = EmpleadosMondaySettings.loadToken(context.access.secrets, tenant)
            ?: throw EmpleadosMondayException(tr("Falta el token de monday o no se puede leer; guardalo de nuevo."))
        var cursor = job.cursor
        var pages = job.pages
        var read = 0
        val state = ReadState(job.pageSize, job.restarts)
        // Cursor vacio: ya se leyo todo y solo falta el resumen de la vista previa.
        if (cursor != "") {
            do {
                if (EmpleadosMondayJobs.statusOf(tenant, job.id) != MondayJobStatus.Reading) return read
                if (pages >= MaxPages) throw EmpleadosMondayException(tr("La lectura del tablero no termina (demasiadas paginas); divide el tablero antes de importarlo."))
                val page = fetchPage(tenant, token, job, cursor, state, deadline)
                    ?: return read // Cursor vencido: se borro lo leido y la proxima corrida relee desde el inicio.
                if (page.cursor.length > MaxCursorLength) throw EmpleadosMondayException(tr("monday devolvio un cursor invalido; no se puede seguir leyendo el tablero."))
                if (page.items.isEmpty() && page.cursor.isNotEmpty()) throw EmpleadosMondayException(tr("monday devolvio una pagina vacia sin terminar la lectura del tablero."))
                if (job.total + read + page.items.size > EmpleadosMondayClient.MaxItems) {
                    throw EmpleadosMondayException(tr("El tablero tiene mas de {0} elementos; divide el tablero antes de importarlo.", EmpleadosMondayClient.MaxItems))
                }
                val rows = page.items.map { item -> item.id to MondayRowDto.encode(EmpleadosMondayMapping.toEmployee(item, job.mapping)) }
                if (!EmpleadosMondayJobs.savePage(tenant, job.id, rows, page.cursor)) return read
                read += page.items.size
                pages++
                cursor = page.cursor
            } while (cursor != "" && EmpleadosMondayClient.hasTime(deadline))
        }
        if (cursor == "") finishReading(context, job)
        return read
    }

    /**
     * Pide una pagina. Ante complejidad o una respuesta de mas de 5 MB baja la pagina a la mitad (minimo
     * [MondayReading.MinPageSize]), la guarda en el trabajo y reintenta; en el minimo falla con un mensaje claro. Bajar la
     * pagina no cuenta como intento. Cursor vencido: relee desde cero ([MondayReading.recover] decide si ya se hizo una vez).
     * Los fallos pasajeros suben al trabajo, que espera mas con cada uno.
     */
    private fun fetchPage(tenant: Connection, token: String, job: MondayJob, cursor: String?, state: ReadState, deadline: Long): MondayPage? {
        while (true) {
            try {
                return EmpleadosMondayClient.itemsPage(token, job.boardId, cursor, deadline, state.pageSize)
            } catch (failure: EmpleadosMondayException) {
                val message = failure.message.orEmpty()
                when (failure.kind) {
                    MondayErrorKind.Permanent, MondayErrorKind.Transient -> throw failure
                    MondayErrorKind.CursorExpired -> when (val step = MondayReading.recover(failure.kind, message, 0, state.pageSize, state.restarts)) {
                        is MondayReading.Step.Restart -> {
                            EmpleadosMondayJobs.restartReading(tenant, job.id)
                            return null
                        }
                        is MondayReading.Step.Fail -> throw EmpleadosMondayException(step.message)
                        is MondayReading.Step.Retry -> throw failure
                    }
                    MondayErrorKind.Complexity, MondayErrorKind.TooLarge -> when (val step = MondayReading.recover(failure.kind, message, 0, state.pageSize, state.restarts)) {
                        is MondayReading.Step.Retry -> {
                            log.warn("Importacion de monday {}: pagina de {} a {} elementos", job.id, state.pageSize, step.pageSize)
                            state.pageSize = step.pageSize
                            EmpleadosMondayJobs.savePageSize(tenant, job.id, step.pageSize)
                            if (!EmpleadosMondayClient.hasTime(deadline)) throw EmpleadosMondayException(tr("monday pidio una pagina mas chica; se sigue en la proxima corrida."), transient = true)
                        }
                        is MondayReading.Step.Fail -> throw EmpleadosMondayException(step.message)
                        is MondayReading.Step.Restart -> throw failure
                    }
                }
            }
        }
    }

    /** Calcula el resumen de la vista previa una sola vez, por lotes, y pasa el trabajo a revision. */
    private fun finishReading(context: EmpleadosImportJobContext, job: MondayJob) {
        val tenant = context.tenant
        val emails = EmpleadosImportApply.userEmails(tenant).keys
        val parts = mutableListOf<MondaySummaryDto>()
        var after = 0
        while (true) {
            val last = EmpleadosMondayJobs.lastRowOfBatch(tenant, job.id, after, SummaryBatch) ?: break
            val rows = EmpleadosMondayJobs.rowBatch(tenant, job.id, after, SummaryBatch)
            parts += MondaySummaryDto.of(EmpleadosImportApply.summary(rows.map { it.toEmployee() }, emails), rows.map { it.problem }.filter { it.isNotEmpty() })
            after = last
        }
        if (parts.isEmpty()) throw EmpleadosMondayException(tr("El tablero no tiene elementos."))
        EmpleadosMondayJobs.moveToPreview(tenant, job.id, MondaySummaryDto.merge(parts))
    }

    // ---- Aplicacion ----

    private fun applyBatch(tenant: Connection, job: MondayJob, deadline: Long): Int {
        // Una consulta por corrida: correo (en minusculas) -> usuario activo, para enlazar fichas por correo.
        val users = EmpleadosImportApply.userEmails(tenant)
        val batch = EmpleadosMondayJobs.pending(tenant, job.id, BatchSize)
        var processed = 0
        for ((index, row) in batch.withIndex()) {
            if (index % CancelCheckEvery == 0 && EmpleadosMondayJobs.statusOf(tenant, job.id) != MondayJobStatus.Applying) break
            if (!EmpleadosMondayClient.hasTime(deadline)) break
            var outcome: String
            var message = ""
            try {
                val dto = MondayRowDto.decode(row.payload)
                when (val result = EmpleadosImportApply.apply(tenant, dto.toEmployee(), users, job.createdBy)) {
                    is EmpleadosImportOutcome.Created -> { outcome = "created"; message = dto.problem }
                    is EmpleadosImportOutcome.Updated -> { outcome = "updated"; message = dto.problem }
                    is EmpleadosImportOutcome.Skipped -> { outcome = "skipped"; message = result.reason }
                }
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            } catch (failure: java.util.concurrent.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                runCatching { if (!tenant.autoCommit) tenant.rollback() }
                log.warn("Importacion de monday {}: la fila {} fallo: {}", job.id, row.rowNo, describe(failure))
                outcome = "failed"
                message = "No se pudo importar esta fila (${describe(failure)}); revisa el registro del servidor."
            }
            EmpleadosMondayJobs.saveApplied(tenant, job.id, row.rowNo, outcome, message)
            processed++
        }
        if (processed > 0) EmpleadosMondayJobs.resetRetries(tenant, job.id)
        EmpleadosMondayJobs.recount(tenant, job.id)
        if (EmpleadosMondayJobs.statusOf(tenant, job.id) == MondayJobStatus.Applying && EmpleadosMondayJobs.pending(tenant, job.id, 1).isEmpty()) finish(tenant, job.id)
        return processed
    }

    private fun finish(tenant: Connection, jobId: UUID) {
        val job = EmpleadosMondayJobs.find(tenant, jobId) ?: return
        val message = "Importacion terminada: ${job.created} creados, ${job.updated} actualizados, ${job.skipped} omitidos y ${job.failed} con error."
        EmpleadosMondayJobs.changeStatus(tenant, jobId, MondayJobStatus.Done, message, from = listOf(MondayJobStatus.Applying))
    }
}
