package com.ideasdeveloper.bms.extensions.cloudbeds

import com.ideasdeveloper.bms.modules.crm.CbGuest
import com.ideasdeveloper.bms.modules.crm.CbRoomRequest
import com.ideasdeveloper.bms.modules.crm.CrmCloudbedsException
import com.ideasdeveloper.bms.modules.crm.CrmCloudbedsSettings
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CloudbedsI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(CrmCloudbedsExtension)

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("cloudbeds", CrmCloudbedsExtension.manifest)

    @Test
    fun apiErrorsAreTranslatedAndSpanishIsUnchanged() {
        assertEquals(
            "Cloudbeds no devolvio el numero de reserva.",
            assertFailsWith<CrmCloudbedsException> { CrmCloudbeds.parseReservation(json("""{"success":true}""")) }.message,
        )
        I18n.withLanguage("en") {
            assertEquals(
                "Cloudbeds did not return the reservation number.",
                assertFailsWith<CrmCloudbedsException> { CrmCloudbeds.parseReservation(json("""{"success":true}""")) }.message,
            )
        }
    }

    @Test
    fun outgoingFormIsTheSameInEnglish() {
        // Payloads sent to Cloudbeds are never translated: the form is identical in both languages.
        val settings = CrmCloudbedsSettings(true, "key", "123", "cash", false, null, 1, 9, true, "")
        val lead = UUID.fromString("00000000-0000-0000-0000-000000000001")
        fun form() = CrmCloudbeds.reservationForm(
            settings, lead, LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 27),
            CbGuest("Megan", "Dravet", "megan@example.com", "+16304602072", "US", ""),
            listOf(CbRoomRequest("101", "10", 2, 0)),
        )
        val spanish = form()
        I18n.withLanguage("en") { assertEquals(spanish, form()) }
    }

    @Test
    fun manifestTextsAreTranslated() {
        assertEquals("Cloudbeds", CrmCloudbedsExtension.manifest.displayName)
        I18n.withLanguage("en") {
            assertEquals(
                "Finds free rooms, creates reservations and checks guests in at Cloudbeds from the lead; sends the welcome and archives the lead when the guest checks out.",
                I18n.translate(CrmCloudbedsExtension.manifest.description),
            )
        }
    }
}
