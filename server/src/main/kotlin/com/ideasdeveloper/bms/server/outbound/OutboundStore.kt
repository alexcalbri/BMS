package com.ideasdeveloper.bms.server.outbound

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/** Estado de un aviso en la cola (columna status de outbound_events). */
enum class OutboundStatus(val code: String) {
    Pending("pending"),
    Sending("sending"),
    Sent("sent"),
    Failed("failed"),
    Skipped("skipped");

    companion object {
        fun of(code: String): OutboundStatus? = entries.firstOrNull { it.code == code }
    }
}

/** Resumen de la cola de un servicio, para la pantalla de estado. */
data class OutboundSummary(
    val pending: Int,
    val sending: Int,
    val failed: Int,
    val sentLast24h: Int,
    val skippedLast24h: Int,
    val lastSentAt: Instant?,
    /** Proximo intento entre los pendientes; null si no hay. */
    val nextAttemptAt: Instant?,
)

/** Una fila del listado de la cola. [label] es el nombre del archivo o las primeras palabras de la nota. */
data class OutboundEventRow(
    val id: Long,
    val kind: OutboundEventKind,
    val recordType: String,
    val recordId: String,
    val status: OutboundStatus,
    val attempts: Int,
    val nextAttemptAt: Instant?,
    val lastError: String?,
    val result: String?,
    val createdAt: Instant,
    val sentAt: Instant?,
    val origin: String,
    val label: String?,
)

/** Un aviso reclamado por el trabajo; [attempts] son los intentos ya gastados (el siguiente es attempts + 1). */
data class OutboundClaim(val id: Long, val sinkId: String, val event: OutboundEvent, val attempts: Int, val createdAt: Instant)

/**
 * Almacen de la cola. [JdbcOutboundStore] guarda en la base de la empresa; las pruebas usan uno en memoria.
 * Las operaciones por id que da un usuario van acotadas al servicio ([sinkId]).
 */
interface OutboundStore {
    /** Hay algun aviso pendiente o enviandose (consulta barata para no hacer mas trabajo si la cola esta vacia). */
    fun hasWork(tenant: Connection): Boolean

    /** De [sinkIds], los que tienen la extension activa (status enabled en tenant_extensions). */
    fun enabledSinks(tenant: Connection, sinkIds: Collection<String>): Set<String>

    /** Encola el aviso; un `updated` pendiente sin intentar del mismo servicio y registro absorbe los `changed`. */
    fun enqueue(tenant: Connection, sinkId: String, event: OutboundEvent, now: Instant)

    /** Deja directamente un aviso como fallido (no se pudo ponerlo en cola); [reason] se muestra al usuario. */
    fun enqueueFailed(tenant: Connection, sinkId: String, event: OutboundEvent, now: Instant, reason: String)

    /** Devuelve a pendiente los `sending` bloqueados desde antes de [lockedBefore]. */
    fun releaseStale(tenant: Connection, lockedBefore: Instant, now: Instant): Int

    /**
     * Reclama (pasa a `sending`) los vencidos de [sinkIds] en orden de id, solo el mas antiguo no terminado de cada
     * registro: un aviso anterior pendiente o enviandose bloquea a los siguientes del mismo registro, y un `created`
     * fallido tambien (sin elemento no hay donde poner notas); un note, file o updated fallido no bloquea.
     */
    fun claimDue(tenant: Connection, sinkIds: Collection<String>, now: Instant, limit: Int): List<OutboundClaim>

    /** Devuelve reclamados a pendiente sin gastar intento. */
    fun release(tenant: Connection, ids: Collection<Long>)

    /**
     * Las operaciones mark* solo afectan a un aviso que sigue en `sending`; devuelven false si la fila ya no lo esta
     * (p. ej. un bloqueo viejo que otra pasada devolvio a pendiente) y entonces no la pisan.
     */
    fun markSent(tenant: Connection, id: Long, now: Instant, result: String?): Boolean

    fun markSkipped(tenant: Connection, id: Long, now: Instant, reason: String): Boolean

    /** Fallo pasajero: queda pendiente con [attempts] intentos y proximo intento [nextAttemptAt]. */
    fun markRetry(tenant: Connection, id: Long, now: Instant, attempts: Int, nextAttemptAt: Instant, reason: String): Boolean

    /** El servicio pidio esperar: pendiente, mismo numero de intentos. */
    fun markThrottled(tenant: Connection, id: Long, now: Instant, nextAttemptAt: Instant, reason: String): Boolean

    fun markFailed(tenant: Connection, id: Long, now: Instant, attempts: Int, reason: String): Boolean

    fun summary(tenant: Connection, sinkId: String, now: Instant): OutboundSummary

    /** Los mas recientes primero; [statuses] vacio = todos. */
    fun events(tenant: Connection, sinkId: String, statuses: Collection<OutboundStatus>, limit: Int): List<OutboundEventRow>

    /** Un fallido, o un pendiente que ya gasto intentos ("Reintentar ahora"), queda pendiente para ya con los intentos en cero; conserva su id y orden. */
    fun retry(tenant: Connection, sinkId: String, id: Long, now: Instant): Boolean

    fun retryFailed(tenant: Connection, sinkId: String, now: Instant): Int

    /** Descarta un fallido o pendiente (queda como `skipped`, asi libera a los siguientes del registro). */
    fun discard(tenant: Connection, sinkId: String, id: Long, now: Instant): Boolean

    /** Borra la cola y los enlaces del servicio (al desinstalar la extension). */
    fun forget(tenant: Connection, sinkId: String): Int

    /**
     * Borra (en lotes) los entregados u omitidos cuyo ultimo cambio es anterior a [before] y los fallidos anteriores a
     * [failedBefore]; devuelve cuantos.
     */
    fun purge(tenant: Connection, before: Instant, failedBefore: Instant): Int
}

/** Texto corto de un aviso para listarlo: el nombre del archivo o las primeras palabras de la nota. */
fun outboundLabel(fileName: String?, text: String?, maxLength: Int = 60): String? {
    val source = fileName?.takeIf { it.isNotBlank() } ?: text?.takeIf { it.isNotBlank() } ?: return null
    val flat = source.trim().replace(Regex("\\s+"), " ")
    return if (flat.length <= maxLength) flat else flat.take(maxLength - 1).trimEnd() + "…"
}

/** Recorta un texto guardado en la cola (motivos y resultados). */
fun clip(text: String, max: Int = 1000): String = if (text.length <= max) text else text.take(max - 1) + "…"

/** Aviso con payload ilegible; [OutboundQueue.drain] lo deja fallido sin entregarlo. */
val UnreadableEvent = OutboundEvent("unknown", "unknown", "unknown", OutboundEventKind.Updated)

/** Almacen sobre las tablas outbound_events y outbound_links; SQL siempre parametrizado. */
object JdbcOutboundStore : OutboundStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val MaxListLimit = 200
    private const val PurgeBatch = 5000

    override fun hasWork(tenant: Connection): Boolean =
        tenant.prepareStatement("SELECT 1 FROM outbound_events WHERE status IN ('pending', 'sending') LIMIT 1").use { query ->
            query.executeQuery().use { rows -> rows.next() }
        }

    override fun enabledSinks(tenant: Connection, sinkIds: Collection<String>): Set<String> {
        if (sinkIds.isEmpty()) return emptySet()
        val array = tenant.createArrayOf("varchar", sinkIds.distinct().toTypedArray())
        try {
            tenant.prepareStatement("SELECT extension_id FROM tenant_extensions WHERE status = 'enabled' AND extension_id = ANY(?)").use { query ->
                query.setArray(1, array)
                query.executeQuery().use { rows ->
                    val found = mutableSetOf<String>()
                    while (rows.next()) found += rows.getString(1)
                    return found
                }
            }
        } finally {
            array.free()
        }
    }

    override fun enqueue(tenant: Connection, sinkId: String, event: OutboundEvent, now: Instant) {
        if (event.kind == OutboundEventKind.Updated && mergeIntoPending(tenant, sinkId, event, now)) return
        tenant.prepareStatement(
            """
            INSERT INTO outbound_events (sink_id, module_id, record_type, record_id, kind, origin, payload, status, next_attempt_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'pending', ?, ?, ?)
            """.trimIndent(),
        ).use { insert ->
            insert.setString(1, sinkId)
            insert.setString(2, event.moduleId)
            insert.setString(3, event.recordType)
            insert.setString(4, event.recordId)
            insert.setString(5, event.kind.code)
            insert.setString(6, event.origin.take(80))
            insert.setString(7, json.encodeToString(OutboundEvent.serializer(), event))
            insert.setTimestamp(8, ts(now))
            insert.setTimestamp(9, ts(now))
            insert.setTimestamp(10, ts(now))
            insert.executeUpdate()
        }
    }

    // Une los cambios en un `updated` pendiente sin intentar con UNA sola sentencia atomica: el bloqueo (FOR UPDATE)
    // y el UPDATE van juntos, asi tambien en autocommit; SKIP LOCKED salta el que el trabajo esta reclamando y
    // entonces se inserta uno nuevo. Sin claves = "todos los campos": absorbe a cualquier otro.
    private fun mergeIntoPending(tenant: Connection, sinkId: String, event: OutboundEvent, now: Instant): Boolean {
        val incoming = json.encodeToString(ListSerializer(String.serializer()), event.changed)
        tenant.prepareStatement(
            """
            UPDATE outbound_events SET
                payload = jsonb_set(payload, '{changed}', CASE
                    WHEN jsonb_array_length(COALESCE(payload->'changed', '[]'::jsonb)) = 0
                      OR jsonb_array_length(CAST(? AS jsonb)) = 0 THEN '[]'::jsonb
                    ELSE (SELECT COALESCE(jsonb_agg(d.value ORDER BY d.first_pos), '[]'::jsonb)
                          FROM (SELECT t.value, min(t.pos) AS first_pos
                                FROM jsonb_array_elements_text(COALESCE(payload->'changed', '[]'::jsonb) || CAST(? AS jsonb))
                                     WITH ORDINALITY AS t(value, pos)
                                GROUP BY t.value) d)
                END),
                updated_at = ?
            WHERE id = (
                SELECT id FROM outbound_events
                WHERE sink_id = ? AND record_type = ? AND record_id = ? AND kind = 'updated' AND status = 'pending' AND attempts = 0
                ORDER BY id DESC LIMIT 1
                FOR UPDATE SKIP LOCKED
            ) AND status = 'pending' AND attempts = 0
            RETURNING id
            """.trimIndent(),
        ).use { update ->
            update.setString(1, incoming)
            update.setString(2, incoming)
            update.setTimestamp(3, ts(now))
            update.setString(4, sinkId)
            update.setString(5, event.recordType)
            update.setString(6, event.recordId)
            update.executeQuery().use { rows -> return rows.next() }
        }
    }

    override fun enqueueFailed(tenant: Connection, sinkId: String, event: OutboundEvent, now: Instant, reason: String) {
        tenant.prepareStatement(
            """
            INSERT INTO outbound_events (sink_id, module_id, record_type, record_id, kind, origin, payload, status, attempts, next_attempt_at, last_error, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'failed', 0, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { insert ->
            insert.setString(1, sinkId)
            insert.setString(2, event.moduleId)
            insert.setString(3, event.recordType)
            insert.setString(4, event.recordId)
            insert.setString(5, event.kind.code)
            insert.setString(6, event.origin.take(80))
            insert.setString(7, json.encodeToString(OutboundEvent.serializer(), event))
            insert.setTimestamp(8, ts(now))
            insert.setString(9, clip(reason))
            insert.setTimestamp(10, ts(now))
            insert.setTimestamp(11, ts(now))
            insert.executeUpdate()
        }
    }

    override fun releaseStale(tenant: Connection, lockedBefore: Instant, now: Instant): Int =
        tenant.prepareStatement(
            "UPDATE outbound_events SET status = 'pending', locked_at = NULL, updated_at = ? WHERE status = 'sending' AND locked_at < ?",
        ).use { update ->
            update.setTimestamp(1, ts(now))
            update.setTimestamp(2, ts(lockedBefore))
            update.executeUpdate()
        }

    override fun claimDue(tenant: Connection, sinkIds: Collection<String>, now: Instant, limit: Int): List<OutboundClaim> {
        if (sinkIds.isEmpty() || limit <= 0) return emptyList()
        val array = tenant.createArrayOf("varchar", sinkIds.distinct().toTypedArray())
        try {
            val claims = tenant.prepareStatement(
                """
                UPDATE outbound_events SET status = 'sending', locked_at = ?, updated_at = ?
                WHERE id IN (
                    SELECT e.id FROM outbound_events e
                    WHERE e.status = 'pending' AND e.sink_id = ANY(?) AND e.next_attempt_at <= ?
                      AND NOT EXISTS (
                          SELECT 1 FROM outbound_events p
                          WHERE p.sink_id = e.sink_id AND p.record_type = e.record_type AND p.record_id = e.record_id
                            AND p.id < e.id
                            AND (p.status IN ('pending', 'sending') OR (p.status = 'failed' AND p.kind = 'created'))
                      )
                    ORDER BY e.id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, sink_id, payload::text, attempts, created_at
                """.trimIndent(),
            ).use { update ->
                update.setTimestamp(1, ts(now))
                update.setTimestamp(2, ts(now))
                update.setArray(3, array)
                update.setTimestamp(4, ts(now))
                update.setInt(5, limit)
                update.executeQuery().use { rows ->
                    val found = mutableListOf<OutboundClaim>()
                    while (rows.next()) {
                        // Un payload ilegible no se puede entregar nunca; drain lo deja fallido.
                        val event = runCatching { json.decodeFromString(OutboundEvent.serializer(), rows.getString(3)) }.getOrNull() ?: UnreadableEvent
                        found += OutboundClaim(rows.getLong(1), rows.getString(2), event, rows.getInt(4), rows.getTimestamp(5).toInstant())
                    }
                    found
                }
            }
            return claims.sortedBy { it.id }
        } finally {
            array.free()
        }
    }

    override fun release(tenant: Connection, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val array = tenant.createArrayOf("bigint", ids.toTypedArray())
        try {
            tenant.prepareStatement("UPDATE outbound_events SET status = 'pending', locked_at = NULL WHERE status = 'sending' AND id = ANY(?)").use { update ->
                update.setArray(1, array)
                update.executeUpdate()
            }
        } finally {
            array.free()
        }
    }

    // Todas las mark* exigen status = 'sending': si otra pasada ya devolvio la fila a pendiente no se pisa.
    override fun markSent(tenant: Connection, id: Long, now: Instant, result: String?): Boolean =
        update(
            tenant,
            "UPDATE outbound_events SET status = 'sent', sent_at = ?, updated_at = ?, locked_at = NULL, last_error = NULL, result = ? WHERE id = ? AND status = 'sending'",
        ) { setTimestamp(1, ts(now)); setTimestamp(2, ts(now)); setString(3, result?.let { clip(it) }); setLong(4, id) }

    override fun markSkipped(tenant: Connection, id: Long, now: Instant, reason: String): Boolean =
        update(
            tenant,
            "UPDATE outbound_events SET status = 'skipped', updated_at = ?, locked_at = NULL, last_error = NULL, result = ? WHERE id = ? AND status = 'sending'",
        ) { setTimestamp(1, ts(now)); setString(2, clip(reason)); setLong(3, id) }

    override fun markRetry(tenant: Connection, id: Long, now: Instant, attempts: Int, nextAttemptAt: Instant, reason: String): Boolean =
        update(
            tenant,
            "UPDATE outbound_events SET status = 'pending', attempts = ?, next_attempt_at = ?, updated_at = ?, locked_at = NULL, last_error = ? WHERE id = ? AND status = 'sending'",
        ) { setInt(1, attempts); setTimestamp(2, ts(nextAttemptAt)); setTimestamp(3, ts(now)); setString(4, clip(reason)); setLong(5, id) }

    override fun markThrottled(tenant: Connection, id: Long, now: Instant, nextAttemptAt: Instant, reason: String): Boolean =
        update(
            tenant,
            "UPDATE outbound_events SET status = 'pending', next_attempt_at = ?, updated_at = ?, locked_at = NULL, last_error = ? WHERE id = ? AND status = 'sending'",
        ) { setTimestamp(1, ts(nextAttemptAt)); setTimestamp(2, ts(now)); setString(3, clip(reason)); setLong(4, id) }

    override fun markFailed(tenant: Connection, id: Long, now: Instant, attempts: Int, reason: String): Boolean =
        update(
            tenant,
            "UPDATE outbound_events SET status = 'failed', attempts = ?, updated_at = ?, locked_at = NULL, last_error = ? WHERE id = ? AND status = 'sending'",
        ) { setInt(1, attempts); setTimestamp(2, ts(now)); setString(3, clip(reason)); setLong(4, id) }

    override fun summary(tenant: Connection, sinkId: String, now: Instant): OutboundSummary {
        val since = ts(now.minus(Duration.ofHours(24)))
        tenant.prepareStatement(
            """
            SELECT count(*) FILTER (WHERE status = 'pending'),
                   count(*) FILTER (WHERE status = 'sending'),
                   count(*) FILTER (WHERE status = 'failed'),
                   count(*) FILTER (WHERE status = 'sent' AND sent_at >= ?),
                   count(*) FILTER (WHERE status = 'skipped' AND updated_at >= ?),
                   max(sent_at),
                   min(next_attempt_at) FILTER (WHERE status = 'pending')
            FROM outbound_events WHERE sink_id = ?
            """.trimIndent(),
        ).use { query ->
            query.setTimestamp(1, since)
            query.setTimestamp(2, since)
            query.setString(3, sinkId)
            query.executeQuery().use { rows ->
                rows.next()
                return OutboundSummary(
                    pending = rows.getInt(1), sending = rows.getInt(2), failed = rows.getInt(3),
                    sentLast24h = rows.getInt(4), skippedLast24h = rows.getInt(5),
                    lastSentAt = rows.getTimestamp(6)?.toInstant(), nextAttemptAt = rows.getTimestamp(7)?.toInstant(),
                )
            }
        }
    }

    override fun events(tenant: Connection, sinkId: String, statuses: Collection<OutboundStatus>, limit: Int): List<OutboundEventRow> {
        val filter = statuses.isNotEmpty()
        val array = if (filter) tenant.createArrayOf("varchar", statuses.map { it.code }.toTypedArray()) else null
        try {
            val statusClause = if (filter) "AND status = ANY(?)" else ""
            tenant.prepareStatement(
                """
                SELECT id, kind, record_type, record_id, status, attempts, next_attempt_at, last_error, result,
                       created_at, sent_at, origin, payload->>'fileName', left(payload->>'text', 200)
                FROM outbound_events
                WHERE sink_id = ? $statusClause
                ORDER BY id DESC LIMIT ?
                """.trimIndent(),
            ).use { query ->
                var index = 1
                query.setString(index++, sinkId)
                if (array != null) query.setArray(index++, array)
                query.setInt(index, limit.coerceIn(1, MaxListLimit))
                query.executeQuery().use { rows ->
                    val found = mutableListOf<OutboundEventRow>()
                    while (rows.next()) found += row(rows)
                    return found
                }
            }
        } finally {
            array?.free()
        }
    }

    private fun row(rows: ResultSet) = OutboundEventRow(
        id = rows.getLong(1),
        kind = OutboundEventKind.of(rows.getString(2)) ?: OutboundEventKind.Updated,
        recordType = rows.getString(3),
        recordId = rows.getString(4),
        status = OutboundStatus.of(rows.getString(5)) ?: OutboundStatus.Pending,
        attempts = rows.getInt(6),
        nextAttemptAt = rows.getTimestamp(7)?.toInstant(),
        lastError = rows.getString(8),
        result = rows.getString(9),
        createdAt = rows.getTimestamp(10).toInstant(),
        sentAt = rows.getTimestamp(11)?.toInstant(),
        origin = rows.getString(12),
        label = outboundLabel(rows.getString(13), rows.getString(14)),
    )

    override fun retry(tenant: Connection, sinkId: String, id: Long, now: Instant): Boolean =
        tenant.prepareStatement(
            "UPDATE outbound_events SET status = 'pending', attempts = 0, next_attempt_at = ?, updated_at = ?, last_error = NULL, locked_at = NULL WHERE id = ? AND sink_id = ? AND (status = 'failed' OR (status = 'pending' AND attempts > 0))",
        ).use { update ->
            update.setTimestamp(1, ts(now)); update.setTimestamp(2, ts(now)); update.setLong(3, id); update.setString(4, sinkId)
            update.executeUpdate() > 0
        }

    override fun retryFailed(tenant: Connection, sinkId: String, now: Instant): Int =
        tenant.prepareStatement(
            "UPDATE outbound_events SET status = 'pending', attempts = 0, next_attempt_at = ?, updated_at = ?, last_error = NULL, locked_at = NULL WHERE sink_id = ? AND status = 'failed'",
        ).use { update ->
            update.setTimestamp(1, ts(now)); update.setTimestamp(2, ts(now)); update.setString(3, sinkId)
            update.executeUpdate()
        }

    override fun discard(tenant: Connection, sinkId: String, id: Long, now: Instant): Boolean =
        tenant.prepareStatement(
            "UPDATE outbound_events SET status = 'skipped', updated_at = ?, locked_at = NULL, result = 'descartado' WHERE id = ? AND sink_id = ? AND status IN ('failed', 'pending')",
        ).use { update ->
            update.setTimestamp(1, ts(now)); update.setLong(2, id); update.setString(3, sinkId)
            update.executeUpdate() > 0
        }

    override fun forget(tenant: Connection, sinkId: String): Int {
        val events = tenant.prepareStatement("DELETE FROM outbound_events WHERE sink_id = ?").use { delete ->
            delete.setString(1, sinkId)
            delete.executeUpdate()
        }
        tenant.prepareStatement("DELETE FROM outbound_links WHERE sink_id = ?").use { delete ->
            delete.setString(1, sinkId)
            delete.executeUpdate()
        }
        return events
    }

    // En lotes para no mantener bloqueos largos sobre una tabla grande.
    override fun purge(tenant: Connection, before: Instant, failedBefore: Instant): Int =
        purgeBatches(tenant, "status IN ('sent', 'skipped') AND updated_at < ?", before) +
            purgeBatches(tenant, "status = 'failed' AND updated_at < ?", failedBefore)

    private fun purgeBatches(tenant: Connection, condition: String, before: Instant): Int {
        var total = 0
        while (true) {
            val deleted = tenant.prepareStatement(
                "DELETE FROM outbound_events WHERE id IN (SELECT id FROM outbound_events WHERE $condition LIMIT $PurgeBatch)",
            ).use { delete ->
                delete.setTimestamp(1, ts(before))
                delete.executeUpdate()
            }
            total += deleted
            if (deleted < PurgeBatch) return total
        }
    }

    // Devuelve true si la sentencia afecto a una fila.
    private fun update(tenant: Connection, sql: String, bind: PreparedStatement.() -> Unit): Boolean =
        tenant.prepareStatement(sql).use { statement ->
            statement.bind()
            statement.executeUpdate() > 0
        }

    private fun ts(instant: Instant) = Timestamp.from(instant)
}

/** Enlaces de registro a elemento de fuera sobre la tabla outbound_links. */
object JdbcOutboundLinks : OutboundLinks {
    override fun get(tenant: Connection, sinkId: String, recordType: String, recordId: String): OutboundLink? =
        tenant.prepareStatement("SELECT external_id, container FROM outbound_links WHERE sink_id = ? AND record_type = ? AND record_id = ?").use { query ->
            query.setString(1, sinkId)
            query.setString(2, recordType)
            query.setString(3, recordId)
            query.executeQuery().use { rows -> if (rows.next()) OutboundLink(rows.getString(1), rows.getString(2)) else null }
        }

    override fun put(tenant: Connection, sinkId: String, recordType: String, recordId: String, link: OutboundLink) {
        tenant.prepareStatement(
            """
            INSERT INTO outbound_links (sink_id, record_type, record_id, external_id, container, updated_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (sink_id, record_type, record_id)
            DO UPDATE SET external_id = EXCLUDED.external_id, container = EXCLUDED.container, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).use { upsert ->
            upsert.setString(1, sinkId)
            upsert.setString(2, recordType)
            upsert.setString(3, recordId)
            upsert.setString(4, link.externalId)
            upsert.setString(5, link.container)
            upsert.executeUpdate()
        }
    }
}
