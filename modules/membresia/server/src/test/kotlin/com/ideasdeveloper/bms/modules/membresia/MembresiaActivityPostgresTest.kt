package com.ideasdeveloper.bms.modules.membresia

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Membresias writes its activity rows (ACTIVITY_LOG.md §4) for every write operation: planes, membresias (venta,
 * renovacion, edicion, cancelacion), visitas, pases de invitado, ajustes, clientes creados desde el modulo, las tareas
 * programadas y la importacion. Corre sobre un PostgreSQL DESECHABLE (BMS_ACTIVITY_PG_URL o BMS_SERVER_ADMINS_PG_URL,
 * jdbc:postgresql://host:port/postgres?user=postgres): crea y borra la base bms_al_membresia. Sin esa variable no hace nada.
 */
class MembresiaActivityPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val database = "bms_al_membresia"
    private val ana = ActivityLogTesting.user("Ana Ruiz", UUID.fromString("00000000-0000-0000-0000-00000000a0a0"))
    private val access = ActivityLogTesting.accessWithoutDatabase()
    private val today = LocalDate.now()

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private fun urlOf(base: String, name: String) = base.replace(Regex("/[^/?]+(\\?|$)"), "/$name$1")
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    /** Base desechable: Core y tenant V011, la tabla de clientes completa y las migraciones del modulo. */
    private fun withTenant(block: (Connection) -> Unit) {
        val base = url ?: return
        DriverManager.getConnection(base).use { admin ->
            admin.run("DROP DATABASE IF EXISTS $database WITH (FORCE)")
            admin.run("CREATE DATABASE $database")
        }
        DriverManager.getConnection(urlOf(base, database)).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials", "V011__create_activity_log")
                .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
            File(root, "modules/clientes/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            File(root, "modules/membresia/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('${ana.userId}', 'role_ana', TRUE, 'Ana Ruiz', 'ana@example.com')")
            block(db)
        }
    }

    private fun permissions() = ActivityLogTesting.permissions(
        granted = setOf(MembresiaModule.View, MembresiaModule.Visits, MembresiaModule.Manage, MembresiaModule.Plans),
        actor = ana,
    )

    private fun planInput(name: String = "Mensual", guestPasses: Int = 2) =
        PlanInput(name = name, price = BigDecimal("50.00"), durationDays = 30, includedVisits = 10, guestPasses = guestPasses)

    private fun plan(db: Connection, name: String = "Mensual"): Plan = MembresiaRepository.savePlan(db, null, planInput(name), ana)

    private fun sell(db: Connection, plan: Plan, name: String = "Luis Mora", email: String = "luis@example.com"): Membership =
        MembresiaService.create(access, db, MembershipInput(customerId = UUID(0, 0), planId = plan.id), NewCustomerInput(name, email, "555"), "Ana Ruiz", ana, today)

    @Test
    fun planCreateEditAndDeactivateWriteRows() = withTenant { db ->
        val (created, added) = ActivityLogTesting.capture { MembresiaRepository.savePlan(db, null, planInput(), ana) }
        assertEquals(ActivityActions.Created, added.single().action)
        assertEquals("Ana Ruiz", added.single().display)
        assertEquals("Mensual", added.single().entry.entityLabel)
        assertEquals("50.00", added.single().change("price")!!.new)

        val (_, edited) = ActivityLogTesting.capture { MembresiaRepository.savePlan(db, created.id, planInput().copy(price = BigDecimal("60.00")), ana) }
        assertEquals(ActivityActions.Updated, edited.single().action)
        assertEquals("50.00", edited.single().change("price")!!.old)
        assertEquals("60.00", edited.single().change("price")!!.new)
        assertNull(edited.single().change("name"))

        val (_, unchanged) = ActivityLogTesting.capture { MembresiaRepository.savePlan(db, created.id, planInput().copy(price = BigDecimal("60.00")), ana) }
        assertTrue(unchanged.isEmpty())

        val (_, off) = ActivityLogTesting.capture { MembresiaRepository.setPlanActive(db, created.id, false, ana) }
        assertEquals(ActivityActions.Archived, off.single().action)
        val (_, on) = ActivityLogTesting.capture { MembresiaRepository.setPlanActive(db, created.id, true, ana) }
        assertEquals(ActivityActions.Restored, on.single().action)
    }

    @Test
    fun aRefusedPlanWritesNoRow() = withTenant { db ->
        plan(db)
        val (outcome, rows) = ActivityLogTesting.capture { runCatching { MembresiaRepository.savePlan(db, null, planInput(), ana) } }
        assertTrue(outcome.exceptionOrNull() is MembresiaValidationException)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun sellingCreatesTheCustomerAndTheMembershipThenEditRenewAndCancel() = withTenant { db ->
        val plan = plan(db)
        val (membership, sold) = ActivityLogTesting.capture { sell(db, plan) }
        assertEquals(listOf("customer", "membership"), sold.map { it.entry.entityType })
        assertTrue(sold.all { it.display == "Ana Ruiz" })
        val row = sold.last()
        assertEquals(ActivityActions.Created, row.action)
        assertEquals("Mensual", row.change("plan")!!.new)
        assertEquals("Luis Mora", row.change("customer")!!.new)
        assertEquals(membership.id.toString(), row.entry.entityId)

        // Un cliente que ya existe (mismo correo) no se vuelve a crear.
        val annual = plan(db, "Anual")
        val (_, again) = ActivityLogTesting.capture { sell(db, annual, email = "luis@example.com") }
        assertEquals(listOf("membership"), again.map { it.entry.entityType })

        val edit = MembershipEdit(membership.endsOn.plusDays(5), BigDecimal("55.00"), unit = "Oficina 3")
        val (_, edited) = ActivityLogTesting.capture { MembresiaService.edit(db, membership.id, edit, ana, today) }
        val change = edited.single()
        assertEquals(ActivityActions.Updated, change.action)
        assertEquals("50.00 USD", change.change("amount")!!.old)
        assertEquals("55.00 USD", change.change("amount")!!.new)
        assertEquals("Oficina 3", change.change("unit")!!.new)
        assertNull(change.change("plan"))

        val (renewed, renewal) = ActivityLogTesting.capture { MembresiaService.renew(db, membership.id, RenewalInput(), "Ana Ruiz", ana, today) }
        assertNotNull(renewed)
        assertEquals(ActivityActions.Created, renewal.single().action)
        assertTrue(renewal.single().entry.summary.startsWith("Renueva"), renewal.single().entry.summary)
        assertNotNull(renewal.single().change("renewedFrom")!!.new)

        val (_, cancelled) = ActivityLogTesting.capture { MembresiaService.cancel(db, renewed.id, "Se mudó", ana) }
        assertEquals(ActivityActions.StatusChanged, cancelled.single().action)
        assertEquals("Cancelada", cancelled.single().change("status")!!.new)
        assertEquals("Se mudó", cancelled.single().change("cancelReason")!!.new)
        // Cancelar otra vez no escribe nada.
        val (second, repeated) = ActivityLogTesting.capture { MembresiaService.cancel(db, renewed.id, "otra vez", ana) }
        assertNull(second)
        assertTrue(repeated.isEmpty())
    }

    @Test
    fun visitsAndGuestPassesAreRecorded() = withTenant { db ->
        val plan = plan(db)
        val membership = sell(db, plan)
        val (first, visit) = ActivityLogTesting.capture { MembresiaService.checkIn(db, membership.customerId, false, "Ana Ruiz", ana, today, "Entró temprano") }
        assertTrue((first as CheckInOutcome.Done).result is VisitResult.Recorded)
        assertEquals("visit", visit.single().entry.entityType)
        assertEquals(ActivityActions.Created, visit.single().action)
        assertEquals("Entró temprano", visit.single().change("note")!!.new)

        // Un doble toque no cuenta dos veces ni escribe otra fila.
        val (_, twice) = ActivityLogTesting.capture { MembresiaService.checkIn(db, membership.customerId, false, "Ana Ruiz", ana, today) }
        assertTrue(twice.isEmpty())

        val (_, guest) = ActivityLogTesting.capture { MembresiaService.guestPass(db, membership.customerId, "Marta Gil", "Ana Ruiz", ana, today) }
        assertEquals("guest_pass", guest.single().entry.entityType)
        assertEquals("Marta Gil", guest.single().change("guest")!!.new)
        assertEquals("1", guest.single().change("guestPassesLeft")!!.new)
    }

    @Test
    fun settingsChangeWritesRowWithoutSecretsAndASettingThatDidNotChangeWritesNone() = withTenant { db ->
        val before = MembresiaRepository.settings(db)
        val next = MembresiaSettings("EUR", before.expiringDays, before.sources + "Evento")
        val (_, rows) = ActivityLogTesting.capture { MembresiaRepository.saveSettings(db, next, ana) }
        val row = rows.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertEquals("settings", row.entry.entityType)
        assertEquals("USD", row.change("currency")!!.old)
        assertEquals("EUR", row.change("currency")!!.new)
        assertNull(row.change("expiringDays"))
        assertTrue(row.entry.changes.none { it.secret })
        val (_, same) = ActivityLogTesting.capture { MembresiaRepository.saveSettings(db, next, ana) }
        assertTrue(same.isEmpty())
    }

    @Test
    fun agentToolsWriteRowsAttributedToTheUserBehindTheAgent() = withTenant { db ->
        val plan = plan(db)
        val membership = sell(db, plan)
        val agent = permissions().throughAgent("Claude")
        val visitTool = MembresiaMcpTools.baseTools.first { it.name == "record_visit" }
        val visitCall = McpToolCall(JsonObject(mapOf("customer_id" to JsonPrimitive(membership.customerId.toString()))), agent, db, access, "Claude", MembresiaModule.Id)
        val (_, visit) = ActivityLogTesting.capture { visitTool.handler(visitCall) }
        assertTrue(visit.single().display.startsWith("Agente de "), visit.single().display)
        assertEquals("Agente de Ana Ruiz", visit.single().display)
        assertEquals("Claude", visit.single().actor.agentName)

        val createTool = MembresiaMcpTools.baseTools.first { it.name == "create_membership" }
        val other = plan(db, "Anual")
        val customer = MembresiaRepository.findOrCreateCustomer(db, NewCustomerInput("Beto Paz", "beto@example.com"), ana)
        val createArgs = JsonObject(mapOf("customer_id" to JsonPrimitive(customer.customerId.toString()), "plan_id" to JsonPrimitive(other.id.toString())))
        val (_, created) = ActivityLogTesting.capture { createTool.handler(McpToolCall(createArgs, agent, db, access, "Claude", MembresiaModule.Id)) }
        assertEquals("Agente de Ana Ruiz", created.single().display)
        assertEquals(ActivityActions.Created, created.single().action)

        val renewTool = MembresiaMcpTools.baseTools.first { it.name == "renew_membership" }
        val renewArgs = JsonObject(mapOf("membership_id" to JsonPrimitive(membership.id.toString())))
        val (_, renewed) = ActivityLogTesting.capture { renewTool.handler(McpToolCall(renewArgs, agent, db, access, "Claude", MembresiaModule.Id)) }
        assertEquals("Agente de Ana Ruiz", renewed.single().display)
    }

    @Test
    fun everyWriteToolIsCoveredByTheTests() {
        // Si se agrega una herramienta que cambia datos, este test obliga a cubrirla aqui.
        val covered = setOf("record_visit", "create_membership", "renew_membership")
        assertEquals(covered, MembresiaMcpTools.baseTools.filterNot { it.readOnly }.map { it.name }.toSet())
    }

    @Test
    fun theOnlyWriteRouteIsTheActionPostThatTheTestsCover() {
        // Todas las acciones (checkin, guest, crear, renovar, editar, cancelar, plan-*, ajustes-guardar, importar) entran por este POST.
        assertEquals(listOf("POST ${MembresiaModule.UiRoute}/{screen}"), ActivityLogTesting.writeRoutes(MembresiaServerModule))
    }

    @Test
    fun dailyJobsAreAttributedToTheScheduledTask() = withTenant { db ->
        val plan = plan(db)
        val started = sell(db, plan)
        // Una programada que ya empezo y una activa vencida.
        db.run("UPDATE membresia_memberships SET status = 'programada' WHERE id = '${started.id}'")
        val (activated, activation) = ActivityLogTesting.capture { MembresiaRepository.activateStarted(db, today, 500, ActivityActor.job("Vencimiento de membresías")) }
        assertEquals(1, activated)
        assertEquals("Tarea programada Vencimiento de membresías", activation.single().display)
        assertEquals("Programada", activation.single().change("status")!!.old)
        assertEquals("Activa", activation.single().change("status")!!.new)

        db.run("UPDATE membresia_memberships SET starts_on = starts_on - 60, ends_on = ends_on - 60 WHERE id = '${started.id}'")
        val (expired, expiry) = ActivityLogTesting.capture { MembresiaRepository.expireEnded(db, today, 500, ActivityActor.job("Vencimiento de membresías")) }
        assertEquals(1, expired.size)
        assertEquals("Vencida", expiry.single().change("status")!!.new)

        val other = sell(db, plan, "Beto Paz", "beto@example.com")
        val (_, notified) = ActivityLogTesting.capture { MembresiaRepository.markExpiringNotified(db, other, today, ActivityActor.job("Avisos de vencimiento de membresías")) }
        assertEquals("Tarea programada Avisos de vencimiento de membresías", notified.single().display)
        assertEquals(today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")), notified.single().change("expiringNotified")!!.new)
    }

    @Test
    fun importedRowsAreAttributedToTheImportAndWriteOneTotalRow() = withTenant { db ->
        val startedBy = ana
        val activity = MembresiaImportApply.importActor(db, ana.userId)
        assertEquals("Importacion (Ana Ruiz)", activity.display)
        // Un usuario que ya no existe se muestra como desconocido.
        val ghost = MembresiaImportApply.importActor(db, UUID.fromString("00000000-0000-0000-0000-0000000000ff"))
        assertEquals("Importacion (Usuario desconocido)", ghost.display)
        assertEquals("Importacion", MembresiaImportApply.importActor(db, null).display)

        val (plans, planRows) = ActivityLogTesting.capture {
            MembresiaImportApply.ensurePlans(db, listOf(ImportPlanProposal("Coworking", BigDecimal("80.00"), 30, 1)), activity)
        }
        assertEquals(1, planRows.size)
        assertEquals("Importacion (Ana Ruiz)", planRows.single().display)
        val (fields, fieldRows) = ActivityLogTesting.capture { MembresiaImportApply.ensureCustomerFields(db, listOf("Documento"), activity) }
        assertEquals(setOf("customer_field"), fieldRows.map { it.entry.entityType }.toSet())

        val row = ImportedMember(
            key = "monday:1", name = "Marta Gil", email = "marta@example.com", planName = "Coworking", startsOn = today.minusDays(5), endsOn = today.plusDays(25),
            amount = BigDecimal("80.00"), customerFields = mapOf("Documento" to "X123"),
            visits = listOf(ImportedVisit("v1", today.minusDays(2)), ImportedVisit("v2", today.minusDays(1))),
        )
        val (outcome, rows) = ActivityLogTesting.capture { MembresiaImportApply.apply(access, db, row, plans, fields.keys, "Ana Ruiz", today, activity) }
        assertTrue(outcome is ImportOutcome.Created)
        assertEquals(listOf("customer", "customer", "membership", "visit"), rows.map { it.entry.entityType })
        assertTrue(rows.all { it.display == "Importacion (Ana Ruiz)" })
        assertEquals("import", rows.first().actor.kind)
        assertEquals(startedBy.userId, rows.first().actor.userId)
        assertEquals("2", rows.last().change("count")!!.new)

        // Importar la misma fila otra vez no repite nada.
        val (second, again) = ActivityLogTesting.capture { MembresiaImportApply.apply(access, db, row, plans, fields.keys, "Ana Ruiz", today, activity) }
        assertTrue(second is ImportOutcome.Updated)
        assertTrue(again.isEmpty())

        val (_, total) = ActivityLogTesting.capture { MembresiaImportApply.recordImportTotal(db, activity, null, "Monday", "Miembros", 1, 0, 0, 0) }
        val totalRow = total.single()
        assertEquals(ActivityActions.Imported, totalRow.action)
        assertEquals("Importacion (Ana Ruiz)", totalRow.display)
        assertEquals("1", totalRow.change("created")!!.new)
        assertTrue(totalRow.entry.summary.contains("Monday"), totalRow.entry.summary)
    }

    @Test
    fun rowsArePersistedInTheSameTransactionAndRollBackWithTheChange() = withTenant { db ->
        // Sin captura: las filas se escriben de verdad en activity_log.
        val plan = plan(db)
        val membership = sell(db, plan)
        MembresiaService.cancel(db, membership.id, "Adiós", ana)
        db.prepareStatement("SELECT actor_display, action, entity_type FROM activity_log WHERE module_id = 'membresia' AND entity_id = ? ORDER BY id").use { st ->
            st.setString(1, membership.id.toString())
            st.executeQuery().use { rows ->
                val seen = buildList { while (rows.next()) add(Triple(rows.getString(1), rows.getString(2), rows.getString(3))) }
                assertEquals(listOf(Triple("Ana Ruiz", "created", "membership"), Triple("Ana Ruiz", "status_changed", "membership")), seen)
            }
        }
        // Si la fila no se puede escribir, el cambio tampoco queda.
        db.run("ALTER TABLE activity_log RENAME TO activity_log_off")
        val failure = runCatching { MembresiaRepository.savePlan(db, null, planInput("Trimestral"), ana) }
        db.run("ALTER TABLE activity_log_off RENAME TO activity_log")
        assertNotNull(failure.exceptionOrNull())
        assertEquals(1, MembresiaRepository.plans(db, includeInactive = true).size)
    }
}
