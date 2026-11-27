package com.ideasdeveloper.bms.extensions.inmobiliaria_monday

import com.ideasdeveloper.bms.modules.crm.CrmTestDatabase
import com.ideasdeveloper.bms.modules.crm.FakeSecrets
import com.ideasdeveloper.bms.modules.crm.FakeTenant
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the import of Inmobiliaria from monday writes in the company activity log: the token (only "changed"), the import job and who
 * started, stopped or discarded it. The job runs on a real PostgreSQL (BMS_ACTIVITY_PG_URL, see [CrmTestDatabase]); the token runs on a fake.
 */
class InmobiliariaMondayActivityLogTest {
    private val person = ActivityLogTesting.user("Ana Ruiz")
    private val agent = ActivityLogTesting.permissions(actor = person).throughAgent("Claude").activityActor
    private var db: Connection? = null

    @AfterTest
    fun close() {
        db?.close()
        db = null
        FakeSecrets.remove()
    }

    @Test
    fun theTokenIsLoggedAsChangedAndNeverItsValue() {
        FakeSecrets.install()
        val tenant = FakeTenant().rows("select token from ext_inmobiliaria_monday__settings", listOf(FakeSecrets.sealed("tokenViejoInmo")))
        val (_, rows) = ActivityLogTesting.capture { InmobiliariaMondaySettingsStore.saveToken(tenant.connection, "tokenNuevoInmoSecreto", agent) }
        val row = rows.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertTrue(row.change("token")!!.secret)
        listOf("tokenViejoInmo", "tokenNuevoInmoSecreto").forEach { assertFalse(row.entry.toString().contains(it), it) }
        assertEquals("Agente de Ana Ruiz", row.display)
    }

    @Test
    fun anImportJobIsLoggedWhenItIsStoppedAndWhenItIsDiscardedBeforeItsRowsDisappear() {
        if (CrmTestDatabase.url == null) return
        db = CrmTestDatabase.open("bms_inmo_monday_al", person.userId!!, "Ana Ruiz", listOf("inmobiliaria_monday"))
        val tenant = db!!
        val job = InmobiliariaMondayStore.create(tenant, "1234567", "Listados", MondayMapping.KindProperty, false, emptyMap(), person.userId, "Ana Ruiz")
        // Applying -> stopped (ready again).
        InmobiliariaMondayStore.transition(tenant, job, MondayJobState.Reading, MondayJobState.Ready)
        assertTrue(InmobiliariaMondayStore.startApplying(tenant, job, person.userId, "Ana Ruiz"))
        InmobiliariaMondayService.cancel(tenant, job, agent)
        assertEquals("status_changed", CrmTestDatabase.scalar(tenant, "SELECT action FROM activity_log WHERE entity_type = 'import' ORDER BY id DESC LIMIT 1"))
        assertEquals("applying", CrmTestDatabase.scalar(tenant, "SELECT c ->> 'old' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_type = 'import' AND c ->> 'field' = 'state'"))
        // Discarded: the row is there after the job and its rows are gone.
        InmobiliariaMondayService.discard(tenant, job, person)
        assertEquals("deleted", CrmTestDatabase.scalar(tenant, "SELECT action FROM activity_log WHERE entity_type = 'import' ORDER BY id DESC LIMIT 1"))
        assertEquals("0", CrmTestDatabase.scalar(tenant, "SELECT count(*) FROM ext_inmobiliaria_monday__jobs"))
        assertEquals("Agente de Ana Ruiz", CrmTestDatabase.scalar(tenant, "SELECT actor_display FROM activity_log WHERE entity_type = 'import' AND action = 'status_changed'"))
        assertEquals("Ana Ruiz", CrmTestDatabase.scalar(tenant, "SELECT actor_display FROM activity_log WHERE entity_type = 'import' AND action = 'deleted'"))
    }
}
