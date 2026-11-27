package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningConfig
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.bms.server.modules.ServerModuleProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.ServiceLoader
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every module tool declared read only, run as the business owner inside a READ ONLY transaction with every module
 * enabled: none may write (PostgreSQL refuses any write there with SQLSTATE 25006). The assistant runs read-only tools
 * without asking, so a hidden write would happen without the user's confirmation. Arguments are synthetic (the required
 * ones, from each schema): tools that refuse them or find nothing still count, what matters is that nothing writes.
 * Runs only with BMS_TEST_PG_URL (a DISPOSABLE server): it creates and drops bms_ro_* databases.
 */
class ReadOnlyToolsPostgresTest {
    private val url: String? = (System.getenv("BMS_TEST_PG_URL") ?: System.getenv("BMS_ACTIVITY_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_ro_central"
    private val company = "bms_ro_acme"
    private val opened = mutableListOf<LoginDatabases>()

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

    private fun arguments(schema: JsonObject): JsonObject {
        val properties = schema["properties"] as? JsonObject ?: return JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return JsonObject(required.associateWith { key ->
            val property = properties[key] as? JsonObject ?: JsonObject(emptyMap())
            val type = (property["type"] as? JsonPrimitive)?.contentOrNull
            val format = (property["format"] as? JsonPrimitive)?.contentOrNull
            val first = (property["enum"] as? JsonArray)?.firstOrNull()
            when {
                first != null -> first
                format == "uuid" -> JsonPrimitive(UUID.randomUUID().toString())
                format == "date" -> JsonPrimitive(LocalDate.of(2026, 11, 27).toString())
                type == "integer" || type == "number" -> JsonPrimitive(1)
                type == "boolean" -> JsonPrimitive(false)
                type == "array" -> JsonArray(emptyList())
                type == "object" -> JsonObject(emptyMap())
                else -> JsonPrimitive("x")
            }
        })
    }

    private fun writes(failure: Throwable?): Boolean {
        var current = failure
        while (current != null) {
            if (current is SQLException && current.sqlState == "25006") return true
            current = current.cause
        }
        return false
    }

    @Test
    fun noReadOnlyToolWrites() {
        if (url == null) return
        DriverManager.getConnection(url).use { admin ->
            listOf(central, company).forEach { admin.run("DROP DATABASE IF EXISTS $it WITH (FORCE)") }
            admin.run("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ro_owner') THEN CREATE ROLE ro_owner; END IF; END $$")
            listOf(central, company).forEach { admin.run("CREATE DATABASE $it OWNER ro_owner") }
        }
        val ownerId = UUID.randomUUID()
        val boss = UUID.randomUUID()
        connect(central).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            File(root, "database/control/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(migration("database/control/migrations/${it.name}")) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', 'ro_owner', TRUE, 'Duena', 'duena@example.com')")
            db.run("INSERT INTO server_owners (user_id) VALUES ('$ownerId')")
            db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), 'acme', 'Acme', '$company', gen_random_uuid())")
        }
        val modules = ServiceLoader.load(ServerModuleProvider::class.java).map { it.create() }.distinctBy { it.definition.id }
        connect(company).use { db ->
            db.run("SET ROLE ro_owner")
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials")
                .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$boss', 'dueno_ro', TRUE, 'Dueno', 'dueno@example.com')")
            db.run("INSERT INTO business_owner (user_id) VALUES ('$boss')")
            // A second user without a WiFi code nor access row yet: listing it must not create them.
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES (gen_random_uuid(), 'eva_ro', TRUE, 'Eva', 'eva@example.com')")
            modules.map { it.definition }.filterNot { it.locked }.forEach { definition ->
                db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('${definition.id}', 'm', 'enabled', CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING")
            }
        }
        val data = source(central)
        val databases = LoginDatabases(data, Database.connect(data), urlOf(central), "[]", pgUser, "", prefix, 3600).also { opened += it }
        val registry = ModuleRegistry(modules, modules.map { it.definition.id }.toSet())
        CompanyProvisioningService(
            central = data,
            config = CompanyProvisioningConfig(urlOf("postgres").substringBefore("?"), pgUser, "", prefix, root.path, provisioningUser = pgUser, provisioningPassword = ""),
            loginDatabases = databases,
            moduleRegistry = registry,
            remoteModuleCatalog = RemoteModuleCatalog(),
            defaultCatalogUrl = "",
            modulePackagesRoot = java.nio.file.Files.createTempDirectory("ro-modules"),
            modulePackageBuilder = null,
            requestRestart = {},
        ).applyPendingModuleMigrations()
        val access = ModuleAccessService(data, databases, moduleInstalled = { true }, moduleMcp = { registry.mcp() })
        val permissions = ModulePermissions("acme", "", businessOwner = true, granted = emptySet(), userId = boss)
        val tools = registry.mcp().flatMap { it.tools }.filter { it.tool.readOnly }
        assertTrue(tools.size > 50, "expected the read-only tools of every module, got ${tools.size}")
        val writers = mutableListOf<String>()
        tools.forEach { registered ->
            connect(company).use { tenant ->
                tenant.autoCommit = false
                tenant.run("SET TRANSACTION READ ONLY")
                val failure = runCatching {
                    registered.tool.handler(McpToolCall(arguments(registered.tool.inputSchema), permissions, tenant, access, "prueba", registered.moduleId))
                }.exceptionOrNull()
                runCatching { tenant.rollback() }
                if (writes(failure)) {
                    // Run it again in an ordinary transaction (rolled back) to see what it wrote: the only write a
                    // read may make is its own activity-log row (bulk reads are logged by design, ACTIVITY_LOG.md §4).
                    tenant.run("SET TRANSACTION READ WRITE")
                    runCatching { registered.tool.handler(McpToolCall(arguments(registered.tool.inputSchema), permissions, tenant, access, "prueba", registered.moduleId)) }
                    val written = tenant.createStatement().use { st ->
                        st.executeQuery("SELECT relname FROM pg_stat_xact_user_tables WHERE n_tup_ins + n_tup_upd + n_tup_del > 0").use { rows ->
                            buildSet { while (rows.next()) add(rows.getString(1)) }
                        }
                    }
                    runCatching { tenant.rollback() }
                    val other = written - setOf("activity_log", "activity_log_chain")
                    if (other.isNotEmpty() || written.isEmpty()) writers += "${registered.qualifiedName} $written"
                }
            }
        }
        assertTrue(writers.isEmpty(), "read-only tools that write: $writers")
        // The check sees writes: a tool that does write (it is not declared read only) is caught the same way.
        val writer = registry.mcp().flatMap { it.tools }.first { it.qualifiedName == "hotspot_my_wifi_code" }
        connect(company).use { tenant ->
            tenant.autoCommit = false
            tenant.run("SET TRANSACTION READ ONLY")
            val failure = runCatching { writer.tool.handler(McpToolCall(JsonObject(emptyMap()), permissions, tenant, access, "prueba", writer.moduleId)) }.exceptionOrNull()
            assertTrue(writes(failure), "my_wifi_code should be refused in a read-only transaction: $failure")
        }
    }
}
