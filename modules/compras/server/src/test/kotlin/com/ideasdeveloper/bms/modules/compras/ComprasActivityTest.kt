package com.ideasdeveloper.bms.modules.compras

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.modules.McpToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cada escritura de compras deja su fila del registro de actividad, con quien la hizo. Corre sobre una conexion
 * falsa que responde por texto de SQL (sin base de datos); las filas se capturan con ActivityLogTesting.
 */
class ComprasActivityTest {
    private val requestId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c001")
    private val quoteId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c002")
    private val departmentId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c003")
    private val attachmentId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c004")
    private val ana: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val luis: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

    // Fila de solicitud con las columnas que lee RequestSelect.
    private fun requestRow(status: RequestStatus = RequestStatus.Draft, title: String = "Sabanas", amount: String = "500.00") = linkedMapOf<String, Any?>(
        "id" to requestId, "number" to 12L, "title" to title, "description" to "", "department_id" to null, "department_name" to null,
        "estimated_amount" to BigDecimal(amount), "currency" to "USD", "required_date" to null, "priority" to "medium", "status" to status.id,
        "requester_id" to ana, "requester_name" to "Ana Ruiz", "decided_by" to null, "decided_by_name" to null, "decided_at" to null,
        "decision_reason" to null, "approved_amount" to null, "approved_currency" to null, "source_kind" to null, "source_ref" to null,
        "submitted_at" to null, "completed_at" to null, "created_at" to Instant.EPOCH, "updated_at" to Instant.EPOCH,
        "approval_amount" to BigDecimal(amount), "approval_currency" to "USD",
    )

    /** Conexion falsa: [answer] da las filas de cada consulta y [updated] cuenta las escrituras. */
    private inner class Db(private val before: Map<String, Any?>?, private val after: Map<String, Any?>? = before) {
        private var requestUpdated = false
        val tenant: Connection = fakeConnection(
            answer = ::answer,
            update = { sql -> if (sql.startsWith("UPDATE compras_requests SET title")) requestUpdated = true; 1 },
        )

        private fun answer(sql: String): List<Map<String, Any?>> = when {
            sql.contains("FOR UPDATE") -> listOf(mapOf("x" to 1))
            sql.endsWith("WHERE r.id = ?") -> listOfNotNull(if (requestUpdated) after else before)
            sql.contains("FROM compras_requests r") -> listOfNotNull(before)
            sql.contains("SELECT count(*) FROM compras_quotes") -> listOf(mapOf("count" to 0))
            sql.contains("FROM compras_quotes WHERE id") -> listOf(quoteRow())
            sql.contains("compras_approvers a") -> emptyList()
            sql.contains("SELECT name, active FROM compras_departments") -> listOf(mapOf("name" to "Compras", "active" to true))
            sql.contains("SELECT name FROM compras_departments") -> listOf(mapOf("name" to "Compras"))
            sql.contains("SELECT default_currency") -> listOf(mapOf("default_currency" to "USD"))
            sql.contains("SELECT COALESCE(NULLIF(trim(display_name)") -> listOf(mapOf("name" to "Luis Mora"))
            sql.contains("postgres_role, display_name, email FROM application_users") -> listOf(mapOf("postgres_role" to "ana", "display_name" to "Ana Ruiz", "email" to "ana@x.com"))
            sql.contains("SELECT count(*) FROM compras_attachments") -> listOf(mapOf("count" to 0))
            sql.contains("SET request_id") -> listOf(mapOf("file_name" to "contrato.pdf", "size_bytes" to 24_576L))
            sql.contains("SET deleted_at") -> listOf(mapOf("file_name" to "contrato.pdf"))
            else -> emptyList()
        }

        private fun quoteRow() = mapOf<String, Any?>(
            "id" to quoteId, "request_id" to requestId, "supplier" to "Ferreteria Sol", "amount" to BigDecimal("120"), "currency" to "USD",
            "notes" to "", "selected" to false, "quoted_by" to ana, "quoted_by_name" to "Ana Ruiz", "created_at" to Instant.EPOCH,
        )
    }

    private fun actorOf(granted: Set<String>, userId: UUID = ana, owner: Boolean = false, agent: Boolean = false): Actor {
        val person = ActivityLogTesting.user("Ana Ruiz", userId)
        val permissions = ActivityLogTesting.permissions(granted = granted, businessOwner = owner, actor = person)
        val scoped = if (agent) permissions.throughAgent("Claude") else permissions
        return Actor(userId, "Ana Ruiz", scoped, scoped.activityActor)
    }

    private val owner = actorOf(setOf(ComprasModule.Manage), userId = luis, owner = true)

    @Test
    fun createRecordsCreatedRowThroughAnAgent() {
        val request = requestRow()
        val db = Db(before = request)
        val rows = ActivityLogTesting.rows {
            ComprasRepository.create(db.tenant, actorOf(setOf(ComprasModule.Create), agent = true), RequestDraft(title = "Sabanas", estimatedAmount = BigDecimal("500.00"), currency = "USD"))
        }
        val row = rows.single()
        assertEquals(ActivityActions.Created, row.action)
        assertEquals("solicitud", row.entry.entityType)
        assertTrue(row.display.startsWith("Agente de "), row.display)
        assertEquals("Sabanas", row.change("title")!!.new)
        assertEquals("500", row.change("estimatedAmount")!!.new)
    }

    @Test
    fun editRecordsOnlyTheChangedFields() {
        val db = Db(before = requestRow(title = "Sabanas"), after = requestRow(title = "Sabanas king"))
        val rows = ActivityLogTesting.rows {
            ComprasRepository.update(db.tenant, actorOf(setOf(ComprasModule.Create)), requestId, RequestDraft(title = "Sabanas king", estimatedAmount = BigDecimal("500.00"), currency = "USD"))
        }
        val row = rows.single()
        assertEquals(ActivityActions.Updated, row.action)
        assertEquals("Sabanas", row.change("title")!!.old)
        assertEquals("Sabanas king", row.change("title")!!.new)
        assertNull(row.change("currency"))
    }

    @Test
    fun rejectionRecordsStatusChangeWithReason() {
        val db = Db(before = requestRow(status = RequestStatus.PendingApproval))
        val rows = ActivityLogTesting.rows {
            ComprasRepository.transition(db.tenant, owner, requestId, RequestStatus.Rejected, "Falta presupuesto")
        }
        val row = rows.single()
        assertEquals(ActivityActions.StatusChanged, row.action)
        assertEquals("Pendiente de aprobacion", row.change("status")!!.old)
        assertEquals("Rechazada", row.change("status")!!.new)
        assertEquals("Falta presupuesto", row.change("reason")!!.new)
    }

    @Test
    fun settingsChangeStoresOldAndNewCurrency() {
        val db = Db(before = null)
        val rows = ActivityLogTesting.rows { ComprasRepository.setDefaultCurrency(db.tenant, owner, "EUR") }
        val row = rows.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertEquals("settings", row.entry.entityType)
        assertEquals("USD", row.change("defaultCurrency")!!.old)
        assertEquals("EUR", row.change("defaultCurrency")!!.new)
    }

    @Test
    fun departmentCreateAndDeactivateAreRecorded() {
        val db = Db(before = null)
        val created = ActivityLogTesting.rows { ComprasRepository.saveDepartment(db.tenant, owner, null, "Compras") }.single()
        assertEquals(ActivityActions.Created, created.action)
        assertEquals("Compras", created.change("name")!!.new)
        val archived = ActivityLogTesting.rows { ComprasRepository.setDepartmentActive(db.tenant, owner, departmentId, false) }.single()
        assertEquals(ActivityActions.Archived, archived.action)
        assertEquals("Si", archived.change("active")!!.old)
        assertEquals("No", archived.change("active")!!.new)
    }

    @Test
    fun approverDesignationUsesTheRealPerson() {
        val db = Db(before = null)
        val rows = ActivityLogTesting.rows {
            ComprasRepository.saveApprover(db.tenant, owner, luis, BigDecimal("1000"), "USD", true)
        }
        val row = rows.single()
        assertEquals(ActivityActions.Created, row.action)
        assertEquals("aprobador", row.entry.entityType)
        assertEquals("Luis Mora", row.change("name")!!.new)
        assertEquals("1000", row.change("maxAmount")!!.new)
        assertEquals("Luis Mora", row.entry.entityLabel)
    }

    @Test
    fun addingAQuoteRecordsItsValues() {
        val db = Db(before = requestRow())
        val rows = ActivityLogTesting.rows {
            ComprasRepository.addQuote(db.tenant, actorOf(setOf(ComprasModule.Create)), requestId, "Ferreteria Sol", BigDecimal("120"), "USD")
        }
        val row = rows.single()
        assertEquals(ActivityActions.Created, row.action)
        assertEquals("cotizacion", row.entry.entityType)
        assertEquals("Ferreteria Sol", row.change("supplier")!!.new)
        assertEquals("120", row.change("amount")!!.new)
    }

    @Test
    fun deletingAQuoteKeepsItsValuesInTheRow() {
        val db = Db(before = requestRow())
        val row = ActivityLogTesting.rows {
            ComprasRepository.deleteQuote(db.tenant, actorOf(setOf(ComprasModule.Create)), requestId, quoteId)
        }.single()
        assertEquals(ActivityActions.Deleted, row.action)
        assertEquals("Ferreteria Sol", row.change("supplier")!!.old)
        assertNull(row.change("supplier")!!.new)
    }

    @Test
    fun linkingAndRemovingAttachmentsRecordFileNamesAndSizes() {
        val db = Db(before = requestRow())
        val actor = actorOf(setOf(ComprasModule.Create))
        val linked = ActivityLogTesting.rows {
            ComprasAttachments.link(db.tenant, actor, requestId, listOf(attachmentId))
        }.single()
        assertEquals(ActivityActions.AttachmentAdded, linked.action)
        assertEquals("contrato.pdf (24 KB)", linked.change("file")!!.new)
        val removed = ActivityLogTesting.rows {
            ComprasAttachments.remove(db.tenant, actor, requestId, attachmentId)
        }.single()
        assertEquals(ActivityActions.AttachmentRemoved, removed.action)
        assertEquals("contrato.pdf", removed.change("file")!!.old)
        assertNull(removed.change("file")!!.new)
    }

    @Test
    fun mcpCreateToolRecordsAgentRow() {
        val db = Db(before = requestRow())
        val tool = ComprasMcpTools.mcp.tools.single { it.name == "create_request" }
        val permissions = ActivityLogTesting.permissions(granted = setOf(ComprasModule.Create), actor = ActivityLogTesting.user("Ana Ruiz", ana)).throughAgent("Claude")
        val call = McpToolCall(
            JsonObject(mapOf("title" to JsonPrimitive("Sabanas"), "estimated_amount" to JsonPrimitive("500.00"))),
            permissions, db.tenant, ActivityLogTesting.accessWithoutDatabase(), "Claude", ComprasModule.id,
        )
        val rows = ActivityLogTesting.rows { tool.handler(call) }
        val row = rows.single()
        assertEquals(ActivityActions.Created, row.action)
        assertTrue(row.display.startsWith("Agente de "), row.display)
        assertEquals("Agente de Ana Ruiz", row.display)
    }
}

/** Conexion JDBC falsa: [answer] responde las consultas por su SQL; [update] cuenta las escrituras. */
private fun fakeConnection(answer: (String) -> List<Map<String, Any?>>, update: (String) -> Int): Connection {
    var autoCommit = true
    fun statement(sql: String): PreparedStatement = fakeProxy(PreparedStatement::class.java) { name, _ ->
        when (name) {
            "executeUpdate" -> update(sql)
            "executeQuery" -> resultSet(answer(sql))
            else -> Unit
        }
    }
    return fakeProxy(Connection::class.java) { name, args ->
        when (name) {
            "prepareStatement" -> statement(args[0] as String)
            "getAutoCommit" -> autoCommit
            "setAutoCommit" -> { autoCommit = args[0] as Boolean; Unit }
            "createArrayOf" -> null
            else -> Unit
        }
    }
}

private fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
    var index = -1
    fun value(key: Any?): Any? {
        val row = rows[index]
        return when (key) {
            is Int -> row.values.elementAt(key - 1)
            is String -> row[key]
            else -> null
        }
    }
    return fakeProxy(ResultSet::class.java) { name, args ->
        when (name) {
            "next" -> { index++; index < rows.size }
            "getString" -> value(args[0])?.toString()
            "getLong" -> (value(args[0]) as Number?)?.toLong() ?: 0L
            "getInt" -> (value(args[0]) as Number?)?.toInt() ?: 0
            "getBoolean" -> value(args[0]) as Boolean? ?: false
            "getBigDecimal" -> value(args[0]) as BigDecimal?
            "getTimestamp" -> when (val v = value(args[0])) {
                is Instant -> Timestamp.from(v)
                else -> v as Timestamp?
            }
            "getObject" -> value(args[0])
            else -> Unit
        }
    }
}

private inline fun <reified T : Any> fakeProxy(type: Class<T>, crossinline handler: (String, List<Any?>) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        when (method.name) {
            "toString" -> "fake"
            "hashCode" -> 0
            "equals" -> false
            "close" -> Unit
            else -> handler(method.name, args?.toList().orEmpty())
        }
    } as T
