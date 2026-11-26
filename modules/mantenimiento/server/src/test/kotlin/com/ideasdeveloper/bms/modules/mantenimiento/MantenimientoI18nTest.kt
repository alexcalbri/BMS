package com.ideasdeveloper.bms.modules.mantenimiento

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadModuleCatalog
import com.ideasdeveloper.bms.server.i18n.localized
import com.ideasdeveloper.bms.server.i18n.localizedPayload
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiScreen
import java.time.LocalDate
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MantenimientoI18nTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val me = UUID.randomUUID()
    private val manager = MantenimientoScope(me, true, true)
    private val users = listOf(MantenimientoUser(me, "Yo", "yo@example.com"))
    private val context = MantenimientoScreens.Context(manager, users, listOf(Property(UUID.randomUUID(), "Torre", 1, false)), today)

    // Tests build no ModuleRegistry, so the module's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadModuleCatalog(MantenimientoServerModule)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertModule("mantenimiento", MantenimientoServerModule.definition)

    private fun buttons(nodes: List<UiNode>): List<UiButton> = nodes.flatMap { node ->
        when (node) {
            is UiButton -> listOf(node)
            is UiCard -> buttons(node.children + node.actions)
            is UiRow -> buttons(node.children)
            else -> emptyList()
        }
    }

    private fun forms(nodes: List<UiNode>): List<UiForm> = nodes.flatMap { node ->
        when (node) {
            is UiForm -> listOf(node)
            is UiCard -> forms(node.children + node.actions)
            is UiRow -> forms(node.children)
            else -> emptyList()
        }
    }

    @Test
    fun navigationAndQuickActionsAreInEnglishButTheActionsStay() {
        val spanish = MantenimientoScreens.navigation(manager, "upcoming")
        val english = I18n.withLanguage("en") { MantenimientoScreens.navigation(manager, "upcoming") }
        assertEquals(listOf("Upcoming", "My orders", "Work orders", "Assets", "Plans", "Settings"), english.items.map { it.label })
        assertEquals(spanish.items.map { it.id }, english.items.map { it.id })
        val quick = I18n.withLanguage("en") { buttons(listOf(MantenimientoScreens.quickActions())) }
        assertEquals(listOf("Report breakdown", "Request project"), quick.map { it.label })
        val requests = quick.map { (it.action as UiNavigate).params }
        assertEquals(buttons(listOf(MantenimientoScreens.quickActions())).map { (it.action as UiNavigate).params }, requests)
    }

    @Test
    fun upcomingScreenTitlesAndBucketsAreTranslated() {
        val order = MantenimientoFixtures.order(status = OrderStatus.Open, dueDate = today.minusDays(2))
        val metrics = OrderMetrics(open = 1, overdue = 1, pendingApproval = 0, completedThisMonth = 0)
        val english = I18n.withLanguage("en") { MantenimientoScreens.upcoming(context, metrics, listOf(order), emptyList(), null) }
        assertEquals("Upcoming", english.title)
        val cards = english.body.filterIsInstance<UiCard>().mapNotNull { it.title }
        assertTrue("Overdue (1)" in cards, cards.toString())
        assertTrue(english.body.any { it is UiCard && it.children.any { child -> child is UiForm && child.submit?.label == "Filter" } })
        val spanish = MantenimientoScreens.upcoming(context, metrics, listOf(order), emptyList(), null)
        assertEquals("Próximos", spanish.title)
        assertEquals("Vencidas (1)", spanish.body.filterIsInstance<UiCard>().mapNotNull { it.title }.first { it.startsWith("Vencidas") })
    }

    @Test
    fun enumLabelsAreTranslatedForDisplayAndParsedInSpanish() {
        I18n.withLanguage("en") {
            assertEquals("In progress", tr(OrderStatus.InProgress.label))
            assertEquals("Urgent", tr(OrderPriority.Urgent.label))
            assertEquals("Monthly", tr(PlanFrequency.Monthly.label))
            // What the app sends back and the server parses stays the Spanish label.
            assertEquals(OrderStatus.InProgress, OrderStatus.fromLabel("En curso"))
            assertEquals(OrderPriority.Urgent, OrderPriority.fromLabel("Urgente"))
            assertEquals(PlanFrequency.Monthly, PlanFrequency.fromLabel("Mensual"))
        }
    }

    @Test
    fun dropdownOptionsKeepTheirSpanishValuesAndGetEnglishLabels() {
        val screen = I18n.withLanguage("en") { localizedPayload(MantenimientoScreens.orders(context, MantenimientoScreens.OrdersData(), emptyMap())) }
        val statusField = forms(screen.body).flatMap { it.fields }.first { it.key == "status" }
        assertEquals("Todos", statusField.options!!.first())
        assertEquals("All", statusField.optionLabels!!.first())
        val pending = statusField.options!!.indexOf("Pendiente de aprobación")
        assertEquals("Pending approval", statusField.optionLabels!![pending])
        val spanish = localizedPayload(MantenimientoScreens.orders(context, MantenimientoScreens.OrdersData(), emptyMap()))
        assertEquals(spanish.title, "Órdenes")
    }

    @Test
    fun validationMessagesAreInEnglish() {
        I18n.withLanguage("en") {
            assertEquals(listOf("The title is required."), OrderInput(title = " ").validate())
            assertEquals(listOf("The asset name is required."), AssetInput(name = "").validate())
            assertEquals(listOf("The next date is required."), PlanInput(title = "Filtro", nextDue = null).validate())
        }
        assertEquals(listOf("El título es obligatorio."), OrderInput(title = " ").validate())
    }

    @Test
    fun parseErrorsUseTheTranslatedLabel() {
        I18n.withLanguage("en") {
            val failure = assertFailsWith<MantenimientoValidationException> { MantenimientoActions.int("x", tr("Los días de aviso")) }
            assertEquals("The notice days must be a whole number.", failure.message)
        }
        val spanish = assertFailsWith<MantenimientoValidationException> { MantenimientoActions.int("x", "Los días de aviso") }
        assertEquals("Los días de aviso debe ser un número entero.", spanish.message)
    }

    @Test
    fun definitionTextsAreTranslatedByCore() {
        val english = I18n.withLanguage("en") { MantenimientoServerModule.definition.localized() }
        assertTrue(english.description.startsWith("Maintenance work orders by property and asset"), english.description)
        assertEquals("View maintenance", english.permissions.first { it.id == MantenimientoModule.View }.title)
        assertEquals("Import data", english.permissions.first { it.id == MantenimientoModule.Import }.title)
    }
}
