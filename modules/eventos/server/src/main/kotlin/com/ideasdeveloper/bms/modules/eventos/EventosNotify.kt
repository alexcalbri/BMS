package com.ideasdeveloper.bms.modules.eventos

import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleNotifier
import com.ideasdeveloper.bms.server.modules.NotificationTarget
import com.ideasdeveloper.bms.server.modules.NotificationText
import java.sql.Connection
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Notices in the Core's notification center, sent after the change is committed. A notice never breaks the change that
 * caused it: each one is created inside runCatching and a failure is only logged. The author is never notified.
 *
 * Who is told: the responsible user of the event when it is an active user; otherwise the active users that manage events
 * (eventos.manage) and the business owner.
 */
object EventosNotify {
    private val log = LoggerFactory.getLogger(EventosNotify::class.java)

    val NewRegistrationTitle = msg("Nueva inscripción")
    val WaitlistTitle = msg("En lista de espera")
    val FullTitle = msg("Evento lleno")
    val SeatFreedTitle = msg("Se liberó una plaza")

    fun target(eventId: UUID) = NotificationTarget("evento", mapOf("id" to eventId.toString()))

    /** The responsible when active, otherwise the managers; each once and never the author. */
    fun recipients(responsibleUserId: UUID?, responsibleActive: Boolean, managers: Collection<UUID>, authorId: UUID?): List<UUID> {
        val base = if (responsibleUserId != null && responsibleActive) listOf(responsibleUserId) else managers.toList()
        return base.distinct().filter { it != authorId }
    }

    fun newRegistrationText(customer: String, event: String, seats: Int): String = tr("{0} · {1} · {2}", customer, event, EventosRules.seatsLabel(seats))

    fun fullText(event: String, capacity: Int?): String = if (capacity == null) event else tr("{0} · {1} plazas", event, capacity)

    fun seatFreedText(event: String, waiting: Int): String = tr("{0}: {1} en espera", event, waiting)

    fun newRegistration(tenant: Connection, notifier: ModuleNotifier?, event: Event, customer: String, seats: Int, waitlisted: Boolean, authorId: UUID?) =
        send(tenant, notifier, event, authorId, if (waitlisted) WaitlistTitle else NewRegistrationTitle) { newRegistrationText(customer, event.title, seats) }

    fun eventFull(tenant: Connection, notifier: ModuleNotifier?, event: Event, authorId: UUID?) =
        send(tenant, notifier, event, authorId, FullTitle) { fullText(event.title, event.capacity) }

    /** A registered person left and people are waiting: the manager decides whom to move ("Pasar a inscrito"). */
    fun seatFreed(tenant: Connection, notifier: ModuleNotifier?, event: Event, waiting: Int, authorId: UUID?) =
        send(tenant, notifier, event, authorId, SeatFreedTitle) { seatFreedText(event.title, waiting) }

    /** Un aviso por destinatario, escrito en su idioma: [text] se evalúa dentro de cada aviso. */
    private fun send(tenant: Connection, notifier: ModuleNotifier?, event: Event, authorId: UUID?, title: String, text: () -> String) {
        if (notifier == null) return
        runCatching {
            val active = event.responsibleUserId?.let { EventosRepository.isActiveUser(tenant, it) } ?: false
            val managers = if (active) emptyList() else EventosRepository.managerIds(tenant)
            recipients(event.responsibleUserId, active, managers, authorId).forEach { userId ->
                runCatching { notifier.notify(tenant, userId, target(event.id)) { NotificationText(tr(title), text().take(1000)) } }
                    .onFailure { log.warn("No se pudo crear el aviso \"{}\" de un evento ({}).", title, it.javaClass.simpleName) }
            }
        }.onFailure { log.warn("No se pudo preparar el aviso \"{}\" de un evento ({}).", title, it.javaClass.simpleName) }
    }
}
