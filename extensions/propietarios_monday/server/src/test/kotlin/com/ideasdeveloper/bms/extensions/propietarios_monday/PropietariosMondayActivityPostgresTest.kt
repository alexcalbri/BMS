package com.ideasdeveloper.bms.extensions.propietarios_monday

import com.ideasdeveloper.bms.modules.propietarios.ImportedLot
import com.ideasdeveloper.bms.modules.propietarios.ImportedOwner
import com.ideasdeveloper.bms.modules.propietarios.PropietariosImportJobContext
import com.ideasdeveloper.bms.modules.propietarios.PropietariosModule
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.server.security.SecretBox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * La extension propietarios_monday escribe en el registro de actividad (ACTIVITY_LOG.md §4) en la MISMA transaccion que el cambio:
 * el token de monday sin su valor, el inicio, la confirmacion (tambien por agente) y la cancelacion de una importacion, la fila de
 * totales al cerrarla y la limpieza de importaciones antiguas; y deja que cada lote, cliente y titular importado diga "Importacion
 * (Agente de <usuario>)" cuando la empezo un agente. Corre sobre un PostgreSQL DESECHABLE (BMS_ACTIVITY_PG_URL o
 * BMS_SERVER_ADMINS_PG_URL): crea y borra la base bms_al_propietarios_monday. Sin esa variable no hace nada.
 */
class PropietariosMondayActivityPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val database = "bms_al_propietarios_monday"
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
                "FROM activity_log WHERE module_id = 'propietarios' AND $where ORDER BY id",
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
            File(root, "modules/clientes/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            File(root, "modules/propietarios/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            File(root, "extensions/propietarios_monday/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('${ana.userId}', 'role_ana', TRUE, 'Ana Ruiz', 'ana@example.com')")
            db.run("INSERT INTO business_owner (user_id) VALUES ('${ana.userId}')")
            db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('propietarios', 'Propietarios', 'enabled', now())")
            db.run("INSERT INTO tenant_extensions (extension_id, module_id, version, status, enabled_at) VALUES ('propietarios_monday', 'propietarios', '1.3.0', 'enabled', now())")
            block(db)
        }
    }

    private fun permissions(agent: Boolean = false): ModulePermissions {
        val plain = ActivityLogTesting.permissions(granted = setOf(PropietariosModule.View, PropietariosModule.Manage, PropietariosModule.Import), businessOwner = true, actor = ana)
        return if (agent) plain.throughAgent("Claude") else plain
    }

    private fun jobContext(db: Connection) = PropietariosImportJobContext(access, db, "acme", access.files("acme", PropietariosModule.id), today)

    private fun lot(key: String, number: String, owner: String) = ImportedLot(
        key = key, lotNumber = number, areaM2 = BigDecimal("300"),
        owners = listOf(ImportedOwner(owner, "${owner.lowercase().replace(' ', '.')}@example.com", sharePercent = BigDecimal("100"))),
    )

    private fun job(db: Connection, status: String, agent: String?, lots: List<ImportedLot> = emptyList(), age: String = "0 days"): UUID {
        val id = UUID.randomUUID()
        db.prepareStatement(
            "INSERT INTO ext_propietarios_monday__jobs (id, board_id, board_name, status, total, created_by, created_by_agent, updated_at) " +
                "VALUES (?, '123', 'Lotes Valle', ?, ?, ?, ?, now() - CAST(? AS interval))",
        ).use { st ->
            st.setObject(1, id); st.setString(2, status); st.setInt(3, lots.size); st.setObject(4, ana.userId); st.setString(5, agent); st.setString(6, age)
            st.executeUpdate()
        }
        lots.forEachIndexed { index, lot ->
            db.prepareStatement("INSERT INTO ext_propietarios_monday__rows (job_id, row_no, item_id, payload) VALUES (?, ?, ?, CAST(? AS jsonb))").use { st ->
                st.setObject(1, id); st.setInt(2, index + 1); st.setString(3, lot.key.substringAfter(':')); st.setString(4, MondayRowDto.encode(lot)); st.executeUpdate()
            }
        }
        return id
    }

    @Test
    fun theWriteToolsAreTheOnesTheTestsCover() {
        assertEquals(listOf("monday_apply", "monday_read_board"), PropietariosMondayMcpTools.tools.filterNot { it.readOnly }.map { it.name }.sorted())
    }

    @Test
    fun theTokenIsRecordedWithoutItsValue() = withTenant { db ->
        val actor = permissions().activityActor
        PropietariosMondayFlow.saveToken(secrets, db, actor, "tok-SECRET-123")
        PropietariosMondayFlow.saveToken(secrets, db, actor, "otro-token")
        PropietariosMondayFlow.forgetToken(secrets, db, actor)
        PropietariosMondayFlow.forgetToken(secrets, db, actor)
        val written = rows(db)
        assertEquals(
            listOf("Guardo el token de la API de monday.", "Cambio el token de la API de monday.", "Borro el token de la API de monday."),
            written.map { it.summary },
        )
        assertTrue(written.all { it.action == ActivityActions.SettingsChanged && it.type == "settings" && it.display == "Ana Ruiz" })
        assertTrue(written.all { it.changes.contains("\"secret\": true") && !it.changes.contains("SECRET") && !it.changes.contains("otro-token") }, written.toString())
        assertNull(text(db, "SELECT token FROM ext_propietarios_monday__settings"))
    }

    @Test
    fun anAgentThatStartsAnImportIsStoredInTheJobAndEveryImportedRowSaysAgenteDe() = withTenant { db ->
        val agent = permissions(agent = true)
        val jobId = PropietariosMondayFlow.enqueue(db, agent, "123", "Lotes Valle", null, MondayMapping())
        assertEquals("Claude", text(db, "SELECT created_by_agent FROM ext_propietarios_monday__jobs WHERE id = '$jobId'"))
        val queued = rows(db).single()
        assertEquals(ActivityActions.Created, queued.action)
        assertEquals("Agente de Ana Ruiz", queued.display)

        // Se deja el trabajo ya leido, en revision, y el agente lo confirma con la herramienta.
        val lots = listOf(lot("monday:1", "L-1", "Carla Mora"), lot("monday:2", "L-2", "Diego Solis"))
        db.run("UPDATE ext_propietarios_monday__jobs SET status = 'preview', total = 2 WHERE id = '$jobId'")
        lots.forEachIndexed { index, lot ->
            db.prepareStatement("INSERT INTO ext_propietarios_monday__rows (job_id, row_no, item_id, payload) VALUES (?, ?, ?, CAST(? AS jsonb))").use { st ->
                st.setObject(1, jobId); st.setInt(2, index + 1); st.setString(3, lot.key.substringAfter(':')); st.setString(4, MondayRowDto.encode(lot)); st.executeUpdate()
            }
        }
        val apply = PropietariosMondayMcpTools.tools.first { it.name == "monday_apply" }
        apply.handler(McpToolCall(JsonObject(mapOf("job_id" to JsonPrimitive(jobId.toString()))), agent, db, access, "Claude", PropietariosModule.id))
        val confirm = rows(db, "action = 'status_changed'").single()
        assertEquals("Agente de Ana Ruiz", confirm.display)
        assertTrue(confirm.changes.contains("Por revisar") && confirm.changes.contains("Aplicando"), confirm.changes)

        PropietariosMondayRunner.run(jobContext(db), Duration.ofSeconds(30))
        assertEquals("done", text(db, "SELECT status FROM ext_propietarios_monday__jobs WHERE id = '$jobId'"))
        val imported = rows(db).filter { it.kind == "import" }
        assertTrue(imported.all { it.display == "Importacion (Agente de Ana Ruiz)" && it.agent == "Claude" && it.origin == "import" && it.userId == ana.userId }, imported.toString())
        assertEquals(2, imported.count { it.type == "lote" && it.action == ActivityActions.Created })
        assertEquals(2, imported.count { it.type == "cliente" })
        assertEquals(2, imported.count { it.action == "owner_added" })
        assertEquals(listOf("Lotes Valle"), imported.filter { it.type == "desarrollo" }.map { it.label })
        val total = imported.single { it.action == ActivityActions.Imported }
        assertEquals(jobId.toString(), total.entityId)
        assertTrue(total.changes.contains("creados 2, actualizados 0, omitidos 0, con error 0"), total.changes)
    }

    @Test
    fun anOlderJobWithoutAnAgentFallsBackToThePlainStarter() = withTenant { db ->
        val jobId = job(db, "applying", agent = null, lots = listOf(lot("monday:7", "L-7", "Elena Vega")))
        PropietariosMondayRunner.run(jobContext(db), Duration.ofSeconds(30))
        assertEquals("done", text(db, "SELECT status FROM ext_propietarios_monday__jobs WHERE id = '$jobId'"))
        val imported = rows(db).filter { it.kind == "import" }
        assertTrue(imported.isNotEmpty() && imported.all { it.display == "Importacion (Ana Ruiz)" && it.agent == null }, imported.toString())
    }

    @Test
    fun cancellingRecordsWhoCancelledAndTheCloseOfTheJobAndItsTotalGoTogether() = withTenant { db ->
        val permissions = permissions()
        val cancelled = job(db, "preview", null)
        assertTrue(PropietariosMondayFlow.cancel(db, permissions, PropietariosMondayJobs.find(db, cancelled)!!))
        assertFalse(PropietariosMondayFlow.cancel(db, permissions, PropietariosMondayJobs.find(db, cancelled)!!))
        val row = rows(db).single()
        assertEquals(ActivityActions.StatusChanged, row.action)
        assertTrue(row.changes.contains("Por revisar") && row.changes.contains("Cancelada"), row.changes)

        val jobId = job(db, "applying", null, lots = listOf(lot("monday:9", "L-9", "Fabio Rojas")))
        db.run("CREATE FUNCTION al_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'sin registro'; END; \$\$")
        db.run("CREATE TRIGGER al_refuse_total BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.action = 'imported') EXECUTE FUNCTION al_refuse()")
        PropietariosMondayRunner.run(jobContext(db), Duration.ofSeconds(30))
        // Sin la fila de totales el trabajo no se cierra como terminado.
        assertTrue(text(db, "SELECT status FROM ext_propietarios_monday__jobs WHERE id = '$jobId'") != "done")
        assertFalse(rows(db).any { it.action == ActivityActions.Imported })
    }

    @Test
    fun cleaningOldImportsWritesTheRowBeforeTheDelete() = withTenant { db ->
        val old = job(db, "done", null, age = "45 days")
        val recent = job(db, "cancelled", null, age = "2 days")
        assertEquals(1, PropietariosMondayJobs.purgeOld(db))
        val purge = rows(db).single()
        assertEquals(ActivityActions.Deleted, purge.action)
        assertEquals(old.toString(), purge.entityId)
        assertEquals("Tarea programada Limpieza de importaciones de monday", purge.display)
        assertNull(text(db, "SELECT id::text FROM ext_propietarios_monday__jobs WHERE id = '$old'"))
        assertEquals(recent.toString(), text(db, "SELECT id::text FROM ext_propietarios_monday__jobs WHERE id = '$recent'"))
    }
}
