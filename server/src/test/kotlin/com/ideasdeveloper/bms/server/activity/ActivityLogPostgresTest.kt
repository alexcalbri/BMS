package com.ideasdeveloper.bms.server.activity

import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerActorKind
import com.ideasdeveloper.bms.server.admins.ServerPermission
import com.ideasdeveloper.bms.server.admins.ServerPermissionException
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.security.ApplicationPasswordHasher
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The activity log on a real PostgreSQL (ACTIVITY_LOG.md §5): append-only enforcement for the
 * runtime role (privileges and trigger), the startup catch-up that brings tenant V011 to an existing
 * company, sign-in rows, the reader's filters and the server's merged read limited to the scope.
 * Runs only with BMS_ACTIVITY_PG_URL (or BMS_SERVER_ADMINS_PG_URL), jdbc:postgresql://host:port/postgres?user=postgres
 * of a DISPOSABLE server: it creates and drops bms_al_* databases and the role bms_al_runtime.
 */
class ActivityLogPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_al_central"
    private val companies = listOf("acme", "otra")
    private val opened = mutableListOf<LoginDatabases>()
    private val ownerId = UUID.randomUUID()

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private fun urlOf(database: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$database$1")
    private fun connect(database: String): Connection = DriverManager.getConnection(urlOf(database))
    private fun source(database: String) = PGSimpleDataSource().apply { setURL(urlOf(database)) }
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }
    private val prefix get() = urlOf("X").substringBefore("/X") + "/"
    private val pgUser get() = Regex("user=([^&]+)").find(url!!)?.groupValues?.get(1) ?: "postgres"

    @AfterTest
    fun close() = opened.forEach { it.close() }

    /** Central database and two companies WITHOUT tenant V011, as an existing server before Core 0.12.0. */
    private fun reset() {
        DriverManager.getConnection(url!!).use { admin ->
            (listOf(central) + companies.map { "bms_al_$it" }).forEach { name ->
                admin.run("DROP DATABASE IF EXISTS $name WITH (FORCE)")
            }
            admin.run("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'al_owner') THEN CREATE ROLE al_owner; END IF; END $$")
            admin.run("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'bms_al_runtime') THEN CREATE ROLE bms_al_runtime LOGIN PASSWORD 'runtime-pass'; END IF; END $$")
            // As on a server: the runtime role runs privileged work with SET ROLE to the owner's role.
            admin.run("GRANT al_owner TO bms_al_runtime")
            (listOf(central) + companies.map { "bms_al_$it" }).forEach { name -> admin.run("CREATE DATABASE $name OWNER al_owner") }
        }
        connect(central).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            File(root, "database/control/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                .forEach { db.run(it.readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', 'al_owner', TRUE, 'Duena', 'duena@example.com')")
            db.run("INSERT INTO server_owners (user_id) VALUES ('$ownerId')")
            companies.forEach { code -> db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), '$code', 'Empresa $code', 'bms_al_$code', gen_random_uuid())") }
        }
        companies.forEach { code ->
            connect("bms_al_$code").use { db ->
                listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code")
                    .forEach { db.run(migration("database/core/migrations/$it.sql")) }
                listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials")
                    .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
                val owner = UUID.randomUUID()
                db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$owner', '${code}_owner', TRUE, 'Dueno $code', '$code@example.com')")
                db.run("INSERT INTO business_owner (user_id) VALUES ('$owner')")
                val hash = ApplicationPasswordHasher.hash("clave-de-eva-123")
                val eva = UUID.randomUUID()
                db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$eva', 'eva_$code', TRUE, 'Eva $code', 'eva@$code.com')")
                db.run("INSERT INTO application_user_credentials (user_id, password_salt, password_hash, iterations) VALUES ('$eva', '${hash.salt}', '${hash.hash}', ${hash.iterations})")
            }
        }
    }

    private fun loginDatabases(): LoginDatabases {
        val data = source(central)
        return LoginDatabases(data, Database.connect(data), urlOf(central), "[]", pgUser, "", prefix, 3600).also { opened += it }
    }

    private fun provisioning(databases: LoginDatabases) = CompanyProvisioningService(
        central = source(central),
        config = CompanyProvisioningConfig(urlOf("postgres").substringBefore("?"), "bms_al_runtime", "runtime-pass", prefix, root.path),
        loginDatabases = databases,
        moduleRegistry = ModuleRegistry(emptyList()),
        remoteModuleCatalog = RemoteModuleCatalog(),
        defaultCatalogUrl = "",
        modulePackagesRoot = java.nio.file.Files.createTempDirectory("al-modules"),
        modulePackageBuilder = null,
        requestRestart = {},
    )

    private fun scalar(database: String, sql: String): String? = connect(database).use { db ->
        db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
    }

    private fun runtime(database: String): Connection =
        DriverManager.getConnection(urlOf(database).substringBefore("?") + "?user=bms_al_runtime&password=runtime-pass")

    @Test
    fun catchUpBringsV011AndTheLogIsAppendOnlyForTheRuntimeRole() {
        if (url == null) return
        reset()
        val databases = loginDatabases()
        // Startup catch-up on existing companies, twice (idempotent).
        provisioning(databases).applyPendingModuleMigrations()
        provisioning(databases).applyPendingModuleMigrations()
        companies.forEach { code ->
            assertEquals("t", scalar("bms_al_$code", "SELECT to_regclass('activity_log') IS NOT NULL"))
            assertEquals("1", scalar("bms_al_$code", "SELECT count(*) FROM schema_migrations WHERE version = 'V011__create_activity_log.sql' OR version LIKE 'V011%'"))
        }
        // The runtime role as update.sh leaves it: only SELECT and INSERT (plus the id sequence).
        connect("bms_al_acme").use { db ->
            db.run("GRANT USAGE ON SCHEMA public TO bms_al_runtime")
            db.run("GRANT SELECT ON application_users TO bms_al_runtime")
            db.run("REVOKE ALL ON activity_log FROM bms_al_runtime")
            db.run("GRANT SELECT, INSERT ON activity_log TO bms_al_runtime")
            db.run("GRANT USAGE, SELECT ON SEQUENCE activity_log_id_seq TO bms_al_runtime")
        }
        val actor = ActivityActor.user(UUID.randomUUID(), "Ana Ruiz").viaAgent("Claude")
        runtime("bms_al_acme").use { db ->
            db.autoCommit = false
            val id = ActivityLog.record(db, actor, ActivityEntry("crm", "lead", "7", "Lead 7", ActivityActions.Updated, "Modifico el lead.",
                listOf(ActivityChange("email", "Correo", "a@x", "b@x"), ActivityChange.secret("token", "Token"))))
            db.commit()
            assertTrue(id > 0)
            listOf("UPDATE activity_log SET summary = 'x'", "DELETE FROM activity_log", "TRUNCATE activity_log").forEach { sql ->
                val refused = assertFailsWith<SQLException>(sql) { db.run(sql) }
                assertEquals("42501", refused.sqlState, sql)
                db.rollback()
            }
        }
        // Even a role given every privilege by mistake (or the table owner) is stopped by the trigger.
        connect("bms_al_acme").use { db -> db.run("GRANT ALL ON activity_log TO bms_al_runtime") }
        runtime("bms_al_acme").use { db ->
            listOf("UPDATE activity_log SET summary = 'x'", "DELETE FROM activity_log", "TRUNCATE activity_log").forEach { sql ->
                val refused = assertFailsWith<SQLException>(sql) { db.run(sql) }
                assertTrue(refused.message.orEmpty().contains("append-only"), refused.message)
            }
        }
        connect("bms_al_acme").use { db ->
            assertFailsWith<SQLException> { db.run("DELETE FROM activity_log") }
        }
        // The startup catch-up takes the extra privileges away again.
        provisioning(databases).applyPendingModuleMigrations()
        // (Direct grants: the runtime role is also a member of the owner's role, as on a server, so the trigger is what stops it.)
        assertEquals(
            "INSERT,SELECT",
            scalar("bms_al_acme", "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE grantee = 'bms_al_runtime' AND table_name = 'activity_log'"),
        )
        // The row survived everything, with its secret masked.
        assertEquals("1", scalar("bms_al_acme", "SELECT count(*) FROM activity_log WHERE summary = 'Modifico el lead.'"))
        assertEquals("Agente de Ana Ruiz", scalar("bms_al_acme", "SELECT actor_display FROM activity_log WHERE module_id = 'crm'"))
        assertEquals("true", scalar("bms_al_acme", "SELECT changes->1->>'secret' FROM activity_log WHERE module_id = 'crm'"))
        assertNull(scalar("bms_al_acme", "SELECT changes->1->>'new' FROM activity_log WHERE module_id = 'crm'"))
    }

    @Test
    fun signInsAreLoggedFailuresCoalescedAndReadsStayInScope() {
        if (url == null) return
        reset()
        val databases = loginDatabases()
        provisioning(databases).applyPendingModuleMigrations()
        val ok = databases.service.login(LoginCredentials("eva_acme", "clave-de-eva-123", "acme").withClientAddress("10.1.1.1"))
        assertTrue(ok != null)
        repeat(5) { databases.service.login(LoginCredentials("eva_acme", "mala", "acme").withClientAddress("10.1.1.1")) }
        databases.service.login(LoginCredentials("nadie", "mala", "acme").withClientAddress("10.1.1.2"))
        assertEquals("1", scalar("bms_al_acme", "SELECT count(*) FROM activity_log WHERE action = 'signed_in' AND actor_name = 'Eva acme'"))
        // Five failures of one login and address: one row now; the other four wait for the window.
        assertEquals("1", scalar("bms_al_acme", "SELECT count(*) FROM activity_log WHERE action = 'sign_in_failed' AND entity_label = 'eva_acme'"))
        assertEquals("system", scalar("bms_al_acme", "SELECT actor_kind FROM activity_log WHERE action = 'sign_in_failed' AND entity_label = 'nadie'"))
        assertEquals("0", scalar("bms_al_acme", "SELECT count(*) FROM activity_log WHERE summary LIKE '%mala%'"))
        databases.logout("acme", ok!!.accessToken)
        assertEquals("1", scalar("bms_al_acme", "SELECT count(*) FROM activity_log WHERE action = 'signed_out'"))
        // A sign-in into the other company, to check the merged read.
        databases.service.login(LoginCredentials("eva_otra", "clave-de-eva-123", "otra").withClientAddress("10.1.1.1"))

        connect("bms_al_acme").use { db ->
            val byName = ActivityLogReader.page(db, ActivityFilter(user = "eva_acme"), { it })
            assertTrue(byName.items.any { it.action == "signed_in" }, byName.items.toString())
            assertEquals(1, ActivityLogReader.page(db, ActivityFilter(action = "signed_out"), { it }).items.size)
            assertEquals(0, ActivityLogReader.page(db, ActivityFilter(text = "no existe nada asi"), { it }).items.size)
            val first = ActivityLogReader.page(db, ActivityFilter(pageSize = 1), { it })
            assertEquals(1, first.items.size)
            assertTrue(first.hasMore)
        }
        val activity = ServerActivity(source(central), { name -> source(name).connection }, { it })
        val owner = ServerActor(ownerId, "al_owner", ServerActorKind.Owner, emptySet(), true, emptySet(), "al_owner")
        val merged = activity.page(owner, null, ActivityFilter(action = "signed_in"))
        assertEquals(setOf("acme", "otra"), merged.items.map { it.companyCode }.toSet())
        val scoped = ServerActor(UUID.randomUUID(), "ana", ServerActorKind.Admin, setOf(ServerPermission.AuditView), false, setOf("otra"), "al_owner")
        val mine = activity.page(scoped, null, ActivityFilter())
        assertTrue(mine.items.isNotEmpty())
        assertTrue(mine.items.all { it.companyCode == "otra" }, mine.items.map { it.companyCode }.toString())
        assertFailsWith<ServerPermissionException> { activity.page(scoped, "acme", ActivityFilter()) }
        assertFailsWith<ServerPermissionException> { activity.page(scoped, "servidor", ActivityFilter()) }
        val noPermission = ServerActor(UUID.randomUUID(), "leo", ServerActorKind.Admin, emptySet(), true, emptySet(), "al_owner")
        assertFailsWith<ServerPermissionException> { activity.page(noPermission, null, ActivityFilter()) }
        val csv = activity.export(owner, "acme", ActivityFilter())
        assertTrue(csv.contains("Inicio de sesion"), csv)
        assertEquals("1", scalar(central, "SELECT count(*) FROM provisioning_audit_log WHERE action = 'server_activity.exported'"))
    }
}
