package com.ideasdeveloper.bms.extensions.monday

import com.ideasdeveloper.bms.modules.crm.CrmAttachments
import com.ideasdeveloper.bms.modules.crm.CrmFieldInput
import com.ideasdeveloper.bms.modules.crm.CrmFieldType
import com.ideasdeveloper.bms.modules.crm.CrmFields
import com.ideasdeveloper.bms.modules.crm.FakeSecrets
import com.ideasdeveloper.bms.modules.crm.FakeTenant
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.modules.CompanyFiles
import com.ideasdeveloper.bms.server.modules.LocalFileBackend
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.outbound.monday.MondayColumnRef
import com.ideasdeveloper.bms.server.outbound.monday.MondayOutboundConfig
import com.ideasdeveloper.bms.server.outbound.monday.MondayOutboundConfigStore
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the monday extension writes in the company activity log: its token (only "changed"), the import it queues, the files it brings in and its sending configuration. */
class MondayActivityLogTest {
    private val person = ActivityLogTesting.user("Ana Ruiz")
    private val agentPermissions = ActivityLogTesting.permissions(actor = person).throughAgent("Claude")
    private val agent = agentPermissions.activityActor

    @BeforeTest
    fun secrets() = FakeSecrets.install()

    @AfterTest
    fun noSecrets() = FakeSecrets.remove()

    private fun <T> logged(block: () -> T): Pair<T, List<ActivityLogTesting.Captured>> = ActivityLogTesting.capture(block)

    @Test
    fun theTokenIsLoggedAsChangedAndNeverItsValue() {
        val tenant = FakeTenant().rows("from tenant_extensions", listOf("enabled")).rows("select token from ext_monday__settings", listOf(FakeSecrets.sealed("tokenViejoDeMonday")))
        val (_, saved) = logged { MondayImport.saveMondayToken(tenant.connection, "tokenNuevoDeMondayMuySecreto", agent) }
        val row = saved.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertTrue(row.change("token")!!.secret)
        listOf("tokenViejoDeMonday", "tokenNuevoDeMondayMuySecreto").forEach { assertFalse(row.entry.toString().contains(it), it) }
        assertEquals("Agente de Ana Ruiz", row.display)
        val (_, removed) = logged { MondayImport.saveMondayToken(tenant.connection, null, person) }
        assertTrue(removed.single().entry.summary.startsWith("Borro"))
    }

    @Test
    fun queueingBoardsLogsTheImportAndWhoStartedIt() {
        val tenant = FakeTenant().rows("from tenant_extensions", listOf("enabled"))
        val (job, rows) = logged { MondayImport.queueMonday(tenant.connection, listOf("1234567"), person.userId, agent) }
        val row = rows.single()
        assertEquals("import", row.entry.entityType)
        assertEquals(job.toString(), row.entry.entityId)
        assertEquals(ActivityActions.Created, row.action)
        assertEquals("Agente de Ana Ruiz", row.display)
    }

    @Test
    fun aFileBroughtInByTheImportIsLoggedWithItsNameAndSizeNeverItsContent() {
        val lead = UUID.randomUUID()
        val tenant = FakeTenant().rows("select c.display_name from crm_leads l", listOf("Luis Mora")).rows("select count(*) from crm_attachments", listOf(0))
        val files = CompanyFiles(listOf(LocalFileBackend(java.nio.file.Files.createTempDirectory("monday-files")))).module("acme", "crm")
        val content = "contenido secreto del contrato".toByteArray()
        val (_, rows) = logged {
            CrmAttachments.importLinked(tenant.connection, files, lead, "documents", "contrato.pdf", "", "application/pdf", content.inputStream(), "monday:asset:1", "Importacion monday", ActivityActor.import(person))
        }
        val row = rows.single()
        assertEquals(ActivityActions.AttachmentAdded, row.action)
        assertEquals("contrato.pdf (30 B)", row.change("file")!!.new)
        assertEquals("Importacion (Ana Ruiz)", row.display)
        assertFalse(row.entry.toString().contains("secreto"))
    }

    // An in-memory store of the sending configuration, as the tests of the Core use.
    private class MemoryStore : MondayOutboundConfigStore {
        val saved = mutableMapOf<String, MondayOutboundConfig>()
        override fun get(tenant: Connection, recordType: String) = saved[recordType]
        override fun all(tenant: Connection) = saved.values.toList()
        override fun save(tenant: Connection, config: MondayOutboundConfig) { saved[config.recordType] = config }
        override fun delete(tenant: Connection) = saved.clear()
    }

    @Test
    fun theSendingConfigurationLogsOldAndNewWithThePersonOrTheAgentWhoSavedIt() {
        val store = AuditedMondayConfigStore(MemoryStore())
        val tenant = FakeTenant()
        val first = MondayOutboundConfig("lead", enabled = true, boardId = "1111", columns = mapOf("name" to MondayColumnRef("name", "name")))
        val (_, created) = logged { MondayAudit.acting(person) { store.save(tenant.connection, first) } }
        assertEquals("Si", created.single().change("enabled")!!.new)
        assertEquals("1111", created.single().change("boardId")!!.new)
        assertEquals("name -> name", created.single().change("columns")!!.new)
        assertEquals("Ana Ruiz", created.single().display)
        // An agent's tool: the Core tool runs inside guarded(), which names the agent for what it saves.
        val tool = McpTool("outbound_configure", "Configurar", "x", "crm.settings") { call ->
            store.save(call.tenant, first.copy(boardId = "2222"))
            JsonObject(mapOf("ok" to JsonPrimitive(true)))
        }
        val call = McpToolCall(JsonObject(emptyMap()), agentPermissions, tenant.connection, ActivityLogTesting.accessWithoutDatabase(), "Claude", "crm")
        val (_, byAgent) = logged { MondayAudit.guarded(tool).handler(call) }
        assertEquals("1111", byAgent.single().change("boardId")!!.old)
        assertEquals("2222", byAgent.single().change("boardId")!!.new)
        assertEquals("Agente de Ana Ruiz", byAgent.single().display)
        // Saving the same thing again changes nothing.
        assertTrue(logged { MondayAudit.acting(person) { store.save(tenant.connection, first.copy(boardId = "2222")) } }.second.isEmpty())
        // A write tool the extension does not wrap keeps its own call; a read tool is left as it is.
        val read = McpTool("outbound_status", "Estado", "x", "crm.settings", readOnly = true) { JsonObject(emptyMap()) }
        assertTrue(MondayAudit.guarded(read) === read)
        assertEquals("Sistema", MondayAudit.actor().display)
    }

    @Test
    fun theFieldsAnImportCreatesAreLoggedWithTheImportActor() {
        val tenant = FakeTenant().rows("from crm_field_sections", listOf(UUID.randomUUID(), "Datos", 0))
        val (field, rows) = logged { CrmFields.save(tenant.connection, null, CrmFieldInput("Origen monday", CrmFieldType.Text, emptyList(), false, false, 0), emptyList(), ActivityActor.import(person)) }
        assertEquals("field", rows.single().entry.entityType)
        assertEquals(field.id.toString(), rows.single().entry.entityId)
        assertEquals("Importacion (Ana Ruiz)", rows.single().display)
        assertEquals("Origen monday", rows.single().change("label")!!.new)
    }
}
