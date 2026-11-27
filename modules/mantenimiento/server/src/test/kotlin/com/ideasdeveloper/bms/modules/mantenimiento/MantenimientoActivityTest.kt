package com.ideasdeveloper.bms.modules.mantenimiento

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
 * Cada cambio de mantenimiento deja su fila en el registro de actividad, con quien lo hizo: la orden (alta, edicion con
 * diferencias, nota, responsable), su lista de comprobacion y sus adjuntos, los ajustes y la herramienta MCP por agente.
 * Corre sobre una conexion falsa que responde por texto de SQL; las filas se capturan.
 */
class MantenimientoActivityTest {
    private val orderId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a001")
    private val itemId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a002")
    private val attachmentId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a003")
    private val ana: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    private fun orderRow(title: String = "Fuga en el techo") = linkedMapOf<String, Any?>(
        "id" to orderId, "number" to 7L, "kind" to "corrective", "status" to "open", "priority" to "medium", "title" to title,
        "description" to "", "property_id" to null, "property_name" to null, "asset_id" to null, "asset_name" to null, "plan_id" to null,
        "plan_due" to null, "due_date" to null, "assignee_id" to null, "requested_by" to ana, "requested_by_name" to "Ana Ruiz",
        "department" to "", "quote_amount" to null, "quote_currency" to "USD", "quoted_by" to "", "cost_amount" to null,
        "cost_currency" to "USD", "resolution" to "", "rejected_reason" to "", "approved_at" to null, "approved_by" to null,
        "completed_at" to null, "completed_by" to null, "tareas_task_id" to null, "purchase_request_id" to null, "purchase_folio" to "",
        "purchase_linking_at" to null, "created_by" to ana, "created_at" to null, "updated_at" to null, "archived_at" to null,
        "checks_done" to 0L, "checks_total" to 0L,
    )

    /** Filas de la base falsa. Tras un UPDATE de titulo, la orden ya lleva el titulo nuevo. */
    private inner class Db(private val before: Map<String, Any?> = orderRow()) {
        private var titleChanged = false
        private var after: Map<String, Any?>? = null

        val tenant: Connection = fakeConnection(
            answer = ::answer,
            update = { sql ->
                if (sql.contains("SET title = ?")) {
                    titleChanged = true
                    after = orderRow(title = "Fuga grave")
                }
                1
            },
        )

        private fun answer(sql: String): List<Map<String, Any?>> = when {
            sql.contains("SELECT id FROM mantenimiento_orders WHERE id = ? FOR UPDATE") -> listOf(mapOf("x" to 1))
            sql.contains("WHERE o.id = ?") -> listOf(if (titleChanged) after!! else before)
            sql.contains("SELECT text, done FROM mantenimiento_order_checklist") -> listOf(mapOf("text" to "Revisar filtro", "done" to false))
            sql.contains("SELECT text FROM mantenimiento_order_checklist") -> listOf(mapOf("text" to "Revisar filtro"))
            sql.contains("SELECT count(*) AS total FROM mantenimiento_order_checklist") -> listOf(mapOf("total" to 0))
            sql.contains("SELECT count(*) AS total FROM mantenimiento_order_notes") -> listOf(mapOf("total" to 0))
            sql.contains("SELECT count(*) AS total FROM mantenimiento_attachments") -> listOf(mapOf("total" to 0))
            sql.contains("UPDATE mantenimiento_attachments SET order_id") -> listOf(mapOf("name" to "plano.pdf", "size" to 2048L))
            sql.contains("SELECT name FROM mantenimiento_attachments") -> listOf(mapOf("name" to "foto.jpg"))
            sql.contains("FROM mantenimiento_settings") -> listOf(mapOf("tareas_board_id" to null, "tareas_publisher_id" to null, "due_soon_days" to 1))
            sql.contains("postgres_role, display_name, email FROM application_users") -> listOf(mapOf("postgres_role" to "ana", "display_name" to "Ana Ruiz", "email" to "ana@x.com"))
            else -> emptyList()
        }
    }

    private fun person(): com.ideasdeveloper.bms.server.activity.ActivityActor = ActivityLogTesting.user("Ana Ruiz", ana)

    private fun actor(agent: Boolean = false): MantenimientoActor {
        val activity = if (agent) person().viaAgent("Claude") else person()
        return MantenimientoActor(ana, "Ana Ruiz", activity)
    }

    @Test
    fun createdOrderRecordsItsTitleAndPriority() {
        val db = Db()
        val row = ActivityLogTesting.rows {
            MantenimientoRepository.createOrder(db.tenant, OrderInput(title = "Fuga en el techo"), actor())
        }.single()
        assertEquals(ActivityActions.Created, row.action)
        assertEquals("orden", row.entry.entityType)
        assertEquals("Fuga en el techo", row.change("title")!!.new)
        assertTrue(row.entry.summary.contains("OT-0007"), row.entry.summary)
    }

    @Test
    fun editedOrderRecordsOnlyWhatChanged() {
        val db = Db()
        val rows = ActivityLogTesting.rows {
            MantenimientoRepository.updateOrder(db.tenant, orderId, OrderInput(title = "Fuga grave"), actor())
        }
        val row = rows.single()
        assertEquals(ActivityActions.Updated, row.action)
        assertEquals("Fuga en el techo", row.change("title")!!.old)
        assertEquals("Fuga grave", row.change("title")!!.new)
        assertNull(row.change("priority"))
    }

    @Test
    fun writtenNoteIsRecordedAsCommented() {
        val db = Db()
        val row = ActivityLogTesting.rows { MantenimientoRepository.addNote(db.tenant, orderId, "Revisar el lunes", actor()) }.single()
        assertEquals(ActivityActions.Commented, row.action)
        assertEquals("Revisar el lunes", row.change("note")!!.new)
    }

    @Test
    fun checklistItemsAreRecordedWhenAddedToggledAndRemoved() {
        val db = Db()
        val activity = actor().activity
        val added = ActivityLogTesting.rows { MantenimientoRepository.addCheckItem(db.tenant, orderId, "Revisar filtro", activity) }.single()
        assertEquals(ActivityActions.Created, added.action)
        assertEquals("checklist", added.entry.entityType)
        val toggled = ActivityLogTesting.rows { MantenimientoRepository.setCheckDone(db.tenant, orderId, itemId, true, ana, activity) }.single()
        assertEquals("No", toggled.change("done")!!.old)
        assertEquals("Si", toggled.change("done")!!.new)
        val removed = ActivityLogTesting.rows { MantenimientoRepository.removeCheckItem(db.tenant, orderId, itemId, activity) }.single()
        assertEquals(ActivityActions.Deleted, removed.action)
        assertEquals("Revisar filtro", removed.change("text")!!.old)
    }

    @Test
    fun attachmentsAreRecordedByNameAndSize() {
        val db = Db()
        val added = ActivityLogTesting.rows {
            MantenimientoRepository.attach(db.tenant, orderId, listOf(attachmentId), ana, actor().activity)
        }.single()
        assertEquals(ActivityActions.AttachmentAdded, added.action)
        assertEquals("plano.pdf (2 KB)", added.change("file")!!.new)
        val removed = ActivityLogTesting.rows {
            MantenimientoRepository.markDeleted(db.tenant, orderId, listOf(attachmentId), actor().activity)
        }.single()
        assertEquals(ActivityActions.AttachmentRemoved, removed.action)
        assertEquals("foto.jpg", removed.change("file")!!.old)
    }

    @Test
    fun takenOrderRecordsTheAssignmentToThePerson() {
        val db = Db()
        val taken = ActivityLogTesting.rows { MantenimientoRepository.takeOrder(db.tenant, orderId, actor()) }
        val row = taken.single()
        assertEquals(ActivityActions.Assigned, row.action)
        assertEquals("Ana Ruiz", row.change("assignee")!!.new)
    }

    @Test
    fun settingsChangeIsRecordedWithOldAndNewValue() {
        val db = Db()
        val permissions = ActivityLogTesting.permissions(granted = setOf(MantenimientoModule.Manage), actor = person())
        val row = ActivityLogTesting.rows { MantenimientoService.saveDueSoon(db.tenant, permissions, "5") }.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertEquals("settings", row.entry.entityType)
        assertEquals("1", row.change("dueSoonDays")!!.old)
        assertEquals("5", row.change("dueSoonDays")!!.new)
    }

    @Test
    fun noteAddedThroughAnAgentToolShowsTheAgentInTheRow() {
        val db = Db()
        val tool = MantenimientoMcpTools.mcp.tools.single { it.name == "add_note" }
        val permissions = ActivityLogTesting.permissions(granted = setOf(MantenimientoModule.Manage), actor = person()).throughAgent("Claude")
        val call = McpToolCall(
            JsonObject(mapOf("id" to JsonPrimitive(orderId.toString()), "body" to JsonPrimitive("Revisar el lunes"))),
            permissions, db.tenant, ActivityLogTesting.accessWithoutDatabase(), "Claude", MantenimientoModule.id,
        )
        val row = ActivityLogTesting.rows { tool.handler(call) }.single()
        assertEquals(ActivityActions.Commented, row.action)
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
            "createArrayOf" -> fakeProxy(java.sql.Array::class.java) { _, _ -> null }
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
