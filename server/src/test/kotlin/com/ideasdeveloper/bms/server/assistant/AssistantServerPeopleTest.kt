package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ServerActivity
import com.ideasdeveloper.bms.server.activity.ServerAuditMcpTools
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerActorKind
import com.ideasdeveloper.bms.server.admins.ServerActorLookup
import com.ideasdeveloper.bms.server.admins.ServerAdminService
import com.ideasdeveloper.bms.server.admins.ServerAudit
import com.ideasdeveloper.bms.server.admins.ServerPermission
import com.ideasdeveloper.bms.server.auth.application.SessionService
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationSessionRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationUserRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.security.ApplicationPasswordHasher
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningConfig
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.ServerMcpTools
import com.ideasdeveloper.bms.server.company.application.ServerOwnerAuthorizer
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpPreviewRow
import com.ideasdeveloper.bms.server.modules.McpSchema
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.RegisteredMcpTool
import com.ideasdeveloper.bms.server.modules.RegisteredModuleMcp
import com.ideasdeveloper.bms.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.bms.server.modules.ServerMcpModuleId
import com.ideasdeveloper.bms.server.security.MasterKeySecretBox
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The assistant for the server owner and the administrators (Core 0.14.1, GLOBAL_AGENT.md "App contract changes"), on a real
 * PostgreSQL with the model scripted (no network, no real key): the server workspace with the owner's tools and the
 * administrator's audit tools only, a company business owner that never sees a server tool, the server turning a company on,
 * and switcher sessions of the owner and of an administrator (with and without the rights), audited with the real person.
 * Runs only with BMS_TEST_PG_URL (a DISPOSABLE server): it creates and drops bms_asp_* databases and the roles asp_owner and
 * bms_asp_runtime.
 */
class AssistantServerPeopleTest {
    private val url: String? = (System.getenv("BMS_TEST_PG_URL") ?: System.getenv("BMS_ACTIVITY_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_asp_central"
    private val workspaceDb = "bms_asp_servidor"
    private val acmeDb = "bms_asp_acme"
    private val databases = listOf(central, workspaceDb, acmeDb)
    private val opened = mutableListOf<LoginDatabases>()
    private val ownerId = UUID.randomUUID()
    private val password = "clave-de-prueba-123"
    private val initial = "inicial-0123456789"
    private val changed = "cambiada-0123456789"
    // Made up for the test, never a real key; the scripted model never sends it anywhere.
    private val serverKey = "sk-ant-test-server-" + UUID.randomUUID().toString().replace("-", "")

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private fun urlOf(database: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$database$1")
    private fun connect(database: String): Connection = DriverManager.getConnection(urlOf(database))
    private fun source(database: String) = PGSimpleDataSource().apply { setURL(urlOf(database)) }
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }
    private val prefix get() = urlOf("X").substringBefore("/X") + "/"
    private val pgUser get() = Regex("user=([^&]+)").find(url!!)?.groupValues?.get(1) ?: "postgres"

    @AfterTest
    fun close() {
        opened.forEach { it.close() }
        if (url == null) return
        DriverManager.getConnection(url).use { admin -> databases.forEach { admin.run("DROP DATABASE IF EXISTS $it WITH (FORCE)") } }
    }

    private fun scalar(database: String, sql: String): String? = connect(database).use { db ->
        db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
    }

    private fun reset() {
        DriverManager.getConnection(url!!).use { admin ->
            databases.forEach { admin.run("DROP DATABASE IF EXISTS $it WITH (FORCE)") }
            admin.run("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'asp_owner') THEN CREATE ROLE asp_owner; END IF; END $$")
            admin.run("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'bms_asp_runtime') THEN CREATE ROLE bms_asp_runtime LOGIN PASSWORD 'runtime-pass'; END IF; END $$")
            admin.run("REVOKE asp_owner FROM bms_asp_runtime")
            databases.forEach { admin.run("CREATE DATABASE $it OWNER asp_owner") }
        }
        connect(central).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            File(root, "database/control/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                .forEach { db.run(it.readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', 'asp_owner', TRUE, 'Duena', 'duena@example.com')")
            db.run("INSERT INTO server_owners (user_id) VALUES ('$ownerId')")
            db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), 'acme', 'Acme SA', '$acmeDb', gen_random_uuid())")
            db.run("INSERT INTO companies (id, code, name, database_name, owner_group, is_server_workspace) VALUES (gen_random_uuid(), 'servidor', 'Empresa del servidor', '$workspaceDb', gen_random_uuid(), TRUE)")
        }
        listOf(workspaceDb, acmeDb).forEach { database ->
            connect(database).use { db ->
                db.run("SET ROLE asp_owner")
                listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code")
                    .forEach { db.run(migration("database/core/migrations/$it.sql")) }
                listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials")
                    .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
                db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('demo', 'Demo', 'enabled', CURRENT_TIMESTAMP)")
                listOf("demo.view", "demo.write", "empresa.assistant.use").forEach {
                    db.run("INSERT INTO application_permissions (permission_id, module_id, title) VALUES ('$it', '${it.substringBefore('.')}', '$it')")
                }
            }
        }
        connect(workspaceDb).use { db ->
            // A staff account of the workspace (a company user that signs in with the code "servidor").
            db.run("SET ROLE asp_owner")
            val hash = ApplicationPasswordHasher.hash(password)
            val id = UUID.randomUUID()
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$id', 'staff_srv', TRUE, 'Personal', 'staff@example.com')")
            db.run("INSERT INTO application_user_credentials (user_id, password_salt, password_hash, iterations) VALUES ('$id', '${hash.salt}', '${hash.hash}', ${hash.iterations})")
        }
        connect(acmeDb).use { db ->
            db.run("SET ROLE asp_owner")
            val hash = ApplicationPasswordHasher.hash(password)
            listOf("dueno_acme" to "Dueno Acme", "eva_acme" to "Eva Acme").forEach { (login, name) ->
                val id = UUID.randomUUID()
                db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$id', '$login', TRUE, '$name', '$login@example.com')")
                db.run("INSERT INTO application_user_credentials (user_id, password_salt, password_hash, iterations) VALUES ('$id', '${hash.salt}', '${hash.hash}', ${hash.iterations})")
                if (login == "dueno_acme") db.run("INSERT INTO business_owner (user_id) VALUES ('$id')")
            }
            db.run("INSERT INTO application_user_permissions (user_id, permission_id) SELECT id, p FROM application_users, unnest(ARRAY['demo.view', 'demo.write']) p WHERE postgres_role = 'eva_acme'")
        }
    }

    private fun loginDatabases(): LoginDatabases {
        val data = source(central)
        return LoginDatabases(data, Database.connect(data), urlOf(central), "[]", pgUser, "", prefix, 3600).also { opened += it }
    }

    private fun provisioning(databases: LoginDatabases) = CompanyProvisioningService(
        central = source(central),
        config = CompanyProvisioningConfig(urlOf("postgres").substringBefore("?"), "bms_asp_runtime", "runtime-pass", prefix, root.path, provisioningUser = pgUser, provisioningPassword = ""),
        loginDatabases = databases,
        moduleRegistry = ModuleRegistry(emptyList()),
        remoteModuleCatalog = RemoteModuleCatalog(),
        defaultCatalogUrl = "",
        modulePackagesRoot = java.nio.file.Files.createTempDirectory("asp-modules"),
        modulePackageBuilder = null,
        requestRestart = {},
    )

    // A module of the test: one read and one write, the write records its activity row as a real module does.
    private val demo = RegisteredModuleMcp(
        "demo", "Demo", "Notas de prueba.",
        listOf(
            RegisteredMcpTool("demo", "Demo", McpTool("list_notes", "Listar notas", "Lista las notas.", "demo.view", McpSchema.obj(), readOnly = true) {
                JsonPrimitive("{\"notes\":[\"una\"]}")
            }),
            RegisteredMcpTool("demo", "Demo", McpTool("add_note", "Agregar nota", "Agrega una nota.", "demo.write",
                McpSchema.obj(mapOf("texto" to McpSchema.string("Texto de la nota.")), listOf("texto"))) { call ->
                call.access.activity.record(call.tenant, call.activityActor, ActivityEntry("demo", "note", "1", "Nota", "created", "Creo una nota."))
                buildJsonObject { put("id", 1) }
            }),
        ),
    )

    /** What the server's own tools did, so the test can tell a tool ran (the real ones live in the provisioning service). */
    private val serverRan = mutableListOf<String>()

    private fun serverModule(audit: ServerAuditMcpTools): RegisteredModuleMcp {
        fun tool(name: String, readOnly: Boolean, destructive: Boolean = false, preview: ((com.ideasdeveloper.bms.server.modules.McpToolCall) -> List<McpPreviewRow>)? = null) =
            RegisteredMcpTool(
                ServerMcpModuleId, "Servidor",
                McpTool(
                    name, name, "Herramienta $name.", "server_owner",
                    McpSchema.obj(mapOf("code" to McpSchema.string("Codigo de la empresa.")), emptyList()),
                    readOnly = readOnly, destructive = destructive, preview = preview,
                ) { call ->
                    serverRan += "$name/${call.agentName}/${call.permissions.userId}"
                    buildJsonObject { put("ok", name) }
                },
            )
        return RegisteredModuleMcp(
            ServerMcpModuleId, "Servidor", ServerMcpTools.Instructions,
            listOf(
                tool("list_companies", readOnly = true),
                tool("set_company_active", readOnly = false) { call -> listOf(McpPreviewRow("Empresa", "Acme SA (${call.string("code")})")) },
                tool("update_company", readOnly = false),
                tool("list_extensions", readOnly = true),
                tool("delete_company", readOnly = false, destructive = true) { call -> listOf(McpPreviewRow("Empresa que se elimina", "Acme SA (${call.string("code")})")) },
                tool("remove_module", readOnly = false, destructive = true) { call -> listOf(McpPreviewRow("Modulo", call.string("code"))) },
            ) + audit.tools.map { RegisteredMcpTool(ServerMcpModuleId, "Servidor", it) },
        )
    }

    private fun events(body: String): List<Pair<String, JsonObject>> = body.split("\n\n").mapNotNull { frame ->
        val name = frame.lines().firstOrNull { it.startsWith("event: ") }?.removePrefix("event: ") ?: return@mapNotNull null
        val data = frame.lines().first { it.startsWith("data: ") }.removePrefix("data: ")
        name to Json.parseToJsonElement(data).jsonObject
    }

    private fun JsonObject.text(key: String) = (this[key] as JsonPrimitive).content

    private fun toolNames(models: FakeModelClients, request: Int) = models.body(request)["tools"]?.jsonArray.orEmpty().map { it.jsonObject.text("name") }

    /** Everything a scenario needs: the databases, the services over them, the owner's session and an administrator, Ana. */
    private inner class Env {
        val logins: LoginDatabases
        val data = source(central)
        val box = MasterKeySecretBox(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
        lateinit var access: ModuleAccessService
        val actors: ServerOwnerAuthorizer
        val admins: ServerAdminService
        val owner = ServerActor(ownerId, "asp_owner", ServerActorKind.Owner, emptySet(), true, emptySet(), "asp_owner")
        val models = FakeModelClients()
        val service: AssistantService
        val ownerToken = SessionTokenGenerator().generate().value
        val anaId: UUID
        val anaToken: String
        val dueno: com.ideasdeveloper.bms.server.auth.domain.LoginSuccessResponse

        init {
            reset()
            logins = loginDatabases()
            provisioning(logins).applyPendingModuleMigrations()
            val audit = ServerAuditMcpTools(
                central = data, audit = { ServerAudit(data) },
                activity = { ServerActivity(data, { access.tenantConnectionForDatabase(it) }, { it }) },
                liveActor = { ServerOwnerAuthorizer.liveActor(logins.admins, data, it) },
            )
            val modules = listOf(serverModule(audit), demo)
            access = ModuleAccessService(data, logins, moduleInstalled = { true }, moduleMcp = { modules }, secrets = box)
            val exposed = Database.connect(data)
            val sessions = SessionService(ApplicationSessionRepository(exposed), ApplicationUserRepository(exposed), SessionTokenGenerator(), 3600)
            actors = ServerOwnerAuthorizer(data, sessions)
            admins = ServerAdminService(
                store = logins.admins,
                loginTaken = { login -> logins.admins.centralLoginTaken(login) },
                revokeCompanySessions = { logins.revokeCompanySessionsOpenedBy(it) },
                audit = { actor, action, details -> data.connection.use { ProvisioningAudit.insert(it, actor, action, null, null, details) } },
                deactivateWorkspaceStaff = { _, _, _ -> },
            )
            service = AssistantService(
                access, AssistantServerConfig(data, box), box, models,
                serverActors = actors, liveServerActor = { ServerOwnerAuthorizer.liveActor(logins.admins, data, it) },
            )
            // The server owner's central session, and an administrator (Ana) who changed her initial password.
            connect(central).use { it.run("INSERT INTO application_sessions (token_hash, user_id, expires_at) VALUES ('${SessionTokenGenerator().hash(ownerToken)}', '$ownerId', CURRENT_TIMESTAMP + INTERVAL '1 day')") }
            val ana = admins.create(owner, "ana", "Ana Admin", "ana@example.com", initial, listOf(ServerPermission.AuditView.id), allCompanies = false, companies = listOf("acme"))
            anaId = UUID.fromString(ana.id)
            val anaLogin = logins.service.login(LoginCredentials("ana", initial))!!
            admins.changeOwnPassword((actors.resolve(anaLogin.accessToken) as ServerActorLookup.PasswordChangeRequired).actor, anaLogin.accessToken, initial, changed)
            anaToken = anaLogin.accessToken
            dueno = logins.service.login(LoginCredentials("dueno_acme", password, "acme").withClientAddress("10.3.3.3"))!!
        }

        fun grant(vararg permissions: ServerPermission, scope: List<String> = listOf("acme")) {
            admins.setPermissions(owner, anaId.toString(), permissions.map { it.id }, false, scope)
        }

        /** Ana's company session (the switcher), or null when she may not enter. */
        fun anaInAcme() = logins.switchTo(null, anaToken, "acme")

        /** The owner's company session (the switcher). */
        fun ownerInAcme() = logins.switchTo(null, ownerToken, "acme")

        fun ana() = ServerOwnerAuthorizer.liveActor(logins.admins, data, anaId).let { (it as ServerActorLookup.Found).actor }
    }

    private inner class Api(val client: io.ktor.client.HttpClient) {
        suspend fun call(method: String, path: String, token: String, company: String?, body: String? = null): HttpResponse {
            val configure: HttpRequestBuilder.() -> Unit = {
                header(HttpHeaders.Authorization, "Bearer $token")
                if (company != null) header("X-Company-Code", company)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            return when (method) {
                "GET" -> client.get(path, configure)
                "POST" -> client.post(path, configure)
                "PUT" -> client.put(path, configure)
                else -> client.delete(path, configure)
            }
        }

        suspend fun json(method: String, path: String, token: String, company: String?, body: String? = null) =
            Json.parseToJsonElement(call(method, path, token, company, body).bodyAsText()).jsonObject

        suspend fun status(token: String, company: String) = json("GET", "/companies/me/assistant/status", token, company)

        /** A server route (no company header). */
        suspend fun server(method: String, path: String, token: String, body: String? = null) = call(method, path, token, null, body)

        /** One turn (or one decision): the events of its stream. */
        suspend fun turn(path: String, token: String, company: String, body: String) = events(call("POST", path, token, company, body).bodyAsText())
    }

    private fun inApp(env: Env, block: suspend Api.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                assistantRoutes(env.service)
                assistantServerRoutes(env.actors, AssistantServerAdmin(env.data, env.service))
            }
        }
        Api(client).block()
    }

    private fun JsonObject.flag(key: String) = this[key]!!.jsonPrimitive.boolean

    @Test
    fun theOwnerAndTheAdministratorsInTheServerWorkspaceAndAnOrdinaryBusinessOwner() {
        if (url == null) return
        val env = Env()
        // The conversations of server people reference no company user (tenant V021): the workspace has none for them.
        assertEquals("0", scalar(workspaceDb, "SELECT count(*) FROM pg_constraint WHERE conrelid = 'assistant_conversations'::regclass AND contype = 'f'"))
        assertEquals("1", scalar(workspaceDb, "SELECT count(*) FROM schema_migrations WHERE version LIKE 'V021__assistant_server_people%'"))
        inApp(env) {
            val ownerToken = env.ownerToken
            val anaToken = env.anaToken
            // 1. Off at server level: the owner sees the entry (to turn it on) in the workspace; the administrator does not.
            val off = status(ownerToken, "servidor")
            assertEquals("SERVER_DISABLED", off.text("reason"))
            assertTrue(off.flag("visible") && off.flag("canManage") && off.flag("workspace") && off.flag("serverPerson"), off.toString())
            env.grant(ServerPermission.AuditView)
            assertFalse(status(anaToken, "servidor").flag("visible"))
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/settings", ownerToken, """{"enabled":true,"acceptDisclosure":true}""").status)
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/key", ownerToken, """{"apiKey":"$serverKey"}""").status)

            // 2. The owner uses it in the workspace with no company permission: its tools are the owner's (server tools and the
            //    modules enabled there); a write waits for the card (with its preview), a read runs at once.
            val ready = status(ownerToken, "servidor")
            assertTrue(ready.flag("available"), ready.toString())
            assertEquals("server", ready.text("keySource"))
            val ownerConversation = json("POST", "/companies/me/assistant/conversations", ownerToken, "servidor", "{}").text("id")
            env.models.add(
                Scripted.message(
                    "tool_use", Scripted.text("Reviso."), Scripted.toolUse("toolu_1", "servidor_list_companies"),
                    Scripted.toolUse("toolu_2", "servidor_set_company_active", buildJsonObject { put("code", "acme") }),
                ),
            )
            val first = turn("/companies/me/assistant/conversations/$ownerConversation/messages", ownerToken, "servidor", """{"text":"cambia acme"}""")
            assertEquals("awaiting_confirmation", first.last().second.text("reason"), first.toString())
            val offered = toolNames(env.models, 0)
            assertTrue(offered.containsAll(listOf("demo_add_note", "demo_list_notes", "servidor_list_companies", "servidor_set_company_active", "servidor_list_server_audit")), offered.toString())
            // Irreversible tools are never offered to the assistant (they are done from the app), though the server module has them.
            assertTrue(offered.none { it == "servidor_delete_company" || it == "servidor_remove_module" }, offered.toString())
            val card = first.single { it.first == "confirmation_required" }.second
            assertEquals("Servidor: set_company_active", card.text("label"))
            assertEquals("acme", card["summary"]!!.jsonArray[0].jsonObject.text("value"))
            assertEquals("Acme SA (acme)", card["preview"]!!.jsonArray[0].jsonObject.text("value"))
            assertEquals(listOf("list_companies/Asistente/${ownerId}"), serverRan)
            // The conversation is in the workspace database, the owner's own (central id, no company user).
            assertEquals("$ownerId", scalar(workspaceDb, "SELECT user_id FROM assistant_conversations WHERE id = '$ownerConversation'"))
            // Approved: it runs, and the server audit says it was the owner's assistant.
            env.models.add(Scripted.message("end_turn", Scripted.text("Hecho."), input = 500, output = 50))
            val approved = turn("/companies/me/assistant/confirmations/${card.text("confirmationId")}", ownerToken, "servidor", """{"decision":"approve"}""")
            assertEquals("end_turn", approved.last().second.text("reason"), approved.toString())
            assertEquals(listOf("list_companies/Asistente/$ownerId", "set_company_active/Asistente/$ownerId"), serverRan)
            assertEquals(
                "server_owner|Asistente de Duena|servidor_set_company_active|acme",
                scalar(central, "SELECT actor_kind || '|' || ((details::jsonb)->>'actor') || '|' || ((details::jsonb)->>'tool') || '|' || company_code FROM provisioning_audit_log WHERE action = 'assistant.server_tool'"),
            )
            // Usage is on the server key under the workspace's own entry, and the server list shows that entry to the owner.
            assertEquals("server|2", scalar(workspaceDb, "SELECT key_source || '|' || requests FROM assistant_usage"))
            val list = json("GET", "/server/assistant/companies", ownerToken, null)["companies"]!!.jsonArray.map { it.jsonObject }
            assertEquals("servidor", list.first().text("code"))
            assertTrue(list.first().flag("serverWorkspace") && list.first().flag("enabled") && list.first().flag("companyEnabled") && list.first().text("code") == "servidor")
            assertEquals(1, list.count { it.flag("serverWorkspace") })
            assertTrue(json("GET", "/server/assistant/usage", ownerToken, null)["companies"]!!.jsonArray.first().jsonObject.text("serverKeyTokens").toLong() > 0)
            // Its allowance and limit are its own row; its switch is the server's.
            assertEquals(HttpStatusCode.Conflict, server("PUT", "/server/assistant/companies/servidor", ownerToken, """{"enabled":false}""").status)
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/companies/servidor", ownerToken, """{"allowed":false}""").status)
            assertEquals("COMPANY_NOT_ALLOWED", status(ownerToken, "servidor").text("reason"))
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/companies/servidor", ownerToken, """{"allowed":true,"monthlyTokens":10}""").status)
            assertEquals("LIMIT_REACHED", status(ownerToken, "servidor").text("reason"))
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/companies/servidor", ownerToken, """{"allowed":true}""").status)
            assertTrue(status(ownerToken, "servidor").flag("available"))
            // Nobody else reads the owner's conversation, not even an administrator with the right.
            env.grant(ServerPermission.AuditView, ServerPermission.AssistantUse)
            assertEquals(HttpStatusCode.NotFound, call("GET", "/companies/me/assistant/conversations/$ownerConversation", anaToken, "servidor").status)
            // The workspace row is the owner's: an administrator (even with every company) neither lists nor edits it.
            env.admins.setPermissions(env.owner, env.anaId.toString(), listOf(ServerPermission.AssistantManage.id), true, emptyList())
            assertTrue(json("GET", "/server/assistant/companies", anaToken, null)["companies"]!!.jsonArray.none { it.jsonObject.text("code") == "servidor" })
            assertEquals(HttpStatusCode.Forbidden, server("PUT", "/server/assistant/companies/servidor", anaToken, """{"allowed":false}""").status)

            // 3. An administrator without server.assistant.use is refused; with it, and server.audit.view, she gets the two
            //    audit tools only, whatever the model names, and what she does is audited as her assistant.
            env.grant(ServerPermission.AuditView)
            val denied = status(anaToken, "servidor")
            assertEquals("PERMISSION_REQUIRED", denied.text("reason"))
            assertFalse(denied.flag("available") || denied.flag("canManage"))
            assertEquals(HttpStatusCode.Forbidden, call("POST", "/companies/me/assistant/conversations", anaToken, "servidor", "{}").status)
            env.grant(ServerPermission.AuditView, ServerPermission.AssistantUse)
            assertTrue(status(anaToken, "servidor").flag("available"))
            val anaConversation = json("POST", "/companies/me/assistant/conversations", anaToken, "servidor", "{}").text("id")
            serverRan.clear()
            env.models.add(
                Scripted.message(
                    "tool_use", Scripted.toolUse("toolu_a", "servidor_set_company_active", buildJsonObject { put("code", "acme") }),
                    Scripted.toolUse("toolu_b", "demo_list_notes"), Scripted.toolUse("toolu_c", "servidor_list_server_audit"),
                ),
                Scripted.message("end_turn", Scripted.text("Listo.")),
            )
            val anaTurn = turn("/companies/me/assistant/conversations/$anaConversation/messages", anaToken, "servidor", """{"text":"mira la auditoria"}""")
            assertEquals("end_turn", anaTurn.last().second.text("reason"), anaTurn.toString())
            assertEquals(listOf("servidor_list_company_activity", "servidor_list_server_audit"), toolNames(env.models, env.models.requests.size - 2))
            val finished = anaTurn.filter { it.first == "tool_finished" }.associate { it.second.text("toolUseId") to it.second.text("status") }
            assertEquals(mapOf("toolu_a" to "error", "toolu_b" to "error", "toolu_c" to "ok"), finished)
            assertTrue(serverRan.isEmpty(), serverRan.toString())
            assertEquals("server_admin|Asistente", scalar(central, "SELECT actor_kind || '|' || ((details::jsonb)->>'agent') FROM provisioning_audit_log WHERE action = 'server_audit.agent_read'"))
            assertEquals("${env.anaId}", scalar(central, "SELECT actor_user_id FROM provisioning_audit_log WHERE action = 'server_audit.agent_read'"))
            // Losing the audit permission takes the tools away at once (re-read on every call), and an administrator never
            // gets the owner's tools nor the server settings.
            env.grant(ServerPermission.AssistantUse)
            val grants = ServerAdminGrants(env.access, env.anaId) { ServerOwnerAuthorizer.liveActor(env.logins.admins, env.data, it) }
            assertNull(env.access.tenantConnectionForCompany("servidor")!!.use { grants.grants(it) })
            assertEquals(HttpStatusCode.Forbidden, server("PUT", "/server/assistant/settings", anaToken, """{"model":"claude-sonnet-5-5"}""").status)

            // 4. A company business owner never gets a server tool, even when the model names it.
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/companies/acme", ownerToken, """{"enabled":true,"acceptDisclosure":true}""").status)
            val dueConversation = json("POST", "/companies/me/assistant/conversations", env.dueno.accessToken, "acme", "{}").text("id")
            serverRan.clear()
            env.models.add(
                Scripted.message("tool_use", Scripted.toolUse("toolu_s", "servidor_delete_company", buildJsonObject { put("code", "acme") }), Scripted.toolUse("toolu_t", "demo_list_notes")),
                Scripted.message("end_turn", Scripted.text("No puedo.")),
            )
            val dueTurn = turn("/companies/me/assistant/conversations/$dueConversation/messages", env.dueno.accessToken, "acme", """{"text":"borra la empresa"}""")
            assertEquals("end_turn", dueTurn.last().second.text("reason"), dueTurn.toString())
            assertEquals(listOf("demo_add_note", "demo_list_notes"), toolNames(env.models, env.models.requests.size - 2))
            assertEquals(mapOf("toolu_s" to "error", "toolu_t" to "ok"), dueTurn.filter { it.first == "tool_finished" }.associate { it.second.text("toolUseId") to it.second.text("status") })
            assertTrue(serverRan.isEmpty())
            // A business owner naming the workspace gets nothing: its token is no central session.
            assertEquals(HttpStatusCode.Unauthorized, call("GET", "/companies/me/assistant/status", env.dueno.accessToken, "servidor").status)
            // The staff accounts of the workspace stay out: the one case left for SERVER_WORKSPACE.
            val staff = env.logins.service.login(LoginCredentials("staff_srv", password, "servidor").withClientAddress("10.3.3.4"))!!
            val staffStatus = status(staff.accessToken, "servidor")
            assertEquals("SERVER_WORKSPACE", staffStatus.text("reason"))
            assertFalse(staffStatus.flag("visible") || staffStatus.flag("available") || staffStatus.flag("canManage"))
            val staffRefused = call("POST", "/companies/me/assistant/conversations", staff.accessToken, "servidor", "{}")
            assertEquals(HttpStatusCode.Forbidden, staffRefused.status)
            assertTrue(staffRefused.bodyAsText().contains("SERVER_WORKSPACE"))
        }
        // Retention covers the workspace: its old conversations go with their messages.
        connect(workspaceDb).use { it.run("UPDATE assistant_conversations SET updated_at = CURRENT_TIMESTAMP - INTERVAL '400 days'") }
        env.service.daily("servidor", workspaceDb)
        assertEquals("0", scalar(workspaceDb, "SELECT count(*) FROM assistant_conversations"))
        assertEquals("0", scalar(workspaceDb, "SELECT count(*) FROM assistant_messages"))
        assertTrue(scalar(workspaceDb, "SELECT count(*) FROM assistant_usage")!!.toInt() > 0)
    }

    @Test
    fun aServerPersonTurnsACompanyOnFromTheServerListAndUsesItInSwitcherSessions() {
        if (url == null) return
        val env = Env()
        inApp(env) {
            val ownerToken = env.ownerToken
            val anaToken = env.anaToken
            val due = env.dueno.accessToken
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/settings", ownerToken, """{"enabled":true,"acceptDisclosure":true}""").status)
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/key", ownerToken, """{"apiKey":"$serverKey"}""").status)

            // 1. The business owner has not turned it on: nobody can use it, the business owner is told it can be turned on.
            val disabled = status(due, "acme")
            assertEquals("COMPANY_DISABLED", disabled.text("reason"))
            assertTrue(disabled.flag("canManage") && !disabled.flag("serverPerson") && !disabled.flag("workspace"))

            // 2. From the server list: the disclosure is required; an administrator out of scope (or without the permission) is refused.
            assertEquals(HttpStatusCode.BadRequest, server("PUT", "/server/assistant/companies/acme", ownerToken, """{"enabled":true}""").status)
            assertEquals(HttpStatusCode.BadRequest, server("PUT", "/server/assistant/companies/acme", ownerToken, "{}").status)
            env.grant(ServerPermission.AssistantManage, scope = emptyList())
            val outOfScope = server("PUT", "/server/assistant/companies/acme", anaToken, """{"enabled":true,"acceptDisclosure":true}""")
            assertEquals(HttpStatusCode.Forbidden, outOfScope.status)
            assertTrue(outOfScope.bodyAsText().contains("SERVER_PERMISSION_REQUIRED"))
            assertEquals("0", scalar(acmeDb, "SELECT count(*) FROM assistant_settings WHERE enabled"))
            env.grant(ServerPermission.AuditView)
            assertEquals(HttpStatusCode.Forbidden, server("PUT", "/server/assistant/companies/acme", anaToken, """{"enabled":true,"acceptDisclosure":true}""").status)
            // In scope with server.assistant.manage the administrator turns it on, recorded as hers.
            env.grant(ServerPermission.AssistantManage)
            val turnedOn = json("PUT", "/server/assistant/companies/acme", anaToken, null, """{"enabled":true,"acceptDisclosure":true}""")
            assertTrue(turnedOn.flag("enabled"), turnedOn.toString())
            assertEquals("1", scalar(acmeDb, "SELECT count(*) FROM assistant_settings WHERE enabled AND disclosure_accepted_at IS NOT NULL"))
            assertEquals(
                "server_admin|Ana Admin (desde el servidor)|${env.anaId}",
                scalar(acmeDb, "SELECT actor_kind || '|' || actor_display || '|' || actor_user_id FROM activity_log WHERE entity_type = 'assistant_settings' AND action = 'settings_changed'"),
            )
            assertEquals(
                "server_admin|${env.anaId}|true|false|server",
                scalar(central, "SELECT actor_kind || '|' || actor_user_id || '|' || ((details::jsonb)->>'disclosureAccepted') || '|' || ((details::jsonb)->>'enabled.from') || '|' || ((details::jsonb)->>'via') FROM provisioning_audit_log WHERE action = 'company.assistant_changed' AND company_code = 'acme'"),
            )
            // The owner turns it off and on again; the allowance is untouched by a body with no `allowed`.
            assertFalse(json("PUT", "/server/assistant/companies/acme", ownerToken, null, """{"companyEnabled":false}""").flag("companyEnabled"))
            assertEquals("COMPANY_DISABLED", status(due, "acme").text("reason"))
            assertTrue(json("PUT", "/server/assistant/companies/acme", ownerToken, null, """{"enabled":true,"acceptDisclosure":true,"allowed":true,"monthlyTokens":5000000}""").flag("enabled"))
            assertTrue(status(due, "acme").flag("available"))
            assertEquals("server_owner", scalar(central, "SELECT actor_kind FROM provisioning_audit_log WHERE action = 'company.assistant_changed' AND ((details::jsonb)->>'enabled.to') = 'false'"))
            // Back to off for the switcher scenarios.
            json("PUT", "/server/assistant/companies/acme", ownerToken, null, """{"enabled":false}""")

            // 3. The owner enters the company from the switcher: it may read and change the company's assistant settings, turn it on
            //    (the activity row and the server audit name the real person), and use it with no company permission.
            val ownerSession = env.ownerInAcme()!!.accessToken
            val seen = status(ownerSession, "acme")
            assertEquals("COMPANY_DISABLED", seen.text("reason"))
            assertTrue(seen.flag("canManage") && seen.flag("serverPerson") && seen.flag("visible") && !seen.flag("available"), seen.toString())
            assertEquals(HttpStatusCode.OK, call("GET", "/companies/me/assistant/settings", ownerSession, "acme").status)
            assertEquals(HttpStatusCode.BadRequest, call("PUT", "/companies/me/assistant/settings", ownerSession, "acme", """{"enabled":true}""").status)
            val enabled = json("PUT", "/companies/me/assistant/settings", ownerSession, "acme", """{"enabled":true,"acceptDisclosure":true}""")
            assertTrue(enabled.flag("enabled"))
            assertEquals(
                "business_owner|server_owner|${ownerId}",
                scalar(acmeDb, "SELECT actor_kind || '|' || real_actor_kind || '|' || real_actor_user_id FROM activity_log WHERE entity_type = 'assistant_settings' AND real_actor_kind = 'server_owner' ORDER BY id DESC LIMIT 1"),
            )
            assertEquals(
                "server_owner|$ownerId|switcher",
                scalar(central, "SELECT actor_kind || '|' || actor_user_id || '|' || ((details::jsonb)->>'via') FROM provisioning_audit_log WHERE action = 'company.assistant_changed' AND company_code = 'acme' AND ((details::jsonb)->>'via') = 'switcher'"),
            )
            assertEquals(HttpStatusCode.OK, call("GET", "/companies/me/assistant/usage", ownerSession, "acme").status)
            val usable = status(ownerSession, "acme")
            assertTrue(usable.flag("available"), usable.toString())
            val ownerConversation = json("POST", "/companies/me/assistant/conversations", ownerSession, "acme", "{}").text("id")
            env.models.add(Scripted.message("tool_use", Scripted.toolUse("toolu_1", "demo_add_note", buildJsonObject { put("texto", "hola") }), Scripted.toolUse("toolu_x", "servidor_list_companies")))
            val paused = turn("/companies/me/assistant/conversations/$ownerConversation/messages", ownerSession, "acme", """{"text":"agrega una nota"}""")
            assertEquals("awaiting_confirmation", paused.last().second.text("reason"), paused.toString())
            // The business owner's tools of the company, never the server's, even for the server owner.
            assertEquals(listOf("demo_add_note", "demo_list_notes"), toolNames(env.models, 0))
            assertEquals("error", paused.single { it.first == "tool_finished" }.second.text("status"))
            // Its conversations are its own: neither the business owner nor an administrator's session sees them.
            assertEquals(HttpStatusCode.NotFound, call("GET", "/companies/me/assistant/conversations/$ownerConversation", due, "acme").status)
            val card = paused.single { it.first == "confirmation_required" }.second
            env.models.add(Scripted.message("end_turn", Scripted.text("Listo.")))
            val done = turn("/companies/me/assistant/confirmations/${card.text("confirmationId")}", ownerSession, "acme", """{"decision":"approve"}""")
            assertEquals("end_turn", done.last().second.text("reason"), done.toString())
            assertEquals(
                "business_owner|server_owner|$ownerId|assistant|Asistente",
                scalar(acmeDb, "SELECT actor_kind || '|' || real_actor_kind || '|' || real_actor_user_id || '|' || origin || '|' || agent_name FROM activity_log WHERE module_id = 'demo' AND action = 'created'"),
            )
            assertFalse(call("GET", "/companies/me/assistant/conversations", due, "acme").bodyAsText().contains(ownerConversation))

            // 4. An administrator in a switcher session: with no assistant permission she is refused everywhere; use and manage
            //    are separate, and neither needs empresa.assistant.use.
            env.grant(ServerPermission.CompaniesEnter, ServerPermission.AuditView)
            val anaSession = env.anaInAcme()!!.accessToken
            val noRights = status(anaSession, "acme")
            assertEquals("PERMISSION_REQUIRED", noRights.text("reason"))
            assertTrue(!noRights.flag("canManage") && !noRights.flag("visible") && noRights.flag("serverPerson"), noRights.toString())
            assertEquals(HttpStatusCode.Forbidden, call("POST", "/companies/me/assistant/conversations", anaSession, "acme", "{}").status)
            assertEquals(HttpStatusCode.Forbidden, call("GET", "/companies/me/assistant/settings", anaSession, "acme").status)
            assertEquals(HttpStatusCode.Forbidden, call("GET", "/companies/me/assistant/usage", anaSession, "acme").status)
            val refusedPut = call("PUT", "/companies/me/assistant/settings", anaSession, "acme", """{"enabled":false}""")
            assertEquals(HttpStatusCode.Forbidden, refusedPut.status)
            assertTrue(refusedPut.bodyAsText().contains("SERVER_PERMISSION_REQUIRED"))
            // Use without manage: she works with the business owner's tools in her own conversations; the settings stay closed.
            env.grant(ServerPermission.CompaniesEnter, ServerPermission.AssistantUse)
            val mayUse = status(anaSession, "acme")
            assertTrue(mayUse.flag("available") && !mayUse.flag("canManage"), mayUse.toString())
            assertEquals(HttpStatusCode.Forbidden, call("PUT", "/companies/me/assistant/settings", anaSession, "acme", """{"enabled":false}""").status)
            val anaConversation = json("POST", "/companies/me/assistant/conversations", anaSession, "acme", "{}").text("id")
            env.models.add(Scripted.message("tool_use", Scripted.toolUse("toolu_2", "demo_add_note", buildJsonObject { put("texto", "de ana") })))
            val anaPaused = turn("/companies/me/assistant/conversations/$anaConversation/messages", anaSession, "acme", """{"text":"otra nota"}""")
            val anaCard = anaPaused.single { it.first == "confirmation_required" }.second
            env.models.add(Scripted.message("end_turn", Scripted.text("Listo.")))
            turn("/companies/me/assistant/confirmations/${anaCard.text("confirmationId")}", anaSession, "acme", """{"decision":"approve"}""")
            assertEquals(
                "server_admin|${env.anaId}",
                scalar(acmeDb, "SELECT real_actor_kind || '|' || real_actor_user_id FROM activity_log WHERE module_id = 'demo' AND action = 'created' AND real_actor_kind = 'server_admin'"),
            )
            assertEquals(HttpStatusCode.NotFound, call("GET", "/companies/me/assistant/conversations/$ownerConversation", anaSession, "acme").status)
            // With manage she changes the company's switch from inside, audited as an administrator.
            env.grant(ServerPermission.CompaniesEnter, ServerPermission.AssistantUse, ServerPermission.AssistantManage)
            assertTrue(status(anaSession, "acme").flag("canManage"))
            assertFalse(json("PUT", "/companies/me/assistant/settings", anaSession, "acme", """{"enabled":false}""").flag("enabled"))
            assertEquals(
                "server_admin|${env.anaId}|switcher",
                scalar(central, "SELECT actor_kind || '|' || actor_user_id || '|' || ((details::jsonb)->>'via') FROM provisioning_audit_log WHERE action = 'company.assistant_changed' AND ((details::jsonb)->>'via') = 'switcher' AND actor_kind = 'server_admin'"),
            )
            // Out of scope (or without the right to enter any more): the session stops at its next request.
            env.grant(ServerPermission.AssistantUse, ServerPermission.AssistantManage, scope = emptyList())
            assertEquals(HttpStatusCode.Unauthorized, call("GET", "/companies/me/assistant/status", anaSession, "acme").status)
        }
    }

    /** The toolbox of the server workspace for [actor] (what a turn of that person gets). */
    private fun workspaceToolbox(env: Env, actor: ServerActor): AssistantToolbox {
        val context = env.access.tenantConnectionForCompany("servidor")!!.use { env.service.serverContext(it, actor) }!!
        return env.service.toolbox(context)
    }

    private fun notesIn(database: String) = scalar(database, "SELECT count(*) FROM activity_log WHERE module_id = 'demo'")

    @Test
    fun theWorkspaceAssistantNeverActsInAnotherCompanyNorIsToldItCan() {
        if (url == null) return
        val env = Env()
        val toolbox = workspaceToolbox(env, env.owner)
        // The model's guidance: the server's own text for the MCP agents says acting_company, the assistant's does not.
        assertTrue(ServerMcpTools.Instructions.contains("acting_company"))
        val guidance = toolbox.instructions().joinToString("\n") { it.second }
        assertTrue(guidance.contains("Administras el servidor") && !guidance.contains("acting_company") && !guidance.contains("list_group_companies"), guidance)
        assertTrue(toolbox.tools().none { it.description.contains("acting_company") })
        // A call naming acting_company is refused by execute and by preview, whatever the tool: nothing runs in the workspace.
        val refusal = "El asistente no actua en otra empresa desde el espacio del servidor; entra a esa empresa y usalo alli."
        val args = buildJsonObject { put("texto", "en acme"); put("acting_company", "acme") }
        val refused = toolbox.execute("demo_add_note", args)
        assertFalse(refused.ok)
        assertEquals(refusal, refused.text)
        assertEquals(refusal, toolbox.preview("demo_add_note", args).error)
        assertEquals(refusal, toolbox.execute("servidor_list_companies", buildJsonObject { put("Acting_Company", "acme") }).text)
        assertEquals("0", notesIn(workspaceDb))
        assertEquals("0", notesIn(acmeDb))
        // The same call without it is the ordinary one.
        assertTrue(toolbox.execute("demo_add_note", buildJsonObject { put("texto", "aqui") }).ok)
        assertEquals("1", notesIn(workspaceDb))
        assertEquals("0", notesIn(acmeDb))
        // In a turn: the model never reads it, and a call that names it ends as an error.
        inApp(env) {
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/settings", env.ownerToken, """{"enabled":true,"acceptDisclosure":true}""").status)
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/key", env.ownerToken, """{"apiKey":"$serverKey"}""").status)
            val conversation = json("POST", "/companies/me/assistant/conversations", env.ownerToken, "servidor", "{}").text("id")
            env.models.add(
                Scripted.message("tool_use", Scripted.toolUse("toolu_1", "demo_list_notes", buildJsonObject { put("acting_company", "acme") })),
                Scripted.message("end_turn", Scripted.text("No puedo.")),
            )
            val turn = turn("/companies/me/assistant/conversations/$conversation/messages", env.ownerToken, "servidor", """{"text":"lista las notas de acme"}""")
            assertEquals("end_turn", turn.last().second.text("reason"), turn.toString())
            assertEquals("error", turn.single { it.first == "tool_finished" }.second.text("status"))
            assertTrue(turn.single { it.first == "tool_finished" }.second.toString().contains("entra a esa empresa"))
            assertFalse(env.models.body(0).toString().replace("lista las notas de acme", "").contains("acting_company"))
        }
    }

    @Test
    fun readsOfCompaniesThatNeverOptedInWaitForACardAndWritesThatOpenAChannelAreFlagged() {
        if (url == null) return
        val env = Env()
        fun reads() = scalar(central, "SELECT count(*) FROM provisioning_audit_log WHERE action = 'server_audit.agent_read'")
        inApp(env) {
            val ownerToken = env.ownerToken
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/settings", ownerToken, """{"enabled":true,"acceptDisclosure":true}""").status)
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/key", ownerToken, """{"apiKey":"$serverKey"}""").status)
            val conversation = json("POST", "/companies/me/assistant/conversations", ownerToken, "servidor", "{}").text("id")
            val path = "/companies/me/assistant/conversations/$conversation/messages"
            fun read(id: String, company: String?) = Scripted.toolUse(id, "servidor_list_company_activity", buildJsonObject { company?.let { put("company", it) } })
            suspend fun decide(card: JsonObject, decision: String): List<Pair<String, JsonObject>> {
                env.models.add(Scripted.message("end_turn", Scripted.text("Listo.")))
                return turn("/companies/me/assistant/confirmations/${card.text("confirmationId")}", ownerToken, "servidor", """{"decision":"$decision"}""")
            }

            // 1. acme never turned the assistant on: its activity is NOT read by itself. A card says whose data goes where.
            env.models.add(Scripted.message("tool_use", read("toolu_1", "acme"), Scripted.toolUse("toolu_2", "servidor_list_companies")))
            val first = turn(path, ownerToken, "servidor", """{"text":"que paso en acme"}""")
            assertEquals("awaiting_confirmation", first.last().second.text("reason"), first.toString())
            val card = first.single { it.first == "confirmation_required" }.second
            assertTrue(card.flag("outbound") && !card.flag("destructive"), card.toString())
            assertEquals("Lee la actividad de Acme SA (acme) (no ha activado el asistente) y la envia al proveedor de IA.", card["preview"]!!.jsonArray[0].jsonObject.text("value"))
            assertEquals("acme", card["summary"]!!.jsonArray[0].jsonObject.text("value"))
            assertEquals("0", reads())
            assertEquals(listOf("list_companies/Asistente/$ownerId"), serverRan)
            // Approved, it reads (and the server audit records the read); the reply is the usual one.
            assertEquals("end_turn", decide(card, "approve").last().second.text("reason"))
            assertEquals("1", reads())

            // 2. "All companies" (no company) always waits, even when every company opted in; rejecting reads nothing.
            env.models.add(Scripted.message("tool_use", read("toolu_3", null)))
            val all = turn(path, ownerToken, "servidor", """{"text":"todo"}""")
            val allCard = all.single { it.first == "confirmation_required" }.second
            assertEquals("Lee la actividad de todas las empresas a tu alcance y la envia al proveedor de IA.", allCard["preview"]!!.jsonArray[0].jsonObject.text("value"))
            assertTrue(allCard.flag("outbound"))
            assertEquals("end_turn", decide(allCard, "reject").last().second.text("reason"))
            assertEquals("1", reads())

            // 3. acme turns it on (server allowance on by default + its own switch): its activity is read at once, no card.
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/companies/acme", ownerToken, """{"enabled":true,"acceptDisclosure":true}""").status)
            env.models.add(Scripted.message("tool_use", read("toolu_4", "acme")), Scripted.message("end_turn", Scripted.text("Nada.")))
            val direct = turn(path, ownerToken, "servidor", """{"text":"otra vez acme"}""")
            assertEquals("end_turn", direct.last().second.text("reason"), direct.toString())
            assertTrue(direct.none { it.first == "confirmation_required" })
            assertEquals("ok", direct.single { it.first == "tool_finished" }.second.text("status"))
            assertEquals("2", reads())
            val toolbox = workspaceToolbox(env, env.owner)
            assertFalse(toolbox.policy("servidor_list_company_activity", buildJsonObject { put("company", "acme") }).confirm)
            // 4. The server takes the allowance away again: the switch alone is not enough.
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/companies/acme", ownerToken, """{"allowed":false}""").status)
            assertTrue(toolbox.policy("servidor_list_company_activity", buildJsonObject { put("company", "acme") }).confirm)
            // 5. The extensions of a company that did not opt in wait too (the tool's own argument is `code`); an administrator gets the same rule.
            env.models.add(Scripted.message("tool_use", Scripted.toolUse("toolu_5", "servidor_list_extensions", buildJsonObject { put("code", "acme") })))
            val extensions = turn(path, ownerToken, "servidor", """{"text":"extensiones de acme"}""")
            val extCard = extensions.single { it.first == "confirmation_required" }.second
            assertEquals("Lee las extensiones y los ajustes de Acme SA (acme) (no ha activado el asistente) y los envia al proveedor de IA.", extCard["preview"]!!.jsonArray[0].jsonObject.text("value"))
            assertTrue(extCard.flag("outbound"))
            assertTrue(serverRan.none { it.startsWith("list_extensions") })
            decide(extCard, "approve")
            assertTrue(serverRan.any { it.startsWith("list_extensions") })
            env.grant(ServerPermission.AuditView, ServerPermission.AssistantUse)
            val anaToolbox = workspaceToolbox(env, env.ana())
            assertTrue(anaToolbox.policy("servidor_list_company_activity", buildJsonObject { put("company", "acme") }).confirm)
            assertTrue(anaToolbox.policy("servidor_list_company_activity", JsonObject(emptyMap())).confirm)
            assertTrue(anaToolbox.preview("servidor_list_company_activity", JsonObject(emptyMap())).rows.toString().contains("todas las empresas"))

            // 6. Writes that open a channel or switch a company off are flagged for that call: a logo URL (every client loads it), deactivating.
            env.models.add(
                Scripted.message(
                    "tool_use",
                    Scripted.toolUse("toolu_6", "servidor_update_company", buildJsonObject { put("code", "acme"); put("logo_url", "https://example.com/l.png") }),
                    Scripted.toolUse("toolu_7", "servidor_set_company_active", buildJsonObject { put("code", "acme"); put("active", false) }),
                    Scripted.toolUse("toolu_8", "servidor_update_company", buildJsonObject { put("code", "acme"); put("name", "Otro") }),
                ),
            )
            val writes = turn(path, ownerToken, "servidor", """{"text":"cambia cosas"}""")
            val flags = writes.filter { it.first == "confirmation_required" }.map { it.second }.associate { card -> card["summary"]!!.jsonArray.joinToString { row -> row.jsonObject.text("value") } to (card.flag("outbound") to card.flag("destructive")) }
            assertEquals(
                mapOf("acme, https://example.com/l.png" to (true to false), "acme, false" to (false to true), "acme, Otro" to (false to false)),
                flags,
            )
        }
    }

    @Test
    fun theOwnersWorkspaceAssistantNeitherListsNorRunsTheIrreversibleServerTools() {
        if (url == null) return
        val env = Env()
        val toolbox = workspaceToolbox(env, env.owner)
        assertTrue(AssistantTools.ServerDenyList == setOf("servidor_delete_company", "servidor_remove_module"))
        val offered = toolbox.tools().map { it.name }
        assertTrue("servidor_set_company_active" in offered && "servidor_list_companies" in offered, offered.toString())
        assertTrue(AssistantTools.ServerDenyList.none { it in offered }, offered.toString())
        // Even when the model names them (or the user "approved" a stored call): unknown, nothing runs, no card text.
        AssistantTools.ServerDenyList.forEach { name ->
            val args = buildJsonObject { put("code", "acme") }
            assertEquals(AssistantToolResult(false, AssistantTools.UnknownTool).text, toolbox.execute(name, args).text)
            assertFalse(toolbox.execute(name, args).ok)
            assertEquals(0, toolbox.preview(name, args).rows.size)
        }
        assertTrue(serverRan.none { it.startsWith("delete_company") || it.startsWith("remove_module") }, serverRan.toString())
        assertEquals("1", scalar(central, "SELECT count(*) FROM companies WHERE code = 'acme'"))
        // The model is told these are done from the app. The MCP agents keep both tools (the registry still has them) and their own guidance.
        val guidance = toolbox.instructions().joinToString("\n") { it.second }
        assertTrue(guidance.contains("Eliminar una empresa y quitar un modulo del servidor") && guidance.contains("desde la app"), guidance)
        assertTrue(env.access.mcpTools().any { it.qualifiedName == "servidor_delete_company" } && env.access.mcpTools().any { it.qualifiedName == "servidor_remove_module" })
        assertFalse(ServerMcpTools.Instructions.contains("desde la app; dilo asi"))
        // In a turn: a call the model makes is an error and no confirmation is asked.
        inApp(env) {
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/settings", env.ownerToken, """{"enabled":true,"acceptDisclosure":true}""").status)
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/key", env.ownerToken, """{"apiKey":"$serverKey"}""").status)
            val conversation = json("POST", "/companies/me/assistant/conversations", env.ownerToken, "servidor", "{}").text("id")
            env.models.add(
                Scripted.message("tool_use", Scripted.toolUse("toolu_1", "servidor_delete_company", buildJsonObject { put("code", "acme") }), Scripted.toolUse("toolu_2", "servidor_remove_module", buildJsonObject { put("module_id", "crm") })),
                Scripted.message("end_turn", Scripted.text("Eso se hace desde la app.")),
            )
            val turn = turn("/companies/me/assistant/conversations/$conversation/messages", env.ownerToken, "servidor", """{"text":"borra acme"}""")
            assertEquals("end_turn", turn.last().second.text("reason"), turn.toString())
            assertTrue(turn.none { it.first == "confirmation_required" })
            assertEquals(listOf("error", "error"), turn.filter { it.first == "tool_finished" }.map { it.second.text("status") })
            assertFalse(toolNames(env.models, 0).any { it in AssistantTools.ServerDenyList })
        }
        assertTrue(serverRan.isEmpty(), serverRan.toString())
    }

    @Test
    fun aSwitcherSessionStopsAtTheNextToolWhenThePersonLosesTheRightMidTurn() {
        if (url == null) return
        val env = Env()
        val dueId = UUID.fromString(scalar(acmeDb, "SELECT id FROM application_users WHERE postgres_role = 'dueno_acme'")!!)
        // The request that starts a turn decides once (context); the toolbox is what the running turn keeps using.
        fun toolboxOf(token: String): AssistantToolbox {
            val context = env.access.tenantConnectionForCompany("acme")!!.use { env.service.context(it, "acme", dueId, token) }!!
            assertTrue(context.mayUse && context.serverPerson != null, "the turn starts")
            return env.service.toolbox(context)
        }
        fun usable(toolbox: AssistantToolbox) = toolbox.tools().any { it.name == "demo_list_notes" } && toolbox.execute("demo_list_notes", buildJsonObject { }).ok
        val read = buildJsonObject { }

        // Ana, an administrator in a switcher session of acme, with the rights to use it.
        env.grant(ServerPermission.CompaniesEnter, ServerPermission.AssistantUse)
        val anaSession = env.anaInAcme()!!.accessToken
        val ana = toolboxOf(anaSession)
        assertTrue(usable(ana))
        // 1. server.assistant.use revoked: nothing is offered and nothing runs, the very next call.
        env.grant(ServerPermission.CompaniesEnter)
        assertEquals(emptyList(), ana.tools().map { it.name })
        assertEquals(AssistantTools.UnknownTool, ana.execute("demo_list_notes", read).text)
        assertEquals(AssistantTools.UnknownTool, ana.execute("demo_add_note", buildJsonObject { put("texto", "no") }).text)
        assertEquals(0, ana.preview("demo_add_note", buildJsonObject { put("texto", "no") }).rows.size)
        assertEquals(emptyList(), ana.instructions())
        assertEquals("0", notesIn(acmeDb))
        // 2. Given back, it works again (the grants are read live, never kept).
        env.grant(ServerPermission.CompaniesEnter, ServerPermission.AssistantUse)
        assertTrue(usable(ana))
        // 3. server.companies.enter revoked.
        env.grant(ServerPermission.AssistantUse)
        assertFalse(usable(ana))
        env.grant(ServerPermission.CompaniesEnter, ServerPermission.AssistantUse)
        assertTrue(usable(ana))
        // 4. The company leaves her scope.
        env.grant(ServerPermission.CompaniesEnter, ServerPermission.AssistantUse, scope = emptyList())
        assertFalse(usable(ana))
        env.grant(ServerPermission.CompaniesEnter, ServerPermission.AssistantUse)
        assertTrue(usable(ana))
        // 5. Deactivated.
        env.admins.setActive(env.owner, env.anaId.toString(), false)
        assertFalse(usable(ana))
        assertEquals(AssistantTools.UnknownTool, ana.execute("demo_list_notes", read).text)
        env.admins.setActive(env.owner, env.anaId.toString(), true)

        // The server owner in a switcher session: always may while still the owner (no permission to lose), and the business owner's own session is untouched.
        val ownerToolbox = toolboxOf(env.ownerInAcme()!!.accessToken)
        assertTrue(usable(ownerToolbox))
        val business = env.access.tenantConnectionForCompany("acme")!!.use { env.service.context(it, "acme", dueId, env.dueno.accessToken) }!!
        assertTrue(business.serverPerson == null && usable(env.service.toolbox(business)))
        env.grant(ServerPermission.AuditView) // the administrator's rights do not touch the business owner's own toolbox
        assertTrue(usable(env.service.toolbox(business)))
    }

    private fun unread(database: String, userId: Any) = scalar(database, "SELECT count(*) FROM notifications WHERE user_id = '$userId' AND read_at IS NULL")!!.toInt()
    private fun notifications(database: String, userId: Any) = scalar(database, "SELECT coalesce(string_agg(title || '|' || body || '|' || target_screen || '|' || target_params || '|' || module_id, ';'), '') FROM notifications WHERE user_id = '$userId'")!!

    @Test
    fun theOwnersOtherDevicesLearnWhatWaitsForThemThroughTheInboxAndTheAttentionEndpointAndNobodyElseDoes() {
        if (url == null) return
        val env = Env()
        val chat = "texto-privado-" + UUID.randomUUID()
        inApp(env) {
            val ownerToken = env.ownerToken
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/settings", ownerToken, """{"enabled":true,"acceptDisclosure":true}""").status)
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/key", ownerToken, """{"apiKey":"$serverKey"}""").status)
            assertEquals(HttpStatusCode.OK, server("PUT", "/server/assistant/companies/acme", ownerToken, """{"enabled":true,"acceptDisclosure":true}""").status)
            connect(acmeDb).use { it.run("INSERT INTO application_user_permissions (user_id, permission_id) SELECT id, 'empresa.assistant.use' FROM application_users WHERE postgres_role = 'eva_acme'") }
            val eva = env.logins.service.login(LoginCredentials("eva_acme", password, "acme").withClientAddress("10.3.3.5"))!!.accessToken
            val due = env.dueno.accessToken
            val dueId = scalar(acmeDb, "SELECT id FROM application_users WHERE postgres_role = 'dueno_acme'")!!
            val evaId = scalar(acmeDb, "SELECT id FROM application_users WHERE postgres_role = 'eva_acme'")!!
            suspend fun attention(token: String, company: String) = json("GET", "/companies/me/assistant/attention", token, company)
            fun write(id: String) = Scripted.toolUse(id, "demo_add_note", buildJsonObject { put("texto", "nota") })

            assertEquals(0, attention(due, "acme")["pendingConfirmations"]!!.jsonPrimitive.content.toInt())
            // 1. A turn pauses: ONE notification for the owner of the conversation, generic, with a target and no chat text.
            val conversation = json("POST", "/companies/me/assistant/conversations", due, "acme", "{}").text("id")
            env.models.add(Scripted.message("tool_use", write("toolu_1")))
            val paused = turn("/companies/me/assistant/conversations/$conversation/messages", due, "acme", """{"text":"$chat"}""")
            val card = paused.single { it.first == "confirmation_required" }.second
            val attentionNow = attention(due, "acme")
            assertEquals("1", attentionNow.text("pendingConfirmations"))
            assertEquals("0", attentionNow.text("unseenFinished"))
            assertEquals(listOf(conversation), attentionNow["conversationIds"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(1, unread(acmeDb, dueId))
            val stored = notifications(acmeDb, dueId)
            assertEquals("Asistente: confirmacion pendiente", stored.substringBefore('|'))
            assertTrue(stored.contains("\"conversationId\":\"$conversation\"") && stored.contains("|assistant|") && stored.endsWith("|assistant"), stored)
            assertFalse(stored.contains(chat) || stored.contains("nota"), stored)
            // Nobody else sees anything: not another user of the company, not the owner's other places.
            assertEquals("0", attention(eva, "acme").text("pendingConfirmations"))
            assertEquals(0, unread(acmeDb, evaId))
            assertEquals("", notifications(acmeDb, evaId))
            assertEquals("0", scalar(acmeDb, "SELECT count(*) FROM notifications WHERE user_id <> '$dueId'"))
            // 2. A second pause of the same conversation replaces it: still one live notification.
            env.models.add(Scripted.message("tool_use", write("toolu_2")))
            val second = turn("/companies/me/assistant/confirmations/${card.text("confirmationId")}", due, "acme", """{"decision":"approve"}""")
            val card2 = second.single { it.first == "confirmation_required" }.second
            assertEquals(1, unread(acmeDb, dueId))
            assertEquals("2", scalar(acmeDb, "SELECT count(*) FROM notifications WHERE user_id = '$dueId'"))
            // 3. The decision resumes the turn and it finishes: the pending one is resolved, "answer ready" is the live one.
            env.models.add(Scripted.message("end_turn", Scripted.text("Listo.")))
            turn("/companies/me/assistant/confirmations/${card2.text("confirmationId")}", due, "acme", """{"decision":"approve"}""")
            assertEquals(1, unread(acmeDb, dueId))
            assertEquals("Asistente: respuesta lista", scalar(acmeDb, "SELECT title FROM notifications WHERE user_id = '$dueId' AND read_at IS NULL"))
            val after = attention(due, "acme")
            assertEquals(listOf("0", "1"), listOf(after.text("pendingConfirmations"), after.text("unseenFinished")))
            assertFalse(notifications(acmeDb, dueId).contains(chat))
            // Loading the conversation alone is not reading it; the read marker (POST .../read, 204) is.
            assertEquals(HttpStatusCode.OK, call("GET", "/companies/me/assistant/conversations/$conversation", due, "acme").status)
            assertEquals("1", attention(due, "acme").text("unseenFinished"))
            // Another person's id never marks it: a user who is not the owner gets 404 and changes nothing.
            assertEquals(HttpStatusCode.NotFound, call("POST", "/companies/me/assistant/conversations/$conversation/read", eva, "acme").status)
            assertEquals(HttpStatusCode.NotFound, call("POST", "/companies/me/assistant/conversations/${UUID.randomUUID()}/read", due, "acme").status)
            assertEquals("1", attention(due, "acme").text("unseenFinished"))
            assertEquals(1, unread(acmeDb, dueId))
            assertEquals(HttpStatusCode.NoContent, call("POST", "/companies/me/assistant/conversations/$conversation/read", due, "acme").status)
            assertEquals("0", attention(due, "acme").text("unseenFinished"))
            assertEquals(0, unread(acmeDb, dueId))
            // Reading twice changes nothing.
            assertEquals(HttpStatusCode.NoContent, call("POST", "/companies/me/assistant/conversations/$conversation/read", due, "acme").status)
            // 4. A confirmation that expires resolves its notification too.
            val other = json("POST", "/companies/me/assistant/conversations", due, "acme", "{}").text("id")
            env.models.add(Scripted.message("tool_use", write("toolu_3")))
            turn("/companies/me/assistant/conversations/$other/messages", due, "acme", """{"text":"otra"}""")
            assertEquals(1, unread(acmeDb, dueId))
            connect(acmeDb).use { it.run("UPDATE assistant_pending_calls SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'") }
            env.service.daily("acme")
            assertEquals(0, unread(acmeDb, dueId))
            assertEquals("0", attention(due, "acme").text("pendingConfirmations"))
            // 5. The endpoint is not part of the chat rate limit: many polls in a row all answer.
            repeat(15) { assertEquals(HttpStatusCode.OK, call("GET", "/companies/me/assistant/attention", due, "acme").status) }

            // 6. A switcher session's conversations notify nobody (the business owner above all) but are counted for their own person.
            val ownerSession = env.ownerInAcme()!!.accessToken
            val mine = json("POST", "/companies/me/assistant/conversations", ownerSession, "acme", "{}").text("id")
            val dueBefore = notifications(acmeDb, dueId)
            env.models.add(Scripted.message("tool_use", write("toolu_4")))
            turn("/companies/me/assistant/conversations/$mine/messages", ownerSession, "acme", """{"text":"desde el selector"}""")
            assertEquals("1", attention(ownerSession, "acme").text("pendingConfirmations"))
            assertEquals("0", attention(due, "acme").text("pendingConfirmations"))
            assertEquals(dueBefore, notifications(acmeDb, dueId))
            assertEquals("0", scalar(acmeDb, "SELECT count(*) FROM notifications WHERE user_id = '$ownerId'"))

            // 7. In the server workspace the owner is notified (its own inbox); an administrator has no inbox there and is not.
            val wsConversation = json("POST", "/companies/me/assistant/conversations", ownerToken, "servidor", "{}").text("id")
            env.models.add(Scripted.message("tool_use", Scripted.toolUse("toolu_5", "servidor_set_company_active", buildJsonObject { put("code", "acme") })))
            turn("/companies/me/assistant/conversations/$wsConversation/messages", ownerToken, "servidor", """{"text":"$chat"}""")
            assertEquals("1", attention(ownerToken, "servidor").text("pendingConfirmations"))
            assertEquals(1, unread(workspaceDb, ownerId))
            assertFalse(notifications(workspaceDb, ownerId).contains(chat))
            env.grant(ServerPermission.AuditView, ServerPermission.AssistantUse)
            assertEquals("0", attention(env.anaToken, "servidor").text("pendingConfirmations"))
            val anaConversation = json("POST", "/companies/me/assistant/conversations", env.anaToken, "servidor", "{}").text("id")
            env.models.add(Scripted.message("end_turn", Scripted.text("Nada.")))
            turn("/companies/me/assistant/conversations/$anaConversation/messages", env.anaToken, "servidor", """{"text":"hola"}""")
            assertEquals("0", scalar(workspaceDb, "SELECT count(*) FROM notifications WHERE user_id = '${env.anaId}'"))
        }
    }
}
