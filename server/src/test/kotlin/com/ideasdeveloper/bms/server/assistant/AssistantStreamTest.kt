package com.ideasdeveloper.bms.server.assistant

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.post
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SSE stream against a real Netty server and a client that stops reading: the turn must end (it is cancelled when
 * the client falls behind) and the stream slot must be given back, instead of a thread waiting forever.
 */
class AssistantStreamTest {
    @Test
    fun everyFrameReachesAClientThatReads() = io.ktor.server.testing.testApplication {
        val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
        val ends = AtomicInteger()
        application {
            routing {
                post("/stream") {
                    call.stream("es", pool, onEnd = { ends.incrementAndGet() }) { sink ->
                        repeat(3) { sink.event("text_delta", buildJsonObject { put("text", "t$it") }) }
                        sink.event("done", buildJsonObject { put("reason", "end_turn") })
                    }
                }
            }
        }
        val body = client.post("/stream").bodyAsText()
        assertTrue(body.contains("event: done") && body.contains("t2"), "body: '$body'")
        val deadline = System.currentTimeMillis() + 10_000
        while (ends.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, ends.get(), "the end of the stream is reported once")
        pool.close()
    }

    @Test
    fun aClientThatStopsReadingEndsTheTurnAndFreesItsSlot() {
        val port = ServerSocket(0).use { it.localPort }
        val ended = CountDownLatch(1)
        val released = AtomicInteger()
        val emitted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        val dispatcher = pool.asCoroutineDispatcher()
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            routing {
                post("/stream") {
                    run {
                        call.stream("es", dispatcher, writeTimeout = 1_000, pingMillis = 500, onEnd = { released.incrementAndGet() }) { sink ->
                            try {
                                val big = "x".repeat(16 * 1024)
                                // A turn producing text as fast as it can, checking the sink as the engine does.
                                while (!sink.cancelled) {
                                    sink.event("text_delta", buildJsonObject { put("text", big) })
                                    emitted.incrementAndGet()
                                }
                            } finally {
                                ended.countDown()
                            }
                        }
                    }
                }
            }
        }.start(wait = false)
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.receiveBufferSize = 4096
                socket.getOutputStream().write("POST /stream HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                // Never read: the server's writes fill the buffers and stall.
                assertTrue(ended.await(60, TimeUnit.SECONDS), "the turn never ended (emitted ${emitted.get()} frames)")
                val deadline = System.currentTimeMillis() + 30_000
                while (released.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertEquals(1, released.get(), "the stream slot was not given back")
            }
        } finally {
            server.stop(100, 1_000)
            dispatcher.close()
        }
    }

    @Test
    fun aClientThatDisconnectsCancelsAWaitingTurn() {
        val port = ServerSocket(0).use { it.localPort }
        val sawCancel = CountDownLatch(1)
        val released = AtomicInteger()
        val started = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val dispatcher = pool.asCoroutineDispatcher()
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            routing {
                post("/stream") {
                    call.stream("es", dispatcher, writeTimeout = 1_000, pingMillis = 100, onEnd = { released.incrementAndGet() }) { sink ->
                        sink.event("status", buildJsonObject { put("state", "thinking") })
                        started.countDown()
                        // Like a blocking model call: it emits nothing and only polls the flag now and then.
                        val limit = System.currentTimeMillis() + 60_000
                        while (System.currentTimeMillis() < limit) {
                            if (sink.cancelled) { sawCancel.countDown(); return@stream }
                            Thread.sleep(50)
                        }
                    }
                }
            }
        }.start(wait = false)
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write("POST /stream HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                // Read the headers, then press Stop (close the socket).
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().startsWith("HTTP/1.1 200"))
                assertTrue(started.await(10, TimeUnit.SECONDS))
            }
            assertTrue(sawCancel.await(8, TimeUnit.SECONDS), "the turn never saw the cancellation of a disconnected client")
            val deadline = System.currentTimeMillis() + 8_000
            while (released.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals(1, released.get(), "the stream slot was not given back")
        } finally {
            server.stop(100, 1_000)
            dispatcher.close()
        }
    }
}
