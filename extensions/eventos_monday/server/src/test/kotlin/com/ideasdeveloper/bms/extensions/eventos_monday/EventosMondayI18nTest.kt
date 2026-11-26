package com.ideasdeveloper.bms.extensions.eventos_monday

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import com.ideasdeveloper.bms.server.i18n.tr
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EventosMondayI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(EventosMondayServerExtension)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("eventos_monday", EventosMondayServerExtension.manifest)

    @Test
    fun errorsAreTranslatedAndSpanishIsUnchanged() {
        assertEquals("El id del tablero debe ser un numero.", assertFailsWith<EventosMondayException> { EventosMondayClient.boardInfo("token", "x") }.message)
        I18n.withLanguage("en") {
            assertEquals("The board id must be a number.", assertFailsWith<EventosMondayException> { EventosMondayClient.boardInfo("token", "x") }.message)
        }
    }

    @Test
    fun mappingLabelsFollowTheLanguageButTargetsStayPlain() {
        val contact = MondayTarget.all.first { it.target == MondayTarget.Contact }
        assertEquals("Contacto (cliente)", tr(contact.label))
        I18n.withLanguage("en") {
            assertEquals("Contact (customer)", tr(contact.label))
            assertEquals("Applying", EventosMondayFlow.statusLabel(MondayJobStatus.Applying))
        }
    }

    @Test
    fun manifestTextIsTranslatedByTheCatalog() {
        assertEquals("Importacion de eventos desde monday", EventosMondayServerExtension.manifest.displayName)
        I18n.withLanguage("en") {
            assertEquals("Events import from monday", I18n.translate(EventosMondayServerExtension.manifest.displayName))
        }
    }
}
