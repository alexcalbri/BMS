package com.ideasdeveloper.bms.server.modules

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Module packages are re-checked before loading (SECURITY_FIXES.md B8). */
class ModuleJarIntegrityTest {
    private val root = Files.createTempDirectory("modules-root")
    private val jar = Files.createDirectories(root.resolve("crm")).resolve("crm-1.0.0.jar").also { Files.write(it, "contenido".toByteArray()) }
    private val sha = ModuleJarIntegrity.sha256(jar)

    @Test
    fun theRecordedHashMustMatch() {
        assertIs<ModuleJarIntegrity.Result.Verified>(ModuleJarIntegrity.check("crm", jar.toString(), sha, root) { error("not asked") })
        Files.write(jar, "cambiado".toByteArray())
        assertIs<ModuleJarIntegrity.Result.Refused>(ModuleJarIntegrity.check("crm", jar.toString(), sha, root) { error("not asked") })
    }

    @Test
    fun aJarOutsideTheModulesFolderOrThroughALinkIsRefused() {
        val outside = Files.createTempFile("evil", ".jar").also { Files.write(it, "x".toByteArray()) }
        val outsideSha = ModuleJarIntegrity.sha256(outside)
        assertIs<ModuleJarIntegrity.Result.Refused>(ModuleJarIntegrity.check("crm", outside.toString(), outsideSha, root) { true })
        assertIs<ModuleJarIntegrity.Result.Refused>(ModuleJarIntegrity.check("crm", root.resolve("crm/../../${outside.fileName}").toString(), outsideSha, root) { true })
        val link = Files.createSymbolicLink(root.resolve("crm/link.jar"), outside)
        assertIs<ModuleJarIntegrity.Result.Refused>(ModuleJarIntegrity.check("crm", link.toString(), outsideSha, root) { true })
    }

    @Test
    fun anOlderRowWithoutAHashGetsItRecordedOnce() {
        var recorded: String? = null
        val result = ModuleJarIntegrity.check("crm", jar.toString(), null, root) { recorded = it; true }
        assertIs<ModuleJarIntegrity.Result.Recorded>(result)
        assertEquals(sha, recorded)
        // If it cannot be recorded, it is not loaded.
        assertIs<ModuleJarIntegrity.Result.Refused>(ModuleJarIntegrity.check("crm", jar.toString(), null, root) { false })
    }
}
