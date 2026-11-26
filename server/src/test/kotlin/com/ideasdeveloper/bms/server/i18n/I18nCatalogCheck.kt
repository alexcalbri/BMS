package com.ideasdeveloper.bms.server.i18n

import com.ideasdeveloper.bms.server.extensions.ExtensionManifest
import com.ideasdeveloper.bms.server.modules.ModuleDefinition
import java.io.File

/**
 * Catalog test utility for every package (I18N.md §3). It extracts the literals of `tr("…")` and
 * `msg("…")` from the given source folders, adds the definition texts that Core translates at response
 * time (§6), and compares them with a catalog file:
 *
 * - missing: used in the code (or the definition) but not in the catalog;
 * - unused: in the catalog but nobody uses it;
 * - placeholders: the translation has other {n} placeholders than the source;
 * - problems: calls the extractor cannot read (a `$` template or `+` concatenation inside tr/msg),
 *   blank translations, and a catalog whose keys are not sorted.
 *
 * A module test (modules/<id>/server/src/test/kotlin) usually needs only:
 *
 *     @Test
 *     fun catalogIsComplete() = I18nCatalogCheck.assertModule("crm", CrmServerModule.definition)
 *
 * and an extension test: `I18nCatalogCheck.assertExtension("meta", MetaExtension.manifest)`.
 */
object I18nCatalogCheck {
    data class Report(
        val catalog: File,
        val missing: List<String>,
        val unused: List<String>,
        val placeholders: List<String>,
        val problems: List<String>,
    ) {
        val ok: Boolean get() = missing.isEmpty() && unused.isEmpty() && placeholders.isEmpty() && problems.isEmpty()

        fun describe(): String = buildString {
            append("Catalog ").append(catalog.path).append(if (ok) " is complete" else " has problems").append('\n')
            if (missing.isNotEmpty()) append("Missing (").append(missing.size).append("):\n").append(missing.joinToString("\n") { "  \"$it\"" }).append('\n')
            if (unused.isNotEmpty()) append("Unused (").append(unused.size).append("):\n").append(unused.joinToString("\n") { "  \"$it\"" }).append('\n')
            if (placeholders.isNotEmpty()) append("Placeholders differ:\n").append(placeholders.joinToString("\n") { "  $it" }).append('\n')
            if (problems.isNotEmpty()) append("Problems:\n").append(problems.joinToString("\n") { "  $it" }).append('\n')
        }
    }

    /** The repository root (the folder with settings.gradle.kts), from the test's working directory. */
    fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "server").isDirectory }
        ?: error("Repository root not found from ${File("").absolutePath}")

    /** Literals of tr("…") / msg("…") in the .kt files under [sourceDirs], unescaped, with the calls the extractor cannot read. */
    fun extract(sourceDirs: List<File>): Pair<Set<String>, List<String>> {
        val texts = sortedSetOf<String>()
        val problems = mutableListOf<String>()
        sourceDirs.filter { it.exists() }.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }.forEach { file ->
            val source = file.readText()
            Call.findAll(source).forEach { match ->
                val raw = match.groupValues[2]
                val line = source.substring(0, match.range.first).count { it == '\n' } + 1
                val where = "${file.name}:$line"
                if (inComment(source, match.range.first)) return@forEach
                val rest = source.substring(match.range.last + 1).trimStart(' ')
                when {
                    Template.containsMatchIn(raw) -> problems += "$where: ${match.groupValues[1]}(\"$raw\") uses a \$ template; use {0} placeholders"
                    rest.startsWith("+") -> problems += "$where: ${match.groupValues[1]}(\"$raw\" + …) concatenates inside the call"
                    else -> unescape(raw).takeIf { it.isNotEmpty() }?.let(texts::add)
                }
            }
        }
        return texts to problems
    }

    /**
     * Compares the texts of [sourceDirs] plus [definitionTexts] (see definitionTexts(ModuleDefinition))
     * and [extraTexts] (texts the package translates with tr(variable) that no literal or definition
     * lists) with [catalog]. A missing catalog file counts as empty.
     */
    fun check(
        sourceDirs: List<File>,
        catalog: File,
        definitionTexts: Collection<String> = emptyList(),
        extraTexts: Collection<String> = emptyList(),
    ): Report {
        val (extracted, problems) = extract(sourceDirs)
        val used = (extracted + definitionTexts + extraTexts).filter { it.isNotBlank() }.toSortedSet()
        val text = if (catalog.isFile) catalog.readText() else "{}"
        val entries = try {
            I18n.parseCatalog(text)
        } catch (failure: Exception) {
            return Report(catalog, used.toList(), emptyList(), emptyList(), problems + "catalog is not a flat JSON object of strings: ${failure.message}")
        }
        val allProblems = problems.toMutableList()
        val keys = KeyOrder.findAll(text).map { unescapeJson(it.groupValues[1]) }.toList()
        if (keys != keys.sorted()) allProblems += "catalog keys are not sorted alphabetically"
        entries.filterValues { it.isBlank() }.keys.forEach { allProblems += "blank translation for \"$it\"" }
        val placeholders = entries.mapNotNull { (source, translation) ->
            val expected = placeholdersOf(source)
            val actual = placeholdersOf(translation)
            if (expected != actual) "\"$source\" has $expected, \"$translation\" has $actual" else null
        }
        return Report(
            catalog = catalog,
            missing = used.filter { it !in entries }.toList(),
            unused = entries.keys.filter { it !in used }.sorted(),
            placeholders = placeholders,
            problems = allProblems,
        )
    }

    /** [check] that fails the test with the report when the catalog is not complete. */
    fun assertComplete(
        sourceDirs: List<File>,
        catalog: File,
        definitionTexts: Collection<String> = emptyList(),
        extraTexts: Collection<String> = emptyList(),
    ) {
        val report = check(sourceDirs, catalog, definitionTexts, extraTexts)
        if (!report.ok) throw AssertionError(report.describe())
    }

    /** Catalog of module [moduleId] (modules/<id>/server/src/main/resources/modules/<id>/i18n/en.json) against its sources and [definition]. */
    fun assertModule(moduleId: String, definition: ModuleDefinition? = null, extraTexts: Collection<String> = emptyList(), language: String = "en") {
        val root = repoRoot()
        assertComplete(
            sourceDirs = listOf(File(root, "modules/$moduleId/server/src/main/kotlin")),
            catalog = File(root, "modules/$moduleId/server/src/main/resources/${moduleCatalogDir(moduleId)}/$language.json"),
            definitionTexts = definition?.let(::definitionTexts).orEmpty(),
            extraTexts = extraTexts,
        )
    }

    /** Catalog of extension [extensionId] (extensions/<id>/server/src/main/resources/extensions/<id>/i18n/en.json) against its sources and [manifest]. */
    fun assertExtension(extensionId: String, manifest: ExtensionManifest? = null, extraTexts: Collection<String> = emptyList(), language: String = "en") {
        val root = repoRoot()
        assertComplete(
            sourceDirs = listOf(File(root, "extensions/$extensionId/server/src/main/kotlin")),
            catalog = File(root, "extensions/$extensionId/server/src/main/resources/${extensionCatalogDir(extensionId)}/$language.json"),
            definitionTexts = manifest?.let(::manifestTexts).orEmpty(),
            extraTexts = extraTexts,
        )
    }

    /** Texts of an extension manifest that Core translates when it lists extensions. */
    fun manifestTexts(manifest: ExtensionManifest): Set<String> = buildSet {
        add(manifest.displayName)
        add(manifest.description)
        manifest.settings.forEach { add(it.label); add(it.help) }
        manifest.uninstall.options.forEach { add(it.label) }
    }.filter { it.isNotBlank() }.toSet()

    /** {n} placeholders of [text], sorted. */
    fun placeholdersOf(text: String): List<String> = I18n.Placeholder.findAll(text).map { it.value }.sorted().toList()

    // tr( or msg( not preceded by a letter/digit/underscore/dot, then one string literal.
    private val Call = Regex("""(?<![\w.])(tr|msg)\(\s*"((?:[^"\\\n]|\\.)*)"""")
    private val Template = Regex("""(?<!\\)\$[{A-Za-z_]""")
    private val KeyOrder = Regex("""[{,]\s*"((?:[^"\\]|\\.)*)"\s*:""")

    // Inside a // comment (outside a string) or on a KDoc/block comment line: examples in comments are not uses.
    private fun inComment(source: String, index: Int): Boolean {
        val lineStart = source.lastIndexOf('\n', index - 1) + 1
        val before = source.substring(lineStart, index)
        val trimmed = before.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return true
        var quotes = 0
        var position = 0
        while (position < before.length - 1) {
            when {
                before[position] == '\\' -> position++
                before[position] == '"' -> quotes++
                before[position] == '/' && before[position + 1] == '/' && quotes % 2 == 0 -> return true
            }
            position++
        }
        return false
    }

    private fun unescape(raw: String): String = buildString {
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char == '\\' && index + 1 < raw.length) {
                when (val next = raw[index + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    'r' -> append('\r')
                    'b' -> append('\b')
                    'u' -> {
                        append(raw.substring(index + 2, index + 6).toInt(16).toChar())
                        index += 4
                    }
                    else -> append(next)
                }
                index += 2
            } else {
                append(char)
                index++
            }
        }
    }

    private fun unescapeJson(raw: String): String = I18n.parseCatalog("{\"$raw\":\"\"}").keys.first()
}
