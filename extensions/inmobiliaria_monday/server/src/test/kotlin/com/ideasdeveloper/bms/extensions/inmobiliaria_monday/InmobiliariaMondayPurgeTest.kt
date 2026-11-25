package com.ideasdeveloper.bms.extensions.inmobiliaria_monday

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Purga del staging y corte del listado de tableros (puras, sin red ni base de datos). */
class InmobiliariaMondayPurgeTest {
    @Test
    fun purgeDeletesOnlyDoneOrFailedJobsOlderThan30DaysWithALimit() {
        val sql = mutableListOf<String>()
        val ints = mutableListOf<Int>()
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, args ->
            when (method.name) {
                "setInt" -> { ints += args!![1] as Int; null }
                "executeUpdate" -> 4
                else -> null
            }
        } as PreparedStatement
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement") { sql += args!![0] as String; statement } else null
        } as Connection
        assertEquals(4, InmobiliariaMondayStore.purgeOld(connection))
        val text = sql.single()
        assertTrue("state IN ('done', 'failed')" in text && "LIMIT ?" in text)
        assertTrue("ready" !in text && "reading" !in text && "applying" !in text)
        assertEquals(listOf(30, 5), ints)
    }

    @Test
    fun boardListingStopsWhenTheTotalBudgetIsSpentButAlwaysReadsFirstPage() {
        val now = 1_000_000_000L
        assertFalse(InmobiliariaMondayClient.boardsBudgetExpired(1, now - 1, now))
        assertFalse(InmobiliariaMondayClient.boardsBudgetExpired(2, now + 1, now))
        assertTrue(InmobiliariaMondayClient.boardsBudgetExpired(2, now, now))
    }

    @Test
    fun pageShrinksFrom100DownToTenAndThenGivesUp() {
        assertEquals(listOf(100, 50, 25, 12, 10), generateSequence(MondayMapping.MaxPageSize) { MondayMapping.nextPageSize(it) }.toList())
        assertEquals(null, MondayMapping.nextPageSize(MondayMapping.MinPageSize))
    }

    @Test
    fun complexityIsTooBigAndMoreWordsAreTransient() {
        val tooBig = kotlin.test.assertFailsWith<InmobiliariaMondayTooBigException> {
            InmobiliariaMondayClient.parseResponse(200, """{"errors":[{"message":"Query has complexity of 6000000"}]}""")
        }
        assertTrue("complexity" in tooBig.message.orEmpty())
        listOf("Concurrency limit", "Temporarily unavailable", "Request timeout", "INTERNAL_SERVER_ERROR", "Internal server error").forEach {
            assertTrue(InmobiliariaMondayClient.isTransientMessage(it), it)
        }
    }

    @Test
    fun readRetriesWaitLongerAfterEachFailure() {
        val at = java.time.Instant.parse("2026-01-01T00:00:00Z")
        assertTrue(MondayMapping.retryDue(0, at, at))
        assertFalse(MondayMapping.retryDue(1, at, at.plusSeconds(60)))
        assertTrue(MondayMapping.retryDue(1, at, at.plusSeconds(120)))
        assertFalse(MondayMapping.retryDue(4, at, at.plusSeconds(15 * 60)))
        assertTrue(MondayMapping.retryDue(4, at, at.plusSeconds(16 * 60)))
    }
}
