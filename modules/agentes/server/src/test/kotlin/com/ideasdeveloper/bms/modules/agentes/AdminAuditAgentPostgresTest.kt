package com.ideasdeveloper.bms.modules.agentes

import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ServerActivity
import com.ideasdeveloper.bms.server.activity.ServerAuditMcpTools
import com.ideasdeveloper.bms.server.admins.ServerAdminPasswords
import com.ideasdeveloper.bms.server.admins.ServerAudit
import com.ideasdeveloper.bms.server.admins.ServerPermission
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningConfig
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.platform.mcp.ServerMcpTools
import com.ideasdeveloper.bms.server.admins.ServerOwnerAuthorizer
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.RemoteModuleCatalog
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A server administrator's AI agent at server level (ACTIVITY_LOG.md §7) through the real MCP
 * endpoint: it is offered ONLY servidor_list_server_audit and servidor_list_company_activity, cannot
 * call any other server or module tool nor act in a company, stays inside its scope, every call is
 * audited, and it stops (revoked, audited) on the next call once the permission is removed.
 * Runs only with BMS_ACTIVITY_PG_URL (or BMS_SERVER_ADMINS_PG_URL) of a DISPOSABLE PostgreSQL.
 */
class AdminAuditAgentPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_aa_central"
    private val workspace = "bms_aa_servidor"
    private val companies = listOf("acme", "otra")
    private val ownerId = UUID.randomUUID()
    private val adminId = UUID.randomUUID()
    private val opened = mutableListOf<LoginDatabases>()

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private fun urlOf(database: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$database$1")
    private fun connect(database: String): Connection = DriverManager.getConnection(urlOf(database))
    private fun source(database: String) = PGSimpleDataSource().apply { setURL(urlOf(database)) }
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }
    private val prefix get() = urlOf("X").substringBefore("/X") + "/"
    private val pgUser get() = Regex("user=([^&]+)").find(url!!)?.groupValues?.get(1) ?: "postgres"

    @AfterTest
    fun close() = opened.forEach { it.close() }

    private fun scalar(database: String, sql: String): String? = connect(database).use { db ->
        db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
    }

    private fun tenantBase(db: Connection) {
        listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code")
            .forEach { db.run(migration("database/core/migrations/$it.sql")) }
        listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials", "V011__create_activity_log")
            .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
    }

    private fun reset(): String {
        DriverManager.getConnection(url!!).use { admin ->
            (listOf(central, workspace) + companies.map { "bms_aa_$it" }).forEach { name ->
                admin.run("DROP DATABASE IF EXISTS $name WITH (FORCE)")
                admin.run("CREATE DATABASE $name")
            }
            admin.run("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aa_owner') THEN CREATE ROLE aa_owner; END IF; END $$")
        }
        connect(central).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            File(root, "database/control/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                .forEach { db.run(it.readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', 'aa_owner', TRUE, 'Duena', 'duena@example.com')")
            db.run("INSERT INTO server_owners (user_id) VALUES ('$ownerId')")
            companies.forEach { code -> db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), '$code', 'Empresa $code', 'bms_aa_$code', gen_random_uuid())") }
            db.run("INSERT INTO companies (id, code, name, database_name, owner_group, is_server_workspace) VALUES (gen_random_uuid(), 'servidor', 'Espacio', '$workspace', gen_random_uuid(), TRUE)")
        }
        connect(workspace).use { db ->
            tenantBase(db)
            File(root, "modules/agentes/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('agentes', 'Agentes IA', 'enabled', CURRENT_TIMESTAMP)")
        }
        companies.forEach { code ->
            connect("bms_aa_$code").use { db ->
                tenantBase(db)
                ActivityLog.record(db, ActivityActor.user(UUID.randomUUID(), "Eva $code"), ActivityEntry("crm", "lead", "1", "Lead de $code", "created", "Creo el lead de $code."))
            }
        }
        val databases = LoginDatabases(source(central), Database.connect(source(central)), urlOf(central), "[]", pgUser, "", prefix, 3600).also { opened += it }
        databases.admins.create(adminId, "ana_adm", "Ana Admin", "ana@example.com", ServerAdminPasswords.encode("clave-larga-de-ana-0123"),
            setOf(ServerPermission.AuditView), allCompanies = false, companies = setOf("acme"), createdBy = ownerId)
        databases.admins.setPassword(adminId, ServerAdminPasswords.encode("clave-larga-de-ana-0123"), mustChange = false)
        this.databases = databases
        return connect(workspace).use { db ->
            AgentTokens.createOAuth(
                db, "servidor", AgentOwner(adminId, serverOwner = false, "Ana Admin"), "client", "Claude", setOf("servidor"), true, null,
                Duration.ofHours(1), serverAdmin = true,
            ).accessToken
        }
    }

    private lateinit var databases: LoginDatabases

    private fun access(): ModuleAccessService {
        val data = source(central)
        val provisioning = CompanyProvisioningService(
            central = data,
            config = CompanyProvisioningConfig(urlOf("postgres").substringBefore("?"), pgUser, "", prefix, root.path),
            loginDatabases = databases, moduleRegistry = ModuleRegistry(emptyList()), remoteModuleCatalog = RemoteModuleCatalog(),
            defaultCatalogUrl = "", modulePackagesRoot = java.nio.file.Files.createTempDirectory("aa-modules"), modulePackageBuilder = null, requestRestart = {},
        )
        val activity = ServerActivity(data, { name -> source(name).connection }, { it })
        val audit = ServerAuditMcpTools(data, { ServerAudit(data) }, { activity }, { id -> ServerOwnerAuthorizer.liveActor(databases.admins, data, id) })
        val serverMcp = ServerMcpTools.mcp(provisioning, auditTools = audit.tools)
        val agentes = com.ideasdeveloper.bms.server.modules.RegisteredModuleMcp(
            "agentes", "Agentes IA", "", AgentesMcpTools.mcp.tools.map { com.ideasdeveloper.bms.server.modules.RegisteredMcpTool("agentes", "Agentes IA", it) },
        )
        return ModuleAccessService(data, databases, moduleMcp = { listOf(serverMcp, agentes) })
    }

    private fun rpc(method: String, params: String = "{}") = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""

    @Test
    fun anAdministratorsAgentGetsOnlyTheAuditToolsAndStopsWhenThePermissionGoes() {
        if (url == null) return
        val token = reset()
        val access = access()
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
                routing { AgentesServerModule.routes(this, access) }
            }
            suspend fun send(body: String) = client.post(AgentesScreens.McpRoute) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            suspend fun call(name: String, arguments: String = "{}"): JsonObject {
                val response = send(rpc("tools/call", """{"name":"$name","arguments":$arguments}"""))
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            fun JsonObject.isToolError() = this["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean == true
            fun JsonObject.text() = this["result"]?.jsonObject?.get("content")?.jsonArray?.first()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()

            val listed = Json.parseToJsonElement(send(rpc("tools/list")).bodyAsText()).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(ServerAuditMcpTools.AdminAgentToolNames, listed)

            // Inside the scope: the activity of acme only; another company or the workspace is refused.
            val merged = call("servidor_list_company_activity")
            assertTrue(!merged.isToolError(), merged.toString())
            assertTrue(merged.text().contains("Lead de acme") && !merged.text().contains("Lead de otra"), merged.text())
            assertTrue(call("servidor_list_company_activity", """{"company":"otra"}""").isToolError())
            assertTrue(call("servidor_list_company_activity", """{"company":"servidor"}""").isToolError())
            assertTrue(!call("servidor_list_server_audit").isToolError())
            // One audit row per call that read (the two refused ones read nothing).
            assertEquals("2", scalar(central, "SELECT count(*) FROM provisioning_audit_log WHERE action = '${ServerAuditMcpTools.AuditAction}' AND actor_kind = 'server_admin'"))

            // No other tool: server tools, module tools, or acting in a company.
            listOf("servidor_list_companies", "servidor_create_company", "servidor_set_company_module", "agentes_list_agents", "agentes_revoke_agent").forEach { name ->
                val refused = call(name)
                assertTrue(refused["error"] != null, "$name: $refused")
            }
            assertTrue(call("servidor_list_server_audit", """{"acting_company":"acme"}""").isToolError())
            assertTrue((scalar(central, "SELECT count(*) FROM provisioning_audit_log WHERE action = 'server_admin.agent_denied'") ?: "0").toInt() >= 5)

            // The permission goes: the very next call fails, the connection is revoked and audited, then 401.
            connect(central).use { it.run("DELETE FROM server_admin_permissions WHERE user_id = '$adminId'") }
            val after = send(rpc("tools/call", """{"name":"servidor_list_server_audit","arguments":{}}"""))
            assertEquals(HttpStatusCode.Unauthorized, after.status)
            assertEquals("1", scalar(central, "SELECT count(*) FROM provisioning_audit_log WHERE action = 'server_admin.agent_revoked'"))
            assertTrue(scalar(workspace, "SELECT revoked_at IS NOT NULL FROM agent_tokens WHERE server_admin") == "t")
            assertEquals(HttpStatusCode.Unauthorized, send(rpc("tools/list")).status)
        }
        // Nothing was written in the companies by the agent's reads (server reads go to the server audit).
        assertEquals("1", scalar("bms_aa_acme", "SELECT count(*) FROM activity_log"))
    }

    @Test
    fun aDeactivatedAdministratorsAgentStopsAtOnce() {
        if (url == null) return
        val token = reset()
        val access = access()
        connect(central).use { it.run("UPDATE server_admins SET is_active = FALSE WHERE user_id = '$adminId'") }
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
                routing { AgentesServerModule.routes(this, access) }
            }
            val response = client.post(AgentesScreens.McpRoute) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(rpc("tools/list"))
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
        assertEquals("t", scalar(workspace, "SELECT revoked_at IS NOT NULL FROM agent_tokens WHERE server_admin"))
        assertEquals("1", scalar(central, "SELECT count(*) FROM provisioning_audit_log WHERE action = 'server_admin.agent_revoked'"))
    }
}
