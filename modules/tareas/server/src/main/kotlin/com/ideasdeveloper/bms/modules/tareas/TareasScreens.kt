package com.ideasdeveloper.bms.modules.tareas

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiAction
import com.ideasdeveloper.bms.ui.UiBadge
import com.ideasdeveloper.bms.ui.UiBarChart
import com.ideasdeveloper.bms.ui.UiBoard
import com.ideasdeveloper.bms.ui.UiBoardCard
import com.ideasdeveloper.bms.ui.UiBoardColumn
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiChartPoint
import com.ideasdeveloper.bms.ui.UiColumn
import com.ideasdeveloper.bms.ui.UiDivider
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiFieldValues
import com.ideasdeveloper.bms.ui.UiFileRef
import com.ideasdeveloper.bms.ui.UiFiles
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiGrid
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiListItem
import com.ideasdeveloper.bms.ui.UiMetric
import com.ideasdeveloper.bms.ui.UiNavItem
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNavigation
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiNotice
import com.ideasdeveloper.bms.ui.UiOpenModule
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiScreen
import com.ideasdeveloper.bms.ui.UiStatus
import com.ideasdeveloper.bms.ui.UiSuggest
import com.ideasdeveloper.bms.ui.UiTable
import com.ideasdeveloper.bms.ui.UiTableColumn
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTimeline
import com.ideasdeveloper.bms.ui.UiTimelineEntry
import com.ideasdeveloper.bms.ui.UiTone
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** A button that moves a task to [status]; [confirm] is asked first when set. */
data class StatusAction(val label: String, val status: TaskStatus, val style: String = "primary", val confirm: String? = null)

/**
 * Native screens of the module. Every builder is pure: it receives data already loaded (and filtered by the scope) and
 * returns a [UiScreen]; TareasRoutes loads the data and TareasActions parses what the forms send.
 */
object TareasScreens {
    const val Route: String = TareasModule.UiRoute
    const val ListLimit = 200
    const val KanbanLimit = 50
    const val ViewList = "list"
    const val ViewKanban = "kanban"
    const val ViewAgenda = "agenda"
    const val ViewWorkload = "workload"
    const val AgendaWeeks = 8
    const val CustomerSuggestRoute = "$Route/suggest/customers"
    private val RetiredUser = msg("Usuario retirado")
    private val Views = listOf(ViewList to msg("Lista"), ViewKanban to msg("Kanban"), ViewAgenda to msg("Agenda"), ViewWorkload to msg("Carga"))
    private val StampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Labels of the assignable users by id. */
    class UserNames(users: List<TareasUser>) {
        private val byId = users.associate { it.id to it.label }
        fun label(id: UUID): String = byId[id] ?: tr(RetiredUser)
        fun labels(ids: Collection<UUID>): List<String> = ids.map(::label)
    }

    /** What every screen needs: who looks ([scope]), the assignable [users], today and whether the user may see customers. */
    data class Context(
        val scope: TareasScope,
        val users: List<TareasUser>,
        val today: LocalDate,
        val canSeeCustomers: Boolean = false,
        // The user holds tareas.import and some import source is active: the "Importar" item shows.
        val importEnabled: Boolean = false,
        val zone: ZoneId = ZoneId.systemDefault(),
        // Board and group names are company configuration: original name -> translation for the viewer (see TareasRoutes.withNames).
        val nameLabels: Map<String, String> = emptyMap(),
    ) {
        val names: UserNames = UserNames(users)

        /** The name as shown to this viewer; the value that is posted back stays the original. */
        fun shown(name: String): String = nameLabels[name] ?: name

        fun shownOrNull(name: String?): String? = name?.let { shown(it) }
    }

    /**
     * Data of a board screen: [tasks] for list and agenda (loaded with one extra row over [ListLimit] to know it was cut),
     * [columns] for the kanban (one extra row over [KanbanLimit] per state) and [workload] for the load view.
     */
    data class BoardData(
        val board: Board,
        val groups: List<BoardGroup>,
        val tasks: List<Task> = emptyList(),
        val columns: Map<TaskStatus, List<Task>> = emptyMap(),
        val workload: List<WorkloadRow> = emptyList(),
    )

    data class TaskDetail(
        val task: Task,
        val groups: List<BoardGroup>,
        val checklist: List<ChecklistItem>,
        val comments: List<TaskComment>,
        val activity: List<TaskActivity>,
        val attachments: List<TaskAttachment>,
        val customerName: String? = null,
        // Translation of each comment body (same order as [comments]); null or missing: nothing to show under it.
        val commentTranslations: List<String?> = emptyList(),
    )

    // ---- tones and badges ----

    fun statusTone(status: TaskStatus): String = when (status) {
        TaskStatus.Review -> UiTone.Info
        TaskStatus.Done -> UiTone.Success
        else -> UiTone.Neutral
    }

    fun priorityTone(priority: TaskPriority): String = when (priority) {
        TaskPriority.Urgent -> UiTone.Danger
        TaskPriority.High -> UiTone.Warning
        else -> UiTone.Neutral
    }

    fun taskBadges(task: Task, today: LocalDate, withStatus: Boolean = true): List<UiBadge> = buildList {
        if (task.isOverdue(today)) add(UiBadge(tr("Vencida"), UiTone.Danger))
        if (task.priority != TaskPriority.Medium) add(UiBadge(task.priority.text(), priorityTone(task.priority)))
        if (withStatus) add(UiBadge(tr(task.status.label), statusTone(task.status)))
    }

    // ---- navigation ----

    fun navigation(scope: TareasScope, selected: String, import: Boolean = false): UiNavigation = UiNavigation(
        title = tr(TareasModule.displayName),
        items = listOfNotNull(
            UiNavItem("mine", tr("Mis tareas"), UiNavigate("mine")),
            UiNavItem("boards", tr("Tableros"), UiNavigate("boards")),
            UiNavItem("admin", tr("Administrar"), UiNavigate("admin")).takeIf { scope.admin },
            UiNavItem("importar", tr("Importar"), UiNavigate("importar")).takeIf { import },
        ),
        selected = selected,
    )

    private fun screen(context: Context, title: String, selected: String, notice: UiNotice?, body: List<UiNode>) =
        UiScreen(title = title, navigation = navigation(context.scope, selected, context.importEnabled), notice = notice, body = body)

    private fun openTask(task: Task): UiAction = UiNavigate("task", mapOf("id" to task.id.toString()))

    private fun taskItem(context: Context, task: Task, withBoard: Boolean = true): UiListItem = UiListItem(
        title = task.title,
        subtitle = listOfNotNull(
            context.shown(task.boardName).takeIf { withBoard },
            context.shownOrNull(task.groupName)?.takeIf { it.isNotBlank() },
            task.assignees.takeIf { it.isNotEmpty() }?.let { context.names.labels(it).joinToString(", ") },
        ).joinToString(" · "),
        trailing = task.dueDate?.toString().orEmpty(),
        badges = taskBadges(task, context.today),
        action = openTask(task),
    )

    private fun hint(text: String, tone: String? = null) = UiText(text, UiTextStyle.Caption, tone)

    // ---- Mis tareas ----

    /** Open tasks of the user grouped by due date; empty groups are left out. */
    fun mineBuckets(tasks: List<Task>, today: LocalDate): List<Pair<String, List<Task>>> {
        val open = tasks.filter { it.status.isOpen }
        val week = today.plusDays(7)
        return listOf(
            tr("Vencidas") to open.filter { it.isOverdue(today) },
            tr("Hoy") to open.filter { it.dueDate == today },
            tr("Próximos 7 días") to open.filter { val due = it.dueDate; due != null && due.isAfter(today) && !due.isAfter(week) },
            tr("Más adelante") to open.filter { it.dueDate?.isAfter(week) == true },
            tr("Sin fecha") to open.filter { it.dueDate == null },
        ).filter { it.second.isNotEmpty() }
    }

    fun mine(context: Context, tasks: List<Task>, notice: UiNotice? = null): UiScreen {
        val ordered = tasks.distinctBy { it.id }
            .sortedWith(compareBy<Task>({ it.dueDate == null }, { it.dueDate }, { it.priority.ordinal }, { it.title.lowercase() }))
        val shown = ordered.take(ListLimit)
        val me = context.scope.userId
        val overdue = shown.count { it.isOverdue(context.today) }
        val toReview = shown.count { it.status == TaskStatus.Review && me != null && it.reviewerId == me }
        val body = buildList<UiNode> {
            add(
                UiGrid(
                    listOf(
                        UiCard(listOf(UiMetric(tr("Abiertas"), shown.size.toString()))),
                        UiCard(
                            listOf(
                                UiMetric(
                                    tr("Vencidas"), overdue.toString(),
                                    caption = if (overdue > 0) tr("Atiéndelas primero") else tr("Al día"),
                                    captionTone = if (overdue > 0) UiTone.Danger else UiTone.Success,
                                ),
                            ),
                        ),
                        UiCard(listOf(UiMetric(tr("Por revisar"), toReview.toString(), captionTone = if (toReview > 0) UiTone.Info else UiTone.Neutral))),
                    ),
                    minItemWidth = 180,
                ),
            )
            if (ordered.size > ListLimit) add(hint(tr("Se muestran las primeras {0} tareas, las más próximas a vencer.", ListLimit), UiTone.Warning))
            val buckets = mineBuckets(shown, context.today)
            if (buckets.isEmpty()) {
                add(
                    UiCard(
                        listOf(
                            UiText(if (me == null) tr("Esta cuenta no recibe tareas: abre un tablero para verlas.") else tr("No tienes tareas abiertas.")),
                            UiButton(tr("Ver tableros"), UiNavigate("boards"), style = "secondary"),
                        ),
                    ),
                )
            } else {
                buckets.forEach { (title, group) -> add(UiCard(listOf(UiList(group.map { taskItem(context, it) })), title = tr("{0} ({1})", title, group.size))) }
            }
        }
        return screen(context, tr("Mis tareas"), "mine", notice, body)
    }

    // ---- Tableros ----

    private fun boardOpen(board: Board): UiAction = UiNavigate("board", mapOf("id" to board.id.toString(), "view" to ViewList))

    fun boards(context: Context, boards: List<Board>, notice: UiNotice? = null): UiScreen {
        val body = buildList<UiNode> {
            if (context.scope.admin) add(UiRow(listOf(UiButton(tr("Nuevo tablero"), UiNavigate("board-edit")))))
            if (boards.isEmpty()) {
                add(UiCard(listOf(UiText(if (context.scope.admin) tr("Aún no hay tableros: crea el primero.") else tr("No hay tableros disponibles para ti.")))))
            } else {
                add(
                    UiGrid(
                        boards.map { board ->
                            UiCard(
                                children = listOfNotNull(
                                    board.description.takeIf { it.isNotBlank() }?.let { hint(it) },
                                    UiText(tr("{0} abiertas", board.openTasks)),
                                    UiText(tr("{0} vencidas", board.overdueTasks), tone = UiTone.Danger).takeIf { board.overdueTasks > 0 },
                                    hint(tr("Privado: solo sus miembros")).takeIf { board.visibility == BoardVisibility.Private },
                                ),
                                title = context.shown(board.name),
                                actions = listOf(UiButton(tr("Abrir"), boardOpen(board), style = "secondary")),
                            )
                        },
                        minItemWidth = 240,
                    ),
                )
            }
        }
        return screen(context, tr("Tableros"), "boards", notice, body)
    }

    // ---- Board ----

    /** The view asked for; anything unknown is the list. */
    fun viewOf(params: Map<String, String>): String = Views.firstOrNull { it.first == params["view"] }?.first ?: ViewList

    private fun filterForm(context: Context, board: Board, view: String, params: Map<String, String>): UiNode {
        val people = listOf(TareasActions.AllOption) + context.users.map { it.label }
        val fields = buildList {
            add(UiField("q", tr("Buscar"), placeholder = tr("Título o detalle")))
            add(UiField("assignee", tr("Responsable"), type = "dropdown", options = people))
            if (view != ViewKanban) add(UiField("status", tr("Estado"), type = "dropdown", options = listOf(TareasActions.AllOption) + TaskStatus.entries.map { it.label }))
            add(UiField("due", tr("Vencimiento"), type = "dropdown", options = listOf(TareasActions.AllOption) + DueFilter.entries.map { it.label }))
        }
        val values = fields.associate { field ->
            field.key to (params[field.key]?.takeIf { it.isNotBlank() } ?: if (field.type == "dropdown") TareasActions.AllOption else "")
        }
        return UiCard(
            listOf(
                UiForm(
                    "filters", fields, values,
                    submit = UiButton(tr("Filtrar"), UiNavigate("board", mapOf("id" to board.id.toString(), "view" to view), formId = "filters"), style = "secondary"),
                    minFieldWidth = 200,
                ),
            ),
        )
    }

    fun board(context: Context, data: BoardData, params: Map<String, String>, notice: UiNotice? = null): UiScreen {
        val board = data.board
        val view = viewOf(params)
        val withFilters = view != ViewWorkload
        val selector = UiRow(
            Views.map { (id, label) ->
                UiButton(
                    tr(label),
                    UiNavigate("board", mapOf("id" to board.id.toString(), "view" to id), formId = if (withFilters) "filters" else null),
                    style = if (id == view) "primary" else "secondary",
                )
            } + listOfNotNull(
                UiButton(tr("Nueva tarea"), UiNavigate("new-task", mapOf("board" to board.id.toString()))).takeIf { context.scope.canManage && !board.archived },
                UiButton(tr("Editar tablero"), UiNavigate("board-edit", mapOf("id" to board.id.toString())), style = "secondary").takeIf { context.scope.admin },
            ),
        )
        val body = buildList<UiNode> {
            if (board.description.isNotBlank()) add(hint(board.description))
            add(selector)
            if (withFilters) add(filterForm(context, board, view, params))
            addAll(
                when (view) {
                    ViewKanban -> kanbanView(context, data, params)
                    ViewAgenda -> agendaView(context, data)
                    ViewWorkload -> workloadView(context, data)
                    else -> listView(context, data)
                },
            )
        }
        return screen(context, context.shown(board.name), "boards", notice, body)
    }

    private fun cutNotice(tasks: List<Task>): UiNode? =
        if (tasks.size > ListLimit) hint(tr("Se muestran las primeras {0} tareas: usa los filtros para acotar.", ListLimit), UiTone.Warning) else null

    private fun listView(context: Context, data: BoardData): List<UiNode> {
        val shown = data.tasks.take(ListLimit)
        val groups = data.groups.filter { !it.archived }
        val canAdd = context.scope.canManage && !data.board.archived
        fun add(groupId: UUID?) = UiButton(
            tr("Nueva tarea"),
            UiNavigate("new-task", buildMap { put("board", data.board.id.toString()); groupId?.let { put("group", it.toString()) } }),
            style = "secondary",
        ).takeIf { canAdd }
        val ids = groups.map { it.id }.toSet()
        val loose = shown.filter { it.groupId == null || it.groupId !in ids }
        val cards = groups.map { group ->
            val tasks = shown.filter { it.groupId == group.id }
            UiCard(
                listOf(UiList(tasks.map { taskItem(context, it, withBoard = false) }, emptyText = tr("Sin tareas en este grupo."))),
                title = tr("{0} ({1})", context.shown(group.name), tasks.size),
                actions = listOfNotNull(add(group.id)),
            )
        } + listOfNotNull(
            UiCard(
                listOf(UiList(loose.map { taskItem(context, it, withBoard = false) }, emptyText = tr("No hay tareas con estos filtros."))),
                title = tr("{0} ({1})", tr(TareasActions.NoGroup), loose.size),
                actions = listOfNotNull(add(null)),
            ).takeIf { loose.isNotEmpty() || groups.isEmpty() },
        )
        return listOfNotNull(cutNotice(data.tasks)) + cards
    }

    private fun kanbanView(context: Context, data: BoardData, params: Map<String, String>): List<UiNode> {
        val keep = TareasActions.filterParams(params) - "status"
        val move = UiRequest(
            "$Route/board",
            params = keep + mapOf("id" to data.board.id.toString(), "view" to ViewKanban, "op" to "move"),
        )
        val columns = TaskStatus.entries.map { status ->
            val tasks = data.columns[status].orEmpty()
            UiBoardColumn(
                id = status.code,
                title = tr(status.label),
                tone = statusTone(status),
                caption = if (tasks.size > KanbanLimit) tr("Mostrando {0}: usa los filtros para ver el resto", KanbanLimit) else "",
                cards = tasks.take(KanbanLimit).map { task ->
                    UiBoardCard(
                        id = task.id.toString(),
                        title = task.title,
                        subtitle = context.shownOrNull(task.groupName).orEmpty(),
                        details = listOfNotNull(
                            task.assignees.takeIf { it.isNotEmpty() }?.let { tr("Responsables: {0}", context.names.labels(it).joinToString(", ")) },
                            task.dueDate?.let { tr("Vence: {0}", it) },
                            task.checklistTotal.takeIf { it > 0 }?.let { tr("Lista: {0}/{1}", task.checklistDone, it) },
                        ),
                        badges = taskBadges(task, context.today, withStatus = false),
                        action = openTask(task),
                    )
                },
            )
        }
        return listOf(UiBoard(columns, move = move, emptyText = tr("Sin tareas.")))
    }

    /** Start (Monday) of the ISO week of [date]. */
    private fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Agenda groups in order: overdue, earlier closed ones, one per ISO week from this week to [AgendaWeeks] weeks ahead,
     * later, and without date. Empty groups are left out.
     */
    fun agendaBuckets(tasks: List<Task>, today: LocalDate): List<Pair<String, List<Task>>> {
        val thisWeek = weekStart(today)
        fun key(task: Task): Pair<Int, String> {
            val due = task.dueDate ?: return 200 to tr("Sin fecha")
            if (task.isOverdue(today)) return 0 to tr("Vencidas")
            if (due.isBefore(thisWeek)) return 1 to tr("Anteriores")
            val weeks = ChronoUnit.WEEKS.between(thisWeek, weekStart(due)).toInt()
            if (weeks > AgendaWeeks) return 100 to tr("Más adelante")
            val start = thisWeek.plusWeeks(weeks.toLong())
            return 10 + weeks to tr("Semana {0}: {1} a {2}", start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), start, start.plusDays(6))
        }
        return tasks.groupBy { key(it) }.entries.sortedBy { it.key.first }.map { it.key.second to it.value }
    }

    private fun agendaView(context: Context, data: BoardData): List<UiNode> {
        val shown = data.tasks.take(ListLimit)
        val buckets = agendaBuckets(shown, context.today)
        val cards = if (buckets.isEmpty()) {
            listOf(UiCard(listOf(UiText(tr("No hay tareas con estos filtros.")))))
        } else {
            buckets.map { (title, tasks) -> UiCard(listOf(UiList(tasks.map { taskItem(context, it, withBoard = false) })), title = tr("{0} ({1})", title, tasks.size)) }
        }
        return listOfNotNull(cutNotice(data.tasks)) + cards
    }

    private fun workloadView(context: Context, data: BoardData): List<UiNode> {
        if (data.workload.isEmpty()) return listOf(UiCard(listOf(UiText(tr("Nadie tiene tareas abiertas en este tablero.")))))
        val rows = data.workload.sortedByDescending { it.open }
        return listOf(
            UiCard(
                listOf(UiBarChart(rows.map { UiChartPoint(context.names.label(it.userId), it.open.toDouble()) })),
                title = tr("Tareas abiertas por responsable"),
            ),
            UiCard(
                listOf(
                    UiTable(
                        columns = listOf(UiTableColumn("person", tr("Persona")), UiTableColumn("open", tr("Abiertas")), UiTableColumn("overdue", tr("Vencidas")), UiTableColumn("review", tr("En revisión"))),
                        rows = rows.map {
                            mapOf("person" to context.names.label(it.userId), "open" to it.open.toString(), "overdue" to it.overdue.toString(), "review" to it.review.toString())
                        },
                        emptyText = tr("Sin datos."),
                    ),
                ),
                title = tr("Carga por persona"),
            ),
        )
    }

    // ---- Task form (shared by "task" and "new-task") ----

    /** Labels of the priority dropdown (null in Spanish: the options already are the labels). */
    private fun priorityLabels(): List<String>? =
        if (I18n.language() == I18n.Source) null else TaskPriority.entries.map { it.text() }

    private fun groupOptions(groups: List<BoardGroup>): List<String> = listOf(TareasActions.NoGroup) + groups.filter { !it.archived }.map { it.name }

    /** What the dropdown shows for each group option (names are company configuration, translated); null when nothing differs. */
    private fun groupLabels(context: Context, groups: List<BoardGroup>): List<String>? {
        val options = groupOptions(groups)
        val labels = listOf(tr(TareasActions.NoGroup)) + groups.filter { !it.archived }.map { context.shown(it.name) }
        return labels.takeIf { it != options }
    }

    private val StatusChange = Regex("^(.+) → (.+?)( \\(enviada a revisión\\))?$")

    /** The activity rows are stored in Spanish: the generic ones are translated when shown, the rest stay as written. */
    fun activityText(detail: String): String {
        val change = StatusChange.matchEntire(detail)
        if (change != null) {
            val from = TaskStatus.fromLabel(change.groupValues[1])
            val to = TaskStatus.fromLabel(change.groupValues[2])
            if (from != null && to != null) {
                val text = tr("{0} → {1}", tr(from.label), tr(to.label))
                return if (change.groupValues[3].isNotEmpty()) text + " " + tr("(enviada a revisión)") else text
            }
        }
        if (detail.startsWith("Importada desde ")) return tr("Importada desde {0}", detail.removePrefix("Importada desde "))
        return tr(detail)
    }

    private fun fileRefs(attachments: List<TaskAttachment>): List<UiFileRef> = attachments.map { UiFileRef(it.id.toString(), it.name, it.path, it.size) }

    private fun filesField(readOnly: Boolean) = UiField(
        "files", tr("Adjuntos"), type = "files", readOnly = readOnly,
        files = UiFiles(upload = "$Route/files", download = "$Route/files"),
    )

    /** Fields of the task form; the customer needs [withCustomer] (clientes.view) and the files [withFiles]. */
    fun taskFields(context: Context, groups: List<BoardGroup>, readOnly: Boolean, withFiles: Boolean = true): List<UiField> = buildList {
        val people = context.users.map { it.label }
        add(UiField("title", tr("Título"), required = true, readOnly = readOnly))
        add(UiField("detail", tr("Detalle"), type = "textarea", readOnly = readOnly))
        add(UiField("priority", tr("Prioridad"), type = "dropdown", options = TaskPriority.entries.map { it.label }, optionLabels = priorityLabels(), readOnly = readOnly))
        add(UiField("due", tr("Fecha límite"), type = "date", readOnly = readOnly))
        add(UiField("group", tr("Grupo"), type = "dropdown", options = groupOptions(groups), optionLabels = groupLabels(context, groups), readOnly = readOnly))
        add(UiField("assignees", tr("Responsables"), type = "dropdown", multiple = true, options = people, readOnly = readOnly))
        add(UiField("reviewer", tr("Revisor"), type = "dropdown", options = listOf(TareasActions.NoReviewer) + people, readOnly = readOnly))
        if (context.canSeeCustomers) {
            add(
                UiField(
                    "customer", tr("Cliente"), readOnly = readOnly, placeholder = tr("Escribe para buscar"),
                    help = tr("Elige un cliente de la lista de sugerencias."),
                    suggest = UiSuggest(CustomerSuggestRoute),
                ),
            )
        }
        if (withFiles) add(filesField(readOnly))
    }

    private fun taskValues(context: Context, groups: List<BoardGroup>, task: Task?, detail: TaskDetail?): Map<String, String> = buildMap {
        put("title", task?.title.orEmpty())
        put("detail", task?.detail.orEmpty())
        put("priority", (task?.priority ?: TaskPriority.Medium).label)
        put("due", task?.dueDate?.toString().orEmpty())
        put("group", task?.groupId?.let { id -> groups.firstOrNull { it.id == id }?.name }.orEmpty().ifBlank { TareasActions.NoGroup })
        put("assignees", UiFieldValues.options(task?.assignees.orEmpty().filter { id -> context.users.any { it.id == id } }.map { context.names.label(it) }))
        put("reviewer", task?.reviewerId?.takeIf { id -> context.users.any { it.id == id } }?.let { context.names.label(it) } ?: TareasActions.NoReviewer)
        if (context.canSeeCustomers && task?.customerId != null) {
            put("customer", detail?.customerName ?: tr("Cliente no disponible"))
            put("customer_id", task.customerId.toString())
        }
        if (detail != null) put("files", UiFieldValues.files(fileRefs(detail.attachments)))
    }

    private fun sectionTitle(text: String) = UiText(text, UiTextStyle.Subtitle)

    // ---- Task ----

    /** Buttons to change the status that [scope] may use on [task]. The service still applies the rules. */
    fun statusActions(scope: TareasScope, task: Task): List<StatusAction> {
        if (task.archivedAt != null || !scope.canWorkOnTask(task)) return emptyList()
        val finish = if (task.reviewerId != null && !scope.canReview(task)) {
            StatusAction(tr("Enviar a revisión"), TaskStatus.Review)
        } else {
            StatusAction(tr("Marcar hecha"), TaskStatus.Done)
        }
        val main = when (task.status) {
            TaskStatus.Pending -> listOf(StatusAction(tr("Empezar"), TaskStatus.InProgress, style = "secondary"), finish)
            TaskStatus.InProgress -> listOf(finish)
            TaskStatus.Review -> if (scope.canReview(task)) {
                listOf(StatusAction(tr("Aprobar"), TaskStatus.Done), StatusAction(tr("Devolver"), TaskStatus.InProgress, style = "secondary"))
            } else {
                emptyList()
            }
            TaskStatus.Done, TaskStatus.Cancelled -> listOf(StatusAction(tr("Reabrir"), TaskStatus.InProgress, style = "secondary"))
        }
        val cancel = StatusAction(tr("Cancelar tarea"), TaskStatus.Cancelled, style = "danger", confirm = tr("¿Cancelar esta tarea?"))
        return main + listOfNotNull(cancel.takeIf { task.status.isOpen && scope.canEditTask(task) })
    }

    private fun taskRequest(op: String, task: Task, extra: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest("$Route/task", params = mapOf("op" to op, "id" to task.id.toString()) + extra, confirm = confirm, formId = formId)

    fun stamp(instant: Instant, zone: ZoneId): String = StampFormat.withZone(zone).format(instant)

    fun task(context: Context, detail: TaskDetail, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val task = detail.task
        val scope = context.scope
        // An archived task (only administrators open it) is read-only: no state, form or checklist changes.
        val archived = task.archivedAt != null
        val canEdit = scope.canEditTask(task) && !archived
        val canWork = scope.canWorkOnTask(task) && !archived
        val boardLink = UiButton(tr("Volver al tablero"), UiNavigate("board", mapOf("id" to task.boardId.toString(), "view" to ViewList)), style = "secondary")
        val statusButtons = statusActions(scope, task).map { action ->
            UiButton(action.label, taskRequest("status", task, mapOf("status" to action.status.code), confirm = action.confirm), style = action.style)
        }
        val actions = UiRow(
            statusButtons + listOfNotNull(
                UiButton(tr("Archivar"), taskRequest("archive", task, confirm = tr("¿Archivar esta tarea?")), style = "danger").takeIf { canEdit },
                UiButton(tr("Abrir cliente"), UiOpenModule("clientes", "customer", mapOf("id" to task.customerId.toString())), style = "secondary")
                    .takeIf { context.canSeeCustomers && task.customerId != null },
                boardLink,
            ),
        )
        val header = UiRow(
            listOfNotNull(
                UiStatus(tr(task.status.label), statusTone(task.status)),
                UiStatus(task.priority.text(), priorityTone(task.priority)),
                UiStatus(tr("Vencida"), UiTone.Danger).takeIf { task.isOverdue(context.today) },
                hint(listOfNotNull(context.shown(task.boardName), context.shownOrNull(task.groupName)?.takeIf { it.isNotBlank() }, task.dueDate?.let { tr("Vence {0}", it) }).joinToString(" · ")),
            ),
            spacing = 8,
        )
        // A person who only works the task (not edit) keeps the form read-only and attaches files apart.
        val workerOnly = !canEdit && canWork
        val values = taskValues(context, detail.groups, task, detail) + (submitted.orEmpty())
        val form = UiForm(
            id = "task",
            fields = taskFields(context, detail.groups, readOnly = !canEdit, withFiles = !workerOnly),
            values = values,
            submit = UiButton(tr("Guardar"), taskRequest("save", task, formId = "task")).takeIf { canEdit },
        )
        val body = buildList<UiNode> {
            if (archived) add(hint(tr("Tarea archivada: solo lectura."), UiTone.Warning))
            add(header)
            add(actions)
            add(UiCard(listOf(form), title = tr("Detalles")))
            if (workerOnly) {
                add(
                    UiCard(
                        listOf(
                            UiForm(
                                id = "attachments",
                                fields = listOf(filesField(readOnly = false)),
                                values = mapOf("files" to UiFieldValues.files(fileRefs(detail.attachments))),
                                submit = UiButton(tr("Guardar adjuntos"), taskRequest("files", task, formId = "attachments"), style = "secondary"),
                            ),
                        ),
                        title = tr("Adjuntos"),
                    ),
                )
            }
            add(checklistCard(task, detail.checklist, canWork, canEdit))
            add(commentsCard(context, task, detail.comments, detail.commentTranslations, canWork))
            add(
                UiCard(
                    listOf(
                        UiTimeline(
                            detail.activity.map { UiTimelineEntry(activityText(it.detail.ifBlank { it.kind }), caption = listOf(it.authorName, stamp(it.createdAt, context.zone)).filter { part -> part.isNotBlank() }.joinToString(" - ")) },
                            emptyText = tr("Sin actividad."),
                        ),
                    ),
                    title = tr("Actividad"),
                    collapsible = true,
                    expanded = false,
                ),
            )
        }
        return screen(context, task.title, "boards", notice, body)
    }

    private fun checklistCard(task: Task, items: List<ChecklistItem>, canWork: Boolean, canEdit: Boolean): UiNode {
        val rows = items.map { item ->
            UiRow(
                listOfNotNull(
                    UiButton(
                        (if (item.done) "[x] " else "[ ] ") + item.text,
                        taskRequest("check-toggle", task, mapOf("item" to item.id.toString(), "done" to (!item.done).toString())),
                        style = "secondary",
                        enabled = canWork,
                    ),
                    UiButton(tr("Quitar"), taskRequest("check-remove", task, mapOf("item" to item.id.toString())), style = "danger").takeIf { canEdit },
                ),
                spacing = 8,
            )
        }
        val children = buildList<UiNode> {
            if (rows.isEmpty()) add(hint(tr("Sin elementos.")))
            addAll(rows)
            if (canWork) {
                add(
                    UiForm(
                        id = "check",
                        fields = listOf(UiField("text", tr("Nuevo elemento"), required = true)),
                        submit = UiButton(tr("Añadir"), taskRequest("check-add", task, formId = "check"), style = "secondary"),
                    ),
                )
            }
        }
        val title = if (task.checklistTotal > 0) tr("Lista de comprobación ({0}/{1})", task.checklistDone, task.checklistTotal) else tr("Lista de comprobación")
        return UiCard(children, title = title)
    }

    private fun commentsCard(context: Context, task: Task, comments: List<TaskComment>, translations: List<String?>, canWork: Boolean): UiNode {
        val children = buildList<UiNode> {
            add(
                UiTimeline(
                    comments.mapIndexed { index, it -> UiTimelineEntry(it.authorName, it.body, stamp(it.createdAt, context.zone), translation = translations.getOrNull(index)) },
                    emptyText = tr("Aún no hay comentarios."),
                ),
            )
            if (canWork) {
                add(
                    UiForm(
                        id = "comment",
                        fields = listOf(UiField("body", tr("Comentario"), type = "textarea", required = true)),
                        submit = UiButton(tr("Comentar"), taskRequest("comment", task, formId = "comment"), style = "secondary"),
                    ),
                )
            }
        }
        return UiCard(children, title = tr("Comentarios ({0})", comments.size))
    }

    // ---- New task ----

    /** [groupId] preselects a group (the "Nueva tarea" button of a group card). */
    fun newTask(context: Context, board: Board, groups: List<BoardGroup>, groupId: UUID?, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val base = taskValues(context, groups, null, null) +
            (groupId?.let { id -> groups.firstOrNull { it.id == id }?.name }?.let { mapOf("group" to it) } ?: emptyMap())
        val form = UiForm(
            id = "task",
            fields = taskFields(context, groups, readOnly = false),
            values = base + submitted.orEmpty(),
            submit = UiButton(tr("Crear tarea"), UiRequest("$Route/new-task", params = mapOf("op" to "create", "board" to board.id.toString()), formId = "task")),
        )
        val body = listOf(
            hint(tr("Tablero: {0}", context.shown(board.name))),
            UiCard(listOf(form), title = tr("Nueva tarea")),
            UiRow(listOf(UiButton(tr("Cancelar"), UiNavigate("board", mapOf("id" to board.id.toString(), "view" to ViewList)), style = "secondary"))),
        )
        return screen(context, tr("Nueva tarea"), "boards", notice, body)
    }

    // ---- Administrar ----

    fun admin(context: Context, boards: List<Board>, notice: UiNotice? = null): UiScreen {
        val items = boards.map { board ->
            UiListItem(
                title = context.shown(board.name),
                subtitle = tr("{0} abiertas · {1} vencidas", board.openTasks, board.overdueTasks) +
                    if (board.visibility == BoardVisibility.Private) " · " + tr("{0} miembros", board.members.size) else "",
                badges = listOfNotNull(
                    UiBadge(tr(board.visibility.label), if (board.visibility == BoardVisibility.Private) UiTone.Warning else UiTone.Neutral),
                    UiBadge(tr("Archivado"), UiTone.Neutral).takeIf { board.archived },
                ),
                action = UiNavigate("board-edit", mapOf("id" to board.id.toString())),
            )
        }
        val body = listOf(
            UiRow(listOf(UiButton(tr("Nuevo tablero"), UiNavigate("board-edit")))),
            UiCard(listOf(UiList(items, emptyText = tr("Aún no hay tableros."))), title = tr("Tableros")),
        )
        return screen(context, tr("Administrar"), "admin", notice, body)
    }

    // ---- Board edit ----

    /** [board] is null for a new board. */
    fun boardEdit(context: Context, board: Board?, groups: List<BoardGroup>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val idParams = board?.let { mapOf("id" to it.id.toString()) }.orEmpty()
        fun request(op: String, extra: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
            UiRequest("$Route/board-edit", params = mapOf("op" to op) + idParams + extra, confirm = confirm, formId = formId)

        val values = mapOf(
            "name" to board?.name.orEmpty(),
            "description" to board?.description.orEmpty(),
            "visibility" to (board?.visibility ?: BoardVisibility.Company).label,
            "members" to UiFieldValues.options(board?.members.orEmpty().filter { id -> context.users.any { it.id == id } }.map { context.names.label(it) }),
        ) + submitted.orEmpty()
        val form = UiForm(
            id = "board",
            fields = listOf(
                UiField("name", tr("Nombre"), required = true),
                UiField("description", tr("Descripción"), type = "textarea"),
                UiField("visibility", tr("Visibilidad"), type = "dropdown", options = BoardVisibility.entries.map { it.label }, help = tr("Un tablero privado solo lo ven sus miembros y quien administra tableros.")),
                UiField("members", tr("Miembros"), type = "dropdown", multiple = true, options = context.users.map { it.label }, help = tr("Solo cuenta en tableros privados.")),
            ),
            values = values,
            submit = UiButton(if (board == null) tr("Crear tablero") else tr("Guardar"), request("board-save", formId = "board")),
        )
        val body = buildList<UiNode> {
            add(UiCard(listOf(form), title = tr("Tablero")))
            if (board != null) {
                add(groupsCard(context, groups, board, ::request))
                add(
                    UiRow(
                        listOfNotNull(
                            UiButton(tr("Abrir tablero"), UiNavigate("board", mapOf("id" to board.id.toString(), "view" to ViewList)), style = "secondary"),
                            UiButton(tr("Archivar tablero"), request("board-archive", confirm = tr("¿Archivar este tablero? Sus tareas dejan de verse.")), style = "danger").takeIf { !board.archived },
                        ),
                    ),
                )
            }
        }
        return screen(context, context.shownOrNull(board?.name) ?: tr("Nuevo tablero"), "admin", notice, body)
    }

    private fun groupsCard(
        context: Context,
        groups: List<BoardGroup>,
        board: Board,
        request: (String, Map<String, String>, String?, String?) -> UiRequest,
    ): UiNode {
        val active = groups.filter { !it.archived }
        val rows = active.map { group ->
            val params = mapOf("group" to group.id.toString())
            UiColumn(
                listOf(
                    UiForm(
                        id = "group-${group.id}",
                        fields = listOf(UiField("name", tr("Nombre del grupo"), required = true)),
                        values = mapOf("name" to group.name),
                        submit = UiButton(tr("Renombrar"), request("group-rename", params, null, "group-${group.id}"), style = "secondary"),
                    ),
                    UiRow(
                        listOf(
                            UiButton(tr("Subir"), request("group-up", params, null, null), style = "secondary"),
                            UiButton(tr("Bajar"), request("group-down", params, null, null), style = "secondary"),
                            UiButton(tr("Archivar"), request("group-archive", params, tr("¿Archivar el grupo {0}? Sus tareas quedan sin grupo.", context.shown(group.name)), null), style = "danger"),
                        ),
                        spacing = 8,
                    ),
                ),
                spacing = 8,
            )
        }
        val children = buildList<UiNode> {
            if (rows.isEmpty()) add(hint(tr("Este tablero aún no tiene grupos (por ejemplo \"Esta semana\" o \"Próxima semana\").")))
            rows.forEachIndexed { index, row ->
                if (index > 0) add(UiDivider())
                add(row)
            }
            add(UiDivider())
            add(sectionTitle(tr("Nuevo grupo")))
            add(
                UiForm(
                    id = "group-new",
                    fields = listOf(UiField("name", tr("Nombre"), required = true)),
                    submit = UiButton(tr("Añadir grupo"), request("group-add", emptyMap(), null, "group-new"), style = "secondary"),
                ),
            )
        }
        return UiCard(children, title = tr("Grupos de {0}", context.shown(board.name)))
    }
}
