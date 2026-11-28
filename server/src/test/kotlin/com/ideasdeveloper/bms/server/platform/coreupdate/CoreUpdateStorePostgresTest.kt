package com.ideasdeveloper.bms.server.platform.coreupdate

import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Control V016 against a real PostgreSQL: the backend role (with the grants install.sh/update.sh gave it for
 * V010) can store the approved commit, the column only accepts a 40-hex sha, and signature_status is only
 * what the root helper writes. Runs only with BMS_TEST_PG_URL (jdbc:postgresql://host:port/postgres?user=postgres)
 * of a disposable server; it creates and drops its own database and role.
 */
class CoreUpdateStorePostgresTest {
    private val url: String? = System.getenv("BMS_TEST_PG_URL")?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val suffix = System.nanoTime().toString(36)
    private val database = "bms_cu_$suffix"
    private val backend = "bms_cu_backend_$suffix"

    private fun urlOf(name: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$name$1")
    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    /** Connections that act as the backend role (SET ROLE), as the server does with its own login. */
    private fun asBackend(): DataSource {
        val base = PGSimpleDataSource().apply { setURL(urlOf(database)) }
        return object : DataSource {
            override fun getConnection(): Connection = base.connection.also { it.run("SET ROLE $backend") }
            override fun getConnection(username: String?, password: String?): Connection = connection
            override fun getLogWriter(): PrintWriter? = null
            override fun setLogWriter(out: PrintWriter?) = Unit
            override fun setLoginTimeout(seconds: Int) = Unit
            override fun getLoginTimeout(): Int = 0
            override fun getParentLogger(): Logger = Logger.getGlobal()
            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("no")
            override fun isWrapperFor(iface: Class<*>?): Boolean = false
        }
    }

    @Test
    fun theBackendStoresTheApprovedCommitAndNothingElse() {
        if (url == null) return
        DriverManager.getConnection(url).use { admin ->
            admin.run("CREATE DATABASE $database")
            admin.run("CREATE ROLE $backend NOLOGIN")
        }
        try {
            DriverManager.getConnection(urlOf(database)).use { db ->
                listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                    .forEach { db.run(migration("database/core/migrations/$it.sql")) }
                val control = File(root, "database/control/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                control.filter { it.name < "V016" }.forEach { db.run(migration("database/control/migrations/${it.name}")) }
                // The grants a live server already has (install.sh / update.sh, before this change).
                db.run(
                    """
                    GRANT USAGE ON SCHEMA public TO $backend;
                    GRANT SELECT ON application_users, server_owners TO $backend;
                    GRANT SELECT, INSERT ON provisioning_audit_log TO $backend;
                    GRANT SELECT ON core_update_requests TO $backend;
                    GRANT INSERT (target_version, from_version, requested_by) ON core_update_requests TO $backend;
                    GRANT USAGE ON SEQUENCE core_update_requests_id_seq TO $backend;
                    """.trimIndent()
                )
                control.filter { it.name >= "V016" }.forEach { db.run(migration("database/control/migrations/${it.name}")) }
            }
            val sha = "0123456789abcdef0123456789abcdef01234567"
            val store = JdbcCoreUpdateStore(asBackend())
            val row = store.insertPending("0.13.0", "0.12.0", "owner", sha)
            assertEquals(sha, row.targetCommit)
            assertNull(row.signatureStatus)
            assertEquals(sha, store.latest()!!.targetCommit)

            asBackend().connection.use { db ->
                // Only a full lowercase sha is accepted.
                listOf("abc", sha.uppercase(), "$sha;x").forEach { bad ->
                    assertFailsWith<SQLException>(bad) {
                        db.prepareStatement("INSERT INTO core_update_requests (target_version, from_version, requested_by, target_commit) VALUES ('0.14.0', '0.12.0', 'x', ?)").use {
                            it.setString(1, bad)
                            it.executeUpdate()
                        }
                    }
                }
                // signature_status, status and the commit of a stored request are the root helper's: the backend cannot write them.
                assertFailsWith<SQLException> { db.run("UPDATE core_update_requests SET signature_status = 'verified'") }
                assertFailsWith<SQLException> { db.run("UPDATE core_update_requests SET target_commit = '${"f".repeat(40)}'") }
                assertFailsWith<SQLException> {
                    db.run("INSERT INTO core_update_requests (target_version, from_version, requested_by, signature_status) VALUES ('0.14.0', '0.12.0', 'x', 'verified')")
                }
            }
            DriverManager.getConnection(urlOf(database)).use { db ->
                assertFailsWith<SQLException> { db.run("UPDATE core_update_requests SET signature_status = 'trusted'") }
                db.run("UPDATE core_update_requests SET signature_status = 'unsigned', status = 'done', finished_at = now()")
            }
            assertEquals("unsigned", JdbcCoreUpdateStore(asBackend()).latest()!!.signatureStatus)
            // The audit row carries the commit.
            DriverManager.getConnection(urlOf(database)).use { db ->
                db.createStatement().use { st ->
                    st.executeQuery("SELECT details->>'commit' FROM provisioning_audit_log WHERE action = 'server.core_update_requested'").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(sha, rows.getString(1))
                    }
                }
            }
        } finally {
            DriverManager.getConnection(url).use { admin ->
                admin.run("DROP DATABASE IF EXISTS $database WITH (FORCE)")
                admin.run("DROP ROLE IF EXISTS $backend")
            }
        }
    }
}
