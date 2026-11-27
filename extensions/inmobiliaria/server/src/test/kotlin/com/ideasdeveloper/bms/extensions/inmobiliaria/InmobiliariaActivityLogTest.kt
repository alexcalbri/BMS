package com.ideasdeveloper.bms.extensions.inmobiliaria

import com.ideasdeveloper.bms.modules.crm.CrmActor
import com.ideasdeveloper.bms.modules.crm.CrmScope
import com.ideasdeveloper.bms.modules.crm.CrmScreens
import com.ideasdeveloper.bms.modules.crm.CrmTestDatabase
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import java.math.BigDecimal
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What Inmobiliaria writes in the company activity log: properties, their price, status and links, deals with their parties and
 * stage moves, the stages and the settings, with the actor of the session (the person, "Agente de <usuario>" through MCP, or the
 * import). The service runs on a real PostgreSQL (BMS_ACTIVITY_PG_URL, see [CrmTestDatabase]); the write tools are listed without it.
 */
class InmobiliariaActivityLogTest {
    private val person = ActivityLogTesting.user("Ana Ruiz")
    private val permissions = ActivityLogTesting.permissions(
        granted = setOf(CrmScreens.View, CrmScreens.Manage, CrmScreens.SeeAll, CrmScreens.Settings), actor = person,
    )
    private val agent = permissions.activityActor.viaAgent("Claude")
    private var db: Connection? = null

    @AfterTest
    fun close() {
        db?.close()
        db = null
    }

    private fun session(activity: ActivityActor): InmobiliariaSession {
        val scope = CrmScope(person.userId, seesAll = true, reception = true, interestsEnabled = false, interests = emptySet())
        return InmobiliariaSession.of(db!!, permissions, scope, CrmActor(person.userId, "Ana Ruiz", activity))
    }

    private fun scalar(sql: String) = CrmTestDatabase.scalar(db!!, sql)

    @Test
    fun everyWriteToolOfTheExtensionIsListedSoNoneIsLeftWithoutItsRow() {
        // The tools call InmobiliariaService with the session of the agent (CrmActor with call.activityActor): their rows are the ones tested below.
        val writes = InmobiliariaMcpTools.tools.filterNot { it.readOnly }.map { it.name }.sorted()
        assertEquals(listOf("inmobiliaria_advance_deal", "inmobiliaria_create_deal", "inmobiliaria_update_property"), writes)
    }

    @Test
    fun propertiesDealsStagesAndSettingsAreLoggedWithTheirOldAndNewValues() {
        if (CrmTestDatabase.url == null) return
        db = CrmTestDatabase.open("bms_inmo_al", person.userId!!, "Ana Ruiz", listOf("inmobiliaria"))!!.also {
            it.createStatement().use { st ->
                st.execute("INSERT INTO application_permissions (permission_id, module_id, title) VALUES ('crm.view', 'crm', 'Usar el CRM')")
                st.execute("INSERT INTO application_user_permissions (user_id, permission_id) VALUES ('${person.userId}', 'crm.view')")
            }
        }
        val s = session(agent)

        // A property: created, edited, repriced, published.
        val property = InmobiliariaService.createProperty(s, PropertyFields(code = null, title = "Casa Las Colinas", kind = PropertyKind.House, zone = "Boquete"), BigDecimal("250000"))
        assertEquals("created", scalar("SELECT action FROM activity_log WHERE entity_type = 'property'"))
        assertEquals("Agente de Ana Ruiz", scalar("SELECT actor_display FROM activity_log WHERE entity_type = 'property'"))
        assertEquals("${property.code} - Casa Las Colinas", scalar("SELECT entity_label FROM activity_log WHERE entity_type = 'property'"))
        InmobiliariaService.updateProperty(s, property.id, PropertyFields(code = property.code, title = "Casa Las Colinas II", kind = PropertyKind.House, zone = "Boquete", bedrooms = 3))
        assertEquals("Casa Las Colinas", scalar("SELECT c ->> 'old' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_type = 'property' AND action = 'updated' AND c ->> 'field' = 'title'"))
        assertEquals("3", scalar("SELECT c ->> 'new' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_type = 'property' AND action = 'updated' AND c ->> 'field' = 'bedrooms'"))
        InmobiliariaService.changePrice(s, property.id, BigDecimal("230000"), "Rebaja")
        assertEquals("250000", scalar("SELECT c ->> 'old' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_type = 'property' AND action = 'updated' AND c ->> 'field' = 'price'"))
        InmobiliariaService.publish(s, property.id)
        assertEquals("status_changed", scalar("SELECT action FROM activity_log WHERE entity_type = 'property' ORDER BY id DESC LIMIT 1"))
        InmobiliariaService.replaceLinks(s, property.id, listOf(LinkInput("Sitio web", "https://sitio.example/casa")))
        assertTrue(scalar("SELECT c ->> 'new' FROM activity_log, jsonb_array_elements(changes) c WHERE c ->> 'field' = 'links'")!!.contains("https://sitio.example/casa"))

        // A deal: the first one seeds the stages of the template (a configuration change), then it is opened and won.
        val deal = InmobiliariaService.createDeal(s, DealDraft(propertyId = property.id, commissionPct = BigDecimal("5"))).deal
        assertEquals("1", scalar("SELECT count(*) FROM activity_log WHERE entity_type = 'settings' AND entity_label = 'Etapas de las transacciones'"))
        assertEquals("created", scalar("SELECT action FROM activity_log WHERE entity_type = 'deal' ORDER BY id LIMIT 1"))
        val won = InmobiliariaStore.stages(db!!).first { it.outcome == DealOutcome.Won }
        InmobiliariaService.moveDeal(s, deal.id, won.id)
        assertEquals("status_changed", scalar("SELECT action FROM activity_log WHERE entity_type = 'deal' ORDER BY id DESC LIMIT 1"))
        assertEquals(won.name, scalar("SELECT c ->> 'new' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_type = 'deal' AND action = 'status_changed' AND c ->> 'field' = 'stage'"))
        // The property became Sold as an effect of the won deal: it has its own row.
        assertEquals("Vendida", scalar("SELECT c ->> 'new' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_type = 'property' AND action = 'status_changed' AND c ->> 'field' = 'status' ORDER BY id DESC LIMIT 1"))

        // Settings and stages.
        InmobiliariaService.saveSettings(s, Settings("PAB", BigDecimal("6")))
        assertEquals("USD", scalar("SELECT c ->> 'old' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_label = 'Inmobiliaria' AND c ->> 'field' = 'currency'"))
        InmobiliariaService.saveStages(s, InmobiliariaStore.stages(db!!).map { StageInput(it.id, it.name, it.outcome) } + StageInput(null, "Escritura", DealOutcome.Open))
        assertEquals("settings_changed", scalar("SELECT action FROM activity_log WHERE entity_label = 'Etapas de las transacciones' ORDER BY id DESC LIMIT 1"))

        // The same operations by the person (not through an agent) show the person; every row is module crm.
        InmobiliariaService.changePrice(session(person), property.id, BigDecimal("220000"))
        assertEquals("Ana Ruiz", scalar("SELECT actor_display FROM activity_log WHERE entity_type = 'property' ORDER BY id DESC LIMIT 1"))
        assertEquals("0", scalar("SELECT count(*) FROM activity_log WHERE module_id <> 'crm'"))
    }
}
