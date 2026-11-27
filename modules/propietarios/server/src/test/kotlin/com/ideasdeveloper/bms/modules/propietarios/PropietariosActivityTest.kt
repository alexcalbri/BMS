package com.ideasdeveloper.bms.modules.propietarios

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.ui.UiFileRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cada cambio de propietarios (desarrollos, lotes, adjuntos y, por agente, el alta de un lote) deja su fila en el registro de
 * actividad, con quien lo hizo. Corre sobre una conexion falsa que responde por texto de SQL; las filas se capturan.
 */
class PropietariosActivityTest {
    private val developmentId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d001")
    private val lotId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000e001")
    private val attachmentId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000f001")
    private val ana: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val today: LocalDate = LocalDate.of(2026, 11, 27)

    private fun developmentRow(name: String = "Valle") = linkedMapOf<String, Any?>(
        "id" to developmentId, "name" to name, "location" to "Norte", "map_url" to "", "latitude" to null, "longitude" to null,
        "notes" to "", "archived_at" to null,
    )

    private fun lotRow(status: String = "disponible", area: String = "100") = linkedMapOf<String, Any?>(
        "id" to lotId, "development_id" to developmentId, "development_name" to "Valle", "lot_number" to "L-12",
        "register_number" to "R-1", "property_name" to "Casa", "property_type" to "Casa", "status" to status,
        "area_m2" to BigDecimal(area), "latitude" to null, "longitude" to null, "map_url" to "", "notes" to "", "archived_at" to null,
    )

    private fun attachmentRow() = linkedMapOf<String, Any?>(
        "id" to attachmentId, "lot_id" to lotId, "name" to "plano.pdf", "path" to "", "content_type" to "application/pdf",
        "size" to 2048L, "uploaded_by" to ana, "created_at" to null,
    )

    /** Filas que responde la base falsa; [updates] cuenta las escrituras. */
    private inner class Db(private val development: Map<String, Any?>? = developmentRow(), private val lot: Map<String, Any?>? = lotRow()) {
        val tenant: Connection = fakeConnection(answer = ::answer, update = { 1 })

        private fun answer(sql: String): List<Map<String, Any?>> = when {
            sql.contains("FROM propietarios_developments d WHERE d.id = ?") -> listOfNotNull(development)
            sql.contains("SELECT id FROM propietarios_lots WHERE development_id") -> emptyList()
            sql.contains("SELECT status FROM propietarios_lots WHERE id") -> listOf(mapOf("status" to "disponible"))
            sql.contains("AS found") -> listOf(mapOf("found" to 1))
            sql.contains("count(*) AS total") -> listOf(mapOf("total" to 0))
            sql.contains("UPDATE propietarios_attachments SET lot_id") -> listOf(mapOf("name" to "escritura.pdf", "size" to 2048L))
            sql.contains("FROM propietarios_attachments WHERE lot_id = ? AND NOT delete_pending ORDER BY") -> listOf(attachmentRow())
            sql.contains("WHERE l.id = ?") -> listOfNotNull(lot)
            sql.contains("FOR UPDATE") -> listOf(mapOf("x" to 1))
            else -> emptyList()
        }
    }

    private fun person(): com.ideasdeveloper.bms.server.activity.ActivityActor = ActivityLogTesting.user("Ana Ruiz", ana)

    @Test
    fun createdDevelopmentRecordsItsNameAndLocation() {
        val db = Db()
        val rows = ActivityLogTesting.rows {
            PropietariosService.createDevelopment(db.tenant, person(), DevelopmentInput(name = "Valle Verde", location = "Norte"), ana)
        }
        val row = rows.single()
        assertEquals(ActivityActions.Created, row.action)
        assertEquals("desarrollo", row.entry.entityType)
        assertEquals("Valle Verde", row.change("name")!!.new)
        assertEquals("Norte", row.change("location")!!.new)
        assertNull(row.change("mapUrl"), "un campo vacio no aparece en el registro")
    }

    @Test
    fun editedDevelopmentRecordsOnlyWhatChanged() {
        val db = Db(development = developmentRow(name = "Valle"))
        val rows = ActivityLogTesting.rows {
            PropietariosService.updateDevelopment(db.tenant, person(), developmentId, DevelopmentInput(name = "Valle Verde", location = "Norte"))
        }
        val row = rows.single()
        assertEquals(ActivityActions.Updated, row.action)
        assertEquals("Valle", row.change("name")!!.old)
        assertEquals("Valle Verde", row.change("name")!!.new)
        assertNull(row.change("location"))
    }

    @Test
    fun archivedDevelopmentRemainsInTheLog() {
        val db = Db(development = developmentRow(name = "Valle"))
        val row = ActivityLogTesting.rows { PropietariosService.archiveDevelopment(db.tenant, person(), developmentId) }.single()
        assertEquals(ActivityActions.Archived, row.action)
        assertEquals("Valle", row.entry.entityLabel)
    }

    @Test
    fun createdLotRecordsItsNumberAndDevelopment() {
        val db = Db()
        val rows = ActivityLogTesting.rows {
            PropietariosService.createLot(db.tenant, person(), LotInput(developmentId, "L-12", propertyType = "Casa"), "Ana Ruiz", ana, today)
        }
        val row = rows.single()
        assertEquals(ActivityActions.Created, row.action)
        assertEquals("lote", row.entry.entityType)
        assertEquals("L-12", row.change("lotNumber")!!.new)
        assertEquals("Valle", row.change("development")!!.new)
    }

    @Test
    fun editedLotRecordsTheOldAndNewValuesOfEachChangedField() {
        val db = Db(lot = lotRow(status = "disponible", area = "100"))
        val rows = ActivityLogTesting.rows {
            PropietariosService.updateLot(db.tenant, person(), lotId, LotInput(developmentId, "L-12", "R-1", "Casa", "Casa", LotStatus.Reservado, BigDecimal("120")), "Ana Ruiz", today)
        }
        val row = rows.single()
        assertEquals(ActivityActions.Updated, row.action)
        assertEquals("Disponible", row.change("status")!!.old)
        assertEquals("Reservado", row.change("status")!!.new)
        assertEquals("100", row.change("areaM2")!!.old)
        assertEquals("120", row.change("areaM2")!!.new)
        assertNull(row.change("notes"))
    }

    @Test
    fun addedAttachmentRecordsItsNameAndSizeNeverItsContent() {
        val db = Db()
        val file = UiFileRef(UUID.randomUUID().toString(), "escritura.pdf", "", 2048L)
        val row = ActivityLogTesting.rows {
            PropietariosService.attachFiles(db.tenant, person(), lotId, listOf(file), ana, "Ana Ruiz", today)
        }.single()
        assertEquals(ActivityActions.AttachmentAdded, row.action)
        assertEquals("escritura.pdf (2 KB)", row.change("file")!!.new)
    }

    @Test
    fun removedAttachmentRecordsItsName() {
        val db = Db()
        val row = ActivityLogTesting.rows {
            PropietariosService.removeFiles(db.tenant, person(), lotId, listOf(attachmentId), "Ana Ruiz", today)
        }.single()
        assertEquals(ActivityActions.AttachmentRemoved, row.action)
        assertEquals("plano.pdf", row.change("file")!!.old)
        assertNull(row.change("file")!!.new)
    }

    @Test
    fun lotCreatedThroughAnAgentToolShowsTheAgentInTheRow() {
        val db = Db()
        val tool = PropietariosMcpTools.mcp.tools.single { it.name == "add_lot" }
        val permissions = ActivityLogTesting.permissions(granted = setOf(PropietariosModule.Manage), actor = person()).throughAgent("Claude")
        val call = McpToolCall(
            JsonObject(mapOf("development_id" to JsonPrimitive(developmentId.toString()), "lot_number" to JsonPrimitive("L-14"))),
            permissions, db.tenant, ActivityLogTesting.accessWithoutDatabase(), "Claude", PropietariosModule.id,
        )
        val row = ActivityLogTesting.rows { tool.handler(call) }.single()
        assertEquals(ActivityActions.Created, row.action)
        assertTrue(row.display.startsWith("Agente de "), row.display)
        assertEquals("Agente de Ana Ruiz", row.display)
        assertEquals("L-14", row.change("lotNumber")!!.new)
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
