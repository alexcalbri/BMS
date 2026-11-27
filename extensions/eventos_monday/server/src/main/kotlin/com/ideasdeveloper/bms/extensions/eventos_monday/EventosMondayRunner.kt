package com.ideasdeveloper.bms.extensions.eventos_monday

import com.ideasdeveloper.bms.modules.eventos.EventosImportApply
import com.ideasdeveloper.bms.modules.eventos.EventosImportJobContext
import com.ideasdeveloper.bms.modules.eventos.EventosRepository
import com.ideasdeveloper.bms.modules.eventos.ImportOutcome
import com.ideasdeveloper.bms.server.i18n.tr
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Avanza las importaciones: lee un tablero pagina por pagina (cursor guardado en el trabajo, hasta 20 000 elementos) y
 * aplica lotes de 100 filas hasta agotar la corrida. Lo llama el modulo Eventos desde su trabajo "import-runner" con un presupuesto de
 * tiempo estricto: no se empieza una llamada con menos de 5 s por delante y cada una se corta a lo que queda. Todo paso
 * es corto e idempotente: si se repite no duplica nada. Los fallos pasajeros de monday reintentan (hasta [MaxRetries]
 * seguidos); los permanentes y todo error de una fila quedan en el trabajo o en la fila y en el registro del servidor,
 * nunca se pierden en silencio.
 */
object EventosMondayRunner {
    const val BatchSize = 100
    const val MaxRetries = 5
    const val MaxCursorLength = 2000
    const val MaxPages = EventosMondayClient.MaxItems / EventosMondayClient.PageSize * 3
    private const val CancelCheckEvery = 20
    private const val SummaryBatch = 500
    private val log = LoggerFactory.getLogger(EventosMondayRunner::class.java)

    /** Devuelve las filas leidas o aplicadas en esta corrida. */
    fun run(context: EventosImportJobContext, budget: Duration): Int {
        val tenant = context.tenant
        if (!EventosMondaySettings.extensionEnabled(tenant)) return 0
        val deadline = System.nanoTime() + budget.toNanos()
        purgeOld(tenant)
        var processed = 0
        // Tras un fallo pasajero se espera 2, 4, 8... minutos (tope 30) antes de reintentar un trabajo.
        EventosMondayJobs.withStatus(tenant, MondayJobStatus.Reading).filter { EventosMondayReading.retryDue(it.retries, it.updatedAt) }.forEach { job ->
            if (EventosMondayClient.hasTime(deadline)) processed += guarded(tenant, job) { readPages(context, job, deadline) }
        }
        EventosMondayJobs.withStatus(tenant, MondayJobStatus.Applying).firstOrNull()?.takeIf { EventosMondayReading.retryDue(it.retries, it.updatedAt) }?.let { job ->
            if (EventosMondayClient.hasTime(deadline)) processed += guarded(tenant, job) { applyBatch(context, job, deadline) }
        }
        return processed
    }

    // Limpieza de trabajos viejos (terminados, fallidos o cancelados, mas de 30 dias): nunca debe impedir la corrida.
    private fun purgeOld(tenant: Connection) {
        runCatching { EventosMondayJobs.purgeOldRecorded(tenant) }
            .onSuccess { if (it > 0) log.info("Importaciones de monday viejas borradas: {}", it) }
            .onFailure { log.warn("No se pudieron borrar las importaciones viejas de monday ({})", it.javaClass.simpleName, it) }
    }

    // Un fallo pasajero de monday reintenta; el resto deja el trabajo en "failed" con un mensaje legible. Todo se registra.
    private fun guarded(tenant: Connection, job: MondayJob, block: () -> Int): Int {
        try {
            return block()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: EventosMondayException) {
            if (failure.transient) {
                log.warn("Importacion de monday {}: error temporal: {}", job.id, failure.message)
                retry(tenant, job.id, failure.message.orEmpty())
            } else {
                log.warn("Importacion de monday {} fallo: {}", job.id, failure.message)
                fail(tenant, job.id, failure.message.orEmpty())
            }
        } catch (failure: Exception) {
            log.error("Importacion de monday {} fallo", job.id, failure)
            fail(tenant, job.id, "No se pudo completar la importacion; revisa el registro del servidor.")
        }
        return 0
    }

    private fun retry(tenant: Connection, id: UUID, message: String) {
        runCatching {
            if (!tenant.autoCommit) tenant.rollback()
            val attempts = EventosMondayJobs.bumpRetries(tenant, id, "Error temporal de monday, se reintenta (intento de $MaxRetries): $message")
            if (attempts >= MaxRetries) fail(tenant, id, "monday no respondio despues de $MaxRetries intentos: $message")
        }.onFailure { log.error("No se pudo registrar el reintento de la importacion de monday {}", id, it) }
    }

    private fun fail(tenant: Connection, id: UUID, message: String) {
        runCatching {
            if (!tenant.autoCommit) tenant.rollback()
            val job = EventosMondayJobs.find(tenant, id)
            EventosMondayJobs.inTransaction(tenant) {
                val changed = EventosMondayJobs.changeStatus(tenant, id, MondayJobStatus.Failed, message, from = listOf(MondayJobStatus.Reading, MondayJobStatus.Applying))
                // Una importacion que ya habia aplicado filas y falla deja su fila de totales (las filas por evento ya estan).
                if (changed && job != null && job.applied > 0) recordTotal(tenant, job, "La importación se detuvo con un error: ${message.take(200)}")
            }
        }.onFailure { log.error("No se pudo marcar como fallida la importacion de monday {}", id, it) }
    }

    // ---- Lectura ----

    private fun readPages(context: EventosImportJobContext, job: MondayJob, deadline: Long): Int {
        val tenant = context.tenant
        val token = EventosMondaySettings.loadToken(context.access.secrets, tenant)
            ?: throw EventosMondayException(tr("Falta el token de monday o no se puede leer; guardalo de nuevo."))
        var cursor = job.cursor
        var pages = job.pages
        var read = 0
        // Cursor vacio: ya se leyo todo y solo falta el resumen de la vista previa.
        if (cursor != "") {
            do {
                if (EventosMondayJobs.statusOf(tenant, job.id) != MondayJobStatus.Reading) return read
                if (pages >= MaxPages) throw EventosMondayException(tr("La lectura del tablero no termina (demasiadas paginas); divide el tablero antes de importarlo."))
                // Solo la pagina de elementos se puede reducir ante complejidad o respuesta enorme; las personas, no.
                val page: MondayPage
                try {
                    page = EventosMondayClient.itemsPage(token, job.boardId, cursor, job.pageSize, deadline)
                    if (page.cursor.length > MaxCursorLength) throw EventosMondayException(tr("monday devolvio un cursor invalido; no se puede seguir leyendo el tablero."))
                    if (page.items.isEmpty() && page.cursor.isNotEmpty()) throw EventosMondayException(tr("monday devolvio una pagina vacia sin terminar la lectura del tablero."))
                    if (job.total + read + page.items.size > EventosMondayClient.MaxItems) {
                        throw EventosMondayException(tr("El tablero tiene mas de {0} elementos; divide el tablero antes de importarlo.", EventosMondayClient.MaxItems))
                    }
                } catch (failure: EventosMondayException) {
                    return recoverRead(tenant, job, failure, read, canShrink = true)
                }
                val people: Map<String, MondayPerson>
                try {
                    // Las personas de la pagina (responsables) se piden a monday una sola vez por trabajo (users(ids) trae el correo).
                    val known = EventosMondayJobs.people(tenant, job.id)
                    val missing = page.items.flatMap { EventosMondayMapping.personIds(it.cells) }.toSet() - known.keys
                    val fetched = if (missing.isEmpty()) emptyList() else EventosMondayClient.users(token, missing, deadline)
                    EventosMondayJobs.savePeople(tenant, job.id, fetched)
                    people = known + fetched.associateBy { it.id }
                } catch (failure: EventosMondayException) {
                    return recoverRead(tenant, job, failure, read, canShrink = false)
                }
                val rows = page.items.map { item ->
                    val event = EventosMondayMapping.toEvent(item, job.mapping, people, context.today)
                    item.id to MondayRowDto.encode(event)
                }
                if (!EventosMondayJobs.savePage(tenant, job.id, rows, page.cursor)) return read
                read += page.items.size
                pages++
                cursor = page.cursor
            } while (cursor != "" && EventosMondayClient.hasTime(deadline))
        }
        if (cursor == "") finishReading(tenant, job)
        return read
    }

    // Fallo de monday al leer: complejidad = pagina a la mitad (guardada); cursor vencido = releer desde cero (una vez);
    // pasajero = reintentar en la corrida siguiente; lo demas = falla. Devuelve las filas leidas antes del fallo.
    private fun recoverRead(tenant: Connection, job: MondayJob, failure: EventosMondayException, read: Int, canShrink: Boolean): Int {
        val current = EventosMondayJobs.find(tenant, job.id)?.takeIf { it.status == MondayJobStatus.Reading } ?: return read
        when (val step = EventosMondayReading.recover(failure.kind, failure.message.orEmpty(), current.retries, current.pageSize, current.restarts, canShrink)) {
            is EventosMondayReading.Step.Fail -> throw EventosMondayException(step.message)
            is EventosMondayReading.Step.Retry -> {
                log.warn("Importacion de monday {}: fallo de lectura ({}), intento {}, pagina {}", job.id, failure.kind, step.attempts, step.pageSize)
                val shrunk = step.pageSize < current.pageSize
                EventosMondayJobs.markRetry(
                    tenant, job.id, step.attempts, step.pageSize,
                    if (shrunk) "monday limito la lectura; se reintenta con paginas de ${step.pageSize} elementos."
                    else "monday limito la lectura; se reintenta en unos minutos (intento ${step.attempts} de ${EventosMondayReading.MaxAttempts}).",
                )
            }
            EventosMondayReading.Step.Restart -> {
                log.warn("Importacion de monday {}: el cursor vencio, se relee desde cero", job.id)
                EventosMondayJobs.restartRead(tenant, job.id)
            }
        }
        return read
    }

    /** Calcula el resumen de la vista previa una sola vez, por lotes, y pasa el trabajo a revision. */
    private fun finishReading(tenant: Connection, job: MondayJob) {
        val users = EventosRepository.users(tenant)
        val parts = mutableListOf<MondaySummaryDto>()
        var after = 0
        while (true) {
            val last = EventosMondayJobs.lastRowOfBatch(tenant, job.id, after, SummaryBatch) ?: break
            val events = EventosMondayJobs.eventBatch(tenant, job.id, after, SummaryBatch).map { it.second }
            val existing = EventosMondayJobs.existingCustomerEmails(tenant, events.map { it.clientEmail })
            parts += MondaySummaryDto.of(EventosImportApply.summary(events, users, existing))
            after = last
        }
        if (parts.isEmpty()) throw EventosMondayException(tr("El tablero no tiene elementos."))
        EventosMondayJobs.moveToPreview(tenant, job.id, MondaySummaryDto.merge(parts))
    }

    // ---- Aplicacion ----

    private fun applyBatch(context: EventosImportJobContext, job: MondayJob, deadline: Long): Int {
        val tenant = context.tenant
        val users = EventosRepository.users(tenant)
        // Quien inicio la importacion es el actor de cada fila del registro de actividad.
        val activity = EventosImportApply.importActor(tenant, job.createdBy)
        // Quien inicio la importacion queda como autor del historial; si ya no es usuario, "Monday".
        val author = users.firstOrNull { it.id == job.createdBy }?.name?.takeIf { it.isNotBlank() } ?: "Monday"
        var processed = 0
        var stopped = false
        // Lotes de 100 filas hasta agotar el presupuesto de tiempo; cada fila se guarda al instante (transaccion corta).
        while (!stopped && EventosMondayClient.hasTime(deadline)) {
            // Los permisos se revisan en vivo en cada lote: si quien pidio aplicar ya no puede importar, se cancela.
            if (!EventosMondayJobs.applierAuthorized(tenant, job)) {
                log.warn("Importacion de monday {} cancelada: quien pidio aplicarla ya no tiene permiso para importar", job.id)
                EventosMondayJobs.recount(tenant, job.id)
                EventosMondayJobs.inTransaction(tenant) {
                    val cancelled = EventosMondayJobs.changeStatus(
                        tenant, job.id, MondayJobStatus.Cancelled,
                        "Cancelada: quien pidio aplicar la importacion ya no tiene permiso para importar datos.", from = listOf(MondayJobStatus.Applying),
                    )
                    if (cancelled) EventosMondayJobs.find(tenant, job.id)?.takeIf { it.applied > 0 }?.let { recordTotal(tenant, it, "Se canceló porque quien pidió aplicarla ya no tiene permiso para importar.") }
                }
                return processed
            }
            val batch = EventosMondayJobs.pending(tenant, job.id, BatchSize)
            if (batch.isEmpty()) break
            for ((index, row) in batch.withIndex()) {
                if (index % CancelCheckEvery == 0 && EventosMondayJobs.statusOf(tenant, job.id) != MondayJobStatus.Applying) { stopped = true; break }
                if (!EventosMondayClient.hasTime(deadline)) { stopped = true; break }
                var outcome: String
                var message = ""
                var eventId: UUID? = null
                try {
                    val event = MondayRowDto.decode(row.payload)
                    when (val result = EventosImportApply.apply(context.access, tenant, event, users, author, context.today, activity)) {
                        is ImportOutcome.Created -> { outcome = "created"; eventId = result.eventId }
                        is ImportOutcome.Updated -> { outcome = "updated"; eventId = result.eventId }
                        is ImportOutcome.Skipped -> { outcome = "skipped"; message = result.reason }
                    }
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw failure
                } catch (failure: Exception) {
                    // apply aisla cada fila con su propio savepoint: aqui no se deshace el lote. Sin datos de la fila ni SQL en el registro.
                    log.warn("Importacion de monday {}: la fila {} fallo ({})", job.id, row.rowNo, failure.javaClass.simpleName)
                    outcome = "failed"
                    message = "Error al aplicar: " + failure.javaClass.simpleName
                }
                EventosMondayJobs.saveApplied(tenant, job.id, row.rowNo, outcome, message, eventId)
                processed++
            }
        }
        if (processed > 0) EventosMondayJobs.resetRetries(tenant, job.id)
        EventosMondayJobs.recount(tenant, job.id)
        if (EventosMondayJobs.statusOf(tenant, job.id) == MondayJobStatus.Applying && EventosMondayJobs.pending(tenant, job.id, 1).isEmpty()) finish(tenant, job.id)
        return processed
    }

    private fun finish(tenant: Connection, jobId: UUID) {
        val job = EventosMondayJobs.find(tenant, jobId) ?: return
        val message = "Importacion terminada: ${job.created} creados, ${job.updated} actualizados, ${job.skipped} omitidos y ${job.failed} con error."
        // La fila de totales (quien, origen y cifras) va en la misma transaccion que cierra el trabajo.
        EventosMondayJobs.inTransaction(tenant) {
            if (EventosMondayJobs.changeStatus(tenant, jobId, MondayJobStatus.Done, message, from = listOf(MondayJobStatus.Applying))) recordTotal(tenant, job, "")
        }
    }

    private fun recordTotal(tenant: Connection, job: MondayJob, outcome: String) {
        val activity = EventosImportApply.importActor(tenant, job.createdBy)
        EventosImportApply.recordImportTotal(tenant, activity, job.id, "Monday", job.boardName, job.created, job.updated, job.skipped, job.failed, outcome)
    }
}
