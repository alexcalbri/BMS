package com.ideasdeveloper.bms.server.i18n

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreCatalogTest {
    @Test
    fun coreCatalogIsComplete() {
        val root = I18nCatalogCheck.repoRoot()
        I18nCatalogCheck.assertComplete(
            sourceDirs = listOf(File(root, "server/src/main/kotlin")),
            catalog = File(root, "server/src/main/resources/i18n/en.json"),
        )
    }

    @Test
    fun checkReportsMissingUnusedPlaceholdersAndProblems() {
        val dir = Files.createTempDirectory("i18n-check").toFile()
        val sources = File(dir, "src").apply { mkdirs() }
        File(sources, "Demo.kt").writeText(
            """
            package demo
            val label = msg("Etiqueta")
            fun a(name: String) = tr("Hola {0}", name)
            fun b() = tr("Sin traducir")
            fun c(name: String) = tr("Malo ${'$'}name")
            fun d(name: String) = tr("Junto " + name)
            fun e() = tr("Comillas \"dentro\"\n")
            // tr("En un comentario") no cuenta
            fun f() = str("No es tr")
            """.trimIndent()
        )
        val catalog = File(dir, "en.json")
        catalog.writeText(
            """
            {
              "Comillas \"dentro\"\n": "Quotes \"inside\"\n",
              "Etiqueta": "Label",
              "Hola {0}": "Hello",
              "Sobra": "Extra"
            }
            """.trimIndent()
        )
        val report = I18nCatalogCheck.check(listOf(sources), catalog, definitionTexts = listOf("Nombre del modulo"))
        assertFalse(report.ok)
        assertEquals(listOf("Nombre del modulo", "Sin traducir"), report.missing)
        assertEquals(listOf("Sobra"), report.unused)
        assertEquals(1, report.placeholders.size)
        assertTrue(report.placeholders.single().contains("Hola {0}"))
        assertTrue(report.problems.any { it.contains("template") }, report.describe())
        assertTrue(report.problems.any { it.contains("concatenates") }, report.describe())
        assertTrue(report.describe().contains("Sin traducir"))

        catalog.writeText("""{"b": "B", "a": "A"}""")
        assertTrue(I18nCatalogCheck.check(emptyList(), catalog, extraTexts = listOf("a", "b")).problems.any { it.contains("sorted") })
        dir.deleteRecursively()
    }

    @Test
    fun missingCatalogCountsAsEmpty() {
        val dir = Files.createTempDirectory("i18n-empty").toFile()
        File(dir, "A.kt").writeText("""val x = tr("Uno")""")
        val report = I18nCatalogCheck.check(listOf(dir), File(dir, "missing.json"))
        assertEquals(listOf("Uno"), report.missing)
        dir.deleteRecursively()
    }
}
