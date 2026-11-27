package com.ideasdeveloper.bms.modules.tareas

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.sql.Connection
import java.util.UUID

/**
 * Rows Tareas writes in the company activity log (ACTIVITY_LOG.md), always inside the transaction of the change: when the row
 * cannot be written the change fails with it. The histories of the module (tareas_activity) are written exactly as before.
 * Every text is Spanish plain text (stored language), never `tr`.
 */
object TareasLog {
    const val Module = TareasModule.id

    const val Task = "task"
    const val ChecklistItem = "checklist_item"
    const val Comment = "task_comment"
    const val Attachment = "task_attachment"
    const val Board = "board"
    const val Group = "board_group"
    const val Reminder = "task_reminder"
    const val Import = "task_import"

    /** Action of a reminder the job sent (not one of the common ids; the screens show it as written). */
    const val ReminderSent = "reminder_sent"

    val TaskLabels = linkedMapOf(
        "title" to "Titulo", "detail" to "Detalle", "dueDate" to "Fecha limite", "priority" to "Prioridad",
        "assignees" to "Responsables", "reviewer" to "Revisor", "customer" to "Cliente", "group" to "Grupo", "board" to "Tablero",
    )

    val BoardLabels = linkedMapOf("name" to "Nombre", "description" to "Descripcion", "visibility" to "Visibilidad", "members" to "Miembros")

    fun record(
        db: Connection,
        actor: ActivityActor,
        entityType: String,
        entityId: Any?,
        label: String,
        action: String,
        summary: String,
        changes: List<ActivityChange> = emptyList(),
    ) {
        ActivityLog.record(db, actor, ActivityEntry(Module, entityType, entityId?.toString(), label, action, summary, changes))
    }

    /** The fields of a task as the log compares them: people, group, board and customer by name. */
    fun taskValues(tenant: Connection, task: Task, users: List<TareasUser> = TareasRepository.users(tenant)): Map<String, Any?> {
        val names = users.associate { it.id to it.label }
        return mapOf(
            "title" to task.title,
            "detail" to task.detail.ifBlank { null },
            "dueDate" to task.dueDate?.toString(),
            "priority" to task.priority.label,
            "assignees" to task.assignees.map { names[it] ?: "Usuario" }.sorted().ifEmpty { null },
            "reviewer" to task.reviewerId?.let { names[it] ?: "Usuario" },
            "customer" to task.customerId?.let { TareasRepository.customerName(tenant, it) },
            "group" to task.groupName,
            "board" to task.boardName,
        )
    }

    /** A task created: every filled field as a new value. */
    fun taskCreated(tenant: Connection, actor: ActivityActor, task: Task, source: String, extra: List<ActivityChange> = emptyList()) {
        val changes = ActivityChanges.created(taskValues(tenant, task), TaskLabels) + extra
        val via = if (source == "manual") "" else " (origen $source)"
        record(tenant, actor, Task, task.id, task.title, ActivityActions.Created, "Creo la tarea ${task.title}$via.", changes)
    }

    /** A task edited: old -> new of each field of [TaskLabels] that changed; nothing is written when nothing changed. */
    fun taskUpdated(tenant: Connection, actor: ActivityActor, before: Task, after: Task) {
        val users = TareasRepository.users(tenant)
        val changes = ActivityChanges.diff(taskValues(tenant, before, users), taskValues(tenant, after, users), TaskLabels)
        if (changes.isEmpty()) return
        record(tenant, actor, Task, after.id, after.title, ActivityActions.Updated, "Modifico la tarea ${after.title}.", changes)
    }

    fun boardValues(board: Board, users: List<TareasUser>): Map<String, Any?> {
        val names = users.associate { it.id to it.label }
        return mapOf(
            "name" to board.name,
            "description" to board.description.ifBlank { null },
            "visibility" to board.visibility.label,
            "members" to board.members.map { names[it] ?: "Usuario" }.sorted().ifEmpty { null },
        )
    }

    /**
     * The active group of the board with that name or a new one ([TareasRepository.findOrCreateGroup]); a group that had to be created
     * is a change of the board and leaves its row, in the same transaction.
     */
    fun findOrCreateGroup(tenant: Connection, actor: ActivityActor, boardId: UUID, name: String): UUID {
        val before = TareasRepository.listGroups(tenant, boardId, includeArchived = true).map { it.id }.toSet()
        val id = TareasRepository.findOrCreateGroup(tenant, boardId, name)
        if (id !in before) {
            val group = TareasRepository.getGroup(tenant, id)
            val board = TareasRepository.getBoard(tenant, boardId)?.name.orEmpty()
            record(
                tenant, actor, Group, id, group?.name ?: name.trim(), ActivityActions.Created, "Creo el grupo ${group?.name ?: name.trim()} en el tablero $board.",
                listOf(ActivityChange.of("board", "Tablero", null, board), ActivityChange.of("name", "Nombre", null, group?.name ?: name.trim())),
            )
        }
        return id
    }

    /** A byte count as people read it ("24 KB"); only the size, never the content. */
    fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** The person who started an import, as the activity log names them (null when the user no longer exists). */
    fun starterOf(tenant: Connection, userId: UUID?): ActivityActor? {
        if (userId == null) return null
        return runCatching {
            tenant.prepareStatement(
                """
                SELECT COALESCE(NULLIF(trim(u.display_name), ''), u.postgres_role) AS name,
                       EXISTS (SELECT 1 FROM business_owner o WHERE o.user_id = u.id) AS owner
                FROM application_users u WHERE u.id = ?
                """.trimIndent()
            ).use { query ->
                query.setObject(1, userId)
                query.executeQuery().use { rows ->
                    if (!rows.next()) null
                    else if (rows.getBoolean("owner")) ActivityActor.businessOwner(userId, rows.getString("name")) else ActivityActor.user(userId, rows.getString("name"))
                }
            }
        }.getOrNull()
    }

    /**
     * The actor of an import: the starter [stored] with the job (ActivityActor.toStoredJson: an AI agent, a server owner...),
     * or for old jobs [startedBy] (the real person), or an anonymous import.
     */
    fun importer(tenant: Connection, startedBy: UUID?, stored: String? = null): ActivityActor =
        ActivityActor.restoreImport(stored) { starterOf(tenant, startedBy) }
}
