package com.ideasdeveloper.bms.server.repo

import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Enforces the code-organisation limits of ARCHITECTURE.md 16.1 and 16.3: a Kotlin file has at most 800 lines in
 * `src/main` (1,000 in tests), a Core package directory at most 25 files and the root package of a module or
 * extension at most 30. Existing exceptions live in `source-layout/allowlist.txt` and may only shrink.
 *
 * With `-Dbms.layout.strict=true` it also fails when an entry can be removed or lowered (the ratchet that every
 * batch-end step runs); normal CI runs lenient so that parallel steps do not conflict on the allow-list.
 */
class SourceLayoutTest {
    private companion object {
        const val MainLimit = 800
        const val TestLimit = 1000
        const val CorePackageLimit = 25
        const val ModulePackageLimit = 30

        /** Maximum number of allow-list entries. Reviewers only ever lower this. */
        const val MaxEntries = 27
    }

    private data class Entry(val kind: String, val max: Int, val path: String)

    private val root: File = I18nCatalogCheck.repoRoot()
    private val strict: Boolean = System.getProperty("bms.layout.strict") == "true"

    private fun allowList(): List<Entry> {
        val text = checkNotNull(javaClass.getResourceAsStream("/source-layout/allowlist.txt")) { "source-layout/allowlist.txt is missing" }
            .bufferedReader().use { it.readText() }
        return text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map { line ->
            val parts = line.split(Regex("\\s+"))
            require(parts.size == 3 && parts[0] in setOf("file", "package") && parts[1].toIntOrNull() != null) { "Bad allow-list line: $line" }
            Entry(parts[0], parts[1].toInt(), parts[2])
        }
    }

    private fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    /** Directories scanned: server, Core, the app targets, and every module and extension server tree. */
    private fun scanRoots(): List<File> {
        val fixed = listOf("server/src", "core/src").map { File(root, it) }
        val app = File(root, "app").listFiles().orEmpty().map { File(it, "src") }
        val modules = File(root, "modules").listFiles().orEmpty().map { File(it, "server/src") }
        val extensions = File(root, "extensions").listFiles().orEmpty().map { File(it, "server/src") }
        return (fixed + app + modules + extensions).filter { it.isDirectory }
    }

    private fun kotlinFiles(): List<File> = scanRoots().flatMap { dir ->
        dir.walkTopDown().onEnter { it.name != "build" }.filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Physical lines, as `wc -l` counts them (number of line feeds). */
    private fun lines(file: File): Int = file.readBytes().count { it == '\n'.code.toByte() }

    private fun isTest(path: String): Boolean = "/src/test/" in path || Regex("/src/[A-Za-z0-9]+Test/").containsMatchIn(path)

    private fun packageDirs(): List<Pair<File, Int>> {
        val core = File(root, "server/src/main/kotlin/com/ideasdeveloper/bms/server")
        val coreDirs = core.walkTopDown().filter { it.isDirectory }.map { it to CorePackageLimit }.toList()
        val modules = File(root, "modules").listFiles().orEmpty().map { File(it, "server/src/main/kotlin/com/ideasdeveloper/bms/modules/${it.name}") }
        val extensions = File(root, "extensions").listFiles().orEmpty().map { File(it, "server/src/main/kotlin/com/ideasdeveloper/bms/extensions/${it.name}") }
        return coreDirs + (modules + extensions).filter { it.isDirectory }.map { it to ModulePackageLimit }
    }

    private fun filesIn(dir: File): Int = dir.listFiles().orEmpty().count { it.isFile && it.extension == "kt" }

    @Test
    fun filesAndPackagesStayWithinTheirLimits() {
        val entries = allowList()
        val byPath = entries.associateBy { it.path }
        val problems = mutableListOf<String>()

        if (entries.size > MaxEntries) problems += "The allow-list has ${entries.size} entries, more than MaxEntries=$MaxEntries (only ever lower it)."
        entries.groupBy { it.path }.filterValues { it.size > 1 }.keys.forEach { problems += "Duplicate allow-list entry: $it" }

        kotlinFiles().forEach { file ->
            val path = relative(file)
            val count = lines(file)
            val limit = if (isTest(path)) TestLimit else MainLimit
            val entry = byPath[path]
            when {
                entry == null && count > limit ->
                    problems += "$path has $count lines (limit $limit). Split it (ARCHITECTURE.md 16.2); an allow-list entry needs the owner's approval."
                entry != null && entry.kind != "file" -> problems += "$path is listed as '${entry.kind}' but it is a file."
                entry != null && count > entry.max -> problems += "$path grew to $count lines (allow-list: ${entry.max}); an entry may only go down."
                entry != null && strict && count <= limit -> problems += "$path has $count lines, within the limit $limit: remove its allow-list entry."
                entry != null && strict && count < entry.max -> problems += "$path has $count lines: lower its allow-list entry from ${entry.max} to $count."
            }
        }

        packageDirs().forEach { (dir, limit) ->
            val path = relative(dir)
            val count = filesIn(dir)
            val entry = byPath[path]
            when {
                entry == null && count > limit ->
                    problems += "$path has $count Kotlin files (limit $limit). Split it into sub-packages by function (ARCHITECTURE.md 16.3)."
                entry != null && entry.kind != "package" -> problems += "$path is listed as '${entry.kind}' but it is a package directory."
                entry != null && count > entry.max -> problems += "$path grew to $count files (allow-list: ${entry.max}); an entry may only go down."
                entry != null && strict && count <= limit -> problems += "$path has $count files, within the limit $limit: remove its allow-list entry."
                entry != null && strict && count < entry.max -> problems += "$path has $count files: lower its allow-list entry from ${entry.max} to $count."
            }
        }

        entries.filterNot { File(root, it.path).exists() }.forEach { problems += "Allow-list path no longer exists: ${it.path} (remove the entry)." }

        assertTrue(problems.isEmpty(), "Source layout violations (ARCHITECTURE.md 16):\n" + problems.joinToString("\n") { "  $it" })
    }
}
