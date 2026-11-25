package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.outbound.OutboundEvent
import com.ideasdeveloper.bms.server.outbound.OutboundLink
import com.ideasdeveloper.bms.server.outbound.OutboundOrigin
import com.ideasdeveloper.bms.server.outbound.OutboundPerson
import com.ideasdeveloper.bms.server.outbound.OutboundResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.ideasdeveloper.bms.server.outbound.OutboundLinks
import kotlinx.serialization.json.JsonNull
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MondayOutboundSinkTest {
    private val api = FakeMondayApi()
    private val store = MemoryConfigStore().also { it.rows["lead"] = TestConfig }
    private val links = MemoryLinks()
    private val source = TestSource().also { it.record = leadRecord() }
    private val sink = TestSink(store, api)

    private fun values(call: FakeCall): JsonObject = Json.parseToJsonElement(call.variables["values"].asStr()) as JsonObject

    private fun deliver(event: OutboundEvent, attempt: Int = 1, withFiles: Boolean = true) = sink.deliver(delivery(event, source, links, attempt, withFiles))

    private fun created() = OutboundEvent.created("crm", "lead", "L1")

    private fun texts(result: OutboundResult) = when (result) {
        is OutboundResult.Sent -> result.detail + result.warnings.joinToString()
        is OutboundResult.Skipped -> result.reason
        is OutboundResult.Retry -> result.reason
        is OutboundResult.Throttled -> result.reason
        is OutboundResult.Failed -> result.reason
    }

    @Test
    fun `un alta crea el elemento, guarda el enlace y avisa al modulo`() {
        val result = deliver(created())
        val sent = result as OutboundResult.Sent
        val call = api.withQuery("create_item").single()
        assertEquals("100", call.variables["board"].asStr())
        assertEquals("Acme", call.variables["name"].asStr())
        assertEquals(setOf("email_1", "num", "st"), values(call).keys)
        assertEquals("555", links.get(NoDatabase, "crm_monday", "lead", "L1")?.externalId)
        assertEquals(listOf("monday:555"), source.linked)
        assertTrue(sent.warnings.any { it.contains("Telefono") }, "el campo sin columna se omite con aviso")
    }

    @Test
    fun `un registro importado de monday actualiza ese elemento y no crea otro`() {
        source.record = leadRecord(importKey = "monday:123")
        api.importedItem = buildJsonObject { put("id", "123"); put("board", buildJsonObject { put("id", "100") }) }
        val result = deliver(OutboundEvent.updated("crm", "lead", "L1", listOf("amount")))
        assertTrue(result is OutboundResult.Sent)
        assertTrue(api.withQuery("create_item").isEmpty())
        val change = api.withQuery("change_multiple_column_values").single()
        assertEquals("123", change.variables["item"].asStr())
        assertEquals(setOf("num"), values(change).keys)
        assertEquals("123", links.get(NoDatabase, "crm_monday", "lead", "L1")?.externalId)
    }

    @Test
    fun `un cambio solo envia las columnas de los campos cambiados`() {
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        deliver(OutboundEvent.updated("crm", "lead", "L1", listOf("stage")))
        assertEquals(setOf("st"), values(api.withQuery("change_multiple_column_values").single()).keys)
    }

    @Test
    fun `un cambio sin elemento en monday lo crea`() {
        deliver(OutboundEvent.updated("crm", "lead", "L1", listOf("stage")))
        assertEquals(1, api.withQuery("create_item").size)
    }

    @Test
    fun `tras un corte la columna clave evita duplicar el elemento`() {
        store.rows["lead"] = TestConfig.copy(keyColumn = "clave")
        api.byColumnItem = "888"
        val result = deliver(created(), attempt = 2)
        assertTrue(result is OutboundResult.Sent)
        assertTrue(api.withQuery("create_item").isEmpty())
        assertEquals("888", links.get(NoDatabase, "crm_monday", "lead", "L1")?.externalId)
    }

    @Test
    fun `la columna clave se escribe al crear`() {
        store.rows["lead"] = TestConfig.copy(keyColumn = "clave")
        deliver(created())
        assertEquals("L1", values(api.withQuery("create_item").single())["clave"].asStr())
    }

    @Test
    fun `una nota resuelve menciones por correo y deja como texto la que no encuentra`() {
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        api.users = JsonArray(listOf(buildJsonObject { put("id", "9"); put("email", "ana@x.com") }))
        val note = OutboundEvent.note(
            "crm", "lead", "L1", "n1", "Hola @Ana y @Beto\nsegunda linea", OutboundPerson(name = "Luis"),
            listOf(OutboundPerson(name = "Ana", email = "ana@x.com"), OutboundPerson(name = "Beto", email = "beto@x.com")),
        )
        val sent = deliver(note) as OutboundResult.Sent
        val call = api.withQuery("create_update").single()
        val mentions = call.variables["mentions"] as JsonArray
        assertEquals(1, mentions.size)
        assertEquals("9", (mentions.first() as JsonObject)["id"].asStr())
        assertEquals("User", (mentions.first() as JsonObject)["type"].asStr())
        val body = call.variables["body"].asStr()
        assertTrue(body.startsWith("<b>Luis</b>:<br>"))
        assertTrue(body.contains("<br>segunda linea"))
        assertTrue(body.contains("@Beto"))
        assertTrue(sent.warnings.any { it.contains("Beto") })
    }

    @Test
    fun `el cuerpo de la nota escapa el HTML`() {
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        deliver(OutboundEvent.note("crm", "lead", "L1", "n1", "<script>alert(1)</script>", null))
        assertFalse(api.withQuery("create_update").single().variables["body"].asStr().contains("<script>"))
    }

    @Test
    fun `un archivo demasiado grande falla sin abrirlo`() {
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        val result = deliver(OutboundEvent.file("crm", "lead", "L1", "f1", "contrato.pdf", 30L * 1024 * 1024))
        assertEquals(OutboundResult.Failed("El archivo supera 25 MB"), result)
        assertFalse(source.fileOpened)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `un archivo grande segun el almacen tambien falla sin abrirlo`() {
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        source.fileSize = 30L * 1024 * 1024
        val result = deliver(OutboundEvent.file("crm", "lead", "L1", "f1", "contrato.pdf", null))
        assertTrue(result is OutboundResult.Failed && result.reason.contains("supera 25 MB"))
        assertFalse(source.fileOpened)
    }

    @Test
    fun `un archivo va a la columna de archivos si hay`() {
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        store.rows["lead"] = TestConfig.copy(filesColumn = "files1")
        val result = deliver(OutboundEvent.file("crm", "lead", "L1", "f1", "contrato.pdf", 10))
        assertTrue(result is OutboundResult.Sent)
        val upload = api.calls.single { it.upload }
        assertTrue(upload.query.contains("add_file_to_column(item_id: 555, column_id: \"files1\""))
        assertEquals("contrato.pdf", upload.fileName)
    }

    @Test
    fun `sin columna de archivos va como actualizacion con el archivo adjunto`() {
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        deliver(OutboundEvent.file("crm", "lead", "L1", "f1", "contrato.pdf", 10))
        assertEquals("Archivo: contrato.pdf", api.withQuery("create_update").single().variables["body"].asStr())
        assertTrue(api.calls.single { it.upload }.query.contains("add_file_to_update(update_id: 777"))
    }

    @Test
    fun `un archivo sin almacen disponible se reintenta`() {
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        val result = deliver(OutboundEvent.file("crm", "lead", "L1", "f1", "contrato.pdf", 10), withFiles = false)
        assertTrue(result is OutboundResult.Retry)
    }

    @Test
    fun `un 429 con espera es Throttled con esa espera`() {
        api.queue += MondayApiException(MondayErrorKind.Throttled, "monday pidio esperar", Duration.ofSeconds(120))
        val result = deliver(created())
        assertEquals(Duration.ofSeconds(120), (result as OutboundResult.Throttled).after)
    }

    @Test
    fun `un fallo pasajero es Retry`() {
        api.queue += MondayApiException(MondayErrorKind.Transient, "monday respondio HTTP 502.")
        assertTrue(deliver(created()) is OutboundResult.Retry)
    }

    @Test
    fun `un token rechazado es Failed`() {
        api.queue += MondayApiException(MondayErrorKind.Auth, "monday rechazo el token")
        assertEquals(OutboundResult.Failed("monday rechazo el token"), deliver(created()))
    }

    @Test
    fun `un error de columna con HTTP 200 es Failed con el mensaje de monday`() {
        val failure = MondayErrors.classify(200, emptyMap(), """{"errors":[{"message":"Column value is invalid","extensions":{"code":"ColumnValueException"}}],"data":null}""")!!
        api.queue += failure
        val result = deliver(created())
        assertTrue(result is OutboundResult.Failed && result.reason.contains("Column value is invalid"))
    }

    @Test
    fun `sin token falla y sin configuracion se omite`() {
        sink.tokenValue = null
        assertEquals(OutboundResult.Failed("Falta el token de monday"), deliver(created()))
        sink.tokenValue = TestToken
        store.rows.clear()
        assertTrue(deliver(created()) is OutboundResult.Skipped)
    }

    @Test
    fun `wants rechaza lo que viene de monday y respeta los interruptores`() {
        fun wants(event: OutboundEvent) = sink.wants(NoDatabase, event)
        assertTrue(wants(created()))
        assertFalse(wants(OutboundEvent.created("crm", "lead", "L1", OutboundOrigin.import("monday"))))
        assertFalse(wants(OutboundEvent.created("crm", "lead", "L1", OutboundOrigin.import("file"))))
        store.rows["lead"] = TestConfig.copy(sendImports = true, sendNotes = false)
        assertTrue(wants(OutboundEvent.created("crm", "lead", "L1", OutboundOrigin.import("file"))))
        assertFalse(wants(OutboundEvent.created("crm", "lead", "L1", OutboundOrigin.import("monday"))))
        assertFalse(wants(OutboundEvent.note("crm", "lead", "L1", "n", "hola", null)))
        store.rows["lead"] = TestConfig.copy(enabled = false)
        assertFalse(wants(created()))
        store.rows["lead"] = TestConfig.copy(boardId = null)
        assertFalse(wants(created()))
        assertFalse(wants(OutboundEvent.created("crm", "otro", "L1")))
    }

    // ---- Revisiones ----

    @Test
    fun `con columna clave se busca antes de crear, tambien en el primer intento`() {
        store.rows["lead"] = TestConfig.copy(keyColumn = "clave")
        api.byColumnItem = "888"
        assertTrue(deliver(created(), attempt = 1) is OutboundResult.Sent)
        assertTrue(api.withQuery("create_item").isEmpty())
    }

    private class FlakyLinks(val inner: MemoryLinks, var failures: Int) : OutboundLinks {
        override fun get(tenant: java.sql.Connection, sinkId: String, recordType: String, recordId: String) = inner.get(tenant, sinkId, recordType, recordId)
        override fun put(tenant: java.sql.Connection, sinkId: String, recordType: String, recordId: String, link: OutboundLink) {
            if (failures > 0) {
                failures--
                throw IllegalStateException("base caida")
            }
            inner.put(tenant, sinkId, recordType, recordId, link)
        }
    }

    @Test
    fun `si guardar el enlace falla una vez se reintenta y todo sigue bien`() {
        val result = sink.deliver(delivery(created(), source, FlakyLinks(links, 1)))
        assertTrue(result is OutboundResult.Sent)
        assertEquals("555", links.get(NoDatabase, "crm_monday", "lead", "L1")?.externalId)
    }

    @Test
    fun `si el enlace no se puede guardar el aviso se reintenta con el id del elemento creado`() {
        val result = sink.deliver(delivery(created(), source, FlakyLinks(links, 5)))
        assertEquals(OutboundResult.Retry("Se creo el elemento 555 en monday pero no se guardo el enlace"), result)
    }

    @Test
    fun `un campo vaciado se borra en monday con null`() {
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        source.record = leadRecord().let { it.copy(values = it.values - "amount") }
        deliver(OutboundEvent.updated("crm", "lead", "L1", listOf("amount", "stage")))
        val sent = values(api.withQuery("change_multiple_column_values").single())
        assertEquals(JsonNull, sent["num"])
        assertEquals("Nuevo", (sent["st"] as JsonObject)["label"].asStr())
    }

    @Test
    fun `un cambio sin columnas que actualizar avisa de que claves no tienen columna`() {
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        val sent = deliver(OutboundEvent.updated("crm", "lead", "L1", listOf("phone"))) as OutboundResult.Sent
        assertTrue(api.withQuery("change_multiple_column_values").isEmpty())
        assertTrue(sent.warnings.any { it.contains("Sin columnas que actualizar") && it.contains("phone") })
    }

    @Test
    fun `una nota cuya actualizacion no devuelve id falla`() {
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        api.queue += JsonObject(emptyMap())
        assertTrue(deliver(OutboundEvent.note("crm", "lead", "L1", "n", "hola", null)) is OutboundResult.Failed)
    }

    @Test
    fun `un usuario no encontrado se recuerda y no se vuelve a buscar`() {
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        val note = OutboundEvent.note("crm", "lead", "L1", "n", "hola @Beto", null, listOf(OutboundPerson(name = "Beto", email = "beto@x.com")))
        deliver(note)
        deliver(note)
        assertEquals(1, api.withQuery("users(").size)
    }

    @Test
    fun `la cache de usuarios esta acotada`() {
        val small = object : MondayOutboundSink("crm_monday", "crm", store, api, { java.time.Instant.parse("2026-01-01T00:00:00Z") }, maxCachedUsers = 3) {
            override fun token(tenant: java.sql.Connection) = TestToken
        }
        links.put(NoDatabase, "crm_monday", "lead", "L1", OutboundLink("555", "100"))
        val people = (1..5).map { OutboundPerson(name = "P$it", email = "p$it@x.com") }
        small.deliver(delivery(OutboundEvent.note("crm", "lead", "L1", "n", "hola", null, people), source, links))
        assertTrue(small.cachedUserCount() <= 3)
    }

    @Test
    fun `un 403 conserva su mensaje propio`() {
        api.queue += MondayErrors.classify(403, emptyMap(), "{}")!!
        val result = deliver(created())
        assertTrue(result is OutboundResult.Failed && result.reason.contains("permiso"))
    }

    @Test
    fun `el token nunca aparece en los mensajes ni en las variables`() {
        api.queue += MondayApiException(MondayErrorKind.Permanent, "monday: error con $TestToken dentro")
        val result = deliver(created())
        assertFalse(texts(result).contains(TestToken))
        deliver(created())
        assertTrue(api.calls.none { call -> call.variables.toString().contains(TestToken) || call.query.contains(TestToken) })
        assertNotNull(api.calls.firstOrNull())
        assertNull(links.get(NoDatabase, "crm_monday", "lead", "L2"))
    }
}
