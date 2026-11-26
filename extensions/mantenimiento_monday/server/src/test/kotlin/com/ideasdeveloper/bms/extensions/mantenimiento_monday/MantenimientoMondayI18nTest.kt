package com.ideasdeveloper.bms.extensions.mantenimiento_monday

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import com.ideasdeveloper.bms.server.i18n.tr
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MantenimientoMondayI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(MantenimientoMondayServerExtension)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("mantenimiento_monday", MantenimientoMondayServerExtension.manifest)

    @Test
    fun errorsAreTranslatedAndSpanishIsUnchanged() {
        assertEquals("El id del tablero debe ser un numero.", assertFailsWith<MantenimientoMondayException> { MantenimientoMondayClient.boardInfo("token", "x") }.message)
        assertEquals("Escribe el token de la API de monday.", assertFailsWith<MantenimientoMondayException> { MantenimientoMondayClient.boards("") }.message)
        I18n.withLanguage("en") {
            assertEquals("The board id must be a number.", assertFailsWith<MantenimientoMondayException> { MantenimientoMondayClient.boardInfo("token", "x") }.message)
            assertEquals("Enter the monday API token.", assertFailsWith<MantenimientoMondayException> { MantenimientoMondayClient.boards("") }.message)
        }
    }

    @Test
    fun labelsFollowTheLanguageButValuesStay() {
        assertEquals("plans", MondayDestination.Plans)
        assertEquals("Plantillas preventivas", MondayDestination.label(MondayDestination.Plans))
        assertEquals("Leyendo", MantenimientoMondayFlow.statusLabel(MondayJobStatus.Reading))
        assertEquals("No usar", MantenimientoMondayFlow.NoColumn)
        I18n.withLanguage("en") {
            assertEquals("Preventive templates", MondayDestination.label(MondayDestination.Plans))
            assertEquals("Reading", MantenimientoMondayFlow.statusLabel(MondayJobStatus.Reading))
            assertEquals("No usar", MantenimientoMondayFlow.NoColumn)
            assertEquals("Matched with users: Ana.", tr("Se emparejan con usuarios: {0}.", "Ana"))
        }
    }
}
