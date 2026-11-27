package com.ideasdeveloper.bms.modules.empleados

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Empleados writes its activity rows (ACTIVITY_LOG.md §4) for every write operation: fichas, sedes y departamentos,
 * horario habitual, turnos, ausencias, la importacion y la herramienta del agente. Corre sobre un PostgreSQL
 * DESECHABLE (BMS_ACTIVITY_PG_URL o BMS_SERVER_ADMINS_PG_URL, jdbc:postgresql://host:port/postgres?user=postgres):
 * crea y borra la base bms_al_empleados. Sin esa variable las pruebas no hacen nada.
 */
class EmpleadosActivityPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val database = "bms_al_empleados"
    private val ana = ActivityLogTesting.user("Ana Ruiz", UUID.fromString("00000000-0000-0000-0000-00000000a0a0"))
    private val beto = ActivityLogTesting.user("Beto Paz", UUID.fromString("00000000-0000-0000-0000-00000000b0b0"))
    private val access = ActivityLogTesting.accessWithoutDatabase()
    private val day = LocalDate.of(2026, 12, 7)

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private fun urlOf(base: String, name: String) = base.replace(Regex("/[^/?]+(\\?|$)"), "/$name$1")
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    /** Base desechable con las tablas de la empresa (Core y tenant V011) y las del modulo; [block] con su conexion. */
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
            File(root, "modules/empleados/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            // Dos cuentas activas: Ana administra fichas; Beto tendra una ficha enlazada para pedir ausencias.
            listOf(ana, beto).forEach { user ->
                db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('${user.userId}', 'role_${user.name.replace(' ', '_').lowercase()}', TRUE, '${user.name}', 'x@example.com')")
            }
            block(db)
        }
    }

    private fun manager(actor: ActivityActor = ana) = ActivityLogTesting.permissions(granted = setOf(EmpleadosModule.Manage, EmpleadosModule.View), actor = actor)

    private fun employee(name: String, userId: UUID? = null) = EmployeeInput(fullName = name, jobTitle = "Técnico", userId = userId)

    private fun shiftOf(employeeId: UUID, date: LocalDate, start: String, end: String) =
        ShiftInput(employeeId, date, ShiftKind.Work, LocalTime.parse(start), LocalTime.parse(end))

    @Test
    fun createAndEditEmployeeWriteFieldDiffs() = withTenant { db ->
        val permissions = manager()
        val scope = EmpleadosService.scope(db, permissions)
        val (id, created) = ActivityLogTesting.capture { EmpleadosService.createEmployee(db, scope, employee("Luis Mora")) }
        assertEquals(1, created.size)
        assertEquals(ActivityActions.Created, created.single().action)
        assertEquals("Ana Ruiz", created.single().display)
        assertEquals("Luis Mora", created.single().entry.entityLabel)
        assertEquals("Luis Mora", created.single().change("fullName")!!.new)

        val edited = EmployeeInput(fullName = "Luis Mora", jobTitle = "Supervisor")
        val (_, changed) = ActivityLogTesting.capture { EmpleadosService.updateEmployee(db, scope, id, edited) }
        val row = changed.single()
        assertEquals(ActivityActions.Updated, row.action)
        assertEquals("Técnico", row.change("jobTitle")!!.old)
        assertEquals("Supervisor", row.change("jobTitle")!!.new)
        assertEquals(null, row.change("fullName"))

        // Guardar sin cambios no escribe fila.
        val (_, unchanged) = ActivityLogTesting.capture { EmpleadosService.updateEmployee(db, scope, id, edited) }
        assertTrue(unchanged.isEmpty())
    }

    @Test
    fun deactivatingAnEmployeeIsAStatusChange() = withTenant { db ->
        val scope = EmpleadosService.scope(db, manager())
        val id = EmpleadosService.createEmployee(db, scope, employee("Luis Mora"))
        val (_, rows) = ActivityLogTesting.capture { EmpleadosService.setActive(db, scope, id, active = false) }
        val row = rows.single()
        assertEquals(ActivityActions.StatusChanged, row.action)
        assertEquals("Activo", row.change("status")!!.old)
        assertEquals("Baja", row.change("status")!!.new)
    }

    @Test
    fun catalogSettingsWriteRowsAndArchiveIsRecorded() = withTenant { db ->
        val scope = EmpleadosService.scope(db, manager())
        val (siteId, added) = ActivityLogTesting.capture { EmpleadosService.addCatalog(db, scope, Catalog.Sites, "Norte") }
        assertEquals(ActivityActions.Created, added.single().action)
        val (_, renamed) = ActivityLogTesting.capture { EmpleadosService.renameCatalog(db, scope, Catalog.Sites, siteId, "Norte centro") }
        assertEquals("Norte", renamed.single().change("name")!!.old)
        assertEquals("Norte centro", renamed.single().change("name")!!.new)
        val (_, archived) = ActivityLogTesting.capture { EmpleadosService.archiveCatalog(db, scope, Catalog.Sites, siteId) }
        assertEquals(ActivityActions.Archived, archived.single().action)
    }

    @Test
    fun shiftCreateEditAndDeleteEachWriteARow() = withTenant { db ->
        val scope = EmpleadosService.scope(db, manager())
        val employeeId = EmpleadosService.createEmployee(db, scope, employee("Luis Mora"))
        val (shiftId, created) = ActivityLogTesting.capture { EmpleadosService.createShift(db, access, scope, shiftOf(employeeId, day, "08:00", "16:00")) }
        assertEquals(ActivityActions.Created, created.single().action)
        assertEquals("Turno de Luis Mora", created.single().entry.entityLabel)

        val patch = ShiftPatch(start = LocalTime.parse("09:00"))
        val (_, moved) = ActivityLogTesting.capture { EmpleadosService.moveShift(db, access, scope, shiftId, patch) }
        assertEquals("08:00", moved.single().change("start")!!.old)
        assertEquals("09:00", moved.single().change("start")!!.new)

        val (_, deleted) = ActivityLogTesting.capture { EmpleadosService.deleteShift(db, access, scope, shiftId) }
        assertEquals(ActivityActions.Deleted, deleted.single().action)
        assertEquals("09:00", deleted.single().change("start")!!.old)
    }

    @Test
    fun dayOffRemovesShiftsOfTheDayWithARowBeforeEachDelete() = withTenant { db ->
        val scope = EmpleadosService.scope(db, manager())
        val employeeId = EmpleadosService.createEmployee(db, scope, employee("Luis Mora"))
        val shiftId = EmpleadosService.createShift(db, access, scope, shiftOf(employeeId, day, "08:00", "16:00"))
        val (_, rows) = ActivityLogTesting.capture { EmpleadosService.setDayOff(db, access, scope, employeeId, day) }
        assertEquals(listOf(ActivityActions.Deleted, ActivityActions.Created), rows.map { it.action })
        assertEquals(shiftId.toString(), rows.first().entry.entityId)
        assertEquals("08:00", rows.first().change("start")!!.old)
    }

    @Test
    fun weeklySlotAddAndRemoveAreRecorded() = withTenant { db ->
        val scope = EmpleadosService.scope(db, manager())
        val employeeId = EmpleadosService.createEmployee(db, scope, employee("Luis Mora"))
        val (_, added) = ActivityLogTesting.capture {
            EmpleadosService.addWeeklySlot(db, access, scope, employeeId, 1, LocalTime.parse("08:00"), LocalTime.parse("12:00"), null)
        }
        assertEquals(ActivityActions.Created, added.single().action)
        assertEquals("Horario de Luis Mora", added.single().entry.entityLabel)
        val slotId = UUID.fromString(added.single().entry.entityId)
        val (_, removed) = ActivityLogTesting.capture { EmpleadosService.removeWeeklySlot(db, access, scope, slotId) }
        assertEquals(ActivityActions.Deleted, removed.single().action)
        assertEquals("12:00", removed.single().change("end")!!.old)
    }

    @Test
    fun absenceRequestCancelApproveAndRegisterWhoDecided() = withTenant { db ->
        val managerScope = EmpleadosService.scope(db, manager())
        val betoId = EmpleadosService.createEmployee(db, managerScope, employee("Beto Paz", userId = beto.userId))
        val betoPermissions = ActivityLogTesting.permissions(granted = setOf(EmpleadosModule.View), actor = beto)
        val betoScope = EmpleadosService.scope(db, betoPermissions)

        val (requestId, requested) = ActivityLogTesting.capture {
            EmpleadosService.requestAbsence(db, access, betoScope, AbsenceKind.Vacation, day, day.plusDays(2), "Viaje")
        }
        assertEquals("Beto Paz", requested.single().display)
        assertEquals("Pendiente", requested.single().change("status")!!.new)

        val (_, approved) = ActivityLogTesting.capture { EmpleadosService.decideAbsence(db, access, managerScope, requestId, true, "Bien") }
        val decision = approved.single()
        assertEquals(ActivityActions.StatusChanged, decision.action)
        assertEquals("Ana Ruiz", decision.display)
        assertEquals("Aprobada", decision.change("status")!!.new)
        assertEquals("Bien", decision.change("note")!!.new)

        val (second, _) = ActivityLogTesting.capture {
            EmpleadosService.requestAbsence(db, access, betoScope, AbsenceKind.Leave, day.plusDays(10), day.plusDays(10), "")
        }
        val (_, cancelled) = ActivityLogTesting.capture { EmpleadosService.cancelAbsence(db, betoScope, second) }
        assertEquals("Cancelada", cancelled.single().change("status")!!.new)
        assertNotNull(betoId)
    }

    @Test
    fun agentToolWritesRowAttributedToTheUserBehindTheAgent() = withTenant { db ->
        val scope = EmpleadosService.scope(db, manager())
        val employeeId = EmpleadosService.createEmployee(db, scope, employee("Luis Mora"))
        val tool = EmpleadosMcpTools.mcp.tools.first { it.name == "create_shift" }
        val arguments = JsonObject(
            mapOf(
                "employee_id" to JsonPrimitive(employeeId.toString()),
                "date" to JsonPrimitive(day.toString()),
                "start" to JsonPrimitive("08:00"),
                "end" to JsonPrimitive("16:00"),
            ),
        )
        val call = McpToolCall(arguments, manager().throughAgent("Claude"), db, access, "Claude", EmpleadosModule.id)
        val (_, rows) = ActivityLogTesting.capture { tool.handler(call) }
        val row = rows.single()
        assertTrue(row.display.startsWith("Agente de "), row.display)
        assertEquals("Agente de Ana Ruiz", row.display)
        assertEquals(ActivityActions.Created, row.action)
    }

    @Test
    fun importedRowsAreAttributedToTheImport() = withTenant { db ->
        val startedBy = ana
        val row = ImportedEmployee(key = "monday:1", fullName = "Marta Gil", jobTitle = "Cocinera", site = "Centro", weekly = listOf(ImportedSlot(1, LocalTime.of(8, 0), LocalTime.of(12, 0))))
        val (first, created) = ActivityLogTesting.capture { EmpleadosImportApply.apply(db, row, emptyMap(), null, ActivityActor.import(startedBy)) }
        assertTrue(first is EmpleadosImportOutcome.Created)
        assertEquals(setOf("employee", "site", "weekly_schedule"), created.map { it.entry.entityType }.toSet())
        assertTrue(created.all { it.display == "Importacion (Ana Ruiz)" })
        assertEquals(ActivityActions.Created, created.first { it.entry.entityType == "employee" }.action)

        val update = row.copy(jobTitle = "Jefa de cocina")
        val (_, updated) = ActivityLogTesting.capture { EmpleadosImportApply.apply(db, update, emptyMap(), null, ActivityActor.import(startedBy)) }
        val change = updated.single { it.entry.entityType == "employee" }
        assertEquals("Cocinera", change.change("jobTitle")!!.old)
        assertEquals("Jefa de cocina", change.change("jobTitle")!!.new)
    }

    @Test
    fun importActorNamesTheStarterAndTheTotalRowSaysWhoAndWhere() = withTenant { db ->
        val activity = EmpleadosImportApply.importActor(db, ana.userId)
        assertEquals("Importacion (Ana Ruiz)", activity.display)
        assertEquals(ana.userId, activity.userId)
        // Si la fila del usuario ya no existe, el nombre es "Usuario desconocido".
        assertEquals("Importacion (Usuario desconocido)", EmpleadosImportApply.importActor(db, UUID.fromString("00000000-0000-0000-0000-0000000000ff")).display)
        assertEquals("Importacion", EmpleadosImportApply.importActor(db, null).display)

        val row = ImportedEmployee(key = "monday:7", fullName = "Pablo Soto", jobTitle = "Mesero")
        val (_, perRecord) = ActivityLogTesting.capture { EmpleadosImportApply.apply(db, row, emptyMap(), ana.userId, activity) }
        assertTrue(perRecord.all { it.display == "Importacion (Ana Ruiz)" })

        val (_, rows) = ActivityLogTesting.capture { EmpleadosImportApply.recordImportTotal(db, activity, null, "Monday", "Personal", 4, 1, 0, 2) }
        val total = rows.single()
        assertEquals(ActivityActions.Imported, total.action)
        assertEquals("Importacion (Ana Ruiz)", total.display)
        assertEquals("4", total.change("created")!!.new)
        assertEquals("2", total.change("failed")!!.new)
        assertTrue(total.entry.summary.contains("Monday") && total.entry.summary.contains("Personal"), total.entry.summary)

        // Escrita de verdad, queda en activity_log con quien la empezo.
        EmpleadosImportApply.recordImportTotal(db, activity, null, "Monday", "", 1, 0, 0, 0)
        db.prepareStatement("SELECT actor_kind, actor_user_id, actor_display, action FROM activity_log WHERE module_id = 'empleados' AND entity_type = 'import'").use { st ->
            st.executeQuery().use { result ->
                assertTrue(result.next())
                assertEquals("import", result.getString(1)); assertEquals(ana.userId, result.getObject(2, UUID::class.java))
                assertEquals("Importacion (Ana Ruiz)", result.getString(3)); assertEquals("imported", result.getString(4))
            }
        }
    }

    @Test
    fun rowsArePersistedAndSurviveTheDelete() = withTenant { db ->
        // Sin captura: la fila se escribe de verdad en activity_log, en la misma transaccion.
        val scope = EmpleadosService.scope(db, manager())
        val employeeId = EmpleadosService.createEmployee(db, scope, employee("Luis Mora"))
        val shiftId = EmpleadosService.createShift(db, access, scope, shiftOf(employeeId, day, "08:00", "16:00"))
        EmpleadosService.deleteShift(db, access, scope, shiftId)
        db.prepareStatement("SELECT count(*) FROM empleados_shifts WHERE id = ?").use { st ->
            st.setObject(1, shiftId)
            st.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
        }
        db.prepareStatement("SELECT actor_display, action, entity_type FROM activity_log WHERE entity_id = ? ORDER BY id").use { st ->
            st.setString(1, shiftId.toString())
            st.executeQuery().use { rows ->
                val seen = buildList { while (rows.next()) add(Triple(rows.getString(1), rows.getString(2), rows.getString(3))) }
                assertEquals(listOf(Triple("Ana Ruiz", "created", "shift"), Triple("Ana Ruiz", "deleted", "shift")), seen)
            }
        }
    }
}
