package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C1: logica del ayudante root (scripts/ubuntu/mirror.sh) con repositorios git temporales y llaves SSH
 * desechables: solo se instala el commit aprobado si esta en la rama, y con allowed_signers instalado solo
 * si tiene una firma valida de esa llave. El guion de la prueba es core-update/mirror-test.sh.
 * Se salta si faltan bash, git o ssh-keygen.
 */
class CoreUpdateHelperShellTest {
    private fun available(command: String): Boolean = runCatching {
        ProcessBuilder("bash", "-c", "command -v $command").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    @Test
    fun mirrorPinsTheApprovedCommitAndVerifiesItsSignature() {
        if (!listOf("bash", "git", "ssh-keygen").all(::available)) return
        val root = I18nCatalogCheck.repoRoot()
        val script = File(root, "server/src/test/resources/core-update/mirror-test.sh")
        val work = Files.createTempDirectory("core-update-helper").toFile()
        try {
            val process = ProcessBuilder("bash", script.absolutePath, root.absolutePath, work.absolutePath)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), output)
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.contains("failures=0"), output)
            assertTrue(output.lines().count { it.startsWith("ok ") } >= 27, output)
        } finally {
            work.deleteRecursively()
        }
    }
}
