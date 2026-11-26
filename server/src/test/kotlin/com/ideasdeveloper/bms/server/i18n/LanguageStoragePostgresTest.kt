package com.ideasdeveloper.bms.server.i18n

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real SQL of the language columns (core V006, control V014) and of the translation cache (tenant V010)
 * against a test PostgreSQL. Runs only with BMS_I18N_PG_URL (jdbc:postgresql://host/db?user=...&password=...)
 * pointing to an EMPTY, disposable database: it creates and drops tables.
 */
class LanguageStoragePostgresTest {
    private val url: String? = System.getenv("BMS_I18N_PG_URL")?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")

    private fun withDb(block: (Connection) -> Unit) {
        val target = url ?: return
        DriverManager.getConnection(target).use { db ->
            db.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS text_translations, server_owners, companies, application_users")
                st.execute(migration("database/core/migrations/V001__create_application_users.sql"))
                st.execute(migration("database/control/migrations/V001__create_server_registry.sql"))
            }
            try {
                block(db)
            } finally {
                db.createStatement().use { it.execute("DROP TABLE IF EXISTS text_translations, server_owners, companies, application_users") }
            }
        }
    }

    private fun Connection.migrate() = createStatement().use { st ->
        st.execute(migration("database/core/migrations/V006__add_application_user_language.sql"))
        st.execute(migration("database/control/migrations/V014__add_company_default_language.sql"))
        st.execute(migration("database/tenant/migrations/V010__create_text_translations.sql"))
    }

    private fun Connection.user(language: String?): UUID {
        val id = UUID.randomUUID()
        prepareStatement("INSERT INTO application_users (id, postgres_role, is_active, language) VALUES (?, ?, TRUE, ?)").use { insert ->
            insert.setObject(1, id)
            insert.setString(2, "u" + id.toString().take(8))
            insert.setString(3, language)
            insert.executeUpdate()
        }
        return id
    }

    @Test
    fun beforeTheMigrationsEverythingFallsBackToTheSource() = withDb { db ->
        val id = UUID.randomUUID()
        assertNull(userLanguage(db, id))
        assertEquals("es", companyLanguage(db, "acme"))
        // Inside a transaction the failed read does not abort the caller's work.
        db.autoCommit = false
        db.createStatement().use { it.execute("INSERT INTO application_users (id, postgres_role) VALUES ('${id}', 'antes')") }
        assertNull(userLanguage(db, id))
        db.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM application_users").use { it.next(); assertEquals(1, it.getInt(1)) } }
        db.rollback()
        db.autoCommit = true
    }

    @Test
    fun userAndCompanyLanguagesAreStored() = withDb { db ->
        db.migrate()
        val english = db.user("en")
        val follows = db.user(null)
        assertEquals("en", userLanguage(db, english))
        assertNull(userLanguage(db, follows))
        assertFailsWith<SQLException> { db.user("english") }
        db.createStatement().use { it.execute("INSERT INTO companies (id, code, name, database_name) VALUES (gen_random_uuid(), 'acme', 'Acme', 'bms_acme')") }
        assertEquals("es", companyLanguage(db, "acme"))
        assertTrue(setCompanyLanguage(db, "acme", "en"))
        assertEquals("en", companyLanguage(db, "acme"))
        assertFalse(setCompanyLanguage(db, "nadie", "en"))
        assertFailsWith<IllegalArgumentException> { setCompanyLanguage(db, "acme", "fr") }
    }

    @Test
    fun translationCacheKeepsManualRows() = withDb { db ->
        db.migrate()
        val store = JdbcTextTranslationStore
        store.saveAutomatic(db, "en", mapOf("Nuevo" to "New", "Ganado" to "Won"))
        store.saveManual(db, "en", "Ganado", "Closed won")
        store.saveAutomatic(db, "en", mapOf("Ganado" to "Won again", "Nuevo" to "New lead"))
        val hashes = listOf("Nuevo", "Ganado", "Perdido").map(TextTranslation::hash)
        assertEquals(
            mapOf(TextTranslation.hash("Nuevo") to "New lead", TextTranslation.hash("Ganado") to "Closed won"),
            store.find(db, "en", hashes),
        )
        assertEquals(mapOf(TextTranslation.hash("Ganado") to "Closed won"), store.findManual(db, "en", hashes))
        store.deleteManual(db, "en", "Ganado")
        assertEquals(setOf(TextTranslation.hash("Nuevo")), store.find(db, "en", hashes).keys)
        assertEquals(emptyMap(), store.find(db, "es", hashes))
    }

    @Test
    fun notesKeepNoSourceText() = withDb { db ->
        db.migrate()
        val store = JdbcTextTranslationStore
        store.saveAutomatic(db, "en", mapOf("Llamar al cliente Juan" to "Call customer Juan"), keepSource = false)
        store.saveAutomatic(db, "en", mapOf("Nuevo" to "New"))
        db.createStatement().use { st ->
            st.executeQuery("SELECT hash, source_text FROM text_translations").use { rows ->
                val stored = buildMap { while (rows.next()) put(rows.getString(1).trim(), rows.getString(2)) }
                assertEquals("", stored.getValue(TextTranslation.hash("Llamar al cliente Juan")))
                assertEquals("Nuevo", stored.getValue(TextTranslation.hash("Nuevo")))
            }
        }
    }
}
