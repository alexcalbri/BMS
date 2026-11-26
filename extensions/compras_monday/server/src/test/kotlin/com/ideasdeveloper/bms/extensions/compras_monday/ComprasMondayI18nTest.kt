package com.ideasdeveloper.bms.extensions.compras_monday

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import com.ideasdeveloper.bms.server.i18n.tr
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComprasMondayI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(ComprasMondayServerExtension)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("compras_monday", ComprasMondayServerExtension.manifest)

    @Test
    fun errorsAreTranslatedAndSpanishIsUnchanged() {
        assertEquals("El id del tablero no es valido.", assertFailsWith<ComprasMondayException> { ComprasMondayApi.boardInfo("token", "x") }.message)
        I18n.withLanguage("en") {
            assertEquals("The board id is not valid.", assertFailsWith<ComprasMondayException> { ComprasMondayApi.boardInfo("token", "x") }.message)
            assertEquals(
                "The items are too heavy even with 10 per page; split the board or remove columns.",
                tr(ComprasMondayRetry.TooHeavyMessage),
            )
        }
    }

    @Test
    fun labelsFollowTheLanguageButStoredStatusesStayPlain() {
        assertEquals("Fecha requerida", MondayFields.label(MondayFields.RequiredDate))
        assertEquals("leyendo monday", ComprasMondayImport.statusLabel("reading"))
        I18n.withLanguage("en") {
            assertEquals("Required date", MondayFields.label(MondayFields.RequiredDate))
            assertEquals("reading monday", tr(ComprasMondayImport.statusLabel("reading")))
        }
    }

    @Test
    fun manifestAndWorkspaceTextsAreTranslated() {
        assertEquals("Importacion desde monday", ComprasMondayServerExtension.manifest.displayName)
        assertEquals("Espacio principal", ComprasMondayParse.MainWorkspace)
        I18n.withLanguage("en") {
            assertEquals("Import from monday", I18n.translate(ComprasMondayServerExtension.manifest.displayName))
            assertEquals("Main workspace", tr(ComprasMondayParse.MainWorkspace))
        }
    }
}
