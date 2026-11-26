package com.ideasdeveloper.bms.server.i18n

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextTranslationTest {
    // The memory store does not use the connection.
    private val connection: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> null } as Connection

    private class MemoryStore : TextTranslationStore {
        // (hash, language) -> translation, manual
        val rows = ConcurrentHashMap<Pair<String, String>, Pair<String, Boolean>>()
        var finds = 0
        var failing = false
        override fun find(tenant: Connection, language: String, hashes: Collection<String>): Map<String, String> {
            if (failing) throw SQLException("relation \"text_translations\" does not exist")
            finds++
            return hashes.mapNotNull { hash -> rows[hash to language]?.let { hash to it.first } }.toMap()
        }
        override fun saveAutomatic(tenant: Connection, language: String, translations: Map<String, String>) {
            translations.forEach { (source, translated) ->
                val key = TextTranslation.hash(source) to language
                if (rows[key]?.second != true) rows[key] = translated to false
            }
        }
        override fun saveManual(tenant: Connection, language: String, source: String, translated: String) {
            rows[TextTranslation.hash(source) to language] = translated to true
        }
        override fun deleteManual(tenant: Connection, language: String, source: String) {
            val key = TextTranslation.hash(source) to language
            if (rows[key]?.second == true) rows.remove(key)
        }
        override fun findManual(tenant: Connection, language: String, hashes: Collection<String>): Map<String, String> =
            if (failing) throw SQLException("relation \"text_translations\" does not exist") else hashes.mapNotNull { hash -> rows[hash to language]?.takeIf { it.second }?.let { hash to it.first } }.toMap()
    }

    private class CountingTranslator(private val answer: (String) -> String = { "EN:$it" }) : TextTranslator {
        val calls = mutableListOf<List<String>>()
        var sources = mutableListOf<String>()
        override fun translate(tenant: Connection, texts: List<String>, source: String, target: String): List<String> {
            synchronized(this) {
                calls += texts
                sources += "$source>$target"
            }
            return texts.map(answer)
        }
    }

    private val store = MemoryStore()

    private fun service(defaultLanguage: String = "es", waitMillis: Long = 2_000, budget: Long = 30_000, clock: () -> Long = System::currentTimeMillis) = TextTranslation(
        openTenant = { connection },
        companyLanguage = { defaultLanguage },
        store = store,
        waitMillis = waitMillis,
        executor = Executors.newFixedThreadPool(2),
        taskBudgetMillis = budget,
        clock = clock,
    )

    @Test
    fun withoutProviderTextsComeBackAsTheyAre() {
        val translation = service()
        assertEquals(listOf("Nuevo", "Ganado"), translation.translate("acme", connection, listOf("Nuevo", "Ganado"), "en"))
        assertEquals(listOf(null, null), translation.noteTranslations("acme", connection, listOf("Llamar", "Hola"), "en"))
    }

    @Test
    fun sameLanguageAsTheCompanyIsNeverSent() {
        val translation = service(defaultLanguage = "en")
        val provider = CountingTranslator()
        translation.registerTextTranslator("crm", provider)
        assertEquals(listOf("New"), translation.translate("acme", connection, listOf("New"), "en"))
        assertEquals(listOf("Nuevo"), service().translate("acme", connection, listOf("Nuevo"), "es"))
        assertTrue(provider.calls.isEmpty())
        assertEquals(0, store.finds)
    }

    @Test
    fun missesGoToTheProviderInOneBatchAndAreCached() {
        val translation = service()
        val provider = CountingTranslator()
        translation.registerTextTranslator("crm", provider)
        val texts = listOf("Nuevo", " Ganado ", "Nuevo", "", "Perdido")
        assertEquals(listOf("EN:Nuevo", "EN:Ganado", "EN:Nuevo", "", "EN:Perdido"), translation.translate("acme", connection, texts, "en"))
        assertEquals(listOf(listOf("Nuevo", "Ganado", "Perdido")), provider.calls)
        assertEquals(listOf("es>en"), provider.sources)
        // Second screen: one cache query, no provider call.
        assertEquals(listOf("EN:Nuevo", "EN:Ganado"), translation.translate("acme", connection, listOf("Nuevo", "Ganado"), "en"))
        assertEquals(1, provider.calls.size)
        assertEquals(2, store.finds)
    }

    @Test
    fun theRequestLanguageIsTheDefaultTarget() {
        val translation = service()
        translation.registerTextTranslator("crm", CountingTranslator())
        assertEquals("Nuevo", translation.translate("acme", connection, "Nuevo"))
        assertEquals("EN:Nuevo", I18n.withLanguage("en") { translation.translate("acme", connection, "Nuevo") })
    }

    @Test
    fun aSlowProviderDoesNotHoldTheScreenAndFillsTheCacheInTheBackground() {
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val translation = service(waitMillis = 50)
        translation.registerTextTranslator("slow") { _, texts, _, _ ->
            release.await(5, TimeUnit.SECONDS)
            texts.map { "EN:$it" }.also { done.countDown() }
        }
        val started = System.nanoTime()
        assertEquals(listOf("Nota larga"), translation.translate("acme", connection, listOf("Nota larga"), "en"))
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        // Stored by the background job (give it a moment to save).
        repeat(50) { if (store.rows.isEmpty()) Thread.sleep(20) }
        assertEquals(listOf("EN:Nota larga"), translation.translate("acme", connection, listOf("Nota larga"), "en"))
    }

    @Test
    fun manualTranslationsWinAndAreNeverOverwritten() {
        val translation = service()
        val provider = CountingTranslator()
        translation.registerTextTranslator("crm", provider)
        translation.setManual(connection, "Ganado", "en", "Closed won")
        assertEquals(listOf("Closed won", "EN:Nuevo"), translation.translate("acme", connection, listOf("Ganado", "Nuevo"), "en"))
        assertEquals(listOf(listOf("Nuevo")), provider.calls)
        store.saveAutomatic(connection, "en", mapOf("Ganado" to "Won"))
        assertEquals("Closed won", translation.translate("acme", connection, "Ganado", "en"))
        assertEquals(mapOf("Ganado" to "Closed won"), translation.manualTranslations(connection, listOf("Ganado", "Nuevo"), "en"))
        translation.removeManual(connection, "Ganado", "en")
        assertEquals(emptyMap(), translation.manualTranslations(connection, listOf("Ganado"), "en"))
    }

    @Test
    fun providerErrorsAndMissingTablesNeverBreakAScreen() {
        val translation = service()
        var calls = 0
        translation.registerTextTranslator("broken") { _, _, _, _ -> calls++; throw IllegalStateException("HTTP 500") }
        assertEquals(listOf("Nuevo"), translation.translate("acme", connection, listOf("Nuevo"), "en"))
        // After a failure the provider rests for a while instead of being called on every screen.
        assertEquals(listOf("Ganado"), translation.translate("acme", connection, listOf("Ganado"), "en"))
        assertEquals(1, calls)
        store.failing = true
        assertEquals(listOf("Nuevo"), translation.translate("acme", connection, listOf("Nuevo"), "en"))
    }

    @Test
    fun aProviderWithoutSettingsForTheCompanyLetsTheNextOneTry() {
        val translation = service()
        translation.registerTextTranslator("unconfigured") { _, _, _, _ -> null }
        translation.registerTextTranslator("second", CountingTranslator { "2:$it" })
        assertEquals(listOf("2:Nuevo"), translation.translate("acme", connection, listOf("Nuevo"), "en"))
        // Re-registering by id replaces the provider.
        translation.registerTextTranslator("second", CountingTranslator { "3:$it" })
        assertEquals(listOf("3:Otro"), translation.translate("acme", connection, listOf("Otro"), "en"))
    }

    @Test
    fun noteTranslationsAreNullWhenEqual() {
        val translation = service()
        translation.registerTextTranslator("crm", CountingTranslator { if (it == "OK") it else "EN:$it" })
        assertEquals(listOf("EN:Llamar", null), translation.noteTranslations("acme", connection, listOf("Llamar", "OK"), "en"))
        assertNull(translation.noteTranslations("acme", connection, listOf("Llamar"), "es").single())
    }

    @Test
    fun hashIsTheSha256OfTheTrimmedText() {
        assertEquals(TextTranslation.hash("Nuevo"), TextTranslation.hash("  Nuevo \n"))
        assertEquals(64, TextTranslation.hash("x").length)
    }

    @Test
    fun aCompanyWithoutProviderSettingsIsRememberedForAWhile() {
        var now = 0L
        val translation = service(clock = { now })
        var calls = 0
        translation.registerTextTranslator("unconfigured") { _, _, _, _ -> calls++; null }
        assertEquals(listOf("Nuevo"), translation.translate("acme", connection, listOf("Nuevo"), "en"))
        assertEquals(1, calls)
        assertEquals(listOf("Otro"), translation.translate("acme", connection, listOf("Otro"), "en"))
        assertEquals(1, calls)
        now += TextTranslation.NoProviderMillis + 1
        translation.translate("acme", connection, listOf("Otro"), "en")
        assertEquals(2, calls)
    }

    @Test
    fun blankProviderAnswersAreCachedAsTheSourceText() {
        val translation = service()
        val provider = CountingTranslator { "" }
        translation.registerTextTranslator("crm", provider)
        assertEquals(listOf("Nuevo"), translation.translate("acme", connection, listOf("Nuevo"), "en"))
        repeat(50) { if (store.rows.isEmpty()) Thread.sleep(20) }
        assertEquals(listOf("Nuevo"), translation.translate("acme", connection, listOf("Nuevo"), "en"))
        assertEquals(1, provider.calls.size)
    }

    @Test
    fun onlyOneFillPerCompanyRunsAndOthersDoNotWait() {
        val release = CountDownLatch(1)
        val translation = service(waitMillis = 100)
        var calls = 0
        translation.registerTextTranslator("slow") { _, texts, _, _ ->
            calls++
            release.await(5, TimeUnit.SECONDS)
            texts
        }
        translation.translate("acme", connection, listOf("Uno"), "en")
        val started = System.nanoTime()
        repeat(5) { translation.translate("acme", connection, listOf("Otro $it"), "en") }
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(80))
        release.countDown()
        assertEquals(1, calls)
    }

    @Test
    fun aHangingProviderIsCancelledAfterItsBudget() {
        val interrupted = CountDownLatch(1)
        val translation = service(waitMillis = 20, budget = 100)
        translation.registerTextTranslator("hang") { _, texts, _, _ ->
            try {
                Thread.sleep(10_000)
            } catch (_: InterruptedException) {
                interrupted.countDown()
            }
            texts
        }
        translation.translate("acme", connection, listOf("Uno"), "en")
        assertTrue(interrupted.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun manualTranslationsOfAMissingTableAreEmpty() {
        store.failing = true
        assertEquals(emptyMap(), service().manualTranslations(connection, listOf("Nuevo"), "en"))
    }
}
