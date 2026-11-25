package com.ideasdeveloper.bms.extensions.mantenimiento_monday

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MantenimientoMondayReadingTest {
    @Test
    fun classifiesMondayErrors() {
        assertEquals(MondayErrorKind.Complexity, MondayReading.classify(200, "ComplexityException", "Query has complexity of 5000000"))
        assertEquals(MondayErrorKind.CursorExpired, MondayReading.classify(200, "", "Cursor expired"))
        assertEquals(MondayErrorKind.Transient, MondayReading.classify(429, "", ""))
        assertEquals(MondayErrorKind.Transient, MondayReading.classify(503, "", ""))
        assertEquals(MondayErrorKind.Permanent, MondayReading.classify(200, "", "Column not found"))
    }

    @Test
    fun pageSizeHalvesDownToTheMinimum() {
        assertEquals(12, MondayReading.nextPageSize(25))
        assertEquals(10, MondayReading.nextPageSize(12))
        assertEquals(10, MondayReading.nextPageSize(10))
    }

    @Test
    fun recoverDecidesRetryRestartOrFail() {
        // Bajar la pagina no cuenta como intento.
        val shrink = assertIs<MondayReading.Step.Retry>(MondayReading.recover(MondayErrorKind.Complexity, "x", 0, 25, 0))
        assertEquals(12, shrink.pageSize)
        assertEquals(0, shrink.attempts)
        assertEquals(12, assertIs<MondayReading.Step.Retry>(MondayReading.recover(MondayErrorKind.TooLarge, "x", 0, 25, 0)).pageSize)
        // En el minimo no hay mas que bajar: error claro.
        val heavy = assertIs<MondayReading.Step.Fail>(MondayReading.recover(MondayErrorKind.Complexity, "x", 0, 10, 0))
        assertEquals(MondayReading.TooHeavyMessage, heavy.message)
        assertIs<MondayReading.Step.Fail>(MondayReading.recover(MondayErrorKind.TooLarge, "x", 0, 10, 0))
        // Un fallo pasajero si cuenta intento y no cambia la pagina.
        val transient = assertIs<MondayReading.Step.Retry>(MondayReading.recover(MondayErrorKind.Transient, "x", 1, 25, 0))
        assertEquals(2, transient.attempts)
        assertEquals(25, transient.pageSize)
        assertIs<MondayReading.Step.Fail>(MondayReading.recover(MondayErrorKind.Transient, "x", MondayReading.MaxAttempts - 1, 25, 0))
        assertIs<MondayReading.Step.Restart>(MondayReading.recover(MondayErrorKind.CursorExpired, "x", 0, 25, 0))
        assertIs<MondayReading.Step.Fail>(MondayReading.recover(MondayErrorKind.CursorExpired, "x", 0, 25, 1))
        assertIs<MondayReading.Step.Fail>(MondayReading.recover(MondayErrorKind.Permanent, "x", 0, 25, 0))
    }

    @Test
    fun mondayTemporaryCodesAreTransient() {
        assertEquals(MondayErrorKind.Transient, MondayReading.classify(200, "maxConcurrencyExceeded", "Concurrency limit"))
        assertEquals(MondayErrorKind.Transient, MondayReading.classify(200, "API_TEMPORARILY_BLOCKED", ""))
        assertEquals(MondayErrorKind.Transient, MondayReading.classify(200, "INTERNAL_SERVER_ERROR", ""))
        assertEquals(MondayErrorKind.Transient, MondayReading.classify(200, "", "Request timeout"))
    }

    @Test
    fun retriesWaitLongerEachTimeWithACap() {
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L), (0..6).map { MondayReading.retryDelayMinutes(it) })
        val updated = java.time.Instant.parse("2026-01-01T00:00:00Z")
        assertTrue(MondayReading.retryReady(0, updated, updated))
        assertFalse(MondayReading.retryReady(2, updated, updated.plusSeconds(3 * 60)))
        assertTrue(MondayReading.retryReady(2, updated, updated.plusSeconds(4 * 60)))
        assertFalse(MondayReading.retryReady(5, updated, updated.plusSeconds(29 * 60)))
    }

    @Test
    fun updatesQueryIsBuiltPerBatchAndOnlyWhenTheTargetNeedsIt() {
        val query = MondayReading.updatesQuery(listOf("1", "2"), 100)
        assertTrue(query.startsWith("query { items(ids: [1, 2], limit: 2)"))
        assertTrue("updates(limit: 100)" in query && "replies {" in query)
        assertTrue(MantenimientoMondayClient.needsUpdates(MondayDestination.Orders))
        assertTrue(MantenimientoMondayClient.needsUpdates(MondayDestination.Plans))
        assertFalse(MantenimientoMondayClient.needsUpdates("otro"))
        assertEquals(2, (1..30).map { it.toString() }.chunked(MondayReading.UpdatesBatch).size)
    }

    @Test
    fun responseCapIsFiveMegabytes() {
        assertEquals(5 * 1024 * 1024, MantenimientoMondayClient.MaxBytes)
    }
}
