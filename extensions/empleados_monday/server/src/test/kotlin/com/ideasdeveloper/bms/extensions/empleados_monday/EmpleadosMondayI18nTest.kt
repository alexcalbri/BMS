package com.ideasdeveloper.bms.extensions.empleados_monday

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import com.ideasdeveloper.bms.server.i18n.tr
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmpleadosMondayI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(EmpleadosMondayServerExtension)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("empleados_monday", EmpleadosMondayServerExtension.manifest)

    @Test
    fun errorsAreTranslatedAndSpanishIsUnchanged() {
        assertEquals("El id del tablero debe ser un numero.", assertFailsWith<EmpleadosMondayException> { EmpleadosMondayClient.boardInfo("token", "x") }.message)
        I18n.withLanguage("en") {
            assertEquals("The board id must be a number.", assertFailsWith<EmpleadosMondayException> { EmpleadosMondayClient.boardInfo("token", "x") }.message)
        }
    }

    @Test
    fun mappingLabelsFollowTheLanguageButTargetsStayPlain() {
        val name = MondayTarget.all.first { it.target == MondayTarget.Name }
        assertEquals("name", name.target)
        assertEquals("Nombre (si no, el nombre del elemento)", tr(name.label))
        I18n.withLanguage("en") {
            assertEquals("Name (otherwise, the item name)", tr(name.label))
            assertEquals("Applying", EmpleadosMondayFlow.statusLabel(MondayJobStatus.Applying))
        }
    }

    @Test
    fun manifestTextIsTranslatedByTheCatalog() {
        assertEquals("Importacion de empleados desde monday", EmpleadosMondayServerExtension.manifest.displayName)
        I18n.withLanguage("en") {
            assertEquals("Employees import from monday", I18n.translate(EmpleadosMondayServerExtension.manifest.displayName))
        }
    }
}
