package com.ideasdeveloper.bms.extensions.tareas_monday

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import com.ideasdeveloper.bms.server.i18n.tr
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TareasMondayI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(TareasMondayServerExtension)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("tareas_monday", TareasMondayServerExtension.manifest)

    @Test
    fun errorsAreTranslatedAndSpanishIsUnchanged() {
        assertEquals("El id del tablero debe ser un numero.", assertFailsWith<TareasMondayException> { TareasMondayClient.boardInfo("token", "x") }.message)
        I18n.withLanguage("en") {
            assertEquals("The board id must be a number.", assertFailsWith<TareasMondayException> { TareasMondayClient.boardInfo("token", "x") }.message)
        }
    }

    @Test
    fun mappingLabelsFollowTheLanguageButTargetsStayPlain() {
        val due = MondayTarget.all.first { it.target == MondayTarget.Due }
        assertEquals("Fecha limite", tr(due.label))
        I18n.withLanguage("en") {
            assertEquals("Due date", tr(due.label))
            assertEquals("Applying", TareasMondayFlow.statusLabel(MondayJobStatus.Applying))
        }
    }

    @Test
    fun manifestTextIsTranslatedByTheCatalog() {
        assertEquals("Importacion de tareas desde monday", TareasMondayServerExtension.manifest.displayName)
        I18n.withLanguage("en") {
            assertEquals("Tasks import from monday", I18n.translate(TareasMondayServerExtension.manifest.displayName))
        }
    }
}
