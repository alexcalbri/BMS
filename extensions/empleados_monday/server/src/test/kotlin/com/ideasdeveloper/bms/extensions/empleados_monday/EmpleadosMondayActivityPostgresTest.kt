package com.ideasdeveloper.bms.extensions.empleados_monday

import com.ideasdeveloper.bms.modules.empleados.EmpleadosImportJobContext
import com.ideasdeveloper.bms.modules.empleados.EmpleadosModule
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.security.SecretBox
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La extension empleados_monday escribe en el registro de actividad (ACTIVITY_LOG.md §4): el token de monday sin su valor,
 * el inicio, la confirmacion y la cancelacion de una importacion, la limpieza de importaciones antiguas, y pasa quien empezo
 * la importacion al modulo (filas por ficha y fila de totales). Corre sobre un PostgreSQL DESECHABLE (BMS_ACTIVITY_PG_URL o
 * BMS_SERVER_ADMINS_PG_URL): crea y borra la base bms_al_empleados_monday. Sin esa variable no hace nada.
 */
class EmpleadosMondayActivityPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val database = "bms_al_empleados_monday"
    private val ana = ActivityLogTesting.user("Ana Ruiz", UUID.fromString("00000000-0000-0000-0000-00000000a0a0"))
    private val access = ActivityLogTesting.accessWithoutDatabase()
    private val secrets = object : SecretBox {
        override val available = true
        override fun seal(tenant: Connection, plain: String) = SecretBox.SealedPrefix + plain.reversed()
        override fun open(tenant: Connection, value: String) = value.removePrefix(SecretBox.SealedPrefix).reversed()
    }

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private fun urlOf(base: String, name: String) = base.replace(Regex("/[^/?]+(\\?|$)"), "/$name$1")
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private fun withTenant(block: (Connection) -> Unit) {
        val base = url ?: return
        DriverManager.getConnection(base).use { admin ->
            admin.run("DROP DATABASE IF EXISTS $database WITH (FORCE)")
            admin.run("CREATE DATABASE $database")
        }
        DriverManager.getConnection(urlOf(base, database)).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials", "V006__create_extensions", "V011__create_activity_log")
                .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
            File(root, "modules/empleados/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            File(root, "extensions/empleados_monday/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('${ana.userId}', 'role_ana', TRUE, 'Ana Ruiz', 'ana@example.com')")
            db.run("INSERT INTO tenant_extensions (extension_id, module_id, version, status, enabled_at) VALUES ('empleados_monday', 'empleados', '1.2.0', 'enabled', now())")
            block(db)
        }
    }

    private fun permissions() = ActivityLogTesting.permissions(granted = setOf(EmpleadosImportPermission, EmpleadosManagePermission), actor = ana)

    private fun insertJob(db: Connection, status: String, createdBy: UUID?, rows: List<MondayRowDto> = emptyList(), age: String = "0 days"): UUID {
        val id = UUID.randomUUID()
        db.prepareStatement(
            "INSERT INTO ext_empleados_monday__jobs (id, board_id, board_name, status, total, created_by, updated_at) VALUES (?, '123', 'Personal', ?, ?, ?, now() - CAST(? AS interval))",
        ).use { st ->
            st.setObject(1, id); st.setString(2, status); st.setInt(3, rows.size); st.setObject(4, createdBy); st.setString(5, age)
            st.executeUpdate()
        }
        rows.forEachIndexed { index, row ->
            db.prepareStatement("INSERT INTO ext_empleados_monday__rows (job_id, row_no, item_id, payload) VALUES (?, ?, ?, CAST(? AS jsonb))").use { st ->
                st.setObject(1, id); st.setInt(2, index + 1); st.setString(3, "item${index + 1}")
                st.setString(4, Json.encodeToString(MondayRowDto.serializer(), row))
                st.executeUpdate()
            }
        }
        return id
    }

    @Test
    fun theTokenIsRecordedWithoutItsValue() = withTenant { db ->
        val (_, saved) = ActivityLogTesting.capture { EmpleadosMondayFlow.saveToken(secrets, db, ana, "tok-SECRET-123") }
        val row = saved.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertEquals("settings", row.entry.entityType)
        assertEquals("empleados", row.entry.moduleId)
        assertTrue(row.change("token")!!.secret)
        assertNull(row.change("token")!!.new)
        assertFalse(row.toString().contains("tok-SECRET"), row.toString())
        assertEquals("Guarda el token de la API de monday.", row.entry.summary)

        val (_, changed) = ActivityLogTesting.capture { EmpleadosMondayFlow.saveToken(secrets, db, ana, "otro-token") }
        assertEquals("Cambia el token de la API de monday.", changed.single().entry.summary)

        val (_, forgotten) = ActivityLogTesting.capture { EmpleadosMondayFlow.forgetToken(secrets, db, ana) }
        assertEquals("Borra el token de la API de monday.", forgotten.single().entry.summary)
        val (_, again) = ActivityLogTesting.capture { EmpleadosMondayFlow.forgetToken(secrets, db, ana) }
        assertTrue(again.isEmpty())

        // Escrito de verdad: ni el token ni su version sellada aparecen en el registro.
        EmpleadosMondayFlow.saveToken(secrets, db, ana, "tok-SECRET-123")
        db.prepareStatement("SELECT count(*) FROM activity_log WHERE changes::text ILIKE '%tok-SECRET%' OR changes::text ILIKE '%321-TERCES%' OR summary ILIKE '%tok-SECRET%' OR summary ILIKE '%321-TERCES%'").use { st ->
            st.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
        }
    }

    @Test
    fun confirmAndCancelAreRecordedByThePersonOrTheirAgent() = withTenant { db ->
        val preview = insertJob(db, "preview", ana.userId, listOf(MondayRowDto("monday:1", "Marta Gil"), MondayRowDto("monday:2", "Pablo Soto")))
        val job = EmpleadosMondayJobs.find(db, preview)!!
        val agent = permissions().throughAgent("Claude")
        val (applied, confirm) = ActivityLogTesting.capture { EmpleadosMondayFlow.requestApply(db, agent, job) }
        assertTrue(applied)
        assertEquals("Agente de Ana Ruiz", confirm.single().display)
        assertEquals(ActivityActions.StatusChanged, confirm.single().action)
        assertEquals("Aplicando", confirm.single().change("status")!!.new)
        // Ya no esta en revision: no cambia nada ni escribe fila.
        val (second, none) = ActivityLogTesting.capture { EmpleadosMondayFlow.requestApply(db, permissions(), job) }
        assertFalse(second)
        assertTrue(none.isEmpty())

        val (cancelled, rows) = ActivityLogTesting.capture { EmpleadosMondayFlow.cancel(db, permissions(), EmpleadosMondayJobs.find(db, preview)!!) }
        assertTrue(cancelled)
        assertEquals("Ana Ruiz", rows.single().display)
        assertEquals("Aplicando", rows.single().change("status")!!.old)
        assertEquals("Cancelada", rows.single().change("status")!!.new)
    }

    @Test
    fun theRunnerImportsAsTheStarterAndWritesOneTotalRow() = withTenant { db ->
        val job = insertJob(db, "applying", ana.userId, listOf(MondayRowDto("monday:1", "Marta Gil", jobTitle = "Cocinera", site = "Centro"), MondayRowDto("monday:2", "")))
        val context = EmpleadosImportJobContext(access, db, "acme", LocalDate.now())
        val (processed, rows) = ActivityLogTesting.capture { EmpleadosMondayRunner.run(context, Duration.ofSeconds(60)) }
        assertEquals(2, processed)
        val employee = rows.first { it.entry.entityType == "employee" }
        assertEquals("Importacion (Ana Ruiz)", employee.display)
        assertEquals("import", employee.actor.kind)
        assertEquals(ana.userId, employee.actor.userId)
        val total = rows.single { it.action == ActivityActions.Imported }
        assertEquals("Importacion (Ana Ruiz)", total.display)
        assertEquals(job.toString(), total.entry.entityId)
        assertEquals("1", total.change("created")!!.new)
        assertEquals("1", total.change("skipped")!!.new)
        assertTrue(total.entry.summary.contains("Monday") && total.entry.summary.contains("Personal"), total.entry.summary)
        assertEquals("done", EmpleadosMondayJobs.find(db, job)!!.status)
        assertEquals(1, rows.count { it.action == ActivityActions.Imported })
    }

    @Test
    fun anImportStartedByAUserWhoIsGoneIsAttributedToAnUnknownUser() = withTenant { db ->
        val gone = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
        insertJob(db, "applying", gone, listOf(MondayRowDto("monday:9", "Luis Mora")))
        val context = EmpleadosImportJobContext(access, db, "acme", LocalDate.now())
        // Sin captura: las filas se escriben de verdad, en la misma transaccion que el cierre del trabajo.
        EmpleadosMondayRunner.run(context, Duration.ofSeconds(60))
        db.prepareStatement("SELECT actor_display, actor_user_id, entity_type, action FROM activity_log WHERE module_id = 'empleados' ORDER BY id").use { st ->
            st.executeQuery().use { result ->
                val seen = buildList { while (result.next()) add(listOf(result.getString(1), result.getObject(2, UUID::class.java), result.getString(3), result.getString(4))) }
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it[0] == "Importacion (Usuario desconocido)" && it[1] == gone }, seen.toString())
                assertEquals(listOf("employee", "import"), seen.map { it[2] })
                assertEquals("imported", seen.last()[3])
            }
        }
    }

    @Test
    fun cleanupRecordsEachOldImportBeforeDeletingIt() = withTenant { db ->
        val old = insertJob(db, "done", ana.userId, age = "40 days")
        insertJob(db, "done", ana.userId, age = "1 day")
        val (deleted, rows) = ActivityLogTesting.capture { EmpleadosMondayJobs.purgeOldRecorded(db) }
        assertEquals(1, deleted)
        val row = rows.single()
        assertEquals(ActivityActions.Deleted, row.action)
        assertEquals(old.toString(), row.entry.entityId)
        assertTrue(row.display.startsWith("Tarea programada "), row.display)
        assertNull(EmpleadosMondayJobs.find(db, old))

        // Sin captura: la fila del borrado queda aunque el trabajo ya no exista.
        val other = insertJob(db, "failed", ana.userId, age = "50 days")
        assertEquals(1, EmpleadosMondayJobs.purgeOldRecorded(db))
        db.prepareStatement("SELECT count(*) FROM activity_log WHERE entity_id = ? AND action = 'deleted'").use { st ->
            st.setString(1, other.toString())
            st.executeQuery().use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
        }
        assertEquals(EmpleadosModule.id, "empleados")
    }
}
