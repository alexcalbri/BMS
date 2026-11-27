package com.ideasdeveloper.bms.server.extensions

import com.ideasdeveloper.bms.server.activity.ActivityIntegrityService
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.api.CreateCompanyRequest
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningConfig
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.bms.server.security.MasterKeySecretBox
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A packaged extension updated to a version with a new migration while companies have it enabled (the incident of the
 * *_monday packages: the new code selected created_by_user, which no company had). Updating the package only replaces
 * the code; at the next start ExtensionService.syncSchemas applies the new migration in every company through the
 * provisioning login and records the version, and until then the gate treats the row as incompatible. A company where
 * the migration fails gets the extension suspended (status incompatible, reason kept, owner told once) while the others
 * keep working; the next sync or enabling it again retries and gives the previous status back. Runs only with
 * BMS_TEST_PG_URL (or BMS_ACTIVITY_PG_URL) of a DISPOSABLE server: bms_seca<random>_* databases and seca<random>_* roles.
 */
class ExtensionSchemaSyncPostgresTest {
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
    private fun scalar(database: String, sql: String): String? =
        superuser(database).use { db -> db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } } }
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

    /** Roles and control database as install.sh creates them (runtime role is not the owner; separate provisioning login). */
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

    private val registry = ModuleRegistry(
        listOf(com.ideasdeveloper.bms.modules.clientes.ClientesServerModule, com.ideasdeveloper.bms.modules.tareas.TareasServerModule),
        installedModuleIds = setOf("clientes", "tareas"),
    )

    private fun provisioning(): CompanyProvisioningService {
        val data = runtimeSource(central)
        val databases = LoginDatabases(data, Database.connect(data), urlOf(central).substringBefore("?"), "[]", runtimeRole, "runtime-pass", prefix, 3600).also { opened += it }
        return CompanyProvisioningService(
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
            activityIntegrity = ActivityIntegrityService(runtimeSource(central), { database -> runtime(database) }),
        )
    }

    /** The package of the extension at [version], with [migrations] of extension-schema-sync/ (test resources). */
    private fun extensionAt(version: String, vararg migrations: String) = object : ServerExtension {
        override val manifest = ExtensionManifest(id = "secasync", module = "tareas", displayName = "Sync", version = version)
        override val migrationPaths = migrations.map { "extension-schema-sync/$it.sql" }
        override val runtimeTables = listOf("ext_secasync__jobs")
    }

    private val notices = mutableListOf<String>()
    private val audits = mutableListOf<String>()

    /** A server start with [extension] loaded. */
    private fun service(provisioning: CompanyProvisioningService, extension: ServerExtension) = ExtensionService(
        connections = object : ExtensionConnections {
            override fun central(): Connection = runtime(central)
            override fun tenant(companyCode: String): Connection = runtime("bms_$companyCode")
        },
        storage = ExtensionStorage(MasterKeySecretBox(null)),
        registry = ExtensionRegistry(listOf(extension)),
        modules = registry,
        notifier = ExtensionNotifier { _, _, _, title, text -> notices += "$title|$text" },
        auditor = { _, action, company, _, _ -> audits += "$action:$company" },
        migrationRunner = { company, ext -> provisioning.applyExtensionMigrations(company, ext) },
    )

    private fun row(company: String): Map<String, String?> = superuser("bms_$company").use { db ->
        db.createStatement().use { st ->
            st.executeQuery("SELECT version, status, schema_error, status_before_error FROM tenant_extensions WHERE extension_id = 'secasync'").use { rows ->
                assertTrue(rows.next())
                mapOf("version" to rows.getString(1), "status" to rows.getString(2), "error" to rows.getString(3), "before" to rows.getString(4))
            }
        }
    }

    /** What the import job of the new version reads every minute (it selects the new column). */
    private fun pendingJobs(company: String): Int = runtime("bms_$company").use { db ->
        db.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM ext_secasync__jobs WHERE status = 'reading' AND created_by_user IS NULL").use { it.next(); it.getInt(1) } }
    }

    @Test
    fun anUpdatedPackageGetsItsMigrationsInEveryCompanyAndAFailingCompanyIsSuspended() {
        if (url == null) return
        reset()
        val provisioning = provisioning()
        val actor = AuditActor.owner(owner, ownerId)
        val good = "${tag}_buena"
        val bad = "${tag}_mala"
        for (company in listOf(good, bad)) {
            provisioning.createCompany(CreateCompanyRequest(company, company, "${company}_dueno", "clave-del-dueno-123", "Dueno", "dueno@$company.example"), owner, actor)
            provisioning.setModuleEnabled(company, "tareas", true, owner, actor)
        }
        // Tenant V017 is part of every new company.
        assertEquals("1", scalar("bms_$good", "SELECT count(*) FROM schema_migrations WHERE module = 'tenant' AND version = 'V017__extension_schema_state'"))

        // Version 1.0.0 installed and enabled in both companies, with a job waiting.
        val v1 = extensionAt("1.0.0", "V001__jobs")
        val before = service(provisioning, v1)
        val extensionActor = ExtensionActor(owner, serverOwner = true)
        for (company in listOf(good, bad)) {
            before.install(company, "secasync", extensionActor)
            before.enable(company, "secasync", extensionActor)
            runtime("bms_$company").use { it.run("INSERT INTO ext_secasync__jobs DEFAULT VALUES") }
        }
        assertEquals("enabled", row(good)["status"])
        // In one company the new migration cannot apply (the column it adds is already there, with another type).
        superuser("bms_$bad").use { it.run("SET ROLE $owner; ALTER TABLE ext_secasync__jobs ADD COLUMN created_by_user TEXT; RESET ROLE") }

        // The package is updated to 1.1.0 (new migration) and the server restarts with the new code.
        val v2 = extensionAt("1.1.0", "V001__jobs", "V002__job_starter")
        val after = service(provisioning, v2)
        // Before the startup sync the new code is not let in: the row is at 1.0.0.
        runtime("bms_$good").use { db -> assertEquals(ExtensionStatus.Incompatible, ExtensionStorage(MasterKeySecretBox(null)).activeStatus(db, "secasync", "1.1.0")) }
        assertFailsWith<SQLException> { pendingJobs(good) }

        val report = after.syncSchemas()
        assertEquals(1, report.updated)
        assertEquals(1, report.suspended)
        // The good company is current: migration recorded, version recorded, still enabled, and the import query works.
        assertEquals(mapOf("version" to "1.1.0", "status" to "enabled", "error" to null, "before" to null), row(good))
        assertEquals("1", scalar("bms_$good", "SELECT count(*) FROM schema_migrations WHERE module = 'ext:secasync' AND version = 'V002__job_starter'"))
        assertEquals(1, pendingJobs(good))
        runtime("bms_$good").use { db -> assertEquals(ExtensionStatus.Enabled, ExtensionStorage(MasterKeySecretBox(null)).activeStatus(db, "secasync", "1.1.0")) }
        // The failing company is suspended with the reason, its previous status kept, the owner told once.
        val suspended = row(bad)
        assertEquals("1.0.0", suspended["version"])
        assertEquals("incompatible", suspended["status"])
        assertEquals("enabled", suspended["before"])
        assertNotNull(suspended["error"])
        assertEquals(1, notices.size)
        assertTrue(notices.single().startsWith("Extension suspendida"), notices.toString())
        assertEquals(listOf("extension.schema_updated:$good", "extension.suspended:$bad"), audits.filter { it.startsWith("extension.s") })

        // A restart (or the hourly retry) while it still fails: no new notice, audit or change; the good company is untouched.
        val again = service(provisioning, v2).syncSchemas()
        assertEquals(0, again.updated)
        assertEquals(1, again.suspended)
        assertEquals(1, notices.size)
        assertEquals(1, audits.count { it == "extension.suspended:$bad" })
        assertEquals("1.1.0", row(good)["version"])

        // Once the cause is fixed, enabling it again applies the migration and gives the previous status back.
        superuser("bms_$bad").use { it.run("SET ROLE $owner; ALTER TABLE ext_secasync__jobs DROP COLUMN created_by_user; RESET ROLE") }
        service(provisioning, v2).enable(bad, "secasync", extensionActor)
        assertEquals(mapOf("version" to "1.1.0", "status" to "enabled", "error" to null, "before" to null), row(bad))
        assertEquals(1, pendingJobs(bad))

        // Every company is current: the next start does nothing.
        val idle = service(provisioning, v2).syncSchemas()
        assertEquals(ExtensionService.SchemaSyncReport(0, 0, emptyList()), idle)
    }

    @Test
    fun aSuspendedExtensionComesBackAtTheNextSyncOnceItsMigrationApplies() {
        if (url == null) return
        reset()
        val provisioning = provisioning()
        val actor = AuditActor.owner(owner, ownerId)
        val company = "${tag}_unica"
        provisioning.createCompany(CreateCompanyRequest(company, company, "${company}_dueno", "clave-del-dueno-123", "Dueno", "dueno@$company.example"), owner, actor)
        provisioning.setModuleEnabled(company, "tareas", true, owner, actor)
        val extensionActor = ExtensionActor(owner, serverOwner = true)
        service(provisioning, extensionAt("1.0.0", "V001__jobs")).also {
            it.install(company, "secasync", extensionActor)
            // Installed but left disabled by the owner: the sync brings it back to disabled, not enabled.
            it.disable(company, "secasync", extensionActor)
        }
        superuser("bms_$company").use { it.run("SET ROLE $owner; ALTER TABLE ext_secasync__jobs ADD COLUMN created_by_user TEXT; RESET ROLE") }
        val v2 = extensionAt("1.1.0", "V001__jobs", "V002__job_starter")
        assertEquals(1, service(provisioning, v2).syncSchemas().suspended)
        assertEquals("disabled", row(company)["before"])
        superuser("bms_$company").use { it.run("SET ROLE $owner; ALTER TABLE ext_secasync__jobs DROP COLUMN created_by_user; RESET ROLE") }
        assertEquals(1, service(provisioning, v2).syncSchemas().updated)
        assertEquals(mapOf("version" to "1.1.0", "status" to "disabled", "error" to null, "before" to null), row(company))
        assertNull(row(company)["error"])
    }
}
