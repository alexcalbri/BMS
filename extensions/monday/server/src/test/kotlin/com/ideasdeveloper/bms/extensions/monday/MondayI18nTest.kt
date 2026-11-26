package com.ideasdeveloper.bms.extensions.monday

import com.ideasdeveloper.bms.modules.crm.CrmImportException
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MondayI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(MondayServerExtension)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("monday", MondayServerExtension.manifest)

    @Test
    fun importErrorsAreTranslatedAndSpanishIsUnchanged() {
        assertEquals("El archivo no tiene filas.", assertFailsWith<CrmImportException> { MondayImport.table(emptyList()) }.message)
        assertEquals("Escribe el token de la API de monday.", assertFailsWith<CrmImportException> { MondayImport.fromMonday("", listOf("1")) }.message)
        I18n.withLanguage("en") {
            assertEquals("The file has no rows.", assertFailsWith<CrmImportException> { MondayImport.table(emptyList()) }.message)
            assertEquals("Enter the monday API token.", assertFailsWith<CrmImportException> { MondayImport.fromMonday("", listOf("1")) }.message)
            assertEquals(
                "Enter the board IDs (numbers separated by commas).",
                assertFailsWith<CrmImportException> { MondayImport.fromMonday("token", listOf("x")) }.message,
            )
        }
    }

    @Test
    fun labelsFollowTheLanguageButIdsStay() {
        assertEquals("Leyendo monday", MondayImport.readingLabel)
        assertEquals("Envio a monday", MondayServerExtension.sections.single().label)
        I18n.withLanguage("en") {
            assertEquals("Reading monday", MondayImport.readingLabel)
            assertEquals("Sending to monday", MondayServerExtension.sections.single().label)
            assertEquals(MondayOutboundScreens.Section, MondayServerExtension.sections.single().id)
        }
    }
}
