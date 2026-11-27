package com.ideasdeveloper.bms.extensions.encuentra24

import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.FakeSecrets
import com.ideasdeveloper.bms.modules.crm.FakeTenant
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the Encuentra24 extension writes in the company activity log: its settings (the API key only as "changed") and the listings it links to leads. */
class Encuentra24ActivityLogTest {
    private val person = ActivityLogTesting.user("Ana Ruiz")
    private val agent = ActivityLogTesting.permissions(actor = person).throughAgent("Claude").activityActor

    @BeforeTest
    fun secrets() = FakeSecrets.install()

    @AfterTest
    fun noSecrets() = FakeSecrets.remove()

    private fun <T> logged(block: () -> T): Pair<T, List<ActivityLogTesting.Captured>> = ActivityLogTesting.capture(block)

    @Test
    fun theSettingsFormLogsOldAndNewWithoutTheApiKey() {
        val tenant = FakeTenant().rows("from tenant_extensions", listOf("enabled"))
            .rows("from ext_encuentra24__settings", listOf(false, FakeSecrets.sealed("claveVieja123"), "casa-vieja", null, "", ""))
        val values = mapOf("enabled" to "true", "slug" to "casa-nueva", "apiKey" to "claveNuevaE24Secreta")
        val (_, rows) = logged { Encuentra24SettingsStore.saveForm(CrmHost, tenant.connection, values, agent) }
        val row = rows.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertEquals("Encuentra24", row.entry.entityLabel)
        assertEquals("casa-vieja", row.change("slug")!!.old)
        assertEquals("casa-nueva", row.change("slug")!!.new)
        assertEquals("Si", row.change("enabled")!!.new)
        assertTrue(row.change("apiKey")!!.secret)
        listOf("claveVieja123", "claveNuevaE24Secreta").forEach { assertFalse(row.entry.toString().contains(it), it) }
        assertEquals("Agente de Ana Ruiz", row.display)
    }

    @Test
    fun aListingLinkedToALeadIsLoggedAsTheIntegrations() {
        val lead = UUID.randomUUID()
        val tenant = FakeTenant().rows("select c.display_name from crm_leads l", listOf("Luis Mora")).rows("insert into crm_lead_items", listOf(true))
        val (linked, rows) = logged { CrmEncuentra24.rememberAd(CrmHost, tenant.connection, lead, "123456", "", "Casa en Boquete") }
        assertTrue(linked)
        val row = rows.single()
        assertEquals("Encuentra24", row.display)
        assertEquals("integration", row.actor.kind)
        assertEquals("Luis Mora", row.entry.entityLabel)
        assertEquals("Casa en Boquete", row.change("item")!!.new)
    }
}
