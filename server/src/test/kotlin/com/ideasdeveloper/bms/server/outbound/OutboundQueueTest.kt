package com.ideasdeveloper.bms.server.outbound

import com.ideasdeveloper.bms.server.modules.ModuleFiles
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Savepoint
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pruebas de la cola de salida con un almacen en memoria (no hay PostgreSQL en las pruebas). */
class OutboundQueueTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val tenant = connection(autoCommit = true)

    @Test
    fun backoffDoublesFromOneMinuteAndStopsAtSixHours() {
        assertEquals(Duration.ofMinutes(1), OutboundQueue.backoff(1))
        assertEquals(Duration.ofMinutes(2), OutboundQueue.backoff(2))
        assertEquals(Duration.ofMinutes(8), OutboundQueue.backoff(4))
        assertEquals(Duration.ofMinutes(128), OutboundQueue.backoff(8))
        assertEquals(Duration.ofHours(6), OutboundQueue.backoff(10))
        assertEquals(Duration.ofHours(6), OutboundQueue.backoff(500))
    }

    @Test
    fun publishFiltersByExtensionModuleAndWants() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday", "other_mod", "picky"))
        val sinks = listOf(
            FakeSink("monday"),
            FakeSink("off"), // extension no activa
            FakeSink("other_mod", moduleId = "membresia"), // otro modulo
            FakeSink("picky", wants = false),
            FakeSink("broken", wantsThrows = true),
        )
        store.enabled += "broken"
        OutboundQueue.publish(store, tenant, sinks, OutboundEvent.created("crm", "lead", "1"), now)
        assertEquals(listOf("monday", "broken"), store.rows.map { it.sinkId })
        // El sink cuyo wants lanza deja una fila fallida visible; el resto no.
        assertEquals(OutboundStatus.Failed, store.rows.last().status)
    }

    @Test
    fun publishNeverThrowsWhenStoreFails() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"), failEnqueue = true)
        OutboundQueue.publish(store, tenant, listOf(FakeSink("monday")), OutboundEvent.created("crm", "lead", "1"), now)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun publishUsesSavepointInsideForeignTransaction() {
        val events = mutableListOf<String>()
        val inTransaction = connection(autoCommit = false, savepointLog = events)
        val failing = InMemoryOutboundStore(enabled = mutableSetOf("monday"), failEnqueue = true)
        OutboundQueue.publish(failing, inTransaction, listOf(FakeSink("monday")), OutboundEvent.created("crm", "lead", "1"), now)
        assertTrue("rollback" in events, "un fallo debe deshacer el savepoint: $events")

        events.clear()
        val working = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        OutboundQueue.publish(working, inTransaction, listOf(FakeSink("monday")), OutboundEvent.created("crm", "lead", "1"), now)
        assertTrue("release" in events && "rollback" !in events, "$events")
        assertEquals(1, working.rows.size)
    }

    @Test
    fun publishMergesPendingUpdates() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sinks = listOf(FakeSink("monday"))
        OutboundQueue.publish(store, tenant, sinks, OutboundEvent.updated("crm", "lead", "1", listOf("name")), now)
        OutboundQueue.publish(store, tenant, sinks, OutboundEvent.updated("crm", "lead", "1", listOf("email", "name")), now)
        OutboundQueue.publish(store, tenant, sinks, OutboundEvent.updated("crm", "lead", "2", listOf("name")), now)
        assertEquals(2, store.rows.size)
        assertEquals(listOf("name", "email"), store.rows.first().event.changed)
    }

    @Test
    fun drainMarksSentWithDetailAndWarnings() {
        val (store, sink) = scenario(OutboundResult.Sent("item 42", listOf("campo sin columna")))
        assertEquals(1, drain(store, sink))
        val row = store.rows.single()
        assertEquals(OutboundStatus.Sent, row.status)
        assertEquals(now, row.sentAt)
        assertEquals("item 42 | Avisos: campo sin columna", row.result)
    }

    @Test
    fun drainMarksSkipped() {
        val (store, sink) = scenario(OutboundResult.Skipped("tipo apagado"))
        drain(store, sink)
        assertEquals(OutboundStatus.Skipped, store.rows.single().status)
        assertEquals("tipo apagado", store.rows.single().result)
    }

    @Test
    fun retryUsesBackoffAndFailsAfterMaxAttempts() {
        val (store, sink) = scenario(OutboundResult.Retry("red caida"))
        drain(store, sink)
        var row = store.rows.single()
        assertEquals(OutboundStatus.Pending, row.status)
        assertEquals(1, row.attempts)
        assertEquals(now.plus(Duration.ofMinutes(1)), row.nextAttemptAt)
        assertEquals("red caida", row.lastError)

        // Aun no vence: no se entrega.
        assertEquals(0, drain(store, sink, now.plusSeconds(30)))

        var clock = now
        repeat(OutboundQueue.MaxAttempts - 1) {
            clock = row.nextAttemptAt
            drain(store, sink, clock)
            row = store.rows.single()
        }
        assertEquals(OutboundStatus.Failed, row.status)
        assertEquals(OutboundQueue.MaxAttempts, row.attempts)
        assertEquals(OutboundQueue.MaxAttempts, sink.deliveries.size)
        assertEquals(OutboundQueue.MaxAttempts, sink.deliveries.last().attempt)
    }

    @Test
    fun retryHonorsLongerAfter() {
        val (store, sink) = scenario(OutboundResult.Retry("espera", after = Duration.ofMinutes(30)))
        drain(store, sink)
        assertEquals(now.plus(Duration.ofMinutes(30)), store.rows.single().nextAttemptAt)
    }

    @Test
    fun throttledKeepsAttemptsAndStopsThatSinkOnly() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday", "second"))
        val slow = FakeSink("monday") { OutboundResult.Throttled(Duration.ofMinutes(5), "limite") }
        val other = FakeSink("second") { OutboundResult.Sent() }
        store.publish(slow, "1")
        store.publish(slow, "2")
        store.publish(other, "1")
        val done = OutboundQueue.drain(store, tenant, FakeSource, listOf(slow, other), null, NoLinks, now)
        assertEquals(2, done) // un Throttled y el del otro sink
        assertEquals(1, slow.deliveries.size, "el sink limitado no recibe mas en la pasada")
        val rows = store.rows.filter { it.sinkId == "monday" }
        assertTrue(rows.all { it.status == OutboundStatus.Pending && it.attempts == 0 && it.lockedAt == null })
        assertEquals(now.plus(Duration.ofMinutes(5)), rows.first().nextAttemptAt)
        assertEquals(OutboundStatus.Sent, store.rows.single { it.sinkId == "second" }.status)
    }

    @Test
    fun failedStaysFailedAndBlocksLaterEventsOfThatRecord() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday") { OutboundResult.Failed("columna invalida") }
        store.publish(sink, "1", OutboundEventKind.Created)
        store.publish(sink, "1", OutboundEventKind.Note)
        drain(store, sink)
        assertEquals(listOf(OutboundStatus.Failed, OutboundStatus.Pending), store.rows.map { it.status })
        assertEquals("columna invalida", store.rows.first().lastError)
        drain(store, sink, now.plus(Duration.ofHours(1)))
        assertEquals(1, sink.deliveries.size, "la nota espera a que se resuelva el created")
    }

    @Test
    fun exceptionCountsAsRetryWithClassAndMessage() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday") { throw IllegalStateException("token secreto") }
        store.publish(sink, "1")
        drain(store, sink)
        val row = store.rows.single()
        assertEquals(OutboundStatus.Pending, row.status)
        assertEquals(1, row.attempts)
        assertEquals("error inesperado (IllegalStateException: token secreto)", row.lastError)
    }

    @Test
    fun errorsAlsoCountAsRetry() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday") { throw NoClassDefFoundError("x") }
        store.publish(sink, "1")
        drain(store, sink)
        assertEquals("error inesperado (NoClassDefFoundError: x)", store.rows.single().lastError)
    }

    @Test
    fun interruptionReleasesTheClaimAndRestoresTheFlag() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday") { throw InterruptedException() }
        store.publish(sink, "1")
        try {
            drain(store, sink)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        val row = store.rows.single()
        assertEquals(OutboundStatus.Pending, row.status)
        assertEquals(0, row.attempts)
    }

    @Test
    fun recordOrderIsKeptAndNotesWaitForCreated() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val order = mutableListOf<OutboundEventKind>()
        val sink = FakeSink("monday") { order += it.event.kind; OutboundResult.Sent() }
        store.publish(sink, "1", OutboundEventKind.Created)
        store.publish(sink, "1", OutboundEventKind.Note)
        store.publish(sink, "2", OutboundEventKind.Created)
        assertEquals(3, drain(store, sink))
        assertEquals(listOf(OutboundEventKind.Created, OutboundEventKind.Created, OutboundEventKind.Note), order.sortedBy { it != OutboundEventKind.Created })
        assertTrue(store.rows.all { it.status == OutboundStatus.Sent })
        // La nota se entrego despues de su created.
        assertTrue(store.rows[1].sentOrder > store.rows[0].sentOrder)
    }

    @Test
    fun aLaterEventWaitsWhileTheEarlierOneRetries() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday") { OutboundResult.Retry("falla") }
        store.publish(sink, "1", OutboundEventKind.Created)
        store.publish(sink, "1", OutboundEventKind.Note)
        drain(store, sink)
        drain(store, sink, now.plus(Duration.ofMinutes(2)))
        assertTrue(sink.deliveries.all { it.event.kind == OutboundEventKind.Created })
        assertEquals(OutboundStatus.Pending, store.rows[1].status)
        assertEquals(0, store.rows[1].attempts)
    }

    @Test
    fun failedEventCanBeRetriedOrDiscarded() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        var fail = true
        val sink = FakeSink("monday") { if (fail) OutboundResult.Failed("mal") else OutboundResult.Sent() }
        store.publish(sink, "1", OutboundEventKind.Created)
        store.publish(sink, "1", OutboundEventKind.Note)
        drain(store, sink)
        val failedId = store.rows.first().id
        assertFalse(store.retry(tenant, "otro", failedId, now), "otro servicio no puede tocarlo")

        fail = false
        assertTrue(store.retry(tenant, "monday", failedId, now))
        assertEquals(0, store.rows.first().attempts)
        drain(store, sink, now)
        drain(store, sink, now)
        assertTrue(store.rows.all { it.status == OutboundStatus.Sent })

        // Descartar un fallido libera a los siguientes.
        val second = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        fail = true
        second.publish(sink, "1", OutboundEventKind.Created)
        second.publish(sink, "1", OutboundEventKind.Note)
        drain(second, sink)
        assertTrue(second.discard(tenant, "monday", second.rows.first().id, now))
        assertEquals(OutboundStatus.Skipped, second.rows.first().status)
        fail = false
        drain(second, sink, now)
        assertEquals(OutboundStatus.Sent, second.rows[1].status)
    }

    @Test
    fun retryNowAcceptsAPendingEventThatSpentAttempts() {
        val (store, sink) = scenario(OutboundResult.Retry("red caida"))
        drain(store, sink)
        val row = store.rows.single()
        assertEquals(1, row.attempts)
        val id = row.id
        assertFalse(store.retry(tenant, "otro", id, now))
        assertTrue(store.retry(tenant, "monday", id, now.plusSeconds(5)))
        assertEquals(0, row.attempts)
        assertEquals(now.plusSeconds(5), row.nextAttemptAt)
        assertEquals(id, store.rows.single().id)
        // Un pendiente sin intentos gastados no necesita reintento.
        val fresh = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        fresh.publish(sink, "9")
        assertFalse(fresh.retry(tenant, "monday", fresh.rows.single().id, now))
    }

    @Test
    fun retryFailedRequeuesEveryFailedOfTheSink() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday") { OutboundResult.Failed("mal") }
        store.publish(sink, "1")
        store.publish(sink, "2")
        drain(store, sink)
        assertEquals(2, store.retryFailed(tenant, "monday", now))
        assertTrue(store.rows.all { it.status == OutboundStatus.Pending })
    }

    @Test
    fun inactiveExtensionWaitsWithoutDeliveringOrDeleting() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday")
        store.publish(sink, "1")
        store.enabled.clear()
        assertEquals(0, drain(store, sink))
        assertTrue(sink.deliveries.isEmpty())
        assertEquals(OutboundStatus.Pending, store.rows.single().status)

        store.enabled += "monday"
        assertEquals(1, drain(store, sink))
        assertEquals(OutboundStatus.Sent, store.rows.single().status)
    }

    @Test
    fun staleSendingEventsGoBackToPending() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday")
        store.publish(sink, "1")
        // Reclamado y nunca terminado (el proceso se corto): 40 minutos despues vuelve a la cola y se entrega.
        store.claimDue(tenant, listOf("monday"), now, 10)
        assertEquals(OutboundStatus.Sending, store.rows.single().status)
        assertEquals(1, OutboundQueue.drain(store, tenant, FakeSource, listOf(sink), null, NoLinks, now.plus(Duration.ofMinutes(40))))
        assertEquals(OutboundStatus.Sent, store.rows.single().status)
    }

    @Test
    fun limitStopsThePass() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday")
        repeat(5) { store.publish(sink, "r$it") }
        assertEquals(3, OutboundQueue.drain(store, tenant, FakeSource, listOf(sink), null, NoLinks, now, limit = 3))
        assertEquals(2, store.rows.count { it.status == OutboundStatus.Pending })
    }

    @Test
    fun labelUsesFileNameOrFirstWords() {
        assertEquals("plano.pdf", outboundLabel("plano.pdf", "ignorado"))
        assertEquals("hola mundo", outboundLabel(null, "  hola \n mundo "))
        assertEquals(60, outboundLabel(null, "x".repeat(200))!!.length)
        assertNull(outboundLabel(null, " "))
    }

    @Test
    fun publishLeavesAFailedRowWhenWantsThrows() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        OutboundQueue.publish(store, tenant, listOf(FakeSink("monday", wantsThrows = true)), OutboundEvent.created("crm", "lead", "1"), now)
        val row = store.rows.single()
        assertEquals(OutboundStatus.Failed, row.status)
        assertEquals("No se pudo poner en cola (IllegalStateException)", row.lastError)
    }

    @Test
    fun publishNormalizesTheOrigin() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sinks = listOf(FakeSink("monday"))
        OutboundQueue.publish(store, tenant, sinks, OutboundEvent.created("crm", "lead", "1", origin = " "), now)
        OutboundQueue.publish(store, tenant, sinks, OutboundEvent.created("crm", "lead", "2", origin = "x".repeat(100)), now)
        assertEquals("user", store.rows[0].event.origin)
        assertEquals(63, store.rows[1].event.origin.length)
    }

    @Test
    fun mergeDoesNotTouchAnEventAlreadySending() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sinks = listOf(FakeSink("monday"))
        OutboundQueue.publish(store, tenant, sinks, OutboundEvent.updated("crm", "lead", "1", listOf("name")), now)
        store.claimDue(tenant, listOf("monday"), now, 10)
        OutboundQueue.publish(store, tenant, sinks, OutboundEvent.updated("crm", "lead", "1", listOf("email")), now)
        assertEquals(2, store.rows.size)
        assertEquals(listOf("name"), store.rows[0].event.changed)
        assertEquals(listOf("email"), store.rows[1].event.changed)
    }

    @Test
    fun failedNoteDoesNotBlockButFailedCreatedDoes() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday") { if (it.event.kind == OutboundEventKind.Note) OutboundResult.Failed("nota mala") else OutboundResult.Sent() }
        store.publish(sink, "1", OutboundEventKind.Created)
        store.publish(sink, "1", OutboundEventKind.Note)
        store.publish(sink, "1", OutboundEventKind.Note)
        assertEquals(3, drain(store, sink))
        assertEquals(listOf(OutboundStatus.Sent, OutboundStatus.Failed, OutboundStatus.Failed), store.rows.map { it.status })

        val blocked = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val failing = FakeSink("monday") { OutboundResult.Failed("sin elemento") }
        blocked.publish(failing, "1", OutboundEventKind.Created)
        blocked.publish(failing, "1", OutboundEventKind.Updated)
        drain(blocked, failing)
        assertEquals(1, failing.deliveries.size)
        assertEquals(OutboundStatus.Pending, blocked.rows[1].status)
    }

    @Test
    fun markAfterReleaseStaleDoesNotOverwrite() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        store.publish(FakeSink("monday"), "1")
        val claim = store.claimDue(tenant, listOf("monday"), now, 10).single()
        store.releaseStale(tenant, now.plusSeconds(60), now.plusSeconds(60))
        assertFalse(store.markSent(tenant, claim.id, now, "tarde"))
        assertFalse(store.markFailed(tenant, claim.id, now, 1, "tarde"))
        assertEquals(OutboundStatus.Pending, store.rows.single().status)
        assertNull(store.rows.single().result)
    }

    @Test
    fun throttledSinkIsSkippedUntilItsNotBefore() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday") { OutboundResult.Throttled(Duration.ofMinutes(10), "limite") }
        store.publish(sink, "1")
        drain(store, sink)
        store.publish(sink, "2") // vencido, pero el servicio sigue limitando
        assertEquals(0, drain(store, sink, now.plus(Duration.ofMinutes(5))))
        assertEquals(1, sink.deliveries.size)
        // Pasado el "no antes de" se vuelve a intentar (y se vuelve a cortar).
        assertEquals(1, drain(store, sink, now.plus(Duration.ofMinutes(11))))
        assertEquals(2, sink.deliveries.size)
    }

    @Test
    fun throttledForMoreThanSevenDaysFails() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday") { OutboundResult.Throttled(Duration.ofMinutes(10), "limite") }
        store.publish(sink, "1")
        drain(store, sink, now.plus(Duration.ofDays(8)))
        val row = store.rows.single()
        assertEquals(OutboundStatus.Failed, row.status)
        assertTrue(row.lastError!!.contains("limitando"))
    }

    @Test
    fun emptyQueueSkipsAllFurtherQueries() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday")
        assertEquals(0, drain(store, sink))
        assertEquals(0, store.enabledCalls)
        assertEquals(0, store.staleCalls)
        // Solo entregados: tampoco hay trabajo.
        store.publish(sink, "1")
        assertEquals(1, drain(store, sink))
        val calls = store.enabledCalls
        assertEquals(0, drain(store, sink))
        assertEquals(calls, store.enabledCalls)
    }

    @Test
    fun enabledSinksFiltersInOneQueryAndNeverThrows() {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sinks = listOf(FakeSink("monday"), FakeSink("off"))
        assertEquals(listOf("monday"), OutboundQueue.enabledSinks(store, tenant, sinks).map { it.sinkId })
        assertEquals(1, store.enabledCalls)
        assertTrue(OutboundQueue.enabledSinks(store, tenant, emptyList()).isEmpty())

        val broken = InMemoryOutboundStore(enabled = mutableSetOf("monday"), failEnabled = true)
        assertTrue(OutboundQueue.enabledSinks(broken, tenant, sinks).isEmpty())
    }

    @Test
    fun publishInForeignTransactionUsesOneSavepointOnTheFastPath() {
        val events = mutableListOf<String>()
        val inTransaction = connection(autoCommit = false, savepointLog = events)
        val store = InMemoryOutboundStore(enabled = mutableSetOf("a", "b"))
        OutboundQueue.publish(store, inTransaction, listOf(FakeSink("a"), FakeSink("b")), OutboundEvent.created("crm", "lead", "1"), now)
        assertEquals(listOf("set", "release"), events)
        assertEquals(2, store.rows.size)
    }

    // --- Ayudas ---

    private fun scenario(result: OutboundResult): Pair<InMemoryOutboundStore, FakeSink> {
        val store = InMemoryOutboundStore(enabled = mutableSetOf("monday"))
        val sink = FakeSink("monday") { result }
        store.publish(sink, "1")
        return store to sink
    }

    private fun drain(store: InMemoryOutboundStore, sink: FakeSink, at: Instant = now): Int =
        OutboundQueue.drain(store, tenant, FakeSource, listOf(sink), null, NoLinks, at)

    private fun InMemoryOutboundStore.publish(sink: FakeSink, recordId: String, kind: OutboundEventKind = OutboundEventKind.Created) {
        val event = when (kind) {
            OutboundEventKind.Note -> OutboundEvent.note("crm", "lead", recordId, "n1", "texto", null)
            else -> OutboundEvent("crm", "lead", recordId, kind)
        }
        enqueue(tenant, sink.sinkId, event, now)
    }

    private fun connection(autoCommit: Boolean, savepointLog: MutableList<String> = mutableListOf()): Connection {
        val savepoint = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Savepoint::class.java)) { _, _, _ -> null } as Savepoint
        val catalog = "test-db-${System.nanoTime()}"
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "getAutoCommit" -> autoCommit
                "getCatalog" -> catalog
                "setSavepoint" -> savepoint.also { savepointLog += "set" }
                "releaseSavepoint" -> { savepointLog += "release"; null }
                "rollback" -> { savepointLog += "rollback"; null }
                else -> null
            }
        } as Connection
    }

    private object NoLinks : OutboundLinks {
        override fun get(tenant: Connection, sinkId: String, recordType: String, recordId: String): OutboundLink? = null
        override fun put(tenant: Connection, sinkId: String, recordType: String, recordId: String, link: OutboundLink) {}
    }

    private object FakeSource : OutboundSource {
        override val moduleId = "crm"
        override fun recordTypes(tenant: Connection) = emptyList<OutboundRecordType>()
        override fun record(tenant: Connection, recordType: String, recordId: String): OutboundRecord? = null
        override fun file(tenant: Connection, files: ModuleFiles, recordType: String, recordId: String, fileId: String): OutboundFile? = null
    }

    private class FakeSink(
        override val sinkId: String,
        override val moduleId: String = "crm",
        private val wants: Boolean = true,
        private val wantsThrows: Boolean = false,
        private val onDeliver: (OutboundDelivery) -> OutboundResult = { OutboundResult.Sent() },
    ) : OutboundSink {
        val deliveries = mutableListOf<OutboundDelivery>()

        override fun wants(tenant: Connection, event: OutboundEvent): Boolean {
            if (wantsThrows) throw IllegalStateException("fallo al leer la configuracion")
            return wants
        }

        override fun deliver(delivery: OutboundDelivery): OutboundResult {
            deliveries += delivery
            return onDeliver(delivery)
        }
    }

    private class Row(
        val id: Long,
        val sinkId: String,
        var event: OutboundEvent,
        var status: OutboundStatus = OutboundStatus.Pending,
        var attempts: Int = 0,
        var nextAttemptAt: Instant,
        var lastError: String? = null,
        var result: String? = null,
        var sentAt: Instant? = null,
        var lockedAt: Instant? = null,
        var sentOrder: Int = 0,
        val createdAt: Instant = nextAttemptAt,
    )

    /** Almacen en memoria con la misma semantica que [JdbcOutboundStore]. */
    private class InMemoryOutboundStore(
        val enabled: MutableSet<String>,
        val failEnqueue: Boolean = false,
        val failEnabled: Boolean = false,
    ) : OutboundStore {
        val rows = mutableListOf<Row>()
        var enabledCalls = 0
        var staleCalls = 0
        private var nextId = 1L
        private var sentCounter = 0

        override fun hasWork(tenant: Connection) = rows.any { it.status == OutboundStatus.Pending || it.status == OutboundStatus.Sending }

        override fun enabledSinks(tenant: Connection, sinkIds: Collection<String>): Set<String> {
            enabledCalls++
            if (failEnabled) throw java.sql.SQLException("fallo simulado")
            return sinkIds.filter { it in enabled }.toSet()
        }

        override fun enqueue(tenant: Connection, sinkId: String, event: OutboundEvent, now: Instant) {
            if (failEnqueue) throw java.sql.SQLException("fallo simulado")
            if (event.kind == OutboundEventKind.Updated) {
                val pending = rows.lastOrNull {
                    it.sinkId == sinkId && it.event.recordType == event.recordType && it.event.recordId == event.recordId &&
                        it.event.kind == OutboundEventKind.Updated && it.status == OutboundStatus.Pending && it.attempts == 0
                }
                if (pending != null) {
                    val changed = if (pending.event.changed.isEmpty() || event.changed.isEmpty()) emptyList() else (pending.event.changed + event.changed).distinct()
                    pending.event = pending.event.copy(changed = changed)
                    return
                }
            }
            rows += Row(nextId++, sinkId, event, nextAttemptAt = now)
        }

        override fun enqueueFailed(tenant: Connection, sinkId: String, event: OutboundEvent, now: Instant, reason: String) {
            if (failEnqueue) throw java.sql.SQLException("fallo simulado")
            rows += Row(nextId++, sinkId, event, status = OutboundStatus.Failed, nextAttemptAt = now, lastError = reason)
        }

        override fun releaseStale(tenant: Connection, lockedBefore: Instant, now: Instant): Int {
            staleCalls++
            val stale = rows.filter { it.status == OutboundStatus.Sending && it.lockedAt!! < lockedBefore }
            stale.forEach { it.status = OutboundStatus.Pending; it.lockedAt = null }
            return stale.size
        }

        override fun claimDue(tenant: Connection, sinkIds: Collection<String>, now: Instant, limit: Int): List<OutboundClaim> {
            val due = rows.filter { row ->
                row.status == OutboundStatus.Pending && row.sinkId in sinkIds && row.nextAttemptAt <= now &&
                    rows.none {
                        it.id < row.id && it.sinkId == row.sinkId && it.event.recordType == row.event.recordType &&
                            it.event.recordId == row.event.recordId &&
                            (it.status == OutboundStatus.Pending || it.status == OutboundStatus.Sending ||
                                (it.status == OutboundStatus.Failed && it.event.kind == OutboundEventKind.Created))
                    }
            }.sortedBy { it.id }.take(limit)
            due.forEach { it.status = OutboundStatus.Sending; it.lockedAt = now }
            return due.map { OutboundClaim(it.id, it.sinkId, it.event, it.attempts, it.createdAt) }
        }

        private fun row(id: Long) = rows.single { it.id == id }

        override fun release(tenant: Connection, ids: Collection<Long>) {
            rows.filter { it.id in ids && it.status == OutboundStatus.Sending }.forEach { it.status = OutboundStatus.Pending; it.lockedAt = null }
        }

        // Como en JDBC, solo afectan a una fila que sigue en `sending`.
        private fun ifSending(id: Long, change: (Row) -> Unit): Boolean {
            val row = row(id)
            if (row.status != OutboundStatus.Sending) return false
            change(row)
            row.lockedAt = null
            return true
        }

        override fun markSent(tenant: Connection, id: Long, now: Instant, result: String?) = ifSending(id) {
            it.status = OutboundStatus.Sent; it.sentAt = now; it.result = result; it.lastError = null; it.sentOrder = ++sentCounter
        }

        override fun markSkipped(tenant: Connection, id: Long, now: Instant, reason: String) = ifSending(id) {
            it.status = OutboundStatus.Skipped; it.result = reason
        }

        override fun markRetry(tenant: Connection, id: Long, now: Instant, attempts: Int, nextAttemptAt: Instant, reason: String) = ifSending(id) {
            it.status = OutboundStatus.Pending; it.attempts = attempts; it.nextAttemptAt = nextAttemptAt; it.lastError = reason
        }

        override fun markThrottled(tenant: Connection, id: Long, now: Instant, nextAttemptAt: Instant, reason: String) = ifSending(id) {
            it.status = OutboundStatus.Pending; it.nextAttemptAt = nextAttemptAt; it.lastError = reason
        }

        override fun markFailed(tenant: Connection, id: Long, now: Instant, attempts: Int, reason: String) = ifSending(id) {
            it.status = OutboundStatus.Failed; it.attempts = attempts; it.lastError = reason
        }

        override fun summary(tenant: Connection, sinkId: String, now: Instant): OutboundSummary {
            val own = rows.filter { it.sinkId == sinkId }
            return OutboundSummary(
                own.count { it.status == OutboundStatus.Pending }, own.count { it.status == OutboundStatus.Sending },
                own.count { it.status == OutboundStatus.Failed }, own.count { it.status == OutboundStatus.Sent }, own.count { it.status == OutboundStatus.Skipped },
                own.mapNotNull { it.sentAt }.maxOrNull(), own.filter { it.status == OutboundStatus.Pending }.minOfOrNull { it.nextAttemptAt },
            )
        }

        override fun events(tenant: Connection, sinkId: String, statuses: Collection<OutboundStatus>, limit: Int) = emptyList<OutboundEventRow>()

        override fun retry(tenant: Connection, sinkId: String, id: Long, now: Instant): Boolean {
            val found = rows.firstOrNull { it.id == id && it.sinkId == sinkId && (it.status == OutboundStatus.Failed || (it.status == OutboundStatus.Pending && it.attempts > 0)) } ?: return false
            found.status = OutboundStatus.Pending; found.attempts = 0; found.nextAttemptAt = now; found.lastError = null
            return true
        }

        override fun retryFailed(tenant: Connection, sinkId: String, now: Instant): Int =
            rows.filter { it.sinkId == sinkId && it.status == OutboundStatus.Failed }.count { retry(tenant, sinkId, it.id, now) }

        override fun discard(tenant: Connection, sinkId: String, id: Long, now: Instant): Boolean {
            val found = rows.firstOrNull { it.id == id && it.sinkId == sinkId && (it.status == OutboundStatus.Failed || it.status == OutboundStatus.Pending) } ?: return false
            found.status = OutboundStatus.Skipped; found.result = "descartado"
            return true
        }

        override fun forget(tenant: Connection, sinkId: String): Int = rows.count { it.sinkId == sinkId }.also { rows.removeAll { row -> row.sinkId == sinkId } }

        override fun purge(tenant: Connection, before: Instant, failedBefore: Instant): Int = 0
    }
}
