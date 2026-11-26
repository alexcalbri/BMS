package com.ideasdeveloper.bms.server.modules

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageInstallQueueTest {
    private val file = Files.createTempDirectory("install-queue").resolve("pending-installs.json")

    // Several packages queued: the server restarts once, when the last build ends, not after the first.
    @Test
    fun `the restart waits until no build is queued or running`() {
        var restarts = 0
        val queue = PackageInstallQueue(file) { restarts++ }
        repeat(3) { queue.queued() }
        queue.built(needsRestart = true)
        queue.built(needsRestart = true)
        assertEquals(0, restarts)
        assertFalse(queue.restarting)
        queue.built(needsRestart = true)
        assertEquals(1, restarts)
        assertTrue(queue.restarting)
        // A build that ends later does not ask for a second restart.
        queue.queued()
        queue.built(needsRestart = true)
        assertEquals(1, restarts)
    }

    @Test
    fun `failed builds alone never restart and do not block the restart of the good ones`() {
        var restarts = 0
        val queue = PackageInstallQueue(file) { restarts++ }
        queue.queued()
        queue.built(needsRestart = false)
        assertEquals(0, restarts)
        queue.queued()
        queue.queued()
        queue.built(needsRestart = true)
        queue.built(needsRestart = false)
        assertEquals(1, restarts)
    }

    // What was pending when the server restarted is read again from the file, in the order asked.
    @Test
    fun `pending installs survive a restart`() {
        val before = PackageInstallQueue(file) {}
        before.add(PackageInstallQueue.Module, "crm")
        before.add(PackageInstallQueue.Extension, "monday", "owner_role")
        before.add(PackageInstallQueue.Extension, "cloudbeds", "owner_role")
        before.add(PackageInstallQueue.Extension, "monday", "otro_rol")
        before.done(PackageInstallQueue.Extension, "cloudbeds")

        val after = PackageInstallQueue(file) {}
        assertEquals(
            listOf(PackageInstallQueue.Entry("module", "crm"), PackageInstallQueue.Entry("extension", "monday", "owner_role")),
            after.pending(),
        )
        assertTrue(after.has(PackageInstallQueue.Module, "crm"))
        assertFalse(after.has(PackageInstallQueue.Extension, "crm"))
        assertFalse(after.has(PackageInstallQueue.Extension, "cloudbeds"))
    }

    // A package that takes the server down while building is not tried for ever.
    @Test
    fun `a resumed install is tried a few times and then dropped`() {
        PackageInstallQueue(file) {}.add(PackageInstallQueue.Extension, "monday", "owner_role")
        repeat(PackageInstallQueue.MaxAttempts) { round ->
            val queue = PackageInstallQueue(file) {}
            assertTrue(queue.attempt(PackageInstallQueue.Extension, "monday"), "intento ${round + 1}")
        }
        val last = PackageInstallQueue(file) {}
        assertFalse(last.attempt(PackageInstallQueue.Extension, "monday"))
        assertEquals(emptyList(), last.pending())
        assertFalse(last.attempt(PackageInstallQueue.Extension, "desconocida"))
    }

    @Test
    fun `an unreadable file starts empty instead of stopping the server`() {
        Files.writeString(file, "{ no es json")
        val queue = PackageInstallQueue(file) {}
        assertEquals(emptyList(), queue.pending())
        queue.add(PackageInstallQueue.Module, "crm")
        assertEquals(listOf(PackageInstallQueue.Entry("module", "crm")), PackageInstallQueue(file) {}.pending())
    }
}
