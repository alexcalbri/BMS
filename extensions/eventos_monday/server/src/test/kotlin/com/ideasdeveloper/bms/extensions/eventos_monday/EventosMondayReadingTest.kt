package com.ideasdeveloper.bms.extensions.eventos_monday

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pruebas puras de la lectura adaptativa, el corte del listado de tableros y la purga (sin red ni base de datos). */
class EventosMondayReadingTest {
    @Test
    fun classifiesComplexityCursorRateLimitAndPermanent() {
        assertEquals(MondayErrorKind.Complexity, EventosMondayReading.classify(200, "COMPLEXITY_BUDGET_EXHAUSTED", "Query too complex"))
        assertEquals(MondayErrorKind.CursorExpired, EventosMondayReading.classify(200, "", "Cursor expired"))
        assertEquals(MondayErrorKind.Transient, EventosMondayReading.classify(429, "", ""))
        assertEquals(MondayErrorKind.Transient, EventosMondayReading.classify(502, "", ""))
        assertEquals(MondayErrorKind.Transient, EventosMondayReading.classify(200, "", "Rate limit exceeded"))
        assertEquals(MondayErrorKind.Permanent, EventosMondayReading.classify(200, "", "Column not found"))
    }

    @Test
    fun pageSizeHalvesDownToTheMinimum() {
        assertEquals(12, EventosMondayReading.nextPageSize(25))
        assertEquals(10, EventosMondayReading.nextPageSize(12))
        assertEquals(10, EventosMondayReading.nextPageSize(10))
    }

    @Test
    fun complexityAndTooLargeShrinkThePageWithoutCountingAnAttempt() {
        assertEquals(EventosMondayReading.Step.Retry(0, 12), EventosMondayReading.recover(MondayErrorKind.Complexity, "x", 0, 25, 0))
        assertEquals(EventosMondayReading.Step.Retry(2, 10), EventosMondayReading.recover(MondayErrorKind.TooLarge, "x", 2, 12, 0))
    }

    @Test
    fun atTheMinimumPageOrWithoutAPageToShrinkTheErrorIsClear() {
        assertEquals(EventosMondayReading.Step.Fail(EventosMondayReading.TooHeavyMessage), EventosMondayReading.recover(MondayErrorKind.Complexity, "x", 0, EventosMondayReading.MinPageSize, 0))
        assertEquals(EventosMondayReading.Step.Fail(EventosMondayReading.TooHeavyMessage), EventosMondayReading.recover(MondayErrorKind.TooLarge, "x", 0, 10, 0))
        val noPage = EventosMondayReading.recover(MondayErrorKind.Complexity, "x", 0, 25, 0, canShrink = false)
        assertTrue(noPage is EventosMondayReading.Step.Fail && "no se puede reducir" in noPage.message)
    }

    @Test
    fun moreTransientWordsAndGrowingWait() {
        listOf("Concurrency limit reached", "Temporarily unavailable", "Request timeout", "INTERNAL_SERVER_ERROR", "Internal server error").forEach {
            assertEquals(MondayErrorKind.Transient, EventosMondayReading.classify(200, "", it), it)
        }
        assertEquals(listOf(0L, 2L, 4L, 8L, 16L, 30L), (0..5).map { EventosMondayReading.backoffMinutes(it) })
        val at = java.time.Instant.parse("2026-01-01T00:00:00Z")
        assertTrue(EventosMondayReading.retryDue(0, at, at))
        assertFalse(EventosMondayReading.retryDue(2, at, at.plusSeconds(3 * 60)))
        assertTrue(EventosMondayReading.retryDue(2, at, at.plusSeconds(4 * 60)))
        assertFalse(EventosMondayReading.retryDue(9, at, at.plusSeconds(29 * 60)))
        assertTrue(EventosMondayReading.retryDue(9, at, at.plusSeconds(30 * 60)))
    }

    @Test
    fun transientKeepsPageSizeAndPermanentFails() {
        assertEquals(EventosMondayReading.Step.Retry(3, 25), EventosMondayReading.recover(MondayErrorKind.Transient, "x", 2, 25, 0))
        assertTrue(EventosMondayReading.recover(MondayErrorKind.Permanent, "x".repeat(500), 0, 25, 0).let { it is EventosMondayReading.Step.Fail && it.message.length == EventosMondayReading.MaxMessage })
    }

    @Test
    fun expiredCursorRestartsOnceThenFails() {
        assertEquals(EventosMondayReading.Step.Restart, EventosMondayReading.recover(MondayErrorKind.CursorExpired, "x", 0, 25, 0))
        assertTrue(EventosMondayReading.recover(MondayErrorKind.CursorExpired, "x", 0, 25, 1) is EventosMondayReading.Step.Fail)
    }

    @Test
    fun boardListingStopsWhenTheTotalBudgetIsSpentButAlwaysReadsFirstPage() {
        val now = 1_000_000_000L
        assertFalse(EventosMondayClient.boardsBudgetExpired(1, now - 1, now))
        assertFalse(EventosMondayClient.boardsBudgetExpired(2, now + 1, now))
        assertTrue(EventosMondayClient.boardsBudgetExpired(2, now, now))
        assertTrue(EventosMondayClient.boardsBudgetExpired(7, now - 5, now))
    }

    @Test
    fun exceptionKindDefaultsFromTransientFlag() {
        assertEquals(MondayErrorKind.Transient, EventosMondayException("x", transient = true).kind)
        assertEquals(MondayErrorKind.Permanent, EventosMondayException("x").kind)
        assertEquals(MondayErrorKind.TooLarge, EventosMondayException("x", false, MondayErrorKind.TooLarge).kind)
    }


    @Test
    fun purgeDeletesOnlyFinishedJobsOlderThan30DaysWithALimit() {
        val sql = mutableListOf<String>()
        val ints = mutableListOf<Int>()
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, args ->
            when (method.name) {
                "setInt" -> { ints += args!![1] as Int; null }
                "executeUpdate" -> 3
                "close" -> null
                else -> null
            }
        } as PreparedStatement
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement") { sql += args!![0] as String; statement } else null
        } as Connection
        assertEquals(3, EventosMondayJobs.purgeOld(connection))
        val text = sql.single()
        assertTrue("status IN ('done', 'failed', 'cancelled')" in text)
        assertTrue("LIMIT ?" in text)
        assertTrue("preview" !in text && "reading" !in text && "applying" !in text)
        assertEquals(listOf(30, 5), ints)
    }
}
