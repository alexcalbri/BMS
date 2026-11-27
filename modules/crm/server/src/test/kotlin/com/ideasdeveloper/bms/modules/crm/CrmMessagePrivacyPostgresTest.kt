package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import java.math.BigDecimal
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What customers write never reaches the company activity log (ACTIVITY_LOG.md §1): the text of the messages of every
 * inbound channel, of the lead's free `message`/`details` and of the history entries built from a body is replaced by
 * its length. Staff notes keep their first 500 characters. Runs only with a disposable PostgreSQL (see [CrmTestDatabase]).
 */
class CrmMessagePrivacyPostgresTest {
    private val person = ActivityLogTesting.user("Ana Ruiz")
    private var tenant: Connection? = null

    @AfterTest
    fun close() {
        tenant?.close()
        tenant = null
    }

    private fun fresh(): Connection? = CrmTestDatabase.open("bms_crm_privacy", person.userId!!, "Ana Ruiz")?.also { tenant = it }

    /** Everything the log stored, as one text, to look for a secret in it. */
    private fun everything(db: Connection): String =
        CrmTestDatabase.scalar(db, "SELECT coalesce(string_agg(summary || ' ' || entity_label || ' ' || changes::text, ' '), '') FROM activity_log")!!

    @Test
    fun noInboundChannelStoresTheTextOfTheMessageOrTheDetails() {
        val db = fresh() ?: return
        val channels = listOf("form", "whatsapp", "messenger", "instagram", "encuentra24", "wordpress", "email")
        channels.forEachIndexed { index, channel ->
            val secret = "SECRETO-$channel-clave-de-la-caja-fuerte"
            val result = CrmInbox.receive(
                db,
                CrmInbound(
                    channel = channel, name = "Cliente $index", email = "c$index@example.com", message = "Hola, $secret", subject = "Asunto $secret",
                    messageId = "m-$channel", source = "Origen $index", details = "Detalle $secret, pide late checkout",
                ),
            )
            assertTrue(result is CrmIntakeResult.Created, "$channel: $result")
            // A second message of the same person goes to the open lead.
            CrmInbox.receive(db, CrmInbound(channel = channel, name = "Cliente $index", email = "c$index@example.com", message = "Otra vez $secret", messageId = "m2-$channel"))
            val log = everything(db)
            assertFalse(log.contains(secret), "$channel stored the text of a customer: $log")
            assertFalse(log.contains("late checkout"), channel)
        }
        // The log still says that the lead was created with a message of a known length, and that messages arrived.
        assertTrue(everything(db).contains("Mensaje ("), everything(db))
        assertEquals("${channels.size * 2}", CrmTestDatabase.scalar(db, "SELECT count(*) FROM activity_log WHERE entity_type = 'message'"))
        // The conversation itself stays in crm_messages.
        assertEquals("2", CrmTestDatabase.scalar(db, "SELECT count(*) FROM crm_messages WHERE channel = 'whatsapp'"))
    }

    @Test
    fun anInboundSubjectIsNotStoredButAnOutboundOneIs() {
        val db = fresh() ?: return
        val id = (CrmInbox.receive(db, CrmInbound(channel = "email", name = "Luis", email = "luis@example.com", message = "x", messageId = "e1")) as CrmIntakeResult.Created).leadId
        CrmInbox.addMessage(db, id, "in", "email", "Asunto privado del cliente", "cuerpo", "e2", "received", "", null, "Luis", CrmInbox.inboundActor("email"))
        CrmInbox.addMessage(db, id, "out", "email", "Re: su consulta", "respuesta secreta", "e3", "sent", "", person.userId, "Ana Ruiz", person)
        val log = everything(db)
        assertFalse(log.contains("Asunto privado del cliente"), log)
        assertFalse(log.contains("respuesta secreta"), log)
        assertTrue(log.contains("Re: su consulta"), log)
    }

    @Test
    fun editingTheFreeTextsLogsOnlyTheirLength() {
        val db = fresh() ?: return
        val stages = CrmRepository.stages(db)
        val open = stages.first { it.kind == StageKind.Open }
        fun input(message: String, details: String) = CrmLeadInput(
            name = "Luis Mora", email = "luis@example.com", phone = "", stageId = open.id, interestId = null, ownerUserIds = emptyList(), source = "Web", language = "es",
            message = message, details = details, targetStart = null, targetEnd = null, estimatedValue = BigDecimal.ZERO, followUpOn = null,
        )
        val actor = CrmActor(person.userId, "Ana Ruiz", person)
        val id = CrmRepository.createLead(db, input("Quiero pagar con la tarjeta 4111-SECRETA", "Alergico a SECRETO-1"), open, actor)
        assertFalse(everything(db).contains("SECRETA"))
        assertFalse(everything(db).contains("SECRETO"))
        assertEquals("Mensaje (40 caracteres)", CrmTestDatabase.scalar(db, "SELECT c ->> 'new' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_type = 'lead' AND action = 'created' AND c ->> 'field' = 'message'"))

        val lead = CrmRepository.lead(db, id)!!
        CrmRepository.updateLead(db, lead, input("Quiero pagar con la tarjeta 4111-SECRETA y otra mas, SECRETA2", "Alergico a SECRETO-1"), stages, CrmNames(emptyList(), emptyList()), actor)
        assertFalse(everything(db).contains("SECRETA"))
        assertEquals("modificado (antes 40, ahora 61 caracteres)", CrmTestDatabase.scalar(db, "SELECT c ->> 'new' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_type = 'lead' AND action = 'updated' AND c ->> 'field' = 'message'"))
        // Details did not change: no change for them.
        assertEquals("0", CrmTestDatabase.scalar(db, "SELECT count(*) FROM activity_log, jsonb_array_elements(changes) c WHERE action = 'updated' AND c ->> 'field' = 'details'"))
    }

    @Test
    fun theRowOfAHistoryEntryNeverCopiesItsBodyButStaffNotesKeepTheirFirst500Characters() {
        val db = fresh() ?: return
        val open = CrmRepository.stages(db).first { it.kind == StageKind.Open }
        val actor = CrmActor(person.userId, "Ana Ruiz", person)
        val id = CrmRepository.createLead(
            db,
            CrmLeadInput("Luis Mora", "luis@example.com", "", open.id, null, emptyList(), "Web", "es", "", "", null, null, BigDecimal.ZERO, null),
            open, actor,
        )
        // Entries a channel or the website writes: the customer's text is in the body.
        CrmRepository.addActivity(db, id, "web", "Guardo una busqueda con alertas:\nSECRETO-busqueda", CrmActor(null, "Sitio web", ActivityActor.integration("Sitio web")))
        CrmRepository.addActivity(db, id, "item", "Plantilla: SECRETO-nota", actor)
        CrmRepository.addActivity(db, id, "updated", "SECRETO-linea\nSegunda", actor)
        assertFalse(everything(db).contains("SECRETO"), everything(db))
        // A staff note: kept, cut at 500 characters.
        CrmRepository.addNote(db, id, "Nota del equipo " + "x".repeat(600), actor)
        val kept = CrmTestDatabase.scalar(db, "SELECT c ->> 'new' FROM activity_log, jsonb_array_elements(changes) c WHERE action = 'commented' AND c ->> 'field' = 'note'")!!
        assertTrue(kept.startsWith("Nota del equipo "), kept)
        assertEquals(500, kept.length)
    }

    @Test
    fun fromHistoryBuildsTheSummaryFromTheKindNeverTheBody() {
        for (kind in listOf("created", "item", "web", "updated", "files", "whatever")) {
            val (_, summary, changes) = CrmAudit.fromHistory(kind, "SECRETO-cliente\notra linea", "Luis")
            assertFalse(summary.contains("SECRETO"), "$kind: $summary")
            assertTrue(changes.none { it.new?.contains("SECRETO") == true || it.old?.contains("SECRETO") == true }, kind)
        }
        assertEquals("Agrego una nota al lead Luis.", CrmAudit.fromHistory("note", "hola", "Luis").second)
    }

    @Test
    fun textChangeDescribesNewEditedAndClearedTexts() {
        assertEquals("Mensaje (5 caracteres)", CrmAudit.textChange("message", "Mensaje", null, "Hola.")!!.new)
        assertEquals("modificado (antes 4, ahora 8 caracteres)", CrmAudit.textChange("message", "Mensaje", "Hola", "Hola..!!")!!.new)
        assertEquals("Mensaje (4 caracteres)", CrmAudit.textChange("message", "Mensaje", "Hola", "")!!.old)
        assertEquals(null, CrmAudit.textChange("message", "Mensaje", "Hola", "Hola"))
        assertEquals(null, CrmAudit.textChange("message", "Mensaje", "", null))
    }
}
