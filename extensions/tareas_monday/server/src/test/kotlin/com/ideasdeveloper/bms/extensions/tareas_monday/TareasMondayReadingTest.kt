package com.ideasdeveloper.bms.extensions.tareas_monday

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pruebas puras de la lectura adaptativa, el corte del listado de tableros y la purga (sin red ni base de datos). */
class TareasMondayReadingTest {
    @Test
    fun classifiesComplexityCursorRateLimitAndPermanent() {
        assertEquals(MondayErrorKind.Complexity, TareasMondayReading.classify(200, "COMPLEXITY_BUDGET_EXHAUSTED", "Query too complex"))
        assertEquals(MondayErrorKind.CursorExpired, TareasMondayReading.classify(200, "", "Cursor expired"))
        assertEquals(MondayErrorKind.Transient, TareasMondayReading.classify(429, "", ""))
        assertEquals(MondayErrorKind.Transient, TareasMondayReading.classify(502, "", ""))
        assertEquals(MondayErrorKind.Transient, TareasMondayReading.classify(200, "", "Rate limit exceeded"))
        assertEquals(MondayErrorKind.Permanent, TareasMondayReading.classify(200, "", "Column not found"))
    }

    @Test
    fun pageSizeHalvesDownToTheMinimum() {
        assertEquals(12, TareasMondayReading.nextPageSize(25))
        assertEquals(10, TareasMondayReading.nextPageSize(12))
        assertEquals(10, TareasMondayReading.nextPageSize(10))
    }

    @Test
    fun complexityAndTooLargeShrinkThePageWithoutCountingAnAttempt() {
        assertEquals(TareasMondayReading.Step.Retry(0, 12), TareasMondayReading.recover(MondayErrorKind.Complexity, "x", 0, 25, 0))
        assertEquals(TareasMondayReading.Step.Retry(2, 10), TareasMondayReading.recover(MondayErrorKind.TooLarge, "x", 2, 12, 0))
    }

    @Test
    fun atTheMinimumPageOrWithoutAPageToShrinkTheErrorIsClear() {
        assertEquals(TareasMondayReading.Step.Fail(TareasMondayReading.TooHeavyMessage), TareasMondayReading.recover(MondayErrorKind.Complexity, "x", 0, TareasMondayReading.MinPageSize, 0))
        assertEquals(TareasMondayReading.Step.Fail(TareasMondayReading.TooHeavyMessage), TareasMondayReading.recover(MondayErrorKind.TooLarge, "x", 0, 10, 0))
        val noPage = TareasMondayReading.recover(MondayErrorKind.Complexity, "x", 0, 25, 0, canShrink = false)
        assertTrue(noPage is TareasMondayReading.Step.Fail && "no se puede reducir" in noPage.message)
    }

    @Test
    fun moreTransientWordsAndGrowingWait() {
        listOf("Concurrency limit reached", "Temporarily unavailable", "Request timeout", "INTERNAL_SERVER_ERROR", "Internal server error").forEach {
            assertEquals(MondayErrorKind.Transient, TareasMondayReading.classify(200, "", it), it)
        }
        assertEquals(listOf(0L, 2L, 4L, 8L, 16L, 30L), (0..5).map { TareasMondayReading.backoffMinutes(it) })
        val at = java.time.Instant.parse("2026-01-01T00:00:00Z")
        assertTrue(TareasMondayReading.retryDue(0, at, at))
        assertFalse(TareasMondayReading.retryDue(2, at, at.plusSeconds(3 * 60)))
        assertTrue(TareasMondayReading.retryDue(2, at, at.plusSeconds(4 * 60)))
        assertFalse(TareasMondayReading.retryDue(9, at, at.plusSeconds(29 * 60)))
        assertTrue(TareasMondayReading.retryDue(9, at, at.plusSeconds(30 * 60)))
    }

    @Test
    fun transientKeepsPageSizeAndPermanentFails() {
        assertEquals(TareasMondayReading.Step.Retry(3, 25), TareasMondayReading.recover(MondayErrorKind.Transient, "x", 2, 25, 0))
        assertTrue(TareasMondayReading.recover(MondayErrorKind.Permanent, "x".repeat(500), 0, 25, 0).let { it is TareasMondayReading.Step.Fail && it.message.length == TareasMondayReading.MaxMessage })
    }

    @Test
    fun expiredCursorRestartsOnceThenFails() {
        assertEquals(TareasMondayReading.Step.Restart, TareasMondayReading.recover(MondayErrorKind.CursorExpired, "x", 0, 25, 0))
        assertTrue(TareasMondayReading.recover(MondayErrorKind.CursorExpired, "x", 0, 25, 1) is TareasMondayReading.Step.Fail)
    }

    @Test
    fun boardListingStopsWhenTheTotalBudgetIsSpentButAlwaysReadsFirstPage() {
        val now = 1_000_000_000L
        assertFalse(TareasMondayClient.boardsBudgetExpired(1, now - 1, now))
        assertFalse(TareasMondayClient.boardsBudgetExpired(2, now + 1, now))
        assertTrue(TareasMondayClient.boardsBudgetExpired(2, now, now))
        assertTrue(TareasMondayClient.boardsBudgetExpired(7, now - 5, now))
    }

    @Test
    fun exceptionKindDefaultsFromTransientFlag() {
        assertEquals(MondayErrorKind.Transient, TareasMondayException("x", transient = true).kind)
        assertEquals(MondayErrorKind.Permanent, TareasMondayException("x").kind)
        assertEquals(MondayErrorKind.TooLarge, TareasMondayException("x", false, MondayErrorKind.TooLarge).kind)
    }

    @Test
    fun updatesQueryIsASecondBatchQueryByIdsWithReplies() {
        val query = TareasMondayClient.updatesQuery(listOf("11", "22", "x; drop", "11"))
        assertTrue(query.startsWith("query { items(ids: [11, 22], limit: 2) {"))
        assertTrue("updates(limit: 100)" in query && "replies {" in query)
    }

    @Test
    fun incompleteNotesAreFlaggedWhenAnItemIsMissingOrHitsTheLimit() {
        val full = (1..TareasMondayClient.UpdatesLimit).map { MondayUpdate("u$it", "t", "", "", "") }
        val updates = mapOf("1" to listOf(MondayUpdate("u", "t", "", "", "")), "2" to full)
        assertEquals(setOf("2", "3"), TareasMondayClient.incompleteNotes(listOf("1", "2", "3"), updates))
    }

    @Test
    fun updatesAreOnlyRequestedWhenTheMappingAsksForThem() {
        assertTrue(TareasMondayMapping.wantsUpdates(MondayMapping()))
        assertTrue(TareasMondayMapping.wantsUpdates(MondayMapping(mapOf(MondayTarget.Status to "status"))))
        assertFalse(TareasMondayMapping.wantsUpdates(MondayMapping(mapOf(MondayTarget.Comments to MondayTarget.CommentsOff))))
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
        assertEquals(3, TareasMondayJobs.purgeOld(connection))
        val text = sql.single()
        assertTrue("status IN ('done', 'failed', 'cancelled')" in text)
        assertTrue("LIMIT ?" in text)
        assertTrue("preview" !in text && "reading" !in text && "applying" !in text)
        assertEquals(listOf(30, 5), ints)
    }
}
