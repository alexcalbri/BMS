package com.ideasdeveloper.bms.server.i18n

import kotlinx.coroutines.asContextElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext

/**
 * Multi-language support of the server (I18N.md). Spanish is the source and the key: with language
 * [Source] every text is the source itself. Other languages are looked up in the catalogs
 * (`i18n/<lang>.json` of Core, of each module and of each extension) by the Spanish text; a missing
 * entry falls back to the source.
 *
 * Every package owns its translations (I18N.md §2): the same Spanish word can mean different things in
 * two packages ("Alta" is a sign-up in Core and a priority in tareas). A text is looked up in the
 * catalog of a namespace first, then in Core's, and only as a last resort in any other catalog. [tr]
 * and [translate] take the namespace of the calling class ([callerNamespace]): classes in
 * `com.ideasdeveloper.bms.modules.<id>` belong to module `<id>`, classes in
 * `com.ideasdeveloper.bms.extensions.<id>` to extension `<id>` ([extensionNamespace]) and everything else
 * to Core. Core code that translates on behalf of a package uses [translateIn] with that package's
 * namespace.
 *
 * The language of the current request lives in a thread local carried by a coroutine context element,
 * so it survives `withContext(Dispatchers.IO)` and the thread hops of Ktor. Core installs it once per
 * request ([installI18n]); background work uses [withLanguage].
 */
object I18n {
    const val Source = "es"
    val Languages = listOf("es", "en")

    /** Namespace of the Core catalog (looked up first). */
    const val CoreNamespace = "core"

    private val log = LoggerFactory.getLogger(I18n::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The language of one request or job. It is a mutable holder (not a plain string) so that Core can
     * replace the language from Accept-Language with the user's stored language once the session is
     * resolved, even from inside `withContext(Dispatchers.IO)`: the coroutine element restores the
     * same holder on every thread the request runs on.
     */
    class Holder internal constructor(@Volatile var language: String)

    private val current = ThreadLocal<Holder?>()

    /** Namespace of module [moduleId]'s catalog: the id itself. */
    fun moduleNamespace(moduleId: String): String = moduleId

    /** Namespace of extension [extensionId]'s catalog: `extensions/<id>`. */
    fun extensionNamespace(extensionId: String): String = "extensions/$extensionId"

    /**
     * The catalogs, replaced as a whole on every register so lookups need no lock: [byNamespace] is
     * namespace -> language -> entries in registration order; [merged] is language -> every catalog
     * merged (Core first, then registration order), the last-resort lookup.
     */
    private class Catalogs(
        val byNamespace: Map<String, Map<String, Map<String, String>>>,
        val merged: Map<String, Map<String, String>>,
    )

    @Volatile
    private var catalogs = Catalogs(emptyMap(), emptyMap())
    private val registerLock = Any()

    /** "en-US,en;q=0.9" -> "en"; the first supported language of the list; unknown or blank -> null. */
    fun normalize(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        val candidates = tag.split(',')
            .mapIndexedNotNull { index, part ->
                val pieces = part.trim().split(';')
                val primary = pieces[0].trim().replace('_', '-').substringBefore('-').lowercase()
                if (primary.isEmpty() || primary == "*") return@mapIndexedNotNull null
                val quality = pieces.drop(1).map { it.trim() }.firstOrNull { it.startsWith("q=") }
                    ?.removePrefix("q=")?.toDoubleOrNull() ?: 1.0
                Triple(primary, quality, index)
            }
            .filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Triple<String, Double, Int>> { it.second }.thenBy { it.third })
        return candidates.firstOrNull { it.first in Languages }?.first
    }

    /** Language of the current request or job; [Source] when none was set. */
    fun language(): String = current.get()?.language ?: Source

    /** Runs [block] with [language] (normalized; null or unknown keeps [Source]) and restores the previous one. */
    fun <T> withLanguage(language: String?, block: () -> T): T {
        val previous = current.get()
        current.set(Holder(normalize(language) ?: Source))
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }

    /**
     * Coroutine context element that carries [language] (normalized; null or unknown is [Source]) on
     * every thread the coroutine runs on: `withContext(I18n.contextElement("en")) { ... }`.
     */
    fun contextElement(language: String?): CoroutineContext.Element =
        current.asContextElement(Holder(normalize(language) ?: Source))

    /**
     * Replaces the language of the current request (Core only: the user's stored language wins over
     * Accept-Language once the session is known). Null or unknown leaves it as it is. Without a
     * request in progress it does nothing.
     */
    fun overrideRequestLanguage(language: String?) {
        val normalized = normalize(language) ?: return
        current.get()?.language = normalized
    }

    /** Adds or replaces the entries of [namespace] for [language]. Blank translations are ignored. */
    fun register(namespace: String, language: String, entries: Map<String, String>) {
        val clean = entries.filter { (key, value) -> key.isNotEmpty() && value.isNotBlank() }
        synchronized(registerLock) {
            val byNamespace = LinkedHashMap(catalogs.byNamespace)
            byNamespace[namespace] = byNamespace[namespace].orEmpty() + (language to clean)
            catalogs = Catalogs(byNamespace, merge(byNamespace))
        }
    }

    /**
     * Reads `<resourceDir>/<lang>.json` with [loader] for every language but the source and registers
     * it under [namespace]. A missing file is fine (nothing translated yet); a malformed one is logged
     * and skipped, never stopping the server.
     */
    fun loadFrom(loader: ClassLoader, resourceDir: String, namespace: String) {
        val dir = resourceDir.trim('/')
        Languages.filter { it != Source }.forEach { language ->
            val path = "$dir/$language.json"
            val text = try {
                loader.getResourceAsStream(path)?.use { String(it.readAllBytes(), Charsets.UTF_8) }
            } catch (failure: Exception) {
                log.warn("Catalog {} of {} could not be read", path, namespace, failure)
                null
            } ?: return@forEach
            val entries = try {
                parseCatalog(text)
            } catch (failure: Exception) {
                log.warn("Catalog {} of {} is not a flat JSON object of strings; it is ignored", path, namespace, failure)
                return@forEach
            }
            register(namespace, language, entries)
        }
    }

    /** Parses a catalog file: a flat JSON object of string to string. */
    fun parseCatalog(text: String): Map<String, String> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: error("not a JSON object")
        return root.mapValues { (key, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("entry '$key' is not a string")
        }
    }

    /**
     * The text of [source] in [language] for the package of the calling class ([callerNamespace]), or
     * [source] itself when it is the source language or no catalog has it.
     */
    fun translate(source: String, language: String = language()): String {
        if (language == Source || source.isEmpty()) return source
        return lookup(callerNamespace(), source, language) ?: source
    }

    /**
     * The text of [source] in [language] as package [namespace] translates it (see [moduleNamespace],
     * [extensionNamespace], [CoreNamespace]): its catalog, then Core's, then any other; [source] when none
     * has it. For Core code that translates on behalf of a package (definitions, manifests, option labels).
     */
    fun translateIn(namespace: String, source: String, language: String = language()): String {
        if (language == Source || source.isEmpty()) return source
        return lookup(namespace, source, language) ?: source
    }

    /** Whether some catalog has an entry for [source] in [language]. */
    fun has(source: String, language: String): Boolean = catalogs.merged[language]?.containsKey(source) == true

    /** Whether the catalog of [namespace] itself has an entry for [source] in [language]. */
    fun hasIn(namespace: String, source: String, language: String): Boolean =
        catalogs.byNamespace[namespace]?.get(language)?.containsKey(source) == true

    private fun lookup(namespace: String, source: String, language: String): String? {
        val current = catalogs
        return current.byNamespace[namespace]?.get(language)?.get(source)
            ?: current.byNamespace[CoreNamespace]?.get(language)?.get(source)
            ?: current.merged[language]?.get(source)
    }

    /**
     * Namespace of the first class on the call stack outside the i18n machinery (this object, `tr`,
     * `localized`): [namespaceOf] that class. Lambdas and inlined code count as the class that declares
     * them, so `tr` inside a module's lambda or helper resolves to that module.
     */
    fun callerNamespace(): String = walker.walk { frames ->
        frames.map { frame -> namespaceByClass.get(frame.declaringClass) }.filter { it.isNotEmpty() }.findFirst().orElse(CoreNamespace)
    }

    /** Namespace of the package that declares [type]: a module, an extension or [CoreNamespace]. */
    fun namespaceOf(type: Class<*>): String = namespaceByClass.get(type).ifEmpty { CoreNamespace }

    /** Namespace of a class name: `…bms.modules.<id>…` -> `<id>`, `…bms.extensions.<id>…` -> `extensions/<id>`, else Core. */
    internal fun namespaceOfClassName(name: String): String {
        if (name.startsWith(ModulePackage)) return moduleNamespace(name.substring(ModulePackage.length).substringBefore('.'))
        if (name.startsWith(ExtensionPackage)) return extensionNamespace(name.substring(ExtensionPackage.length).substringBefore('.'))
        return CoreNamespace
    }

    private const val ModulePackage = "com.ideasdeveloper.bms.modules."
    private const val ExtensionPackage = "com.ideasdeveloper.bms.extensions."

    // Classes that translate on behalf of their caller and are skipped by callerNamespace.
    private val Machinery = setOf(
        "com.ideasdeveloper.bms.server.i18n.I18n",
        "com.ideasdeveloper.bms.server.i18n.I18nKt",
        "com.ideasdeveloper.bms.server.i18n.LocalizationKt",
    )

    private val walker: StackWalker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)

    // Per class, computed once: "" for the i18n machinery (skipped), otherwise the namespace of its package.
    private val namespaceByClass = object : ClassValue<String>() {
        override fun computeValue(type: Class<*>): String =
            if (type.name in Machinery || type.name.startsWith("com.ideasdeveloper.bms.server.i18n.I18n$")) "" else namespaceOfClassName(type.name)
    }

    /**
     * Replaces {0}, {1}... in [text] by [args] (null is written "null", like a Kotlin string template, so
     * texts converted from templates stay byte-identical); a placeholder without argument stays as it is.
     */
    fun format(text: String, args: Array<out Any?>): String {
        if (args.isEmpty() || !text.contains('{')) return text
        return Placeholder.replace(text) { match ->
            val index = match.groupValues[1].toInt()
            if (index < args.size) args[index].toString() else match.value
        }
    }

    internal val Placeholder = Regex("\\{(\\d+)\\}")

    private fun merge(byNamespace: Map<String, Map<String, Map<String, String>>>): Map<String, Map<String, String>> {
        val languages = byNamespace.values.flatMap { it.keys }.toSet()
        val order = byNamespace.keys.sortedBy { if (it == CoreNamespace) 0 else 1 }
        return languages.associateWith { language ->
            val result = HashMap<String, String>()
            order.forEach { namespace ->
                byNamespace[namespace]?.get(language)?.forEach { (key, value) -> result.putIfAbsent(key, value) }
            }
            result
        }
    }

    /** The Core catalog (server/src/main/resources/i18n); loaded at startup and idempotent. */
    fun loadCore() {
        loadFrom(I18n::class.java.classLoader, "i18n", CoreNamespace)
    }

    init {
        loadCore()
    }
}

/**
 * Translates a Spanish source text into the current language, with the catalog of the calling class's
 * package first (I18n.callerNamespace). Placeholders {0}, {1}… are replaced by args.
 */
fun tr(source: String, vararg args: Any?): String {
    val language = I18n.language()
    if (language == I18n.Source || source.isEmpty()) return I18n.format(source, args)
    return I18n.format(I18n.translateIn(I18n.callerNamespace(), source, language), args)
}

/** [tr] with the catalog of [namespace] first (I18n.moduleNamespace, I18n.extensionNamespace, I18n.CoreNamespace). */
fun trIn(namespace: String, source: String, vararg args: Any?): String = I18n.format(I18n.translateIn(namespace, source), args)

/** Marks a literal for the catalog without translating it now (enum labels, vals). Returns it unchanged. */
fun msg(source: String): String = source
