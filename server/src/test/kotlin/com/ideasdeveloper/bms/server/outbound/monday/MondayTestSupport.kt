package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.modules.CompanyFiles
import com.ideasdeveloper.bms.server.modules.ModuleFiles
import com.ideasdeveloper.bms.server.outbound.OutboundDelivery
import com.ideasdeveloper.bms.server.outbound.OutboundEvent
import com.ideasdeveloper.bms.server.outbound.OutboundFieldDef
import com.ideasdeveloper.bms.server.outbound.OutboundFieldType
import com.ideasdeveloper.bms.server.outbound.OutboundFile
import com.ideasdeveloper.bms.server.outbound.OutboundLink
import com.ideasdeveloper.bms.server.outbound.OutboundLinks
import com.ideasdeveloper.bms.server.outbound.OutboundRecord
import com.ideasdeveloper.bms.server.outbound.OutboundRecordType
import com.ideasdeveloper.bms.server.outbound.OutboundSource
import com.ideasdeveloper.bms.server.outbound.OutboundValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant

const val TestToken = "tok-secreto-123"

/** Una llamada que recibio el cliente simulado. */
class FakeCall(val query: String, val variables: JsonObject, val upload: Boolean, val token: String, val fileName: String? = null)

/** Cliente simulado: primero consume las respuestas en cola (resultados o fallos); sin cola responde segun la consulta. Registra las llamadas. */
class FakeMondayApi : MondayApi {
    val calls = mutableListOf<FakeCall>()
    val queue = ArrayDeque<Any>()
    var users: JsonArray = JsonArray(emptyList())
    var importedItem: JsonObject? = null
    var byColumnItem: String? = null

    private fun answer(query: String, upload: Boolean): JsonObject {
        queue.removeFirstOrNull()?.let { next ->
            if (next is MondayApiException) throw next
            return next as JsonObject
        }
        return when {
            upload -> buildJsonObject { put("add_file", buildJsonObject { put("id", "900") }) }
            "create_item" in query -> buildJsonObject { put("create_item", buildJsonObject { put("id", "555") }) }
            "create_update" in query -> buildJsonObject { put("create_update", buildJsonObject { put("id", "777") }) }
            "users(" in query -> buildJsonObject { put("users", users) }
            "items(ids" in query -> buildJsonObject { put("items", JsonArray(listOfNotNull(importedItem))) }
            "items_page_by_column_values" in query -> buildJsonObject {
                put("items_page_by_column_values", buildJsonObject { put("items", JsonArray(listOfNotNull(byColumnItem?.let { buildJsonObject { put("id", it) } }))) })
            }
            else -> buildJsonObject { put("change_multiple_column_values", buildJsonObject { put("id", "1") }) }
        }
    }

    override fun call(token: String, query: String, variables: JsonObject): JsonObject {
        calls += FakeCall(query, variables, false, token)
        return answer(query, false)
    }

    override fun upload(token: String, query: String, variables: JsonObject, fileVariable: String, file: OutboundFile, maxBytes: Long): JsonObject {
        calls += FakeCall(query, variables, true, token, file.name)
        return answer(query, true)
    }

    fun withQuery(text: String) = calls.filter { text in it.query }
}

class MemoryConfigStore : MondayOutboundConfigStore {
    val rows = linkedMapOf<String, MondayOutboundConfig>()
    override fun get(tenant: Connection, recordType: String) = rows[recordType]
    override fun all(tenant: Connection) = rows.values.toList()
    override fun save(tenant: Connection, config: MondayOutboundConfig) { rows[config.recordType] = config }
    override fun delete(tenant: Connection) = rows.clear()
}

class MemoryLinks : OutboundLinks {
    val map = linkedMapOf<String, OutboundLink>()
    override fun get(tenant: Connection, sinkId: String, recordType: String, recordId: String) = map["$sinkId/$recordType/$recordId"]
    override fun put(tenant: Connection, sinkId: String, recordType: String, recordId: String, link: OutboundLink) { map["$sinkId/$recordType/$recordId"] = link }
}

/** Conexion que falla si alguien la usa: los dobles de prueba nunca tocan la base. */
val NoDatabase: Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
    throw UnsupportedOperationException("Sin base de datos en las pruebas: ${method.name}")
} as Connection

val LeadType = OutboundRecordType(
    "lead", "Leads",
    listOf(
        OutboundFieldDef("email", "Correo", OutboundFieldType.Email),
        OutboundFieldDef("phone", "Telefono", OutboundFieldType.Phone),
        OutboundFieldDef("amount", "Monto", OutboundFieldType.Number),
        OutboundFieldDef("stage", "Etapa", OutboundFieldType.Status),
    ),
)

class TestSource : OutboundSource {
    override val moduleId = "crm"
    var record: OutboundRecord? = null
    var fileSize = 10L
    var fileOpened = false
    val linked = mutableListOf<String>()

    override fun recordTypes(tenant: Connection) = listOf(LeadType)
    override fun record(tenant: Connection, recordType: String, recordId: String) = record
    override fun file(tenant: Connection, files: ModuleFiles, recordType: String, recordId: String, fileId: String): OutboundFile =
        OutboundFile("contrato.pdf", "application/pdf", fileSize) { fileOpened = true; ByteArrayInputStream(ByteArray(fileSize.toInt().coerceAtMost(1024))) }

    override fun linked(tenant: Connection, recordType: String, recordId: String, importKey: String) { linked += importKey }
    override fun importMapping(tenant: Connection, recordType: String, importSource: String) = mapOf("Correo electronico" to "email", "Etapa" to "stage")
}

class TestSink(val store: MondayOutboundConfigStore, api: MondayApi, var tokenValue: String? = TestToken) :
    MondayOutboundSink("crm_monday", "crm", store, api, { Instant.parse("2026-01-01T00:00:00Z") }) {
    override fun token(tenant: Connection) = tokenValue
}

val TestConfig = MondayOutboundConfig(
    recordType = "lead", enabled = true, boardId = "100", groupId = "topics",
    columns = mapOf("email" to MondayColumnRef("email_1", "email"), "amount" to MondayColumnRef("num", "numbers"), "stage" to MondayColumnRef("st", "status")),
)

fun leadRecord(importKey: String? = null) = OutboundRecord(
    "lead", "L1", "Acme",
    mapOf(
        "email" to OutboundValue.Text("a@acme.com"),
        "phone" to OutboundValue.Text("5512345678"),
        "amount" to OutboundValue.Number(java.math.BigDecimal("42.50")),
        "stage" to OutboundValue.Text("Nuevo"),
    ),
    importKey,
)

private val files: ModuleFiles by lazy { CompanyFiles.local(Files.createTempDirectory("bms-monday-test")).module("c", "crm") }

fun delivery(event: OutboundEvent, source: OutboundSource, links: OutboundLinks, attempt: Int = 1, withFiles: Boolean = true) =
    OutboundDelivery(NoDatabase, 1, event, attempt, source, if (withFiles) files else null, links, Instant.parse("2026-01-01T00:00:00Z"))
