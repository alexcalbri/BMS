package com.ideasdeveloper.bms.server.i18n

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class I18nTest {
    init {
        I18n.register("i18n-test", "en", mapOf("Hola {0}, tienes {1} tareas" to "Hi {0}, you have {1} tasks", "Prueba de idioma" to "Language test"))
    }

    @Test
    fun normalizeReadsAcceptLanguageLists() {
        assertEquals("en", I18n.normalize("en-US,en;q=0.9"))
        assertEquals("es", I18n.normalize("es-MX"))
        assertEquals("en", I18n.normalize("fr-FR, en;q=0.8, es;q=0.5"))
        assertEquals("es", I18n.normalize("en;q=0.3, es;q=0.9"))
        assertEquals("en", I18n.normalize("EN_us"))
        assertNull(I18n.normalize("fr, de"))
        assertNull(I18n.normalize(""))
        assertNull(I18n.normalize(null))
        assertNull(I18n.normalize("*"))
    }

    @Test
    fun sourceLanguageReturnsTheSourceWithPlaceholdersReplaced() {
        assertEquals("es", I18n.language())
        assertEquals("Hola Ana, tienes 3 tareas", tr("Hola {0}, tienes {1} tareas", "Ana", 3))
        assertEquals("Sin traducir", tr("Sin traducir"))
        // Without arguments the braces stay as they are.
        assertEquals("Hola {0}, tienes {1} tareas", tr("Hola {0}, tienes {1} tareas"))
        // null is written like a string template would: texts converted from "$x" stay identical.
        assertEquals("Nada para null", tr("Nada para {0}", null))
    }

    @Test
    fun otherLanguagesUseTheCatalogAndFallBackToTheSource() {
        I18n.withLanguage("en") {
            assertEquals("en", I18n.language())
            assertEquals("Hi Ana, you have 3 tasks", tr("Hola {0}, tienes {1} tareas", "Ana", 3))
            // Missing entry: the Spanish source, never a key.
            assertEquals("Texto sin entrada 7", tr("Texto sin entrada {0}", 7))
            // The Core catalog is loaded at startup.
            assertEquals("The session is not valid.", tr("La sesion no es valida."))
        }
        assertEquals("es", I18n.language())
        assertEquals("Prueba de idioma", tr("Prueba de idioma"))
        assertEquals("Prueba de idioma", msg("Prueba de idioma"))
    }

    @Test
    fun withLanguageNestsAndRestores() {
        I18n.withLanguage("en") {
            I18n.withLanguage("es") { assertEquals("Prueba de idioma", tr("Prueba de idioma")) }
            assertEquals("Language test", tr("Prueba de idioma"))
            I18n.withLanguage("xx") { assertEquals("es", I18n.language()) }
        }
        assertEquals("es", I18n.language())
    }

    @Test
    fun coreCatalogWinsOverModulesAndModulesKeepRegistrationOrder() {
        I18n.register("i18n-order-a", "en", mapOf("Orden de prueba" to "First", "La sesion no es valida." to "Module text"))
        I18n.register("i18n-order-b", "en", mapOf("Orden de prueba" to "Second"))
        I18n.withLanguage("en") {
            assertEquals("First", tr("Orden de prueba"))
            assertEquals("The session is not valid.", tr("La sesion no es valida."))
        }
    }

    @Test
    fun languageSurvivesDispatcherAndThreadHops() = runBlocking {
        withContext(I18n.contextElement("en")) {
            assertEquals("Language test", tr("Prueba de idioma"))
            withContext(Dispatchers.IO) {
                assertEquals("en", I18n.language())
                delay(5)
                yield()
                assertEquals("Language test", tr("Prueba de idioma"))
            }
            val results = (1..20).map { async(Dispatchers.Default) { delay(1); I18n.language() } }.awaitAll()
            assertTrue(results.all { it == "en" })
            assertEquals("en", I18n.language())
        }
        // Outside the element the thread is back to the source language.
        assertEquals("es", I18n.language())
        // Two concurrent requests never see each other's language.
        val both = listOf("en", "es").map { language ->
            async(Dispatchers.Default) {
                withContext(I18n.contextElement(language)) {
                    repeat(20) { delay(1); check(I18n.language() == language) }
                    I18n.language()
                }
            }
        }.awaitAll()
        assertEquals(listOf("en", "es"), both)
    }

    @Test
    fun requestLanguageChangedInsideIoIsSeenAfterwards() = runBlocking {
        withContext(I18n.contextElement("es")) {
            // Like ModuleAccessService: the session is resolved on the IO dispatcher.
            withContext(Dispatchers.IO) { I18n.overrideRequestLanguage("en") }
            assertEquals("en", I18n.language())
            delay(2)
            withContext(Dispatchers.Default) { assertEquals("en", I18n.language()) }
            // Unknown languages do not change it.
            I18n.overrideRequestLanguage("xx")
            assertEquals("en", I18n.language())
        }
        // Without a request it does nothing.
        I18n.overrideRequestLanguage("en")
        assertEquals("es", I18n.language())
    }

    @Test
    fun loadFromReadsCatalogsThroughTheClassLoader() {
        val dir = Files.createTempDirectory("i18n-loader").toFile()
        File(dir, "modules/demo_i18n/i18n").mkdirs()
        File(dir, "modules/demo_i18n/i18n/en.json").writeText("""{"Texto del modulo demo": "Demo module text", "Vacio": "  "}""")
        URLClassLoader(arrayOf(dir.toURI().toURL()), null).use { loader ->
            I18n.loadFrom(loader, "modules/demo_i18n/i18n", "demo_i18n")
            // A malformed or missing catalog never fails.
            I18n.loadFrom(loader, "modules/missing/i18n", "missing")
        }
        I18n.withLanguage("en") {
            assertEquals("Demo module text", tr("Texto del modulo demo"))
            // Blank translations are ignored: the source is shown.
            assertEquals("Vacio", tr("Vacio"))
        }
        dir.deleteRecursively()
    }

    @Test
    fun malformedCatalogIsIgnored() {
        val dir = Files.createTempDirectory("i18n-bad").toFile()
        File(dir, "bad/i18n").mkdirs()
        File(dir, "bad/i18n/en.json").writeText("""["not", "an", "object"]""")
        URLClassLoader(arrayOf(dir.toURI().toURL()), null).use { loader -> I18n.loadFrom(loader, "bad/i18n", "bad") }
        dir.deleteRecursively()
    }
}
