package com.ideasdeveloper.bms.server.i18n

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs the request language (I18N.md §2, §7): every call runs inside [I18n.contextElement] with the
 * language of its session when Core already knows it (see [SessionLanguages]), else Accept-Language,
 * else [I18n.Source]. When the session is resolved later in the request (ModuleAccessService), the
 * stored user language replaces it. Modules never read the header.
 */
fun Application.installI18n() {
    I18n.loadCore()
    intercept(ApplicationCallPipeline.Setup) {
        withContext(I18n.contextElement(requestLanguage(context))) {
            proceed()
        }
    }
}

/** Language for [call] before its session is validated: the stored language of its session, else Accept-Language. */
fun requestLanguage(call: ApplicationCall): String? {
    val token = call.request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: call.request.cookies[ModuleTokenCookie]
    return token?.let { SessionLanguages.forToken(it) } ?: I18n.normalize(call.request.headers[HttpHeaders.AcceptLanguage])
}

// Same name as ModuleAccessService.TokenCookie (web module pages); kept here to avoid a dependency cycle.
private const val ModuleTokenCookie = "bms_module_token"

/**
 * Stored languages of signed-in users, in memory, so the language of a request is known without a
 * database query: filled at sign-in, when the user changes it and when a session is validated
 * (ModuleAccessService). An entry expires after [TtlMillis] and is read again from the database.
 *
 * A user is identified by "<scope>/<userId>", where scope is the company code or [ServerScope].
 */
object SessionLanguages {
    const val ServerScope = "server"
    private const val TtlMillis = 10 * 60_000L
    private const val MaxEntries = 50_000

    private class UserEntry(val language: String?, val at: Long)

    private val users = ConcurrentHashMap<String, UserEntry>()
    // Hash of the session token -> user key.
    private val sessions = ConcurrentHashMap<String, String>()

    fun userKey(scope: String?, userId: UUID): String = "${scope ?: ServerScope}/$userId"

    /** Remembers the stored language (null = follows the company) of a user. */
    fun rememberUser(userKey: String, language: String?) {
        if (users.size >= MaxEntries) users.clear()
        users[userKey] = UserEntry(I18n.normalize(language), System.currentTimeMillis())
    }

    /** Remembers that [token] belongs to [userKey]. */
    fun rememberSession(token: String, userKey: String) {
        if (sessions.size >= MaxEntries) sessions.clear()
        sessions[hash(token)] = userKey
    }

    /** The stored language of the user behind [token] when it is known and fresh; null otherwise. */
    fun forToken(token: String): String? {
        val key = sessions[hash(token)] ?: return null
        return fresh(key)?.language
    }

    /**
     * The stored language of [userKey]: from memory when fresh, else [load] (a database read) which
     * is remembered. A failing read (an older schema without the column) counts as no language.
     */
    fun resolve(userKey: String, load: () -> String?): String? {
        fresh(userKey)?.let { return it.language }
        val language = try {
            load()
        } catch (_: SQLException) {
            null
        }
        rememberUser(userKey, language)
        return I18n.normalize(language)
    }

    /** Forgets everything (tests). */
    fun clear() {
        users.clear()
        sessions.clear()
    }

    private fun fresh(key: String): UserEntry? =
        users[key]?.takeIf { System.currentTimeMillis() - it.at < TtlMillis }

    private fun hash(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
}

/**
 * Stored language of a user in [connection] (`application_users.language`, core V006): "es", "en" or
 * null when the user follows the company default. Null too before the migration ran.
 */
fun userLanguage(connection: Connection, userId: UUID): String? = guarded(connection) {
    connection.prepareStatement("SELECT language FROM application_users WHERE id = ?").use { query ->
        query.setObject(1, userId)
        query.executeQuery().use { rows -> if (rows.next()) I18n.normalize(rows.getString(1)) else null }
    }
}

/** Default language of a company (`companies.default_language`, control V014); [I18n.Source] before the migration. */
fun companyLanguage(central: Connection, companyCode: String): String = guarded(central) {
    central.prepareStatement("SELECT default_language FROM companies WHERE code = ?").use { query ->
        query.setString(1, companyCode)
        query.executeQuery().use { rows -> if (rows.next()) I18n.normalize(rows.getString(1)) else null }
    }
} ?: I18n.Source

/** Sets the default language of a company; false when the company does not exist. Throws for an unsupported language. */
fun setCompanyLanguage(central: Connection, companyCode: String, language: String): Boolean {
    val normalized = language.trim().lowercase().takeIf { it in I18n.Languages }
        ?: throw IllegalArgumentException("Unsupported language: $language")
    val changed = central.prepareStatement("UPDATE companies SET default_language = ? WHERE code = ?").use { update ->
        update.setString(1, normalized)
        update.setString(2, companyCode)
        update.executeUpdate() > 0
    }
    CompanyLanguages.forget(companyCode)
    return changed
}

/** Company default languages in memory (short TTL), read with [companyLanguage]. */
object CompanyLanguages {
    private const val TtlMillis = 5 * 60_000L
    private class Entry(val language: String, val at: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun resolve(companyCode: String, load: () -> String): String {
        entries[companyCode]?.takeIf { System.currentTimeMillis() - it.at < TtlMillis }?.let { return it.language }
        val language = try {
            load()
        } catch (_: SQLException) {
            I18n.Source
        }
        if (entries.size >= 10_000) entries.clear()
        entries[companyCode] = Entry(language, System.currentTimeMillis())
        return language
    }

    fun forget(companyCode: String) {
        entries.remove(companyCode)
    }

    fun clear() = entries.clear()
}

/**
 * Runs a read that may fail on an older schema (a column a migration adds); null on SQLException. Inside
 * a transaction it runs in a savepoint, so the failure never aborts the caller's transaction.
 */
internal fun <T> guarded(connection: Connection, read: () -> T?): T? {
    val savepoint = try {
        if (connection.autoCommit) null else connection.setSavepoint()
    } catch (_: Exception) {
        null
    }
    return try {
        read().also { savepoint?.let { runCatching { connection.releaseSavepoint(it) } } }
    } catch (_: SQLException) {
        savepoint?.let { runCatching { connection.rollback(it) } }
        null
    }
}
