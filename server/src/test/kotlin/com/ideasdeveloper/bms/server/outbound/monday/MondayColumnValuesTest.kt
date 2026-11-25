package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.outbound.OutboundFieldType
import com.ideasdeveloper.bms.server.outbound.OutboundPerson
import com.ideasdeveloper.bms.server.outbound.OutboundValue
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MondayColumnValuesTest {
    private fun value(type: String, v: OutboundValue, people: List<Long> = emptyList(), country: String? = "MX") =
        MondayColumnValues.value(type, v, { people }, country)

    private fun json(type: String, v: OutboundValue, people: List<Long> = emptyList(), country: String? = "MX") = value(type, v, people, country).json.toString()

    @Test
    fun `status y dropdown`() {
        assertEquals("""{"label":"Nuevo"}""", json("status", OutboundValue.Text("Nuevo")))
        assertEquals("""{"labels":["A","B"]}""", json("dropdown", OutboundValue.Options(listOf("A", "B"))))
    }

    @Test
    fun `fecha y fecha con hora en UTC`() {
        assertEquals("""{"date":"2026-02-03"}""", json("date", OutboundValue.Date(LocalDate.of(2026, 2, 3))))
        assertEquals("""{"date":"2026-02-03","time":"14:05:00"}""", json("date", OutboundValue.DateTime(Instant.parse("2026-02-03T14:05:00Z"))))
    }

    @Test
    fun `personas por id de usuario`() {
        val result = value("people", OutboundValue.Persons(listOf(OutboundPerson(name = "Ana", email = "a@x.com"))), listOf(9))
        assertEquals("""{"personsAndTeams":[{"id":9,"kind":"person"}]}""", result.json.toString())
        val none = value("people", OutboundValue.Persons(listOf(OutboundPerson(name = "Ana"))))
        assertNull(none.json)
        assertTrue(none.warning!!.isNotEmpty())
    }

    @Test
    fun `telefono con pais, correo, numero y enlace`() {
        assertEquals("""{"phone":"+5255123456","countryShortName":"MX"}""", json("phone", OutboundValue.Text("+52 55 123 456")))
        assertNull(value("phone", OutboundValue.Text("5512345678"), country = null).json)
        assertEquals("""{"email":"a@b.com","text":"a@b.com"}""", json("email", OutboundValue.Text("a@b.com")))
        assertNull(value("email", OutboundValue.Text("no-es-correo")).json)
        assertEquals("\"42.5\"", json("numbers", OutboundValue.Number(BigDecimal("42.50"))))
        assertEquals("""{"url":"https://a.com","text":"https://a.com"}""", json("link", OutboundValue.Text("https://a.com")))
        assertNull(value("link", OutboundValue.Text("javascript:x")).json)
    }

    @Test
    fun `rango, texto, texto largo y checkbox`() {
        assertEquals("""{"from":"2026-01-01","to":"2026-01-05"}""", json("timeline", OutboundValue.Range(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5))))
        assertEquals("""{"from":"2026-01-01","to":"2026-01-01"}""", json("timeline", OutboundValue.Range(LocalDate.of(2026, 1, 1), null)))
        assertEquals("\"hola\"", json("text", OutboundValue.Text("hola")))
        val long = value("long_text", OutboundValue.Text("x".repeat(3000))).json.toString()
        assertEquals(2000 + """{"text":""}""".length, long.length)
        assertEquals("texto recortado a 2000 caracteres", value("long_text", OutboundValue.Text("x".repeat(3000))).warning)
        assertNull(value("long_text", OutboundValue.Text("corto")).warning)
        assertEquals("""{"checked":"true"}""", json("checkbox", OutboundValue.Bool(true)))
        assertEquals("\"Acme\"", json("name", OutboundValue.Text("Acme")))
    }

    @Test
    fun `un tipo incompatible se omite con aviso`() {
        val result = value("checkbox", OutboundValue.Text("x"))
        assertNull(result.json)
        assertEquals("no se puede escribir texto en una columna checkbox", result.warning)
        assertEquals("no se puede escribir numero en una columna date", value("date", OutboundValue.Number(BigDecimal.ONE)).warning)
        assertNull(value("rating", OutboundValue.Text("x")).json)
    }

    @Test
    fun `columnas compatibles por tipo de campo`() {
        assertTrue("email" in MondayColumnValues.compatible(OutboundFieldType.Email))
        assertTrue("people" in MondayColumnValues.compatible(OutboundFieldType.Person))
        assertEquals(setOf("timeline"), MondayColumnValues.compatible(OutboundFieldType.Timeline))
        OutboundFieldType.entries.forEach { assertTrue(MondayColumnValues.compatible(it).isNotEmpty(), it.name) }
    }
}
