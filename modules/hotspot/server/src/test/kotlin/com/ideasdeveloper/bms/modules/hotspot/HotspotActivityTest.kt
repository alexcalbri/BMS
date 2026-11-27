package com.ideasdeveloper.bms.modules.hotspot

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.modules.McpToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Activity rows of the hotspot's writes (ACTIVITY_LOG.md §5). Runs on a fake tenant: statements answer
 * from the rows the test gives them, updates count as one row, and every write is listed in [events]
 * so a test can see that a row was written before the delete it describes.
 */
class HotspotActivityTest {
    private val events = mutableListOf<String>()

    // A fake tenant database. [rows] answers the SELECTs by their SQL text; the writes are only listed.
    private fun tenant(rows: (String) -> List<Map<String, Any?>> = { emptyList() }): Connection =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            when (method.name) {
                "getAutoCommit" -> true
                "prepareStatement" -> statement(args[0] as String, rows)
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "FakeTenant"
                else -> null
            }
        } as Connection

    private fun statement(sql: String, rows: (String) -> List<Map<String, Any?>>) =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, method, _ ->
            when (method.name) {
                "executeUpdate" -> {
                    events += "sql:" + sql.trim().take(60)
                    1
                }
                "executeQuery" -> resultSet(rows(sql.trim()))
                else -> null
            }
        }

    private fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
        var index = -1
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, method, args ->
            fun value(): Any? {
                val column = args[0]
                return if (column is String) rows[index][column] else rows[index].values.elementAt((column as Int) - 1)
            }
            when (method.name) {
                "next" -> {
                    index++
                    index < rows.size
                }
                "getString" -> value() as String?
                "getObject" -> value()
                "getBoolean" -> value() as? Boolean ?: false
                "getInt" -> (value() as? Number)?.toInt() ?: 0
                "getArray", "getTimestamp" -> null
                else -> null
            }
        } as ResultSet
    }

    private val ana = ActivityLogTesting.user("Ana Ruiz")

    private fun network(id: UUID, ssid: String, shared: Boolean = false) = HotspotNetwork(
        id = id, ssid = ssid, accessMode = HotspotNetworks.Form, accessSources = emptyList(),
        accessMinutes = null, reconnectMinutes = null, shared = shared,
    )

    private fun networkRow(network: HotspotNetwork) = mapOf(
        "id" to network.id, "ssid" to network.ssid, "access_mode" to network.accessMode, "access_sources" to null,
        "access_minutes" to network.accessMinutes, "reconnect_minutes" to network.reconnectMinutes, "shared" to network.shared,
    )

    private fun settings(networkName: String = "WiFi", apiKey: String? = null) = HotspotSettings(
        domain = null, networkName = networkName, portalTitle = "WiFi", portalSubtitle = "", logoUrl = null,
        consentText = "Acepto", termsUrl = null, privacyUrl = null,
        footerText = "", successUrl = null, unifiConsoleId = "console-1", unifiSiteId = "site-1", unifiApiKey = apiKey,
        accessMinutes = 60, dataLimitMb = 0, rxKbps = 0, txKbps = 0, reconnectMinutes = 1440, codeTtlMinutes = 15,
        emailSubject = "Codigo", crmAccessEnabled = false, crmStageId = null, crmScheduleMode = "daily",
        crmCheckIntervalMinutes = 60, crmCheckTime = LocalTime.of(7, 0),
    )

    @Test
    fun networkCreateEditAndDeleteWriteTheirRows() {
        val id = UUID.randomUUID()
        val created = ActivityLogTesting.rows { HotspotActivity.saveNetwork(tenant(), ana, null, network(id, "WiFi Sala")) }
        assertEquals(ActivityActions.Created, created.single().action)
        assertEquals("Ana Ruiz", created.single().display)
        assertEquals("WiFi Sala", created.single().change("ssid")!!.new)

        val edited = ActivityLogTesting.rows {
            HotspotActivity.saveNetwork(tenant(), ana, network(id, "WiFi Sala"), network(id, "WiFi Oficina", shared = true))
        }
        assertEquals(ActivityActions.Updated, edited.single().action)
        assertEquals("Modifico la red WiFi Oficina.", edited.single().entry.summary)
        assertEquals("WiFi Sala", edited.single().change("ssid")!!.old)
        assertEquals("WiFi Oficina", edited.single().change("ssid")!!.new)
        assertEquals("Si", edited.single().change("shared")!!.new)

        // Saving with nothing changed writes no row.
        assertTrue(ActivityLogTesting.rows { HotspotActivity.saveNetwork(tenant(), ana, network(id, "WiFi Oficina"), network(id, "WiFi Oficina")) }.isEmpty())
    }

    @Test
    fun deletingANetworkWritesItsRowBeforeTheDelete() {
        val id = UUID.randomUUID()
        val existing = network(id, "WiFi Oficina")
        val tenant = tenant { sql -> if (sql.startsWith("SELECT * FROM hotspot_networks WHERE id")) listOf(networkRow(existing)) else emptyList() }

        // Rows and statements go to the same list: the row must come before the DELETE.
        val deleted = ActivityLog.capturing({ _, entry -> events += "row:${entry.action}" }) {
            HotspotActivity.deleteNetwork(tenant, ana, id)
        }
        assertTrue(deleted)
        val rowAt = events.indexOf("row:deleted")
        val deleteAt = events.indexOfFirst { it.startsWith("sql:DELETE FROM hotspot_networks") }
        assertTrue(rowAt >= 0 && deleteAt > rowAt, events.toString())

        // The row describes the network that is gone: it lives on after the delete.
        val rows = ActivityLogTesting.rows { HotspotActivity.deleteNetwork(tenant, ana, id) }
        assertEquals(ActivityActions.Deleted, rows.single().action)
        assertEquals("WiFi Oficina", rows.single().change("ssid")!!.old)
        assertNull(rows.single().change("ssid")!!.new)
        assertEquals("Elimino la red WiFi Oficina.", rows.single().entry.summary)

        // A network that does not exist writes nothing and is not deleted.
        val missing = ActivityLogTesting.rows { assertFalse(HotspotActivity.deleteNetwork(tenant(), ana, id)) }
        assertTrue(missing.isEmpty())
    }

    @Test
    fun mcpDeleteNetworkThroughAgentIsRecordedAsTheAgent() {
        val id = UUID.randomUUID()
        val existing = network(id, "WiFi Oficina")
        val tenant = tenant { sql -> if (sql.startsWith("SELECT * FROM hotspot_networks WHERE id")) listOf(networkRow(existing)) else emptyList() }
        val permissions = ActivityLogTesting.permissions(granted = setOf(HotspotScreens.SettingsManage)).throughAgent("Claude")
        val call = McpToolCall(
            JsonObject(mapOf("id" to JsonPrimitive(id.toString()))), permissions, tenant,
            ActivityLogTesting.accessWithoutDatabase(), "Claude", HotspotModule.id,
        )
        val tool = HotspotMcpTools.tools.single { it.name == "delete_network" }

        val rows = ActivityLogTesting.rows { tool.handler(call) }

        val row = rows.single()
        assertTrue(row.display.startsWith("Agente de "))
        assertEquals("Agente de Ana Ruiz", row.display)
        assertEquals(ActivityActions.Deleted, row.action)
        assertEquals("WiFi Oficina", row.change("ssid")!!.old)
    }

    @Test
    fun mcpSaveNetworkThroughAgentIsRecordedAsTheAgent() {
        val tenant = tenant()
        val permissions = ActivityLogTesting.permissions(granted = setOf(HotspotScreens.SettingsManage)).throughAgent("Claude")
        val arguments = JsonObject(mapOf("ssid" to JsonPrimitive("Invitados"), "accessMode" to JsonPrimitive(HotspotNetworks.Form)))
        val tool = HotspotMcpTools.tools.single { it.name == "save_network" }

        val rows = ActivityLogTesting.rows {
            tool.handler(McpToolCall(arguments, permissions, tenant, ActivityLogTesting.accessWithoutDatabase(), "Claude", HotspotModule.id))
        }

        assertEquals("Agente de Ana Ruiz", rows.single().display)
        assertEquals(ActivityActions.Created, rows.single().action)
        assertEquals("Invitados", rows.single().change("ssid")!!.new)
    }

    @Test
    fun settingsChangeRecordsTheFieldsAndNeverAnApiKey() {
        val before = settings(networkName = "WiFi", apiKey = "CLAVE-ANTIGUA-1")
        val after = settings(networkName = "WiFi Nuevo", apiKey = "CLAVE-NUEVA-2")

        val rows = ActivityLogTesting.rows { HotspotActivity.saveSettings(tenant(), ana, before, after) }

        assertEquals(1, rows.size)
        assertEquals(ActivityActions.SettingsChanged, rows.single().action)
        assertEquals("WiFi", rows.single().change("networkName")!!.old)
        assertEquals("WiFi Nuevo", rows.single().change("networkName")!!.new)
        // The UniFi key is the controller's: the portal settings row holds none of it, and no value of it at all.
        assertTrue(rows.single().entry.changes.none { it.field.startsWith("unifi") })
        assertFalse(rows.toString().contains("CLAVE"))
    }

    @Test
    fun integrationOnAndOffIsRecordedWithTheActiveSet() {
        val rows = ActivityLogTesting.rows { HotspotIntegrations.setEnabled(tenant(), HotspotIntegrations.Users, true, ana) }

        assertEquals(ActivityActions.Enabled, rows.single().action)
        assertEquals("Activo la integracion Usuarios.", rows.single().entry.summary)
        assertEquals("Ninguna", rows.single().change("integraciones_activas")!!.old)
        assertEquals("Usuarios", rows.single().change("integraciones_activas")!!.new)

        val off = ActivityLogTesting.rows {
            val enabledNow = tenant { sql -> if (sql.startsWith("SELECT id FROM hotspot_integrations")) listOf(mapOf("id" to HotspotIntegrations.Users)) else emptyList() }
            HotspotIntegrations.setEnabled(enabledNow, HotspotIntegrations.Users, false, ana)
        }
        assertEquals(ActivityActions.Disabled, off.single().action)
        assertEquals("Desactivo la integracion Usuarios.", off.single().entry.summary)
    }

    @Test
    fun revokingAndRestoringAUserCodeIsRecorded() {
        val user = HotspotUser(UUID.randomUUID(), "ana", "US23456789", serverOwner = false, active = true, displayName = "Ana Ruiz")

        val revoked = ActivityLogTesting.rows { HotspotActivity.setUserAccess(tenant(), ana, user, enabled = false) }
        assertEquals(ActivityActions.Updated, revoked.single().action)
        assertEquals("Revoco el acceso WiFi de Ana Ruiz.", revoked.single().entry.summary)
        assertEquals("Activo", revoked.single().change("acceso_wifi")!!.old)
        assertEquals("Revocado", revoked.single().change("acceso_wifi")!!.new)

        val restored = ActivityLogTesting.rows { HotspotActivity.setUserAccess(tenant(), ana, user, enabled = true) }
        assertEquals("Restauro el acceso WiFi de Ana Ruiz.", restored.single().entry.summary)
    }

    @Test
    fun grantsToOtherCompaniesAreRecordedWhenAddedAndRemoved() {
        val userId = UUID.randomUUID()
        val added = ActivityLogTesting.rows { HotspotActivity.addGrant(tenant(), ana, "acme", "Socios", userId, "Luis Mora") }
        assertEquals(ActivityActions.Created, added.single().action)
        assertEquals("acme · Socios", added.single().change("network")!!.new)

        val grantId = UUID.randomUUID()
        val grantRow = mapOf("id" to grantId, "company_code" to "acme", "ssid" to "Socios", "user_id" to userId)
        val removed = ActivityLogTesting.rows {
            HotspotActivity.deleteGrant(tenant { sql -> if (sql.startsWith("SELECT id, company_code")) listOf(grantRow) else emptyList() }, ana, grantId)
        }
        assertEquals(ActivityActions.Deleted, removed.single().action)
        assertEquals("acme · Socios", removed.single().change("network")!!.old)
    }

    @Test
    fun exportingTheReportIsRecordedAsAnExport() {
        val rows = ActivityLogTesting.rows {
            HotspotActivity.exportReport(tenant(), ana, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7))
        }
        assertEquals(ActivityActions.Exported, rows.single().action)
        assertEquals("Exporto el reporte de visitantes del 2026-11-01 al 2026-11-07 a CSV.", rows.single().entry.summary)
    }

    @Test
    fun templateRowSaysWhatChangedButNotTheHtml() {
        val view = HotspotTemplates.views.first()
        val rows = ActivityLogTesting.rows { HotspotActivity.saveTemplate(tenant(), ana, view, "<form>MARCA-SECRETA</form>") }

        assertEquals(ActivityActions.SettingsChanged, rows.single().action)
        assertEquals("Diseno de BMS", rows.single().change("html")!!.old)
        assertFalse(rows.toString().contains("MARCA-SECRETA"))
    }

    @Test
    fun theWritesOfTheModuleAreAllListed() {
        // Every write tool of the module is covered by the tests above (ACTIVITY_LOG.md §5).
        assertEquals(
            listOf(
                "hotspot_add_access_grant", "hotspot_delete_network", "hotspot_remove_access_grant", "hotspot_save_network",
                "hotspot_set_integration", "hotspot_unifi_choose_console", "hotspot_unifi_choose_site", "hotspot_update_settings",
            ),
            ActivityLogTesting.writeTools(HotspotServerModule),
        )
        // The staff screens' writes go through the shared UI route (saves, network, integration, access and design actions).
        val routes = ActivityLogTesting.writeRoutes(HotspotServerModule)
        assertTrue(routes.any { it == "POST ${HotspotScreens.UiRoute}/{screen}" }, routes.toString())
    }
}
