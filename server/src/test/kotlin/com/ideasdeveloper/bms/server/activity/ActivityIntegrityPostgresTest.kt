package com.ideasdeveloper.bms.server.activity

import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.api.CreateCompanyRequest
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
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tamper evidence and hardening of the activity log on a real PostgreSQL (ACTIVITY_LOG.md §9), with the roles as
 * install.sh leaves them: the runtime role is a member of the server owner's role (CREATEDB CREATEROLE), which owns
 * every company database. Runs only with BMS_ACTIVITY_PG_URL (or BMS_SERVER_ADMINS_PG_URL) of a DISPOSABLE server:
 * it creates and drops bms_ai_* databases and roles, and creates (never drops) the role bms_audit.
 */
class ActivityIntegrityPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_ai_central"
    private val owner = "bms_ai_owner"
    private val runtimeRole = "bms_ai_runtime"
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

    @AfterTest
    fun close() = opened.forEach { it.close() }

    /** Roles and central database as install.sh leaves them (control tables owned by the superuser, runtime grants). */
    private fun reset(vararg companies: String) {
        superuser("postgres").use { admin ->
            admin.run("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname LIKE 'bms\\_ai\\_%' AND pid <> pg_backend_pid()")
            val databases = admin.createStatement().use { st -> st.executeQuery("SELECT datname FROM pg_database WHERE datname LIKE 'bms\\_ai\\_%'").use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
            databases.forEach { admin.run("DROP DATABASE IF EXISTS \"$it\" WITH (FORCE)") }
            val roles = admin.createStatement().use { st -> st.executeQuery("SELECT rolname FROM pg_roles WHERE rolname LIKE 'ai\\_dueno\\_%'").use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
            roles.forEach { admin.run("DROP ROLE IF EXISTS \"$it\"") }
            admin.run("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$owner') THEN CREATE ROLE $owner LOGIN NOSUPERUSER CREATEDB CREATEROLE PASSWORD 'owner-pass'; END IF; END $$")
            admin.run("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$runtimeRole') THEN CREATE ROLE $runtimeRole LOGIN PASSWORD 'runtime-pass'; END IF; END $$")
            admin.run("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'bms_audit') THEN CREATE ROLE bms_audit NOLOGIN NOINHERIT; END IF; END $$")
            admin.run("GRANT $owner TO $runtimeRole")
            admin.run("CREATE DATABASE $central")
            admin.run("REVOKE ALL ON DATABASE $central FROM PUBLIC")
            admin.run("GRANT CONNECT ON DATABASE $central TO $runtimeRole, $owner")
        }
        superuser(central).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            File(root, "database/control/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                .forEach { db.run(it.readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', '$owner', TRUE, 'Duena', 'duena@example.com')")
            db.run("INSERT INTO server_owners (user_id) VALUES ('$ownerId')")
            // install.sh: the runtime role may read and append the server audit, never change it.
            db.run("GRANT USAGE ON SCHEMA public TO $runtimeRole")
            db.run("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $runtimeRole")
            db.run("REVOKE UPDATE, DELETE, TRUNCATE ON provisioning_audit_log FROM $runtimeRole")
            db.run("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO $runtimeRole")
        }
        companies.forEach { code -> tenant(code) }
    }

    /** A company database as the server creates it at runtime: by the owner's role, V011 applied as that role. */
    private fun tenant(code: String) {
        runtime("postgres").use { rt ->
            rt.run("SET ROLE $owner")
            rt.run("CREATE DATABASE bms_ai_$code")
            rt.run("REVOKE CONNECT ON DATABASE bms_ai_$code FROM PUBLIC")
            rt.run("GRANT CONNECT ON DATABASE bms_ai_$code TO $runtimeRole")
        }
        runtime("bms_ai_$code").use { rt ->
            rt.run("SET ROLE $owner")
            rt.run("CREATE TABLE schema_migrations (module TEXT NOT NULL, version TEXT NOT NULL, checksum TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (module, version))")
            rt.run(migration("database/tenant/migrations/V011__create_activity_log.sql"))
            rt.run("INSERT INTO schema_migrations (module, version, checksum) VALUES ('tenant', 'V011__create_activity_log', 'x')")
            rt.run("GRANT SELECT, INSERT ON activity_log TO $runtimeRole")
            rt.run("GRANT USAGE, SELECT ON SEQUENCE activity_log_id_seq TO $runtimeRole")
        }
        superuser(central).use { db ->
            db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), '$code', 'Empresa $code', 'bms_ai_$code', gen_random_uuid())")
        }
    }

    private val notified = Collections.synchronizedList(mutableListOf<String>())
    private fun service() = ActivityIntegrityService(runtimeSource(central), { database -> runtime(database) }) { title, text -> notified += "$title: $text" }

    private fun write(database: String, count: Int, summary: String = "fila"): List<Long> = runtime(database).use { db ->
        (1..count).map { index ->
            ActivityLog.atomically(db) {
                ActivityLog.record(db, ActivityActor.user(UUID.randomUUID(), "Ana Ruiz"), ActivityEntry("crm", "lead", "$index", "Lead $index", ActivityActions.Updated, "$summary $index."))
            }
        }
    }

    /** Runs [sql] as the server owner's role through the runtime login, as a compromised runtime could. */
    private fun asOwner(database: String, vararg sql: String) = runtime(database).use { db ->
        db.run("SET ROLE $owner")
        sql.forEach { db.run(it) }
    }

    private fun harden(database: String) = superuser(database).use { db ->
        db.run("SELECT set_config('bms.runtime_user', '$runtimeRole', false)")
        db.run(File(root, "scripts/ubuntu/activity-harden.sql").readText())
    }

    @Test
    fun honestConcurrentWritersChainInCommitOrderWithoutHoldingALockForTheirWork() {
        if (url == null) return
        reset("acme")
        val writers = 8
        val perWriter = 8
        val workMillis = 60L
        val pool = Executors.newFixedThreadPool(writers)
        val started = System.nanoTime()
        val futures = (1..writers).map { writer ->
            pool.submit {
                runtime("bms_ai_acme").use { db ->
                    repeat(perWriter) { index ->
                        ActivityLog.atomically(db) {
                            ActivityLog.record(db, ActivityActor.user(UUID.randomUUID(), "Escritor $writer"), ActivityEntry("crm", "lead", "$writer-$index", "", ActivityActions.Created, "Fila $writer-$index."))
                            // The rest of the change: a lock held until commit would serialize all of this.
                            Thread.sleep(workMillis)
                        }
                    }
                }
            }
        }
        futures.forEach { it.get(2, TimeUnit.MINUTES) }
        pool.shutdown()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val serialized = writers * perWriter * workMillis
        assertTrue(elapsed < serialized / 2, "writers took $elapsed ms; one lock per transaction would take at least $serialized ms")
        val service = service()
        val report = service.verify("acme")
        assertTrue(report.ok, report.problems.toString())
        assertEquals((writers * perWriter).toLong(), report.rows)
        assertEquals(report.rows, report.lastSeq)
        assertFalse(report.hardened)
        assertTrue(report.notHardenedReasons.any { it.contains("bms_ai_owner") }, report.notHardenedReasons.toString())
        // Anchored, then still clean against that anchor.
        assertNotNull(service.anchor("acme", "hourly"))
        assertEquals(1, service.anchors("acme").size)
        val again = service.verify("acme")
        assertTrue(again.ok, again.problems.toString())
        assertEquals(1, again.anchorsChecked)
        assertTrue(notified.isEmpty(), notified.toString())
    }

    @Test
    fun aForgedUpdateOrDeleteAsTheOwnerRoleIsFoundWithTheFirstBadRow() {
        if (url == null) return
        reset("upd", "del", "tail", "func", "gone")
        val service = service()
        val updated = write("bms_ai_upd", 5)
        service.anchor("upd", "hourly")
        // The reviewer's path: the runtime inherits the owner's rights, disables the trigger and rewrites a row.
        asOwner(
            "bms_ai_upd", "ALTER TABLE activity_log DISABLE TRIGGER activity_log_no_change",
            "UPDATE activity_log SET summary = 'Nada que ver.' WHERE id = ${updated[2]}",
            "ALTER TABLE activity_log ENABLE TRIGGER activity_log_no_change",
        )
        val forged = service.verify("upd")
        assertFalse(forged.ok)
        assertEquals(updated[2], forged.firstBadId)
        assertTrue(forged.problems.any { it.contains("fue modificada") }, forged.problems.toString())
        // Written once to the server audit and sent to the owners; the same finding is not repeated.
        service.verify("upd")
        assertEquals("1", scalar(central, "SELECT count(*) FROM provisioning_audit_log WHERE action = 'activity.integrity_failed' AND company_code = 'upd'"))
        assertTrue(scalar(central, "SELECT details->>'firstBadId' FROM provisioning_audit_log WHERE action = 'activity.integrity_failed'") == updated[2].toString())
        assertEquals(1, notified.size)

        // A deleted row: the next one reports the gap. A trigger left disabled is reported too.
        val deleted = write("bms_ai_del", 5)
        asOwner("bms_ai_del", "ALTER TABLE activity_log DISABLE TRIGGER activity_log_no_change", "DELETE FROM activity_log WHERE id = ${deleted[1]}")
        val gap = service.verify("del")
        assertFalse(gap.ok)
        assertEquals(deleted[2], gap.firstBadId)
        assertTrue(gap.problems.any { it.contains("Faltan las filas 2 a 2") }, gap.problems.toString())
        assertTrue(gap.problems.any { it.contains("activity_log_no_change esta desactivado") }, gap.problems.toString())

        // The newest rows deleted (and the head rewound): the anchor still says how far the chain reached.
        val tail = write("bms_ai_tail", 4)
        service.anchor("tail", "hourly")
        asOwner(
            "bms_ai_tail", "ALTER TABLE activity_log DISABLE TRIGGER activity_log_no_change",
            "DELETE FROM activity_log WHERE id >= ${tail[2]}",
            "ALTER TABLE activity_log ENABLE TRIGGER activity_log_no_change",
            "UPDATE activity_log_chain_head SET last_seq = 2, last_hash = (SELECT row_hash FROM activity_log WHERE chain_seq = 2)",
        )
        val cut = service.verify("tail")
        assertFalse(cut.ok)
        assertTrue(cut.problems.any { it.contains("llegaba a la posicion 4") }, cut.problems.toString())

        // A replaced trigger function, then honest rows: the function is reported even though the rows chain.
        write("bms_ai_func", 2)
        asOwner("bms_ai_func", "CREATE OR REPLACE FUNCTION activity_log_append_only() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$ BEGIN RETURN COALESCE(NEW, OLD); END; $$")
        val replaced = service.verify("func")
        assertFalse(replaced.ok)
        assertTrue(replaced.problems.any { it.contains("activity_log_append_only fue cambiada") }, replaced.problems.toString())

        // The table dropped after V011 was applied: reported, and Core refuses to carry on without it.
        write("bms_ai_gone", 1)
        asOwner("bms_ai_gone", "DROP TABLE activity_log CASCADE")
        val missing = service.verify("gone")
        assertFalse(missing.ok)
        assertTrue(missing.problems.any { it.contains("Falta la tabla activity_log aunque la migracion V011") }, missing.problems.toString())
        val alarms = mutableListOf<String>()
        CoreActivity.integrityAlarm = { database, problem -> alarms += "$database: $problem" }
        try {
            runtime("bms_ai_gone").use { db ->
                db.autoCommit = false
                val refused = assertFailsWith<SQLException> { CoreActivity.record(db, ActivityActor.system(), "user", null, "x", ActivityActions.SignInFailed, "x") }
                assertEquals("42P01", refused.sqlState)
                db.rollback()
            }
            assertEquals(1, alarms.size, alarms.toString())
            assertTrue(alarms.single().startsWith("bms_ai_gone"))
        } finally {
            CoreActivity.integrityAlarm = { _, _ -> }
        }
        // Before V011 is applied (no history row) the old tolerance still holds.
        runtime("bms_ai_gone").use { db ->
            asOwner("bms_ai_gone", "DELETE FROM schema_migrations")
            db.autoCommit = false
            CoreActivity.record(db, ActivityActor.system(), "user", null, "x", ActivityActions.SignInFailed, "x")
            db.rollback()
        }
    }

    @Test
    fun theHardeningScriptTakesTheLogAwayFromTheRuntimeRoleAndTheAppKeepsWorking() {
        if (url == null) return
        reset("acme")
        val service = service()
        write("bms_ai_acme", 3)
        assertFalse(service.verify("acme").hardened)
        harden("bms_ai_acme")
        // Idempotent.
        harden("bms_ai_acme")
        val hardened = service.verify("acme")
        assertTrue(hardened.ok, hardened.problems.toString())
        assertTrue(hardened.hardened, hardened.notHardenedReasons.toString())
        service.anchor("acme", "hourly")

        // Everything the reviewer did, and more, as the runtime role and as the owner's role it can become.
        listOf(
            "DELETE FROM activity_log", "UPDATE activity_log SET summary = 'x'", "TRUNCATE activity_log",
            "ALTER TABLE activity_log DISABLE TRIGGER activity_log_no_change", "DROP TABLE activity_log CASCADE",
            "DROP SCHEMA public CASCADE", "ALTER SCHEMA public RENAME TO otro",
            "CREATE OR REPLACE FUNCTION activity_log_append_only() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$",
            "DROP FUNCTION activity_log_seal() CASCADE", "ALTER TABLE activity_log OWNER TO $owner", "GRANT bms_audit TO $runtimeRole",
            "UPDATE activity_log_chain_head SET last_seq = 0", "SELECT setval('activity_log_id_seq', 1)",
            "DROP EVENT TRIGGER bms_activity_log_no_drop", "SET session_replication_role = replica",
        ).forEach { sql ->
            listOf(false, true).forEach { owned ->
                runtime("bms_ai_acme").use { db ->
                    if (owned) db.run("SET ROLE $owner")
                    assertFailsWith<SQLException>("$sql (as owner: $owned)") { db.run(sql) }
                }
            }
        }
        // The runtime still writes, and the chain still verifies (and keeps matching its anchor).
        write("bms_ai_acme", 2)
        val after = service.verify("acme")
        assertTrue(after.ok, after.problems.toString())
        assertEquals(5, after.rows)
        assertEquals(1, after.anchorsChecked)

        // The server audit is append-only for the runtime role as install.sh grants it.
        runtime(central).use { db ->
            listOf(false, true).forEach { owned ->
                if (owned) db.run("SET ROLE $owner")
                listOf("UPDATE provisioning_audit_log SET action = 'x'", "DELETE FROM provisioning_audit_log", "TRUNCATE provisioning_audit_log",
                    "DROP TABLE provisioning_audit_log").forEach { sql -> assertFailsWith<SQLException>("$sql ($owned)") { db.run(sql) } }
            }
        }

        // Undoing the hardening later (as the superuser) is a failure, not a silent downgrade.
        superuser("bms_ai_acme").use { it.run("ALTER TABLE activity_log OWNER TO $owner") }
        val downgraded = service.verify("acme")
        assertFalse(downgraded.ok)
        assertTrue(downgraded.problems.any { it.contains("estaba protegido") }, downgraded.problems.toString())
    }

    private fun loginDatabases(): LoginDatabases {
        val data = runtimeSource(central)
        return LoginDatabases(data, Database.connect(data), urlOf(central).substringBefore("?"), "[]", runtimeRole, "runtime-pass", prefix, 3600).also { opened += it }
    }

    private fun provisioning(databases: LoginDatabases, integrity: ActivityIntegrityService) = CompanyProvisioningService(
        central = runtimeSource(central),
        config = CompanyProvisioningConfig(urlOf("postgres").substringBefore("?"), runtimeRole, "runtime-pass", prefix, root.path),
        loginDatabases = databases,
        moduleRegistry = ModuleRegistry(listOf(com.ideasdeveloper.bms.modules.clientes.ClientesServerModule)),
        remoteModuleCatalog = RemoteModuleCatalog(),
        defaultCatalogUrl = "",
        modulePackagesRoot = java.nio.file.Files.createTempDirectory("ai-modules"),
        modulePackageBuilder = null,
        requestRestart = {},
        activityIntegrity = integrity,
    )

    @Test
    fun provisioningDuplicationCatchUpDeactivationAndDeletionWorkOnAHardenedLog() {
        if (url == null) return
        reset()
        val databases = loginDatabases()
        val service = ActivityIntegrityService(runtimeSource(central), { database -> runtime(database) })
        val provisioning = provisioning(databases, service)
        val actor = AuditActor.owner(owner, ownerId)
        provisioning.createCompany(
            CreateCompanyRequest("ai_acme", "Acme", "ai_dueno_acme", "clave-del-dueno-123", "Dueno Acme", "dueno@acme.example"), owner, actor,
        )
        // Created at runtime: owned by the server owner's role until the next hardening run, and reported so.
        val fresh = service.verify("ai_acme")
        assertTrue(fresh.ok, fresh.problems.toString())
        assertFalse(fresh.hardened)
        harden("bms_ai_acme")
        assertTrue(service.verify("ai_acme").hardened)
        // A change made from the server writes its row on the company database (role switched back to the runtime).
        provisioning.updateCompany("ai_acme", com.ideasdeveloper.bms.server.company.api.UpdateCompanyRequest("Acme SA", null, "#112233", "#445566", "#778899"), owner, actor)
        assertEquals("1", scalar("bms_ai_acme", "SELECT count(*) FROM activity_log WHERE summary = 'Cambio desde el servidor los datos de la empresa.'"))
        // The startup catch-up runs over a hardened log without errors and without giving anything back.
        provisioning.applyPendingModuleMigrations()
        assertEquals(
            "INSERT,SELECT",
            scalar("bms_ai_acme", "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE grantee = '$runtimeRole' AND table_name = 'activity_log'"),
        )
        assertTrue(service.verify("ai_acme").let { it.ok && it.hardened })
        // Duplicating from a hardened company.
        provisioning.duplicateCompany("ai_acme", "ai_copia", owner, actor)
        assertTrue(service.verify("ai_copia").ok)
        // Deactivation anchors; deletion writes the final anchor with its summary and drops the database.
        provisioning.setCompanyActive("ai_acme", false, owner, actor)
        assertEquals("deactivation", scalar(central, "SELECT details->>'reason' FROM provisioning_audit_log WHERE action = 'activity.anchor' AND company_code = 'ai_acme' ORDER BY created_at DESC LIMIT 1"))
        val rows = scalar("bms_ai_acme", "SELECT count(*) FROM activity_log")
        provisioning.deleteInactiveCompany("ai_acme", owner, actor)
        assertEquals("0", scalar("postgres", "SELECT count(*) FROM pg_database WHERE datname = 'bms_ai_acme'"))
        superuser(central).use { db ->
            assertEquals("deletion", scalar(db, "SELECT details->>'reason' FROM provisioning_audit_log WHERE action = 'activity.anchor' AND company_code = 'ai_acme' ORDER BY created_at DESC LIMIT 1"))
            assertEquals(rows, scalar(db, "SELECT details->>'count' FROM provisioning_audit_log WHERE action = 'activity.anchor' AND company_code = 'ai_acme' ORDER BY created_at DESC LIMIT 1"))
            assertNotNull(scalar(db, "SELECT details->>'firstAt' FROM provisioning_audit_log WHERE action = 'activity.anchor' AND details->>'reason' = 'deletion'"))
            assertEquals(rows, scalar(db, "SELECT details->>'activityRows' FROM provisioning_audit_log WHERE action = 'company.deleted' AND company_code = 'ai_acme'"))
        }
        // The job: every remaining company checked, the one not hardened yet named.
        val reports = service.runAll()
        assertEquals(listOf("ai_copia"), reports.map { it.companyCode })
        assertEquals("ai_copia", scalar(central, "SELECT details->'companies'->>0 FROM provisioning_audit_log WHERE action = 'activity.not_hardened' ORDER BY created_at DESC LIMIT 1"))
    }
}
