package com.ideasdeveloper.bms.modules.tareas

import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The customer and the group a task points at are checked where the task is written (BMS_TEST_PG_URL of a DISPOSABLE
 * server; the test creates and drops its own database): a customer that is not visible in the company (a hidden copy of
 * another company, an archived one) and a group of another board never end up on a task, on creation or on edit.
 */
class TareasLinksPostgresTest {
    private val url = (System.getenv("BMS_TEST_PG_URL") ?: System.getenv("BMS_ACTIVITY_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val name = "bms_tareas_links_${System.nanoTime()}"
    private var tenant: Connection? = null

    private fun urlOf(db: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$db$1")

    private fun sorted(directory: String) = File(root, directory).list()!!.filter { it.endsWith(".sql") }.sorted().map { "$directory/$it" }

    private fun open(): Connection? {
        if (url == null) return null
        DriverManager.getConnection(url).use { admin -> admin.createStatement().use { it.execute("CREATE DATABASE $name") } }
        val connection = DriverManager.getConnection(urlOf(name))
        tenant = connection
        (sorted("database/core/migrations") + sorted("database/tenant/migrations") + sorted("modules/clientes/migrations") + sorted("modules/tareas/migrations")).forEach { path ->
            connection.createStatement().use { it.execute(File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")) }
        }
        return connection
    }

    @AfterTest
    fun drop() {
        tenant?.close()
        tenant = null
        if (url != null) DriverManager.getConnection(url).use { admin -> admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") } }
    }

    private fun customer(db: Connection, name: String, origin: String = "", shared: Boolean = false, status: String = "active"): UUID {
        val id = UUID.randomUUID()
        db.prepareStatement("INSERT INTO customers (id, display_name, primary_email, status, customer_type, origin_company_code, shared) VALUES (?, ?, ?, ?, 'person', ?, ?)").use {
            it.setObject(1, id); it.setString(2, name); it.setString(3, "$name@example.com"); it.setString(4, status); it.setString(5, origin); it.setBoolean(6, shared)
            it.executeUpdate()
        }
        return id
    }

    @Test
    fun customerAndGroupMustBeVisibleAndBelongToTheBoard() {
        val db = open() ?: return
        val board = TareasRepository.createBoard(db, "Operaciones", "", BoardVisibility.Company, emptyList(), null)
        val other = TareasRepository.createBoard(db, "Otro", "", BoardVisibility.Company, emptyList(), null)
        val ownGroup = TareasRepository.addGroup(db, board, "Hoy")
        val foreignGroup = TareasRepository.addGroup(db, other, "Ajeno")
        val visible = customer(db, "Ana")
        val hidden = customer(db, "Oculto", origin = "otra_empresa")
        val archived = customer(db, "Archivado", status = "archived")

        // A visible customer and a group of the same board are accepted.
        TareasRepository.create(db, TaskInput(board, ownGroup, "Llamar", customerId = visible), null)
        // A group of another board, a hidden copy and an archived customer are refused.
        assertFailsWith<TareasValidationException> { TareasRepository.create(db, TaskInput(board, foreignGroup, "Mal grupo"), null) }
        assertFailsWith<TareasValidationException> { TareasRepository.create(db, TaskInput(board, null, "Oculto", customerId = hidden), null) }
        assertFailsWith<TareasValidationException> { TareasRepository.create(db, TaskInput(board, null, "Archivado", customerId = archived), null) }
        assertFailsWith<TareasValidationException> { TareasRepository.create(db, TaskInput(board, null, "Inventado", customerId = UUID.randomUUID()), null) }
        // The same on edit.
        val id = TareasRepository.create(db, TaskInput(board, ownGroup, "Editar"), null)
        assertFailsWith<TareasValidationException> { TareasRepository.update(db, id, TaskInput(board, foreignGroup, "Editar")) }
        assertFailsWith<TareasValidationException> { TareasRepository.update(db, id, TaskInput(board, ownGroup, "Editar", customerId = hidden)) }
        assertEquals("1", db.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM tareas_tasks WHERE customer_id IS NOT NULL").use { it.next(); it.getString(1) } })
    }
}
