package com.ideasdeveloper.bms.server.company

import com.ideasdeveloper.bms.server.activity.ActivityIntegrityService
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.api.CreateCompanyRequest
import com.ideasdeveloper.bms.server.company.api.UpdateCompanyRequest
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningConfig
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.RemoteModuleCatalog
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The PostgreSQL roles as install.sh and update.sh now leave them (SELF_HOSTING.md, Roles de PostgreSQL): the runtime
 * role of the pool is a plain role (no membership, no CREATEDB/CREATEROLE, owns nothing); provisioning connects as a
 * separate NOINHERIT login that may SET ROLE to the server owner's role (and to the runtime role). Creating,
 * changing, duplicating, catching up and deleting companies keep working, and the runtime role can use every company
 * table but cannot drop, alter or disable anything of the activity log. Runs only with BMS_TEST_PG_URL (or
 * BMS_ACTIVITY_PG_URL) of a DISPOSABLE server: it creates and drops bms_seca<random>_* databases and seca<random>_* roles
 * of its own (a new prefix per test, so concurrent runs and the script tests never touch each other's objects).
 */
class ProvisioningRolesPostgresTest {
    // Unique per test instance: every database and role name of this test starts with it.
    private val tag = "seca" + (1..6).map { ('a'..'z').random() }.joinToString("")
    private val url: String? = (System.getenv("BMS_TEST_PG_URL") ?: System.getenv("BMS_ACTIVITY_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_${tag}_central"
    private val owner = "${tag}_owner"
    private val runtimeRole = "${tag}_runtime"
    private val provisioner = "${tag}_provisioner"
    private val ownerId = UUID.randomUUID()
    private val opened = mutableListOf<LoginDatabases>()

    private fun urlOf(database: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$database$1")
    private fun superuser(database: String): Connection = DriverManager.getConnection(urlOf(database))
    private fun runtime(database: String): Connection =
        DriverManager.getConnection(urlOf(database).substringBefore("?") + "?user=$runtimeRole&password=runtime-pass")
    private fun runtimeSource(database: String) = PGSimpleDataSource().apply {
        setURL(urlOf(database).substringBefore("?"))
        user = runtimeRole
        password = "runtime-pass"
    }
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }
    private fun scalar(connection: Connection, sql: String): String? =
        connection.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
    private fun scalar(database: String, sql: String) = superuser(database).use { scalar(it, sql) }
    private val prefix get() = urlOf("X").substringBefore("/X").substringBefore("?") + "/"
    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")

    private fun dropEverything() {
        superuser("postgres").use { admin ->
            admin.run("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname LIKE 'bms\\_${tag}\\_%' AND pid <> pg_backend_pid()")
            val databases = admin.createStatement().use { st -> st.executeQuery("SELECT datname FROM pg_database WHERE datname LIKE 'bms\\_${tag}\\_%'").use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
            databases.forEach { admin.run("DROP DATABASE IF EXISTS \"$it\" WITH (FORCE)") }
            val roles = admin.createStatement().use { st -> st.executeQuery("SELECT rolname FROM pg_roles WHERE rolname LIKE '${tag}\\_%'").use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
            roles.forEach { admin.run("DROP OWNED BY \"$it\" CASCADE") }
            roles.forEach { admin.run("DROP ROLE IF EXISTS \"$it\"") }
        }
    }

    @AfterTest
    fun close() {
        opened.forEach { it.close() }
        if (url != null) dropEverything()
    }

    /** Roles and control database as install.sh now creates them. */
    private fun reset() {
        dropEverything()
        superuser("postgres").use { admin ->
            admin.run("CREATE ROLE $owner LOGIN NOSUPERUSER CREATEDB CREATEROLE PASSWORD 'owner-pass'")
            admin.run("CREATE ROLE $runtimeRole LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD 'runtime-pass'")
            admin.run("CREATE ROLE $provisioner LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD 'provisioner-pass'")
            admin.run("GRANT $owner TO $provisioner WITH INHERIT FALSE, SET TRUE")
            admin.run("GRANT $runtimeRole TO $provisioner WITH INHERIT FALSE, SET TRUE")
            admin.run("CREATE DATABASE $central")
            admin.run("REVOKE ALL ON DATABASE $central FROM PUBLIC")
            admin.run("GRANT CONNECT ON DATABASE $central TO $runtimeRole, $owner, $provisioner")
        }
        superuser(central).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            File(root, "database/control/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                .forEach { db.run(it.readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', '$owner', TRUE, 'Duena', 'duena@example.com')")
            db.run("INSERT INTO server_owners (user_id) VALUES ('$ownerId')")
            db.run("GRANT USAGE ON SCHEMA public TO $runtimeRole")
            db.run("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $runtimeRole")
            db.run("REVOKE UPDATE, DELETE, TRUNCATE ON provisioning_audit_log FROM $runtimeRole")
            db.run("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO $runtimeRole")
        }
    }

    private fun loginDatabases(): LoginDatabases {
        val data = runtimeSource(central)
        return LoginDatabases(data, Database.connect(data), urlOf(central).substringBefore("?"), "[]", runtimeRole, "runtime-pass", prefix, 3600).also { opened += it }
    }

    private fun provisioning(
        databases: LoginDatabases,
        integrity: ActivityIntegrityService,
        registry: ModuleRegistry = ModuleRegistry(listOf(com.ideasdeveloper.bms.modules.clientes.ClientesServerModule)),
    ) = CompanyProvisioningService(
        central = runtimeSource(central),
        config = CompanyProvisioningConfig(
            urlOf("postgres").substringBefore("?"), runtimeRole, "runtime-pass", prefix, root.path,
            provisioningUser = provisioner, provisioningPassword = "provisioner-pass",
        ),
        loginDatabases = databases,
        moduleRegistry = registry,
        remoteModuleCatalog = RemoteModuleCatalog(),
        defaultCatalogUrl = "",
        modulePackagesRoot = java.nio.file.Files.createTempDirectory("seca-modules"),
        modulePackageBuilder = null,
        requestRestart = {},
        activityIntegrity = integrity,
    )

    private fun assertRuntimeIsPlain(database: String) {
        assertEquals("f", scalar(database, "SELECT pg_has_role('$runtimeRole', '$owner', 'MEMBER')"))
        assertEquals("0", scalar(database, "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND pg_get_userbyid(c.relowner) <> '$owner'"))
        runtime(database).use { db ->
            listOf(
                "ALTER TABLE activity_log DISABLE TRIGGER ALL",
                "DROP TRIGGER activity_log_no_change ON activity_log",
                "DROP TABLE activity_log_chain",
                "UPDATE activity_log SET summary = 'x'",
                "DELETE FROM activity_log_chain",
                "TRUNCATE customers",
                "CREATE TABLE intrusa (id INT)",
                "SET ROLE $owner",
                "CREATE DATABASE bms_${tag}_intrusa",
                "CREATE ROLE ${tag}_intrusa",
            ).forEach { sql -> assertFailsWith<SQLException>(sql) { db.run(sql) } }
            // Every ordinary table of the company stays usable.
            db.run("UPDATE customers SET status = status WHERE FALSE")
            db.run("DELETE FROM customers WHERE FALSE")
            assertEquals(
                "0",
                scalar(db, """
                    SELECT count(*) FROM pg_class c, (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')) p (privilege)
                    WHERE c.relnamespace = 'public'::regnamespace AND c.relkind = 'r'
                      AND c.relname NOT IN ('activity_log', 'activity_log_chain', 'schema_migrations', 'tenant_data_keys', 'application_user_2fa_email_recovery', 'assistant_messages')
                      AND NOT has_table_privilege(c.oid, p.privilege)
                """.trimIndent()),
            )
            // The company's data key is only read and created (replacing or deleting it loses every sealed secret).
            assertEquals("INSERT,SELECT", scalar(db, "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE grantee = current_user AND table_name = 'tenant_data_keys'"))
            listOf("UPDATE tenant_data_keys SET key_version = key_version", "DELETE FROM tenant_data_keys")
                .forEach { sql -> assertFailsWith<SQLException>(sql) { db.run(sql) } }
            // Two-step verification of the company's users (tenant V012).
            db.run("DELETE FROM application_user_recovery_codes WHERE FALSE")
            db.run("UPDATE application_user_two_factor SET failed_attempts = 0 WHERE FALSE")
            // Delayed recovery by email (tenant V013): started, updated, never deleted by the runtime.
            assertEquals("INSERT,SELECT,UPDATE", scalar(db, "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE grantee = current_user AND table_name = 'application_user_2fa_email_recovery'"))
            assertFailsWith<SQLException> { db.run("DELETE FROM application_user_2fa_email_recovery") }
            // The assistant's messages (tenant V018) are only read and appended; they go with their conversation.
            assertEquals("INSERT,SELECT", scalar(db, "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE grantee = current_user AND table_name = 'assistant_messages'"))
            listOf("UPDATE assistant_messages SET role = role", "DELETE FROM assistant_messages").forEach { sql -> assertFailsWith<SQLException>(sql) { db.run(sql) } }
            db.run("DELETE FROM assistant_conversations WHERE FALSE")
            assertEquals("0", scalar(db, "SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind = 'S' AND NOT CASE WHEN relkind = 'S' THEN has_sequence_privilege(oid, 'USAGE') ELSE TRUE END"))
            assertTrue(scalar(db, "SELECT count(*) FROM schema_migrations")!!.toInt() > 5)
        }
    }

    @Test
    fun companiesAreProvisionedThroughTheSeparateLoginAndTheRuntimeRoleHoldsOnlyGrants() {
        if (url == null) return
        reset()
        val databases = loginDatabases()
        val integrity = ActivityIntegrityService(runtimeSource(central), { database -> runtime(database) })
        val provisioning = provisioning(databases, integrity)
        val actor = AuditActor.owner(owner, ownerId)
        provisioning.createCompany(
            CreateCompanyRequest("${tag}_acme", "Acme", "${tag}_dueno_acme", "clave-del-dueno-123", "Dueno Acme", "dueno@acme.example"), owner, actor,
        )
        assertEquals(owner, scalar("postgres", "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'bms_${tag}_acme'"))
        assertRuntimeIsPlain("bms_${tag}_acme")
        assertTrue(integrity.verify("${tag}_acme").let { it.ok }, integrity.verify("${tag}_acme").problems.toString())
        // A change from the server writes its row as the runtime role on the provisioning connection.
        provisioning.updateCompany("${tag}_acme", UpdateCompanyRequest("Acme SA", null, "#112233", "#445566", "#778899"), owner, actor)
        assertEquals("1", scalar("bms_${tag}_acme", "SELECT count(*) FROM activity_log WHERE summary = 'Cambio desde el servidor los datos de la empresa.'"))
        provisioning.resetBusinessOwnerPassword("${tag}_acme", "otra-clave-del-dueno-456", owner, actor)
        // The startup catch-up and a duplicate.
        provisioning.applyPendingModuleMigrations()
        assertEquals(
            "0",
            scalar("bms_${tag}_acme", "SELECT count(*) FROM information_schema.role_table_grants WHERE grantee = '$runtimeRole' AND table_name LIKE 'activity\\_log%' AND privilege_type NOT IN ('SELECT', 'INSERT')"),
        )
        provisioning.duplicateCompany("${tag}_acme", "${tag}_copia", owner, actor)
        assertRuntimeIsPlain("bms_${tag}_copia")
        val reports = integrity.verifyAll()
        assertTrue(reports.all { it.ok }, reports.flatMap { it.problems }.toString())
        // Deactivation and deletion.
        provisioning.setCompanyActive("${tag}_copia", false, owner, actor)
        // A runtime session still on the database (a pool, or one closed a moment ago) does not stop the deletion: the
        // owner's role may not end it, so it is ended as the runtime role.
        val stillOpen = runtime("bms_${tag}_copia")
        // A connection of another login (a reporting tool) that the owner's role is no member of: ending sessions one by
        // one skips it, DROP DATABASE ... WITH (FORCE) ends it (the owner holds pg_signal_backend for that).
        superuser("postgres").use { admin ->
            admin.run("DROP ROLE IF EXISTS ${tag}_visor")
            admin.run("CREATE ROLE ${tag}_visor LOGIN PASSWORD 'visor-pass'")
            admin.run("GRANT CONNECT ON DATABASE bms_${tag}_copia TO ${tag}_visor")
            admin.run("GRANT pg_signal_backend TO $owner")
        }
        val foreign = DriverManager.getConnection(urlOf("bms_${tag}_copia").substringBefore("?") + "?user=${tag}_visor&password=visor-pass")
        // And a pool that keeps reconnecting while the company is deleted.
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val pool = Thread {
            while (!stop.get()) runCatching { runtime("bms_${tag}_copia").use { it.run("SELECT pg_sleep(0.02)") } }
        }.apply { isDaemon = true; start() }
        Thread.sleep(200)
        try {
            provisioning.deleteInactiveCompany("${tag}_copia", owner, actor)
        } finally {
            stop.set(true)
            pool.join(5_000)
        }
        assertFailsWith<SQLException> { stillOpen.run("SELECT 1") }
        assertFailsWith<SQLException> { foreign.run("SELECT 1") }
        runCatching { stillOpen.close() }
        runCatching { foreign.close() }
        superuser("postgres").use { it.run("DROP ROLE IF EXISTS ${tag}_visor"); it.run("REVOKE pg_signal_backend FROM $owner") }
        assertEquals("0", scalar("postgres", "SELECT count(*) FROM pg_database WHERE datname = 'bms_${tag}_copia'"))
        // The pool's role still cannot create anything at server level.
        runtime(central).use { db ->
            assertFailsWith<SQLException> { db.run("CREATE DATABASE bms_${tag}_intrusa") }
            assertFailsWith<SQLException> { db.run("SET ROLE $owner") }
            assertFailsWith<SQLException> { db.run("DELETE FROM provisioning_audit_log") }
        }
    }

    /**
     * Module and extension migrations when the runtime role owns nothing: enabling an optional module, an extension's
     * migrations and the startup catch-up (tenant V012) all run through the provisioning login as the owner's role, so
     * every new table belongs to the owner and the runtime role only gets grants. A table an older update.sh left to
     * postgres does not stop the catch-up (update.sh moves those to the owner; the Core cannot).
     */
    @Test
    fun moduleAndExtensionMigrationsRunAsTheOwnerThroughTheProvisioningLogin() {
        if (url == null) return
        reset()
        val databases = loginDatabases()
        val integrity = ActivityIntegrityService(runtimeSource(central), { database -> runtime(database) })
        val registry = ModuleRegistry(
            listOf(
                com.ideasdeveloper.bms.modules.clientes.ClientesServerModule,
                com.ideasdeveloper.bms.modules.crm.CrmServerModule,
                com.ideasdeveloper.bms.modules.tareas.TareasServerModule,
            ),
            installedModuleIds = setOf("clientes", "crm", "tareas"),
        )
        val provisioning = provisioning(databases, integrity, registry)
        val actor = AuditActor.owner(owner, ownerId)
        provisioning.createCompany(
            CreateCompanyRequest("${tag}_mod", "Modulos", "${tag}_dueno_mod", "clave-del-dueno-123", "Dueno", "dueno@mod.example"), owner, actor,
        )
        val database = "bms_${tag}_mod"
        provisioning.setModuleEnabled("${tag}_mod", "tareas", true, owner, actor)
        provisioning.setModuleEnabled("${tag}_mod", "crm", true, owner, actor)
        // An extension package carries its migrations as resources of its own class loader (here, a test resource).
        val extension = object : com.ideasdeveloper.bms.server.extensions.ServerExtension {
            override val manifest = com.ideasdeveloper.bms.server.extensions.ExtensionManifest(id = "wordpress", module = "crm", displayName = "WordPress", version = "0.1.0")
            override val migrationPaths = listOf("provisioning-roles/V001__ext_items.sql")
            override val runtimeTables = listOf("ext_secatest__items")
        }
        provisioning.applyExtensionMigrations("${tag}_mod", extension)
        assertTrue(scalar(database, "SELECT count(*) FROM schema_migrations WHERE module = 'tareas'")!!.toInt() > 0)
        assertTrue(scalar(database, "SELECT count(*) FROM schema_migrations WHERE module = 'crm'")!!.toInt() > 0)
        assertEquals("1", scalar(database, "SELECT count(*) FROM schema_migrations WHERE module = 'ext:wordpress'"))
        assertRuntimeIsPlain(database)
        runtime(database).use { db ->
            db.run("SELECT count(*) FROM tareas_tasks")
            db.run("INSERT INTO ext_secatest__items (name) VALUES ('uno')")
            db.run("DELETE FROM crm_leads WHERE FALSE")
            listOf("ALTER TABLE tareas_tasks ADD COLUMN intrusa INT", "DROP TABLE crm_leads", "TRUNCATE tareas_tasks", "DROP TABLE ext_secatest__items")
                .forEach { sql -> assertFailsWith<SQLException>(sql) { db.run(sql) } }
        }

        // Startup catch-up on a company that lacks tenant V012, with a table an older update.sh created as postgres.
        superuser(database).use { db ->
            db.run("DROP TABLE application_user_recovery_codes, application_user_two_factor, application_user_2fa_email_recovery")
            db.run("ALTER TABLE application_sessions DROP COLUMN second_factor_at")
            db.run("DELETE FROM schema_migrations WHERE module = 'tenant' AND version IN ('V012__create_two_factor', 'V013__create_two_factor_email_recovery', 'V014__session_second_factor_mark')")
            db.run("CREATE TABLE ${tag}_de_postgres (id INT)")
        }
        provisioning.applyPendingModuleMigrations()
        assertEquals(owner, scalar(database, "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = 'application_user_two_factor'"))
        assertEquals(owner, scalar(database, "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = 'application_user_2fa_email_recovery'"))
        assertEquals("3", scalar(database, "SELECT count(*) FROM schema_migrations WHERE module = 'tenant' AND version IN ('V012__create_two_factor', 'V013__create_two_factor_email_recovery', 'V014__session_second_factor_mark')"))
        // The runtime role writes the new columns of tenant V014 through its table grants.
        runtime(database).use { db ->
            db.run("UPDATE application_sessions SET second_factor_at = CURRENT_TIMESTAMP WHERE FALSE")
            db.run("UPDATE application_user_2fa_email_recovery SET warning_mailed_at = CURRENT_TIMESTAMP, notice_base = 'x' WHERE FALSE")
        }
        superuser(database).use { it.run("DROP TABLE ${tag}_de_postgres") }
        assertRuntimeIsPlain(database)
    }
}
