package com.ideasdeveloper.bms.extensions.tareas_monday

import com.ideasdeveloper.bms.modules.tareas.TareasImportContext
import com.ideasdeveloper.bms.modules.tareas.TareasImportJobContext
import com.ideasdeveloper.bms.modules.tareas.TareasRepository
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.Captured
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.server.security.SecretBox
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the monday extension writes leaves its row in the company activity log (ACTIVITY_LOG.md §4, §5): the token (only that it
 * changed), starting, applying and cancelling an import (with "Agente de ..." through an agent) and what the import writes in
 * the background, attributed to "Importacion (<persona que la inicio>)". The walks need a DISPOSABLE PostgreSQL: they run only
 * with BMS_ACTIVITY_PG_URL (or BMS_SERVER_ADMINS_PG_URL); the coverage check always runs.
 */
class TareasMondayActivityLogTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_tml_central"
    private val company = "bms_tml_acme"
    private val ownerId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val opened = mutableListOf<LoginDatabases>()

    // monday_read_board starts with a call to the monday API (network): its write, queueRead, is walked directly below.
    @Test
    fun theWriteToolsAreTheOnesWalkedHere() {
        val writes = TareasMondayMcpTools.tools.filterNot { it.readOnly }.map { it.name }.sorted()
        assertEquals(listOf("monday_apply", "monday_read_board"), writes)
    }

    // ---- Fixture ----

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private fun urlOf(database: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$database$1")
    private fun connect(database: String): Connection = DriverManager.getConnection(urlOf(database))
    private fun source(database: String) = PGSimpleDataSource().apply { setURL(urlOf(database)) }
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }
    private val prefix get() = urlOf("X").substringBefore("/X") + "/"
    private val pgUser get() = Regex("user=([^&]+)").find(url!!)?.groupValues?.get(1) ?: "postgres"
    private fun sqlFiles(dir: String) = File(root, dir).listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }

    @AfterTest
    fun close() = opened.forEach { it.close() }

    // Sealing needs the master key of the server: a box that only marks the value is enough here (what is stored is not what is tested).
    private val box = object : SecretBox {
        override val available = true
        override fun seal(tenant: Connection, plain: String) = SecretBox.SealedPrefix + plain.reversed()
        override fun open(tenant: Connection, value: String) = value.removePrefix(SecretBox.SealedPrefix).reversed()
    }

    private fun build(): ModuleAccessService {
        DriverManager.getConnection(url!!).use { admin ->
            listOf(central, company).forEach { name ->
                admin.run("DROP DATABASE IF EXISTS $name WITH (FORCE)")
                admin.run("CREATE DATABASE $name")
            }
        }
        connect(central).use { db ->
            sqlFiles("database/core/migrations").forEach { db.run(migration("database/core/migrations/${it.name}")) }
            sqlFiles("database/control/migrations").forEach { db.run(it.readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")) }
            db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), 'acme', 'Empresa acme', '$company', gen_random_uuid())")
        }
        connect(company).use { db ->
            sqlFiles("database/core/migrations").forEach { db.run(migration("database/core/migrations/${it.name}")) }
            listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials", "V006__create_extensions", "V011__create_activity_log")
                .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
            sqlFiles("modules/clientes/migrations").forEach { db.run(it.readText()) }
            sqlFiles("modules/tareas/migrations").forEach { db.run(it.readText()) }
            sqlFiles("extensions/tareas_monday/migrations").forEach { db.run(it.readText()) }
            db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('tareas', 'Tareas', 'enabled', CURRENT_TIMESTAMP) ON CONFLICT (module_id) DO UPDATE SET status = 'enabled'")
            db.run("INSERT INTO tenant_extensions (extension_id, module_id, version, status, enabled_at) VALUES ('tareas_monday', 'tareas', '1.2.0', 'enabled', CURRENT_TIMESTAMP)")
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', 'ana', TRUE, 'Ana Ruiz', 'ana@empresa.com')")
            db.run("INSERT INTO business_owner (user_id) VALUES ('$ownerId')")
        }
        val databases = LoginDatabases(source(central), Database.connect(source(central)), urlOf(central), "[]", pgUser, "", prefix, 3600).also { opened += it }
        return ModuleAccessService(source(central), databases, secrets = box)
    }

    private class Recorder {
        val rows = java.util.Collections.synchronizedList(mutableListOf<Captured>())
        fun take(): List<Captured> = rows.toList().also { rows.clear() }
    }

    private fun <T> recording(block: (Recorder) -> T): T {
        val recorder = Recorder()
        return ActivityLog.capturing({ actor, entry -> recorder.rows += Captured(actor, entry) }) { block(recorder) }
    }

    private fun sql(connection: Connection, statement: String) = connection.createStatement().use { it.execute(statement) }

    private fun scalar(connection: Connection, query: String): String? =
        connection.createStatement().use { st -> st.executeQuery(query).use { if (it.next()) it.getString(1) else null } }

    private fun owner() = ActivityActor.businessOwner(ownerId, "Ana Ruiz")

    private fun permissions() = ModulePermissions("acme", "", true, emptySet(), ownerId, activityActor = owner())

    // ---- The walk ----

    @Test
    fun tokenImportStepsAndTheBackgroundImportAreRecorded() {
        if (url == null) return
        val access = build()
        recording { recorder ->
            access.tenantConnectionForCompany("acme")!!.use { tenant ->
                val context = TareasImportContext(access, tenant, "acme", permissions(), "Ana Ruiz", LocalDate.now())

                // Settings: the token is only said to have changed, never its value.
                var reply = TareasMondayScreens.action(context, MondayOp.Token, mapOf("token" to "tok-super-secreto-123"))
                assertFalse(reply.error, reply.notice)
                var rows = recorder.take()
                assertEquals(listOf(ActivityActions.SettingsChanged), rows.map { it.action })
                assertEquals("Ana Ruiz", rows.single().display)
                assertTrue(rows.single().entry.changes.single().secret)
                assertEquals(null, rows.single().entry.changes.single().new)
                assertFalse(rows.single().entry.toString().contains("super-secreto"))
                assertEquals(SecretBox.SealedPrefix + "tok-super-secreto-123".reversed(), scalar(tenant, "SELECT token FROM ext_tareas_monday__settings"))
                reply = TareasMondayScreens.action(context, MondayOp.Forget, emptyMap())
                assertFalse(reply.error, reply.notice)
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.SettingsChanged), rows.map { it.action })
                assertEquals("Borro el token de la API de monday.", rows.single().entry.summary)

                // An import is started (what the screen does after reading the board) by an agent: "Agente de Ana Ruiz".
                val agent = owner().viaAgent("Claude")
                val jobId = TareasMondayJobs.queueRead(tenant, "123456", "Tablero de ventas", null, MondayMapping(emptyMap()), ownerId, agent)
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Created), rows.map { it.action })
                assertEquals("Agente de Ana Ruiz", rows.single().display)
                assertEquals("Tablero de ventas", rows.single().entry.entityLabel)
                val task = ImportedTaskFor("A1", "Llamar a Luis", "Ventas")
                TareasMondayJobs.savePage(
                    tenant, jobId,
                    listOf(
                        Triple("A1", MondayRowDto.encode(task), "[]"),
                        Triple("A2", MondayRowDto.encode(ImportedTaskFor("A2", "Enviar presupuesto", "Ventas")), "[]"),
                    ),
                    "",
                )
                sql(tenant, "UPDATE ext_tareas_monday__jobs SET status = 'preview' WHERE id = '$jobId'")

                // The owner cancels it from the screen, then another import is applied through the agent tool.
                reply = TareasMondayScreens.action(context, MondayOp.Cancel, mapOf("job" to jobId.toString()))
                assertFalse(reply.error, reply.notice)
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                assertEquals("Por revisar" to "Cancelada", rows.single().change("status")!!.let { it.old to it.new })
                assertEquals("Ana Ruiz", rows.single().display)

                val second = TareasMondayJobs.queueRead(tenant, "123456", "Tablero de ventas", null, MondayMapping(emptyMap()), ownerId, owner())
                recorder.take()
                TareasMondayJobs.savePage(
                    tenant, second,
                    listOf(
                        Triple("A1", MondayRowDto.encode(task), "[]"),
                        Triple("A2", MondayRowDto.encode(ImportedTaskFor("A2", "Enviar presupuesto", "Ventas")), "[]"),
                    ),
                    "",
                )
                sql(tenant, "UPDATE ext_tareas_monday__jobs SET status = 'preview' WHERE id = '$second'")
                val call = McpToolCall(buildJsonObject { put("job_id", second.toString()) }, permissions().throughAgent("Claude"), tenant, access, "Claude", "tareas")
                TareasMondayMcpTools.tools.single { it.name == "monday_apply" }.handler(call)
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                assertEquals("Agente de Ana Ruiz", rows.single().display)
                assertEquals("Por revisar" to "Aplicando", rows.single().change("status")!!.let { it.old to it.new })

                // The background job applies it: everything is "Importacion (Ana Ruiz)", with a row for each task and one for the whole import.
                val files = access.files("acme", "tareas")
                val processed = TareasMondayRunner.run(TareasImportJobContext(access, tenant, "acme", files, TareasRepository.today()), Duration.ofSeconds(30))
                assertTrue(processed >= 2, "processed $processed")
                rows = recorder.take()
                rows.forEach {
                    assertEquals("import", it.actor.kind)
                    assertEquals("Importacion (Ana Ruiz)", it.display)
                    assertEquals(ownerId, it.actor.userId)
                }
                assertEquals(1, rows.count { it.entry.entityType == "board" && it.action == ActivityActions.Created })
                assertEquals(1, rows.count { it.entry.entityType == "board_group" && it.action == ActivityActions.Created })
                assertEquals(listOf("Llamar a Luis", "Enviar presupuesto"), rows.filter { it.entry.entityType == "task" }.map { it.entry.entityLabel })
                assertTrue(rows.filter { it.entry.entityType == "task" }.all { it.action == ActivityActions.Created })
                val whole = rows.single { it.action == ActivityActions.Imported }
                assertEquals("2", whole.change("created")!!.new)
                assertEquals("0", whole.change("failed")!!.new)
                assertEquals("done", scalar(tenant, "SELECT status FROM ext_tareas_monday__jobs WHERE id = '$second'"))
                // The module's own history keeps its author text.
                assertEquals("Importación", scalar(tenant, "SELECT author_name FROM tareas_activity WHERE kind = 'imported' LIMIT 1"))
            }
        }
    }

    private fun ImportedTaskFor(key: String, title: String, group: String) =
        com.ideasdeveloper.bms.modules.tareas.ImportedTask(key = key, title = title, groupName = group)
}
