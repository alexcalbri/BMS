package com.ideasdeveloper.bms.modules.clientes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientesActivityTest {
    @Test
    fun everyKindHasItsOwnTitle() {
        val kinds = ClientesActivity.customerKinds + ClientesActivity.companyKinds
        val titles = kinds.filter { it != "updated" }.map { ClientesActivity.title(it) }
        assertEquals(titles.size, titles.toSet().size)
        assertEquals("Datos del cliente modificados", ClientesActivity.title("updated"))
        assertEquals("Nota borrada", ClientesActivity.title("note_deleted"))
        assertEquals("Entrada del historial borrada", ClientesActivity.title("history_deleted"))
    }

    @Test
    fun customerAndCompanyKindsDoNotOverlap() {
        assertTrue((ClientesActivity.customerKinds intersect ClientesActivity.companyKinds).isEmpty())
    }

    // The kinds the code writes must be exactly the ones the CHECK of V009 (the last migration that redefines it) accepts.
    @Test
    fun kindsMatchTheCheckOfMigrationV009() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        var migration: java.io.File? = null
        while (dir != null && migration == null) {
            migration = java.io.File(dir, "modules/clientes/migrations/V009__customer_accounts.sql").takeIf { it.exists() }
            dir = dir.parentFile
        }
        val text = checkNotNull(migration) { "No se encontro V009 subiendo desde el directorio de pruebas." }.readText()
        val check = text.substringAfter("customer_activity_kind_check CHECK (kind IN (").substringBefore("));")
        val inMigration = Regex("'([a-z_]+)'").findAll(check).map { it.groupValues[1] }.toSet()
        assertEquals(inMigration, ClientesActivity.customerKinds + ClientesActivity.companyKinds)
    }

    // The module filter and the limit run in SQL (one visible set for all module ids), not 500 rows filtered one query at a time.
    @Test
    fun historyFiltersByVisibleModulesAndLimitsInSql() {
        assertTrue(ClientesProfile.HistorySql.contains("module_id = ANY(?)"))
        assertTrue(ClientesProfile.HistorySql.contains("LIMIT ?"))
        assertFalse(ClientesProfile.HistorySql.contains("500"))
    }

    @Test
    fun companyNamesResolveFromOneMapAndFallBackToTheCode() {
        val names = mapOf("acme" to "Acme", "beta" to "Beta")
        assertEquals("Beta", ClientesProfile.nameOf(names, "beta"))
        assertEquals("gone", ClientesProfile.nameOf(names, "gone"))
    }

    @Test
    fun emailCheckOnlyCountsVisibleCustomers() {
        assertTrue(ClientesServerModule.EmailExistsSql.contains(com.ideasdeveloper.bms.server.modules.CustomerSql.Visible))
        assertTrue(ClientesServerModule.EmailExistsSql.contains("status <> 'archived'"))
    }

    @Test
    fun anEmailChangeIsOnlyCheckedWhenTheEmailReallyChanges() {
        assertFalse(ClientesServerModule.emailChanged("Ana@X.com", " ana@x.com "))
        assertTrue(ClientesServerModule.emailChanged("ana@x.com", "otra@x.com"))
        assertTrue(ClientesServerModule.emailChanged("", "ana@x.com"))
    }

    @Test
    fun customerCursorRoundTripsAndRejectsGarbage() {
        val id = java.util.UUID.randomUUID()
        val cursor = CustomerQuery.decodeCursor(CustomerQuery.encodeCursor("ana | maria", id))!!
        assertEquals("ana | maria", cursor.sortName)
        assertEquals(id, cursor.id)
        assertEquals(null, CustomerQuery.decodeCursor("no-es-un-cursor"))
        assertEquals(null, CustomerQuery.decodeCursor(""))
    }

    @Test
    fun customerFilterBuildsSearchAndCursorWithParametersInOrder() {
        val none = CustomerQuery.filter("  ", null)
        assertTrue(none.params.isEmpty())
        assertTrue(none.where.contains(com.ideasdeveloper.bms.server.modules.CustomerSql.Visible))

        val id = java.util.UUID.randomUUID()
        val filter = CustomerQuery.filter("An_a%", CustomerQuery.Cursor("ana", id))
        assertEquals(listOf<Any>("an\\_a\\%%", "an\\_a\\%%", "AN_A%", "An\\_a\\%%", "ana", id), filter.params)
        assertEquals(filter.where.count { it == '?' }, filter.params.size)
        assertTrue(filter.where.contains("(lower(display_name), id) > (?, ?)"))
        assertTrue(CustomerQuery.pageSql(filter).contains("ORDER BY lower(display_name), id"))
    }

    @Test
    fun deletedNotesAndEntriesKeepTheirText() {
        assertEquals("Nota borrada: alergico al mani", ClientesProfile.noteDeletedBody("  alergico al mani "))
        assertEquals("Entrada borrada del historial: Paquete de 3 noches", ClientesProfile.historyDeletedBody("Paquete de 3 noches"))
    }
}
