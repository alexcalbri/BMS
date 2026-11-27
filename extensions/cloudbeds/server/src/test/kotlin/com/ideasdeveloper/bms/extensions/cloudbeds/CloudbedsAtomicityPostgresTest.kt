package com.ideasdeveloper.bms.extensions.cloudbeds

import com.ideasdeveloper.bms.modules.crm.CbReservationResult
import com.ideasdeveloper.bms.modules.crm.CbRoomRequest
import com.ideasdeveloper.bms.modules.crm.CrmActor
import com.ideasdeveloper.bms.modules.crm.CrmCloudbedsStore
import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.CrmInbound
import com.ideasdeveloper.bms.modules.crm.CrmInbox
import com.ideasdeveloper.bms.modules.crm.CrmIntakeResult
import com.ideasdeveloper.bms.modules.crm.CrmItems
import com.ideasdeveloper.bms.modules.crm.CrmReservations
import com.ideasdeveloper.bms.modules.crm.CrmRepository
import com.ideasdeveloper.bms.modules.crm.CrmTestDatabase
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A Cloudbeds change and its activity row are saved together: when the row fails (here a trigger refuses every
 * "reservation" row of the log) the status, the reservation, the rooms and the history roll back with it. Runs only
 * with a disposable PostgreSQL (see [CrmTestDatabase]).
 */
class CloudbedsAtomicityPostgresTest {
    private val person = ActivityLogTesting.user("Ana Ruiz")
    private val actor = CrmActor(person.userId, "Ana Ruiz", person)
    private var tenant: Connection? = null

    @AfterTest
    fun close() {
        tenant?.close()
        tenant = null
    }

    private fun fresh(): Connection? = CrmTestDatabase.open("bms_cb_atomic", person.userId!!, "Ana Ruiz", listOf("cloudbeds"))?.also { tenant = it }

    private fun scalar(sql: String) = CrmTestDatabase.scalar(tenant!!, sql)

    private fun refuseReservationRows(db: Connection) = db.createStatement().use {
        it.execute("CREATE FUNCTION refuse_reservation_row() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'row refused'; END ${'$'}${'$'} LANGUAGE plpgsql")
        it.execute("CREATE TRIGGER refuse_reservation BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.entity_type = 'reservation') EXECUTE FUNCTION refuse_reservation_row()")
    }

    private fun allowReservationRows(db: Connection) = db.createStatement().use { it.execute("DROP TRIGGER refuse_reservation ON activity_log") }

    private fun lead(db: Connection) = CrmRepository.lead(db, (CrmInbox.receive(db, CrmInbound(channel = "form", name = "Luis Mora", email = "luis@example.com", message = "Hola", messageId = "c1")) as CrmIntakeResult.Created).leadId)!!

    private fun reservation(db: Connection, lead: UUID, status: String = "confirmed") {
        CrmReservations.save(db, lead, CbReservationResult("R-1", status, BigDecimal("100"), 0), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), "Hab 1", "luis@example.com")
    }

    @Test
    fun aStatusSyncThatFailsToWriteItsRowKeepsTheOldStatus() {
        val db = fresh() ?: return
        val lead = lead(db)
        reservation(db, lead.id)
        val current = CrmReservations.forLead(db, lead.id).single()
        val cloudbeds = CrmActor(null, "Cloudbeds", com.ideasdeveloper.bms.server.activity.ActivityActor.integration("Cloudbeds"))
        refuseReservationRows(db)
        assertFailsWith<Exception> { CrmCloudbeds.applyStatus(CrmHost, db, current, "canceled", cloudbeds) }
        assertEquals("confirmed", scalar("SELECT status FROM crm_reservations"))
        assertEquals("0", scalar("SELECT count(*) FROM crm_activity WHERE body LIKE 'Reserva R-1%'"))
        // Without the failure both are saved.
        allowReservationRows(db)
        CrmCloudbeds.applyStatus(CrmHost, db, current, "canceled", cloudbeds)
        assertEquals("canceled", scalar("SELECT status FROM crm_reservations"))
        assertEquals("1", scalar("SELECT count(*) FROM activity_log WHERE entity_type = 'reservation'"))
    }

    @Test
    fun aCheckInThatFailsToWriteItsRowKeepsTheReservationWaiting() {
        val db = fresh() ?: return
        val lead = lead(db)
        reservation(db, lead.id)
        val current = CrmReservations.forLead(db, lead.id).single()
        refuseReservationRows(db)
        // The same steps checkInForLead runs after the call to Cloudbeds: the status and its rows in one transaction.
        assertFailsWith<Exception> {
            CrmCloudbeds.markCheckedIn(CrmHost, db, current) {
                CrmHost.addActivity(db, lead.id, "item", "Check-in hecho en Cloudbeds: reserva R-1.", actor, com.ideasdeveloper.bms.modules.crm.CrmLeadAudit.None)
                com.ideasdeveloper.bms.modules.crm.CrmAudit.record(db, person, "reservation", current.id.toString(), "Reserva R-1", com.ideasdeveloper.bms.server.activity.ActivityActions.StatusChanged, "Hizo el check-in.")
            }
        }
        assertEquals("confirmed", scalar("SELECT status FROM crm_reservations"))
        assertEquals("0", scalar("SELECT count(*) FROM crm_activity WHERE body LIKE 'Check-in%'"))
        allowReservationRows(db)
        CrmCloudbeds.markCheckedIn(CrmHost, db, current)
        assertEquals("checked_in", scalar("SELECT status FROM crm_reservations"))
    }

    @Test
    fun aReservationThatFailsToWriteItsRowLeavesNoReservationNoBookedRoomsAndNoHistory() {
        val db = fresh() ?: return
        val lead = lead(db)
        CrmHost.linkItem(db, lead.id, CrmCloudbedsStore.Source, "room-1", "Hab 1", status = "chosen", startsOn = LocalDate.of(2026, 12, 1), endsOn = LocalDate.of(2026, 12, 3))
        val rooms = CrmItems.list(db, lead.id, CrmCloudbedsStore.Source)
        val requests = rooms.map { CbRoomRequest(it.externalId, "type-1", 2, 0) }
        val result = CbReservationResult("R-9", "confirmed", BigDecimal("250"), 0)
        refuseReservationRows(db)
        assertFailsWith<Exception> {
            CrmCloudbedsExtension.saveReserved(CrmHost, db, lead, result, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), rooms, requests, actor)
        }
        assertEquals("0", scalar("SELECT count(*) FROM crm_reservations"))
        assertEquals("chosen", scalar("SELECT status FROM crm_lead_items WHERE external_id = 'room-1'"))
        assertEquals("0", scalar("SELECT count(*) FROM crm_activity WHERE body LIKE 'Reserva R-9%'"))
        allowReservationRows(db)
        CrmCloudbedsExtension.saveReserved(CrmHost, db, lead, result, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), rooms, requests, actor)
        assertEquals("1", scalar("SELECT count(*) FROM crm_reservations"))
        assertEquals("booked", scalar("SELECT status FROM crm_lead_items WHERE external_id = 'room-1'"))
        assertTrue(scalar("SELECT count(*) FROM activity_log WHERE entity_type = 'reservation'") == "1")
    }
}
