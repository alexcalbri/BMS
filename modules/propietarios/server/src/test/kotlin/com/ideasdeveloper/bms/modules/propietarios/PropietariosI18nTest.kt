package com.ideasdeveloper.bms.modules.propietarios

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadModuleCatalog
import com.ideasdeveloper.bms.server.i18n.localized
import com.ideasdeveloper.bms.server.i18n.localizedPayload
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiRow
import java.time.LocalDate
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PropietariosI18nTest {
    private val today = LocalDate.of(2026, 11, 18)

    // Tests build no ModuleRegistry, so the module's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadModuleCatalog(PropietariosServerModule)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertModule(
        "propietarios",
        PropietariosServerModule.definition,
        extraTexts = listOf("Todos"),
    )

    private fun context(manage: Boolean = false, import: Boolean = false) =
        PropietariosScreens.Context(canManage = manage, canAdmin = false, importEnabled = import, canSeeCustomers = false, today = today)

    private fun forms(nodes: List<UiNode>): List<UiForm> = nodes.flatMap { node ->
        when (node) {
            is UiForm -> listOf(node)
            is UiRow -> forms(node.children)
            is UiCard -> forms(node.children + node.actions)
            else -> emptyList()
        }
    }

    @Test
    fun navigationIsInEnglishAndTheIdsStayTheSame() {
        val spanish = PropietariosScreens.navigation(context(import = true), "desarrollos")
        val english = I18n.withLanguage("en") { PropietariosScreens.navigation(context(import = true), "desarrollos") }
        assertEquals(listOf("Desarrollos", "Propietarios", "Buscar", "Importar"), spanish.items.map { it.label })
        assertEquals(listOf("Developments", "Owners", "Search", "Import"), english.items.map { it.label })
        assertEquals(spanish.items.map { it.id }, english.items.map { it.id })
        assertEquals("desarrollos", english.selected)
    }

    @Test
    fun lotStatusLabelsAreTranslatedButTheFormValuesStaySpanish() {
        val detail = PropietariosScreens.LotDetail(PropietariosFixtures.lot(), emptyList(), emptyList(), emptyList())
        val spanish = forms(PropietariosScreens.lot(context(manage = true), detail).body).flatMap { it.fields }.first { it.key == "status" }
        val english = I18n.withLanguage("en") {
            localizedPayload(PropietariosScreens.lot(context(manage = true), detail))
        }
        val field = forms(english.body).flatMap { it.fields }.first { it.key == "status" }
        assertEquals("Status", field.label)
        assertEquals(listOf("Disponible", "Reservado", "Vendido", "En construcción", "Construido"), spanish.options)
        assertEquals(listOf("Available", "Reserved", "Sold", "Under construction", "Built"), field.optionLabels)
        assertEquals(LotStatus.Vendido, LotStatus.fromLabel("Vendido"))
        I18n.withLanguage("en") {
            assertEquals("Sold", tr(LotStatus.Vendido.label))
            assertEquals(LotStatus.Vendido, LotStatus.fromLabel("Vendido"))
        }
    }

    @Test
    fun rulesErrorsAreTranslatedWhereTheyAreThrown() {
        I18n.withLanguage("en") {
            val failure = assertFailsWith<PropietariosValidationException> {
                PropietariosRules.planTransfer(today, emptyList(), emptyList(), emptySet(), LotStatus.Disponible)
            }
            assertEquals("The lot has no current owners to remove.", failure.message)
            assertEquals(
                "Each percentage must be greater than 0 and at most 100, with up to two decimals.",
                assertFailsWith<PropietariosValidationException> { PropietariosRules.checkShares(listOf(java.math.BigDecimal("0"))) }.message,
            )
        }
        // The same rule in Spanish keeps today's text.
        assertEquals(
            "El lote no tiene titulares vigentes que dar de baja.",
            assertFailsWith<PropietariosValidationException> { PropietariosRules.planTransfer(today, emptyList(), emptyList(), emptySet(), LotStatus.Disponible) }.message,
        )
    }

    @Test
    fun historyTextIsStoredInSpanishWhateverTheLanguage() {
        I18n.withLanguage("en") {
            assertEquals("Baja de: Ana Perez", PropietariosRules.historyDetail("baja", listOf("Ana Perez"), emptyList(), ""))
            assertEquals("Alta de: Beta\nNotas: nota", PropietariosRules.historyDetail("alta", emptyList(), listOf("Beta"), "nota"))
        }
    }

    @Test
    fun theModuleDefinitionIsTranslatedByCoreFromTheCatalog() {
        val english = I18n.withLanguage("en") { PropietariosServerModule.definition.localized() }
        assertEquals("Owners", english.displayName)
        assertEquals("View owners", english.permissions.first { it.id == PropietariosModule.View }.title)
        assertEquals("Manage developments", english.permissions.first { it.id == PropietariosModule.Admin }.title)
    }
}
