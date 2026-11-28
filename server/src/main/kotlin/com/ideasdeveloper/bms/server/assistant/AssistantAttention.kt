package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.server.company.application.ServerWorkspace
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.NotificationTarget
import com.ideasdeveloper.bms.server.modules.NotificationText
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The inbox notifications of the assistant (Core notification center): ONE live notification per conversation, for the person
 * who owns it, never with chat content (no title taken from the first message, no tool arguments): only that something waits
 * (a confirmation, or an answer ready) and a target the app opens on the conversation. Whoever the owner is, nothing is sent to
 * anybody else: the recipient is the conversation's own user.
 *
 * Who can be addressed: the inbox of a company is read by that company's session user, so only a person's own session
 * ("self") is notified. A server person's switcher session (owner key `server:<id>`) is skipped: the notification would sit in
 * the company's inbox addressed to a central id nobody reads there, and must never reach the business owner. In the server
 * workspace only the server owner is notified (an administrator has no inbox there). There are no per-user notification
 * preferences in the Core to honour.
 */
class AssistantNotifier(
    private val access: ModuleAccessService,
    private val companyCode: String,
    private val isServerOwner: (UUID) -> Boolean,
) {
    private val log = LoggerFactory.getLogger(AssistantNotifier::class.java)

    /** A turn paused with confirmations pending. */
    fun paused(store: AssistantStore, conversationId: UUID) = push(store, conversationId, pending = true)

    /** A resumed turn finished (answer ready, or it ended in an error or at the limit). */
    fun finished(store: AssistantStore, conversationId: UUID) = push(store, conversationId, pending = false)

    /** The notification of the conversation is not needed any more (decided, expired, read): marked read, never deleted. */
    fun resolve(store: AssistantStore, conversationId: UUID) {
        val live = store.liveNotification(conversationId) ?: return
        val owner = store.owner(conversationId) ?: return
        store.connections?.invoke()?.use { tenant -> access.notifications.markRead(tenant, owner.first, live) }
        store.setLiveNotification(conversationId, null)
    }

    private fun push(store: AssistantStore, conversationId: UUID, pending: Boolean) {
        val owner = store.owner(conversationId) ?: return
        if (owner.second != "self") return
        if (companyCode.equals(ServerWorkspace.Code, ignoreCase = true) && !isServerOwner(owner.first)) return
        // One live notification: the previous one is resolved before the new one is created.
        resolve(store, conversationId)
        val target = NotificationTarget("assistant", mapOf("conversationId" to conversationId.toString()))
        val id = store.connections?.invoke()?.use { tenant ->
            access.notificationsFor(ModuleId).notify(tenant, owner.first, target) {
                if (pending) NotificationText(tr("Asistente: confirmacion pendiente"), tr("El asistente espera tu aprobacion para seguir."))
                else NotificationText(tr("Asistente: respuesta lista"), tr("El asistente termino. Abre la conversacion para verla."))
            }
        } ?: return
        store.setLiveNotification(conversationId, id)
        log.debug("Assistant notification created for conversation {}", conversationId)
    }

    companion object {
        /** Origin of the notifications; the target screen is "assistant" too with the `conversationId` parameter. */
        const val ModuleId = "assistant"
    }
}
