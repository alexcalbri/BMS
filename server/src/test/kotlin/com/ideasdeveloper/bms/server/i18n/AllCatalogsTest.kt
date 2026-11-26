package com.ideasdeveloper.bms.server.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every catalog of the repository at once (I18N.md §2, §3). Each package owns its translations, so the
 * same Spanish key may have different translations in two namespaces ("Alta": a sign-up in Core, a
 * priority in tareas): those clashes are only reported. It fails when a package uses a literal that
 * its own catalog lacks, when a catalog is broken, or when placeholders do not match; the complete
 * per-package checks (definitions, unused entries) stay in each package's own I18n test.
 */
class AllCatalogsTest {
    private class Package(val namespace: String, val sources: File, val catalog: File)

    private val root = I18nCatalogCheck.repoRoot()

    private fun packages(): List<Package> {
        fun under(kind: String, catalogDir: (String) -> String, namespace: (String) -> String) =
            File(root, kind).listFiles().orEmpty().filter { File(it, "server/src/main/kotlin").isDirectory }.sortedBy { it.name }.map { dir ->
                Package(namespace(dir.name), File(dir, "server/src/main/kotlin"), File(dir, "server/src/main/resources/${catalogDir(dir.name)}/en.json"))
            }
        return listOf(Package(I18n.CoreNamespace, File(root, "server/src/main/kotlin"), File(root, "server/src/main/resources/i18n/en.json"))) +
            under("modules", ::moduleCatalogDir, I18n::moduleNamespace) +
            under("extensions", ::extensionCatalogDir, I18n::extensionNamespace)
    }

    @Test
    fun everyPackageCatalogHasTheTextsItUses() {
        val failures = mutableListOf<String>()
        var checked = 0
        packages().forEach { pkg ->
            val (texts, problems) = I18nCatalogCheck.extract(listOf(pkg.sources))
            if (!pkg.catalog.isFile) {
                if (texts.isNotEmpty()) failures += "${pkg.namespace}: uses ${texts.size} texts with tr/msg but has no catalog ${pkg.catalog.relativeTo(root)}"
                return@forEach
            }
            checked++
            val report = I18nCatalogCheck.check(listOf(pkg.sources), pkg.catalog, extraTexts = texts)
            val missing = report.missing
            if (missing.isNotEmpty()) failures += "${pkg.namespace}: missing ${missing.size}: ${missing.take(10)}"
            if (report.placeholders.isNotEmpty()) failures += "${pkg.namespace}: placeholders differ: ${report.placeholders}"
            if (problems.isNotEmpty() || report.problems.isNotEmpty()) failures += "${pkg.namespace}: ${(problems + report.problems).distinct()}"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(checked >= 2, "no package catalog found under $root")
    }

    @Test
    fun keysTranslatedDifferentlyByTwoNamespacesAreReportedOnly() {
        val catalogs = packages().filter { it.catalog.isFile }.associate { it.namespace to I18n.parseCatalog(it.catalog.readText()) }
        val clashes = catalogs.flatMap { (namespace, entries) -> entries.map { (key, value) -> Triple(key, namespace, value) } }
            .groupBy { it.first }
            .filterValues { rows -> rows.map { it.third }.distinct().size > 1 }
            .toSortedMap()
        // Allowed: every package owns its translations. Listed so a reviewer sees them in the test output.
        println("i18n: ${clashes.size} keys translated differently by different namespaces (allowed)")
        clashes.forEach { (key, rows) ->
            println("  \"$key\": " + rows.groupBy({ it.third }, { it.second }).entries.joinToString(" | ") { (value, namespaces) -> "\"$value\" <- ${namespaces.joinToString(",")}" })
        }
        // Inside one namespace a key is unique by construction (a JSON object); values are never blank.
        catalogs.forEach { (namespace, entries) -> entries.forEach { (key, value) -> assertTrue(value.isNotBlank(), "$namespace: blank translation for \"$key\"") } }
    }
}
