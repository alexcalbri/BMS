package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * A disposable company database on a real PostgreSQL for the activity-log tests of the CRM and its extensions: Core's tenant
 * migrations (with the activity log), Clientes, the CRM and the migrations of the extension under test. Only with
 * BMS_CRM_ACTIVITY_PG_URL (or BMS_ACTIVITY_PG_URL, BMS_SERVER_ADMINS_PG_URL), jdbc:postgresql://host:port/postgres?user=postgres of a DISPOSABLE
 * server: [open] drops and creates the database it is given.
 */
object CrmTestDatabase {
    val url: String? = (System.getenv("BMS_CRM_ACTIVITY_PG_URL") ?: System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()

    private fun urlOf(name: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$name$1")

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")

    private fun sorted(directory: String) = File(root, directory).list()!!.filter { it.endsWith(".sql") }.sorted().map { "$directory/$it" }

    /** A fresh database [name] with the migrations of [extensions] (folders under extensions/) and the user [personId]; null without PostgreSQL. */
    fun open(name: String, personId: UUID, personName: String, extensions: List<String> = emptyList()): Connection? {
        if (url == null) return null
        DriverManager.getConnection(url).use { admin ->
            admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") }
            admin.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        val connection = DriverManager.getConnection(urlOf(name))
        val files = sorted("database/core/migrations") + sorted("database/tenant/migrations") + sorted("modules/clientes/migrations") +
            sorted("modules/crm/migrations") + extensions.flatMap { sorted("extensions/$it/migrations") }
        files.forEach { path -> connection.createStatement().use { it.execute(migration(path)) } }
        connection.createStatement().use {
            it.execute("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$personId', 'ana', TRUE, '$personName', 'ana@example.com')")
        }
        return connection
    }

    fun scalar(connection: Connection, sql: String): String? = connection.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
}
