package com.ideasdeveloper.bms.server.modules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * What the server owner asked to install (modules and extension packages) and is not finished yet.
 *
 * Two problems it solves. A package is loaded by restarting the server, and the build queues lived
 * only in memory: with several installs queued, the first one restarted the server and the rest
 * were lost. So the restart now waits until nothing is being built ([built]), and what is pending is
 * kept in a file next to the packages, to be resumed after any restart ([pending]).
 *
 * And an extension is compiled against its module, which must be loaded: an extension asked for
 * while its module is still installing stays here until the module is loaded, after the restart.
 */
class PackageInstallQueue(private val file: Path, private val restart: () -> Unit) {
    @Serializable
    data class Entry(val kind: String, val id: String, val actor: String = "", val attempts: Int = 0)

    private val log = LoggerFactory.getLogger(PackageInstallQueue::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var entries: List<Entry> = read()
    private var building = 0
    private var restartNeeded = false
    private var restartRequested = false

    /** Pending installs in the order they were asked for. */
    fun pending(): List<Entry> = synchronized(lock) { entries }

    fun has(kind: String, id: String): Boolean = synchronized(lock) { entries.any { it.kind == kind && it.id == id } }

    /** Remembers the install until [done]; asking again for the same one keeps its place and attempts. */
    fun add(kind: String, id: String, actor: String = "") = synchronized(lock) {
        if (entries.none { it.kind == kind && it.id == id }) save(entries + Entry(kind, id, actor))
    }

    /** The install ended, well or not: it is not resumed any more. */
    fun done(kind: String, id: String) = synchronized(lock) {
        if (entries.any { it.kind == kind && it.id == id }) save(entries.filterNot { it.kind == kind && it.id == id })
    }

    /**
     * Counts one more try of a resumed install and tells whether it may run: after [MaxAttempts] it
     * is dropped, so a package that kills the server while building cannot restart it for ever.
     */
    fun attempt(kind: String, id: String): Boolean = synchronized(lock) {
        val entry = entries.firstOrNull { it.kind == kind && it.id == id } ?: return@synchronized false
        if (entry.attempts >= MaxAttempts) {
            save(entries - entry)
            return@synchronized false
        }
        save(entries.map { if (it === entry) it.copy(attempts = it.attempts + 1) else it })
        true
    }

    /** A build was queued; the restart waits for it. */
    fun queued() = synchronized(lock) { building++ }

    /** A queued build ended. With [needsRestart] the server restarts once no other build is queued or running. */
    fun built(needsRestart: Boolean) {
        val fire = synchronized(lock) {
            building = (building - 1).coerceAtLeast(0)
            if (needsRestart) restartNeeded = true
            if (building == 0 && restartNeeded && !restartRequested) {
                restartRequested = true
                true
            } else {
                false
            }
        }
        if (fire) restart()
    }

    /** Whether a restart to load new packages is already on its way. */
    val restarting: Boolean get() = synchronized(lock) { restartRequested }

    private fun read(): List<Entry> = try {
        if (Files.isRegularFile(file)) json.decodeFromString<List<Entry>>(Files.readString(file)) else emptyList()
    } catch (failure: Exception) {
        log.warn("Pending installs could not be read from {}: {}", file, failure.message)
        emptyList()
    }

    // Written whole to a temporary file and moved, so a restart never leaves it half written.
    private fun save(next: List<Entry>) {
        entries = next
        try {
            file.parent?.let { Files.createDirectories(it) }
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temporary, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Entry.serializer()), next))
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Exception) {
            log.warn("Pending installs could not be saved in {}: {}", file, failure.message)
        }
    }

    companion object {
        const val Module = "module"
        const val Extension = "extension"
        const val MaxAttempts = 3
    }
}
