package com.ideasdeveloper.bms.extensions.unifi

import com.ideasdeveloper.bms.modules.hotspot.HotspotSettings
import com.ideasdeveloper.bms.modules.hotspot.NetworkControllerException
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import java.time.LocalTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnifiI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(UnifiExtension)

    // Not configured: the checks below fail before any call to UniFi.
    private val unconfigured = HotspotSettings(
        domain = null, networkName = "WiFi", portalTitle = "WiFi", portalSubtitle = "", logoUrl = null,
        consentText = "Acepto", termsUrl = null, privacyUrl = null,
        footerText = "", successUrl = null, unifiConsoleId = null, unifiSiteId = null, unifiApiKey = null,
        accessMinutes = 60, dataLimitMb = 0, rxKbps = 0, txKbps = 0, reconnectMinutes = 1440, codeTtlMinutes = 15,
        emailSubject = "Codigo",
        crmAccessEnabled = false, crmStageId = null, crmScheduleMode = "daily",
        crmCheckIntervalMinutes = 60, crmCheckTime = LocalTime.of(7, 0),
    )

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("unifi", UnifiExtension.manifest)

    @Test
    fun controllerErrorsAndDescriptionFollowTheLanguage() {
        assertEquals(
            "Configura la consola y el sitio de UniFi primero.",
            assertFailsWith<NetworkControllerException> { UnifiController.wifiNetworks(unconfigured) }.message,
        )
        I18n.withLanguage("en") {
            assertEquals(
                "Set up the UniFi console and site first.",
                assertFailsWith<NetworkControllerException> { UnifiController.wifiNetworks(unconfigured) }.message,
            )
            assertEquals(
                "Authorizes guests on a UniFi console through UniFi Site Manager (api.ui.com).",
                UnifiController.description,
            )
            assertEquals("UniFi", UnifiController.name)
        }
    }

    @Test
    fun storedGuestMessagesStayInSpanishForAnyLanguage() {
        // The message is stored in hotspot_accesses.unifi_message, so it is never translated.
        I18n.withLanguage("en") {
            assertEquals("UniFi no esta configurado.", UnifiClient.authorizeGuest(unconfigured, "aa:bb:cc:dd:ee:ff", "guest").message)
            assertEquals("UniFi no esta configurado.", UnifiClient.unauthorizeGuest(unconfigured, "aa:bb:cc:dd:ee:ff").message)
        }
    }

    @Test
    fun manifestTextsAreTranslated() {
        assertEquals("UniFi", UnifiExtension.manifest.displayName)
        I18n.withLanguage("en") {
            assertEquals(
                "UniFi network controller: authorizes and disconnects portal guests on a UniFi console through UniFi Site Manager (api.ui.com) and lists its WiFi networks.",
                I18n.translate(UnifiExtension.manifest.description),
            )
        }
    }
}
