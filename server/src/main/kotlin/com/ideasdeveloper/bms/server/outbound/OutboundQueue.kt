package com.ideasdeveloper.bms.server.outbound

import com.ideasdeveloper.bms.server.modules.ModuleFiles
import com.ideasdeveloper.bms.server.modules.ModuleJob
import com.ideasdeveloper.bms.server.modules.ModuleJobContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.Savepoint
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Cola de salida de cada empresa (tablas outbound_events y outbound_links, migracion tenant V009).
 * Ver OutboundContract.kt para el reparto entre modulo, extension y cola.
 */
object OutboundQueue {
    /** Intentos antes de dejar un aviso como fallido. */
    const val MaxAttempts = 8

    private val log = LoggerFactory.getLogger(OutboundQueue::class.java)
    private val MaxBackoff: Duration = Duration.ofHours(6)
    private val BaseBackoff: Duration = Duration.ofMinutes(1)

    // La subida de un archivo grande puede durar 15 min: un bloqueo se da por muerto pasados 30.
    private val PurgeFailedAfter: Duration = Duration.ofDays(90)
    private val StaleLock: Duration = Duration.ofMinutes(30)
    private val RetentionPeriod: Duration = Duration.ofDays(30)
    private val PurgeEvery: Duration = Duration.ofHours(24)

    // Un aviso que sigue limitado por el servicio tras tantos dias se deja como fallido.
    private val MaxThrottledAge: Duration = Duration.ofDays(7)
    private const val BatchSize = 25
    private const val MaxReasonLength = 200

    // Ultima purga por base de empresa (en memoria: tras un reinicio se purga una vez mas, sin dano).
    private val lastPurge = ConcurrentHashMap<String, Instant>()

    // "No antes de" por (base de empresa, servicio) tras un Throttled: no se reclama de ese servicio hasta entonces.
    private val throttledUntil = ConcurrentHashMap<String, Instant>()

    /** Enlaces de registros con elementos de fuera, sobre la tabla outbound_links. */
    val links: OutboundLinks = JdbcOutboundLinks

    /** Espera antes del intento siguiente tras [attempts] intentos gastados: 1 min * 2^(attempts-1), con tope de 6 h. */
    fun backoff(attempts: Int): Duration {
        val shift = (attempts - 1).coerceIn(0, 20)
        val wait = BaseBackoff.multipliedBy(1L shl shift)
        return if (wait > MaxBackoff) MaxBackoff else wait
    }

    /**
     * Avisa de un cambio. NUNCA lanza ni llama a la red: solo escribe en la cola de la empresa. Dentro de una
     * transaccion ajena usa un SAVEPOINT por paso para que un fallo no aborte la operacion del usuario. Si un
     * servicio no se puede encolar, intenta dejar una fila fallida visible; si tambien falla, solo queda el log.
     */
    fun publish(tenant: Connection, sinks: Collection<OutboundSink>, event: OutboundEvent) =
        publish(JdbcOutboundStore, tenant, sinks, event, Instant.now())

    fun publish(store: OutboundStore, tenant: Connection, sinks: Collection<OutboundSink>, event: OutboundEvent, now: Instant = Instant.now()) {
        try {
            val candidates = sinks.filter { it.moduleId == event.moduleId }.distinctBy { it.sinkId }
            if (candidates.isEmpty()) return
            val clean = event.copy(origin = event.origin.ifBlank { OutboundOrigin.User }.take(63))
            // Via rapida dentro de una transaccion ajena: UN savepoint para todo el aviso. Si algo falla se deshace
            // entero y se reintenta paso a paso (un savepoint por servicio) para aislar al que falla.
            if (!tenant.autoCommit) {
                val failure = guarded(tenant) { enqueueAll(store, tenant, candidates, clean, now) }
                if (failure == null) return
                log.debug("El aviso de salida fallo en bloque; se reintenta servicio por servicio", failure)
            }
            enqueueOneByOne(store, tenant, candidates, clean, now)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Exception) {
            log.warn("No se pudo publicar un aviso de salida del modulo {}", event.moduleId, failure)
        }
    }

    // Encola para todos los servicios activos que lo quieren; lanza al primer fallo.
    private fun enqueueAll(store: OutboundStore, tenant: Connection, candidates: List<OutboundSink>, event: OutboundEvent, now: Instant) {
        val enabled = store.enabledSinks(tenant, candidates.map { it.sinkId })
        for (sink in candidates) {
            if (sink.sinkId in enabled && sink.wants(tenant, event)) store.enqueue(tenant, sink.sinkId, event, now)
        }
    }

    // Paso a paso: un fallo de un servicio no impide a los demas y deja una fila fallida visible para ese.
    private fun enqueueOneByOne(store: OutboundStore, tenant: Connection, candidates: List<OutboundSink>, event: OutboundEvent, now: Instant) {
        var enabled: Set<String> = emptySet()
        val listing = guarded(tenant) { enabled = store.enabledSinks(tenant, candidates.map { it.sinkId }) }
        if (listing != null) {
            log.warn("No se pudo leer las extensiones activas para un aviso de salida del modulo {}", event.moduleId, listing)
            return
        }
        for (sink in candidates) {
            if (sink.sinkId !in enabled) continue
            // wants y el insert comparten savepoint: si wants falla en la base, la transaccion queda sana.
            val failure = guarded(tenant) {
                if (sink.wants(tenant, event)) store.enqueue(tenant, sink.sinkId, event, now)
            } ?: continue
            log.warn(
                "No se pudo encolar el aviso de salida para {} (modulo {}, {} {})",
                sink.sinkId, event.moduleId, event.recordType, event.recordId, failure,
            )
            val reason = "No se pudo poner en cola (${failure.javaClass.simpleName})"
            guarded(tenant) { store.enqueueFailed(tenant, sink.sinkId, event, now, reason) }?.let { second ->
                log.warn("Tampoco se pudo dejar el aviso fallido de {} (modulo {})", sink.sinkId, event.moduleId, second)
            }
        }
    }

    /**
     * De [sinks], los que tienen la extension activa en esta empresa (una consulta). Nunca lanza: ante un error avisa
     * en el log y devuelve una lista vacia. Un modulo lo resuelve una vez por importacion y llama a publish solo
     * con esos.
     */
    fun enabledSinks(tenant: Connection, sinks: Collection<OutboundSink>): List<OutboundSink> =
        enabledSinks(JdbcOutboundStore, tenant, sinks)

    fun enabledSinks(store: OutboundStore, tenant: Connection, sinks: Collection<OutboundSink>): List<OutboundSink> {
        if (sinks.isEmpty()) return emptyList()
        var enabled: Set<String> = emptySet()
        val failure = guarded(tenant) { enabled = store.enabledSinks(tenant, sinks.map { it.sinkId }) }
        if (failure != null) {
            log.warn("No se pudo leer las extensiones activas de la salida", failure)
            return emptyList()
        }
        return sinks.filter { it.sinkId in enabled }
    }

    // Corre [action] dentro de un savepoint si la transaccion es ajena (autoCommit apagado). Devuelve el fallo, o null.
    private fun guarded(tenant: Connection, action: () -> Unit): Exception? {
        var savepoint: Savepoint? = null
        return try {
            if (!tenant.autoCommit) savepoint = tenant.setSavepoint()
            action()
            savepoint?.let { tenant.releaseSavepoint(it) }
            null
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (savepoint != null) {
                try {
                    tenant.rollback(savepoint)
                } catch (rollbackFailure: SQLException) {
                    log.warn("No se pudo deshacer el savepoint del aviso de salida", rollbackFailure)
                }
            }
            failure
        }
    }

    /**
     * Entrega los avisos vencidos. Devuelve cuantos proceso. Respeta [limit] y [budget] (tiempo total) y el orden por
     * registro. Los servicios con la extension inactiva no reciben nada: sus avisos esperan.
     */
    fun drain(
        tenant: Connection,
        source: OutboundSource,
        sinks: Collection<OutboundSink>,
        files: ModuleFiles?,
        now: Instant = Instant.now(),
        limit: Int = 100,
        budget: Duration = Duration.ofSeconds(10),
    ): Int = drain(JdbcOutboundStore, tenant, source, sinks, files, links, now, limit, budget)

    fun drain(
        store: OutboundStore,
        tenant: Connection,
        source: OutboundSource,
        sinks: Collection<OutboundSink>,
        files: ModuleFiles?,
        links: OutboundLinks,
        now: Instant = Instant.now(),
        limit: Int = 100,
        budget: Duration = Duration.ofSeconds(10),
    ): Int {
        val catalog = catalogOf(tenant)
        purgeIfDue(store, tenant, catalog, now)
        if (sinks.isEmpty()) return 0
        // Cola vacia (lo normal): nada que reclamar ni consultar mas; el hilo de trabajos es uno solo para todo el servidor.
        if (!store.hasWork(tenant)) return 0
        store.releaseStale(tenant, now.minus(StaleLock), now)
        val bySink = sinks.associateBy { it.sinkId }
        val active = store.enabledSinks(tenant, bySink.keys)
        if (active.isEmpty()) return 0

        val throttled = mutableSetOf<String>()
        val deadline = System.nanoTime() + budget.toNanos()
        var processed = 0
        var stop = false
        // Reclamados que aun no se resolvieron: si algo lanza, el finally los devuelve a la cola.
        val claimed = linkedSetOf<Long>()
        try {
            while (!stop && processed < limit && System.nanoTime() < deadline) {
                val ids = active.filter { it !in throttled && !isHeldBack(catalog, it, now) }
                if (ids.isEmpty()) break
                val batch = store.claimDue(tenant, ids, now, minOf(BatchSize, limit - processed))
                if (batch.isEmpty()) break
                claimed += batch.map { it.id }
                val unused = mutableListOf<Long>()
                for (claim in batch) {
                    val sink = bySink[claim.sinkId]
                    if (stop || sink == null || claim.sinkId in throttled || System.nanoTime() >= deadline) {
                        unused += claim.id
                        continue
                    }
                    when (deliverOne(store, tenant, catalog, source, sink, files, links, claim, now)) {
                        DeliveryOutcome.Interrupted -> { unused += claim.id; stop = true }
                        DeliveryOutcome.Throttled -> { throttled += claim.sinkId; processed++; claimed -= claim.id }
                        DeliveryOutcome.Done -> { processed++; claimed -= claim.id }
                    }
                }
                store.release(tenant, unused)
                claimed -= unused.toSet()
            }
        } finally {
            if (claimed.isNotEmpty()) {
                try {
                    store.release(tenant, claimed.toList())
                } catch (failure: Exception) {
                    log.warn("No se pudieron devolver {} avisos reclamados a la cola; volveran solos tras el bloqueo", claimed.size, failure)
                }
            }
        }
        return processed
    }

    private enum class DeliveryOutcome { Done, Throttled, Interrupted }

    private fun deliverOne(
        store: OutboundStore,
        tenant: Connection,
        catalog: String,
        source: OutboundSource,
        sink: OutboundSink,
        files: ModuleFiles?,
        links: OutboundLinks,
        claim: OutboundClaim,
        now: Instant,
    ): DeliveryOutcome {
        val attempts = claim.attempts + 1
        if (claim.event === UnreadableEvent) {
            resolve(claim.id) { store.markFailed(tenant, claim.id, now, attempts, "aviso ilegible en la cola") }
            return DeliveryOutcome.Done
        }
        val result = try {
            sink.deliver(OutboundDelivery(tenant, claim.id, claim.event, attempts, source, files, links, now))
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return DeliveryOutcome.Interrupted
        } catch (failure: Throwable) {
            log.warn("Fallo la entrega {} del aviso {}", sink.sinkId, claim.id, failure)
            OutboundResult.Retry(unexpectedReason(failure))
        }
        when (result) {
            is OutboundResult.Sent -> resolve(claim.id) { store.markSent(tenant, claim.id, now, sentText(result)) }
            is OutboundResult.Skipped -> resolve(claim.id) { store.markSkipped(tenant, claim.id, now, result.reason) }
            is OutboundResult.Failed -> resolve(claim.id) { store.markFailed(tenant, claim.id, now, attempts, result.reason) }
            is OutboundResult.Retry -> resolve(claim.id) {
                if (attempts >= MaxAttempts) {
                    store.markFailed(tenant, claim.id, now, attempts, result.reason)
                } else {
                    val wait = result.after?.takeIf { it > backoff(attempts) } ?: backoff(attempts)
                    store.markRetry(tenant, claim.id, now, attempts, now.plus(wait), result.reason)
                }
            }
            is OutboundResult.Throttled -> {
                throttledUntil[heldKey(catalog, claim.sinkId)] = now.plus(result.after)
                resolve(claim.id) {
                    if (Duration.between(claim.createdAt, now) > MaxThrottledAge) {
                        store.markFailed(tenant, claim.id, now, claim.attempts, "El servicio siguio limitando los envios durante mas de 7 dias")
                    } else {
                        store.markThrottled(tenant, claim.id, now, now.plus(result.after), result.reason)
                    }
                }
                return DeliveryOutcome.Throttled
            }
        }
        return DeliveryOutcome.Done
    }

    // Guarda el resultado; si falla, lo reintenta una vez. Un false (la fila ya no estaba en envio) no se pisa.
    private fun resolve(id: Long, action: () -> Boolean) {
        val applied = try {
            action()
        } catch (failure: Exception) {
            log.warn("No se pudo guardar el resultado del aviso {}; se reintenta una vez", id, failure)
            action()
        }
        if (!applied) log.warn("El aviso {} ya no estaba en envio (otra pasada lo devolvio a la cola); no se sobrescribe", id)
    }

    // Motivo visible de una excepcion inesperada: clase y mensaje recortado, sin datos del aviso.
    private fun unexpectedReason(failure: Throwable): String {
        val detail = failure.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        return clip("error inesperado (${failure.javaClass.simpleName}$detail)", MaxReasonLength)
    }

    private fun sentText(sent: OutboundResult.Sent): String? {
        val parts = listOfNotNull(sent.detail.takeIf { it.isNotBlank() }, sent.warnings.takeIf { it.isNotEmpty() }?.let { "Avisos: " + it.joinToString("; ") })
        return parts.joinToString(" | ").takeIf { it.isNotEmpty() }
    }

    private fun catalogOf(tenant: Connection): String = try { tenant.catalog.orEmpty() } catch (_: SQLException) { "" }

    private fun heldKey(catalog: String, sinkId: String) = "$catalog/$sinkId"

    // Hay un "no antes de" vigente para ese servicio en esa empresa (se limpia al vencer).
    private fun isHeldBack(catalog: String, sinkId: String, now: Instant): Boolean {
        val key = heldKey(catalog, sinkId)
        val until = throttledUntil[key] ?: return false
        if (until > now) return true
        throttledUntil.remove(key, until)
        return false
    }

    // Borra lo entregado hace mas de 30 dias, como mucho una vez al dia por empresa (se registra solo si salio bien).
    private fun purgeIfDue(store: OutboundStore, tenant: Connection, catalog: String, now: Instant) {
        val last = lastPurge[catalog]
        if (last != null && Duration.between(last, now) < PurgeEvery) return
        try {
            store.purge(tenant, now.minus(RetentionPeriod), now.minus(PurgeFailedAfter))
            lastPurge[catalog] = now
        } catch (failure: SQLException) {
            log.warn("No se pudo purgar la cola de salida", failure)
        }
    }

    /** Trabajo de un modulo: entrega su cola cada minuto. [sinks] se evalua en cada pasada (las extensiones cambian). */
    fun job(
        moduleId: String,
        source: OutboundSource,
        sinks: () -> List<OutboundSink>,
        files: (ModuleJobContext) -> ModuleFiles?,
    ): ModuleJob {
        require(source.moduleId == moduleId) { "La fuente de salida es del modulo ${source.moduleId}, no de $moduleId." }
        return ModuleJob("outbound", Duration.ofMinutes(1)) { context ->
            drain(context.tenant, source, sinks(), files(context))
        }
    }

    fun summary(tenant: Connection, sinkId: String): OutboundSummary = JdbcOutboundStore.summary(tenant, sinkId, Instant.now())

    /** Los avisos mas recientes del servicio; [statuses] vacio = todos. */
    fun events(tenant: Connection, sinkId: String, statuses: Collection<OutboundStatus> = emptyList(), limit: Int = 50): List<OutboundEventRow> =
        JdbcOutboundStore.events(tenant, sinkId, statuses, limit)

    /** Un fallido vuelve a la cola con los intentos en cero. */
    fun retry(tenant: Connection, sinkId: String, id: Long): Boolean = JdbcOutboundStore.retry(tenant, sinkId, id, Instant.now())

    fun retryFailed(tenant: Connection, sinkId: String): Int = JdbcOutboundStore.retryFailed(tenant, sinkId, Instant.now())

    /** Descarta un aviso fallido o pendiente: no se envia y deja pasar a los siguientes del registro. */
    fun discard(tenant: Connection, sinkId: String, id: Long): Boolean = JdbcOutboundStore.discard(tenant, sinkId, id, Instant.now())

    /** Borra la cola y los enlaces del servicio (al desinstalar la extension). */
    fun forget(tenant: Connection, sinkId: String): Int = JdbcOutboundStore.forget(tenant, sinkId)
}
