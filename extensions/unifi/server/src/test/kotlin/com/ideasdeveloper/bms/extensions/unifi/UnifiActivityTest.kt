package com.ideasdeveloper.bms.extensions.unifi

import com.ideasdeveloper.bms.modules.hotspot.HotspotSettings
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The UniFi configuration rows (ACTIVITY_LOG.md §5): the console, the site and the API key, which is
 * never stored, only that it changed. Runs with no database: the rows are captured, and the fake
 * connection fails on any statement, which proves nothing else is written from here.
 */
class UnifiActivityTest {
    private val noDatabase = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        throw AssertionError("No debia usar la conexion: ${method.name}")
    } as Connection

    private fun settings(console: String? = "console-1", site: String? = "site-1", apiKey: String? = null) = HotspotSettings(
        domain = null, networkName = "WiFi", portalTitle = "WiFi", portalSubtitle = "", logoUrl = null,
        consentText = "Acepto", termsUrl = null, privacyUrl = null,
        footerText = "", successUrl = null, unifiConsoleId = console, unifiSiteId = site, unifiApiKey = apiKey,
        accessMinutes = 60, dataLimitMb = 0, rxKbps = 0, txKbps = 0, reconnectMinutes = 1440, codeTtlMinutes = 15,
        emailSubject = "Codigo", crmAccessEnabled = false, crmStageId = null, crmScheduleMode = "daily",
        crmCheckIntervalMinutes = 60, crmCheckTime = LocalTime.of(7, 0),
    )

    // The actor of the request: Ana Ruiz, through her agent.
    private val agent = ActivityLogTesting.user("Ana Ruiz").viaAgent("Claude")

    @Test
    fun apiKeyChangeIsRecordedWithoutTheKey() {
        val before = settings(apiKey = "CLAVE-ANTIGUA-1")
        val after = settings(apiKey = "CLAVE-NUEVA-2")

        val rows = ActivityLogTesting.rows { UnifiActivity.record(noDatabase, agent, before, after) }

        assertEquals(1, rows.size)
        val row = rows.single()
        assertTrue(row.display.startsWith("Agente de "))
        assertEquals("Agente de Ana Ruiz", row.display)
        assertEquals(ActivityActions.SettingsChanged, row.action)
        val key = row.change("unifiApiKey")!!
        assertTrue(key.secret)
        assertEquals("API key de UniFi", key.label)
        assertNull(key.old)
        assertNull(key.new)
        // No value of the key reaches the row, in any field.
        assertFalse(row.toString().contains("CLAVE"))
    }

    @Test
    fun consoleAndSiteChangesKeepTheirValues() {
        val rows = ActivityLogTesting.rows {
            UnifiActivity.record(noDatabase, agent, settings(console = "console-1", site = "site-1"), settings(console = "console-2", site = "site-9"))
        }

        val row = rows.single()
        assertEquals("console-1", row.change("unifiConsoleId")!!.old)
        assertEquals("console-2", row.change("unifiConsoleId")!!.new)
        assertEquals("Consola de UniFi", row.change("unifiConsoleId")!!.label)
        assertEquals("site-9", row.change("unifiSiteId")!!.new)
        assertEquals("Cambio la configuracion de UniFi.", row.entry.summary)
    }

    @Test
    fun savingTheSameConfigurationWritesNothing() {
        val same = settings(apiKey = "CLAVE-1")
        assertTrue(ActivityLogTesting.rows { UnifiActivity.record(noDatabase, agent, same, same) }.isEmpty())
    }

    @Test
    fun theControllerRecordsItsRowThroughTheExtensionHook() {
        // The hotspot calls the controller's hook on the same connection; the hook writes the UniFi row.
        val rows = ActivityLogTesting.rows {
            UnifiExtension.recordConfiguration(noDatabase, agent, settings(apiKey = "A"), settings(apiKey = "B"))
        }
        assertEquals("API key de UniFi", rows.single().change("unifiApiKey")!!.label)
    }
}
