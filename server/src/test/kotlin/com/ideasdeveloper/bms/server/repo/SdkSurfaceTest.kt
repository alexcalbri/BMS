package com.ideasdeveloper.bms.server.repo

import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the module SDK (ARCHITECTURE.md §16.5). Optional modules and extensions are compiled once, against the Core
 * that is running when they are installed, and are not rebuilt when Core updates. Every Core symbol they reference is
 * therefore frozen: if it is renamed or moved, an installed package fails with NoClassDefFoundError inside a request.
 *
 * - [everyReferencedSymbolIsFrozen]: a module or extension that starts using a new Core symbol must add it to
 *   `sdk/released-surface.txt` deliberately.
 * - [everyFrozenSymbolStillResolves]: every entry of the snapshot still resolves to a class or a top-level declaration.
 */
class SdkSurfaceTest {
    private val reference = Regex("""com\.ideasdeveloper\.bms\.server\.[a-z0-9_.]+\.[A-Za-z_]\w*""")

    private fun snapshot(): Set<String> {
        val text = checkNotNull(javaClass.getResourceAsStream("/sdk/released-surface.txt")) { "sdk/released-surface.txt is missing" }
            .bufferedReader().use { it.readText() }
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSortedSet()
    }

    /** `src/main/kotlin` of the optional modules (module.json without `"bundled": true`) and of every extension. */
    private fun releasedSourceDirs(root: File): List<File> {
        val modules = File(root, "modules").listFiles().orEmpty().filter { it.isDirectory }.filter { dir ->
            val manifest = File(dir, "module.json")
            manifest.isFile && !Regex(""""bundled"\s*:\s*true""").containsMatchIn(manifest.readText())
        }
        val extensions = File(root, "extensions").listFiles().orEmpty().filter { it.isDirectory }
        return (modules + extensions).map { File(it, "server/src/main/kotlin") }.filter { it.isDirectory }
    }

    private fun currentReferences(): Set<String> {
        val found = sortedSetOf<String>()
        releasedSourceDirs(I18nCatalogCheck.repoRoot()).forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                reference.findAll(file.readText()).forEach { found += it.value }
            }
        }
        return found
    }

    @Test
    fun everyReferencedSymbolIsFrozen() {
        val frozen = snapshot()
        val unlisted = currentReferences() - frozen
        assertTrue(
            unlisted.isEmpty(),
            "Modules or extensions use Core symbols that are not in sdk/released-surface.txt. Add them deliberately: " +
                "once released they become frozen (ARCHITECTURE §16.5).\n" + unlisted.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun everyFrozenSymbolStillResolves() {
        val broken = snapshot().filterNot(::resolves)
        assertTrue(
            broken.isEmpty(),
            "Binary break for installed packages: these frozen Core symbols no longer resolve " +
                "(renamed, moved or removed; ARCHITECTURE §16.5).\n" + broken.joinToString("\n") { "  $it" },
        )
    }

    private fun resolves(name: String): Boolean = loadsAsClass(name) || declaredTopLevel(name)

    /** `a.b.Outer.Inner` is the JVM class `a.b.Outer$Inner`: retry replacing dots from the right. */
    private fun loadsAsClass(name: String): Boolean {
        var candidate = name
        while (true) {
            if (runCatching { Class.forName(candidate, false, javaClass.classLoader) }.isSuccess) return true
            val dot = candidate.lastIndexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1)
            if (candidate.substringBeforeLast('$').count { it == '.' } < 4) return false
        }
    }

    /** A top-level function, property or constant: a static member of some `<File>Kt` facade of the package. */
    private fun declaredTopLevel(name: String): Boolean {
        val pkg = name.substringBeforeLast('.')
        val member = name.substringAfterLast('.')
        val getter = "get" + member.replaceFirstChar { it.uppercase() }
        return facadesOf(pkg).any { facade ->
            val type = runCatching { Class.forName(facade, false, javaClass.classLoader) }.getOrNull() ?: return@any false
            val methods = runCatching { type.declaredMethods }.getOrDefault(emptyArray())
            val fields = runCatching { type.declaredFields }.getOrDefault(emptyArray())
            methods.any { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && (it.name == member || it.name == getter) } ||
                fields.any { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.name == member }
        }
    }

    /** Names of the classes `<Something>Kt` directly inside [pkg], read from the test classpath (directories and jars). */
    private fun facadesOf(pkg: String): List<String> {
        val path = pkg.replace('.', '/')
        val result = sortedSetOf<String>()
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }.forEach { entry ->
            val file = File(entry)
            when {
                file.isDirectory -> File(file, path).listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith("Kt.class") }
                    .forEach { result += "$pkg.${it.name.removeSuffix(".class")}" }
                file.isFile && file.extension == "jar" -> runCatching {
                    JarFile(file).use { jar ->
                        jar.entries().asSequence().map { it.name }
                            .filter { it.startsWith("$path/") && it.endsWith("Kt.class") && it.removePrefix("$path/").indexOf('/') < 0 }
                            .forEach { result += it.removeSuffix(".class").replace('/', '.') }
                    }
                }
            }
        }
        return result.toList()
    }
}
