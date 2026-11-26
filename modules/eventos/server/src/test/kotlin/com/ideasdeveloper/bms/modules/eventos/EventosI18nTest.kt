package com.ideasdeveloper.bms.modules.eventos

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadModuleCatalog
import com.ideasdeveloper.bms.server.i18n.localized
import com.ideasdeveloper.bms.server.i18n.tr
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventosI18nTest {
    private val viewer = EventosScreens.Caps(register = false, manage = false)

    // Tests build no ModuleRegistry, so the module's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadModuleCatalog(EventosServerModule)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertModule("eventos", EventosServerModule.definition)

    @Test
    fun navigationAndTheTitleAreInEnglishButTheIdsStayTheSame() {
        val spanish = EventosScreens.navigation(viewer, "proximos")
        val english = I18n.withLanguage("en") { EventosScreens.navigation(viewer, "proximos") }
        assertEquals("Eventos", spanish.title)
        assertEquals("Events", english.title)
        assertEquals(spanish.items.map { it.id }, english.items.map { it.id })
        assertTrue("Upcoming" in english.items.map { it.label }, english.items.map { it.label }.toString())
        assertTrue("Past" in english.items.map { it.label }, english.items.map { it.label }.toString())
    }

    @Test
    fun statusLabelsAreTranslatedForDisplayAndKeptInSpanishAsValues() {
        I18n.withLanguage("en") {
            assertEquals("Tentative", tr(EventosRules.statusLabel(EventKind.Private, EventStatus.Draft)))
            assertEquals("Published", tr(EventosRules.statusLabel(EventKind.Public, EventStatus.Published)))
            assertEquals("Cancelled", tr(EventosRules.statusLabel(EventKind.Public, EventStatus.Cancelled)))
            // The label itself is the Spanish text the app and the server compare with.
            assertEquals("Tentativo", EventosRules.statusLabel(EventKind.Private, EventStatus.Draft))
            assertEquals("Miembro", EventosScreens.priceLabel(PriceKind.Member))
        }
    }

    @Test
    fun seatsAndNoticeTextsAreInEnglish() {
        I18n.withLanguage("en") {
            assertEquals("1 seat", EventosRules.seatsLabel(1))
            assertEquals("3 seats", EventosRules.seatsLabel(3))
            assertEquals("Escape · 10 seats", EventosNotify.fullText("Escape", 10))
            assertEquals("Escape: 2 waiting", EventosNotify.seatFreedText("Escape", 2))
            assertEquals("Ana · Escape · 1 seat", EventosNotify.newRegistrationText("Ana", "Escape", 1))
        }
        assertEquals("Escape: 2 en espera", EventosNotify.seatFreedText("Escape", 2))
    }

    @Test
    fun validationMessagesAreInEnglish() {
        I18n.withLanguage("en") {
            val messages = EventosRules.validateRegistration(0, null, "")
            assertTrue(messages.single().startsWith("The seats must be between 1 and "), messages.toString())
        }
        assertTrue(EventosRules.validateRegistration(0, null, "").single().startsWith("Las plazas deben estar entre 1 y "))
    }

    @Test
    fun definitionTextsAreTranslatedByCore() {
        val english = I18n.withLanguage("en") { EventosServerModule.definition.localized() }
        assertEquals("Events", english.displayName)
        assertEquals("View events", english.permissions.first { it.id == EventosModule.View }.title)
        assertEquals("Import data", english.permissions.first { it.id == EventosModule.Import }.title)
    }
}
