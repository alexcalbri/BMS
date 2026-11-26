package com.ideasdeveloper.bms.modules.compras

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadModuleCatalog
import com.ideasdeveloper.bms.server.i18n.localizedPayload
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiColumn
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiGrid
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiRow
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComprasI18nTest {
    private val viewer = ComprasModule.View

    // Tests build no ModuleRegistry, so the module's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadModuleCatalog(ComprasServerModule)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertModule(
        "compras",
        ComprasServerModule.definition,
        extraTexts = listOf("Todos", "Sin departamento"),
    )

    private fun forms(nodes: List<UiNode>): List<UiForm> = nodes.flatMap { node ->
        when (node) {
            is UiForm -> listOf(node)
            is UiColumn -> forms(node.children)
            is UiRow -> forms(node.children)
            is UiGrid -> forms(node.children)
            is UiCard -> forms(node.children + node.actions)
            else -> emptyList()
        }
    }

    @Test
    fun menuAndTitlesAreInEnglishButIdsStayTheSame() {
        val actor = ComprasTestSupport.actor(UUID.randomUUID(), viewer)
        val home = ComprasScreens.HomeData(0, 0, 0, null, emptyList())
        val spanish = ComprasScreens.homeScreen(actor, home)
        val english = I18n.withLanguage("en") { ComprasScreens.homeScreen(actor, home) }
        assertEquals(listOf("Inicio", "Solicitudes", "Tablero", "Altas y criticas"), spanish.navigation!!.items.map { it.label })
        assertEquals(listOf("Start", "Requests", "Board", "High and critical"), english.navigation!!.items.map { it.label })
        assertEquals(spanish.navigation!!.items.map { it.id }, english.navigation!!.items.map { it.id })
        assertEquals("Start", english.title)
        assertEquals("Inicio", spanish.title)
    }

    @Test
    fun formLabelsAreEnglishButOptionValuesStayAsTheServerReadsThem() {
        val actor = ComprasTestSupport.actor(UUID.randomUUID(), viewer, ComprasModule.Create)
        val data = ComprasScreens.NewData(listOf(Department(UUID.randomUUID(), "Limpieza", true, 1)), "USD")
        val spanish = forms(ComprasScreens.newScreen(actor, data).body).single()
        val english = I18n.withLanguage("en") { localizedPayload(ComprasScreens.newScreen(actor, data)) }
        val englishForm = forms(english.body).single()
        val priority = englishForm.fields.first { it.key == "priority" }
        assertEquals("Priority", priority.label)
        assertEquals(listOf("Baja", "Media", "Alta", "Critica"), priority.options)
        // The module's own catalog: "Alta" is a priority here, not Core's "New item".
        assertEquals(listOf("Low", "Medium", "High", "Critical"), priority.optionLabels)
        // Same fields, same keys and same options in both languages; only the labels change.
        assertEquals(spanish.fields.map { it.key }, englishForm.fields.map { it.key })
        assertEquals(spanish.fields.map { it.options }, englishForm.fields.map { it.options })
        assertEquals(spanish.fields.map { it.required }, englishForm.fields.map { it.required })
    }

    @Test
    fun enumLabelsAreTranslatedWhereTheyAreShownAndParsedInSpanish() {
        I18n.withLanguage("en") {
            assertEquals("Borrador", RequestStatus.Draft.label)
            assertEquals("Draft", tr(RequestStatus.Draft.label))
            assertEquals("Pending approval", tr(RequestStatus.PendingApproval.label))
            assertEquals("Critica", Priority.Critical.label)
            assertEquals("Critical", tr(Priority.Critical.label))
            // The server parses the Spanish source label, whatever the language of the request.
            assertEquals(Priority.Critical, Priority.entries.firstOrNull { it.label == "Critica" })
            assertEquals("Send for approval", ComprasScreens.transitionLabel(RequestStatus.Draft, RequestStatus.PendingApproval))
        }
    }

    @Test
    fun validationErrorsAreTranslatedWhereTheyAreThrown() {
        I18n.withLanguage("en") {
            assertEquals("The amount is required.", assertFailsWith<ComprasValidationException> { ComprasMoney.parse(null) }.message)
            assertEquals("The amount cannot be negative.", assertFailsWith<ComprasValidationException> { ComprasMoney.parse("-1") }.message)
            val actor = ComprasTestSupport.actor(UUID.randomUUID(), viewer, ComprasModule.Create)
            val context = FlowContext(requesterId = null, decidedBy = null, approver = null, amount = null, currency = "USD")
            assertEquals(
                "Cannot move from Draft to Completed.",
                assertFailsWith<ComprasValidationException> { ComprasFlow.validate(RequestStatus.Draft, RequestStatus.Completed, actor, context, null, "Sabanas") }.message,
            )
        }
        // The same call in Spanish keeps today's text.
        assertEquals("El monto es obligatorio.", assertFailsWith<ComprasValidationException> { ComprasMoney.parse(null) }.message)
    }
}
