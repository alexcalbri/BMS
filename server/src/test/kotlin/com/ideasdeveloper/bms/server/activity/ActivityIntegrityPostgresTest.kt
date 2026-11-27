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
 * Tamper evidence of the activity log on a real PostgreSQL (ACTIVITY_LOG.md §9), with the roles as install.sh leaves
 * them: the runtime role is a member of the server owner's role (CREATEDB CREATEROLE), which owns every company
 * database. No superuser step is part of it: the superuser connection only plays install.sh and update.sh (control
 * migrations, and company tables an older update.sh created as postgres). Runs only with BMS_ACTIVITY_PG_URL (or
 * BMS_SERVER_ADMINS_PG_URL) of a DISPOSABLE server: it creates and drops bms_ai_* databases and roles.
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
            rt.run("GRANT SELECT, INSERT ON activity_log, activity_log_chain TO $runtimeRole")
            rt.run("GRANT USAGE ON SEQUENCE activity_log_id_seq TO $runtimeRole")
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

    /** The deviation query as the server runs it, here as the superuser (detection reads catalogs only). */
    private fun deviations(database: String): List<String> = superuser(database).use { db ->
        db.run("SET search_path = pg_catalog, pg_temp")
        db.prepareStatement("SELECT set_config('bms.activity_functions', ?, false)").use { st ->
            st.setString(1, ActivityIntegrity.versions.last().functions.entries.joinToString(";") { "${it.key}=${it.value}" })
            st.executeQuery().close()
        }
        db.createStatement().use { st -> st.executeQuery(ActivityIntegrity.deviationQuery).use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
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

        // The newest rows deleted with their chain rows: the anchor still says how far the chain reached.
        val tail = write("bms_ai_tail", 4)
        service.anchor("tail", "hourly")
        asOwner(
            "bms_ai_tail", "ALTER TABLE activity_log DISABLE TRIGGER activity_log_no_change",
            "ALTER TABLE activity_log_chain DISABLE TRIGGER activity_log_chain_no_change",
            "DELETE FROM activity_log WHERE id >= ${tail[2]}", "DELETE FROM activity_log_chain WHERE id >= ${tail[2]}",
            "ALTER TABLE activity_log ENABLE TRIGGER activity_log_no_change",
            "ALTER TABLE activity_log_chain ENABLE TRIGGER activity_log_chain_no_change",
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
    fun structuralTamperingIsReportedAndAttackerTypesAreNeverRead() {
        if (url == null) return
        reset("pre", "types", "late")
        val service = service()
        write("bms_ai_pre", 2)
        assertTrue(deviations("bms_ai_pre").isEmpty(), deviations("bms_ai_pre").toString())
        assertTrue(service.verify("pre").ok)
        // What the owner's role (through the runtime login) can install to steer future rows or hide old ones.
        asOwner(
            "bms_ai_pre",
            "CREATE FUNCTION public.evil_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.summary := 'Reescrita.'; RETURN NEW; END $$",
            "CREATE TRIGGER zz_rewrite BEFORE INSERT ON activity_log FOR EACH ROW EXECUTE FUNCTION public.evil_rewrite()",
            "CREATE RULE swallow AS ON INSERT TO activity_log WHERE NEW.summary LIKE 'tragar%' DO INSTEAD NOTHING",
            "CREATE POLICY hide ON activity_log FOR SELECT USING (summary <> 'oculta')",
            "ALTER TABLE activity_log ALTER COLUMN entity_label SET DEFAULT 'x'",
            "ALTER TABLE activity_log ADD COLUMN extra TEXT DEFAULT 'oculto'",
            "CREATE TABLE activity_log_shadow () INHERITS (activity_log)",
            "CREATE FUNCTION public.evil_check(BIGINT) RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS 'SELECT true'",
            "ALTER TABLE activity_log_chain ADD CONSTRAINT evil CHECK (public.evil_check(id))",
            "CREATE INDEX evil_idx ON activity_log ((public.evil_check(id)))",
            "CREATE STATISTICS evil_stats ON (public.evil_check(id)) FROM activity_log",
            "ALTER TABLE activity_log_chain SET (user_catalog_table = true)",
            "CREATE FUNCTION public.activity_log_seal(INTEGER) RETURNS INTEGER LANGUAGE sql AS 'SELECT 1'",
            "CREATE TABLE refs (id BIGINT REFERENCES activity_log (id))",
            "ALTER TABLE activity_log DISABLE TRIGGER activity_log_seal",
        )
        val tampered = service.verify("pre")
        assertFalse(tampered.ok)
        listOf(
            "zz_rewrite", "La regla swallow", "La politica hide", "entity_label fue cambiada", "extra no es de V011",
            "hereda de activity_log", "La restriccion evil", "El indice evil_idx", "estadisticas evil_stats", "user_catalog_table",
            "sobrecarga", "La clave foranea", "activity_log_seal de activity_log esta desactivado",
        ).forEach { expected -> assertTrue(tampered.problems.any { it.contains(expected) }, "$expected: ${tampered.problems}") }
        // Owners: a function given to another role than the tables'.
        superuser("bms_ai_pre").use { it.run("ALTER FUNCTION activity_log_append_only() OWNER TO $runtimeRole") }
        assertTrue(service.verify("pre").problems.any { it.contains("activity_log_append_only pertenece a $runtimeRole") })

        // A column of a type the company database defines, a policy calling its own function, and an implicit cast: the
        // verification reports them and never reads those tables, so none of it runs (it would leave a trace in 'traces').
        write("bms_ai_types", 2)
        asOwner(
            "bms_ai_types",
            "CREATE TABLE traces (at TIMESTAMPTZ DEFAULT now())",
            "GRANT INSERT ON traces TO PUBLIC",
            "CREATE DOMAIN public.trap AS TEXT CHECK (VALUE <> '')",
            "CREATE FUNCTION public.trap_text(public.trap) RETURNS TEXT LANGUAGE plpgsql AS $$ BEGIN INSERT INTO public.traces DEFAULT VALUES; RETURN 'x'; END $$",
            "ALTER TABLE activity_log DISABLE TRIGGER activity_log_no_change",
            "ALTER TABLE activity_log ALTER COLUMN summary TYPE public.trap",
            "ALTER TABLE activity_log ENABLE TRIGGER activity_log_no_change",
            "ALTER TABLE activity_log ENABLE ROW LEVEL SECURITY",
            "CREATE POLICY spy ON activity_log FOR SELECT USING ((SELECT true FROM (SELECT public.trap_text(summary)) x))",
        )
        val typed = service.verify("types")
        assertFalse(typed.ok)
        listOf("summary es de un tipo que no es el de PostgreSQL", "No se leyeron las filas de activity_log", "seguridad por filas")
            .forEach { expected -> assertTrue(typed.problems.any { it.contains(expected) }, "$expected: ${typed.problems}") }
        assertEquals("0", scalar("bms_ai_types", "SELECT count(*) FROM traces"))
        asOwner(
            "bms_ai_types", "CREATE TYPE public.mood AS ENUM ('a')", "ALTER TABLE activity_log_chain ADD COLUMN mood public.mood",
            "CREATE FUNCTION public.mood_text(public.mood) RETURNS TEXT LANGUAGE plpgsql AS $$ BEGIN INSERT INTO public.traces DEFAULT VALUES; RETURN 'x'; END $$",
            "CREATE CAST (public.mood AS TEXT) WITH FUNCTION public.mood_text(public.mood) AS IMPLICIT",
        )
        val cast = service.verify("types")
        listOf("Hay una conversion (CREATE CAST) que no es de PostgreSQL de public.mood a text", "No se leyo la cadena (activity_log_chain)")
            .forEach { expected -> assertTrue(cast.problems.any { it.contains(expected) }, "$expected: ${cast.problems}") }
        assertEquals("0", scalar("bms_ai_types", "SELECT count(*) FROM traces"))
        // A typed table cannot hold the log's rows as V011 has them; reported as such when the chain is replaced by one.
        asOwner(
            "bms_ai_types", "CREATE TYPE public.link AS (chain_seq BIGINT, id BIGINT, prev_hash TEXT, row_hash TEXT)",
            "ALTER TABLE activity_log_chain RENAME TO activity_log_chain_old", "CREATE TABLE activity_log_chain OF public.link",
        )
        assertTrue(service.verify("types").problems.any { it.contains("tabla tipada") })

        // Rows without a seal are reported once they are older than the grace period (an honest row is sealed at commit).
        write("bms_ai_late", 1)
        asOwner("bms_ai_late", "ALTER TABLE activity_log DISABLE TRIGGER activity_log_seal")
        val unsealed = write("bms_ai_late", 1).single()
        asOwner("bms_ai_late", "ALTER TABLE activity_log ENABLE TRIGGER activity_log_seal")
        assertTrue(service.verify("late").ok, "an unsealed row within the grace period is not yet a finding")
        asOwner(
            "bms_ai_late", "ALTER TABLE activity_log DISABLE TRIGGER activity_log_no_change",
            "UPDATE activity_log SET occurred_at = occurred_at - interval '1 hour' WHERE id = $unsealed",
            "ALTER TABLE activity_log ENABLE TRIGGER activity_log_no_change",
        )
        val late = service.verify("late")
        assertFalse(late.ok)
        assertEquals(unsealed, late.firstBadId)
        assertTrue(late.problems.any { it.contains("no esta sellada") }, late.problems.toString())
    }

    @Test
    fun theServerAuditIsAppendOnlyForTheRuntimeRoleAsInstallShGrantsIt() {
        if (url == null) return
        reset()
        // provisioning_audit_log belongs to the superuser (control migrations run as postgres); runtime: SELECT, INSERT.
        assertEquals("postgres", scalar(central, "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'public.provisioning_audit_log'::regclass"))
        runtime(central).use { db ->
            listOf(false, true).forEach { owned ->
                if (owned) db.run("SET ROLE $owner")
                listOf("UPDATE provisioning_audit_log SET action = 'x'", "DELETE FROM provisioning_audit_log", "TRUNCATE provisioning_audit_log",
                    "DROP TABLE provisioning_audit_log").forEach { sql -> assertFailsWith<SQLException>("$sql ($owned)") { db.run(sql) } }
            }
            db.run("RESET ROLE")
            db.run("INSERT INTO provisioning_audit_log (id, actor_role, action, details) VALUES (gen_random_uuid(), '$runtimeRole', 'activity.anchor', '{}')")
        }
    }

    /**
     * A company of a server where an older update.sh created the tenant tables as postgres: update.sh's own procedure
     * (V011 applied by the superuser, then its GRANT lines for the runtime role, read from update.sh itself) leaves a log
     * the runtime role, which is not a member of postgres, can write and seal, and that verifies.
     */
    @Test
    fun aCompanyWhoseTablesBelongToAnotherRoleGetsV011ThroughUpdateShAndSealsRows() {
        if (url == null) return
        reset()
        superuser("postgres").use { admin ->
            admin.run("CREATE DATABASE bms_ai_old")
            admin.run("GRANT CONNECT ON DATABASE bms_ai_old TO $runtimeRole")
        }
        superuser(central).use { db ->
            db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), 'old', 'Empresa vieja', 'bms_ai_old', gen_random_uuid())")
        }
        val updateSh = File(root, "scripts/ubuntu/update.sh").readText()
        assertTrue(updateSh.contains("apply_migration \"\$tenant_db\" tenant database/tenant/migrations/V011__create_activity_log.sql"))
        val grants = updateSh.lines().dropWhile { !it.startsWith("-- Activity log (tenant V011") }.drop(1).takeWhile { it.startsWith("GRANT") }
        assertEquals(2, grants.size, grants.toString())
        superuser("bms_ai_old").use { db ->
            // What an older update.sh left: tenant tables owned by postgres (here core and tenant V001 and the history).
            db.run(migration("database/core/migrations/V001__create_application_users.sql"))
            db.run(migration("database/tenant/migrations/V001__create_business_owner.sql"))
            db.run("CREATE TABLE schema_migrations (module TEXT NOT NULL, version TEXT NOT NULL, checksum TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (module, version))")
            db.autoCommit = false
            db.run(migration("database/tenant/migrations/V011__create_activity_log.sql"))
            db.run("INSERT INTO schema_migrations (module, version, checksum) VALUES ('tenant', 'V011__create_activity_log', 'x')")
            db.commit()
            db.autoCommit = true
            grants.forEach { db.run(it.replace(":\"db_user\"", "\"$runtimeRole\"")) }
            assertEquals("postgres", scalar(db, "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'public.activity_log'::regclass"))
        }
        assertEquals("f", scalar("bms_ai_old", "SELECT pg_has_role('$runtimeRole', 'postgres', 'MEMBER')"))
        val ids = write("bms_ai_old", 3)
        assertEquals("3", scalar("bms_ai_old", "SELECT count(*) FROM activity_log_chain WHERE id IN (${ids.joinToString()})"))
        val service = service()
        val report = service.verify("old")
        assertTrue(report.ok, report.problems.toString())
        assertEquals(3L, report.lastSeq)
        assertNotNull(service.anchor("old", "hourly"))
        // Still append-only for the runtime role, and the chain is insert-only.
        runtime("bms_ai_old").use { db ->
            listOf("UPDATE activity_log SET summary = 'x'", "DELETE FROM activity_log", "DELETE FROM activity_log_chain").forEach { sql ->
                assertFailsWith<SQLException>(sql) { db.run(sql) }
            }
        }
        assertTrue(service.verify("old").let { it.ok && it.anchorsChecked == 1 })
    }

    @Test
    fun aRestoredCopyWithTheSameChainVerifiesAndAnOlderOneIsReported() {
        if (url == null) return
        reset("orig")
        val service = service()
        write("bms_ai_orig", 3)
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
        // Writing a row does not change the writer's own lock_timeout (the 10 s applies to the sealing lock only).
        runtime("bms_ai_busy").use { db ->
            db.autoCommit = false
            ActivityLog.record(db, ActivityActor.system(), ActivityEntry("core", "x", null, "", ActivityActions.Updated, "x"))
            assertEquals("0", scalar(db, "SHOW lock_timeout"))
            db.commit()
            assertEquals("0", scalar(db, "SHOW lock_timeout"))
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
    fun provisioningDuplicationCatchUpDeactivationAndDeletionKeepTheChainAndItsAnchors() {
        if (url == null) return
        reset()
        val databases = loginDatabases()
        val service = ActivityIntegrityService(runtimeSource(central), { database -> runtime(database) })
        val provisioning = provisioning(databases, service)
        val actor = AuditActor.owner(owner, ownerId)
        provisioning.createCompany(
            CreateCompanyRequest("ai_acme", "Acme", "ai_dueno_acme", "clave-del-dueno-123", "Dueno Acme", "dueno@acme.example"), owner, actor,
        )
        // Created at runtime by the server owner's role, with the runtime grants on the log and its chain.
        val fresh = service.verify("ai_acme")
        assertTrue(fresh.ok, fresh.problems.toString())
        assertEquals("t", scalar("bms_ai_acme", "SELECT has_table_privilege('$runtimeRole', 'activity_log_chain', 'INSERT') AND has_table_privilege('$runtimeRole', 'activity_log_chain', 'SELECT')"))
        // A change made from the server writes its row on the company database (role switched back to the runtime).
        provisioning.updateCompany("ai_acme", com.ideasdeveloper.bms.server.company.api.UpdateCompanyRequest("Acme SA", null, "#112233", "#445566", "#778899"), owner, actor)
        assertEquals("1", scalar("bms_ai_acme", "SELECT count(*) FROM activity_log WHERE summary = 'Cambio desde el servidor los datos de la empresa.'"))
        // The startup catch-up runs again without errors and gives the runtime role nothing beyond SELECT, INSERT.
        provisioning.applyPendingModuleMigrations()
        assertEquals(
            "0",
            scalar("bms_ai_acme", "SELECT count(*) FROM information_schema.role_table_grants WHERE grantee = '$runtimeRole' AND table_name LIKE 'activity\\_log%' AND privilege_type NOT IN ('SELECT', 'INSERT')"),
        )
        assertTrue(service.verify("ai_acme").ok)
        // Duplicating a company.
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
        // The job: every remaining company checked and anchored.
        val reports = service.runAll()
        assertEquals(listOf("ai_copia"), reports.map { it.companyCode })
        assertTrue(reports.single().ok, reports.single().problems.toString())
        assertEquals("hourly", scalar(central, "SELECT details->>'reason' FROM provisioning_audit_log WHERE action = 'activity.anchor' AND company_code = 'ai_copia' ORDER BY created_at DESC LIMIT 1"))
    }
}
