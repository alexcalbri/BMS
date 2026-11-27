package com.ideasdeveloper.bms.modules.tareas

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.Captured
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.ui.UiFileRef
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every write of Tareas leaves its row in the company activity log (ACTIVITY_LOG.md §4, §5): through the real routes with a real
 * session (the actor is resolved by Core), through the agent tools ("Agente de ..."), the due reminders job and the importer.
 * The walks need a DISPOSABLE PostgreSQL: they run only with BMS_ACTIVITY_PG_URL (or BMS_SERVER_ADMINS_PG_URL); the coverage
 * check of the write tools and routes always runs.
 */
class TareasActivityLogTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_tal_central"
    private val company = "bms_tal_acme"
    private val ownerId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val betoId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
    private val token = "S".repeat(43)
    private val opened = mutableListOf<LoginDatabases>()

    // ---- Coverage: every write tool and route of the module is walked below ----

    private val walkedTools = listOf("tareas_add_comment", "tareas_complete_task", "tareas_create_task", "tareas_set_checklist_item", "tareas_update_task")

    // The upload only stores a pending file (no task yet): the row comes when it is attached to a task (walked in the routes test).
    private val walkedRoutes = listOf("POST /modules/tareas/ui/files", "POST /modules/tareas/ui/{screen}")

    @Test
    fun everyWriteToolAndRouteIsWalkedByTheTests() {
        assertEquals(walkedTools, ActivityLogTesting.writeTools(TareasServerModule))
        assertEquals(walkedRoutes, ActivityLogTesting.writeRoutes(TareasServerModule))
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

    /** One company with the core tables, the activity log table, Clientes (customers) and Tareas; the owner Ana Ruiz and Beto. */
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
            listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials", "V011__create_activity_log")
                .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
            sqlFiles("modules/clientes/migrations").forEach { db.run(it.readText()) }
            sqlFiles("modules/tareas/migrations").forEach { db.run(it.readText()) }
            db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('tareas', 'Tareas', 'enabled', CURRENT_TIMESTAMP) ON CONFLICT (module_id) DO UPDATE SET status = 'enabled'")
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', 'ana', TRUE, 'Ana Ruiz', 'ana@empresa.com')")
            db.run("INSERT INTO business_owner (user_id) VALUES ('$ownerId')")
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$betoId', 'beto', TRUE, 'Beto', 'beto@empresa.com')")
            db.run("INSERT INTO application_permissions (permission_id, module_id, title) VALUES ('tareas.view', 'tareas', 'Ver tareas') ON CONFLICT DO NOTHING")
            db.run("INSERT INTO application_user_permissions (user_id, permission_id) VALUES ('$betoId', 'tareas.view')")
            db.prepareStatement("INSERT INTO application_sessions (token_hash, user_id, expires_at) VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 hour')").use { insert ->
                insert.setString(1, SessionTokenGenerator().hash(token))
                insert.setObject(2, ownerId)
                insert.executeUpdate()
            }
        }
        val databases = LoginDatabases(source(central), Database.connect(source(central)), urlOf(central), "[]", pgUser, "", prefix, 3600).also { opened += it }
        return ModuleAccessService(source(central), databases)
    }

    private class Recorder {
        val rows = java.util.Collections.synchronizedList(mutableListOf<Captured>())
        fun take(): List<Captured> = rows.toList().also { rows.clear() }
    }

    // Rows go to the recorder (from every thread: routes run on others) instead of the table; take() empties it step by step.
    private fun <T> recording(block: (Recorder) -> T): T {
        val recorder = Recorder()
        return ActivityLog.capturing({ actor, entry -> recorder.rows += Captured(actor, entry) }, everywhere = true) { block(recorder) }
    }

    private fun scalar(sql: String): String? = connect(company).use { db ->
        db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
    }

    private fun owner(): ActivityActor = ActivityActor.businessOwner(ownerId, "Ana Ruiz")

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, body: String = "{}", extra: Map<String, String> = emptyMap()): HttpResponse =
        client.request(path) {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Company-Code", "acme")
            extra.forEach { (name, value) -> header(name, value) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun withRoutes(access: ModuleAccessService, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { routing { TareasServerModule.routes(this, access) } }
        block()
    }

    private fun Captured.field(name: String) = change(name)?.let { it.old to it.new }

    // ---- Routes: the screens' own operations, in the order a person would use them ----

    @Test
    fun routesRecordBoardsGroupsTasksChecklistsCommentsAndAttachments() {
        if (url == null) return
        val access = build()
        recording { recorder ->
            withRoutes(access) {
                val ui = "/modules/tareas/ui"
                suspend fun post(path: String, body: String = "{}"): HttpResponse =
                    send(HttpMethod.Post, "$ui/$path", body).also { assertEquals(HttpStatusCode.OK, it.status, "$path: ${it.bodyAsText()}") }

                // Boards and groups (configuration of the module).
                post("board-edit?op=board-save", """{"name":"Operaciones","description":"Tareas del dia","visibility":"Toda la empresa","members":[]}""")
                var rows = recorder.take()
                assertEquals(listOf(ActivityActions.Created), rows.map { it.action })
                assertEquals("board", rows.single().entry.entityType)
                assertEquals("Ana Ruiz", rows.single().display)
                assertEquals("business_owner", rows.single().actor.kind)
                assertEquals("Operaciones", rows.single().change("name")!!.new)
                val boardId = scalar("SELECT id FROM tareas_boards WHERE name = 'Operaciones'")!!

                post("board-edit?op=board-save&id=$boardId", """{"name":"Operaciones 2","description":"Tareas del dia","visibility":"Privado","members":["Beto"]}""")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                assertEquals("Operaciones" to "Operaciones 2", rows.single().field("name"))
                assertEquals("Toda la empresa" to "Privado", rows.single().field("visibility"))
                assertEquals(null to "Beto", rows.single().field("members"))
                assertEquals(null, rows.single().change("description"))

                post("board-edit?op=group-add&id=$boardId", """{"name":"Esta semana"}""")
                post("board-edit?op=group-add&id=$boardId", """{"name":"Luego"}""")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Created, ActivityActions.Created), rows.map { it.action })
                assertEquals("board_group", rows.first().entry.entityType)
                val firstGroup = scalar("SELECT id FROM tareas_groups WHERE name = 'Esta semana'")!!
                post("board-edit?op=group-rename&id=$boardId&group=$firstGroup", """{"name":"Semana 1"}""")
                rows = recorder.take()
                assertEquals("Esta semana" to "Semana 1", rows.single().field("name"))
                post("board-edit?op=group-down&id=$boardId&group=$firstGroup")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                assertEquals("1" to "2", rows.single().field("position"))
                post("board-edit?op=group-archive&id=$boardId&group=$firstGroup")
                assertEquals(listOf(ActivityActions.Archived), recorder.take().map { it.action })
                // The group is needed again below: a new one with the same name.
                post("board-edit?op=group-add&id=$boardId", """{"name":"Semana 1"}""")
                recorder.take()

                // A task: created, edited (old -> new), moved through its states.
                post(
                    "new-task?op=create&board=$boardId",
                    """{"title":"Revisar caja","detail":"Cuadrar","priority":"Alta","due":"2026-12-01","reviewer":"Beto","group":"Semana 1","assignees":["Beto"]}""",
                )
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Created), rows.map { it.action })
                assertEquals("task", rows.single().entry.entityType)
                val created = rows.single()
                assertEquals("Revisar caja", created.change("title")!!.new)
                assertEquals("Alta", created.change("priority")!!.new)
                assertEquals("2026-12-01", created.change("dueDate")!!.new)
                assertEquals("Beto", created.change("assignees")!!.new)
                assertEquals("Beto", created.change("reviewer")!!.new)
                assertEquals("Semana 1", created.change("group")!!.new)
                assertEquals("Operaciones 2", created.change("board")!!.new)
                val taskId = scalar("SELECT id FROM tareas_tasks WHERE title = 'Revisar caja'")!!
                // The module's own history keeps its text.
                assertEquals("Tarea creada", scalar("SELECT detail FROM tareas_activity WHERE task_id = '$taskId' AND kind = 'created'"))

                post(
                    "task?op=save&id=$taskId",
                    """{"title":"Revisar caja grande","detail":"Cuadrar","priority":"Urgente","due":"2026-12-05","reviewer":"Sin revisor","group":"Sin grupo","assignees":[]}""",
                )
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                val edited = rows.single()
                assertEquals("Revisar caja" to "Revisar caja grande", edited.field("title"))
                assertEquals("Alta" to "Urgente", edited.field("priority"))
                assertEquals("2026-12-01" to "2026-12-05", edited.field("dueDate"))
                assertEquals("Beto" to null, edited.field("assignees"))
                assertEquals("Beto" to null, edited.field("reviewer"))
                assertEquals("Semana 1" to null, edited.field("group"))
                assertEquals(null, edited.change("detail"))
                assertEquals("Tarea editada", scalar("SELECT detail FROM tareas_activity WHERE task_id = '$taskId' AND kind = 'updated'"))

                post("task?op=status&id=$taskId&status=En%20curso")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.StatusChanged), rows.map { it.action })
                assertEquals("Pendiente" to "En curso", rows.single().field("status"))

                post("task?op=comment&id=$taskId", """{"body":"Ya esta contado"}""")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Commented), rows.map { it.action })
                assertEquals("Ya esta contado", rows.single().change("body")!!.new)

                // Checklist: added, ticked, removed (the row of the removal is there after the item is gone).
                post("task?op=check-add&id=$taskId", """{"text":"Contar billetes"}""")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Created), rows.map { it.action })
                assertEquals("checklist_item", rows.single().entry.entityType)
                val itemId = scalar("SELECT id FROM tareas_checklist WHERE task_id = '$taskId'")!!
                post("task?op=check-toggle&id=$taskId&item=$itemId&done=true")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                assertEquals("No" to "Si", rows.single().field("done"))
                post("task?op=check-remove&id=$taskId&item=$itemId")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Deleted), rows.map { it.action })
                assertEquals("Contar billetes", rows.single().change("text")!!.old)
                assertEquals("0", scalar("SELECT count(*) FROM tareas_checklist WHERE task_id = '$taskId'"))

                // Files: the upload alone stores a pending file; attaching it to the task leaves the row (name and size, never the content).
                val upload = send(
                    HttpMethod.Post, "$ui/files", "contenido del contrato",
                    mapOf("X-File-Name" to "contrato.pdf"),
                )
                assertEquals(HttpStatusCode.OK, upload.status, upload.bodyAsText())
                assertTrue(recorder.take().isEmpty())
                val file = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(UiFileRef.serializer(), upload.bodyAsText())
                access.tenantConnectionForCompany("acme")!!.use { tenant ->
                    val permissions = ModulePermissions("acme", "", true, emptySet(), ownerId, activityActor = owner())
                    TareasService.attachFiles(tenant, permissions, UUID.fromString(taskId), listOf(file))
                    rows = recorder.take()
                    assertEquals(listOf(ActivityActions.AttachmentAdded), rows.map { it.action })
                    assertEquals("contrato.pdf", rows.single().entry.entityLabel)
                    assertTrue(rows.single().change("file")!!.new!!.startsWith("contrato.pdf ("))
                    assertTrue(rows.none { row -> row.entry.changes.any { it.new?.contains("contenido") == true } })
                    TareasService.removeFiles(tenant, permissions, UUID.fromString(taskId), listOf(UUID.fromString(file.id)))
                    rows = recorder.take()
                    assertEquals(listOf(ActivityActions.AttachmentRemoved), rows.map { it.action })
                    assertTrue(rows.single().change("file")!!.old!!.startsWith("contrato.pdf ("))
                }

                // Archive the task and the board.
                post("task?op=archive&id=$taskId")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Archived), rows.map { it.action })
                assertEquals("Revisar caja grande", rows.single().entry.entityLabel)
                post("board-edit?op=board-archive&id=$boardId")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Archived), rows.map { it.action })
                assertEquals("board", rows.single().entry.entityType)
            }
        }
    }

    // ---- Agent tools ----

    private fun toolCall(access: ModuleAccessService, tenant: Connection, arguments: JsonObject): McpToolCall =
        McpToolCall(
            arguments, ModulePermissions("acme", "", true, emptySet(), ownerId, activityActor = owner()).throughAgent("Claude"),
            tenant, access, "Claude", "tareas",
        )

    private fun run(access: ModuleAccessService, tenant: Connection, name: String, arguments: JsonObject): JsonObject =
        TareasMcpTools.tools.single { it.name == name }.handler(toolCall(access, tenant, arguments)).jsonObject

    @Test
    fun agentToolsRecordTheUserBehindTheAgent() {
        if (url == null) return
        val access = build()
        recording { recorder ->
            access.tenantConnectionForCompany("acme")!!.use { tenant ->
                val permissions = ModulePermissions("acme", "", true, emptySet(), ownerId, activityActor = owner())
                val boardId = TareasService.createBoard(tenant, permissions, "Soporte", "", BoardVisibility.Company, emptyList())
                recorder.take()
                val walked = mutableListOf<String>()
                fun call(name: String, arguments: JsonObject): Pair<JsonObject, List<Captured>> {
                    walked += "tareas_$name"
                    val result = run(access, tenant, name, arguments)
                    val rows = recorder.take()
                    rows.forEach { assertEquals("Agente de Ana Ruiz", it.display); assertEquals("Claude", it.actor.agentName) }
                    return result to rows
                }
                val (created, createdRows) = call("create_task", buildJsonObject {
                    put("board_id", boardId.toString()); put("title", "Llamar al proveedor"); put("priority", "Alta")
                    put("assignees", JsonArray(listOf(JsonPrimitive("Beto")))); put("checklist", JsonArray(listOf(JsonPrimitive("Pedir presupuesto"))))
                })
                val id = created["task"]!!.jsonObject["id"]!!.jsonPrimitive.content
                assertEquals(listOf(ActivityActions.Created), createdRows.map { it.action })
                assertEquals("Beto", createdRows.single().change("assignees")!!.new)
                assertEquals("Pedir presupuesto", createdRows.single().change("checklist")!!.new)
                assertEquals("agent", scalar("SELECT source FROM tareas_tasks WHERE id = '$id'"))

                var rows = call("update_task", buildJsonObject { put("id", id); put("title", "Llamar al proveedor de luz"); put("due_date", "2026-12-10") }).second
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                assertEquals("Llamar al proveedor" to "Llamar al proveedor de luz", rows.single().field("title"))
                rows = call("update_task", buildJsonObject { put("id", id); put("status", "En curso") }).second
                assertEquals(listOf(ActivityActions.StatusChanged), rows.map { it.action })
                rows = call("add_comment", buildJsonObject { put("id", id); put("body", "Pendiente de respuesta") }).second
                assertEquals(listOf(ActivityActions.Commented), rows.map { it.action })
                rows = call("set_checklist_item", buildJsonObject { put("id", id); put("text", "Firmar contrato"); put("done", true) }).second
                assertEquals(listOf(ActivityActions.Created, ActivityActions.Updated), rows.map { it.action })
                rows = call("complete_task", buildJsonObject { put("id", id) }).second
                assertEquals(listOf(ActivityActions.StatusChanged), rows.map { it.action })
                assertEquals("En curso" to "Hecha", rows.single().field("status"))

                assertEquals(ActivityLogTesting.writeTools(TareasServerModule).toSet(), walked.toSet())
            }
        }
    }

    // ---- Jobs and the importer ----

    @Test
    fun theDueRemindersJobAndTheImporterHaveTheirOwnActors() {
        if (url == null) return
        val access = build()
        recording { recorder ->
            access.tenantConnectionForCompany("acme")!!.use { tenant ->
                val permissions = ModulePermissions("acme", "", true, emptySet(), ownerId, activityActor = owner())
                val boardId = TareasService.createBoard(tenant, permissions, "Cobros", "", BoardVisibility.Company, emptyList())
                TareasService.createTask(
                    tenant, access, permissions,
                    TaskInput(boardId, title = "Cobrar factura", dueDate = LocalDate.now().minusDays(2), assignees = listOf(betoId)),
                )
                recorder.take()

                // The job: its row says it is the job, in the same transaction as the reminder.
                val sent = TareasNotify.dueReminders(tenant, access.notificationsFor(TareasModule.id), TareasRepository.today())
                assertEquals(1, sent)
                var rows = recorder.take()
                assertEquals(listOf(TareasLog.ReminderSent), rows.map { it.action })
                assertEquals("job", rows.single().actor.kind)
                assertEquals("Tarea programada Recordatorios de tareas", rows.single().display)
                assertEquals("Beto", rows.single().change("recipient")!!.new)
                assertEquals("Vencida", rows.single().change("kind")!!.new)

                // The importer: every row is attributed to the import and to the person who started it.
                val importer = TareasLog.importer(tenant, ownerId)
                assertEquals("Importacion (Ana Ruiz)", importer.display)
                assertEquals("import", importer.kind)
                val users = TareasRepository.users(tenant)
                val imported = TareasImportApply.ensureBoard(tenant, "monday:board:1", "Tablero de monday", null, ownerId, importer)
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Created), rows.map { it.action })
                assertEquals("Importacion (Ana Ruiz)", rows.single().display)
                val row = ImportedTask(
                    key = "10", title = "Preparar informe", groupName = "Octubre", status = TaskStatus.InProgress, priority = TaskPriority.High,
                    dueDate = LocalDate.of(2026, 12, 20), assigneeEmails = listOf("beto@empresa.com"),
                    checklist = listOf(ImportedCheck("c1", "Datos", false)), comments = listOf(ImportedComment("m1", "Luis", null, "Revisar")),
                )
                val first = TareasImportApply.apply(tenant, "monday", imported, row, users, ownerId, TareasRepository.today(), importer)
                assertTrue(first is ImportOutcome.Created)
                rows = recorder.take()
                // The group the import needed, then the task.
                assertEquals(listOf("board_group" to ActivityActions.Created, "task" to ActivityActions.Created), rows.map { it.entry.entityType to it.action })
                rows.forEach { assertEquals("Importacion (Ana Ruiz)", it.display) }
                val task = rows.last()
                assertEquals("Preparar informe", task.change("title")!!.new)
                assertEquals("En curso", task.change("status")!!.new)
                assertEquals("Octubre", task.change("group")!!.new)
                assertEquals("1", task.change("checklistImported")!!.new)
                assertEquals("1", task.change("commentsImported")!!.new)
                val second = TareasImportApply.apply(tenant, "monday", imported, row.copy(title = "Preparar informe final", status = TaskStatus.Done), users, ownerId, TareasRepository.today(), importer)
                assertTrue(second is ImportOutcome.Updated)
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                assertEquals("Preparar informe" to "Preparar informe final", rows.single().field("title"))
                assertEquals("En curso" to "Hecha", rows.single().field("status"))
                // An import that changed nothing leaves no row for that task.
                TareasImportApply.apply(tenant, "monday", imported, row.copy(title = "Preparar informe final", status = TaskStatus.Done), users, ownerId, TareasRepository.today(), importer)
                assertTrue(recorder.take().isEmpty())
                // One row for the whole import, with totals.
                TareasImportApply.recordImport(tenant, importer, "monday", "Tablero de monday", UUID.randomUUID(), 1, 1, 0, 0, 0, 0)
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Imported), rows.map { it.action })
                assertEquals("1", rows.single().change("created")!!.new)
                assertEquals("Importacion (Ana Ruiz)", rows.single().display)
                // A file of the source.
                val taskId = (first as ImportOutcome.Created).taskId
                val stored = TareasImportApply.attachFile(
                    tenant, access.files("acme", TareasModule.id), taskId, "monday:asset:1", "plano.png", "image/png",
                    java.io.ByteArrayInputStream(ByteArray(2048)), ownerId, importer,
                )
                assertTrue(stored)
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.AttachmentAdded), rows.map { it.action })
                assertEquals("plano.png (2 KB)", rows.single().change("file")!!.new)
                assertEquals("Importacion (Ana Ruiz)", rows.single().display)
            }
        }
    }

    // ---- The row is part of the change ----

    @Test
    fun aFailingActivityRowUndoesTheChangeAndTheRowLandsInTheTable() {
        if (url == null) return
        val access = build()
        access.tenantConnectionForCompany("acme")!!.use { tenant ->
            val permissions = ModulePermissions("acme", "", true, emptySet(), ownerId, activityActor = owner())
            // Nothing captured here: the rows are really written, in the transaction of the change.
            val boardId = TareasService.createBoard(tenant, permissions, "Ventas", "", BoardVisibility.Company, emptyList())
            assertEquals("1", scalar("SELECT count(*) FROM activity_log WHERE module_id = 'tareas' AND entity_type = 'board' AND entity_id = '$boardId'"))
            assertEquals("Ana Ruiz", scalar("SELECT actor_display FROM activity_log WHERE entity_id = '$boardId'"))
            val created = TareasService.createTask(tenant, access, permissions, TaskInput(boardId, title = "Una tarea"))
            assertEquals("1", scalar("SELECT count(*) FROM activity_log WHERE entity_type = 'task' AND entity_id = '${created.id}' AND action = 'created'"))
            // The row cannot be written: the task is not created either.
            ActivityLog.capturing({ _, _ -> error("tabla sin permiso") }) {
                assertFailsWith<IllegalStateException> { TareasService.createTask(tenant, access, permissions, TaskInput(boardId, title = "Otra tarea")) }
            }
            assertEquals("0", scalar("SELECT count(*) FROM tareas_tasks WHERE title = 'Otra tarea'"))
            assertEquals("0", scalar("SELECT count(*) FROM tareas_activity WHERE task_id NOT IN (SELECT id FROM tareas_tasks)"))
        }
    }
}
