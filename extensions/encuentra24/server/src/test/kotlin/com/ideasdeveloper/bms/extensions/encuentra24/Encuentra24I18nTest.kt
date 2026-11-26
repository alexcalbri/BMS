package com.ideasdeveloper.bms.extensions.encuentra24

import com.ideasdeveloper.bms.modules.crm.CrmEncuentra24Exception
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Encuentra24I18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(Encuentra24ServerExtension)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("encuentra24", Encuentra24ServerExtension.manifest)

    @Test
    fun manifestTextsAreTranslatedAndNamesStay() {
        assertEquals("Encuentra24", Encuentra24ServerExtension.manifest.displayName)
        I18n.withLanguage("en") {
            assertEquals(
                "Imports the messages, chats and calls from Encuentra24 listings as leads and keeps the listing in the lead's interests.",
                I18n.translate(Encuentra24ServerExtension.manifest.description),
            )
            assertEquals(
                "Encuentra24 listings linked to the leads",
                I18n.translate(Encuentra24ServerExtension.manifest.uninstall.options.single().label),
            )
        }
    }

    @Test
    fun storedTextsStayInSpanishForAnyLanguage() {
        // The conversation text and the API errors are stored (crm_messages, last sync error): always Spanish.
        val (leads, _) = CrmEncuentra24.parsePage(
            """{"data":[{"id":"7","origin":"PHONECALL","title":"Lote","adid":"29000001"}]}""",
        )
        I18n.withLanguage("en") {
            assertEquals(
                "[Encuentra24 - Llamada] Lote (anuncio 29000001)\nLlamada desde el anuncio.",
                CrmEncuentra24.messageText(leads.single()),
            )
            assertEquals(
                "Encuentra24 respondio algo que no es una lista de leads.",
                assertFailsWith<CrmEncuentra24Exception> { CrmEncuentra24.parsePage("<html>") }.message,
            )
        }
    }
}
