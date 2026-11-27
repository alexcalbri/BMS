package com.ideasdeveloper.bms.modules.clientes

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
 * The email state of customers (V012) on a real PostgreSQL (BMS_TEST_PG_URL of a DISPOSABLE server; the test creates and drops its own
 * database): the list filter, the reset when the email changes, and the check on the allowed combinations.
 */
class ClientesEmailStatusPostgresTest {
    private val url = (System.getenv("BMS_TEST_PG_URL") ?: System.getenv("BMS_ACTIVITY_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val name = "bms_clientes_email_${System.nanoTime()}"
    private var tenant: Connection? = null

    private fun sorted(directory: String) = File(root, directory).list()!!.filter { it.endsWith(".sql") }.sorted().map { "$directory/$it" }

    private fun open(): Connection? {
        if (url == null) return null
        DriverManager.getConnection(url).use { admin -> admin.createStatement().use { it.execute("CREATE DATABASE $name") } }
        val connection = DriverManager.getConnection(url.replace(Regex("/[^/?]+(\\?|$)"), "/$name$1"))
        tenant = connection
        (sorted("database/core/migrations") + sorted("database/tenant/migrations") + sorted("modules/clientes/migrations")).forEach { path ->
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

    private fun customer(db: Connection, displayName: String, email: String, status: String? = null): UUID {
        val id = UUID.randomUUID()
        db.prepareStatement(
            "INSERT INTO customers (id, display_name, primary_email, primary_phone, status, email_status, email_verified_at) VALUES (?, ?, ?, '', 'active', ?, CASE WHEN ? = 'verified' THEN now() END)",
        ).use { it.setObject(1, id); it.setString(2, displayName); it.setString(3, email); it.setString(4, status); it.setString(5, status); it.executeUpdate() }
        return id
    }

    private fun status(db: Connection, id: UUID) = db.createStatement().use { st -> st.executeQuery("SELECT email_status FROM customers WHERE id = '$id'").use { it.next(); it.getString(1) } }

    private fun names(db: Connection, state: String) = CustomerQuery.page(db, "", null, 50, state).customers.map { it.displayName }

    @Test
    fun theListFiltersByEmailState() {
        val db = open() ?: return
        customer(db, "A sin estado", "a@example.com")
        customer(db, "B sin verificar", "b@example.com", "unverified")
        customer(db, "C verificado", "c@example.com", "verified")
        assertEquals(listOf("A sin estado", "B sin verificar", "C verificado"), names(db, ""))
        assertEquals(listOf("A sin estado", "B sin verificar", "C verificado"), names(db, "anything else is no filter"))
        assertEquals(listOf("B sin verificar"), names(db, "unverified"))
        assertEquals(listOf("C verificado"), names(db, "verified"))
        assertEquals(listOf("A sin estado"), names(db, "unknown"))
        // The state travels with each customer.
        assertEquals("verified", CustomerQuery.page(db, "C", null, 5, "").customers.single().emailStatus)
        assertEquals("", CustomerQuery.page(db, "A", null, 5, "").customers.single().emailStatus)
    }

    @Test
    fun changingTheEmailGoesBackToUnknown() {
        val db = open() ?: return
        val verified = customer(db, "Ana", "ana@example.com", "verified")
        val unverified = customer(db, "Beto", "beto@example.com", "unverified")
        // Same address written differently: nothing changes.
        db.createStatement().use { it.execute("UPDATE customers SET primary_email = ' ANA@example.com ' WHERE id = '$verified'") }
        assertEquals("verified", status(db, verified))
        // Another address (staff, import or agent): unknown, and the date goes with it.
        db.createStatement().use { it.execute("UPDATE customers SET primary_email = 'nueva@example.com' WHERE id = '$verified'") }
        db.createStatement().use { it.execute("UPDATE customers SET primary_email = 'otra@example.com' WHERE id = '$unverified'") }
        assertEquals(null, status(db, verified))
        assertEquals(null, status(db, unverified))
        assertEquals("0", db.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM customers WHERE email_verified_at IS NOT NULL").use { it.next(); it.getString(1) } })
        // Editing the name or phone leaves the state alone.
        val again = customer(db, "Cata", "cata@example.com", "verified")
        db.createStatement().use { it.execute("UPDATE customers SET display_name = 'Catalina', primary_phone = '+507600' WHERE id = '$again'") }
        assertEquals("verified", status(db, again))
    }

    @Test
    fun onlyTheMeaningfulCombinationsAreStored() {
        val db = open() ?: return
        val id = customer(db, "Ana", "ana@example.com")
        assertFailsWith<java.sql.SQLException> { db.createStatement().use { it.execute("UPDATE customers SET email_status = 'verified' WHERE id = '$id'") } }
        assertFailsWith<java.sql.SQLException> { db.createStatement().use { it.execute("UPDATE customers SET email_status = 'maybe' WHERE id = '$id'") } }
    }
}
