package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The same CRM operations as [CrmActivityLogTest] on a real PostgreSQL: every row really reaches `activity_log` in the
 * transaction of its change (and goes with it when the change fails), the delete leaves its row after the cascade erased the
 * history of the lead, and no secret value is stored. Runs only with BMS_ACTIVITY_PG_URL (or BMS_SERVER_ADMINS_PG_URL),
 * jdbc:postgresql://host:port/postgres?user=postgres of a DISPOSABLE server: it creates and drops the database bms_crm_al.
 */
class CrmActivityLogPostgresTest {
    private val url: String? = CrmTestDatabase.url
    private val database = "bms_crm_al"
    private var tenant: Connection? = null

    private val person = ActivityLogTesting.user("Ana Ruiz")
    private val agent = ActivityLogTesting.permissions(actor = person).throughAgent("Claude").activityActor

    @AfterTest
    fun close() {
        tenant?.close()
        tenant = null
    }

    private fun fresh(): Connection = CrmTestDatabase.open(database, person.userId!!, "Ana Ruiz")!!.also { tenant = it }

    private fun scalar(sql: String): String? = tenant!!.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }

    /** The rows of the log of one kind of record, oldest first, as "action:summary". */
    private fun log(entityType: String): List<String> = tenant!!.createStatement().use { st ->
        st.executeQuery("SELECT action || ':' || summary FROM activity_log WHERE module_id = 'crm' AND entity_type = '$entityType' ORDER BY id").use { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }
    }

    private fun crmActor(activity: ActivityActor = person) = CrmActor(person.userId, "Ana Ruiz", activity)

    private fun input(stage: CrmStage, name: String, email: String, phone: String = "") = CrmLeadInput(
        name = name, email = email, phone = phone, stageId = stage.id, interestId = null, ownerUserIds = emptyList(), source = "Web", language = "es",
        message = "Quiero reservar", details = "", targetStart = null, targetEnd = null, estimatedValue = BigDecimal("1200"), followUpOn = null,
    )

    @Test
    fun theRowsReachTheRealLogInTheTransactionOfTheChange() {
        if (url == null) return
        val db = fresh()
        val stages = CrmRepository.stages(db)
        val open = stages.first { it.kind == StageKind.Open }
        val won = stages.first { it.kind == StageKind.Won }
        val names = CrmNames(emptyList(), emptyList())

        // Create: the lead and the customer it created in Clientes.
        val id = CrmRepository.createLead(db, input(open, "Luis Mora", "luis@example.com", "+5076000"), open, crmActor(agent))
        assertEquals(1, log("customer").size)
        assertTrue(log("lead").single().startsWith("created:"), log("lead").toString())
        assertEquals("Agente de Ana Ruiz", scalar("SELECT actor_display FROM activity_log WHERE entity_type = 'lead'"))
        assertEquals("Claude", scalar("SELECT agent_name FROM activity_log WHERE entity_type = 'lead'"))
        assertEquals("agent", scalar("SELECT origin FROM activity_log WHERE entity_type = 'lead'"))
        assertEquals("luis@example.com", scalar("SELECT c ->> 'new' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_type = 'lead' AND c ->> 'field' = 'email'"))

        // Edit: old -> new by field, the customer's contact data in its own row.
        val lead = CrmRepository.lead(db, id)!!
        CrmRepository.updateLead(db, lead, input(open, "Luis Mora Diaz", "luis@example.com", "+5077000").copy(estimatedValue = BigDecimal("1500")), stages, names, crmActor())
        assertEquals("Luis Mora", scalar("SELECT c ->> 'old' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_type = 'customer' AND action = 'updated' AND c ->> 'field' = 'name'"))
        assertEquals("1500", scalar("SELECT c ->> 'new' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_type = 'lead' AND action = 'updated' AND c ->> 'field' = 'estimatedValue'"))

        // Stage, archive, restore and a note.
        CrmRepository.moveLead(db, CrmRepository.lead(db, id)!!, won, stages, crmActor(agent))
        assertEquals("Ganado", scalar("SELECT c ->> 'new' FROM activity_log, jsonb_array_elements(changes) c WHERE action = 'status_changed' AND c ->> 'field' = 'stage'"))
        assertTrue(CrmRepository.archiveLead(db, id, crmActor()))
        assertTrue(CrmRepository.restoreLead(db, id, crmActor()))
        CrmRepository.addNote(db, id, "Pidio descuento", crmActor())
        val actions = scalar("SELECT string_agg(action, ',' ORDER BY id) FROM activity_log WHERE entity_type = 'lead'")
        assertEquals("created,updated,status_changed,archived,restored,commented", actions)

        // Delete: the row is written before the cascade erases the history of the lead, and stays.
        assertTrue(CrmRepository.deleteLead(db, CrmRepository.lead(db, id)!!, crmActor(agent), "Ganado"))
        assertEquals("0", scalar("SELECT count(*) FROM crm_activity WHERE lead_id = '$id'"))
        assertEquals("0", scalar("SELECT count(*) FROM crm_leads WHERE id = '$id'"))
        assertEquals("deleted", scalar("SELECT action FROM activity_log WHERE entity_type = 'lead' ORDER BY id DESC LIMIT 1"))
        assertEquals("Luis Mora Diaz", scalar("SELECT entity_label FROM activity_log WHERE entity_type = 'lead' AND action = 'deleted'"))
        assertEquals(id.toString(), scalar("SELECT entity_id FROM activity_log WHERE entity_type = 'lead' AND action = 'deleted'"))
        // The customer stays in Clientes.
        assertEquals("1", scalar("SELECT count(*) FROM customers WHERE display_name = 'Luis Mora Diaz'"))
    }

    @Test
    fun aFailedChangeLeavesNeitherTheChangeNorItsRow() {
        if (url == null) return
        val db = fresh()
        val before = scalar("SELECT count(*) FROM crm_stages")!!.toInt()
        assertFailsWith<IllegalStateException> {
            CrmAudit.atomically(db) {
                CrmRepository.saveStage(db, null, CrmStageInput("Visita", StageKind.Open, StageTone.Warning, 9), person)
                error("fails after the stage and its row were written")
            }
        }
        assertEquals(before.toString(), scalar("SELECT count(*) FROM crm_stages"))
        assertEquals("0", scalar("SELECT count(*) FROM activity_log WHERE entity_type = 'stage'"))
        // And the same change without failing keeps both.
        CrmRepository.saveStage(db, null, CrmStageInput("Visita", StageKind.Open, StageTone.Warning, 9), person)
        assertEquals((before + 1).toString(), scalar("SELECT count(*) FROM crm_stages"))
        assertEquals(listOf("created:Creo la etapa Visita."), log("stage"))
    }

    @Test
    fun settingsChangesStoreNoSecretAndTheTemplateIsDeletedAfterItsRow() {
        if (url == null) return
        val db = fresh()
        // A mail password: only that it changed.
        CrmSecrets.box = object : com.ideasdeveloper.bms.server.security.SecretBox {
            override val available = true
            override fun seal(tenant: Connection, plain: String) = com.ideasdeveloper.bms.server.security.SecretBox.SealedPrefix + plain.reversed()
            override fun open(tenant: Connection, value: String) = value.removePrefix(com.ideasdeveloper.bms.server.security.SecretBox.SealedPrefix).reversed()
        }
        try {
            val current = CrmMail.load(db)
            CrmMail.save(db, current.copy(imapEnabled = false, imapPassword = "claveSuperSecretaIMAP", imapHost = "mail.example.com"), agent)
            val row = scalar("SELECT changes::text || summary || entity_label FROM activity_log WHERE entity_type = 'settings'")!!
            assertFalse(row.contains("claveSuperSecretaIMAP"), row)
            assertEquals("true", scalar("SELECT (c ->> 'secret')::text FROM activity_log, jsonb_array_elements(changes) c WHERE c ->> 'field' = 'imapPassword'"))
            assertEquals("settings_changed", scalar("SELECT action FROM activity_log WHERE entity_type = 'settings'"))
            assertEquals("Agente de Ana Ruiz", scalar("SELECT actor_display FROM activity_log WHERE entity_type = 'settings'"))
        } finally {
            CrmSecrets.box = null
        }
        // Template: created, edited with old -> new, deleted (its row before it disappears).
        CrmMail.saveTemplate(db, null, mapOf("name" to "Bienvenida", "subject" to "Hola", "body" to "Texto uno"), person)
        val template = CrmMail.templates(db).single()
        CrmMail.saveTemplate(db, template.id, mapOf("name" to "Bienvenida", "subject" to "Hola de nuevo", "body" to "Texto uno"), person)
        assertEquals("Hola", scalar("SELECT c ->> 'old' FROM activity_log, jsonb_array_elements(changes) c WHERE entity_type = 'template' AND action = 'updated' AND c ->> 'field' = 'subject'"))
        assertTrue(CrmMail.deleteTemplate(db, template.id, person))
        assertEquals("created,updated,deleted", scalar("SELECT string_agg(action, ',' ORDER BY id) FROM activity_log WHERE entity_type = 'template'"))
    }

    @Test
    fun anImportWritesItsRowsAsTheImportOfThePersonAndAnAutomationAsItself() {
        if (url == null) return
        val db = fresh()
        val stages = CrmRepository.stages(db)
        val open = stages.first { it.kind == StageKind.Open }
        val job = CrmImport.create(db, "file", "Clientes", CrmTable(listOf("Nombre", "Correo"), listOf(mapOf("Nombre" to "Marta Gil", "Correo" to "marta@example.com"))), person.userId, agent)
        assertEquals("created", scalar("SELECT action FROM activity_log WHERE entity_type = 'import' AND entity_id = '$job'"))
        val context = CrmImport.Context(stages, emptyList(), emptyList(), LocalDate.of(2026, 11, 1), "file", activity = ActivityActor.import(person))
        val values = CrmImport.Values(
            key = "file:1", name = "Marta Gil", email = "marta@example.com", phone = "", stage = open, interestId = null, ownerIds = emptyList(), ownersUnmatched = emptyList(),
            fields = mapOf("source" to "", "language" to "", "message" to "", "details" to "", "followUpOn" to "", "targetStart" to "", "targetEnd" to "", "estimatedValue" to ""),
            notes = listOf(CrmImportNote(null, "Ana", "Nota historica")), createdAt = null,
        )
        CrmImport.apply(db, values, context, "archivo", null)
        assertEquals("Importacion (Ana Ruiz)", scalar("SELECT DISTINCT actor_display FROM activity_log WHERE entity_type = 'lead'"))
        assertEquals("import", scalar("SELECT DISTINCT actor_kind FROM activity_log WHERE entity_type = 'lead'"))
        assertEquals("created,commented", scalar("SELECT string_agg(action, ',' ORDER BY id) FROM activity_log WHERE entity_type = 'lead'"))
        assertEquals(person.userId.toString(), scalar("SELECT DISTINCT actor_user_id::text FROM activity_log WHERE entity_type = 'lead'"))

        // A manual automation pressed by a person: "Automatizacion X (Agente de Ana Ruiz)".
        val lead = CrmRepository.lead(db, CrmImport.leadByKey(db, "file:1")!!)!!
        val automation = CrmAutomation(UUID.randomUUID(), "Seguimiento", true, CrmTrigger(CrmTriggerType.Manual), actions = listOf(CrmAction(CrmActionKind.SetField, field = CrmAutomationRefs.FollowUpField, value = "2026-12-01")))
        val outcome = CrmAutomationEngine.runClaimed(db, automation, lead, "k1", crmActor(agent), null, CrmJavaHttp, refs = CrmAutomationRefs(stages = stages, fields = emptyList()))
        assertEquals(CrmRunOutcome.Done, outcome.status, outcome.detail)
        assertEquals("Automatizacion Seguimiento (Agente de Ana Ruiz)", scalar("SELECT actor_display FROM activity_log WHERE actor_kind = 'automation' AND entity_type = 'lead'"))
        assertEquals("automation", scalar("SELECT origin FROM activity_log WHERE actor_kind = 'automation' AND entity_type = 'lead'"))
    }

    @Test
    fun aRunningImportWritesOneRowPerLeadAndOneForTheWholeImportWithItsTotals() {
        if (url == null) return
        val db = fresh()
        val table = CrmTable(
            listOf("Nombre", "Correo"),
            listOf(mapOf("Nombre" to "Marta Gil", "Correo" to "marta@example.com"), mapOf("Nombre" to "Pedro Rey", "Correo" to "pedro@example.com"), mapOf("Nombre" to "", "Correo" to "")),
        )
        val id = CrmImport.create(db, "file", "Clientes 2026", table, person.userId, agent)
        val job = CrmImport.job(db, id)!!
        CrmImport.saveMapping(db, job, CrmImportMapping(columns = mapOf("Nombre" to "name", "Correo" to "email")))
        CrmImport.setStatus(db, CrmImport.job(db, id)!!, "running", agent)
        // The runner of the job (as the minute job does) attributes the rows to the import and to the person who created it.
        assertEquals(3, CrmImport.runPending(db, 300, LocalDate.of(2026, 11, 1)))
        assertEquals("2", scalar("SELECT count(*) FROM activity_log WHERE entity_type = 'lead' AND action = 'created'"))
        assertEquals("Importacion (Ana Ruiz)", scalar("SELECT DISTINCT actor_display FROM activity_log WHERE entity_type = 'lead'"))
        assertEquals(person.userId.toString(), scalar("SELECT DISTINCT actor_user_id::text FROM activity_log WHERE entity_type = 'lead'"))
        assertEquals("imported", scalar("SELECT action FROM activity_log WHERE entity_type = 'import' ORDER BY id DESC LIMIT 1"))
        assertEquals("2", scalar("SELECT c ->> 'new' FROM activity_log, jsonb_array_elements(changes) c WHERE action = 'imported' AND c ->> 'field' = 'created'"))
        assertEquals("1", scalar("SELECT c ->> 'new' FROM activity_log, jsonb_array_elements(changes) c WHERE action = 'imported' AND c ->> 'field' = 'skipped'"))
        assertEquals("Importacion (Ana Ruiz)", scalar("SELECT actor_display FROM activity_log WHERE action = 'imported'"))
        assertEquals("import", scalar("SELECT origin FROM activity_log WHERE action = 'imported'"))
        // Created, started (with the whole identity of who pressed start, here the agent) and finished.
        assertEquals("created,status_changed,imported", scalar("SELECT string_agg(action, ',' ORDER BY id) FROM activity_log WHERE entity_type = 'import'"))
        assertEquals("Agente de Ana Ruiz", scalar("SELECT actor_display FROM activity_log WHERE entity_type = 'import' AND action = 'status_changed'"))
    }

    @Test
    fun aBulkActionWritesOneRowPerLeadAndOneForTheOperationOnTheRealLog() {
        if (url == null) return
        val db = fresh()
        val stages = CrmRepository.stages(db)
        val open = stages.first { it.kind == StageKind.Open }
        val ids = listOf("a", "b").map { CrmRepository.createLead(db, input(open, "Lead $it", "$it@example.com"), open, crmActor()) }
        val scope = CrmScope(person.userId, seesAll = true, reception = true, interestsEnabled = false, interests = emptySet())
        val permissions = ActivityLogTesting.permissions(granted = setOf(CrmScreens.View, CrmScreens.Manage), actor = person)
        val context = CrmScreens.Context(db, permissions, scope, stages, emptyList(), emptyList())
        CrmBulkActions.apply(context, ids.map { CrmRepository.lead(db, it)!! }, CrmImportScreens.ActionArchive, "", crmActor(agent))
        assertEquals("2", scalar("SELECT count(*) FROM activity_log WHERE entity_type = 'lead' AND action = 'archived'"))
        assertEquals("1", scalar("SELECT count(*) FROM activity_log WHERE entity_type = 'bulk_action' AND action = '${ActivityActions.Archived}'"))
    }
}
