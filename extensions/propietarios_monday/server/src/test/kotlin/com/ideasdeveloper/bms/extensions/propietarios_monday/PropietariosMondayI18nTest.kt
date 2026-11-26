package com.ideasdeveloper.bms.extensions.propietarios_monday

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import com.ideasdeveloper.bms.server.i18n.tr
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PropietariosMondayI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(PropietariosMondayServerExtension)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("propietarios_monday", PropietariosMondayServerExtension.manifest)

    @Test
    fun errorsAreTranslatedAndSpanishIsUnchanged() {
        assertEquals("El id del tablero debe ser un numero.", assertFailsWith<PropietariosMondayException> { PropietariosMondayClient.boardInfo("token", "x") }.message)
        assertEquals("Escribe el token de la API de monday.", assertFailsWith<PropietariosMondayException> { PropietariosMondayClient.boards("") }.message)
        I18n.withLanguage("en") {
            assertEquals("The board id must be a number.", assertFailsWith<PropietariosMondayException> { PropietariosMondayClient.boardInfo("token", "x") }.message)
            assertEquals("Enter the monday API token.", assertFailsWith<PropietariosMondayException> { PropietariosMondayClient.boards("") }.message)
        }
    }

    @Test
    fun statusesMappingLabelsAndOptionsFollowTheLanguageButValuesStay() {
        assertEquals("Leyendo", PropietariosMondayFlow.statusLabel("reading"))
        assertEquals("No usar", PropietariosMondayFlow.NoColumn)
        assertEquals("Propietario", MondayTarget.all.first().label)
        I18n.withLanguage("en") {
            assertEquals("Reading", PropietariosMondayFlow.statusLabel("reading"))
            assertEquals("No usar", PropietariosMondayFlow.NoColumn)
            assertEquals("Owner", tr(MondayTarget.all.first().label))
            assertEquals("New development (with the board name)", tr(PropietariosMondayFlow.NewDevelopment))
            assertEquals(
                "The mapping uses a column that no longer exists in the board.",
                PropietariosMondayFlow.mappingProblem(MondayMapping(mapOf(MondayTarget.Owner to "gone")), emptyList()),
            )
        }
    }
}
