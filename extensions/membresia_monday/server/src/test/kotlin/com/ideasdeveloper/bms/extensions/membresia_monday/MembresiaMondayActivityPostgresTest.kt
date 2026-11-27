package com.ideasdeveloper.bms.extensions.membresia_monday

import com.ideasdeveloper.bms.modules.membresia.ImportedMember
import com.ideasdeveloper.bms.modules.membresia.ImportedVisit
import com.ideasdeveloper.bms.modules.membresia.MembresiaModule
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.ModuleJobContext
import com.ideasdeveloper.bms.server.security.SecretBox
import java.io.File
import java.math.BigDecimal
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
 * La extension membresia_monday escribe en el registro de actividad (ACTIVITY_LOG.md §4): el token de monday sin su valor,
 * el inicio, la confirmacion y la cancelacion de una importacion, la limpieza de importaciones antiguas, y pasa quien empezo
 * la importacion al modulo (filas por registro y fila de totales). Corre sobre un PostgreSQL DESECHABLE (BMS_ACTIVITY_PG_URL o
 * BMS_SERVER_ADMINS_PG_URL): crea y borra la base bms_al_membresia_monday. Sin esa variable no hace nada.
 */
class MembresiaMondayActivityPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val database = "bms_al_membresia_monday"
    private val ana = ActivityLogTesting.user("Ana Ruiz", UUID.fromString("00000000-0000-0000-0000-00000000a0a0"))
    private val access = ActivityLogTesting.accessWithoutDatabase()
    private val today = LocalDate.now()
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
            File(root, "modules/clientes/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            File(root, "modules/membresia/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            File(root, "extensions/membresia_monday/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('${ana.userId}', 'role_ana', TRUE, 'Ana Ruiz', 'ana@example.com')")
            db.run("INSERT INTO tenant_extensions (extension_id, module_id, version, status, enabled_at) VALUES ('membresia_monday', 'membresia', '1.2.0', 'enabled', now())")
            block(db)
        }
    }

    private fun permissions() = ActivityLogTesting.permissions(
        granted = setOf(MembresiaModule.Import, MembresiaModule.ImportWrite, MembresiaModule.Plans),
        actor = ana,
    )

    private fun member(key: String, name: String) = ImportedMember(
        key = key, name = name, email = "${name.lowercase().replace(' ', '.')}@example.com", planName = "Coworking",
        startsOn = today.minusDays(5), endsOn = today.plusDays(25), amount = BigDecimal("80.00"), customerFields = mapOf("Documento" to "X123"),
        visits = listOf(ImportedVisit("v1", today.minusDays(2)), ImportedVisit("v2", today.minusDays(1))),
    )

    private fun insertJob(db: Connection, status: String, createdByUser: UUID?, rows: List<ImportedMember> = emptyList(), age: String = "0 days"): UUID {
        val id = UUID.randomUUID()
        val summary = MondaySummaryDto.encode(
            MondaySummaryDto(rows.size, rows.sumOf { it.visits.size }, listOf(MondayPlanDto("Coworking", "80.00", 30, 1)), listOf("Documento")),
        )
        db.prepareStatement(
            "INSERT INTO ext_membresia_monday__jobs (id, board_id, board_name, status, total, created_by, created_by_user, summary, updated_at) " +
                "VALUES (?, '123', 'Miembros', ?, ?, 'Ana Ruiz', ?, CAST(? AS jsonb), now() - CAST(? AS interval))",
        ).use { st ->
            st.setObject(1, id); st.setString(2, status); st.setInt(3, rows.size); st.setObject(4, createdByUser); st.setString(5, summary); st.setString(6, age)
            st.executeUpdate()
        }
        rows.forEachIndexed { index, row ->
            db.prepareStatement("INSERT INTO ext_membresia_monday__rows (job_id, row_no, payload) VALUES (?, ?, CAST(? AS jsonb))").use { st ->
                st.setObject(1, id); st.setInt(2, index + 1); st.setString(3, MondayRowDto.encode(row))
                st.executeUpdate()
            }
        }
        return id
    }

    @Test
    fun theTokenIsRecordedWithoutItsValue() = withTenant { db ->
        val (_, saved) = ActivityLogTesting.capture { MembresiaMondayFlow.saveToken(secrets, db, ana, "tok-SECRET-123") }
        val row = saved.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertEquals("settings", row.entry.entityType)
        assertEquals("membresia", row.entry.moduleId)
        assertTrue(row.change("token")!!.secret)
        assertNull(row.change("token")!!.new)
        assertFalse(row.toString().contains("tok-SECRET"), row.toString())
        assertEquals("Guarda el token de la API de monday.", row.entry.summary)

        val (_, changed) = ActivityLogTesting.capture { MembresiaMondayFlow.saveToken(secrets, db, ana, "otro-token") }
        assertEquals("Cambia el token de la API de monday.", changed.single().entry.summary)

        val (_, forgotten) = ActivityLogTesting.capture { MembresiaMondayFlow.forgetToken(secrets, db, ana) }
        assertEquals("Borra el token de la API de monday.", forgotten.single().entry.summary)
        val (_, again) = ActivityLogTesting.capture { MembresiaMondayFlow.forgetToken(secrets, db, ana) }
        assertTrue(again.isEmpty())

        // Escrito de verdad: ni el token ni su version sellada aparecen en el registro.
        MembresiaMondayFlow.saveToken(secrets, db, ana, "tok-SECRET-123")
        db.prepareStatement("SELECT count(*) FROM activity_log WHERE changes::text ILIKE '%tok-SECRET%' OR changes::text ILIKE '%321-TERCES%' OR summary ILIKE '%tok-SECRET%' OR summary ILIKE '%321-TERCES%'").use { st ->
            st.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
        }
    }

    @Test
    fun theStarterOfTheImportIsStoredAndConfirmAndCancelAreRecorded() = withTenant { db ->
        val id = MembresiaMondayJobs.queueRead(db, "123", "Miembros", MondayMapping(), "Ana Ruiz", ana.userId)
        assertEquals(ana.userId, MembresiaMondayJobs.find(db, id)!!.createdByUser)
        db.run("UPDATE ext_membresia_monday__jobs SET status = 'preview', total = 7 WHERE id = '$id'")
        val job = MembresiaMondayJobs.find(db, id)!!
        val agent = permissions().throughAgent("Claude")
        val (applied, confirm) = ActivityLogTesting.capture { MembresiaMondayFlow.requestApply(db, agent, job) }
        assertTrue(applied)
        assertEquals("Agente de Ana Ruiz", confirm.single().display)
        assertEquals("Aplicando", confirm.single().change("status")!!.new)
        assertEquals("7", confirm.single().change("rows")!!.new)
        val (second, none) = ActivityLogTesting.capture { MembresiaMondayFlow.requestApply(db, permissions(), job) }
        assertFalse(second)
        assertTrue(none.isEmpty())

        val (cancelled, rows) = ActivityLogTesting.capture { MembresiaMondayFlow.cancel(db, permissions(), MembresiaMondayJobs.find(db, id)!!) }
        assertTrue(cancelled)
        assertEquals("Ana Ruiz", rows.single().display)
        assertEquals("Cancelada", rows.single().change("status")!!.new)
    }

    @Test
    fun theJobImportsAsTheStarterAndWritesOneTotalRow() = withTenant { db ->
        val job = insertJob(db, "applying", ana.userId, listOf(member("monday:1", "Marta Gil")))
        val context = ModuleJobContext("acme", db, access, access.notificationsFor(MembresiaModule.Id))
        val (_, rows) = ActivityLogTesting.capture { MembresiaMondayJobs.runPending(context, Duration.ofSeconds(60)) }
        assertEquals(setOf("plan", "customer_field", "customer", "membership", "visit", "import"), rows.map { it.entry.entityType }.toSet())
        assertTrue(rows.all { it.display == "Importacion (Ana Ruiz)" }, rows.map { it.display }.toString())
        assertTrue(rows.all { it.actor.kind == "import" && it.actor.userId == ana.userId })
        val total = rows.single { it.action == ActivityActions.Imported }
        assertEquals(job.toString(), total.entry.entityId)
        assertEquals("1", total.change("created")!!.new)
        assertTrue(total.entry.summary.contains("Monday") && total.entry.summary.contains("Miembros"), total.entry.summary)
        assertEquals("done", MembresiaMondayJobs.find(db, job)!!.status)
    }

    @Test
    fun anImportStartedThroughAnAgentOrFromTheServerKeepsTheWholeIdentityOfItsStarter() = withTenant { db ->
        val context = ModuleJobContext("acme", db, access, access.notificationsFor(MembresiaModule.Id))
        val agent = permissions().throughAgent("Claude").activityActor
        val luis = UUID.fromString("00000000-0000-0000-0000-00000000b0b0")
        val switcher = ActivityActor.switcher(ana.userId!!, "Ana Ruiz", ActivityActor.Kinds.ServerAdmin, luis, "Luis Mora")
        for ((starter, display) in listOf(agent to "Importacion (Agente de Ana Ruiz)", switcher to "Importacion (Luis Mora (desde el servidor))")) {
            db.run("DELETE FROM ext_membresia_monday__jobs")
            // The job is queued the way the screen does (it keeps the starter) and then applied by the minute job.
            val id = MembresiaMondayJobs.queueRead(db, "123", "Miembros", MondayMapping(), "Ana Ruiz", ana.userId, starter)
            assertEquals(ActivityActor.import(starter), ActivityActor.restoreImportOrNull(MembresiaMondayJobs.find(db, id)!!.startedActor))
            db.run("DELETE FROM ext_membresia_monday__jobs WHERE id <> '$id'")
            val summary = MondaySummaryDto.encode(MondaySummaryDto(1, 2, listOf(MondayPlanDto("Coworking", "80.00", 30, 1)), listOf("Documento")))
            db.prepareStatement("UPDATE ext_membresia_monday__jobs SET status = 'applying', total = 1, summary = CAST(? AS jsonb) WHERE id = ?").use { st ->
                st.setString(1, summary); st.setObject(2, id); st.executeUpdate()
            }
            db.prepareStatement("INSERT INTO ext_membresia_monday__rows (job_id, row_no, payload) VALUES (?, 1, CAST(? AS jsonb))").use { st ->
                st.setObject(1, id); st.setString(2, MondayRowDto.encode(member("monday:${starter.kind}", "Marta Gil"))); st.executeUpdate()
            }
            val (_, rows) = ActivityLogTesting.capture { MembresiaMondayJobs.runPending(context, Duration.ofSeconds(60)) }
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.all { it.display == display }, rows.map { it.display }.toString())
            assertTrue(rows.all { it.actor.kind == "import" })
        }
    }

    @Test
    fun anImportStartedByAUserWhoIsGoneIsAttributedToAnUnknownUser() = withTenant { db ->
        val gone = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
        insertJob(db, "applying", gone, listOf(member("monday:9", "Luis Mora")))
        val context = ModuleJobContext("acme", db, access, access.notificationsFor(MembresiaModule.Id))
        // Sin captura: las filas se escriben de verdad, en la misma transaccion que cada cambio.
        MembresiaMondayJobs.runPending(context, Duration.ofSeconds(60))
        db.prepareStatement("SELECT actor_display, actor_user_id, entity_type, action FROM activity_log WHERE module_id = 'membresia' ORDER BY id").use { st ->
            st.executeQuery().use { result ->
                val seen = buildList { while (result.next()) add(listOf(result.getString(1), result.getObject(2, UUID::class.java), result.getString(3), result.getString(4))) }
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it[0] == "Importacion (Usuario desconocido)" && it[1] == gone }, seen.toString())
                assertEquals("import", seen.last()[2])
                assertEquals("imported", seen.last()[3])
            }
        }
    }

    @Test
    fun cleanupRecordsEachOldImportBeforeDeletingIt() = withTenant { db ->
        val old = insertJob(db, "done", ana.userId, age = "40 days")
        insertJob(db, "done", ana.userId, age = "1 day")
        val (deleted, rows) = ActivityLogTesting.capture { MembresiaMondayJobs.purgeOldRecorded(db) }
        assertEquals(1, deleted)
        val row = rows.single()
        assertEquals(ActivityActions.Deleted, row.action)
        assertEquals(old.toString(), row.entry.entityId)
        assertTrue(row.display.startsWith("Tarea programada "), row.display)
        assertNull(MembresiaMondayJobs.find(db, old))

        // Sin captura: la fila del borrado queda aunque el trabajo ya no exista.
        val other = insertJob(db, "failed", ana.userId, age = "50 days")
        assertEquals(1, MembresiaMondayJobs.purgeOldRecorded(db))
        db.prepareStatement("SELECT count(*) FROM activity_log WHERE entity_id = ? AND action = 'deleted'").use { st ->
            st.setString(1, other.toString())
            st.executeQuery().use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
        }
    }
}
