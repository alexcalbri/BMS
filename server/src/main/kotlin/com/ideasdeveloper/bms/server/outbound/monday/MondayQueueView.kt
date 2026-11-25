package com.ideasdeveloper.bms.server.outbound.monday

import java.sql.Connection
import java.time.Instant

/** Cuenta de avisos de la cola por estado. */
data class MondayQueueSummary(val pending: Int = 0, val sending: Int = 0, val sent: Int = 0, val failed: Int = 0, val skipped: Int = 0)

/** Un aviso de la cola tal como lo ve la pantalla de estado. [detail] es la razon (fallidos) o el resultado con avisos (enviados). */
data class MondayQueueEvent(
    val id: Long,
    val kind: String,
    val recordType: String,
    val recordId: String,
    val status: String,
    val attempts: Int = 0,
    val detail: String = "",
    val at: Instant? = null,
    /** Pendientes: cuando se vuelve a intentar. */
    val nextAttemptAt: Instant? = null,
    /** Enviados: si el resultado trae avisos (algo se omitio sin fallar). */
    val hasWarnings: Boolean = false,
)

/**
 * Lo que la pantalla de estado y las herramientas MCP necesitan de la cola de un sink. El modulo (CRM) lo conecta con
 * OutboundQueue; asi este paquete no depende de la cola.
 */
interface MondayQueueView {
    fun summary(tenant: Connection): MondayQueueSummary

    /** Avisos en alguno de [statuses] ("failed", "sent", "pending"...), los mas recientes primero. */
    fun events(tenant: Connection, statuses: Set<String>, limit: Int): List<MondayQueueEvent>

    /** Devuelve a la cola un aviso fallido o pendiente con error ("Reintentar ahora"); false si no existe o no aplica. */
    fun retry(tenant: Connection, id: Long): Boolean

    /** Devuelve a la cola todos los fallidos; cuantos fueron. */
    fun retryFailed(tenant: Connection): Int

    /** Descarta un aviso fallido (no se envia nunca); false si no existe. */
    fun discard(tenant: Connection, id: Long): Boolean
}
