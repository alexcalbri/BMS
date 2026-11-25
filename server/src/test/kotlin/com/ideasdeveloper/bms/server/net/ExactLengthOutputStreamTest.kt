package com.ideasdeveloper.bms.server.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExactLengthOutputStreamTest {
    @Test
    fun `escribir exactamente los bytes declarados funciona`() {
        val sink = ByteArrayOutputStream()
        val out = ExactLengthOutputStream(sink, 5)
        out.write("hol".toByteArray())
        out.write('a'.code)
        out.write('!'.code)
        out.finish()
        assertEquals("hola!", sink.toString(Charsets.UTF_8))
    }

    @Test
    fun `escribir de mas falla`() {
        val out = ExactLengthOutputStream(ByteArrayOutputStream(), 3)
        assertFailsWith<IOException> { out.write("hola".toByteArray()) }
    }

    @Test
    fun `pasado el plazo la escritura falla`() {
        val out = ExactLengthOutputStream(ByteArrayOutputStream(), 10, System.nanoTime() - 1_000_000)
        assertFailsWith<IOException> { out.write("hola".toByteArray()) }
    }

    @Test
    fun `escribir de menos falla al terminar`() {
        val out = ExactLengthOutputStream(ByteArrayOutputStream(), 10)
        out.write("hola".toByteArray())
        assertFailsWith<IOException> { out.finish() }
    }
}
