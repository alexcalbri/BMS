package com.ideasdeveloper.bms.extensions.meta

import com.ideasdeveloper.bms.modules.crm.CrmMetaServerApp
import com.ideasdeveloper.bms.modules.crm.FakeSecrets
import com.ideasdeveloper.bms.modules.crm.FakeTenant
import com.ideasdeveloper.bms.modules.crm.MetaEvent
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the Meta extension writes in the company activity log: its settings (secrets only as "changed") and what its webhooks bring in. */
class MetaActivityLogTest {
    private val person = ActivityLogTesting.user("Ana Ruiz")
    private val agent = ActivityLogTesting.permissions(actor = person).throughAgent("Claude").activityActor

    @BeforeTest
    fun secrets() = FakeSecrets.install()

    @AfterTest
    fun noSecrets() = FakeSecrets.remove()

    private fun <T> logged(block: () -> T): Pair<T, List<ActivityLogTesting.Captured>> = ActivityLogTesting.capture(block)

    @Test
    fun savingTheSettingsLogsOldAndNewAndOnlyThatTheTokensChanged() {
        val tenant = FakeTenant()
        val before = CrmMeta.load(tenant.connection)
        val saved = before.copy(
            enabled = true, systemToken = "EAAGsecretoDeWhatsAppMuyLargo", pageId = "123456", pageName = "Casa Las Colinas", pageToken = "EAAGsecretoDeLaPagina", phoneNumberId = "998877",
            appSecret = "appSecretMuySecreto",
        )
        val (_, rows) = logged { CrmMeta.save(tenant.connection, saved, agent) }
        val row = rows.single()
        assertEquals("crm", row.entry.moduleId)
        assertEquals("settings", row.entry.entityType)
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertEquals("Agente de Ana Ruiz", row.display)
        assertEquals("Si", row.change("enabled")!!.new)
        assertEquals("Casa Las Colinas", row.change("pageName")!!.new)
        assertEquals("998877", row.change("phoneNumberId")!!.new)
        listOf("systemToken", "pageToken", "appSecret").forEach { field ->
            val change = row.change(field)!!
            assertTrue(change.secret, field)
            assertEquals(null, change.new, field)
            assertEquals(null, change.old, field)
        }
        val everything = row.entry.toString()
        listOf("EAAGsecretoDeWhatsAppMuyLargo", "EAAGsecretoDeLaPagina", "appSecretMuySecreto").forEach { assertFalse(everything.contains(it), it) }
    }

    @Test
    fun savingTheSameSettingsAgainLogsNothing() {
        val tenant = FakeTenant()
        val current = CrmMeta.load(tenant.connection)
        assertTrue(logged { CrmMeta.save(tenant.connection, current, person) }.second.isEmpty())
    }

    @Test
    fun theServerAppMasksItsSecrets() {
        val tenant = FakeTenant()
        val app = CrmMetaServerApp(enabled = true, appId = "5551234", appSecret = "elSecretoDeLaApp", verifyToken = "verificaToken1234", pagesConfigId = "11", whatsAppConfigId = "22")
        val (_, rows) = logged { CrmMetaServer.saveApp(tenant.connection, app, person) }
        val row = rows.single()
        assertEquals("App de Meta del servidor", row.entry.entityLabel)
        assertEquals("5551234", row.change("appId")!!.new)
        assertTrue(row.change("appSecret")!!.secret)
        assertTrue(row.change("verifyToken")!!.secret)
        assertFalse(row.entry.toString().contains("elSecretoDeLaApp"))
        assertFalse(row.entry.toString().contains("verificaToken1234"))
    }

    @Test
    fun disconnectingLogsWhoDidIt() {
        val tenant = FakeTenant().rows(
            "from ext_meta__settings",
            listOf(true, FakeSecrets.sealed("secreto"), null, FakeSecrets.sealed("sistema"), "123", "Pagina", FakeSecrets.sealed("pagina"), null, null, "998", false, true, null, null, null, "own", null, null),
        )
        val (_, rows) = logged { CrmMetaServer.disconnect(tenant.connection, "acme", agent) }
        val row = rows.single()
        assertEquals("No", row.change("enabled")!!.new)
        assertEquals("123", row.change("pageId")!!.old)
        assertEquals(null, row.change("pageId")!!.new)
        assertTrue(row.change("systemToken")!!.secret)
        assertEquals("Agente de Ana Ruiz", row.display)
        // The same disconnection asked by Meta itself (data deletion callback) is the integration's.
        val byMeta = FakeTenant().rows("from ext_meta__settings", listOf(true, null, null, null, "123", "Pagina", null, null, null, null, false, true, null, null, null, "own", null, null))
        assertEquals("Meta", logged { CrmMetaServer.disconnect(byMeta.connection, "acme", ActivityActor.integration("Meta")) }.second.single().display)
    }

    @Test
    fun anEchoOfTheWhatsAppBusinessAppIsTheIntegrationsMessageOfTheLead() {
        val lead = UUID.randomUUID()
        val tenant = FakeTenant().rows("select c.display_name from crm_leads l", listOf("Luis Mora")).rows("from crm_leads", listOf(lead))
        val settings = CrmMeta.load(tenant.connection).copy(enabled = true, recordEchoes = true, phoneNumberId = "998")
        val (stored, rows) = logged { CrmMeta.handle(tenant.connection, settings, listOf(MetaEvent.WhatsAppEcho("998", "5076000", "wamid.9", "Hola desde el telefono", Instant.now())), {}) }
        assertEquals(1, stored)
        val row = rows.single { it.entry.entityType == "message" }
        assertEquals("WhatsApp Business", row.display)
        assertEquals("integration", row.actor.kind)
        assertEquals("Luis Mora", row.entry.entityLabel)
        assertFalse(row.entry.toString().contains("Hola desde el telefono"))
    }

    @Test
    fun theLogRowOfASettingFailsTheChange() {
        // A failing log row is never swallowed: the exception reaches the caller (and its transaction rolls back).
        val tenant = FakeTenant()
        val broken = com.ideasdeveloper.bms.server.activity.ActivityLog.capturing({ _, _ -> error("the log is down") }) {
            runCatching { CrmMeta.save(tenant.connection, CrmMeta.load(tenant.connection).copy(enabled = true), person) }
        }
        assertEquals("the log is down", broken.exceptionOrNull()?.message)
        assertEquals(1, tenant.rollbacks)
        assertEquals(0, tenant.commits)
    }
}
