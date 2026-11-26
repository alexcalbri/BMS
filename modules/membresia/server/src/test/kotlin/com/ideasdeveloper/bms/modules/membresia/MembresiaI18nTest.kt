package com.ideasdeveloper.bms.modules.membresia

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadModuleCatalog
import com.ideasdeveloper.bms.server.i18n.localized
import com.ideasdeveloper.bms.server.i18n.tr
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MembresiaI18nTest {
    private val everything = MembresiaAccess(view = true, visits = true, manage = true, plans = true)

    // Tests build no ModuleRegistry, so the module's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadModuleCatalog(MembresiaServerModule)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertModule("membresia", MembresiaServerModule.definition)

    @Test
    fun navigationAndTitleAreInEnglishButTheIdsStayTheSame() {
        val spanish = MembresiaScreens.navigation(everything, "recepcion")
        val english = I18n.withLanguage("en") { MembresiaScreens.navigation(everything, "recepcion") }
        assertEquals("Membresías", spanish.title)
        assertEquals("Memberships", english.title)
        assertEquals(spanish.items.map { it.id }, english.items.map { it.id })
        assertTrue("Front desk" in english.items.map { it.label }, english.items.map { it.label }.toString())
        assertTrue("Members" in english.items.map { it.label }, english.items.map { it.label }.toString())
    }

    @Test
    fun statusLabelsAreTranslatedForDisplayAndKeptInSpanishAsValues() {
        I18n.withLanguage("en") {
            assertEquals("Active", tr(DisplayStatus.ACTIVA.label))
            assertEquals("Expiring", tr(DisplayStatus.POR_VENCER.label))
            assertEquals("Cancelled", tr(DisplayStatus.CANCELADA.label))
            // The key and the label the app and the server compare with stay Spanish.
            assertEquals("activa", DisplayStatus.ACTIVA.key)
            assertEquals("Activa", DisplayStatus.ACTIVA.label)
        }
    }

    @Test
    fun validationMessagesAreInEnglish() {
        val start = LocalDate.of(2026, 12, 10)
        val end = LocalDate.of(2026, 12, 1)
        I18n.withLanguage("en") {
            assertEquals(listOf("The end cannot be before the start."), MembresiaRules.validateDates(start, end, LocalDate.of(2026, 11, 18)))
        }
        assertEquals(listOf("El fin no puede ser anterior al inicio."), MembresiaRules.validateDates(start, end, LocalDate.of(2026, 11, 18)))
    }

    @Test
    fun definitionTextsAreTranslatedByCore() {
        val english = I18n.withLanguage("en") { MembresiaServerModule.definition.localized() }
        assertEquals("Memberships", english.displayName)
        assertEquals("View members", english.permissions.first { it.id == MembresiaModule.View }.title)
        assertEquals("Import data", english.permissions.first { it.id == MembresiaModule.Import }.title)
    }
}
