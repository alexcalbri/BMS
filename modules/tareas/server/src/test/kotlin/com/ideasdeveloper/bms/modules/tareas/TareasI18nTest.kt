package com.ideasdeveloper.bms.modules.tareas

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadModuleCatalog
import com.ideasdeveloper.bms.server.i18n.localized
import com.ideasdeveloper.bms.ui.UiBoard
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiTimeline
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TareasI18nTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val users = listOf(TareasUser(me, "Yo", "yo@example.com"), TareasUser(other, "Otro", "otro@example.com"))
    private val scope = TareasScope(me, admin = true, canManage = true, memberOf = emptySet())
    private val board = TareasFixtures.board().copy(name = "Operaciones", visibility = BoardVisibility.Private, members = listOf(me, other))
    private val group = BoardGroup(UUID.randomUUID(), board.id, "Esta semana", 0, false)

    // Tests build no ModuleRegistry, so the module's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadModuleCatalog(TareasServerModule)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertModule("tareas", TareasServerModule.definition)

    private fun context(labels: Map<String, String> = emptyMap()) =
        TareasScreens.Context(scope, users, today, canSeeCustomers = true, zone = ZoneOffset.UTC, nameLabels = labels)

    private fun texts(nodes: List<UiNode>): List<String> = nodes.flatMap { node ->
        when (node) {
            is UiButton -> listOf(node.label)
            is UiCard -> listOfNotNull(node.title) + texts(node.children + node.actions)
            is UiRow -> texts(node.children)
            is UiList -> node.items.map { it.title } + node.items.map { it.subtitle }
            else -> emptyList()
        }
    }

    private fun buttons(nodes: List<UiNode>): List<UiButton> = nodes.flatMap { node ->
        when (node) {
            is UiButton -> listOf(node)
            is UiCard -> buttons(node.children + node.actions)
            is UiRow -> buttons(node.children)
            else -> emptyList()
        }
    }

    private fun fieldsOf(nodes: List<UiNode>): List<UiField> = nodes.flatMap { node ->
        when (node) {
            is UiForm -> node.fields
            is UiCard -> fieldsOf(node.children)
            else -> emptyList()
        }
    }

    private fun task(status: TaskStatus = TaskStatus.Pending, priority: TaskPriority = TaskPriority.High) =
        TareasFixtures.task(board.id, status, listOf(me), dueDate = today.minusDays(1))
            .copy(title = "Revisar contrato", priority = priority, groupName = group.name, boardName = board.name, groupId = group.id)

    @Test
    fun mineScreenIsInEnglish() {
        val tasks = listOf(task())
        val spanish = TareasScreens.mine(context(), tasks)
        val english = I18n.withLanguage("en") { TareasScreens.mine(context(), tasks) }
        assertEquals("Mis tareas", spanish.title)
        assertEquals("My tasks", english.title)
        assertEquals(listOf("mine", "boards", "admin"), english.navigation!!.items.map { it.id })
        assertEquals(listOf("My tasks", "Boards", "Manage"), english.navigation!!.items.map { it.label })
        val titles = texts(english.body)
        assertTrue("Overdue (1)" in titles, titles.toString())
        // Task titles are user data: untouched. Action params do not change with the language.
        assertTrue("Revisar contrato" in titles)
        val open = { screen: com.ideasdeveloper.bms.ui.UiScreen -> screen.body.filterIsInstance<UiCard>().flatMap { it.children }.filterIsInstance<UiList>().flatMap { it.items }.map { it.action } }
        assertEquals(open(spanish), open(english))
    }

    @Test
    fun boardNamesShowTranslatedButTheValuesStay() {
        val labels = mapOf("Operaciones" to "Operations", "Esta semana" to "This week")
        val data = TareasScreens.BoardData(board, listOf(group), tasks = listOf(task()))
        val english = I18n.withLanguage("en") { TareasScreens.board(context(labels), data, mapOf("view" to "list")) }
        assertEquals("Operations", english.title)
        val titles = texts(english.body)
        assertTrue("This week (1)" in titles, titles.toString())
        val detail = TareasScreens.TaskDetail(task(), listOf(group), emptyList(), emptyList(), emptyList(), emptyList())
        val screen = I18n.withLanguage("en") { TareasScreens.task(context(labels), detail) }
        val fields = fieldsOf(screen.body)
        val groupField = fields.single { it.key == "group" }
        assertEquals(listOf("Sin grupo", "Esta semana"), groupField.options)
        assertEquals(listOf("No group", "This week"), groupField.optionLabels)
        // The posted value of the form stays the original name.
        val form = screen.body.filterIsInstance<UiCard>().flatMap { it.children }.filterIsInstance<UiForm>().first()
        assertEquals("Esta semana", form.values["group"])
    }

    @Test
    fun priorityHighHasItsOwnEnglishLabel() {
        val detail = TareasScreens.TaskDetail(task(), listOf(group), emptyList(), emptyList(), emptyList(), emptyList())
        val spanish = TareasScreens.task(context(), detail)
        val english = I18n.withLanguage("en") { TareasScreens.task(context(), detail).localized() }
        val spanishField = fieldsOf(spanish.body).single { it.key == "priority" }
        assertNull(spanishField.optionLabels)
        val field = fieldsOf(english.body).single { it.key == "priority" }
        assertEquals(listOf("Urgente", "Alta", "Media", "Baja"), field.options)
        assertEquals(listOf("Urgent", "High", "Medium", "Low"), field.optionLabels)
        val badges = I18n.withLanguage("en") { TareasScreens.taskBadges(task(), today).map { it.label } }
        assertEquals(listOf("Overdue", "High", "Pending"), badges)
    }

    @Test
    fun statusButtonsAndKanbanColumnsAreEnglishWithTheSameCodes() {
        val detail = TareasScreens.TaskDetail(task(), listOf(group), emptyList(), emptyList(), emptyList(), emptyList())
        val spanish = TareasScreens.task(context(), detail)
        val english = I18n.withLanguage("en") { TareasScreens.task(context(), detail) }
        val englishButtons = buttons(english.body)
        assertTrue(englishButtons.map { it.label }.containsAll(listOf("Start", "Mark as done", "Cancel task", "Archive", "Back to the board")))
        val spanishRequests = buttons(spanish.body).mapNotNull { it.action as? UiRequest }.map { it.params }
        val englishRequests = englishButtons.mapNotNull { it.action as? UiRequest }.map { it.params }
        assertEquals(spanishRequests, englishRequests)
        val data = TareasScreens.BoardData(board, listOf(group), columns = TaskStatus.entries.associateWith { listOf(task(it)) })
        val kanban = I18n.withLanguage("en") { TareasScreens.board(context(), data, mapOf("view" to "kanban")) }
        val columns = kanban.body.filterIsInstance<UiBoard>().single().columns
        assertEquals(listOf("pending", "in_progress", "review", "done", "cancelled"), columns.map { it.id })
        assertEquals(listOf("Pending", "In progress", "In review", "Done", "Cancelled"), columns.map { it.title })
        assertTrue(columns.first().cards.first().details.any { it.startsWith("Assignees: ") })
    }

    @Test
    fun commentsShowTheirTranslationUnderTheOriginal() {
        val comment = TaskComment(UUID.randomUUID(), UUID.randomUUID(), me, "Ana", "Por favor revisa", java.time.Instant.EPOCH)
        val detail = TareasScreens.TaskDetail(task(), listOf(group), emptyList(), listOf(comment), emptyList(), emptyList(), commentTranslations = listOf("Please review"))
        val screen = I18n.withLanguage("en") { TareasScreens.task(context(), detail) }
        val timeline = screen.body.filterIsInstance<UiCard>().flatMap { it.children }.filterIsInstance<UiTimeline>().first { it.entries.any { e -> e.body == "Por favor revisa" } }
        assertEquals("Por favor revisa", timeline.entries.single().body)
        assertEquals("Please review", timeline.entries.single().translation)
    }

    @Test
    fun rulesAndErrorsAreEnglish() {
        val input = TaskInput(UUID.randomUUID(), title = " ")
        assertEquals(listOf("El título es obligatorio."), input.validate())
        assertEquals(listOf("The title is required."), I18n.withLanguage("en") { input.validate() })
        val reject = I18n.withLanguage("en") { TaskTransitions.resolve(TareasFixtures.task(), TaskStatus.Done, TareasScope(null, false, false, emptySet())) }
        assertEquals(TransitionResult.Reject("You do not have permission to change the status of this task."), reject)
        val failure = assertFailsWith<TareasValidationException> { I18n.withLanguage("en") { TareasActions.priority("nada") } }
        assertEquals("Invalid priority: nada.", failure.message)
        assertEquals(TaskPriority.High, TareasActions.priority("Alta"))
    }

    @Test
    fun activityRowsStoredInSpanishAreShownInTheViewerLanguage() {
        assertEquals("Pendiente → En curso", TareasScreens.activityText("Pendiente → En curso"))
        I18n.withLanguage("en") {
            assertEquals("Pending → In progress", TareasScreens.activityText("Pendiente → En curso"))
            assertEquals("In progress → In review (sent to review)", TareasScreens.activityText("En curso → En revisión (enviada a revisión)"))
            assertEquals("Task created", TareasScreens.activityText("Tarea creada"))
            assertEquals("Imported from Monday", TareasScreens.activityText("Importada desde Monday"))
            // Anything else stays as it was written.
            assertEquals("Algo raro", TareasScreens.activityText("Algo raro"))
        }
    }

    @Test
    fun customerHistoryEntriesFollowTheLanguage() {
        val sees = TareasScope(me, true, true, emptySet())
        val tasks = listOf(TareasFixtures.task(board.id, TaskStatus.Done).copy(title = "Pagar", completedAt = java.time.Instant.EPOCH, boardName = "Operaciones"))
        val entries = I18n.withLanguage("en") { TareasCustomerTimeline.entries(tasks, sees, 10) }
        assertEquals(listOf("Task: Pagar", "Task completed: Pagar").sorted(), entries.map { it.title }.sorted())
        assertTrue(entries.any { it.detail == "Operaciones · Done" })
    }
}
