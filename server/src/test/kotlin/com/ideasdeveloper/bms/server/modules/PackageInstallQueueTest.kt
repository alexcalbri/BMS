package com.ideasdeveloper.bms.server.modules

import java.sql.DriverManager
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageInstallQueueTest {
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun runNext() = tasks.removeFirst().run()
        fun runAll() { while (tasks.isNotEmpty()) runNext() }
    }

    private class Run(val journal: MemoryPackageInstallJournal = MemoryPackageInstallJournal(), val executor: ManualExecutor = ManualExecutor()) {
        var restarts = 0
        val built = mutableListOf<String>()
        val queue = PackageInstallQueue(journal, requestRestart = { restarts++ }, executor = executor)

        fun module(id: String, failure: String? = null) = queue.enqueue(PendingPackageInstall(PackageInstallQueue.Module, id)) {
            built += id
            failure
        }
    }

    @Test
    fun `modules and extensions asked in a row share one queue and one restart`() {
        val run = Run()
        listOf("crm", "tareas", "membresia").forEach { run.module(it) }
        run.queue.enqueue(PendingPackageInstall(PackageInstallQueue.Extension, "monday", "owner_role")) { run.built += "monday"; null }
        assertEquals(4, run.journal.state.pending.size)

        repeat(3) {
            run.executor.runNext()
            assertEquals(0, run.restarts)
        }
        assertTrue(run.queue.restartPending)
        run.executor.runNext()
        assertEquals(1, run.restarts)
        assertEquals(listOf("crm", "tareas", "membresia", "monday"), run.built)
        assertTrue(run.journal.state.pending.isEmpty())
    }

    @Test
    fun `a failing module does not stop the others and its error is kept apart by kind`() {
        val run = Run()
        run.module("crm")
        run.module("tareas", failure = "No compila")
        run.queue.enqueue(PendingPackageInstall(PackageInstallQueue.Module, "roto")) { error("se rompio") }
        run.module("membresia")
        run.executor.runAll()

        assertEquals(listOf("crm", "tareas", "membresia"), run.built)
        assertEquals(1, run.restarts)
        assertEquals(mapOf("tareas" to "No compila", "roto" to "Error inesperado: se rompio"), run.queue.failures(PackageInstallQueue.Module))
        assertTrue(run.queue.failures(PackageInstallQueue.Extension).isEmpty())

        // Pedirlo otra vez o quitarlo limpia el error guardado.
        val next = Run(run.journal)
        assertEquals("No compila", next.queue.failures(PackageInstallQueue.Module)["tareas"])
        next.module("tareas")
        next.queue.clearFailure(PackageInstallQueue.Module, "roto")
        assertTrue(next.queue.failures(PackageInstallQueue.Module).isEmpty())
    }

    @Test
    fun `a restart in the middle resumes what was queued and counts the interrupted build`() {
        val before = Run()
        listOf("crm", "tareas", "membresia").forEach { before.module(it) }
        before.executor.runNext()
        // tareas estaba compilando cuando el servidor se reinicio.
        val state = before.journal.state
        before.journal.state = state.copy(pending = state.pending.map { if (it.id == "tareas") it.copy(attempts = 1) else it })

        val after = Run(before.journal)
        // Solo se retoma lo de cada tipo; una extension no se pide por el camino de los modulos.
        after.queue.resume(PackageInstallQueue.Extension) { error("no hay extensiones pendientes") }
        after.queue.resume(PackageInstallQueue.Module) { pending -> after.module(pending.id) }
        after.executor.runNext()
        // El intento interrumpido de tareas cuenta; membresia nunca habia empezado.
        assertEquals(0, after.journal.state.pending.single { it.id == "membresia" }.attempts)
        after.executor.runAll()
        assertEquals(listOf("tareas", "membresia"), after.built)
        assertEquals(1, after.restarts)
        assertTrue(after.journal.state.pending.isEmpty())
        assertTrue(after.journal.state.failures.isEmpty())
    }

    @Test
    fun `an install that cannot be resumed ends with a visible error and the rest continue`() {
        val journal = MemoryPackageInstallJournal(
            PackageInstallJournalState(
                pending = listOf(
                    PendingPackageInstall(PackageInstallQueue.Module, "ya_no_existe"),
                    PendingPackageInstall(PackageInstallQueue.Module, "ya_instalado", attempts = 1),
                    PendingPackageInstall(PackageInstallQueue.Module, "tumba_el_servidor", attempts = PackageInstallQueue.MaxAttempts),
                    PendingPackageInstall(PackageInstallQueue.Module, "crm"),
                ),
            ),
        )
        val run = Run(journal)
        run.queue.resume(PackageInstallQueue.Module) { pending ->
            when (pending.id) {
                "ya_no_existe" -> error("Modulo no encontrado en el catalogo configurado.")
                "ya_instalado" -> Unit
                else -> run.module(pending.id)
            }
        }
        run.executor.runAll()

        assertEquals(listOf("crm"), run.built)
        assertTrue(journal.state.pending.isEmpty())
        val failures = run.queue.failures(PackageInstallQueue.Module)
        assertEquals(setOf("ya_no_existe", "tumba_el_servidor"), failures.keys)
        assertEquals("Modulo no encontrado en el catalogo configurado.", failures["ya_no_existe"])
        assertEquals(1, run.restarts)
    }

    @Test
    fun `no restart when nothing was built and none after it was already requested`() {
        val run = Run()
        run.module("crm", failure = "No compila")
        run.executor.runAll()
        assertEquals(0, run.restarts)
        assertFalse(run.queue.restartPending)

        run.module("tareas")
        run.executor.runAll()
        assertEquals(1, run.restarts)
        // El proceso esta saliendo: lo nuevo no empieza a compilar, queda anotado para el arranque.
        run.module("membresia")
        assertTrue(run.executor.tasks.isEmpty())
        assertEquals(listOf("membresia"), run.journal.state.pending.map { it.id })
        assertEquals(1, run.restarts)
    }

    @Test
    fun `the queue keeps working when the journal cannot be read or written`() {
        val broken = object : PackageInstallJournal {
            override fun read(): PackageInstallJournalState = error("sin base de datos")
            override fun write(state: PackageInstallJournalState) = error("sin base de datos")
        }
        var restarts = 0
        val built = mutableListOf<String>()
        val queue = PackageInstallQueue(broken, requestRestart = { restarts++ }, executor = Executor { it.run() })
        queue.resume(PackageInstallQueue.Module) { error("nada que retomar") }
        queue.enqueue(PendingPackageInstall(PackageInstallQueue.Module, "crm")) { built += "crm"; null }
        assertEquals(listOf("crm"), built)
        assertEquals(1, restarts)
        assertTrue(queue.failures(PackageInstallQueue.Module).isEmpty())
    }

    // Contra PostgreSQL real (server_settings de la base de control); se salta sin BMS_CONTROL_PG_URL.
    @Test
    fun `the jdbc journal keeps the queue in server_settings across processes`() {
        val url = System.getenv("BMS_CONTROL_PG_URL")?.takeIf { it.isNotBlank() } ?: return
        DriverManager.getConnection(url).use { db ->
            db.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS server_settings (setting_key VARCHAR(120) PRIMARY KEY, setting_value TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)")
                statement.execute("DELETE FROM server_settings WHERE setting_key = '${JdbcPackageInstallJournal.SettingKey}'")
            }
        }
        val journal = JdbcPackageInstallJournal { DriverManager.getConnection(url) }
        assertEquals(PackageInstallJournalState(), journal.read())

        val executor = ManualExecutor()
        val before = PackageInstallQueue(journal, requestRestart = {}, executor = executor)
        before.enqueue(PendingPackageInstall(PackageInstallQueue.Module, "crm")) { "No compila" }
        before.enqueue(PendingPackageInstall(PackageInstallQueue.Extension, "monday", "owner_role", PendingPackageInstall.OriginZip, "/tmp/p.zip", "zip:p.zip", "zip")) { null }
        executor.runNext()

        val stored = JdbcPackageInstallJournal { DriverManager.getConnection(url) }.read()
        assertEquals(listOf(PendingPackageInstall(PackageInstallQueue.Extension, "monday", "owner_role", PendingPackageInstall.OriginZip, "/tmp/p.zip", "zip:p.zip", "zip")), stored.pending)
        assertEquals(listOf(PackageInstallFailure(PackageInstallQueue.Module, "crm", "No compila")), stored.failures)

        val resumed = mutableListOf<String>()
        val after = PackageInstallQueue(journal, requestRestart = {}, executor = Executor { it.run() })
        after.resume(PackageInstallQueue.Extension) { pending -> after.enqueue(pending) { resumed += pending.id; null } }
        assertEquals(listOf("monday"), resumed)
        assertTrue(journal.read().pending.isEmpty())
    }
}
