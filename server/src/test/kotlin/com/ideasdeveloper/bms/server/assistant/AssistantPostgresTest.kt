package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerActorKind
import com.ideasdeveloper.bms.server.admins.ServerActorLookup
import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.security.ApplicationPasswordHasher
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningConfig
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpSchema
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.RegisteredMcpTool
import com.ideasdeveloper.bms.server.modules.RegisteredModuleMcp
import com.ideasdeveloper.bms.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.bms.server.security.MasterKeySecretBox
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
import kotlinx.serialization.json.JsonArray
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
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The assistant end to end on a real PostgreSQL, with the model scripted (no network, no real key): tenant V018 and
 * its grants, server and company settings (sealed keys, audit, activity log), availability rules, the SSE turn with a
 * paused write approved later, the owner rule, the activity actor of the tool's row, usage and accounting, retention.
 * Runs only with BMS_TEST_PG_URL (or BMS_ACTIVITY_PG_URL), jdbc:postgresql://host:port/postgres?user=postgres of a
 * DISPOSABLE server: it creates and drops bms_as_* databases and the roles as_owner and bms_as_runtime.
 */
class AssistantPostgresTest {
    private val url: String? = (System.getenv("BMS_TEST_PG_URL") ?: System.getenv("BMS_ACTIVITY_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_as_central"
    private val company = "bms_as_acme"
    private val opened = mutableListOf<LoginDatabases>()
    private val ownerId = UUID.randomUUID()
    private val password = "clave-de-prueba-123"
    // Made up for the test, never a real key; the scripted model never sends it anywhere.
    private val serverKey = "sk-ant-test-server-" + UUID.randomUUID().toString().replace("-", "")
    private val companyKey = "sk-ant-test-company-" + UUID.randomUUID().toString().replace("-", "")
    private val chatText = "texto-privado-del-chat-" + UUID.randomUUID()

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
        DriverManager.getConnection(url).use { admin -> listOf(central, company).forEach { admin.run("DROP DATABASE IF EXISTS $it WITH (FORCE)") } }
    }

    private fun scalar(database: String, sql: String): String? = connect(database).use { db ->
        db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
    }

    private fun reset() {
        DriverManager.getConnection(url!!).use { admin ->
            listOf(central, company).forEach { admin.run("DROP DATABASE IF EXISTS $it WITH (FORCE)") }
            admin.run("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'as_owner') THEN CREATE ROLE as_owner; END IF; END $$")
            admin.run("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'bms_as_runtime') THEN CREATE ROLE bms_as_runtime LOGIN PASSWORD 'runtime-pass'; END IF; END $$")
            // The runtime role owns nothing and is a member of nothing: it reaches the tables only through grants.
            admin.run("REVOKE as_owner FROM bms_as_runtime")
            listOf(central, company).forEach { admin.run("CREATE DATABASE $it OWNER as_owner") }
            admin.run("GRANT CONNECT ON DATABASE $company TO bms_as_runtime")
        }
        connect(central).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            File(root, "database/control/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                .forEach { db.run(it.readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', 'as_owner', TRUE, 'Duena', 'duena@example.com')")
            db.run("INSERT INTO server_owners (user_id) VALUES ('$ownerId')")
            db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), 'acme', 'Acme SA', '$company', gen_random_uuid())")
        }
        connect(company).use { db ->
            // The company's tables belong to the server owner's role, as provisioning creates them.
            db.run("SET ROLE as_owner")
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials")
                .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
            val hash = ApplicationPasswordHasher.hash(password)
            listOf("dueno_acme" to "Dueno Acme", "eva_acme" to "Eva Acme", "leo_acme" to "Leo Acme").forEach { (login, name) ->
                val id = UUID.randomUUID()
                db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$id', '$login', TRUE, '$name', '$login@example.com')")
                db.run("INSERT INTO application_user_credentials (user_id, password_salt, password_hash, iterations) VALUES ('$id', '${hash.salt}', '${hash.hash}', ${hash.iterations})")
                if (login == "dueno_acme") db.run("INSERT INTO business_owner (user_id) VALUES ('$id')")
            }
            db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('demo', 'Demo', 'enabled', CURRENT_TIMESTAMP)")
            listOf("demo.view", "demo.write", "empresa.assistant.use").forEach {
                db.run("INSERT INTO application_permissions (permission_id, module_id, title) VALUES ('$it', '${it.substringBefore('.')}', '$it')")
            }
            db.run("INSERT INTO application_user_permissions (user_id, permission_id) SELECT id, p FROM application_users, unnest(ARRAY['demo.view', 'demo.write']) p WHERE postgres_role IN ('eva_acme', 'leo_acme')")
        }
    }

    private fun loginDatabases(): LoginDatabases {
        val data = source(central)
        return LoginDatabases(data, Database.connect(data), urlOf(central), "[]", pgUser, "", prefix, 3600).also { opened += it }
    }

    private fun provisioning(databases: LoginDatabases) = CompanyProvisioningService(
        central = source(central),
        // A separate provisioning login (here the test server's superuser), as on a server with the new role layout.
        config = CompanyProvisioningConfig(urlOf("postgres").substringBefore("?"), "bms_as_runtime", "runtime-pass", prefix, root.path, provisioningUser = pgUser, provisioningPassword = ""),
        loginDatabases = databases,
        moduleRegistry = ModuleRegistry(emptyList()),
        remoteModuleCatalog = RemoteModuleCatalog(),
        defaultCatalogUrl = "",
        modulePackagesRoot = java.nio.file.Files.createTempDirectory("as-modules"),
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

    private fun owner(): ServerActorResolver = ServerActorResolver { token ->
        if (token == "server-owner") ServerActorLookup.Found(ServerActor(ownerId, "as_owner", ServerActorKind.Owner, emptySet(), true, emptySet(), "as_owner")) else ServerActorLookup.None
    }

    private fun events(body: String): List<Pair<String, JsonObject>> = body.split("\n\n").mapNotNull { frame ->
        val name = frame.lines().firstOrNull { it.startsWith("event: ") }?.removePrefix("event: ") ?: return@mapNotNull null
        val data = frame.lines().first { it.startsWith("data: ") }.removePrefix("data: ")
        name to Json.parseToJsonElement(data).jsonObject
    }

    private fun JsonObject.text(key: String) = (this[key] as JsonPrimitive).content

    @Test
    fun theAssistantEndToEnd() {
        if (url == null) return
        reset()
        val databases = loginDatabases()
        provisioning(databases).applyPendingModuleMigrations()
        provisioning(databases).applyPendingModuleMigrations()
        assertEquals("1", scalar(company, "SELECT count(*) FROM schema_migrations WHERE version LIKE 'V018__create_assistant%'"))
        // The fingerprint of the confirmation card (V020) is applied once, with its column.
        assertEquals("1", scalar(company, "SELECT count(*) FROM schema_migrations WHERE version LIKE 'V020__assistant_preview_hash%'"))
        assertEquals("1", scalar(company, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'assistant_pending_calls' AND column_name = 'preview_hash'"))
        // Narrower rights: the runtime role only reads and appends messages; the rest of the tables are ordinary.
        assertEquals("f|t|t|f", scalar(company, "SELECT has_table_privilege('bms_as_runtime', 'assistant_messages', 'UPDATE') || '|' || has_table_privilege('bms_as_runtime', 'assistant_messages', 'INSERT') || '|' || has_table_privilege('bms_as_runtime', 'assistant_conversations', 'DELETE') || '|' || has_table_privilege('bms_as_runtime', 'assistant_messages', 'DELETE')")?.replace("true", "t")?.replace("false", "f"))

        val box = MasterKeySecretBox(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
        val access = ModuleAccessService(source(central), databases, moduleInstalled = { true }, moduleMcp = { listOf(demo) }, secrets = box)
        val models = FakeModelClients()
        val service = AssistantService(access, AssistantServerConfig(source(central), box), box, models)
        val login = { who: String -> databases.service.login(LoginCredentials(who, password, "acme").withClientAddress("10.2.2.2"))!! }
        val eva = login("eva_acme")
        val dueno = login("dueno_acme")
        val leo = login("leo_acme")

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    assistantRoutes(service)
                    assistantServerRoutes(owner(), AssistantServerAdmin(source(central), service))
                }
            }
            suspend fun companyCall(method: String, path: String, token: String, body: String? = null): HttpResponse {
                val configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("X-Company-Code", "acme")
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
            suspend fun serverCall(method: String, path: String, body: String? = null): HttpResponse {
                val configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                    header(HttpHeaders.Authorization, "Bearer server-owner")
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
                return when (method) {
                    "GET" -> client.get(path, configure)
                    "PUT" -> client.put(path, configure)
                    else -> client.delete(path, configure)
                }
            }
            suspend fun status(token: String) = Json.parseToJsonElement(companyCall("GET", "/companies/me/assistant/status", token).bodyAsText()).jsonObject

            // 1. Off at server level: nobody sees it, the business owner sees it to configure it.
            assertEquals("SERVER_DISABLED", status(eva.accessToken).text("reason"))
            assertFalse(status(eva.accessToken)["visible"]!!.jsonPrimitive.boolean)
            assertTrue(status(dueno.accessToken)["visible"]!!.jsonPrimitive.boolean)

            // 2. The server owner turns it on (the disclosure is required) and sets the key, sealed and audited.
            assertEquals(HttpStatusCode.BadRequest, serverCall("PUT", "/server/assistant/settings", """{"enabled":true}""").status)
            assertEquals(HttpStatusCode.OK, serverCall("PUT", "/server/assistant/settings", """{"enabled":true,"acceptDisclosure":true,"retentionDays":30}""").status)
            assertEquals(HttpStatusCode.BadRequest, serverCall("PUT", "/server/assistant/settings", """{"model":"gpt-5"}""").status)
            assertEquals(HttpStatusCode.BadRequest, serverCall("PUT", "/server/assistant/key", """{"apiKey":"no-es-una-clave"}""").status)
            val keySet = serverCall("PUT", "/server/assistant/key", """{"apiKey":"$serverKey"}""")
            assertEquals(HttpStatusCode.OK, keySet.status, keySet.bodyAsText())
            assertFalse(keySet.bodyAsText().contains(serverKey))
            assertTrue(keySet.bodyAsText().contains(serverKey.takeLast(4)))
            val sealed = scalar(central, "SELECT setting_value FROM server_settings WHERE setting_key = 'assistant.api_key'")!!
            assertTrue(sealed.startsWith("enc:v1:") && !sealed.contains(serverKey))
            assertEquals("2", scalar(central, "SELECT count(*) FROM provisioning_audit_log WHERE action IN ('assistant.settings_changed', 'assistant.key_set')"))
            assertEquals("0", scalar(central, "SELECT count(*) FROM provisioning_audit_log WHERE details::text LIKE '%${serverKey.takeLast(12)}%'"))

            // 3. The company is off until its owner turns it on.
            assertEquals("COMPANY_DISABLED", status(dueno.accessToken).text("reason"))
            assertEquals(HttpStatusCode.Forbidden, companyCall("PUT", "/companies/me/assistant/settings", eva.accessToken, """{"enabled":true,"acceptDisclosure":true}""").status)
            assertEquals(HttpStatusCode.BadRequest, companyCall("PUT", "/companies/me/assistant/settings", dueno.accessToken, """{"enabled":true}""").status)
            assertEquals(HttpStatusCode.OK, companyCall("PUT", "/companies/me/assistant/settings", dueno.accessToken, """{"enabled":true,"acceptDisclosure":true}""").status)
            val ownKey = companyCall("PUT", "/companies/me/assistant/settings", dueno.accessToken, """{"apiKey":"$companyKey"}""")
            assertEquals(HttpStatusCode.OK, ownKey.status, ownKey.bodyAsText())
            assertTrue(Json.parseToJsonElement(ownKey.bodyAsText()).jsonObject["ownKeySet"]!!.jsonPrimitive.boolean)
            // Settings changes are in the company log, the key only as "changed".
            assertEquals("2", scalar(company, "SELECT count(*) FROM activity_log WHERE entity_type = 'assistant_settings' AND action = 'settings_changed' AND actor_name = 'Dueno Acme'"))
            assertEquals("1", scalar(company, "SELECT count(*) FROM activity_log WHERE changes::text LIKE '%\"secret\": true%' OR changes::text LIKE '%\"secret\":true%'"))
            assertEquals("0", scalar(company, "SELECT count(*) FROM activity_log WHERE changes::text LIKE '%${companyKey.takeLast(12)}%' OR summary LIKE '%sk-ant%'"))

            // 4. Users need the permission; the business owner has it.
            assertEquals("PERMISSION_REQUIRED", status(eva.accessToken).text("reason"))
            assertFalse(status(eva.accessToken)["visible"]!!.jsonPrimitive.boolean)
            assertEquals(HttpStatusCode.Forbidden, companyCall("POST", "/companies/me/assistant/conversations", eva.accessToken, "{}").status)
            connect(company).use { it.run("INSERT INTO application_user_permissions (user_id, permission_id) SELECT id, 'empresa.assistant.use' FROM application_users WHERE postgres_role = 'eva_acme'") }
            val ready = status(eva.accessToken)
            assertTrue(ready["available"]!!.jsonPrimitive.boolean, ready.toString())
            assertEquals("company", ready.text("keySource"))

            // 5. The server can take it away from one company, and give it back with a limit.
            assertEquals(HttpStatusCode.OK, serverCall("PUT", "/server/assistant/companies/acme", """{"allowed":false}""").status)
            assertEquals("COMPANY_NOT_ALLOWED", status(eva.accessToken).text("reason"))
            assertEquals(HttpStatusCode.NotFound, serverCall("PUT", "/server/assistant/companies/nadie", """{"allowed":true}""").status)
            assertEquals(HttpStatusCode.OK, serverCall("PUT", "/server/assistant/companies/acme", """{"allowed":true,"monthlyTokens":100000}""").status)
            assertEquals("1", scalar(central, "SELECT count(*) FROM provisioning_audit_log WHERE action = 'company.assistant_changed' AND company_code = 'acme' AND details::text LIKE '%100000%'"))

            // 6. A turn: the read runs, the write waits; the stream ends and the pause is in the database.
            val created = companyCall("POST", "/companies/me/assistant/conversations", eva.accessToken, "{}")
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val conversation = Json.parseToJsonElement(created.bodyAsText()).jsonObject.text("id")
            models.add(Scripted.message("tool_use", Scripted.text("Reviso."), Scripted.toolUse("toolu_1", "demo_list_notes"), Scripted.toolUse("toolu_2", "demo_add_note", buildJsonObject { put("texto", "nueva") })))
            val turn = companyCall("POST", "/companies/me/assistant/conversations/$conversation/messages", eva.accessToken, """{"text":"$chatText"}""")
            assertEquals(HttpStatusCode.OK, turn.status)
            assertTrue(turn.headers[HttpHeaders.ContentType]!!.startsWith("text/event-stream"))
            assertEquals("no", turn.headers["X-Accel-Buffering"])
            val firstBody = turn.bodyAsText()
            val first = events(firstBody)
            assertTrue(first.isNotEmpty(), "empty stream: '$firstBody'")
            assertEquals("turn_started", first.first().first)
            assertEquals("awaiting_confirmation", first.last().second.text("reason"))
            val card = first.single { it.first == "confirmation_required" }.second
            assertEquals("Demo: Agregar nota", card.text("label"))
            assertEquals("Texto de la nota.", card["summary"]!!.jsonArray[0].jsonObject.text("label"))
            assertEquals("awaiting_confirmation", scalar(company, "SELECT state FROM assistant_conversations WHERE id = '$conversation'"))
            assertEquals(listOf(companyKey), models.keys)
            // The detail shows the open confirmation (its summary) and the read's outcome, never a tool result.
            val detail = companyCall("GET", "/companies/me/assistant/conversations/$conversation", eva.accessToken).bodyAsText()
            assertTrue(detail.contains(chatText) && !detail.contains("una") && !detail.contains(AssistantEngine.ContextPrefix), detail)
            assertEquals(1, Json.parseToJsonElement(detail).jsonObject["pending"]!!.jsonArray.size)
            // A new message waits for the decision.
            assertEquals(HttpStatusCode.Conflict, companyCall("POST", "/companies/me/assistant/conversations/$conversation/messages", eva.accessToken, """{"text":"otra"}""").status)

            // 7. Nobody else reads it: not the business owner, not another user.
            assertEquals(HttpStatusCode.NotFound, companyCall("GET", "/companies/me/assistant/conversations/$conversation", dueno.accessToken).status)
            assertEquals(HttpStatusCode.NotFound, companyCall("POST", "/companies/me/assistant/conversations/$conversation/messages", dueno.accessToken, """{"text":"dame eso"}""").status)
            assertFalse(companyCall("GET", "/companies/me/assistant/conversations", dueno.accessToken).bodyAsText().contains(conversation))
            assertEquals(HttpStatusCode.NotFound, companyCall("POST", "/companies/me/assistant/confirmations/${card.text("confirmationId")}", dueno.accessToken, """{"decision":"approve"}""").status)
            assertEquals(HttpStatusCode.Forbidden, companyCall("POST", "/companies/me/assistant/confirmations/${card.text("confirmationId")}", leo.accessToken, """{"decision":"approve"}""").status)

            // 8. Approved later, on another request: the write runs as "Asistente de Eva Acme" and the turn ends.
            models.add(Scripted.message("end_turn", Scripted.text("Listo."), input = 1000, output = 100, cacheRead = 5000, cacheWrite = 200))
            val approved = companyCall("POST", "/companies/me/assistant/confirmations/${card.text("confirmationId")}", eva.accessToken, """{"decision":"approve"}""")
            val second = events(approved.bodyAsText())
            assertEquals("end_turn", second.last().second.text("reason"), second.toString())
            assertEquals("ok", second.single { it.first == "tool_finished" }.second.text("status"))
            assertEquals("Asistente de Eva Acme", scalar(company, """
                SELECT CASE WHEN origin = 'assistant' AND agent_name = 'Asistente' THEN 'Asistente de ' || actor_name END
                FROM activity_log WHERE module_id = 'demo' AND action = 'created'
            """.trimIndent()))
            assertEquals("idle", scalar(company, "SELECT state FROM assistant_conversations WHERE id = '$conversation'"))
            assertEquals("0", scalar(company, "SELECT count(*) FROM assistant_pending_calls"))
            // Chat text and keys are never in the activity log.
            assertEquals("0", scalar(company, "SELECT count(*) FROM activity_log WHERE summary LIKE '%texto-privado%' OR changes::text LIKE '%texto-privado%' OR entity_label LIKE '%texto-privado%'"))

            // 9. Usage: billed to the company's own key, visible as totals to the owners.
            assertEquals("company|2", scalar(company, "SELECT key_source || '|' || requests FROM assistant_usage"))
            val usage = Json.parseToJsonElement(companyCall("GET", "/companies/me/assistant/usage", dueno.accessToken).bodyAsText()).jsonObject
            assertEquals("Eva Acme", usage["users"]!!.jsonArray.single().jsonObject.text("name"))
            assertEquals(HttpStatusCode.Forbidden, companyCall("GET", "/companies/me/assistant/usage", eva.accessToken).status)
            val serverUsage = serverCall("GET", "/server/assistant/usage").bodyAsText()
            assertTrue(Json.parseToJsonElement(serverUsage).jsonObject["companies"]!!.jsonArray.single().jsonObject.text("ownKeyTokens").toLong() > 1000, serverUsage)
            // Own-key usage does not count for the company's limit unless the server says so.
            assertEquals("0", status(eva.accessToken).text("monthUsedTokens"))
            serverCall("PUT", "/server/assistant/settings", """{"limitOwnKeyCompanies":true}""")
            assertTrue(status(eva.accessToken).text("monthUsedTokens").toLong() > 1000)

            // 10. The limit: with the server key and a tiny limit, a new message is refused before any stream.
            companyCall("PUT", "/companies/me/assistant/settings", dueno.accessToken, """{"removeApiKey":true}""")
            serverCall("PUT", "/server/assistant/companies/acme", """{"allowed":true,"monthlyTokens":10}""")
            serverCall("PUT", "/server/assistant/settings", """{"limitOwnKeyCompanies":false}""")
            connect(company).use { it.run("INSERT INTO assistant_usage (day, user_id, key_source, requests, counted_tokens) SELECT CURRENT_DATE, id, 'server', 1, 50 FROM application_users WHERE postgres_role = 'eva_acme'") }
            val limited = companyCall("POST", "/companies/me/assistant/conversations/$conversation/messages", eva.accessToken, """{"text":"una mas"}""")
            assertEquals(HttpStatusCode.Forbidden, limited.status)
            assertTrue(limited.bodyAsText().contains("LIMIT_REACHED"))
            assertEquals("LIMIT_REACHED", status(eva.accessToken).text("reason"))
        }

        // 11. Retention: conversations not touched for longer than the server's setting go away with their messages.
        connect(company).use { it.run("UPDATE assistant_conversations SET updated_at = CURRENT_TIMESTAMP - INTERVAL '31 days'") }
        service.daily("acme")
        assertEquals("0", scalar(company, "SELECT count(*) FROM assistant_conversations"))
        assertEquals("0", scalar(company, "SELECT count(*) FROM assistant_messages"))
        assertTrue(scalar(company, "SELECT count(*) FROM assistant_usage")!!.toInt() > 0)
    }

    @Test
    fun theStoreKeepsOwnersApartAndTheRuntimeRoleCanDeleteConversationsWithTheirMessages() {
        if (url == null) return
        reset()
        provisioning(loginDatabases()).applyPendingModuleMigrations()
        connect(company).use { db -> db.run("GRANT USAGE ON SCHEMA public TO bms_as_runtime") }
        val runtime = { DriverManager.getConnection(urlOf(company).substringBefore("?") + "?user=bms_as_runtime&password=runtime-pass") }
        val store = JdbcAssistantStore(runtime)
        val eva = UUID.fromString(scalar(company, "SELECT id FROM application_users WHERE postgres_role = 'eva_acme'"))
        val owner = UUID.fromString(scalar(company, "SELECT id FROM application_users WHERE postgres_role = 'dueno_acme'"))
        val now = Instant.now()
        val mine = store.createConversation(eva, "self", "", now)
        // Writes of a turn need its lease.
        assertTrue(runCatching { store.appendMessage(mine.id, UUID.randomUUID(), Roles.User, jsonArrayOf(Scripted.text("hola")), null, null, null, now) }.exceptionOrNull() is AssistantLeaseLostException)
        val first = store.startTurn(mine.id, AssistantConversation.Idle, now.plusSeconds(600), now)!!
        store.appendMessage(mine.id, first, Roles.User, jsonArrayOf(Scripted.text("hola")), null, null, null, now)
        assertTrue(runCatching { store.appendMessage(mine.id, first, Roles.User, JsonArray(emptyList()), null, null, null, now) }.isFailure)
        assertTrue(store.release(mine.id, first, AssistantConversation.Idle, now))
        assertEquals(null, store.conversation(mine.id, owner, "self"))
        assertEquals(null, store.conversation(mine.id, eva, "server:x"))
        assertEquals(1, store.messages(mine.id).size)
        assertFalse(store.deleteConversation(mine.id, owner, "self"))
        // Taking a turn is exclusive.
        val taken = store.startTurn(mine.id, AssistantConversation.Idle, now.plusSeconds(600), now)!!
        assertEquals(null, store.startTurn(mine.id, AssistantConversation.Idle, now.plusSeconds(600), now))
        assertTrue(store.renew(mine.id, taken, now.plusSeconds(700)))
        val next = store.startTurn(mine.id, AssistantConversation.Idle, now.plusSeconds(1500), now.plusSeconds(701))!!
        // The old turn can neither renew, write nor release over the new one.
        assertFalse(store.renew(mine.id, taken, now.plusSeconds(2000)))
        assertFalse(store.release(mine.id, taken, AssistantConversation.Idle, now))
        assertTrue(runCatching { store.appendMessage(mine.id, taken, Roles.User, jsonArrayOf(Scripted.text("x")), null, null, null, now) }.exceptionOrNull() is AssistantLeaseLostException)
        // A claimed call is never claimed twice; its result stays.
        val call = AssistantPendingCall(UUID.randomUUID(), mine.id, 0, "toolu_1", "demo_add_note", JsonObject(emptyMap()), "Demo", "Demo", false, JsonArray(emptyList()), AssistantPendingCall.Pending, null, now.plusSeconds(3600))
        store.savePending(next, listOf(call))
        assertTrue(store.decide(call.id, AssistantPendingCall.Approved, now))
        assertTrue(store.claim(call.id, mine.id, next))
        assertFalse(store.claim(call.id, mine.id, next))
        store.finish(call.id, mine.id, next, AssistantEngine.toolResult("toolu_1", true, "ok"))
        assertEquals(AssistantPendingCall.Done, store.pending(mine.id).single().status)
        assertEquals(null, store.completePending(mine.id, next, JsonArray(emptyList()), now))
        assertTrue(store.pending(mine.id).isEmpty())
        assertTrue(store.release(mine.id, next, AssistantConversation.Idle, now))
        // Messages cannot be edited by the runtime role.
        val refused = runCatching { runtime().use { it.run("UPDATE assistant_messages SET content = '[]'") } }.exceptionOrNull()
        assertTrue(refused is java.sql.SQLException && refused.sqlState == "42501", "$refused")
        // Usage accumulates per day, user and key.
        val day = java.time.LocalDate.of(2026, 11, 27)
        store.recordUsage(day, eva, "server", ModelUsage(10, 5, 100, 0), 1)
        store.recordUsage(day, eva, "server", ModelUsage(10, 5, 0, 0), 1)
        store.recordUsage(day, eva, "company", ModelUsage(1, 1, 0, 0), 1)
        assertEquals(15 + 10 + 15, store.monthUsage(java.time.YearMonth.of(2026, 11), setOf("server")))
        assertEquals(42, store.monthUsage(java.time.YearMonth.of(2026, 11), setOf("server", "company")))
        assertEquals(2L, store.usage(java.time.YearMonth.of(2026, 11)).first { it.keySource == "server" }.requests)
        // Deleting the conversation takes its messages (cascade as the table owner).
        assertTrue(store.deleteConversation(mine.id, eva, "self"))
        assertEquals("0", scalar(company, "SELECT count(*) FROM assistant_messages"))
    }

    @Test
    fun theStoreKeepsThePreviewFingerprintCapsConversationsAtomicallyAndPurgesStuckTurns() {
        if (url == null) return
        reset()
        provisioning(loginDatabases()).applyPendingModuleMigrations()
        connect(company).use { db -> db.run("GRANT USAGE ON SCHEMA public TO bms_as_runtime") }
        val runtime = { DriverManager.getConnection(urlOf(company).substringBefore("?") + "?user=bms_as_runtime&password=runtime-pass") }
        val store = JdbcAssistantStore(runtime)
        val eva = UUID.fromString(scalar(company, "SELECT id FROM application_users WHERE postgres_role = 'eva_acme'"))
        val now = Instant.now()
        // The fingerprint of the card travels with the pending call.
        val conversation = store.createConversation(eva, "self", "", now)
        val token = store.startTurn(conversation.id, AssistantConversation.Idle, now.plusSeconds(600), now)!!
        val call = AssistantPendingCall(UUID.randomUUID(), conversation.id, 0, "toolu_1", "demo_add_note", JsonObject(emptyMap()), "Demo", "Demo", false, JsonArray(emptyList()), AssistantPendingCall.Pending, null, now.plusSeconds(3600), previewHash = "abc123")
        store.savePending(token, listOf(call))
        assertEquals("abc123", store.pending(conversation.id).single().previewHash)
        assertEquals("abc123", scalar(company, "SELECT preview_hash FROM assistant_pending_calls"))
        // Awaiting with every call decided is found once nothing happened for a while (not before).
        assertTrue(store.release(conversation.id, token, AssistantConversation.Awaiting, now))
        assertTrue(store.stuckConversations(now.plusSeconds(3600)).isEmpty() || store.pending(conversation.id).any { it.status == AssistantPendingCall.Pending })
        assertTrue(store.decide(call.id, AssistantPendingCall.Approved, now))
        assertEquals(emptyList(), store.stuckConversations(now.minusSeconds(60)))
        assertEquals(listOf(conversation.id), store.stuckConversations(now.plusSeconds(60)))
        // Two requests at once for the last place: exactly one gets it.
        val other = UUID.fromString(scalar(company, "SELECT id FROM application_users WHERE postgres_role = 'leo_acme'"))
        repeat(3) { store.createConversation(other, "self", "", now) }
        val created = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val jobs = (1..6).map { pool.submit { start.await(); if (store.createConversationCapped(other, "self", "", now, 4) != null) created.incrementAndGet() } }
            start.countDown()
            jobs.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
        assertEquals(1, created.get())
        assertEquals(4, store.conversationCount(other, "self"))
        // Retention also deletes a turn stuck in 'running' whose lease is long over, and keeps a live one.
        val old = now.minusSeconds(100L * 86_400)
        val stuck = store.createConversation(eva, "self", "", old)
        store.startTurn(stuck.id, AssistantConversation.Idle, old.plusSeconds(600), old)
        val live = store.createConversation(eva, "self", "", old)
        store.startTurn(live.id, AssistantConversation.Idle, now.plusSeconds(600), old)
        assertEquals(1, store.purge(now.minusSeconds(90L * 86_400), java.time.LocalDate.of(2025, 1, 1)))
        assertEquals(null, store.conversation(stuck.id, eva, "self"))
        assertTrue(store.conversation(live.id, eva, "self") != null)
    }

    // ---- F4: the preview sandbox ----

    private fun previewTool(name: String, preview: (com.ideasdeveloper.bms.server.modules.McpToolCall) -> List<com.ideasdeveloper.bms.server.modules.McpPreviewRow>) =
        RegisteredMcpTool(
            "demo", "Demo",
            McpTool(name, name, "Prueba $name.", "demo.write", McpSchema.obj(), preview = preview) { JsonPrimitive("hecho") },
        )

    @Test
    fun aPreviewRunsOnAReadOnlyConnectionThatCannotCommitReachOtherConnectionsAndIsNotReused() {
        if (url == null) return
        reset()
        val databases = loginDatabases()
        provisioning(databases).applyPendingModuleMigrations()
        connect(company).use { it.run("CREATE TABLE demo_notes (id SERIAL PRIMARY KEY, text TEXT)") }
        fun notes() = scalar(company, "SELECT count(*) FROM demo_notes")
        val sneaky = RegisteredModuleMcp(
            "demo", "Demo", "",
            listOf(
                previewTool("ok") { call ->
                    val readOnly = call.tenant.createStatement().use { st -> st.executeQuery("SHOW transaction_read_only").use { it.next(); it.getString(1) } }
                    listOf(com.ideasdeveloper.bms.server.modules.McpPreviewRow("Solo lectura", readOnly))
                },
                previewTool("writes") { call -> call.tenant.createStatement().use { it.execute("INSERT INTO demo_notes (text) VALUES ('a')") }; emptyList() },
                previewTool("commits") { call -> call.tenant.createStatement().use { it.execute("SELECT 1") }; call.tenant.commit(); emptyList() },
                previewTool("flips") { call -> call.tenant.autoCommit = true; call.tenant.createStatement().use { it.execute("INSERT INTO demo_notes (text) VALUES ('b')") }; emptyList() },
                previewTool("unlocks") { call -> call.tenant.isReadOnly = false; call.tenant.createStatement().use { it.execute("INSERT INTO demo_notes (text) VALUES ('c')") }; emptyList() },
                previewTool("closes") { call -> call.tenant.close(); emptyList() },
                previewTool("another") { call ->
                    // A second connection would not be read only: the service refuses to open one while a preview runs.
                    call.access.tenantConnectionForCompany("acme")!!.use { other -> other.createStatement().use { it.execute("INSERT INTO demo_notes (text) VALUES ('d')") } }
                    emptyList()
                },
                previewTool("central") { call -> call.access.centralConnection().use { it.createStatement().use { st -> st.execute("SELECT 1") } }; emptyList() },
            ),
        )
        val access = ModuleAccessService(source(central), databases, moduleInstalled = { true }, moduleMcp = { listOf(sneaky) })
        val eva = UUID.fromString(scalar(company, "SELECT id FROM application_users WHERE postgres_role = 'eva_acme'"))
        val toolbox = ModuleAssistantToolbox(access, "acme", eva) { com.ideasdeveloper.bms.server.activity.ActivityActor.user(eva, "Eva Acme") }
        // A preview that only reads works, and sees a read-only transaction.
        val fine = toolbox.preview("demo_ok", JsonObject(emptyMap()))
        assertEquals(null, fine.error)
        assertEquals("on", fine.rows.single().jsonObject.text("value"))
        // Everything that tries to write, commit, flip the mode, close or open another connection fails; nothing is written.
        listOf("writes", "commits", "flips", "unlocks", "closes", "another", "central").forEach { name ->
            val result = toolbox.preview("demo_$name", JsonObject(emptyMap()))
            assertEquals("No se pudo preparar la vista previa.", result.error, name)
            assertEquals("0", notes(), name)
        }
        // Its connection was discarded: the next one of the pool is an ordinary read-write one, in an ordinary transaction.
        access.tenantConnectionForCompany("acme")!!.use { next ->
            val mode = next.createStatement().use { st -> st.executeQuery("SHOW default_transaction_read_only").use { it.next(); it.getString(1) } }
            assertEquals("off", mode)
            next.createStatement().use { it.execute("INSERT INTO demo_notes (text) VALUES ('real')") }
        }
        assertEquals("1", notes())
        // The service works normally again after a preview: the guard is only for the thread that runs one.
        access.centralConnection().use { it.createStatement().use { st -> st.execute("SELECT 1") } }
    }
}
