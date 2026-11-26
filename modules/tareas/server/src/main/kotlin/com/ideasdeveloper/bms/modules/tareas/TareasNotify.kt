package com.ideasdeveloper.bms.modules.tareas

import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleNotifier
import com.ideasdeveloper.bms.server.modules.NotificationTarget
import com.ideasdeveloper.bms.server.modules.NotificationText
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Notices in the Core's notification center. A notice never breaks the change that caused it: each one is created inside
 * runCatching and a failure is only logged. The author of the change is never notified of it.
 */
object TareasNotify {
    private val log = LoggerFactory.getLogger(TareasNotify::class.java)

    val AssignedTitle = msg("Te asignaron una tarea")
    val ReviewTitle = msg("Tarea por revisar")
    val ReturnedTitle = msg("Tarea devuelta")
    val ApprovedTitle = msg("Tarea aprobada")
    val CommentedTitle = msg("Nuevo comentario en una tarea")
    val DueSoonTitle = msg("Tarea por vencer")
    val OverdueTitle = msg("Tarea vencida")
    private const val ExcerptLength = 140

    fun target(taskId: UUID) = NotificationTarget("task", mapOf("id" to taskId.toString()))

    /** Whom to tell about an assignment: the added users, each once, never the one who did it. */
    fun assignedTargets(added: Collection<UUID>, byUserId: UUID?): List<UUID> = added.distinct().filter { it != byUserId }

    /** Responsibles and reviewer of the task, each once, without the author of the comment. */
    fun commentRecipients(task: Task, authorId: UUID?): List<UUID> = (task.assignees + listOfNotNull(task.reviewerId)).distinct().filter { it != authorId }

    /** Responsibles of the task without the user who changed its state. */
    fun assigneeRecipients(task: Task, byUserId: UUID?): List<UUID> = task.assignees.distinct().filter { it != byUserId }

    fun assigned(tenant: Connection, notifier: ModuleNotifier?, task: Task, added: Collection<UUID>, byUserId: UUID?) =
        send(tenant, notifier, assignedTargets(added, byUserId), task, AssignedTitle) { task.title }

    /** The task went to review: its reviewer is told. */
    fun sentToReview(tenant: Connection, notifier: ModuleNotifier?, task: Task, byUserId: UUID?) {
        val reviewer = task.reviewerId?.takeIf { it != byUserId } ?: return
        send(tenant, notifier, listOf(reviewer), task, ReviewTitle) { task.title }
    }

    /** The reviewer sent it back to "En curso": its responsibles are told. */
    fun returned(tenant: Connection, notifier: ModuleNotifier?, task: Task, byUserId: UUID?) =
        send(tenant, notifier, assigneeRecipients(task, byUserId), task, ReturnedTitle) { task.title }

    /** The reviewer approved it (Hecha): its responsibles are told. */
    fun approved(tenant: Connection, notifier: ModuleNotifier?, task: Task, byUserId: UUID?) =
        send(tenant, notifier, assigneeRecipients(task, byUserId), task, ApprovedTitle) { task.title }

    fun commented(tenant: Connection, notifier: ModuleNotifier?, task: Task, authorId: UUID?, authorName: String, body: String) {
        val excerpt = body.trim().replace(Regex("\\s+"), " ").let { if (it.length > ExcerptLength) it.take(ExcerptLength - 1) + "…" else it }
        send(tenant, notifier, commentRecipients(task, authorId), task, CommentedTitle) { tr("{0} en «{1}»: {2}", authorName, task.title, excerpt) }
    }

    /**
     * Job body: tasks due tomorrow and overdue tasks notify each responsible once per due date (the reminder row is
     * claimed first; only whoever inserted it sends). Returns how many notices were created.
     */
    fun dueReminders(tenant: Connection, notifier: ModuleNotifier?, today: LocalDate): Int {
        if (notifier == null) return 0
        var sent = 0
        TareasRepository.reminderCandidates(tenant, today, 200).forEach { candidate ->
            // The notice uses the same connection: if it fails, the claim is rolled back and retried on the next run.
            runCatching {
                TareasRepository.transaction(tenant) {
                    if (TareasRepository.claimReminder(tenant, candidate.taskId, candidate.userId, candidate.kind, candidate.dueDate)) {
                        val overdue = candidate.kind == "overdue"
                        // The notice is written in the language of its recipient (the job itself runs in Spanish).
                        notifier.notify(tenant, candidate.userId, target(candidate.taskId)) {
                            NotificationText(
                                tr(if (overdue) OverdueTitle else DueSoonTitle),
                                tr("{0} · {1} · vence el {2}", candidate.title, candidate.boardName, candidate.dueDate),
                            )
                        }
                        sent++
                    }
                }
            }.onFailure { log.warn("No se pudo crear un aviso de vencimiento de tarea ({}).", it.javaClass.simpleName) }
        }
        return sent
    }

    private fun send(tenant: Connection, notifier: ModuleNotifier?, users: Collection<UUID>, task: Task, title: String, text: () -> String) {
        if (notifier == null || users.isEmpty()) return
        users.forEach { userId ->
            runCatching { notifier.notify(tenant, userId, target(task.id)) { NotificationText(tr(title), text().take(1000)) } }
                .onFailure { log.warn("No se pudo crear el aviso \"{}\" de una tarea ({}).", title, it.javaClass.simpleName) }
        }
    }
}
