package com.ideasdeveloper.bms.server.http

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoundedBodyTest {
    private val limit = 16

    /** A body sent in chunks with no Content-Length, as a client using chunked transfer would. */
    private class Chunked(private val chunks: List<ByteArray>) : OutgoingContent.WriteChannelContent() {
        override suspend fun writeTo(channel: ByteWriteChannel) {
            chunks.forEach { channel.writeFully(it, 0, it.size) }
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.routes() = application {
        routing {
            post("/bytes") { call.respondText(call.receiveBoundedBytes(limit)?.size?.toString() ?: "null") }
            post("/text") { call.respondText(call.receiveBoundedText(limit) ?: "null") }
            post("/bytes413") { call.receiveBoundedBytesOr413(limit)?.let { call.respondText("ok ${it.size}") } }
            post("/text413") { call.receiveBoundedTextOr413(limit)?.let { call.respondText("ok $it") } }
        }
    }

    @Test
    fun aBodyAtOrUnderTheLimitIsReturned() = testApplication {
        routes()
        assertEquals("16", client.post("/bytes") { setBody("a".repeat(16)) }.bodyAsText())
        assertEquals("1", client.post("/bytes") { setBody("a") }.bodyAsText())
        assertEquals("ok 16", client.post("/bytes413") { setBody("a".repeat(16)) }.bodyAsText())
        assertEquals("hola", client.post("/text") { setBody("hola") }.bodyAsText())
        assertEquals("ok ñandú", client.post("/text413") { setBody("ñandú") }.bodyAsText())
    }

    @Test
    fun anEmptyBodyIsEmptyNotNull() = testApplication {
        routes()
        assertEquals("0", client.post("/bytes") { setBody(ByteArray(0)) }.bodyAsText())
        assertEquals("", client.post("/text") { setBody("") }.bodyAsText())
        assertEquals("ok 0", client.post("/bytes413") { setBody(ByteArray(0)) }.bodyAsText())
    }

    @Test
    fun aDeclaredBodyOverTheLimitIsRefused() = testApplication {
        routes()
        assertEquals("null", client.post("/bytes") { setBody("a".repeat(17)) }.bodyAsText())
        assertEquals("null", client.post("/text") { setBody("a".repeat(1000)) }.bodyAsText())
        val refused = client.post("/bytes413") { setBody("a".repeat(17)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, refused.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/text413") { setBody("a".repeat(17)) }.status)
    }

    @Test
    fun aChunkedBodyOverTheLimitIsRefusedToo() = testApplication {
        routes()
        val over = Chunked(List(5) { "abcd".toByteArray() }) // 20 bytes, no declared length
        val exact = Chunked(List(4) { "abcd".toByteArray() }) // 16 bytes
        assertEquals("null", client.post("/bytes") { setBody(over) }.bodyAsText())
        assertEquals("16", client.post("/bytes") { setBody(exact) }.bodyAsText())
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/bytes413") { setBody(over) }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/text413") { setBody(over) }.status)
        assertEquals("ok abcdabcdabcdabcd", client.post("/text413") { setBody(exact) }.bodyAsText())
    }

    @Test
    fun formsDecodePlusAsSpaceAndStopAtTheLimit() {
        val form = formOf("a=1&b=two+words&c=%C3%B1&&d&e=x=y", 100)
        assertEquals("1", form["a"])
        assertEquals("two words", form["b"])
        assertEquals("ñ", form["c"])
        assertEquals("", form["d"])
        assertEquals("x=y", form["e"])
        assertEquals(setOf("a", "b"), formOf("a=1&b=2&c=3", 2).names())
        assertEquals(emptySet(), formOf("", 10).names())
        assertNull(formOf("a=1", 10)["zzz"])
        assertEquals("a b", formOf("a+b=1", 10).names().single())
    }
}
