package com.ideasdeveloper.bms.extensions.membresia_monday

import com.ideasdeveloper.bms.modules.membresia.ImportedMember
import com.ideasdeveloper.bms.modules.membresia.ImportedVisit
import com.ideasdeveloper.bms.modules.membresia.MembresiaImportApply
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Purga del staging y resumen por lotes (puras, sin base de datos). */
class MembresiaMondayPurgeAndSummaryTest {
    @Test
    fun purgeDeletesOnlyFinishedJobsOlderThan30DaysWithALimit() {
        val sql = mutableListOf<String>()
        val ints = mutableListOf<Int>()
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, args ->
            when (method.name) {
                "setInt" -> { ints += args!![1] as Int; null }
                "executeUpdate" -> 2
                else -> null
            }
        } as PreparedStatement
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement") { sql += args!![0] as String; statement } else null
        } as Connection
        assertEquals(2, MembresiaMondayJobs.purgeOld(connection))
        val text = sql.single()
        assertTrue("status IN ('done', 'failed', 'cancelled')" in text && "LIMIT ?" in text)
        assertTrue("preview" !in text && "reading" !in text && "applying" !in text)
        assertEquals(listOf(30, 5), ints)
    }

    @Test
    fun slimRowsGiveTheSamePlansAndProblemsAsFullRows() {
        fun member(i: Int, plan: String, price: String?) = ImportedMember(
            key = "k$i", name = if (i % 7 == 0) "" else "Socio $i", planName = plan,
            startsOn = LocalDate.of(2025, 1, 1), endsOn = LocalDate.of(2025, 1, 1).plusDays(29L + i % 3),
            amount = price?.let(::BigDecimal), guestPasses = i % 2,
            visits = listOf(ImportedVisit("v$i", LocalDate.of(2025, 1, 5))), customerFields = mapOf("Pais" to "CR"), notes = "nota",
        )
        val full = (1..120).map { member(it, if (it % 2 == 0) "Oro" else "Plata", "50.00") }
        val slim = full.map { MembresiaMondayJobs.slimOf(it) }
        val a = MembresiaImportApply.summary(full, emptyList())
        val b = MembresiaImportApply.summary(slim, emptyList())
        assertEquals(a.members, b.members)
        assertEquals(a.newPlans, b.newPlans)
        assertEquals(a.problems, b.problems)
        assertEquals(MondayReading.problemCodes(full), MondayReading.problemCodes(slim))
        // Lo que el resumen slim no ve (visitas y campos) se acumula por lote, sin perder nada.
        val batches = full.chunked(MembresiaMondayJobs.SummaryBatch / 10)
        assertEquals(a.visits, batches.sumOf { MembresiaImportApply.summary(it, emptyList()).visits })
        assertEquals(a.customerFields, batches.flatMap { MembresiaImportApply.summary(it, emptyList()).customerFields }.toSortedSet().toList())
    }
}
