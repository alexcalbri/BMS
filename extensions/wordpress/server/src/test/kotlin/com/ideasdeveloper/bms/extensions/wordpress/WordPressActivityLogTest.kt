package com.ideasdeveloper.bms.extensions.wordpress

import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.CrmUser
import com.ideasdeveloper.bms.modules.crm.FakeSecrets
import com.ideasdeveloper.bms.modules.crm.FakeTenant
import com.ideasdeveloper.bms.modules.crm.WpAgent
import com.ideasdeveloper.bms.modules.crm.WpListing
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the WordPress extension writes in the company activity log: its settings, its secret (never the value), its agents and what the site brings in. */
class WordPressActivityLogTest {
    private val person = ActivityLogTesting.user("Ana Ruiz")
    private val agent = ActivityLogTesting.permissions(actor = person).throughAgent("Claude").activityActor

    @BeforeTest
    fun secrets() = FakeSecrets.install()

    @AfterTest
    fun noSecrets() = FakeSecrets.remove()

    private fun <T> logged(block: () -> T): Pair<T, List<ActivityLogTesting.Captured>> = ActivityLogTesting.capture(block)

    private fun enabled() = FakeTenant().rows("from tenant_extensions", listOf("enabled"))

    private val listing = WpListing(77, "Casa Las Colinas", "https://sitio.example/casa", "250000", "$250,000", listOf("Casa"), listOf("En venta"), "3", "2", "Boquete", "180", "", 5, "Carla", "carla@example.com")

    @Test
    fun theSettingsFormLogsOldAndNew() {
        val tenant = enabled().rows("from ext_wordpress__settings", listOf(true, "https://viejo.example", FakeSecrets.sealed("secretoDelPlugin"), "Viejo", null, ""))
        val (_, rows) = logged { WordPressSettingsStore.saveForm(CrmHost, tenant.connection, mapOf("enabled" to "false", "siteUrl" to ""), agent) }
        val row = rows.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertEquals("WordPress", row.entry.entityLabel)
        assertEquals("Si", row.change("enabled")!!.old)
        assertEquals("No", row.change("enabled")!!.new)
        assertEquals("https://viejo.example", row.change("siteUrl")!!.old)
        assertNull(row.change("siteUrl")!!.new)
        assertEquals("Agente de Ana Ruiz", row.display)
        assertFalse(row.entry.toString().contains("secretoDelPlugin"))
    }

    @Test
    fun aNewSecretIsLoggedWithoutItsValue() {
        val tenant = FakeTenant()
        val (secret, rows) = logged { WordPressSettingsStore.newSecret(CrmHost, tenant.connection, person) }
        assertEquals(48, secret.length)
        val row = rows.single()
        assertTrue(row.change("secret")!!.secret)
        assertFalse(row.entry.toString().contains(secret))
    }

    @Test
    fun agentsOfTheSiteAreSyncedAndLinkedToUsersWithTheirNames() {
        val user = UUID.randomUUID()
        val tenant = FakeTenant().rows("from ext_wordpress__agents", listOf(5L, "Carla", "carla@example.com", null)).rows("select postgres_role from application_users", listOf("carla"))
        val (_, synced) = logged {
            WordPressSettingsStore.syncAgents(tenant.connection, listOf(WpAgent(5, "Carla", "carla@example.com", null)), listOf(CrmUser(user, "carla", "carla@example.com")), mapOf(user to "carla@example.com"), agent)
        }
        assertEquals("Carla", synced.single().change("agents")!!.new)
        assertEquals("1", synced.single().change("linked")!!.new)
        val (_, mapped) = logged { WordPressSettingsStore.mapAgent(tenant.connection, 5, user, person) }
        assertNull(mapped.single().change("user")!!.old)
        assertEquals("carla", mapped.single().change("user")!!.new)
    }

    @Test
    fun anAgentSeenForTheFirstTimeInAnEventIsLoggedAsTheSitesDoing() {
        val tenant = FakeTenant().rows("insert into ext_wordpress__agents", listOf(null, true))
        val (_, rows) = logged { WordPressSettingsStore.userForAgent(tenant.connection, 9, "Pedro", "pedro@example.com") }
        assertEquals("Sitio web", rows.single().display)
        assertEquals(ActivityActions.Created, rows.single().action)
        // An agent that already existed is only updated: nothing is logged.
        val known = FakeTenant().rows("insert into ext_wordpress__agents", listOf(null, false))
        assertTrue(logged { WordPressSettingsStore.userForAgent(known.connection, 9, "Pedro", "pedro@example.com") }.second.isEmpty())
    }

    @Test
    fun aListingLinkedByAPersonOrByTheSiteIsLoggedWithItsActor() {
        val lead = UUID.randomUUID()
        val tenant = FakeTenant().rows("select c.display_name from crm_leads l", listOf("Luis Mora")).rows("insert into crm_lead_items", listOf(true))
        val byAgent = com.ideasdeveloper.bms.modules.crm.CrmActor(person.userId, "Ana Ruiz (agente Claude)", agent)
        val (_, linked) = logged { CrmWordPress.linkListing(tenant.connection, lead, listing, "Propiedad vinculada por Ana: Casa Las Colinas.", byAgent) }
        assertEquals("Agente de Ana Ruiz", linked.single().display)
        assertEquals("Luis Mora", linked.single().entry.entityLabel)
        val (_, bySite) = logged { CrmWordPress.linkListing(tenant.connection, lead, listing, null) }
        assertEquals("Sitio web", bySite.single().display)
        assertEquals("integration", bySite.single().actor.kind)
    }

    @Test
    fun theStoredSecretOfTheSiteIsNeverReadBackIntoTheLog() {
        val tenant = enabled().rows("from ext_wordpress__settings", listOf(false, null, FakeSecrets.sealed("secretoGuardado"), "", null, ""))
        val (_, rows) = logged { WordPressSettingsStore.saveForm(CrmHost, tenant.connection, mapOf("enabled" to "false", "siteUrl" to ""), person) }
        assertTrue(rows.isEmpty(), "nothing changed: no row")
        assertEquals(ActivityActor.integration("Sitio web").display, "Sitio web")
    }
}
