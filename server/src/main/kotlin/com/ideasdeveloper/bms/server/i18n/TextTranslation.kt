package com.ideasdeveloper.bms.server.i18n

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A machine translation provider registered by a module (I18N.md §8), e.g. the CRM with its
 * CrmTranslate settings. Core calls it only for cache misses, in batches, off the request thread.
 */
fun interface TextTranslator {
    /**
     * Translates [texts] (plain text written by staff, never customer data) from [source] to [target]
     * for the company of [tenant] (an open connection to its database, to read the provider settings).
     * Returns the translations in the same order and size, or null when this company has no provider
     * or key configured (texts are then shown in the original). Throwing means a provider error: Core
     * logs it once and shows the originals.
     */
    fun translate(tenant: Connection, texts: List<String>, source: String, target: String): List<String>?
}

/** Rows of `text_translations` (tenant V010) by hash of the trimmed source text. */
interface TextTranslationStore {
    /** Hash -> translated text for [hashes] in [language]. */
    fun find(tenant: Connection, language: String, hashes: Collection<String>): Map<String, String>

    /** Stores provider translations (source -> translation); never overwrites a manual one. */
    fun saveAutomatic(tenant: Connection, language: String, translations: Map<String, String>)

    /** Stores a manual translation, which the provider never overwrites. */
    fun saveManual(tenant: Connection, language: String, source: String, translated: String)

    /** Removes a manual translation (the provider may fill it again later). */
    fun deleteManual(tenant: Connection, language: String, source: String)

    /** Manual translations of [hashes] in [language]: hash -> translated text. */
    fun findManual(tenant: Connection, language: String, hashes: Collection<String>): Map<String, String>
}

/**
 * Translation of short texts written by users (stage names, pipeline/board/category names, notes) into
 * the viewer's language, with a cache per company (I18N.md §8). Reach it through
 * `access.textTranslation` (ModuleAccessService).
 *
 * - A text is translated only when [target] differs from the company default language, which is the
 *   language texts are assumed to be written in (no detection).
 * - No provider registered, none configured for the company, a provider error or a missing cache table:
 *   the texts come back as they are, never an error.
 * - One cache query per call; only misses go to the provider, in one batch, in the background. The call
 *   waits at most [waitMillis] for them; what is not ready shows in the original and appears on the next load.
 * - Manual translations ([setManual]) win and are never overwritten by the provider.
 */
class TextTranslation(
    // Opens the company's database for the background fill (the request's own connection is not shared).
    private val openTenant: (companyCode: String) -> Connection?,
    // Default language of the company.
    private val companyLanguage: (companyCode: String) -> String,
    private val store: TextTranslationStore = JdbcTextTranslationStore,
    private val waitMillis: Long = DefaultWaitMillis,
    private val executor: ExecutorService = defaultExecutor(),
    // Longest a background fill may run before it is cancelled, so one hanging provider cannot hold the pool.
    private val taskBudgetMillis: Long = TaskBudgetMillis,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(TextTranslation::class.java)
    private val translators = CopyOnWriteArrayList<Pair<String, TextTranslator>>()
    // "company/language/hash" being translated now: not sent twice.
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    // company -> time of the last provider failure; the provider is not called again for FailureCooldownMillis.
    private val failures = ConcurrentHashMap<String, Long>()
    // company -> time when no provider had settings for it; no task is queued for NoProviderMillis.
    private val noProvider = ConcurrentHashMap<String, Long>()
    // Companies with a background fill running: at most one task per company, the others answer at once.
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val logged = ConcurrentHashMap.newKeySet<String>()

    /** Registers (or replaces, by [id]) a provider. Providers are asked in registration order. */
    fun registerTextTranslator(id: String, translator: TextTranslator) {
        translators.removeIf { it.first == id }
        translators.add(id to translator)
    }

    fun hasProvider(): Boolean = translators.isNotEmpty()

    /**
     * [texts] in [target] (default: the language of the request) for the company [companyCode] whose
     * database is [tenant]. Same size and order; blank texts and texts without translation come back as they are.
     */
    fun translate(companyCode: String, tenant: Connection, texts: List<String>, target: String = I18n.language()): List<String> {
        if (texts.isEmpty() || !needsTranslation(companyCode, target)) return texts
        val sources = texts.map { it.trim() }.filter { it.isNotEmpty() && it.length <= MaxTextLength }.distinct()
        if (sources.isEmpty()) return texts
        val hashes = sources.associateWith(::hash)
        val cached = try {
            store.find(tenant, target, hashes.values)
        } catch (failure: SQLException) {
            logOnce("cache:$companyCode", "Text translations of company {} not read (cache table missing?): {}", companyCode, failure.message)
            return texts
        }
        val found = HashMap<String, String>()
        sources.forEach { source -> cached[hashes.getValue(source)]?.let { found[source] = it } }
        val misses = sources.filter { it !in found }
        if (misses.isNotEmpty()) found.putAll(fill(companyCode, target, misses))
        return texts.map { text -> found[text.trim()] ?: text }
    }

    /** One text; see [translate]. */
    fun translate(companyCode: String, tenant: Connection, text: String, target: String = I18n.language()): String =
        translate(companyCode, tenant, listOf(text), target).first()

    /**
     * The translation to show under each note (UiTimelineEntry.translation), or null when there is none
     * (same language, no provider, not ready yet, or equal to the original).
     */
    fun noteTranslations(companyCode: String, tenant: Connection, notes: List<String>, target: String = I18n.language()): List<String?> {
        val translated = translate(companyCode, tenant, notes, target)
        return notes.indices.map { index -> translated[index].takeIf { it.trim() != notes[index].trim() } }
    }

    /** Stores a manual translation of [source] into [language] (e.g. from the stage editor). */
    fun setManual(tenant: Connection, source: String, language: String, translated: String) {
        val text = source.trim()
        require(text.isNotEmpty() && translated.isNotBlank()) { "Source and translation are required" }
        require(language in I18n.Languages) { "Unsupported language: $language" }
        store.saveManual(tenant, language, text, translated.trim())
    }

    /** Removes the manual translation of [source] into [language]. */
    fun removeManual(tenant: Connection, source: String, language: String) {
        store.deleteManual(tenant, language, source.trim())
    }

    /** Manual translations of [sources] into [language] (source -> translation), for an editor. */
    fun manualTranslations(tenant: Connection, sources: List<String>, language: String): Map<String, String> {
        val hashes = sources.map { it.trim() }.filter { it.isNotEmpty() }.distinct().associateBy(::hash)
        if (hashes.isEmpty()) return emptyMap()
        val rows = try {
            store.findManual(tenant, language, hashes.keys)
        } catch (failure: SQLException) {
            return emptyMap()
        }
        return rows.mapNotNull { (hash, translated) -> hashes[hash]?.let { it to translated } }.toMap()
    }

    private fun needsTranslation(companyCode: String, target: String): Boolean {
        if (target !in I18n.Languages) return false
        val default = try {
            companyLanguage(companyCode)
        } catch (failure: Exception) {
            I18n.Source
        }
        return target != default
    }

    // Asks the providers for [misses] in the background and waits a little for the answer.
    private fun fill(companyCode: String, target: String, misses: List<String>): Map<String, String> {
        if (translators.isEmpty()) return emptyMap()
        val lastFailure = failures[companyCode]
        if (lastFailure != null && clock() - lastFailure < FailureCooldownMillis) return emptyMap()
        val lastNone = noProvider[companyCode]
        if (lastNone != null) {
            if (clock() - lastNone < NoProviderMillis) return emptyMap()
            noProvider.remove(companyCode, lastNone)
        }
        // One fill per company at a time; a screen never waits behind the company's running task.
        if (!busy.add(companyCode)) return emptyMap()
        val keys = misses.associateWith { "$companyCode/$target/${hash(it)}" }
        val batch = misses.filter { inFlight.add(keys.getValue(it)) }
        if (batch.isEmpty()) {
            busy.remove(companyCode)
            return emptyMap()
        }
        val source = try {
            companyLanguage(companyCode)
        } catch (_: Exception) {
            I18n.Source
        }
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val release = {
            if (released.compareAndSet(false, true)) {
                batch.forEach { inFlight.remove(keys.getValue(it)) }
                busy.remove(companyCode)
            }
        }
        val future: Future<Map<String, String>> = try {
            executor.submit<Map<String, String>> {
                try {
                    providerFill(companyCode, source, target, batch)
                } finally {
                    release()
                }
            }
        } catch (failure: Exception) {
            release()
            logOnce("submit", "Text translation for company {} not started: {}", companyCode, failure.message)
            return emptyMap()
        }
        // A task that overruns its budget is cancelled (interrupted) and its company rests like after a failure.
        if (taskBudgetMillis > 0) {
            Watchdog.schedule({
                if (!future.isDone) {
                    future.cancel(true)
                    failures[companyCode] = clock()
                    release()
                    logOnce("budget:$companyCode", "Text translation for company {} cancelled after {} ms", companyCode, taskBudgetMillis)
                }
            }, taskBudgetMillis, TimeUnit.MILLISECONDS)
        }
        return try {
            future.get(waitMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            emptyMap()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun providerFill(companyCode: String, source: String, target: String, texts: List<String>): Map<String, String> {
        val connection = try {
            openTenant(companyCode)
        } catch (failure: Exception) {
            logOnce("open:$companyCode", "Text translation: database of company {} not opened: {}", companyCode, failure.message)
            null
        } ?: return emptyMap()
        return connection.use { tenant ->
            val result = HashMap<String, String>()
            for (chunk in texts.chunked(MaxBatch)) {
                val translated = ask(companyCode, tenant, chunk, source, target) ?: break
                chunk.indices.forEach { index ->
                    val text = translated[index].trim()
                    // A blank answer is cached as the source text so it is not sent again.
                    result[chunk[index]] = text.ifEmpty { chunk[index] }
                }
            }
            if (result.isNotEmpty()) {
                try {
                    store.saveAutomatic(tenant, target, result)
                } catch (failure: SQLException) {
                    logOnce("save:$companyCode", "Text translations of company {} not stored: {}", companyCode, failure.message)
                }
            }
            result
        }
    }

    // The first provider configured for the company; null when none is, or on a provider error.
    private fun ask(companyCode: String, tenant: Connection, texts: List<String>, source: String, target: String): List<String>? {
        var answered = false
        for ((id, translator) in translators) {
            val translated = try {
                translator.translate(tenant, texts, source, target)
            } catch (failure: Exception) {
                failures[companyCode] = clock()
                logOnce("provider:$id:$companyCode", "Text translation provider {} failed for company {}: {}", id, companyCode, failure.message)
                return null
            } ?: continue
            answered = true
            if (translated.size != texts.size) {
                logOnce("size:$id", "Text translation provider {} answered {} texts for {}", id, translated.size, texts.size)
                return null
            }
            failures.remove(companyCode)
            return translated
        }
        // Every provider answered null: this company has none configured.
        if (!answered) noProvider[companyCode] = clock()
        return null
    }

    private fun logOnce(key: String, message: String, vararg args: Any?) {
        if (logged.size > 10_000) logged.clear()
        if (logged.add(key)) log.warn(message, *args)
    }

    companion object {
        const val DefaultWaitMillis = 800L
        const val FailureCooldownMillis = 5 * 60_000L
        const val NoProviderMillis = 5 * 60_000L
        const val TaskBudgetMillis = 30_000L
        const val MaxBatch = 50
        // Longer texts are shown in the original.
        const val MaxTextLength = 5_000

        /** SHA-256 (hex) of the trimmed text: the key of text_translations. */
        fun hash(text: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray(Charsets.UTF_8)))

        // Two workers and a short queue: when full, submit is rejected and the screen shows the originals.
        private fun defaultExecutor(): ExecutorService = ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
            { runnable -> Thread(runnable, "text-translation").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

        private val Watchdog = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "text-translation-watchdog").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

/** [TextTranslationStore] on the tenant table text_translations (tenant V010). */
object JdbcTextTranslationStore : TextTranslationStore {
    override fun find(tenant: Connection, language: String, hashes: Collection<String>): Map<String, String> {
        if (hashes.isEmpty()) return emptyMap()
        return guardedTransaction(tenant) {
            tenant.prepareStatement("SELECT hash, translated_text FROM text_translations WHERE language = ? AND hash = ANY(?)").use { query ->
                query.setString(1, language)
                query.setArray(2, tenant.createArrayOf("text", hashes.toTypedArray()))
                query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) } }
            }
        }
    }

    override fun findManual(tenant: Connection, language: String, hashes: Collection<String>): Map<String, String> {
        if (hashes.isEmpty()) return emptyMap()
        return guardedTransaction(tenant) {
            tenant.prepareStatement("SELECT hash, translated_text FROM text_translations WHERE language = ? AND manual AND hash = ANY(?)").use { query ->
                query.setString(1, language)
                query.setArray(2, tenant.createArrayOf("text", hashes.toTypedArray()))
                query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) } }
            }
        }
    }

    override fun saveAutomatic(tenant: Connection, language: String, translations: Map<String, String>) {
        tenant.prepareStatement(
            """
            INSERT INTO text_translations (hash, language, source_text, translated_text, manual, updated_at)
            VALUES (?, ?, ?, ?, FALSE, ?)
            ON CONFLICT (hash, language) DO UPDATE SET source_text = EXCLUDED.source_text, translated_text = EXCLUDED.translated_text,
                updated_at = EXCLUDED.updated_at
            WHERE NOT text_translations.manual
            """.trimIndent()
        ).use { upsert ->
            translations.forEach { (source, translated) ->
                upsert.setString(1, TextTranslation.hash(source))
                upsert.setString(2, language)
                upsert.setString(3, source)
                upsert.setString(4, translated)
                upsert.setTimestamp(5, Timestamp.from(Instant.now()))
                upsert.addBatch()
            }
            upsert.executeBatch()
        }
    }

    override fun saveManual(tenant: Connection, language: String, source: String, translated: String) {
        tenant.prepareStatement(
            """
            INSERT INTO text_translations (hash, language, source_text, translated_text, manual, updated_at)
            VALUES (?, ?, ?, ?, TRUE, ?)
            ON CONFLICT (hash, language) DO UPDATE SET source_text = EXCLUDED.source_text, translated_text = EXCLUDED.translated_text,
                manual = TRUE, updated_at = EXCLUDED.updated_at
            """.trimIndent()
        ).use { upsert ->
            upsert.setString(1, TextTranslation.hash(source))
            upsert.setString(2, language)
            upsert.setString(3, source)
            upsert.setString(4, translated)
            upsert.setTimestamp(5, Timestamp.from(Instant.now()))
            upsert.executeUpdate()
        }
    }

    override fun deleteManual(tenant: Connection, language: String, source: String) {
        tenant.prepareStatement("DELETE FROM text_translations WHERE hash = ? AND language = ? AND manual").use { delete ->
            delete.setString(1, TextTranslation.hash(source))
            delete.setString(2, language)
            delete.executeUpdate()
        }
    }

    // A screen's read must never abort the module's transaction: inside one it runs in a savepoint.
    private fun <T> guardedTransaction(tenant: Connection, read: () -> T): T {
        if (tenant.autoCommit) return read()
        val savepoint = tenant.setSavepoint()
        try {
            return read().also { runCatching { tenant.releaseSavepoint(savepoint) } }
        } catch (failure: SQLException) {
            runCatching { tenant.rollback(savepoint) }
            throw failure
        }
    }
}
