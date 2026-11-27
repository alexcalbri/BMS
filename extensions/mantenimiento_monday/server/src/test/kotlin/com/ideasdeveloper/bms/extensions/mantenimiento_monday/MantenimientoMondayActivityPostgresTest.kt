package com.ideasdeveloper.bms.extensions.mantenimiento_monday

import com.ideasdeveloper.bms.modules.mantenimiento.MantenimientoImportJobContext
import com.ideasdeveloper.bms.modules.mantenimiento.MantenimientoModule
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.server.security.SecretBox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * La extension mantenimiento_monday escribe en el registro de actividad (ACTIVITY_LOG.md §4) en la MISMA transaccion que el cambio:
 * el token de monday sin su valor, el inicio, la confirmacion (tambien por agente) y la cancelacion de una importacion, la fila de
 * totales al cerrarla y la limpieza de importaciones antiguas; y deja que cada propiedad, activo, plan y orden importado diga "Importacion
 * (Agente de <usuario>)" cuando la empezo un agente. Corre sobre un PostgreSQL DESECHABLE (BMS_ACTIVITY_PG_URL o
 * BMS_SERVER_ADMINS_PG_URL): crea y borra la base bms_al_mantenimiento_monday. Sin esa variable no hace nada.
 */
class MantenimientoMondayActivityPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val database = "bms_al_mantenimiento_monday"
    private val ana = ActivityLogTesting.user("Ana Ruiz", UUID.fromString("00000000-0000-0000-0000-00000000a0a0"))
    private val access = ActivityLogTesting.accessWithoutDatabase()
    private val today = LocalDate.of(2026, 11, 27)
    private val secrets = object : SecretBox {
        override val available = true
        override fun seal(tenant: Connection, plain: String) = SecretBox.SealedPrefix + plain.reversed()
        override fun open(tenant: Connection, value: String) = value.removePrefix(SecretBox.SealedPrefix).reversed()
    }

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private fun urlOf(base: String, name: String) = base.replace(Regex("/[^/?]+(\\?|$)"), "/$name$1")
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private data class Row(
        val kind: String, val userId: UUID?, val display: String, val agent: String?, val type: String, val entityId: String?, val label: String,
        val action: String, val summary: String, val changes: String, val origin: String?,
    )

    private fun rows(db: Connection, where: String = "TRUE"): List<Row> = db.createStatement().use { st ->
        st.executeQuery(
            "SELECT actor_kind, actor_user_id, actor_display, agent_name, entity_type, entity_id, entity_label, action, summary, changes::text, origin " +
                "FROM activity_log WHERE module_id = 'mantenimiento' AND $where ORDER BY id",
        ).use { rs ->
            buildList {
                while (rs.next()) {
                    add(Row(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)))
                }
            }
        }
    }

    private fun text(db: Connection, sql: String): String? = db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }

    private fun withTenant(block: (Connection) -> Unit) {
        val base = url ?: return
        DriverManager.getConnection(base).use { admin ->
            admin.run("DROP DATABASE IF EXISTS $database WITH (FORCE)")
            admin.run("CREATE DATABASE $database")
        }
        DriverManager.getConnection(urlOf(base, database)).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V005__create_notifications", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials", "V006__create_extensions", "V011__create_activity_log")
                .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
            File(root, "modules/mantenimiento/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            File(root, "extensions/mantenimiento_monday/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('${ana.userId}', 'role_ana', TRUE, 'Ana Ruiz', 'ana@example.com')")
            db.run("INSERT INTO business_owner (user_id) VALUES ('${ana.userId}')")
            db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('mantenimiento', 'Mantenimiento', 'enabled', now())")
            db.run("INSERT INTO tenant_extensions (extension_id, module_id, version, status, enabled_at) VALUES ('mantenimiento_monday', 'mantenimiento', '1.3.0', 'enabled', now())")
            block(db)
        }
    }

    private fun permissions(agent: Boolean = false): ModulePermissions {
        val plain = ActivityLogTesting.permissions(granted = setOf(MantenimientoModule.View, MantenimientoModule.Manage, MantenimientoModule.Import), businessOwner = true, actor = ana)
        return if (agent) plain.throughAgent("Claude") else plain
    }

    private fun jobContext(db: Connection) = MantenimientoImportJobContext(access, db, "acme", access.files("acme", MantenimientoModule.id), today)

    private val json = Json { encodeDefaults = false }

    private fun order(key: String, title: String, property: String? = "Hotel Norte") = MondayRowDto(
        target = MondayDestination.Orders, key = key, title = title, description = "No enfria", propertyName = property, assetName = "Aire 1", priority = "High",
        status = "InProgress", department = "Hotel", dueDate = today.plusDays(2).toString(),
        checklist = listOf(MondayCheckDto("c1", "Revisar gas", true), MondayCheckDto("c2", "Revisar filtro")),
        notes = listOf(MondayNoteDto("n1", "Pedro", "2026-11-20T10:00:00Z", "Lo vi ayer")),
    )

    private fun plan(key: String, title: String) = MondayRowDto(
        target = MondayDestination.Plans, key = key, title = title, propertyName = "Hotel Norte", frequency = "Quarterly", nextDue = today.plusDays(20).toString(),
        checklist = listOf(MondayCheckDto("p1", "Revisar presion")),
    )

    private fun job(db: Connection, status: String, agent: String?, target: String = MondayDestination.Orders, rows: List<MondayRowDto> = emptyList(), age: String = "0 days"): UUID {
        val id = UUID.randomUUID()
        db.prepareStatement(
            "INSERT INTO ext_mantenimiento_monday__jobs (id, board_id, board_name, target, status, total, created_by, created_by_agent, updated_at) " +
                "VALUES (?, '123', 'Mantenimiento hotel', ?, ?, ?, ?, ?, now() - CAST(? AS interval))",
        ).use { st ->
            st.setObject(1, id); st.setString(2, target); st.setString(3, status); st.setInt(4, rows.size); st.setObject(5, ana.userId); st.setString(6, agent); st.setString(7, age)
            st.executeUpdate()
        }
        addRows(db, id, rows)
        return id
    }

    private fun addRows(db: Connection, jobId: UUID, rows: List<MondayRowDto>) {
        rows.forEachIndexed { index, row ->
            db.prepareStatement("INSERT INTO ext_mantenimiento_monday__rows (job_id, row_no, item_id, payload) VALUES (?, ?, ?, CAST(? AS jsonb))").use { st ->
                st.setObject(1, jobId); st.setInt(2, index + 1); st.setString(3, row.key.substringAfter(':')); st.setString(4, json.encodeToString(MondayRowDto.serializer(), row)); st.executeUpdate()
            }
        }
    }

    @Test
    fun theWriteToolsAreTheOnesTheTestsCover() {
        assertEquals(listOf("monday_apply", "monday_read_board"), MantenimientoMondayMcpTools.tools.filterNot { it.readOnly }.map { it.name }.sorted())
    }

    @Test
    fun theTokenIsRecordedWithoutItsValue() = withTenant { db ->
        val actor = permissions().activityActor
        MantenimientoMondayFlow.saveToken(secrets, db, actor, "tok-SECRET-123")
        MantenimientoMondayFlow.saveToken(secrets, db, actor, "otro-token")
        MantenimientoMondayFlow.forgetToken(secrets, db, actor)
        MantenimientoMondayFlow.forgetToken(secrets, db, actor)
        val written = rows(db)
        assertEquals(
            listOf("Guardo el token de la API de monday.", "Cambio el token de la API de monday.", "Borro el token de la API de monday."),
            written.map { it.summary },
        )
        assertTrue(written.all { it.action == ActivityActions.SettingsChanged && it.type == "settings" && it.display == "Ana Ruiz" })
        assertTrue(written.all { it.changes.contains("\"secret\": true") && !it.changes.contains("SECRET") && !it.changes.contains("otro-token") }, written.toString())
        assertNull(text(db, "SELECT token FROM ext_mantenimiento_monday__settings"))
    }

    @Test
    fun anAgentThatStartsAnImportIsStoredInTheJobAndEveryImportedRowSaysAgenteDe() = withTenant { db ->
        val agent = permissions(agent = true)
        val jobId = MantenimientoMondayFlow.enqueue(db, agent, "123", "Mantenimiento hotel", MondayDestination.Orders, MondayDestination.KindCorrective, MondayMapping())
        assertEquals("Claude", text(db, "SELECT created_by_agent FROM ext_mantenimiento_monday__jobs WHERE id = '$jobId'"))
        val queued = rows(db).single()
        assertEquals(ActivityActions.Created, queued.action)
        assertEquals("Agente de Ana Ruiz", queued.display)

        // Se deja el trabajo ya leido, en revision, y el agente lo confirma con la herramienta.
        db.run("UPDATE ext_mantenimiento_monday__jobs SET status = 'preview', total = 2 WHERE id = '$jobId'")
        addRows(db, jobId, listOf(order("monday:1", "Aire acondicionado"), order("monday:2", "Puerta del garaje")))
        val apply = MantenimientoMondayMcpTools.tools.first { it.name == "monday_apply" }
        apply.handler(McpToolCall(JsonObject(mapOf("job_id" to JsonPrimitive(jobId.toString()))), agent, db, access, "Claude", MantenimientoModule.id))
        val confirm = rows(db, "action = 'status_changed'").single()
        assertEquals("Agente de Ana Ruiz", confirm.display)
        assertTrue(confirm.changes.contains("Por revisar") && confirm.changes.contains("Aplicando"), confirm.changes)

        MantenimientoMondayRunner.run(jobContext(db), Duration.ofSeconds(30))
        assertEquals("done", text(db, "SELECT status FROM ext_mantenimiento_monday__jobs WHERE id = '$jobId'"))
        val imported = rows(db).filter { it.kind == "import" }
        assertTrue(imported.all { it.display == "Importacion (Agente de Ana Ruiz)" && it.agent == "Claude" && it.origin == "import" && it.userId == ana.userId }, imported.toString())
        // Una fila por registro importado, con la lista y las notas contadas dentro de la fila de la orden.
        val orders = imported.filter { it.type == "orden" }
        assertEquals(listOf("Aire acondicionado", "Puerta del garaje"), orders.map { it.label })
        assertTrue(orders.all { it.action == ActivityActions.Created && it.changes.contains("2 elemento(s) de lista y 1 nota(s)") }, orders.toString())
        assertEquals(listOf("Hotel Norte"), imported.filter { it.type == "propiedad" }.map { it.label })
        assertEquals(listOf("Aire 1"), imported.filter { it.type == "activo" }.map { it.label })
        val total = imported.single { it.action == ActivityActions.Imported }
        assertEquals(jobId.toString(), total.entityId)
        assertTrue(total.changes.contains("creados 2, actualizados 0, omitidos 0, con error 0"), total.changes)
        assertTrue(total.summary.startsWith("Importo 2 ordenes"), total.summary)
    }

    @Test
    fun importedPlansAreRecordedWithTheirKeyDataAndTheChecklistCount() = withTenant { db ->
        val jobId = job(db, "applying", agent = null, target = MondayDestination.Plans, rows = listOf(plan("monday:5", "Revisar extintores")))
        MantenimientoMondayRunner.run(jobContext(db), Duration.ofSeconds(30))
        assertEquals("done", text(db, "SELECT status FROM ext_mantenimiento_monday__jobs WHERE id = '$jobId'"))
        val imported = rows(db).filter { it.kind == "import" }
        assertTrue(imported.all { it.display == "Importacion (Ana Ruiz)" && it.agent == null }, imported.toString())
        val created = imported.single { it.type == "plan" }
        assertEquals("Revisar extintores", created.label)
        assertTrue(created.changes.contains("Trimestral") && created.changes.contains("Hotel Norte") && created.changes.contains("1 elemento(s) de lista"), created.changes)
        assertTrue(imported.last().summary.startsWith("Importo 1 planes"), imported.last().summary)
    }

    @Test
    fun cancellingRecordsWhoCancelledAndTheCloseOfTheJobAndItsTotalGoTogether() = withTenant { db ->
        val permissions = permissions()
        val cancelled = job(db, "preview", null)
        assertTrue(MantenimientoMondayFlow.cancel(db, permissions, MantenimientoMondayJobs.find(db, cancelled)!!))
        assertFalse(MantenimientoMondayFlow.cancel(db, permissions, MantenimientoMondayJobs.find(db, cancelled)!!))
        val row = rows(db).single()
        assertEquals(ActivityActions.StatusChanged, row.action)
        assertTrue(row.changes.contains("Por revisar") && row.changes.contains("Cancelada"), row.changes)

        val jobId = job(db, "applying", null, rows = listOf(order("monday:9", "Calentador")))
        db.run("CREATE FUNCTION al_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'sin registro'; END; \$\$")
        db.run("CREATE TRIGGER al_refuse_total BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.action = 'imported') EXECUTE FUNCTION al_refuse()")
        MantenimientoMondayRunner.run(jobContext(db), Duration.ofSeconds(30))
        // Sin la fila de totales el trabajo no se cierra como terminado.
        assertTrue(text(db, "SELECT status FROM ext_mantenimiento_monday__jobs WHERE id = '$jobId'") != "done")
        assertFalse(rows(db).any { it.action == ActivityActions.Imported })
    }

    @Test
    fun cleaningOldImportsWritesTheRowBeforeTheDelete() = withTenant { db ->
        val old = job(db, "done", null, age = "45 days")
        val recent = job(db, "cancelled", null, age = "2 days")
        assertEquals(1, MantenimientoMondayJobs.purgeOld(db))
        val purge = rows(db).single()
        assertEquals(ActivityActions.Deleted, purge.action)
        assertEquals(old.toString(), purge.entityId)
        assertEquals("Tarea programada Limpieza de importaciones de monday", purge.display)
        assertNull(text(db, "SELECT id::text FROM ext_mantenimiento_monday__jobs WHERE id = '$old'"))
        assertEquals(recent.toString(), text(db, "SELECT id::text FROM ext_mantenimiento_monday__jobs WHERE id = '$recent'"))
    }
}
