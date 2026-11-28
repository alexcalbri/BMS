package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningConfig
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every tool declared read only (modules AND extensions, as the real registry offers them), run as the business owner inside
 * a READ ONLY transaction with every module and extension enabled and a minimal set of records seeded: none may write
 * (PostgreSQL refuses any write there with SQLSTATE 25006). The assistant runs read-only tools without asking, so a hidden
 * write would happen without the user's confirmation. The records come from the tools' own write handlers (and a few rows of
 * SQL where no tool creates them) so the `get_*` tools get past "not found"; arguments are the seeded ids or, failing that,
 * synthetic ones from each schema. A tool that still refuses must be listed in [refuses] with why.
 *
 * Intentional writes: only [loggedReads], reads that leave their own activity-log row by design (ACTIVITY_LOG.md section 4).
 * External calls: [externalReads] are read-only tools that ask a provider (fixed endpoints). The test records every request
 * the JDK's default ProxySelector sees while a tool runs and every networking exception in its failure; a read-only tool
 * that does either and is not in the list fails. Honest limit: that sees only what the tool reaches under these seeds (no
 * provider credentials are seeded, so the listed tools stop before the call) and HttpClients built before the test with a
 * selector of their own; the source tripwire [knownHttpFiles] covers the rest by failing when a NEW file uses an HTTP client.
 * Runs only with BMS_TEST_PG_URL (a DISPOSABLE server): it creates and drops bms_ro_* databases.
 */
class ReadOnlyToolsPostgresTest {
    private val url: String? = (System.getenv("BMS_TEST_PG_URL") ?: System.getenv("BMS_ACTIVITY_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_ro_central"
    private val company = "bms_ro_acme"
    private val opened = mutableListOf<LoginDatabases>()

    /** Reads that leave their own row in the activity log by design (an export or a log read is itself logged). */
    private val loggedReads = setOf("empresa_list_activity", "empresa_get_activity_entry", "hotspot_export_report")

    /** Read-only tools that ask an external provider (fixed endpoints, nothing sent but what is needed to read), and why that is acceptable. */
    private val externalReads = mapOf(
        "crm_search_rooms" to "Cloudbeds availability (api.cloudbeds.com): sends the stay dates and guest counts with the company's own API key",
        "crm_search_properties" to "the company's own WordPress site (its saved address, signed): sends the search text and price range",
        "hotspot_unifi_list_consoles" to "UniFi cloud (api.ui.com): lists the consoles of the saved API key",
        "hotspot_list_controller_ssids" to "the company's configured network controller (UniFi): reads its WiFi network names",
        "crm_list_monday_boards" to "monday (api.monday.com) with the company's token: lists boards",
        "crm_monday_outbound_boards" to "monday (api.monday.com) with the company's token: lists boards and their columns",
        "crm_inmobiliaria_monday_list_boards" to "monday (api.monday.com) with the company's token: lists boards",
        "compras_monday_list_boards" to "monday (api.monday.com) with the company's token: lists boards",
        "empleados_monday_list_boards" to "monday (api.monday.com) with the company's token: lists boards",
        "eventos_monday_list_boards" to "monday (api.monday.com) with the company's token: lists boards",
        "mantenimiento_monday_list_boards" to "monday (api.monday.com) with the company's token: lists boards",
        "membresia_monday_list_boards" to "monday (api.monday.com) with the company's token: lists boards",
        "propietarios_monday_list_boards" to "monday (api.monday.com) with the company's token: lists boards",
        "tareas_monday_list_boards" to "monday (api.monday.com) with the company's token: lists boards",
    )

    /** Read-only tools that still end in an error here, and why (no provider credentials, no server workspace...). Empty message, anything else: a gap in the seed. */
    private val refuses = mapOf(
        "hotspot_list_access_grants" to "only server owners in the server workspace",
        "hotspot_unifi_list_consoles" to "needs the UniFi API key (typed in the app)",
        "hotspot_list_controller_ssids" to "needs the controller console and site (chosen in the app)",
        "crm_search_properties" to "needs the WordPress site and its secret (typed in the app)",
        "crm_search_rooms" to "needs the Cloudbeds API key (typed in the app)",
        "agentes_get_agent_activity" to "needs a connection (token) made from the app",
        "crm_inmobiliaria_get_property" to "needs a property of the inmobiliaria extension (imported or created in the app)",
    ) + listOf(
        "compras_monday_import_status", "crm_inmobiliaria_monday_status", "empleados_monday_import_status", "eventos_monday_import_status",
        "mantenimiento_monday_import_status", "membresia_monday_import_status", "propietarios_monday_import_status", "tareas_monday_import_status",
    ).associateWith { "no monday import has run (it needs the token typed in the app)" } + externalReads.filterKeys { it.endsWith("_boards") }.mapValues { "needs the monday token (typed in the app)" }

    /** Source files that use an HTTP client of the JDK or the project's HTTP abstractions: a new one fails the test until reviewed. */
    private val knownHttpFiles = setOf(
        "CrmTranslate.kt", "CrmMeta.kt", "CrmMetaServer.kt", "CrmCloudbeds.kt", "CrmEncuentra24.kt", "UnifiClient.kt", "MondayApi.kt",
        "WordPressClient.kt", "VersionCatalogClient.kt", "CoreUpdateService.kt", "RemoteExtensionCatalog.kt", "RemoteModuleCatalog.kt",
        "ExtensionPackageBuilder.kt", "ModulePackages.kt", "AssistantModel.kt", "ExtensionService.kt", "CrmImport.kt", "MailHostGuard.kt",
        // The default transport of the sends and renders of the CRM (button, template mail, automation engine): writes, never a read-only tool.
        "CrmButtons.kt", "CrmTemplateMailer.kt", "CrmAutomationEngine.kt",
    )

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

    private fun text(value: String) = JsonPrimitive(value)

    private fun writes(failure: Throwable?): Boolean = generateSequence(failure) { it.cause }.any { it is SQLException && it.sqlState == "25006" }

    private val networkFailures = listOf("UnknownHostException", "ConnectException", "HttpConnectTimeoutException", "HttpTimeoutException", "NoRouteToHostException", "SSLException", "SocketTimeoutException")

    private fun networking(failure: Throwable?): Boolean = generateSequence(failure) { it.cause }.any { cause -> networkFailures.any { cause.javaClass.name.endsWith(it) } }

    /** The first `id` string in a tool's answer (an object or the first element of a list in it). */
    private fun idOf(element: JsonElement?): String? = when (element) {
        is JsonObject -> (element["id"] as? JsonPrimitive)?.contentOrNull ?: element.values.firstNotNullOfOrNull { idOf(it) }
        is JsonArray -> element.firstNotNullOfOrNull { idOf(it) }
        else -> null
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
        val modules = RealToolRegistry.modules
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
        val registry = RealToolRegistry.registry
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
        // Every extension installed and enabled: its tables, and its row in tenant_extensions (the gate of its tools).
        connect(company).use { db ->
            RealToolRegistry.extensions.forEach { extension ->
                File(root, "extensions/${extension.manifest.id}/migrations").listFiles().orEmpty().filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                    .forEach { db.run(migration("extensions/${extension.manifest.id}/migrations/${it.name}")) }
                db.run(
                    "INSERT INTO tenant_extensions (extension_id, module_id, version, status, enabled_at) VALUES ('${extension.manifest.id}', '${extension.manifest.module}', '1.0.0', 'enabled', CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
                )
            }
        }
        val access = ModuleAccessService(data, databases, moduleInstalled = { true }, moduleMcp = { registry.mcp() })
        val permissions = ActivityLogTesting.permissions(businessOwner = true, actor = ActivityLogTesting.user("Dueno", boss)).let {
            ModulePermissions("acme", "", businessOwner = true, granted = emptySet(), userId = boss, activityActor = it.activityActor)
        }
        val all = registry.mcp().flatMap { it.tools }
        val tools = all.filter { it.tool.readOnly }
        // The modules alone (CI server job) have fewer read-only tools than modules plus extensions (local helper).
        assertTrue(tools.size > if (RealToolRegistry.extensionsComplete) 90 else 60, "expected the read-only tools of every module and extension, got ${tools.size}")

        // ---- Seed: the tools' own write handlers, and SQL where nothing creates the record ----
        val ids = mutableMapOf<String, String>()
        connect(company).use { seed ->
            fun write(name: String, vararg pairs: Pair<String, JsonElement>): JsonObject {
                val registered = all.first { it.qualifiedName == name }
                val result = registered.tool.handler(McpToolCall(JsonObject(mapOf(*pairs)), permissions, seed, access, "semilla", registered.moduleId))
                return result as? JsonObject ?: JsonObject(emptyMap())
            }
            fun scalar(sql: String): String? = seed.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
            write("clientes_create_customer", "displayName" to text("Ana Prueba"), "email" to text("ana.prueba@example.com"))
            ids["customer"] = scalar("SELECT id::text FROM customers WHERE display_name = 'Ana Prueba'")!!
            write("crm_create_lead", "name" to text("Luis Prueba"), "email" to text("luis.prueba@example.com"))
            ids["lead"] = scalar("SELECT id::text FROM crm_leads LIMIT 1")!!
            write(
                "crm_save_automation", "name" to text("Prueba"), "trigger" to text("manual"),
                "actions" to buildJsonArray { add(buildJsonObject { put("kind", "notify"); put("who", "assigned"); put("title", "Aviso"); put("text", "Hola") }) },
            )
            ids["automation"] = scalar("SELECT id::text FROM crm_automations LIMIT 1")!!
            write("crm_import_csv", "name" to text("Prueba"), "csv" to text("Nombre,Correo\nAna,ana@example.com"))
            ids["import"] = scalar("SELECT id::text FROM crm_import_jobs LIMIT 1")!!
            write("compras_create_request", "title" to text("Papel"))
            ids["request"] = scalar("SELECT id::text FROM compras_requests LIMIT 1")!!
            seed.run("INSERT INTO tareas_boards (id, name) VALUES (gen_random_uuid(), 'Pruebas') ON CONFLICT DO NOTHING")
            ids["board"] = scalar("SELECT id::text FROM tareas_boards LIMIT 1")!!
            write("tareas_create_task", "board_id" to text(ids["board"]!!), "title" to text("Tarea de prueba"))
            ids["task"] = scalar("SELECT id::text FROM tareas_tasks LIMIT 1")!!
            write("mantenimiento_create_order", "title" to text("Fuga"))
            ids["order"] = scalar("SELECT id::text FROM mantenimiento_orders LIMIT 1")!!
            seed.run("INSERT INTO mantenimiento_plans (id, title, frequency, next_due) VALUES (gen_random_uuid(), 'Revision', 'monthly', CURRENT_DATE)")
            ids["plan"] = scalar("SELECT id::text FROM mantenimiento_plans LIMIT 1")!!
            seed.run("INSERT INTO propietarios_developments (id, name) VALUES (gen_random_uuid(), 'Desarrollo') ON CONFLICT DO NOTHING")
            ids["development"] = scalar("SELECT id::text FROM propietarios_developments LIMIT 1")!!
            write("propietarios_add_lot", "development_id" to text(ids["development"]!!), "lot_number" to text("L-1"))
            ids["lot"] = scalar("SELECT id::text FROM propietarios_lots LIMIT 1")!!
            write(
                "propietarios_register_ownership_change", "lot_id" to text(ids["lot"]!!), "date" to text("2026-11-27"),
                "owners" to buildJsonArray { add(buildJsonObject { put("customer_id", ids["customer"]!!) }) },
            )
            seed.run("INSERT INTO eventos_events (id, kind, title, starts_at, currency, capacity, status) VALUES (gen_random_uuid(), 'publico', 'Taller', now() + interval '1 day', 'USD', 10, 'publicado')")
            ids["event"] = scalar("SELECT id::text FROM eventos_events LIMIT 1")!!
            seed.run("INSERT INTO empleados_employees (id, full_name, user_id) VALUES (gen_random_uuid(), 'Dueno Prueba', '$boss')")
            ids["employee"] = scalar("SELECT id::text FROM empleados_employees LIMIT 1")!!
            ids["activity"] = scalar("SELECT max(id)::text FROM activity_log")!!
        }

        // What the read tools get for their id arguments (the rest of the arguments follow the schema).
        val customer = ids.getValue("customer")
        val fixed: Map<String, JsonObject> = mapOf(
            "agentes_get_agent_activity" to JsonObject(emptyMap()),
            "clientes_get_customer" to JsonObject(mapOf("id" to text(customer))),
            "clientes_get_customer_history" to JsonObject(mapOf("id" to text(customer))),
            "compras_get_request" to JsonObject(mapOf("id" to text(ids.getValue("request")))),
            "crm_get_lead" to JsonObject(mapOf("id" to text(ids.getValue("lead")))),
            "crm_get_lead_messages" to JsonObject(mapOf("id" to text(ids.getValue("lead")))),
            "crm_search_rooms" to JsonObject(mapOf("lead" to text(ids.getValue("lead")))),
            "crm_get_lead_items" to JsonObject(mapOf("lead" to text(ids.getValue("lead")))),
            "crm_get_automation" to JsonObject(mapOf("id" to text(ids.getValue("automation")))),
            "crm_get_import" to JsonObject(mapOf("job" to text(ids.getValue("import")))),
            "empleados_get_employee" to JsonObject(mapOf("id" to text(ids.getValue("employee")))),
            "empresa_get_activity_entry" to JsonObject(mapOf("id" to JsonPrimitive(ids.getValue("activity").toLong()))),
            "eventos_get_event" to JsonObject(mapOf("event_id" to text(ids.getValue("event")))),
            "eventos_get_capacity" to JsonObject(mapOf("event_id" to text(ids.getValue("event")))),
            "eventos_list_registrations" to JsonObject(mapOf("event_id" to text(ids.getValue("event")))),
            "mantenimiento_get_order" to JsonObject(mapOf("id" to text(ids.getValue("order")))),
            "mantenimiento_get_plan" to JsonObject(mapOf("id" to text(ids.getValue("plan")))),
            "membresia_get_member_status" to JsonObject(mapOf("customer_id" to text(customer))),
            "propietarios_get_lot" to JsonObject(mapOf("lot_id" to text(ids.getValue("lot")))),
            "propietarios_search_owners" to JsonObject(mapOf("query" to text("Ana"), "q" to text("Ana"))),
            "propietarios_get_owner" to JsonObject(mapOf("customer_id" to text(customer))),
            "tareas_get_task" to JsonObject(mapOf("id" to text(ids.getValue("task")))),
        )

        // ---- Run every read-only tool in a READ ONLY transaction, watching writes and the network ----
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previousSelector = java.net.ProxySelector.getDefault()
        java.net.ProxySelector.setDefault(object : java.net.ProxySelector() {
            override fun select(uri: java.net.URI?): MutableList<java.net.Proxy> {
                // Plain sockets (the database) ask too, as socket://host:port: only http(s) requests are HTTP.
                if (uri?.scheme?.startsWith("http") == true) requests += uri.toString()
                return mutableListOf(java.net.Proxy.NO_PROXY)
            }
            override fun connectFailed(uri: java.net.URI?, address: java.net.SocketAddress?, failure: java.io.IOException?) = Unit
        })
        val writers = mutableListOf<String>()
        val loggedWriters = mutableSetOf<String>()
        val networkers = mutableSetOf<String>()
        val refusing = mutableSetOf<String>()
        try {
            tools.forEach { registered ->
                val arguments = fixed[registered.qualifiedName]?.let { JsonObject(arguments(registered.tool.inputSchema) + it) } ?: arguments(registered.tool.inputSchema)
                val before = requests.size
                connect(company).use { tenant ->
                    tenant.autoCommit = false
                    tenant.run("SET TRANSACTION READ ONLY")
                    val failure = runCatching { registered.tool.handler(McpToolCall(arguments, permissions, tenant, access, "prueba", registered.moduleId)) }.exceptionOrNull()
                    runCatching { tenant.rollback() }
                    if (requests.size > before || networking(failure)) networkers += registered.qualifiedName
                    if (failure != null && failure !is SQLException) refusing += registered.qualifiedName
                    if (writes(failure)) {
                        // Run it again in an ordinary transaction (rolled back) to see what it wrote: the only write a
                        // read may make is its own activity-log row.
                        tenant.run("SET TRANSACTION READ WRITE")
                        runCatching { registered.tool.handler(McpToolCall(arguments, permissions, tenant, access, "prueba", registered.moduleId)) }
                        val written = tenant.createStatement().use { st ->
                            st.executeQuery("SELECT relname FROM pg_stat_xact_user_tables WHERE n_tup_ins + n_tup_upd + n_tup_del > 0").use { rows ->
                                buildSet { while (rows.next()) add(rows.getString(1)) }
                            }
                        }
                        runCatching { tenant.rollback() }
                        val other = written - setOf("activity_log", "activity_log_chain")
                        if (other.isNotEmpty() || written.isEmpty()) writers += "${registered.qualifiedName} $written" else loggedWriters += registered.qualifiedName
                    } else if (failure is SQLException) {
                        writers += "${registered.qualifiedName} failed: ${failure.message?.take(120)}"
                    }
                }
            }
        } finally {
            java.net.ProxySelector.setDefault(previousSelector)
        }
        assertTrue(writers.isEmpty(), "read-only tools that write or fail in SQL: $writers")
        // The intentional writes are exactly the listed ones: no other read leaves a row, and the listed ones do.
        assertEquals(loggedReads, loggedWriters, "reads that leave an activity-log row")
        // Network: only the listed providers, and they are real read-only tools.
        assertTrue(networkers.all { it in externalReads }, "read-only tools that reach the network and are not in externalReads: ${networkers - externalReads.keys}")
        // Tools of the extensions (monday, inmobiliaria) exist only where the extensions are on the classpath (local helper, not the CI server job).
        fun fromExtension(name: String) = "monday" in name || "inmobiliaria" in name
        externalReads.keys.filter { RealToolRegistry.extensionsComplete || !fromExtension(it) }
            .forEach { name -> assertTrue(tools.any { it.qualifiedName == name }, "$name is not a read-only tool of the registry") }
        // Everything else got past the gate: a tool that still refuses must say why in `refuses`.
        assertEquals(refuses.keys.filter { RealToolRegistry.extensionsComplete || !fromExtension(it) }.sorted(), refusing.sorted(), "tools that refuse under the seed (list them in refuses with the reason, or seed what they need)")
        // The check sees writes: a tool that does write (it is not declared read only) is caught the same way.
        val writer = all.first { it.qualifiedName == "hotspot_my_wifi_code" }
        connect(company).use { tenant ->
            tenant.autoCommit = false
            tenant.run("SET TRANSACTION READ ONLY")
            val failure = runCatching { writer.tool.handler(McpToolCall(JsonObject(emptyMap()), permissions, tenant, access, "prueba", writer.moduleId)) }.exceptionOrNull()
            assertTrue(writes(failure), "my_wifi_code should be refused in a read-only transaction: $failure")
        }
    }

    @Test
    fun theNetworkDetectorSeesAFreshJdkHttpClient() {
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = java.net.ProxySelector.getDefault()
        java.net.ProxySelector.setDefault(object : java.net.ProxySelector() {
            override fun select(uri: java.net.URI?): MutableList<java.net.Proxy> {
                // Plain sockets (the database) ask too, as socket://host:port: only http(s) requests are HTTP.
                if (uri?.scheme?.startsWith("http") == true) requests += uri.toString()
                return mutableListOf(java.net.Proxy.NO_PROXY)
            }
            override fun connectFailed(uri: java.net.URI?, address: java.net.SocketAddress?, failure: java.io.IOException?) = Unit
        })
        try {
            val failure = runCatching {
                java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:9/x")).build(), java.net.http.HttpResponse.BodyHandlers.discarding())
            }.exceptionOrNull()
            assertTrue(requests.any { it.contains("127.0.0.1") } || networking(failure), "requests: $requests failure: $failure")
        } finally {
            java.net.ProxySelector.setDefault(previous)
        }
    }

    @Test
    fun noNewSourceFileUsesAnHttpClientWithoutBeingReviewed() {
        val sources = listOf("modules", "extensions", "server/src/main").flatMap { directory ->
            File(root, directory).walkTopDown().filter { it.isFile && it.extension == "kt" && "/build/" !in it.path && "/src/test/" !in it.path }.toList()
        }
        val users = sources.filter { file -> file.readText().let { Regex("java\\.net\\.http|HttpURLConnection|CrmJavaHttp|okhttp").containsMatchIn(it) } }.map { it.name }.toSet()
        assertTrue((users - knownHttpFiles).isEmpty(), "new source files that use an HTTP client (review whether a read-only tool reaches them, then add them to knownHttpFiles): ${users - knownHttpFiles}")
    }
}
