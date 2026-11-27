package com.ideasdeveloper.bms.modules.clientes

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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every write of Clientes leaves its row in the company activity log (ACTIVITY_LOG.md §4, §5): through the real routes with a
 * real session (the actor is resolved by Core), through the agent tools ("Agente de ..."), by the sharing job and for settings.
 * The walks need a DISPOSABLE PostgreSQL: they run only with BMS_ACTIVITY_PG_URL (or BMS_SERVER_ADMINS_PG_URL); the coverage
 * checks of the write tools and routes always run.
 */
class ClientesActivityLogTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_cal_central"
    private val codes = listOf("acme", "beta")
    private val ownerGroup = UUID.randomUUID()
    private val ownerId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val accountId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
    private val token = "T".repeat(43)
    private val opened = mutableListOf<LoginDatabases>()

    // ---- Coverage: every write tool and route of the module is walked below ----

    private val walkedTools = listOf(
        "clientes_archive_customer", "clientes_create_customer", "clientes_create_customer_field", "clientes_delete_customer_field",
        "clientes_link_company", "clientes_link_user_account", "clientes_merge_manually", "clientes_restore_customer",
        "clientes_separate_merge", "clientes_unlink_company", "clientes_unlink_user_account", "clientes_update_customer",
    )

    private val walkedRoutes = listOf(
        "DELETE /modules/clientes/customers/{customerId}", "DELETE /modules/clientes/fields/{fieldId}",
        "POST /modules/clientes/customers", "POST /modules/clientes/customers/{customerId}/restore", "POST /modules/clientes/fields",
        "POST /modules/clientes/ui/customer", "POST /modules/clientes/ui/links", "PUT /modules/clientes/customers/{customerId}",
    )

    @Test
    fun everyWriteToolAndRouteIsWalkedByTheTests() {
        assertEquals(walkedTools, ActivityLogTesting.writeTools(ClientesServerModule))
        assertEquals(walkedRoutes, ActivityLogTesting.writeRoutes(ClientesServerModule))
    }

    // ---- Fixture ----

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private fun urlOf(database: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$database$1")
    private fun connect(database: String): Connection = DriverManager.getConnection(urlOf(database))
    private fun source(database: String) = PGSimpleDataSource().apply { setURL(urlOf(database)) }
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }
    private val prefix get() = urlOf("X").substringBefore("/X") + "/"
    private val pgUser get() = Regex("user=([^&]+)").find(url!!)?.groupValues?.get(1) ?: "postgres"
    private fun dbOf(code: String) = "bms_cal_$code"
    private fun sqlFiles(dir: String) = File(root, dir).listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }

    @AfterTest
    fun close() = opened.forEach { it.close() }

    /** Two companies of one owner group, each with the core tables, the activity log table and the migrations of Clientes. */
    private fun build(): ModuleAccessService {
        DriverManager.getConnection(url!!).use { admin ->
            (listOf(central) + codes.map(::dbOf)).forEach { name ->
                admin.run("DROP DATABASE IF EXISTS $name WITH (FORCE)")
                admin.run("CREATE DATABASE $name")
            }
        }
        connect(central).use { db ->
            sqlFiles("database/core/migrations").forEach { db.run(migration("database/core/migrations/${it.name}")) }
            sqlFiles("database/control/migrations").forEach { db.run(it.readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")) }
            codes.forEach { code ->
                db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), '$code', 'Empresa $code', '${dbOf(code)}', '$ownerGroup')")
            }
        }
        codes.forEach { code ->
            connect(dbOf(code)).use { db ->
                sqlFiles("database/core/migrations").forEach { db.run(migration("database/core/migrations/${it.name}")) }
                listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials", "V011__create_activity_log")
                    .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
                sqlFiles("modules/clientes/migrations").forEach { db.run(it.readText()) }
                db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('clientes', 'Clientes', 'enabled', CURRENT_TIMESTAMP) ON CONFLICT (module_id) DO UPDATE SET status = 'enabled'")
                db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', 'ana_$code', TRUE, 'Ana Ruiz', 'ana@empresa.com')")
                db.run("INSERT INTO business_owner (user_id) VALUES ('$ownerId')")
                db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$accountId', 'luis_$code', TRUE, 'Luis Mora', 'luis@x.com')")
                db.prepareStatement("INSERT INTO application_sessions (token_hash, user_id, expires_at) VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 hour')").use { insert ->
                    insert.setString(1, SessionTokenGenerator().hash(token))
                    insert.setObject(2, ownerId)
                    insert.executeUpdate()
                }
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

    private fun scalar(code: String, sql: String): String? = connect(dbOf(code)).use { db ->
        db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
    }

    private fun owner(): ActivityActor = ActivityActor.businessOwner(ownerId, "Ana Ruiz")

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, body: String? = null, company: String = "acme"): HttpResponse =
        client.request(path) {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Company-Code", company)
            contentType(ContentType.Application.Json)
            setBody(body ?: "{}")
        }

    private fun withRoutes(access: ModuleAccessService, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
            routing { ClientesServerModule.routes(this, access) }
        }
        block()
    }

    private fun Captured.about(type: String) = entry.entityType == type

    // ---- Routes: the screens' own operations ----

    @Test
    fun routesRecordCustomersFieldsNotesHistoryAndAccounts() {
        if (url == null) return
        val access = build()
        recording { recorder ->
            withRoutes(access) {
                // Settings: a custom field.
                var response = send(HttpMethod.Post, "/modules/clientes/fields", """{"fieldKey":"ciudad","label":"Ciudad","fieldType":"text"}""")
                assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
                val fieldId = Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
                var rows = recorder.take()
                assertEquals(listOf(ActivityActions.SettingsChanged), rows.map { it.action })
                assertEquals("customer_field", rows.single().entry.entityType)
                assertEquals("Ana Ruiz", rows.single().display)
                assertEquals("business_owner", rows.single().actor.kind)
                assertEquals("ciudad", rows.single().change("fieldKey")!!.new)

                // Create.
                response = send(HttpMethod.Post, "/modules/clientes/customers", """{"displayName":"Ana Perez","primaryEmail":"ana@x.com","primaryPhone":"+507600","dynamicFields":{"ciudad":"Lima"}}""")
                assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
                val customerId = Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Created), rows.map { it.action })
                assertEquals(customerId, rows.single().entry.entityId)
                assertEquals("Ana Perez", rows.single().entry.entityLabel)
                assertEquals("Lima", rows.single().change("campo.ciudad")!!.new)
                assertEquals("Ciudad", rows.single().change("campo.ciudad")!!.label)
                assertEquals("ana@x.com", rows.single().change("primaryEmail")!!.new)
                // The module's own history is written as before.
                assertEquals("created", scalar("acme", "SELECT kind FROM customer_activity WHERE customer_id = '$customerId'"))

                // Edit: old -> new per field; an edit that changes nothing leaves no row.
                response = send(HttpMethod.Put, "/modules/clientes/customers/$customerId", """{"displayName":"Ana Perez","primaryEmail":"luis@x.com","primaryPhone":"+507600","dynamicFields":{"ciudad":"Cusco"}}""")
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                assertEquals("ana@x.com" to "luis@x.com", rows.single().change("primaryEmail")!!.let { it.old to it.new })
                assertEquals("Lima" to "Cusco", rows.single().change("campo.ciudad")!!.let { it.old to it.new })
                assertEquals(null, rows.single().change("displayName"))
                send(HttpMethod.Put, "/modules/clientes/customers/$customerId", """{"displayName":"Ana Perez","primaryEmail":"luis@x.com","primaryPhone":"+507600","dynamicFields":{"ciudad":"Cusco"}}""")
                assertTrue(recorder.take().isEmpty())

                // Notes and manual history entries of the profile (added, then deleted: the row keeps what was there).
                val profile = "/modules/clientes/ui/customer?id=$customerId"
                assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "$profile&op=note", """{"body":"Alergico al mani"}""").status)
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Commented), rows.map { it.action })
                assertEquals("Alergico al mani", rows.single().change("note")!!.new)
                val noteId = scalar("acme", "SELECT id FROM customer_notes WHERE customer_id = '$customerId'")!!
                send(HttpMethod.Post, "$profile&op=history", """{"title":"Compro un paquete","detail":"3 noches"}""")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Created), rows.map { it.action })
                assertEquals("Compro un paquete", rows.single().change("title")!!.new)
                val entryId = scalar("acme", "SELECT id FROM customer_history WHERE customer_id = '$customerId' AND kind = 'manual'")!!
                send(HttpMethod.Post, "$profile&op=deletenote&note=$noteId")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Deleted), rows.map { it.action })
                assertEquals("Alergico al mani", rows.single().change("note")!!.old)
                assertEquals("0", scalar("acme", "SELECT count(*) FROM customer_notes WHERE customer_id = '$customerId'"))
                send(HttpMethod.Post, "$profile&op=deletehistory&entry=$entryId")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Deleted), rows.map { it.action })
                assertEquals("Compro un paquete", rows.single().change("title")!!.old)
                assertEquals("0", scalar("acme", "SELECT count(*) FROM customer_history WHERE customer_id = '$customerId' AND kind = 'manual'"))

                // The user account with the same email is linked to the card and unlinked.
                send(HttpMethod.Post, "$profile&op=linkuser&account=$accountId")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                assertEquals("customer_account_link", rows.single().entry.entityType)
                assertTrue(rows.single().change("userAccount")!!.new!!.startsWith("Luis Mora"))
                send(HttpMethod.Post, "$profile&op=unlinkuser")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                assertTrue(rows.single().change("userAccount")!!.old!!.startsWith("Luis Mora"))

                // Archive and restore, from the routes and from the profile.
                assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/modules/clientes/customers/$customerId").status)
                assertEquals(listOf(ActivityActions.Archived), recorder.take().map { it.action })
                assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/modules/clientes/customers/$customerId/restore").status)
                assertEquals(listOf(ActivityActions.Restored), recorder.take().map { it.action })
                send(HttpMethod.Delete, "/modules/clientes/customers/$customerId")
                recorder.take()
                send(HttpMethod.Post, "$profile&op=restore")
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.Restored), rows.map { it.action })
                assertEquals("Ana Perez", rows.single().entry.entityLabel)

                // Settings again: the field is removed, its values stay on the customers.
                assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/modules/clientes/fields/$fieldId").status)
                rows = recorder.take()
                assertEquals(listOf(ActivityActions.SettingsChanged), rows.map { it.action })
                assertEquals("ciudad", rows.single().change("fieldKey")!!.old)
                assertEquals("customer_field", rows.single().entry.entityType)
            }
        }
    }

    // ---- Agent tools ----

    private fun toolCall(access: ModuleAccessService, tenant: Connection, name: String, arguments: JsonObject): com.ideasdeveloper.bms.server.modules.McpToolCall {
        val permissions = ActivityLogTesting.permissions(businessOwner = true, actor = owner()).let {
            ModulePermissions("acme", "", true, emptySet(), ownerId, activityActor = it.activityActor)
        }.throughAgent("Claude")
        return McpToolCall(arguments, permissions, tenant, access, "Claude", "clientes")
    }

    private fun run(access: ModuleAccessService, tenant: Connection, name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject =
        ClientesMcpTools.tools.single { it.name == name }.handler(toolCall(access, tenant, name, arguments)).jsonObject

    @Test
    fun agentToolsRecordTheUserBehindTheAgent() {
        if (url == null) return
        val access = build()
        recording { recorder ->
            access.tenantConnectionForCompany("acme")!!.use { tenant ->
                val walked = mutableListOf<String>()
                fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): Pair<JsonObject, List<Captured>> {
                    walked += "clientes_$name"
                    val result = run(access, tenant, name, arguments)
                    return result to recorder.take().also { rows -> rows.forEach { assertTrue(it.display.startsWith("Agente de "), it.display) ; assertEquals("Claude", it.actor.agentName) } }
                }
                var (result, rows) = call("create_customer_field", buildJsonObject { put("fieldKey", "ciudad"); put("label", "Ciudad"); put("fieldType", "text") })
                assertEquals(listOf(ActivityActions.SettingsChanged), rows.map { it.action })
                assertEquals("Agente de Ana Ruiz", rows.single().display)
                val fieldId = result["id"]!!.jsonPrimitive.content

                val created = call("create_customer", buildJsonObject { put("displayName", "Ana Perez"); put("primaryEmail", "luis@x.com"); put("primaryPhone", "+507600") })
                val id = created.first["id"]!!.jsonPrimitive.content
                assertEquals(listOf(ActivityActions.Created), created.second.map { it.action })
                assertEquals("luis@x.com", created.second.single().change("primaryEmail")!!.new)

                rows = call("update_customer", buildJsonObject { put("id", id); put("primaryPhone", "+507999"); put("dynamicFields", buildJsonObject { put("ciudad", "Lima") }) }).second
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                assertEquals("+507600" to "+507999", rows.single().change("primaryPhone")!!.let { it.old to it.new })
                assertEquals("Lima", rows.single().change("campo.ciudad")!!.new)

                rows = call("link_user_account", buildJsonObject { put("userId", accountId.toString()) }).second
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })
                rows = call("unlink_user_account", buildJsonObject { put("customerId", id) }).second
                assertEquals(listOf(ActivityActions.Updated), rows.map { it.action })

                assertEquals(listOf(ActivityActions.Archived), call("archive_customer", buildJsonObject { put("id", id) }).second.map { it.action })
                assertEquals(listOf(ActivityActions.Restored), call("restore_customer", buildJsonObject { put("id", id) }).second.map { it.action })
                rows = call("delete_customer_field", buildJsonObject { put("id", fieldId) }).second
                assertEquals(listOf(ActivityActions.SettingsChanged), rows.map { it.action })

                // The tools about linked companies and merges are walked in the next test.
                val linked = setOf("clientes_link_company", "clientes_unlink_company", "clientes_separate_merge", "clientes_merge_manually")
                assertEquals(ActivityLogTesting.writeTools(ClientesServerModule).toSet(), walked.toSet() + linked)
            }
        }
    }

    // ---- Linked companies: link, the sharing job, merges, separation and unlink ----

    private fun customer(connection: Connection, name: String, email: String, actor: ActivityActor) {
        ClientesServerModule.createCustomerLogged(
            connection, UUID.randomUUID(), CreateCustomerRequest(name, email, ""), ClientesAuthor(null, "Ana Ruiz", actor),
        )
    }

    @Test
    fun linkingTheSharingJobMergesAndUnlinkingAreRecorded() {
        if (url == null) return
        val access = build()
        recording { recorder ->
            access.tenantConnectionForCompany("acme")!!.use { acme ->
                access.tenantConnectionForCompany("beta")!!.use { beta ->
                    // Same name and email in both: merged by itself. Same email, another name: left for the owner (two pairs).
                    customer(acme, "Ana Perez", "ana@x.com", owner()); customer(beta, "ana perez", "ana@x.com", owner())
                    customer(acme, "Zoe Ruiz", "zoe@x.com", owner()); customer(beta, "Zoe Mora", "zoe@x.com", owner())
                    customer(acme, "Leo Sol", "leo@x.com", owner()); customer(beta, "Leo Luna", "leo@x.com", owner())
                    customer(acme, "Solo Acme", "solo@x.com", owner())
                    recorder.take()

                    // Linking from the route: both companies get their row.
                    withRoutes(access) {
                        val response = send(HttpMethod.Post, "/modules/clientes/ui/links?op=link&company=beta")
                        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    }
                    var rows = recorder.take()
                    assertEquals(2, rows.size)
                    rows.forEach {
                        assertEquals(ActivityActions.SettingsChanged, it.action)
                        assertEquals("company_link", it.entry.entityType)
                        assertEquals("Ana Ruiz", it.display)
                    }

                    // The background job copies and merges: its own actor, one row per destination and the merges.
                    repeat(3) {
                        ClientesLinking.sync(access, acme, "acme")
                        ClientesLinking.sync(access, beta, "beta")
                    }
                    rows = recorder.take()
                    assertTrue(rows.isNotEmpty())
                    rows.forEach { assertEquals("job", it.actor.kind, "${it.entry.summary} / ${it.display}") }
                    assertTrue(rows.all { it.display == "Tarea programada Compartir clientes" })
                    assertTrue(rows.any { it.entry.entityType == "customer_sync" && it.change("copied") != null })
                    assertTrue(rows.any { it.action == ActivityActions.Archived && it.about("customer") }, "the merged duplicate is archived")
                    assertTrue(rows.any { it.entry.entityType == "customer_review" && it.action == ActivityActions.Created })

                    // The owner decides the two pairs through the agent tools.
                    // Each company keeps its own decision for the pair, so a pair may be listed from both sides.
                    val reviews = ClientesLinking.reviews(access, "acme").filter { it.status == "review" }
                    assertTrue(reviews.size >= 2, reviews.toString())
                    val manual = reviews.first { "Zoe" in it.mergedName + it.keptName }
                    val apart = reviews.first { "Leo" in it.mergedName + it.keptName }
                    var result = run(access, acme, "merge_manually", buildJsonObject { put("companyCode", manual.company.code); put("mergeId", manual.id.toString()) })
                    rows = recorder.take()
                    assertTrue(rows.isNotEmpty(), result.toString())
                    rows.forEach { assertTrue(it.display.startsWith("Agente de Ana Ruiz"), it.display) }
                    assertTrue(rows.any { it.action == ActivityActions.Archived })
                    result = run(access, acme, "separate_merge", buildJsonObject { put("companyCode", apart.company.code); put("mergeId", apart.id.toString()) })
                    rows = recorder.take()
                    assertEquals(listOf(ActivityActions.Updated), rows.map { it.action }, result.toString())
                    assertEquals("customer_review", rows.single().entry.entityType)
                    assertTrue(rows.single().display.startsWith("Agente de "))

                    // A person's edit is copied to the other company by an automation started by that person.
                    withRoutes(access) {
                        val soloId = scalar("acme", "SELECT id FROM customers WHERE display_name = 'Solo Acme'")!!
                        recorder.take()
                        val response = send(HttpMethod.Put, "/modules/clientes/customers/$soloId", """{"displayName":"Solo Acme","primaryEmail":"solo@x.com","primaryPhone":"+1","dynamicFields":{}}""")
                        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    }
                    rows = recorder.take()
                    assertTrue(rows.any { it.display == "Ana Ruiz" && it.action == ActivityActions.Updated }, rows.map { it.display }.toString())
                    assertTrue(rows.any { it.actor.kind == "automation" && it.display == "Automatizacion Compartir clientes (Ana Ruiz)" }, rows.map { it.display }.toString())

                    // Unlinking from the tool leaves its row in both companies.
                    result = run(access, acme, "unlink_company", buildJsonObject { put("companyCode", "beta") })
                    rows = recorder.take()
                    assertEquals(2, rows.count { it.entry.entityType == "company_link" && it.action == ActivityActions.SettingsChanged }, result.toString())
                    rows.forEach { assertTrue(it.display.startsWith("Agente de ")) }
                    // And linking again from the tool.
                    run(access, acme, "link_company", buildJsonObject { put("companyCode", "beta") })
                    assertEquals(2, recorder.take().count { it.entry.entityType == "company_link" })
                }
            }
        }
    }

    // ---- The row is part of the change ----

    @Test
    fun aFailingActivityRowUndoesTheChangeAndTheRowLandsInTheTable() {
        if (url == null) return
        val access = build()
        access.tenantConnectionForCompany("acme")!!.use { tenant ->
            val author = ClientesAuthor(ownerId, "Ana Ruiz", owner())
            val request = CreateCustomerRequest("Ana Perez", "ana@x.com", "")
            // Nothing captured here: the row is really written, in the same transaction as the customer.
            val id = UUID.randomUUID()
            ClientesServerModule.createCustomerLogged(tenant, id, request, author)
            assertEquals("1", scalar("acme", "SELECT count(*) FROM activity_log WHERE module_id = 'clientes' AND action = 'created' AND entity_id = '$id'"))
            assertEquals("Ana Ruiz", scalar("acme", "SELECT actor_display FROM activity_log WHERE entity_id = '$id'"))
            // The row cannot be written: the customer is not created either.
            val other = UUID.randomUUID()
            ActivityLog.capturing({ _, _ -> error("tabla sin permiso") }) {
                assertFailsWith<ActivityWriteException> { ClientesServerModule.createCustomerLogged(tenant, other, request.copy(primaryEmail = "otra@x.com"), author) }
            }
            assertEquals("0", scalar("acme", "SELECT count(*) FROM customers WHERE id = '$other'"))
            assertEquals("0", scalar("acme", "SELECT count(*) FROM customer_activity WHERE customer_id = '$other'"))
            assertNotNull(scalar("acme", "SELECT id FROM customers WHERE id = '$id'"))
        }
    }
}
