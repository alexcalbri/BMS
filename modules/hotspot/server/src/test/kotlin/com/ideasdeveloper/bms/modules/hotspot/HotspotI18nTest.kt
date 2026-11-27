package com.ideasdeveloper.bms.modules.hotspot

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadModuleCatalog
import com.ideasdeveloper.bms.server.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalTime
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotspotI18nTest {
    private val settings = HotspotSettings(
        domain = null, networkName = "MiRed", portalTitle = "Mi hotel", portalSubtitle = "", logoUrl = null,
        consentText = "Acepto", termsUrl = "https://a.test/t", privacyUrl = null,
        footerText = "", successUrl = null, unifiConsoleId = null, unifiSiteId = null, unifiApiKey = null,
        accessMinutes = 60, dataLimitMb = 0, rxKbps = 0, txKbps = 0, reconnectMinutes = 1440, codeTtlMinutes = 15,
        emailSubject = "Codigo",
        crmAccessEnabled = false, crmStageId = null, crmScheduleMode = "daily",
        crmCheckIntervalMinutes = 60, crmCheckTime = LocalTime.of(7, 0),
    )
    private val guest = HotspotPortalPages.GuestContext("aa:bb:cc:dd:ee:ff", "", "MiRed", "")

    // Tests build no ModuleRegistry, so the module's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadModuleCatalog(HotspotServerModule)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertModule("hotspot", HotspotServerModule.definition)

    @Test
    fun portalFormIsInEnglishForAGuestWhoseBrowserAsksForIt() {
        val spanish = HotspotPortalPages.form(settings, guest)
        val english = I18n.withLanguage("en") { HotspotPortalPages.form(settings, guest, error = tr("Escribe tu nombre.")) }
        assertTrue("<html lang=\"es\">" in spanish && "Conectate ahora" in spanish)
        assertTrue("<html lang=\"en\">" in english)
        assertTrue("Connect now" in english && "Send code" in english && "Enter your name." in english, english)
        assertTrue(">Terms</a>" in english)
        // Field names, the endpoint and the hidden device fields do not depend on the language.
        listOf("action=\"/guest/s/default/register\"", "name=\"email\"", "name=\"terms\" value=\"si\"", "name=\"id\" value=\"aa:bb:cc:dd:ee:ff\"")
            .forEach { assertTrue(it in spanish && it in english, it) }
        // The company's own texts stay as written.
        assertTrue("Mi hotel" in english && "Acepto" in english)
    }

    @Test
    fun portalMessagesAndDurationsFollowTheLanguage() {
        I18n.withLanguage("en") {
            assertEquals("1 day 2 h 5 min", HotspotPortalPages.timeLeft(1565))
            val wait = HotspotPortalPages.wait(settings, 90, byCode = true)
            assertTrue("You already used your access" in wait && "This code connected recently" in wait && "2 hours" in wait, wait)
            val code = HotspotPortalPages.code(settings, "ana@test.com", "")
            assertTrue("Check your email" in code && "data-busy=\"Connecting...\"" in code && "expires in 15 minutes if you do not use it" in code, code)
            assertTrue("name=\"email\" value=\"ana@test.com\"" in code && "pending" !in code)
            val success = HotspotPortalPages.success(settings, "Ana", null)
            assertTrue("You are connected" in success && "Your WiFi session lasts <strong>60 minutes</strong>." in success, success)
        }
        assertEquals("1 dia 2 h 5 min", HotspotPortalPages.timeLeft(1565))
    }

    @Test
    fun accessCodePageNamesTheKindOfCodeInEachLanguage() {
        val customer = I18n.withLanguage("en") { HotspotPortalPages.accessCode(settings, guest) }
        assertTrue("Enter your customer code to connect to this network." in customer && ">Customer code</label>" in customer, customer)
        val user = I18n.withLanguage("en") { HotspotPortalPages.accessCode(settings, guest, byUser = true) }
        assertTrue("Enter your user code to connect to this network." in user)
        assertTrue("Escribe tu codigo de usuario para conectarte a esta red." in HotspotPortalPages.accessCode(settings, guest, byUser = true))
    }

    @Test
    fun validationErrorsAreEnglishAndValuesDoNotChange() {
        val failure = assertFailsWith<HotspotValidationException> {
            I18n.withLanguage("en") { HotspotSettingsRepository.merge(settings, JsonObject(mapOf("accessMinutes" to JsonPrimitive("0")))) }
        }
        assertEquals("The access time must be between 1 and 10080.", failure.message)
        val spanish = assertFailsWith<HotspotValidationException> {
            HotspotSettingsRepository.merge(settings, JsonObject(mapOf("accessMinutes" to JsonPrimitive("0"))))
        }
        assertEquals("El tiempo de acceso debe estar entre 1 y 10080.", spanish.message)
        val network = assertFailsWith<HotspotValidationException> {
            I18n.withLanguage("en") { HotspotNetworks.parse(UUID.randomUUID(), mapOf("ssid" to "")) }
        }
        assertEquals("The network name (SSID) is required.", network.message)
        // The stored access mode and the option values stay Spanish: the app posts them back.
        assertEquals("Codigo de cliente", I18n.withLanguage("en") { HotspotNetworks.modeLabel(HotspotNetworks.CustomerCode) })
        assertEquals("codigo_cliente", HotspotNetworks.parse(UUID.randomUUID(), mapOf("ssid" to "A", "accessMode" to "Codigo de cliente", "source_hotspot" to "true")).accessMode)
    }

    @Test
    fun templateProblemsAreShownInTheViewerLanguage() {
        val html = "<form action=\"/guest/s/default/register\" method=\"post\"></form>"
        val spanish = HotspotTemplates.validate(HotspotTemplates.Form, html)
        val english = I18n.withLanguage("en") { HotspotTemplates.validate(HotspotTemplates.Form, html) }
        assertEquals(spanish.size, english.size)
        assertTrue(english.any { it.startsWith("The field name=\"name\" is missing inside the form.") }, english.toString())
        assertFalse(english.any { "Falta" in it })
    }

    @Test
    fun csvExportKeepsSpanishHeadersWhateverTheLanguage() {
        // The header line is built from the Spanish labels; a stub connection must not be touched before it.
        val labels = I18n.withLanguage("en") { HotspotReports.columns.map { it.label } }
        assertEquals(listOf("Date", "Name", "Email", "Mobile phone", "Device", "Access", "Connected"), labels)
        assertEquals(listOf("Fecha", "Nombre", "Correo", "Celular", "Dispositivo", "Acceso", "Conectado"), HotspotReports.columns.map { it.label })
    }
}
