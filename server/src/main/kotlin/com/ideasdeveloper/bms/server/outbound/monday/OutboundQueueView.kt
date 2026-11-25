package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.outbound.OutboundQueue
import com.ideasdeveloper.bms.server.outbound.OutboundStatus
import java.sql.Connection

/** [MondayQueueView] de la cola del Core para un sink: lo usan tal cual todas las extensiones de monday. */
class OutboundQueueView(private val sinkId: String) : MondayQueueView {
    override fun summary(tenant: Connection): MondayQueueSummary {
        val summary = OutboundQueue.summary(tenant, sinkId)
        return MondayQueueSummary(
            pending = summary.pending,
            sending = summary.sending,
            sent = summary.sentLast24h,
            failed = summary.failed,
            skipped = summary.skippedLast24h,
        )
    }

    override fun events(tenant: Connection, statuses: Set<String>, limit: Int): List<MondayQueueEvent> =
        OutboundQueue.events(tenant, sinkId, statuses.mapNotNull { OutboundStatus.of(it) }, limit).map { row ->
            MondayQueueEvent(
                id = row.id,
                kind = row.kind.code,
                recordType = row.recordType,
                recordId = row.recordId,
                status = row.status.code,
                attempts = row.attempts,
                detail = listOfNotNull(row.label, if (row.status == OutboundStatus.Failed) row.lastError else row.result ?: row.lastError)
                    .filter { it.isNotBlank() }.joinToString(" | "),
                at = row.sentAt ?: row.nextAttemptAt ?: row.createdAt,
                nextAttemptAt = row.nextAttemptAt.takeIf { row.status == OutboundStatus.Pending },
                // La cola guarda los avisos de un envio como "detalle | Avisos: ...".
                hasWarnings = row.status == OutboundStatus.Sent && row.result.orEmpty().contains("Avisos:"),
            )
        }

    override fun retry(tenant: Connection, id: Long): Boolean = OutboundQueue.retry(tenant, sinkId, id)

    override fun retryFailed(tenant: Connection): Int = OutboundQueue.retryFailed(tenant, sinkId)

    override fun discard(tenant: Connection, id: Long): Boolean = OutboundQueue.discard(tenant, sinkId, id)
}
