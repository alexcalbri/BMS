package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.security.SecretBox
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CRM writes every change to the company activity log (ACTIVITY_LOG.md §4, §5), next to the history of the lead, in the
 * same connection, with the Core actor: the person, "Agente de <usuario>" through MCP, the automation or the import.
 * Runs on a fake company database ([FakeTenant]); the same operations run on a real PostgreSQL in [CrmActivityLogPostgresTest].
 */
class CrmActivityLogTest {
    private val person = ActivityLogTesting.user("Ana Ruiz")
    private val permissions = ActivityLogTesting.permissions(granted = setOf(CrmScreens.View, CrmScreens.Manage, CrmScreens.Settings), actor = person)
    private val agentActor = permissions.throughAgent("Claude").activityActor
    private val leadId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()
    private val nuevo = CrmStage(UUID.randomUUID(), "Nuevo", StageKind.Open, StageTone.Info, 0)
    private val ganado = CrmStage(UUID.randomUUID(), "Ganado", StageKind.Won, StageTone.Success, 1)

    private fun crmActor(activity: ActivityActor = person) = CrmActor(person.userId, "Ana Ruiz", activity)

    private fun lead(stage: CrmStage = nuevo) = CrmLead(
        id = leadId, customerId = customerId, name = "Luis Mora", email = "luis@example.com", phone = "+5076000", stageId = stage.id, interestId = null,
        ownerUserIds = emptyList(), source = "Web", language = "es", message = "Quiero reservar", details = "", targetStart = null, targetEnd = null,
        estimatedValue = BigDecimal("1200"), followUpOn = null, stageChangedAt = Instant.now(), closedAt = null, createdAt = Instant.now(),
    )

    /** Runs [block] with the rows of the activity log marked in the SQL of [tenant], so their order against the statements can be asserted. */
    private fun <T> logged(tenant: FakeTenant, block: () -> T): Pair<T, List<ActivityLogTesting.Captured>> {
        val rows = mutableListOf<ActivityLogTesting.Captured>()
        val result = ActivityLog.capturing({ actor, entry ->
            rows += ActivityLogTesting.Captured(actor, entry)
            tenant.mark("ACTIVITY ${entry.entityType}:${entry.action}")
        }) { block() }
        return result to rows
    }

    private fun FakeTenant.withLeadLabel(name: String = "Luis Mora") = rows("select c.display_name from crm_leads l", listOf(name))

    // The lead as the repository reads it (by column name).
    private fun FakeTenant.withLead(lead: CrmLead = lead()) = named(
        "from crm_leads l join customers c on c.id = l.customer_id where l.id",
        listOf(
            "id", "customer_id", "display_name", "primary_email", "primary_phone", "customer_code", "stage_id", "interest_id", "owner_ids", "source", "language", "message",
            "details", "target_start", "target_end", "estimated_value", "follow_up_on", "stage_changed_at", "closed_at", "created_at", "suggested_owner_user_id",
            "review_reason", "archived_at", "restored_at", "custom_fields", "import_key",
        ),
        listOf(
            lead.id, lead.customerId, lead.name, lead.email, lead.phone, "", lead.stageId, null, emptyList<UUID>(), lead.source, lead.language, lead.message,
            lead.details, null, null, lead.estimatedValue, lead.followUpOn, lead.stageChangedAt, null, lead.createdAt, null, "", null, null, "{}", null,
        ),
    )

    @AfterTest
    fun reset() {
        CrmSecrets.box = null
    }

    // ---- Leads ----

    @Test
    fun deletingALeadWritesItsRowBeforeTheDeleteWithItsKeyData() {
        val tenant = FakeTenant()
        val (deleted, rows) = logged(tenant) { CrmRepository.deleteLead(tenant.connection, lead(), crmActor(), "Nuevo") }
        assertTrue(deleted)
        val row = rows.single()
        assertEquals(ActivityActions.Deleted, row.action)
        assertEquals("lead", row.entry.entityType)
        assertEquals(leadId.toString(), row.entry.entityId)
        assertEquals("Luis Mora", row.entry.entityLabel)
        assertEquals("Luis Mora", row.change("name")!!.old)
        assertEquals("luis@example.com", row.change("email")!!.old)
        assertEquals("Nuevo", row.change("stage")!!.old)
        assertEquals("Ana Ruiz", row.display)
        // The row is written before the DELETE that cascades over the history of the lead.
        val log = tenant.statements.indexOfFirst { it.startsWith("ACTIVITY lead:deleted") }
        val delete = tenant.statements.indexOfFirst { it.startsWith("DELETE FROM crm_leads") }
        assertTrue(log in 0 until delete, tenant.statements.toString())
        // And inside one transaction with it.
        assertEquals(1, tenant.commits)
    }

    @Test
    fun anAgentDeletingALeadIsShownAsAgenteDeUsuario() {
        val tenant = FakeTenant()
        val (_, rows) = logged(tenant) { CrmRepository.deleteLead(tenant.connection, lead(), crmActor(agentActor), null) }
        assertEquals("Agente de Ana Ruiz", rows.single().display)
        assertEquals("Claude", rows.single().actor.agentName)
        assertEquals("agent", rows.single().actor.origin)
    }

    @Test
    fun theActorOfTheRepositoryCarriesTheCoreActorOfTheRequest() {
        val tenant = FakeTenant()
        assertEquals("Ana Ruiz", CrmRepository.actor(tenant.connection, permissions).activity.display)
        assertTrue(CrmRepository.actor(tenant.connection, permissions.throughAgent("Claude")).activity.display.startsWith("Agente de "))
    }

    @Test
    fun movingALeadRecordsTheStageChange() {
        val tenant = FakeTenant().withLeadLabel()
        val (_, rows) = logged(tenant) { CrmRepository.moveLead(tenant.connection, lead(), ganado, listOf(nuevo, ganado), crmActor(agentActor)) }
        val row = rows.first { it.action == ActivityActions.StatusChanged }
        assertEquals("Luis Mora", row.entry.entityLabel)
        assertEquals("Nuevo", row.change("stage")!!.old)
        assertEquals("Ganado", row.change("stage")!!.new)
        assertTrue(row.display.startsWith("Agente de "))
        // The history of the lead keeps its text.
        assertTrue(tenant.sqlContaining("INSERT INTO crm_activity").isNotEmpty())
    }

    @Test
    fun archivingAndRestoringALeadAreTheirOwnActions() {
        val tenant = FakeTenant().withLeadLabel()
        val (archived, archive) = logged(tenant) { CrmRepository.archiveLead(tenant.connection, leadId, crmActor()) }
        assertTrue(archived)
        assertEquals(ActivityActions.Archived, archive.single().action)
        assertEquals("Luis Mora", archive.single().entry.entityLabel)
        assertTrue(archive.single().entry.summary.contains("Luis Mora"))
        val (_, restore) = logged(tenant) { CrmRepository.restoreLead(tenant.connection, leadId, crmActor()) }
        assertEquals(ActivityActions.Restored, restore.single().action)
        // Already archived: nothing changed, nothing is logged.
        val none = FakeTenant().updated("set archived_at", 0)
        assertTrue(logged(none) { CrmRepository.archiveLead(none.connection, leadId, crmActor()) }.second.isEmpty())
    }

    @Test
    fun aNoteIsLoggedAsACommentWithItsText() {
        val tenant = FakeTenant().withLeadLabel()
        val (_, rows) = logged(tenant) { CrmRepository.addNote(tenant.connection, leadId, "Llamar el lunes", crmActor()) }
        assertEquals(ActivityActions.Commented, rows.single().action)
        assertEquals("Llamar el lunes", rows.single().change("note")!!.new)
    }

    @Test
    fun editingALeadLogsOldAndNewByFieldAndTheCustomerRowOfClientes() {
        val tenant = FakeTenant().withLeadLabel("Luis Mora")
        val before = lead()
        val input = CrmLeadInput(
            name = "Luis Mora Diaz", email = "luis@example.com", phone = "+5077000", stageId = nuevo.id, interestId = null, ownerUserIds = null, source = "Web",
            language = "es", message = "Quiero reservar", details = "VIP", targetStart = null, targetEnd = null, estimatedValue = BigDecimal("1500"), followUpOn = null,
        )
        val (_, rows) = logged(tenant) {
            CrmRepository.updateLead(tenant.connection, before, input, listOf(nuevo, ganado), CrmNames(emptyList(), emptyList()), crmActor(agentActor))
        }
        val customer = rows.first { it.entry.entityType == "customer" }
        assertEquals(customerId.toString(), customer.entry.entityId)
        assertEquals("Luis Mora", customer.change("name")!!.old)
        assertEquals("Luis Mora Diaz", customer.change("name")!!.new)
        assertEquals("+5077000", customer.change("phone")!!.new)
        assertNull(customer.change("email"))
        val lead = rows.first { it.entry.entityType == "lead" && it.action == ActivityActions.Updated }
        assertEquals("+5076000", lead.change("phone")!!.old)
        assertEquals("VIP", lead.change("details")!!.new)
        assertEquals("1200", lead.change("estimatedValue")!!.old)
        assertEquals("1500", lead.change("estimatedValue")!!.new)
        assertTrue(rows.all { it.display == "Agente de Ana Ruiz" })
        // The text of the lead history is the one tests assert today.
        assertTrue(tenant.statements.any { it.startsWith("INSERT INTO crm_activity") })
    }

    @Test
    fun savingALeadWithoutChangesLogsNothing() {
        val tenant = FakeTenant().withLeadLabel()
        val current = lead()
        val same = CrmLeadInput(
            current.name, current.email, current.phone, current.stageId, null, null, current.source, current.language, current.message, current.details,
            null, null, current.estimatedValue, null,
        )
        val (_, rows) = logged(tenant) {
            CrmRepository.updateLead(tenant.connection, current, same, listOf(nuevo), CrmNames(emptyList(), emptyList()), crmActor())
        }
        assertTrue(rows.isEmpty(), rows.toString())
    }

    @Test
    fun creatingALeadLogsTheLeadAndTheNewCustomer() {
        val tenant = FakeTenant().named("from crm_stages", listOf("id", "name", "kind", "tone", "position"), listOf(nuevo.id, "Nuevo", "open", "info", 0))
        val input = CrmLeadInput(
            name = "Rosa Pinto", email = "rosa@example.com", phone = "", stageId = nuevo.id, interestId = null, ownerUserIds = emptyList(), source = "Web",
            language = "es", message = "Hola", details = "", targetStart = null, targetEnd = null, estimatedValue = null, followUpOn = null,
        )
        val (created, rows) = logged(tenant) { CrmRepository.createLead(tenant.connection, input, nuevo, crmActor()) }
        assertNotNull(created)
        val customer = rows.first { it.entry.entityType == "customer" }
        assertEquals(ActivityActions.Created, customer.action)
        assertEquals("rosa@example.com", customer.change("email")!!.new)
        val lead = rows.first { it.entry.entityType == "lead" }
        assertEquals(ActivityActions.Created, lead.action)
        assertEquals(created.toString(), lead.entry.entityId)
        assertEquals("Rosa Pinto", lead.change("name")!!.new)
        assertEquals("Nuevo", lead.change("stage")!!.new)
    }

    // ---- Messages ----

    @Test
    fun aMessageSentFromATemplateSaysWhichTemplateWentToWhomAndNeverTheBody() {
        val tenant = FakeTenant().withLeadLabel()
        val (_, rows) = logged(tenant) {
            CrmInbox.addMessage(
                tenant.connection, leadId, "out", "email", "Bienvenida", "Hola Luis, tu cuenta secreta es 123456", null, "sent", "", person.userId, "Ana Ruiz",
                person, template = "Bienvenida", to = "luis@example.com",
            )
        }
        val row = rows.single()
        assertEquals("message", row.entry.entityType)
        assertEquals("Bienvenida", row.change("template")!!.new)
        assertEquals("luis@example.com", row.change("to")!!.new)
        assertFalse(row.entry.toString().contains("123456"))
    }

    @Test
    fun anInboundMessageIsTheIntegrationsAndAMessageThatAlreadyExistsIsNotLoggedAgain() {
        val tenant = FakeTenant().withLeadLabel()
        val (_, rows) = logged(tenant) {
            CrmInbox.addMessage(tenant.connection, leadId, "in", "whatsapp", "", "Hola", "wamid.1", "received", "", null, "Luis", CrmInbox.inboundActor("whatsapp"))
        }
        assertEquals("WhatsApp", rows.single().display)
        assertEquals("integration", rows.single().actor.kind)
        assertEquals("webhook", rows.single().actor.origin)
        // ON CONFLICT DO NOTHING: no row inserted, no log.
        val duplicate = FakeTenant().updated("insert into crm_messages", 0)
        assertTrue(logged(duplicate) { CrmInbox.addMessage(duplicate.connection, leadId, "in", "whatsapp", "", "Hola", "wamid.1", "received", "", null, "Luis", CrmInbox.inboundActor("whatsapp")) }.second.isEmpty())
    }

    // ---- Bulk ----

    @Test
    fun aBulkActionWritesOneRowPerLeadAndOneForTheOperation() {
        val tenant = FakeTenant().withLeadLabel()
        val scope = CrmScope(person.userId, seesAll = true, reception = true, interestsEnabled = false, interests = emptySet())
        val context = CrmScreens.Context(tenant.connection, permissions, scope, listOf(nuevo, ganado), emptyList(), emptyList())
        val second = lead().copy(id = UUID.randomUUID(), name = "Rosa Pinto")
        val (_, rows) = logged(tenant) { CrmBulkActions.apply(context, listOf(lead(), second), CrmImportScreens.ActionArchive, "", crmActor(agentActor)) }
        assertEquals(2, rows.count { it.entry.entityType == "lead" && it.action == ActivityActions.Archived })
        val summary = rows.single { it.entry.entityType == "bulk_action" }
        assertEquals(ActivityActions.Archived, summary.action)
        assertEquals("2 de 2", summary.change("count")!!.new)
        assertEquals("Luis Mora, Rosa Pinto", summary.change("leads")!!.new)
        assertTrue(rows.all { it.display == "Agente de Ana Ruiz" })
    }

    @Test
    fun bulkDeleteWritesEachLeadBeforeItsDelete() {
        val tenant = FakeTenant()
        val scope = CrmScope(person.userId, seesAll = true, reception = true, interestsEnabled = false, interests = emptySet())
        val delete = ActivityLogTesting.permissions(granted = setOf(CrmScreens.View, CrmScreens.Manage, CrmScreens.Delete), actor = person)
        val context = CrmScreens.Context(tenant.connection, delete, scope, listOf(nuevo), emptyList(), emptyList())
        val (_, rows) = logged(tenant) { CrmBulkActions.apply(context, listOf(lead()), CrmImportScreens.ActionDelete, "", crmActor()) }
        assertEquals(1, rows.count { it.action == ActivityActions.Deleted && it.entry.entityType == "lead" })
        assertEquals(1, rows.count { it.entry.entityType == "bulk_action" })
        assertTrue(tenant.statements.indexOfFirst { it.startsWith("ACTIVITY lead:deleted") } < tenant.statements.indexOfFirst { it.startsWith("DELETE FROM crm_leads") })
    }

    // ---- Settings ----

    @Test
    fun stagesLogCreationOldAndNewAndDeletionBeforeTheDelete() {
        val tenant = FakeTenant().named("from crm_stages", listOf("id", "name", "kind", "tone", "position"), listOf(nuevo.id, "Nuevo", "open", "info", 0))
            .rows("select count(*) from crm_leads where stage_id", listOf(0))
        val (_, created) = logged(tenant) { CrmRepository.saveStage(tenant.connection, null, CrmStageInput("Visita", StageKind.Open, StageTone.Warning, 3), person) }
        assertEquals(ActivityActions.Created, created.single().action)
        assertEquals("stage", created.single().entry.entityType)
        assertEquals("Visita", created.single().change("name")!!.new)

        val (_, updated) = logged(tenant) { CrmRepository.saveStage(tenant.connection, nuevo.id, CrmStageInput("Nuevo lead", StageKind.Open, StageTone.Info, 0), agentActor) }
        assertEquals(ActivityActions.Updated, updated.single().action)
        assertEquals("Nuevo", updated.single().change("name")!!.old)
        assertEquals("Nuevo lead", updated.single().change("name")!!.new)
        assertNull(updated.single().change("kind"))
        assertEquals("Agente de Ana Ruiz", updated.single().display)

        // No change, no row.
        assertTrue(logged(tenant) { CrmRepository.saveStage(tenant.connection, nuevo.id, CrmStageInput("Nuevo", StageKind.Open, StageTone.Info, 0), person) }.second.isEmpty())

        val (_, deleted) = logged(tenant) { CrmRepository.deleteStage(tenant.connection, nuevo.id, person) }
        assertEquals(ActivityActions.Deleted, deleted.single().action)
        assertEquals("Nuevo", deleted.single().change("name")!!.old)
        assertTrue(tenant.statements.lastIndexOf("ACTIVITY stage:deleted") < tenant.statements.indexOfLast { it.startsWith("DELETE FROM crm_stages") })
    }

    @Test
    fun interestsAndTheirSwitchAreLogged() {
        val tenant = FakeTenant().rows("select interests_enabled from crm_settings", listOf(false)).rows("from crm_interests", listOf(UUID.randomUUID(), "Playa", 0))
        val (_, enabled) = logged(tenant) { CrmRepository.setInterestsEnabled(tenant.connection, true, agentActor) }
        assertEquals(ActivityActions.SettingsChanged, enabled.single().action)
        assertEquals("No", enabled.single().change("enabled")!!.old)
        assertEquals("Si", enabled.single().change("enabled")!!.new)
        val (_, saved) = logged(tenant) { CrmRepository.saveInterest(tenant.connection, null, "Montana", person) }
        assertEquals("Montana", saved.single().change("name")!!.new)
        assertEquals(ActivityActions.Created, saved.single().action)
    }

    @Test
    fun digestHourAndNoticesAreLoggedPerUser() {
        val tenant = FakeTenant().rows("select digest_hour from crm_settings", listOf(7))
        val (_, hour) = logged(tenant) { CrmNotify.setDigestHour(tenant.connection, 9, person) }
        assertEquals("7", hour.single().change("hour")!!.old)
        assertEquals("9", hour.single().change("hour")!!.new)
        val user = UUID.randomUUID()
        val (_, notices) = logged(tenant) {
            CrmNotify.saveUserSettings(tenant.connection, CrmUserSettings(user, "ana@example.com", notifyNewLeads = true, notifyAssigned = true, dailyDigest = false), person, "ana")
        }
        assertEquals(ActivityActions.SettingsChanged, notices.single().action)
        assertEquals("ana@example.com", notices.single().change("email")!!.new)
        assertEquals("Si", notices.single().change("notifyNewLeads")!!.new)
        // The default of notifyAssigned (true) did not change.
        assertNull(notices.single().change("notifyAssigned"))
    }

    @Test
    fun aSecretSettingStoresThatItChangedAndNeverItsValue() {
        CrmSecrets.box = object : SecretBox {
            override val available = true
            override fun seal(tenant: Connection, plain: String) = SecretBox.SealedPrefix + plain.reversed()
            override fun open(tenant: Connection, value: String) = value.removePrefix(SecretBox.SealedPrefix).reversed()
        }
        val tenant = FakeTenant().rows(
            "from crm_settings where id = 1", listOf("deepl", SecretBox.SealedPrefix + "viejaClave".reversed(), "", "", "{}", false),
        )
        val before = CrmTranslate.load(tenant.connection)
        assertEquals("viejaClave", before.apiKey)
        val settings = before.copy(apiKey = "nuevaClaveSuperSecreta")
        val (_, rows) = logged(tenant) { CrmTranslate.save(tenant.connection, settings, agentActor) }
        val row = rows.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        val key = row.change("apiKey")!!
        assertTrue(key.secret)
        assertNull(key.old)
        assertNull(key.new)
        val everything = row.entry.toString()
        assertFalse(everything.contains("nuevaClaveSuperSecreta"))
        assertFalse(everything.contains("viejaClave"))
        assertEquals("Agente de Ana Ruiz", row.display)
    }

    @Test
    fun theMailPasswordIsNeverStored() {
        CrmSecrets.box = object : SecretBox {
            override val available = true
            override fun seal(tenant: Connection, plain: String) = SecretBox.SealedPrefix + plain
            override fun open(tenant: Connection, value: String) = value.removePrefix(SecretBox.SealedPrefix)
        }
        val tenant = FakeTenant().rows(
            "from crm_mail_settings where id = 1",
            listOf(true, "imap.example.com", 993, "ventas", SecretBox.SealedPrefix + "claveVieja", "INBOX", true, null, 0, null, ""),
        )
        val current = CrmMail.load(tenant.connection)
        val next = current.copy(imapPassword = "claveNuevaIMAP", imapHost = "mail.example.com")
        val (_, rows) = logged(tenant) { CrmMail.save(tenant.connection, next, person) }
        val row = rows.single()
        assertEquals("imap.example.com", row.change("imapHost")!!.old)
        assertEquals("mail.example.com", row.change("imapHost")!!.new)
        assertTrue(row.change("imapPassword")!!.secret)
        assertFalse(row.entry.toString().contains("claveNuevaIMAP"))
        assertFalse(row.entry.toString().contains("claveVieja"))
    }

    @Test
    fun templatesAreLoggedWithoutTheirBodyOnDeletionOfAnUnknownOne() {
        val template = UUID.randomUUID()
        val tenant = FakeTenant().rows("from crm_email_templates", listOf(template, "Bienvenida", "Hola", "Texto del correo", "es", false))
        val (deleted, rows) = logged(tenant) { CrmMail.deleteTemplate(tenant.connection, template, person) }
        assertTrue(deleted)
        assertEquals(ActivityActions.Deleted, rows.single().action)
        assertEquals("Bienvenida", rows.single().entry.entityLabel)
        assertTrue(tenant.statements.indexOf("ACTIVITY template:deleted") < tenant.statements.indexOfFirst { it.startsWith("DELETE FROM crm_email_templates") })
    }

    @Test
    fun spamRulesAndAutomationsToggleAreLogged() {
        val tenant = FakeTenant().rows("from crm_spam_settings where id = 1", listOf(listOf("viagra"), 3, false, listOf<String>(), listOf("form"), true))
        val before = CrmSpam.load(tenant.connection)
        val (_, rows) = logged(tenant) { CrmSpam.save(tenant.connection, before.copy(words = listOf("viagra", "casino"), maxLinks = 1), agentActor) }
        assertEquals("viagra", rows.single().change("words")!!.old)
        assertEquals("viagra, casino", rows.single().change("words")!!.new)
        assertEquals("3", rows.single().change("maxLinks")!!.old)
        assertEquals("1", rows.single().change("maxLinks")!!.new)
    }

    // ---- Automations and imports ----

    @Test
    fun anAutomationActsAsItselfWithTheButtonPersonWhenThereIsOne() {
        val automation = CrmAutomation(UUID.randomUUID(), "Seguimiento", true, CrmTrigger(CrmTriggerType.Manual), actions = emptyList())
        val tenant = FakeTenant().withLeadLabel().withLead()
        val refs = CrmAutomationRefs(stages = listOf(nuevo, ganado), fields = emptyList())
        val action = CrmAction(CrmActionKind.SetField, field = CrmAutomationRefs.FollowUpField, value = "2026-12-01")
        val withStep = automation.copy(actions = listOf(action))
        val (outcome, byButton) = logged(tenant) { CrmAutomationEngine.runClaimed(tenant.connection, withStep, lead(), "k1", crmActor(agentActor), null, CrmJavaHttp, refs = refs) }
        assertEquals(CrmRunOutcome.Done, outcome.status, outcome.detail)
        val row = byButton.first { it.entry.entityType == "lead" }
        assertEquals("Automatizacion Seguimiento (Agente de Ana Ruiz)", row.display)
        assertEquals("automation", row.actor.kind)
        assertEquals("2026-12-01", row.change("followUpOn")!!.new)
        val (_, scheduled) = logged(tenant) { CrmAutomationEngine.runClaimed(tenant.connection, withStep, lead(), "k2", null, null, CrmJavaHttp, refs = refs) }
        assertEquals("Automatizacion Seguimiento", scheduled.first { it.entry.entityType == "lead" }.display)
    }

    @Test
    fun anImportRowIsAttributedToTheImportAndItsPerson() {
        val tenant = FakeTenant().named("from crm_stages", listOf("id", "name", "kind", "tone", "position"), listOf(nuevo.id, "Nuevo", "open", "info", 0))
        val activity = ActivityActor.import(person)
        val context = CrmImport.Context(listOf(nuevo, ganado), emptyList(), emptyList(), java.time.LocalDate.of(2026, 11, 1), "file", activity = activity)
        val values = CrmImport.Values(
            key = "file:1", name = "Marta Gil", email = "marta@example.com", phone = "", stage = nuevo, interestId = null, ownerIds = emptyList(),
            ownersUnmatched = emptyList(), fields = mapOf("source" to "", "language" to "", "message" to "", "details" to "", "followUpOn" to "", "targetStart" to "", "targetEnd" to "", "estimatedValue" to ""),
            notes = listOf(CrmImportNote(null, "Ana", "Nota historica")), createdAt = null,
        )
        val (result, rows) = logged(tenant) { CrmImport.apply(tenant.connection, values, context, "archivo", null) }
        assertEquals(CrmImport.Outcome.Created, result.first)
        assertTrue(rows.all { it.display == "Importacion (Ana Ruiz)" && it.actor.kind == "import" }, rows.map { it.display }.toString())
        assertEquals(setOf("customer:created", "lead:created", "lead:commented"), rows.map { "${it.entry.entityType}:${it.action}" }.toSet())
    }

    @Test
    fun theJobOfAnImportIsLoggedWhenItIsCreatedStartedAndCanceled() {
        val tenant = FakeTenant()
        val (job, created) = logged(tenant) {
            CrmImport.create(tenant.connection, "file", "Clientes 2026", CrmTable(listOf("Nombre"), listOf(mapOf("Nombre" to "Ana"), mapOf("Nombre" to "Beto"))), person.userId, agentActor)
        }
        assertEquals("import", created.single().entry.entityType)
        assertEquals(job.toString(), created.single().entry.entityId)
        assertEquals("2", created.single().change("rows")!!.new)
        val importJob = CrmImportJob(job, "file", "Clientes 2026", "mapping", CrmImportMapping(), listOf("Nombre"), 2, 0, 0, 0, 0, 0, 0, Instant.now())
        val (_, started) = logged(tenant) { CrmImport.setStatus(tenant.connection, importJob, "running", agentActor) }
        assertEquals(ActivityActions.StatusChanged, started.single().action)
        assertEquals("mapping", started.single().change("status")!!.old)
        assertEquals("running", started.single().change("status")!!.new)
        assertEquals("Agente de Ana Ruiz", started.single().display)
    }

    @Test
    fun assigningLogsWhoTheLeadHadAndWhoItHasNow() {
        val beto = UUID.randomUUID()
        val tenant = FakeTenant().withLeadLabel()
            .rows("insert into crm_lead_owners", listOf(beto))
            .rows("select user_id from crm_lead_owners", listOf(beto))
        val names = CrmNames(listOf(CrmUser(beto, "Beto Ruiz")), emptyList())
        val (change, rows) = logged(tenant) { CrmRepository.assign(tenant.connection, lead(), CrmAssignees.Mode.Add, listOf(beto), names, crmActor(agentActor)) }
        assertEquals(listOf(beto), change.added)
        val row = rows.single()
        assertEquals(ActivityActions.Assigned, row.action)
        assertNull(row.change("owners")!!.old)
        assertEquals("Beto Ruiz", row.change("owners")!!.new)
        assertEquals("Agente de Ana Ruiz", row.display)
        // Nothing changed: no row.
        val none = FakeTenant().withLeadLabel()
        assertTrue(logged(none) { CrmRepository.assign(none.connection, lead(), CrmAssignees.Mode.Add, listOf(beto), names, crmActor()) }.second.isEmpty())
    }

    @Test
    fun resolvingASuggestionAndMarkingAReviewAreLogged() {
        val beto = UUID.randomUUID()
        val names = CrmNames(listOf(CrmUser(beto, "Beto Ruiz")), emptyList())
        val tenant = FakeTenant().withLeadLabel().rows("insert into crm_lead_owners", listOf(beto))
        val suggested = lead().copy(suggestedOwnerUserId = beto)
        val (_, approved) = logged(tenant) { CrmRepository.resolveSuggestion(tenant.connection, suggested, true, names, crmActor()) }
        assertEquals(ActivityActions.Assigned, approved.single().action)
        assertEquals("Beto Ruiz", approved.single().change("suggestedOwner")!!.old)
        assertEquals("Asignado", approved.single().change("suggestedOwner")!!.new)
        val (_, discarded) = logged(tenant) { CrmRepository.resolveSuggestion(tenant.connection, suggested, false, names, crmActor()) }
        assertEquals("Descartado", discarded.single().change("suggestedOwner")!!.new)
        val (_, reviewed) = logged(tenant) { CrmRepository.clearReview(tenant.connection, lead().copy(reviewReason = "El telefono es de otro cliente"), crmActor()) }
        assertEquals("El telefono es de otro cliente", reviewed.single().change("review")!!.old)
    }

    @Test
    fun theOtherHistoryEntriesOfALeadAreMirroredInTheLog() {
        val tenant = FakeTenant().withLeadLabel()
        val (_, rows) = logged(tenant) {
            CrmRepository.addActivity(tenant.connection, leadId, "item", "Interes agregado: Casa en la playa.", crmActor(agentActor))
            CrmRepository.addActivity(tenant.connection, leadId, "web", "Vio la propiedad Casa", CrmActor(null, "Sitio web", ActivityActor.integration("Sitio web")))
            CrmRepository.addActivity(tenant.connection, leadId, "stage", "Nuevo -> Ganado", crmActor(), fromStage = nuevo.id, toStage = ganado.id, audit = CrmLeadAudit.None)
        }
        assertEquals(2, rows.size)
        assertEquals(listOf("Agente de Ana Ruiz", "Sitio web"), rows.map { it.display })
        assertTrue(rows.all { it.action == ActivityActions.Updated && it.entry.entityLabel == "Luis Mora" })
        assertTrue(rows.first().entry.summary.contains("Interes agregado: Casa en la playa."))
    }

    // ---- Coverage of every write path ----

    /**
     * Every MCP write tool of the CRM and where its activity row comes from. A new write tool fails
     * [everyWriteToolOfTheCrmIsClassified] until it is listed here, so none is left without its row. Tools of the integrations run
     * the extension code and are tested in the tests of each extension.
     */
    private val classification = mapOf(
        // Leads (this class).
        "crm_create_lead" to "createLead", "crm_update_lead" to "updateLead", "crm_move_lead" to "moveLead", "crm_restore_lead" to "restoreLead",
        "crm_add_lead_note" to "addNote", "crm_send_message" to "addMessage", "crm_mark_lead_reviewed" to "clearReview",
        "crm_resolve_assignment" to "resolveSuggestion", "crm_delete_lead" to "deleteLead", "crm_bulk_update_leads" to "CrmBulkActions",
        "crm_add_lead_item" to "addActivity", "crm_set_lead_item_status" to "addActivity", "crm_remove_lead_item" to "addActivity",
        "crm_close_property_sale" to "addActivity + moveLead (+ wordpress markSold)", "crm_link_property" to "wordpress linkListing",
        // Settings (this class).
        "crm_apply_pipeline_template" to "applyTemplate", "crm_delete_interest" to "deleteInterest", "crm_delete_message_template" to "deleteTemplate",
        "crm_delete_stage" to "deleteStage", "crm_save_interest" to "saveInterest", "crm_save_message_template" to "saveTemplate",
        "crm_save_stage" to "saveStage", "crm_set_digest_hour" to "setDigestHour", "crm_set_exit_automation" to "CrmExit.save",
        "crm_set_interests_enabled" to "setInterestsEnabled", "crm_set_user_interests" to "setUserInterests", "crm_update_mail_settings" to "CrmMail.save",
        "crm_update_spam_rules" to "CrmSpam.save", "crm_update_user_notifications" to "saveUserSettings",
        // Automations (this class).
        "crm_delete_automation" to "CrmAutomationStore.delete", "crm_save_automation" to "CrmAutomationStore.save",
        "crm_run_automation" to "CrmAutomationEngine (automation actor)", "crm_press_lead_button" to "CrmButtons (template message / automation actor)",
        // Imports (this class).
        "crm_import_csv" to "CrmImport.create", "crm_import_from_url" to "CrmImport.create", "crm_map_import" to "saveMappingCreatingFields + savePreset",
        "crm_apply_import_preset" to "the mapping is part of the job (logged when it starts)", "crm_start_import" to "CrmImport.setStatus", "crm_cancel_import" to "CrmImport.setStatus",
        // Integrations (tests of each extension).
        "crm_update_meta" to "CrmMeta.save", "crm_meta_connect_page" to "CrmMeta.setup", "crm_meta_connect_whatsapp" to "CrmMeta.setup", "crm_meta_fill_names" to "CrmInbox.fillName",
        "crm_update_wordpress" to "WordPressSettingsStore.saveForm", "crm_wordpress_sync_agents" to "WordPressSettingsStore.syncAgents",
        "crm_wordpress_map_agent" to "WordPressSettingsStore.mapAgent", "crm_wordpress_test" to "only records the last error of the site",
        "crm_update_encuentra24" to "Encuentra24SettingsStore.saveForm", "crm_encuentra24_sync" to "CrmEncuentra24.sync",
        "crm_update_cloudbeds" to "CloudbedsSettingsStore.saveForm", "crm_cloudbeds_test" to "only records the last error of Cloudbeds",
        "crm_assign_room" to "CloudbedsServerExtension.assignRoom", "crm_create_reservation" to "CloudbedsServerExtension.reserveForLead",
    )

    @Test
    fun everyWriteToolOfTheCrmIsClassified() {
        assertEquals(classification.keys.sorted(), ActivityLogTesting.writeTools(CrmServerModule))
    }

    @Test
    fun theOnlyWriteRoutesAreTheScreenActionsAndTheFileUpload() {
        // Every screen action runs CrmServerModule.action with the Core actor of the request (permissions.activityActor, tested above).
        assertEquals(listOf("POST /modules/crm/ui/files", "POST /modules/crm/ui/{screen}"), ActivityLogTesting.writeRoutes(CrmServerModule).sorted())
    }
}
