package com.ideasdeveloper.bms.modules.mantenimiento

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reglas puras de rendimiento: lista por defecto de activas, ventana de cerradas, espera de tareas-sync y comparación de hijos importados. */
class MantenimientoRendimientoTest {
    private val tower = Property(UUID.randomUUID(), "Torre", 1, false)
    private val now = Instant.parse("2026-11-19T12:00:00Z")

    private fun filter(params: Map<String, String>, board: Boolean = false) =
        MantenimientoActions.orderFilter(params, emptyList(), listOf(tower), board, 201, now)

    @Test
    fun defaultListShowsOnlyActiveOrders() {
        val all = filter(mapOf("status" to "Todos"))
        assertTrue(all.activeOnly)
        assertNull(all.status)
        assertFalse(all.closedOnly)
        assertNull(all.closedSince)
        assertEquals(OrderSort.Due, all.sort)
        assertTrue(filter(emptyMap()).activeOnly)
        // Un estado desconocido no filtra por estado, pero tampoco trae el histórico.
        assertTrue(filter(mapOf("status" to "Inventado")).activeOnly)
    }

    @Test
    fun searchWithAllStatusesAlsoFindsClosedOrders() {
        val search = filter(mapOf("status" to "Todos", "q" to "fuga"))
        assertFalse(search.activeOnly)
        assertFalse(search.closedOnly)
        assertNull(search.closedSince)
        assertEquals("fuga", search.query)
        assertEquals(MantenimientoActions.ListingMode.Search, MantenimientoActions.listingMode(mapOf("q" to "OT-7")))
        assertTrue(filter(mapOf("q" to "  ")).activeOnly)
    }

    @Test
    fun closedViewIsBoundedByDateAndSortedByRecent() {
        val closed = filter(mapOf("status" to MantenimientoActions.ClosedOption))
        assertTrue(closed.closedOnly)
        assertFalse(closed.activeOnly)
        assertEquals(now.minus(Duration.ofDays(90)), closed.closedSince)
        assertEquals(OrderSort.Recent, closed.sort)
        assertEquals(MantenimientoActions.ListingMode.ClosedWindow, MantenimientoActions.listingMode(mapOf("status" to MantenimientoActions.ClosedOption)))
    }

    @Test
    fun explicitStatusIsRespectedEvenWhenClosed() {
        val done = filter(mapOf("status" to "Completada"))
        assertEquals(OrderStatus.Done, done.status)
        assertFalse(done.activeOnly)
        assertNull(done.closedSince)
        assertEquals(OrderSort.Recent, done.sort)
        val working = filter(mapOf("status" to "En curso"))
        assertEquals(OrderStatus.InProgress, working.status)
        assertFalse(working.activeOnly)
        assertEquals(OrderSort.Due, working.sort)
    }

    @Test
    fun boardColumnsBoundClosedStatusesOnly() {
        val base = filter(emptyMap(), board = true)
        assertFalse(base.activeOnly)
        val active = MantenimientoActions.boardColumnFilter(base, OrderStatus.Open, 51, now)
        assertEquals(OrderStatus.Open, active.status)
        assertNull(active.closedSince)
        assertEquals(51, active.limit)
        val closed = MantenimientoActions.boardColumnFilter(base, OrderStatus.Done, 51, now)
        assertEquals(OrderStatus.Done, closed.status)
        assertEquals(MantenimientoActions.closedCutoff(now), closed.closedSince)
        assertEquals(OrderSort.Recent, closed.sort)
    }

    @Test
    fun tareasSyncBackoffGrowsAndStops() {
        assertNull(MantenimientoTareasSync.retryDelay(0))
        assertEquals(Duration.ofMinutes(5), MantenimientoTareasSync.retryDelay(1))
        assertEquals(Duration.ofMinutes(10), MantenimientoTareasSync.retryDelay(2))
        assertEquals(Duration.ofMinutes(40), MantenimientoTareasSync.retryDelay(4))
        assertEquals(Duration.ofMinutes(360), MantenimientoTareasSync.retryDelay(9))
        assertNull(MantenimientoTareasSync.retryDelay(MantenimientoTareasSync.MaxAttempts))
        assertNotNull(MantenimientoTareasSync.retryDelay(MantenimientoTareasSync.MaxAttempts - 1))
    }

    @Test
    fun tareasSyncSkipsRecentFailuresAndExhaustedOrders() {
        // Sin fallos entra siempre.
        assertTrue(MantenimientoTareasSync.canRetry(0, null, now))
        // Falló hace 1 minuto con 1 intento: espera 5.
        assertFalse(MantenimientoTareasSync.canRetry(1, now.minusSeconds(60), now))
        assertTrue(MantenimientoTareasSync.canRetry(1, now.minusSeconds(300), now))
        // Con 3 intentos espera 20 minutos.
        assertFalse(MantenimientoTareasSync.canRetry(3, now.minus(Duration.ofMinutes(19)), now))
        assertTrue(MantenimientoTareasSync.canRetry(3, now.minus(Duration.ofMinutes(20)), now))
        // Con el tope, no se reintenta aunque haya pasado mucho tiempo.
        assertFalse(MantenimientoTareasSync.canRetry(MantenimientoTareasSync.MaxAttempts, now.minus(Duration.ofDays(30)), now))
        // Fallos sin fecha registrada: no se adivina, se espera al siguiente cambio.
        assertFalse(MantenimientoTareasSync.canRetry(2, null, now))
    }

    @Test
    fun importedChildrenSkipExistingAndRepeatedKeys() {
        val incoming = listOf(ImportedCheck("a", "uno"), ImportedCheck("b", "dos"), ImportedCheck("b", "dos otra vez"), ImportedCheck("c", "tres"))
        val fresh = MantenimientoImportApply.missingChildren(setOf("a"), incoming) { it.key }
        assertEquals(listOf("b", "c"), fresh.map { it.key })
        assertEquals("dos", fresh.first().text)
        // Importar de nuevo lo mismo no deja nada por insertar.
        assertTrue(MantenimientoImportApply.missingChildren(setOf("a", "b", "c"), incoming) { it.key }.isEmpty())
    }

    @Test
    fun pendingUploadLimitsTriggerAtTheCap() {
        assertFalse(MantenimientoRepository.pendingExceeded(MantenimientoRepository.MaxPendingFiles - 1, 0))
        assertTrue(MantenimientoRepository.pendingExceeded(MantenimientoRepository.MaxPendingFiles, 0))
        assertTrue(MantenimientoRepository.pendingExceeded(0, MantenimientoRepository.MaxPendingBytes))
    }

    @Test
    fun givenUpNoticeOnlyWhenIntegrationIsOnAndThereAreStuckOrders() {
        val on = MantenimientoSettings(UUID.randomUUID(), UUID.randomUUID(), 1)
        val off = MantenimientoSettings(null, null, 1)
        assertNull(MantenimientoAdminScreens.syncGivenUpNotice(on, 0))
        assertNull(MantenimientoAdminScreens.syncGivenUpNotice(off, 3))
        assertNotNull(MantenimientoAdminScreens.syncGivenUpNotice(on, 3))
    }

    @Test
    fun bridgeMarksMissingToolsAsAbsent() {
        assertTrue(MantenimientoBridge.denial(null, null)!!.absent)
        assertFalse(MantenimientoBridge.BridgeResult.Unavailable("la herramienta falló").absent)
    }

    @Test
    fun assetOptionsExplainTruncation() {
        assertEquals("", AssetOptions(emptyList()).truncatedHelp)
        assertTrue(AssetOptions(emptyList(), truncated = true).truncatedHelp.isNotEmpty())
    }
}
