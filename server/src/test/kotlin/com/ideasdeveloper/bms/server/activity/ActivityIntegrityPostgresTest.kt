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

    /**
     * What scripts/ubuntu/activity-harden.sh feeds the superuser for one database (its psql variable and the canonical
     * migration spliced in), with the role and search path pg-safe.sh pins. The shell paths themselves (settings,
     * evidence, the check first) are tested by scripts/ubuntu/tests/activity-harden-test.sh.
     */
    private fun harden(database: String) = superuser(database).use { db ->
        db.run("SET ROLE NONE")
        db.run("SET search_path = pg_catalog, pg_temp")
        db.run(hardenSql())
    }

    private fun hardenSql(): String {
        val canonical = migration("database/tenant/migrations/V011__create_activity_log.sql")
        return File(root, "scripts/ubuntu/activity-harden.sql").readText()
            .replace(":'db_user'", "'$runtimeRole'")
            .replace("\n-- @canonical@\n", "\n$canonical\n")
    }

    /** The deviation query as activity-harden.sh runs it (as the superuser), minus the "hardening" rows when [all] is false. */
    private fun deviations(database: String, all: Boolean = false): List<String> = superuser(database).use { db ->
        db.run("SET search_path = pg_catalog, pg_temp")
        db.run("SELECT set_config('bms.runtime_user', '$runtimeRole', false)")
        db.prepareStatement("SELECT set_config('bms.audit_functions', ?, false)").use { st ->
            st.setString(1, (ActivityIntegrity.versions.last().functions + ("guard" to ActivityIntegrity.GuardSha256)).entries.joinToString(";") { "${it.key}=${it.value}" })
            st.executeQuery().close()
        }
        db.createStatement().use { st ->
            st.executeQuery(ActivityIntegrity.deviationQuery).use { r -> buildList { while (r.next()) if (all || r.getString(1) != "hardening") add(r.getString(1) + ": " + r.getString(2)) } }
        }
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
        assertEquals(deleted[1], gap.firstBadId)
        assertTrue(gap.problems.any { it.contains("posicion 2 de la cadena) fue borrada") }, gap.problems.toString())
        assertTrue(gap.problems.any { it.contains("activity_log_no_change de activity_log esta desactivado") }, gap.problems.toString())
        // Its chain row deleted too: the gap in the chain itself.
        asOwner("bms_ai_del", "ALTER TABLE activity_log_chain DISABLE TRIGGER activity_log_chain_no_change", "DELETE FROM activity_log_chain WHERE id = ${deleted[1]}")
        val hole = service.verify("del")
        assertEquals(deleted[2], hole.firstBadId)
        assertTrue(hole.problems.any { it.contains("Faltan las posiciones 2 a 2") }, hole.problems.toString())

        // The newest rows deleted (and the head rewound): the anchor still says how far the chain reached.
        val tail = write("bms_ai_tail", 4)
        service.anchor("tail", "hourly")
        asOwner(
            "bms_ai_tail", "ALTER TABLE activity_log DISABLE TRIGGER activity_log_no_change",
            "ALTER TABLE activity_log_chain DISABLE TRIGGER activity_log_chain_no_change",
            "DELETE FROM activity_log WHERE id >= ${tail[2]}", "DELETE FROM activity_log_chain WHERE id >= ${tail[2]}",
            "ALTER TABLE activity_log ENABLE TRIGGER activity_log_no_change",
            "ALTER TABLE activity_log_chain ENABLE TRIGGER activity_log_chain_no_change",
            "UPDATE activity_log_chain_head SET last_seq = 2, last_hash = (SELECT row_hash FROM activity_log_chain WHERE chain_seq = 2)",
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
            "DELETE FROM activity_log_chain", "TRUNCATE activity_log_chain", "INSERT INTO activity_log_chain VALUES (99, 99, 'x', 'y')",
            "DROP TABLE activity_log_chain CASCADE", "DROP INDEX activity_log_occurred_idx", "ALTER TABLE activity_log ADD COLUMN x INT",
            "CREATE TRIGGER x BEFORE INSERT ON activity_log FOR EACH ROW EXECUTE FUNCTION activity_log_before_insert()",
            "CREATE RULE x AS ON INSERT TO activity_log DO INSTEAD NOTHING", "ALTER TABLE activity_log ENABLE ROW LEVEL SECURITY",
            "ALTER SEQUENCE activity_log_id_seq INCREMENT BY 2", "DROP SEQUENCE activity_log_id_seq CASCADE",
            "UPDATE activity_log_chain_head SET hardened_at = now()",
        ).forEach { sql ->
            listOf(false, true).forEach { owned ->
                runtime("bms_ai_acme").use { db ->
                    if (owned) db.run("SET ROLE $owner")
                    assertFailsWith<SQLException>("$sql (as owner: $owned)") { db.run(sql) }
                }
            }
        }
        // The guard matches exact objects: other tables of the schema named activity_log* are the owner's business.
        asOwner("bms_ai_acme", "CREATE TABLE activity_log_notes (x INT)", "DROP TABLE activity_log_notes")
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

    @Test
    fun tamperingInstalledBeforeHardeningIsReportedAndHardeningRemovesItAndRecordsWhenTheGuaranteeStarts() {
        if (url == null) return
        reset("pre")
        val service = service()
        write("bms_ai_pre", 2)
        assertTrue(deviations("bms_ai_pre").isEmpty(), deviations("bms_ai_pre").toString())
        // The reviewer's path: before the hardening the owner's role leaves itself live control over future rows.
        asOwner(
            "bms_ai_pre",
            "CREATE FUNCTION public.evil_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.summary := 'Reescrita.'; RETURN NEW; END $$",
            "CREATE TRIGGER zz_rewrite BEFORE INSERT ON activity_log FOR EACH ROW EXECUTE FUNCTION public.evil_rewrite()",
            "CREATE RULE swallow AS ON INSERT TO activity_log WHERE NEW.summary LIKE 'tragar%' DO INSTEAD NOTHING",
            "ALTER TABLE activity_log ENABLE ROW LEVEL SECURITY",
            "CREATE POLICY hide ON activity_log FOR SELECT USING (summary <> 'oculta')",
            "ALTER TABLE activity_log ALTER COLUMN entity_label SET DEFAULT 'x'",
            "ALTER TABLE activity_log ADD COLUMN extra TEXT DEFAULT 'oculto'",
            "CREATE TABLE activity_log_shadow () INHERITS (activity_log)",
            "CREATE FUNCTION public.evil_check(BIGINT) RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS 'SELECT true'",
            "ALTER TABLE activity_log_chain ADD CONSTRAINT evil CHECK (public.evil_check(id))",
            "CREATE INDEX evil_idx ON activity_log ((public.evil_check(id)))",
            "ALTER TABLE activity_log_chain_head SET UNLOGGED",
            "CREATE FUNCTION public.activity_log_seal(INTEGER) RETURNS INTEGER LANGUAGE sql AS 'SELECT 1'",
            "UPDATE activity_log_chain_head SET hardened_at = '2000-01-01', hardened_seq = 0",
            "CREATE TABLE refs (id BIGINT REFERENCES activity_log (id))",
            "ALTER TABLE activity_log DISABLE TRIGGER activity_log_seal",
        )
        val tampered = service.verify("pre")
        assertFalse(tampered.ok)
        listOf(
            "zz_rewrite", "La regla swallow", "seguridad por filas", "La politica hide", "entity_label fue cambiada", "extra no es de V011",
            "hereda de activity_log", "La restriccion evil", "El indice evil_idx", "UNLOGGED", "sobrecarga", "La clave foranea",
            "activity_log_seal de activity_log esta desactivado",
        ).forEach { expected -> assertTrue(tampered.problems.any { it.contains(expected) }, "$expected: ${tampered.problems}") }

        harden("bms_ai_pre")
        assertEquals(emptyList(), deviations("bms_ai_pre", all = true))
        val clean = service.verify("pre")
        assertTrue(clean.ok, clean.problems.toString())
        assertTrue(clean.hardened, clean.notHardenedReasons.toString())
        // The guarantee starts at this hardening (not at the forged date), after the 2 rows written before it.
        assertEquals(2L, clean.hardenedSeq)
        assertTrue(clean.hardenedAt!!.startsWith(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()), clean.hardenedAt)
        assertTrue(clean.coverage!!.contains("hasta la posicion 2"), clean.coverage)
        // Nothing of it acts on new rows: no rewrite, no swallowed insert, no hidden column, no child rows.
        write("bms_ai_pre", 1, "tragar")
        assertEquals("tragar 1.", scalar("bms_ai_pre", "SELECT summary FROM activity_log ORDER BY id DESC LIMIT 1"))
        assertEquals("3", scalar("bms_ai_pre", "SELECT count(*) FROM activity_log"))
        assertEquals("0", scalar("bms_ai_pre", "SELECT count(*) FROM pg_inherits"))
        assertEquals("p", scalar("bms_ai_pre", "SELECT relpersistence FROM pg_class WHERE relname = 'activity_log_chain_head'"))
        assertTrue(service.verify("pre").ok)
        // A hardening done again (after the superuser undid it) moves the start: reported against the anchor.
        service.anchor("pre", "hourly")
        superuser("bms_ai_pre").use { it.run("ALTER TABLE activity_log OWNER TO $owner") }
        Thread.sleep(1_100)
        harden("bms_ai_pre")
        val moved = service.verify("pre")
        assertFalse(moved.ok)
        assertTrue(moved.problems.any { it.contains("fecha de proteccion") }, moved.problems.toString())
    }

    @Test
    fun aRestoredCopyWithTheSameChainVerifiesAndAnOlderOneIsReported() {
        if (url == null) return
        reset("orig")
        val service = service()
        write("bms_ai_orig", 3)
        harden("bms_ai_orig")
        service.anchor("orig", "hourly")
        // A restore as SELF_HOSTING.md describes it gives a new database (new oid) with the same chain.
        superuser("postgres").use { admin ->
            admin.run("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'bms_ai_orig'")
            admin.run("CREATE DATABASE bms_ai_older TEMPLATE bms_ai_orig")
        }
        write("bms_ai_orig", 2)
        service.anchor("orig", "hourly")
        superuser("postgres").use { admin ->
            admin.run("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'bms_ai_orig'")
            admin.run("CREATE DATABASE bms_ai_restored TEMPLATE bms_ai_orig")
        }
        superuser(central).use { it.run("UPDATE companies SET database_name = 'bms_ai_restored' WHERE code = 'orig'") }
        val restored = service.verify("orig")
        assertTrue(restored.ok, restored.problems.toString())
        assertTrue(restored.notes.any { it.contains("restaurada") }, restored.notes.toString())
        // A copy from before the last anchor lost rows: that is reported.
        superuser(central).use { it.run("UPDATE companies SET database_name = 'bms_ai_older' WHERE code = 'orig'") }
        val older = service.verify("orig")
        assertFalse(older.ok)
        assertTrue(older.problems.any { it.contains("restaurada de una copia anterior") }, older.problems.toString())
    }

    @Test
    fun sixteenConcurrentWritersKeepAContiguousChainAndWritersNeverHangOrUseSnapshotIsolation() {
        if (url == null) return
        reset("busy")
        harden("bms_ai_busy")
        val pool = Executors.newFixedThreadPool(16)
        val futures = (1..16).map { writer ->
            pool.submit {
                runtime("bms_ai_busy").use { db ->
                    repeat(50) { index ->
                        ActivityLog.atomically(db) {
                            ActivityLog.record(db, ActivityActor.user(UUID.randomUUID(), "Escritor $writer"), ActivityEntry("crm", "lead", "$writer-$index", "", ActivityActions.Created, "Fila $writer-$index."))
                        }
                    }
                }
            }
        }
        futures.forEach { it.get(3, TimeUnit.MINUTES) }
        pool.shutdown()
        val report = service().verify("busy")
        assertTrue(report.ok, report.problems.toString())
        assertEquals(800L, report.rows)
        assertEquals(800L, report.lastSeq)
        // A transaction of 20,000 rows seals in one pass at commit.
        runtime("bms_ai_busy").use { db ->
            db.autoCommit = false
            db.run("INSERT INTO public.activity_log (actor_kind, actor_name, actor_display, module_id, entity_type, action, summary) SELECT 'import', 'Importacion', 'Importacion', 'crm', 'lead', 'imported', 'Fila ' || g FROM generate_series(1, 20000) g")
            val started = System.nanoTime()
            db.commit()
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue(millis < 5_000, "20,000 rows took $millis ms to seal")
        }
        assertEquals(20_800L, service().verify("busy").lastSeq)
        // Snapshot isolation would fail at commit under concurrency: refused at the insert, clearly.
        runtime("bms_ai_busy").use { db ->
            db.autoCommit = false
            db.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            val refused = assertFailsWith<SQLException> {
                ActivityLog.record(db, ActivityActor.system(), ActivityEntry("core", "x", null, "", ActivityActions.Updated, "x"))
            }
            assertTrue(refused.message!!.contains("READ COMMITTED"), refused.message)
            db.rollback()
        }
        // A commit waiting for the company's sealing lock fails after 10 s instead of hanging.
        runtime("bms_ai_busy").use { holder ->
            holder.autoCommit = false
            ActivityLog.record(holder, ActivityActor.system(), ActivityEntry("core", "x", null, "", ActivityActions.Updated, "sostiene"))
            holder.run("SET CONSTRAINTS ALL IMMEDIATE")
            runtime("bms_ai_busy").use { waiter ->
                waiter.autoCommit = false
                ActivityLog.record(waiter, ActivityActor.system(), ActivityEntry("core", "x", null, "", ActivityActions.Updated, "espera"))
                val started = System.nanoTime()
                val timeout = assertFailsWith<SQLException> { waiter.commit() }
                assertEquals("55P03", timeout.sqlState)
                assertTrue((System.nanoTime() - started) / 1_000_000 in 9_000..20_000)
            }
            holder.commit()
        }
        assertTrue(service().verify("busy").ok)
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
