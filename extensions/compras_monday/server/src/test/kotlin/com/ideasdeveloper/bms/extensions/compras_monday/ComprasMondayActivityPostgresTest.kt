package com.ideasdeveloper.bms.extensions.compras_monday

import com.ideasdeveloper.bms.modules.compras.Actor
import com.ideasdeveloper.bms.modules.compras.ComprasImportContext
import com.ideasdeveloper.bms.modules.compras.ComprasModule
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
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La extension compras_monday escribe en el registro de actividad (ACTIVITY_LOG.md §4) en la MISMA transaccion que el cambio: el
 * token de monday sin su valor, el inicio, la confirmacion (tambien por agente) y la cancelacion de una importacion, la fila de
 * totales al cerrarla y la limpieza de importaciones antiguas; y deja que cada solicitud importada diga "Importacion (Agente de
 * <usuario>)" cuando la confirmo un agente. Corre sobre un PostgreSQL DESECHABLE (BMS_ACTIVITY_PG_URL o BMS_SERVER_ADMINS_PG_URL):
 * crea y borra la base bms_al_compras_monday. Sin esa variable no hace nada.
 */
class ComprasMondayActivityPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val database = "bms_al_compras_monday"
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

    private data class Row(
        val kind: String, val userId: UUID?, val display: String, val agent: String?, val type: String, val entityId: String?, val label: String,
        val action: String, val summary: String, val changes: String, val origin: String?,
    )

    private fun rows(db: Connection, where: String = "TRUE"): List<Row> = db.createStatement().use { st ->
        st.executeQuery(
            "SELECT actor_kind, actor_user_id, actor_display, agent_name, entity_type, entity_id, entity_label, action, summary, changes::text, origin " +
                "FROM activity_log WHERE module_id = 'compras' AND $where ORDER BY id",
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
            File(root, "modules/compras/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            File(root, "extensions/compras_monday/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('${ana.userId}', 'role_ana', TRUE, 'Ana Ruiz', 'ana@example.com')")
            db.run("INSERT INTO business_owner (user_id) VALUES ('${ana.userId}')")
            db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('compras', 'Compras', 'enabled', now())")
            db.run("INSERT INTO tenant_extensions (extension_id, module_id, version, status, enabled_at) VALUES ('compras_monday', 'compras', '1.3.1', 'enabled', now())")
            ComprasMondayImport.bind(access)
            block(db)
        }
    }

    private fun permissions(agent: Boolean = false): ModulePermissions {
        val plain = ActivityLogTesting.permissions(granted = setOf(ComprasModule.View, ComprasModule.Create, ComprasModule.Manage, ComprasModule.Import), actor = ana)
        return if (agent) plain.throughAgent("Claude") else plain
    }

    private fun context(db: Connection, agent: Boolean = false): ComprasImportContext {
        val permissions = permissions(agent)
        return ComprasImportContext(
            db, "acme", Actor(ana.userId, "Ana Ruiz", permissions, permissions.activityActor), access.files("acme", ComprasModule.id), null, secrets,
        )
    }

    private val board = MondayBoardInfo("123", "Compras hotel", listOf(MondayColumn("amt", "Monto", "numbers")), listOf(MondayGroup("g1", "Costa Azul")))

    private fun readyJob(db: Connection, status: String = "ready", items: List<MondayItem> = emptyList(), age: String = "0 days"): UUID {
        val id = UUID.randomUUID()
        db.prepareStatement(
            "INSERT INTO ext_compras_monday__jobs (id, board_id, board_name, groups, mapping, status, read_count, created_by, created_by_name, updated_at) " +
                "VALUES (?, '123', 'Compras hotel', '[\"g1\"]', CAST(? AS jsonb), ?, ?, ?, 'Ana Ruiz', now() - CAST(? AS interval))",
        ).use { st ->
            st.setObject(1, id)
            st.setString(2, ComprasMondayParse.encodeMap(mapOf(MondayFields.Amount to "amt", MondayFields.Currency to "USD")))
            st.setString(3, status); st.setInt(4, items.size); st.setObject(5, ana.userId); st.setString(6, age)
            st.executeUpdate()
        }
        items.forEach { item ->
            db.prepareStatement("INSERT INTO ext_compras_monday__rows (job_id, item_id, payload) VALUES (?, ?, CAST(? AS jsonb))").use { st ->
                st.setObject(1, id); st.setString(2, item.id); st.setString(3, ComprasMondayParse.encode(item)); st.executeUpdate()
            }
        }
        return id
    }

    private fun item(id: String, name: String, amount: String = "900") =
        MondayItem(id, name, "g1", "Costa Azul", "2026-01-10T10:00:00Z", mapOf("amt" to MondayColumnValue("numbers", amount)), emptyList())

    @Test
    fun theWriteToolsAreTheOnesTheTestsCover() {
        assertEquals(listOf("monday_apply_import", "monday_start_import"), ComprasMondayMcpTools.tools.filterNot { it.readOnly }.map { it.name }.sorted())
    }

    @Test
    fun theTokenIsRecordedWithoutItsValue() = withTenant { db ->
        val ctx = context(db)
        ComprasMondayImport.saveToken(ctx, "tok-SECRET-123")
        ComprasMondayImport.saveToken(ctx, "otro-token")
        ComprasMondayImport.forgetToken(ctx)
        ComprasMondayImport.forgetToken(ctx)
        val written = rows(db)
        assertEquals(
            listOf("Guardo el token de la API de monday.", "Cambio el token de la API de monday.", "Borro el token de la API de monday."),
            written.map { it.summary },
        )
        assertTrue(written.all { it.action == ActivityActions.SettingsChanged && it.type == "settings" && it.display == "Ana Ruiz" })
        assertTrue(written.all { it.changes.contains("\"secret\": true") && !it.changes.contains("SECRET") && !it.changes.contains("otro-token") }, written.toString())
        assertNull(text(db, "SELECT token FROM ext_compras_monday__settings"))
    }

    @Test
    fun startingConfirmingAndCancellingLeaveTheirRowsInTheSameTransaction() = withTenant { db ->
        val ctx = context(db)
        val started = ComprasMondayImport.startImport(ctx, board, listOf("g1"), mapOf(MondayFields.Amount to "amt"), "USD")
        val queued = rows(db).single()
        assertEquals(ActivityActions.Created, queued.action)
        assertEquals(started.toString(), queued.entityId)
        assertEquals("Compras hotel", queued.label)

        // Cancelar: la fila lleva el estado anterior; una segunda cancelacion falla sin fila.
        ComprasMondayImport.cancelJob(ctx, started)
        val cancelled = rows(db).last()
        assertEquals(ActivityActions.StatusChanged, cancelled.action)
        assertTrue(cancelled.changes.contains("Leyendo") && cancelled.changes.contains("Cancelada"), cancelled.changes)
        assertEquals(2, rows(db).size)

        // Si la fila del registro falla, el trabajo no queda en cola.
        db.run("CREATE FUNCTION al_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'sin registro'; END; \$\$")
        db.run("CREATE TRIGGER al_refuse_import BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.entity_type = 'importacion') EXECUTE FUNCTION al_refuse()")
        runCatching { ComprasMondayImport.startImport(ctx, board, listOf("g1"), mapOf(MondayFields.Amount to "amt"), "USD") }
        assertEquals("1", text(db, "SELECT count(*) FROM ext_compras_monday__jobs"))
        assertTrue(db.autoCommit)
    }

    @Test
    fun anAgentThatConfirmsAnImportMakesEveryImportedRowSayAgenteDe() = withTenant { db ->
        val jobId = readyJob(db, items = listOf(item("1001", "Aire acondicionado"), item("1002", "Sabanas", "120")))
        val apply = ComprasMondayMcpTools.tools.first { it.name == "monday_apply_import" }
        apply.handler(McpToolCall(JsonObject(mapOf("job_id" to JsonPrimitive(jobId.toString()))), permissions(agent = true), db, access, "Claude", ComprasModule.id))
        assertEquals("Claude", text(db, "SELECT applied_by_agent FROM ext_compras_monday__jobs WHERE id = '$jobId'"))
        val confirm = rows(db).single()
        assertEquals("Agente de Ana Ruiz", confirm.display)
        assertEquals("agent", confirm.origin)
        assertEquals(ActivityActions.StatusChanged, confirm.action)

        // El trabajo periodico aplica con los permisos vivos del usuario y el nombre del agente guardado en el trabajo.
        ComprasMondayImport.runPending(context(db), 100)
        assertEquals("done", text(db, "SELECT status FROM ext_compras_monday__jobs WHERE id = '$jobId'"))
        val imported = rows(db).drop(1)
        assertTrue(imported.all { it.kind == "import" && it.display == "Importacion (Agente de Ana Ruiz)" && it.agent == "Claude" && it.origin == "import" && it.userId == ana.userId }, imported.toString())
        // El departamento nuevo (el nombre del tablero), las dos solicitudes y la fila de totales.
        assertEquals(listOf("departamento", "solicitud", "solicitud", "importacion"), imported.map { it.type })
        assertEquals(listOf(ActivityActions.Created, ActivityActions.Created, ActivityActions.Created, ActivityActions.Imported), imported.map { it.action })
        assertTrue(imported.last().changes.contains("creadas 2, ya existian 0, con error 0"), imported.last().changes)
        assertEquals(jobId.toString(), imported.last().entityId)
    }

    @Test
    fun anImportConfirmedByAPersonOrBeforeTheColumnExistedFallsBackToThePlainStarter() = withTenant { db ->
        val jobId = readyJob(db, items = listOf(item("2001", "Toallas")))
        ComprasMondayImport.applyJob(context(db), jobId)
        assertNull(text(db, "SELECT applied_by_agent FROM ext_compras_monday__jobs WHERE id = '$jobId'"))
        ComprasMondayImport.runPending(context(db), 100)
        val imported = rows(db).filter { it.type == "solicitud" || it.type == "importacion" && it.action == ActivityActions.Imported }
        assertTrue(imported.all { it.display == "Importacion (Ana Ruiz)" && it.agent == null }, imported.toString())
        assertEquals(2, imported.size)
    }

    @Test
    fun theImportTotalAndTheClosingOfTheJobGoTogether() = withTenant { db ->
        val jobId = readyJob(db, items = listOf(item("3001", "Pintura")))
        ComprasMondayImport.applyJob(context(db), jobId)
        db.run("CREATE FUNCTION al_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'sin registro'; END; \$\$")
        db.run("CREATE TRIGGER al_refuse_total BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.action = 'imported') EXECUTE FUNCTION al_refuse()")
        ComprasMondayImport.runPending(context(db), 100)
        // La fila de totales no se pudo escribir: el trabajo no se cerro (se reintenta) y no hay fila de totales.
        assertEquals("applying", text(db, "SELECT status FROM ext_compras_monday__jobs WHERE id = '$jobId'"))
        assertFalse(rows(db).any { it.action == ActivityActions.Imported })
        db.run("DROP TRIGGER al_refuse_total ON activity_log")
        ComprasMondayImport.runPending(context(db), 100)
        assertEquals("done", text(db, "SELECT status FROM ext_compras_monday__jobs WHERE id = '$jobId'"))
        assertEquals(1, rows(db).count { it.action == ActivityActions.Imported })
    }

    @Test
    fun cleaningOldImportsWritesTheRowBeforeTheDelete() = withTenant { db ->
        val old = readyJob(db, status = "done", age = "45 days")
        val recent = readyJob(db, status = "done", age = "2 days")
        assertEquals(1, ComprasMondayStore.purgeOld(db))
        val purge = rows(db).single()
        assertEquals(ActivityActions.Deleted, purge.action)
        assertEquals(old.toString(), purge.entityId)
        assertEquals("job", purge.kind)
        assertEquals("Tarea programada Limpieza de importaciones de monday", purge.display)
        assertNull(text(db, "SELECT id::text FROM ext_compras_monday__jobs WHERE id = '$old'"))
        assertEquals(recent.toString(), text(db, "SELECT id::text FROM ext_compras_monday__jobs WHERE id = '$recent'"))
    }
}
