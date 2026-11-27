package com.ideasdeveloper.bms.extensions.cloudbeds

import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.CrmTemplate
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

/** What the Cloudbeds extension writes in the company activity log: its settings (the API key only as "changed") and the welcome email switch. */
class CloudbedsActivityLogTest {
    private val person = ActivityLogTesting.user("Ana Ruiz")
    private val agent = ActivityLogTesting.permissions(actor = person).throughAgent("Claude").activityActor

    @BeforeTest
    fun secrets() = FakeSecrets.install()

    @AfterTest
    fun noSecrets() = FakeSecrets.remove()

    private fun <T> logged(block: () -> T): Pair<T, List<ActivityLogTesting.Captured>> = ActivityLogTesting.capture(block)

    private val welcome = CrmTemplate(UUID.randomUUID(), "Bienvenida", "Hola", "Texto")

    private fun stored(welcomeEnabled: Boolean = false) = FakeTenant().rows("from tenant_extensions", listOf("enabled"))
        .rows("from ext_cloudbeds__settings", listOf(true, FakeSecrets.sealed("apiKeyVieja99"), "111", "cash", welcomeEnabled, welcome.id, 1, 9, true, "", "NI"))

    @Test
    fun theSettingsFormLogsOldAndNewWithoutTheApiKey() {
        val values = mapOf(
            "enabled" to "true", "apiKey" to "apiKeyNuevaSecreta77", "propertyId" to "222", "paymentMethod" to "Efectivo", "welcomeEnabled" to "true",
            "welcomeTemplate" to "Bienvenida", "welcomeDaysBefore" to "2", "welcomeHour" to "9", "archiveOnCheckout" to "true", "defaultCountry" to "PA",
        )
        val tenant = stored()
        val (_, rows) = logged { CloudbedsSettingsStore.saveForm(CrmHost, tenant.connection, values, listOf(welcome), agent) }
        val row = rows.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertEquals("111", row.change("propertyId")!!.old)
        assertEquals("222", row.change("propertyId")!!.new)
        assertEquals("NI", row.change("defaultCountry")!!.old)
        assertEquals("PA", row.change("defaultCountry")!!.new)
        assertEquals("No", row.change("welcomeEnabled")!!.old)
        assertEquals("Si", row.change("welcomeEnabled")!!.new)
        assertTrue(row.change("apiKey")!!.secret)
        listOf("apiKeyVieja99", "apiKeyNuevaSecreta77").forEach { assertFalse(row.entry.toString().contains(it), it) }
        assertEquals("Agente de Ana Ruiz", row.display)
    }

    @Test
    fun turningTheWelcomeEmailOffIsLoggedAndTurningItOffAgainIsNot() {
        val on = stored(welcomeEnabled = true)
        val (_, rows) = logged { CloudbedsSettingsStore.setWelcomeEnabled(CrmHost, on.connection, false, person) }
        assertEquals("Si", rows.single().change("welcomeEnabled")!!.old)
        assertEquals("No", rows.single().change("welcomeEnabled")!!.new)
        val off = stored(welcomeEnabled = false)
        assertTrue(logged { CloudbedsSettingsStore.setWelcomeEnabled(CrmHost, off.connection, false, person) }.second.isEmpty())
    }
}
