package com.ideasdeveloper.bms.extensions.inmobiliaria_monday

import com.ideasdeveloper.bms.modules.crm.CrmImportField
import com.ideasdeveloper.bms.modules.crm.CrmImportKind
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InmobiliariaMondayI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(InmobiliariaMondayServerExtension)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("inmobiliaria_monday", InmobiliariaMondayServerExtension.manifest)

    private val kind = CrmImportKind("property", "Propiedades", listOf(CrmImportField("title", "Titulo", true)))
    private val columns = listOf(MondayColumn("c0", "Nombre", "text"))

    @Test
    fun mappingErrorsAreTranslated() {
        val spanish = assertFailsWith<InmobiliariaMondayException> { MondayMapping.checkMapping(kind, columns, mapOf("Otra" to "title")) }.message
        assertEquals("El tablero no tiene la columna 'Otra'.", spanish)
        I18n.withLanguage("en") {
            assertEquals("The board has no column 'Otra'.", assertFailsWith<InmobiliariaMondayException> { MondayMapping.checkMapping(kind, columns, mapOf("Otra" to "title")) }.message)
            assertEquals("'x' is not a field of Properties.", assertFailsWith<InmobiliariaMondayException> { MondayMapping.checkMapping(kind, columns, mapOf("Nombre" to "x")) }.message)
        }
    }

    @Test
    fun statesAndConstantsFollowTheLanguage() {
        assertEquals("lista para revisar", InmobiliariaMondayScreens.stateLabel(MondayJobState.Ready))
        assertEquals("Importa primero las propiedades.", InmobiliariaMondayService.PropertiesFirst)
        I18n.withLanguage("en") {
            assertEquals("ready to review", InmobiliariaMondayScreens.stateLabel(MondayJobState.Ready))
            assertEquals("finished", InmobiliariaMondayScreens.stateLabel(MondayJobState.Done))
            // Stored values never change language.
            assertEquals("Importa primero las propiedades.", InmobiliariaMondayService.PropertiesFirst)
            assertEquals("Import the properties first.", I18n.translate(InmobiliariaMondayService.PropertiesFirst))
            assertEquals("monday rejected the token.", assertFailsWith<InmobiliariaMondayException> { InmobiliariaMondayClient.parseResponse(401, "") }.message)
        }
    }
}
