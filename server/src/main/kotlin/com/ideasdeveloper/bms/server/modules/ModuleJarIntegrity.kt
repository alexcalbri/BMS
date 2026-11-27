package com.ideasdeveloper.bms.server.modules

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.sql.DataSource

/**
 * Re-checks a module package before the server loads it (SECURITY_FIXES.md B8): the jar must be a
 * regular file inside the modules folder (no path or link leading elsewhere) and its SHA-256 must be
 * the one recorded when it was built (server_modules.package_sha256). A row without a hash (older
 * installs) gets the current one recorded on the first start after the update, then is checked like
 * the others. A package that fails is not loaded; the module shows as available to reinstall.
 */
object ModuleJarIntegrity {
    private val log = LoggerFactory.getLogger(ModuleJarIntegrity::class.java)

    sealed interface Result {
        data class Verified(val jar: Path) : Result
        data class Recorded(val jar: Path, val sha256: String) : Result
        data class Refused(val reason: String) : Result
    }

    fun sha256(file: Path): String = Files.newInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        HexFormat.of().formatHex(digest.digest())
    }

    /** The real path of [stored] when it is a regular file under [packagesRoot] (links resolved), else null. */
    fun insideRoot(stored: String, packagesRoot: Path): Path? = runCatching {
        val root = packagesRoot.toAbsolutePath().normalize().let { if (Files.exists(it)) it.toRealPath() else it }
        val jar = Path.of(stored).toAbsolutePath().normalize()
        if (!Files.isRegularFile(jar)) return null
        val real = jar.toRealPath()
        real.takeIf { it.startsWith(root) && it != root && it.fileName.toString().endsWith(".jar") }
    }.getOrNull()

    /**
     * Checks the package of [moduleId] at [stored] against [expectedSha256]. With a null hash, the
     * current one is recorded through [record] (true when it was stored) and the package loads.
     */
    fun check(moduleId: String, stored: String, expectedSha256: String?, packagesRoot: Path, record: (String) -> Boolean): Result {
        val jar = insideRoot(stored, packagesRoot)
            ?: return Result.Refused("the package is not a jar inside $packagesRoot")
        val actual = sha256(jar)
        val expected = expectedSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (expected == null) {
            if (!record(actual)) return Result.Refused("its hash could not be recorded")
            log.warn("Module {}: package hash recorded on first start ({}); it is checked from now on.", moduleId, actual)
            return Result.Recorded(jar, actual)
        }
        if (!MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())) {
            return Result.Refused("its SHA-256 does not match the one recorded at install")
        }
        return Result.Verified(jar)
    }

    /** Records [sha256] for [moduleId] only where none is stored yet. */
    fun recordFirst(central: DataSource, moduleId: String, sha256: String): Boolean = runCatching {
        central.connection.use { connection ->
            connection.prepareStatement("UPDATE server_modules SET package_sha256 = ? WHERE module_id = ? AND package_sha256 IS NULL").use { update ->
                update.setString(1, sha256)
                update.setString(2, moduleId)
                update.executeUpdate() == 1
            }
        }
    }.onFailure { log.error("Module {}: package hash could not be recorded", moduleId, it) }.getOrDefault(false)
}
