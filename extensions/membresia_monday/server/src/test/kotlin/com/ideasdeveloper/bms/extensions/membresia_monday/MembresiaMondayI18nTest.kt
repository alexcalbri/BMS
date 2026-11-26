package com.ideasdeveloper.bms.extensions.membresia_monday

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import com.ideasdeveloper.bms.server.i18n.tr
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MembresiaMondayI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(MembresiaMondayServerExtension)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("membresia_monday", MembresiaMondayServerExtension.manifest)

    @Test
    fun errorsAreTranslatedAndSpanishIsUnchanged() {
        assertEquals("El id del tablero debe ser un numero.", assertFailsWith<MembresiaMondayException> { MembresiaMondayClient.boardInfo("token", "x") }.message)
        assertEquals("Escribe el token de la API de monday.", assertFailsWith<MembresiaMondayException> { MembresiaMondayClient.boards("") }.message)
        I18n.withLanguage("en") {
            assertEquals("The board ID must be a number.", assertFailsWith<MembresiaMondayException> { MembresiaMondayClient.boardInfo("token", "x") }.message)
            assertEquals("Enter the monday API token.", assertFailsWith<MembresiaMondayException> { MembresiaMondayClient.boards("") }.message)
        }
    }

    @Test
    fun statusesAndMappingLabelsFollowTheLanguageButValuesStay() {
        assertEquals("Leyendo", MembresiaMondayFlow.statusLabel("reading"))
        assertEquals("No usar", MembresiaMondayFlow.NoColumn)
        assertEquals("Correo", MondayTarget.all.first().label)
        I18n.withLanguage("en") {
            assertEquals("Reading", MembresiaMondayFlow.statusLabel("reading"))
            assertEquals("No usar", MembresiaMondayFlow.NoColumn)
            assertEquals("Email", tr(MondayTarget.all.first().label))
            assertEquals(
                "Choose the membership type column: without it there is no way to know which plan each member belongs to.",
                MembresiaMondayFlow.mappingProblem(MondayMapping(), emptyList()),
            )
        }
        assertEquals(
            "Elige la columna del tipo de membresia: sin ella no se sabe a que plan pertenece cada miembro.",
            MembresiaMondayFlow.mappingProblem(MondayMapping(), emptyList()),
        )
    }
}
