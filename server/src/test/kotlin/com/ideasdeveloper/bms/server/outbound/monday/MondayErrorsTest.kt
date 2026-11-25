package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.outbound.OutboundFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.assertFailsWith
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MondayErrorsTest {
    private val now = Instant.parse("2026-03-10T22:00:00Z")
    private fun classify(status: Int, body: String, headers: Map<String, String> = emptyMap()) = MondayErrors.classify(status, headers, body, now)

    @Test
    fun `una respuesta buena no es error`() {
        assertNull(classify(200, """{"data":{"boards":[]}}"""))
    }

    @Test
    fun `429 con Retry-After espera ese tiempo`() {
        val failure = classify(429, "", mapOf("retry-after" to "45"))!!
        assertEquals(MondayErrorKind.Throttled, failure.kind)
        assertEquals(Duration.ofSeconds(45), failure.retryAfter)
    }

    @Test
    fun `429 sin dato espera 60 segundos`() {
        assertEquals(Duration.ofSeconds(60), classify(429, "{}")!!.retryAfter)
    }

    @Test
    fun `el presupuesto de complejidad es Throttled con retry_in_seconds`() {
        val failure = classify(200, """{"errors":[{"message":"x","extensions":{"code":"COMPLEXITY_BUDGET_EXHAUSTED","retry_in_seconds":17}}]}""")!!
        assertEquals(MondayErrorKind.Throttled, failure.kind)
        assertEquals(Duration.ofSeconds(17), failure.retryAfter)
    }

    @Test
    fun `los demas codigos de limite son Throttled`() {
        listOf("ComplexityException", "maxComplexityExceeded", "RATE_LIMIT_EXCEEDED", "IP_RATE_LIMIT_EXCEEDED", "maxConcurrencyExceeded").forEach { code ->
            assertEquals(MondayErrorKind.Throttled, classify(200, """{"errors":[{"message":"x","extensions":{"code":"$code"}}]}""")!!.kind, code)
        }
    }

    @Test
    fun `el limite diario espera hasta la medianoche UTC`() {
        val failure = classify(200, """{"errors":[{"message":"x","extensions":{"code":"DAILY_LIMIT_EXCEEDED"}}]}""")!!
        assertEquals(MondayErrorKind.Throttled, failure.kind)
        assertEquals(Duration.ofHours(2), failure.retryAfter)
    }

    @Test
    fun `401, 403 y UserUnauthorizedException son Auth`() {
        assertEquals(MondayErrorKind.Auth, classify(401, "")!!.kind)
        assertEquals(MondayErrorKind.Auth, classify(403, "")!!.kind)
        assertEquals(MondayErrorKind.Auth, classify(200, """{"errors":[{"message":"x","extensions":{"code":"UserUnauthorizedException"}}]}""")!!.kind)
    }

    @Test
    fun `5xx es Transient`() {
        assertEquals(MondayErrorKind.Transient, classify(502, "<html>")!!.kind)
        assertEquals(MondayErrorKind.Transient, classify(500, "")!!.kind)
    }

    @Test
    fun `ColumnValueException con HTTP 200 es Permanent con el mensaje recortado`() {
        val long = "m".repeat(1000)
        val failure = classify(200, """{"errors":[{"message":"$long","extensions":{"code":"ColumnValueException"}}]}""")!!
        assertEquals(MondayErrorKind.Permanent, failure.kind)
        assertTrue(failure.message!!.length <= 310)
    }

    @Test
    fun `401 y 403 tienen mensajes distintos`() {
        assertEquals("monday rechazo el token.", classify(401, "")!!.message)
        assertTrue(classify(403, "")!!.message!!.contains("permiso"))
    }

    @Test
    fun `un error permanente guarda el codigo y recorta el mensaje a 150`() {
        val failure = classify(200, """{"errors":[{"message":"${"z".repeat(500)}","extensions":{"code":"ColumnValueException"}}]}""")!!
        assertTrue(failure.message!!.contains("(ColumnValueException)"))
        assertTrue(failure.message!!.length <= "monday (ColumnValueException): ".length + 150)
    }

    @Test
    fun `un 5xx con Retry-After usa esa espera`() {
        val failure = classify(503, "", mapOf("retry-after" to "30"))!!
        assertEquals(MondayErrorKind.Transient, failure.kind)
        assertEquals(Duration.ofSeconds(30), failure.retryAfter)
    }

    @Test
    fun `otros 4xx y respuestas sin data son Permanent`() {
        assertEquals(MondayErrorKind.Permanent, classify(400, "{}")!!.kind)
        assertEquals(MondayErrorKind.Permanent, classify(200, "{}")!!.kind)
    }
}

class MondayLocalFileErrorsTest {
    @Test
    fun `un fallo al abrir el archivo local se distingue de uno de red`() {
        val file = OutboundFile("a.txt", "text/plain", 3) { throw IOException("ya no esta") }
        assertFailsWith<LocalFileReadException> { MondayMultipart(emptyMap(), "f", file, boundary = "B").write(ByteArrayOutputStream()) }
    }

    @Test
    fun `un fallo al leer el archivo local se distingue de uno de red`() {
        val broken = object : java.io.InputStream() {
            override fun read(): Int = throw IOException("disco")
        }
        val multipart = MondayMultipart(emptyMap(), "f", OutboundFile("a.txt", "text/plain", 3) { broken }, boundary = "B")
        assertFailsWith<LocalFileReadException> { multipart.write(ByteArrayOutputStream()) }
    }

    @Test
    fun `un fallo al escribir en la red no es un fallo del archivo local`() {
        val sink = object : java.io.OutputStream() {
            override fun write(b: Int) = throw IOException("conexion rota")
        }
        val multipart = MondayMultipart(emptyMap(), "f", OutboundFile("a.txt", "text/plain", 3) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, boundary = "B")
        val failure = assertFailsWith<IOException> { multipart.write(sink) }
        assertTrue(failure !is LocalFileReadException)
    }
}

class MondayMultipartTest {
    @Test
    fun `el cuerpo tiene el Content-Length exacto y las partes query y variables file`() {
        val bytes = "contenido".toByteArray()
        val file = OutboundFile("a\"b.txt", "text/plain", bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        val multipart = MondayMultipart(mapOf("query" to "mutation (\$file: File!) { x }"), "variables[file]", file, boundary = "BND")
        val out = ByteArrayOutputStream().also(multipart::write).toByteArray()
        assertEquals(out.size.toLong(), multipart.contentLength)
        val text = out.decodeToString()
        assertTrue(text.contains("name=\"query\""))
        assertTrue(text.contains("name=\"variables[file]\"; filename=\"a_b.txt\""))
        assertTrue(text.contains("contenido"))
        assertTrue(text.endsWith("--BND--\r\n"))
        assertEquals("multipart/form-data; boundary=BND", multipart.contentType)
    }

    @Test
    fun `el boundary es aleatorio`() {
        val file = OutboundFile("a", "x", 0) { ByteArrayInputStream(ByteArray(0)) }
        assertNotNull(MondayMultipart(emptyMap(), "f", file).boundary)
        assertTrue(MondayMultipart(emptyMap(), "f", file).boundary != MondayMultipart(emptyMap(), "f", file).boundary)
    }
}
