package com.ideasdeveloper.bms.server.modules

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomerReferencesTest {
    @Test
    fun validNamesPass() {
        assertNull(CustomerReference.problem(CustomerReference("crm_leads", "customer_id")))
        assertNull(CustomerReference.problem(CustomerReference("ext_x__rows", "customer_id", "row_id")))
        assertNull(CustomerReference.problem(CustomerReference("a".repeat(63), "c")))
    }

    @Test
    fun invalidNamesAreRejected() {
        listOf("", "1abc", "Leads", "crm-leads", "crm.leads", "a b", "x; DROP TABLE customers", "_x", "a".repeat(64)).forEach { name ->
            assertNotNull(CustomerReference.problem(CustomerReference(name, "customer_id")), "table '$name'")
            assertNotNull(CustomerReference.problem(CustomerReference("leads", name)), "column '$name'")
            assertNotNull(CustomerReference.problem(CustomerReference("leads", "customer_id", name)), "key '$name'")
        }
    }

    @Test
    fun modulesMayOnlyDeclareTablesWithTheirOwnPrefix() {
        assertNull(CustomerReference.problemFor(CustomerReference("crm_leads", "customer_id"), "crm", null))
        assertNotNull(CustomerReference.problemFor(CustomerReference("hotel_stays", "customer_id"), "crm", null))
        assertNotNull(CustomerReference.problemFor(CustomerReference("crmx", "customer_id"), "crm", null))
    }

    @Test
    fun extensionsMayOnlyDeclareTablesWithTheirExtensionPrefix() {
        assertNull(CustomerReference.problemFor(CustomerReference("ext_meta__leads", "customer_id"), "crm", "meta"))
        assertNotNull(CustomerReference.problemFor(CustomerReference("crm_leads", "customer_id"), "crm", "meta"))
        assertNotNull(CustomerReference.problemFor(CustomerReference("ext_other__leads", "customer_id"), "crm", "meta"))
    }

    @Test
    fun coreAndCustomerTablesAreForbiddenEvenWithAMatchingPrefix() {
        listOf("customers", "customer_merges", "application_users", "tenant_modules", "schema_migrations", "business_owner_x").forEach { table ->
            val owner = table.substringBefore('_')
            assertNotNull(CustomerReference.problemFor(CustomerReference(table, "customer_id"), owner, null), table)
        }
    }

    @Test
    fun visibleWithAliasQualifiesColumnsAndRejectsBadAliases() {
        val sql = CustomerSql.visible("c")
        assertTrue("c.origin_company_code = ''" in sql && "c.shared" in sql && "customer_link_origins" in sql)
        listOf("", "C", "1c", "c; DROP", "c.d", "a".repeat(32)).forEach { alias ->
            kotlin.test.assertFailsWith<IllegalArgumentException> { CustomerSql.visible(alias) }
        }
    }

    @Test
    fun keyColumnDefaultsToId() {
        assertEquals("id", CustomerReference("t", "c").keyColumn)
    }

    @Test
    fun visibleConditionUsesTheLinkedOriginsTable() {
        assertTrue("customer_link_origins" in CustomerSql.Visible)
        assertTrue(CustomerSql.Visible.startsWith("(origin_company_code = ''"))
    }

    @Test
    fun jobWithDatabaseScheduleStillNeedsAConnectionToDecide() {
        val clock = ModuleJobClock()
        val now = Instant.parse("2026-11-05T12:00:00Z")
        val plain = ModuleJob("plain", Duration.ofMinutes(5)) {}
        val scheduled = ModuleJob("scheduled", Duration.ofMinutes(5), scheduleFor = { ModuleJobSchedule(interval = Duration.ofHours(1)) }, run = {})
        assertTrue(clock.mayBeDue("m", plain, "acme", now))
        clock.ran("m", plain, "acme", now)
        assertFalse(clock.mayBeDue("m", plain, "acme", now.plusSeconds(60)))
        clock.ran("m", scheduled, "acme", now)
        assertTrue(clock.mayBeDue("m", scheduled, "acme", now.plusSeconds(60)))
    }

    @Test
    fun longRunningDefaultsToFalseAndTrailingLambdaStillBindsToRun() {
        assertFalse(ModuleJob("a", Duration.ofMinutes(1)) {}.longRunning)
        assertTrue(ModuleJob("b", Duration.ofMinutes(1), longRunning = true, run = {}).longRunning)
    }

    @Test
    fun aLongJobIsNotStartedWhileItsPreviousRunIsInProgress() {
        val guard = ModuleJobRunGuard()
        val key = "empleados/import-runner/acme"
        assertTrue(guard.tryStart(key))
        assertTrue(guard.isRunning(key))
        assertFalse(guard.tryStart(key))
        assertTrue(guard.tryStart("empleados/import-runner/other"))
        guard.finish(key)
        assertFalse(guard.isRunning(key))
        assertTrue(guard.tryStart(key))
    }
}
