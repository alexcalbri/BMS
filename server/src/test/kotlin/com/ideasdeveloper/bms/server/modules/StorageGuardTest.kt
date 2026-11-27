package com.ideasdeveloper.bms.server.modules

import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Storage quota, free-disk floor and uploads in progress (STORAGE_QUOTA.md, SECURITY_FIXES.md B9). */
class StorageGuardTest {
    private val root = Files.createTempDirectory("storage-guard")
    private var quota: Long? = 100
    private var free: Long? = 10_000_000_000

    private fun files(maxUploads: Int = 4, minFree: Long = 1_000) = CompanyFiles.local(
        root,
        StorageGuard(root, { quota }, minFreeBytes = minFree, maxConcurrentUploads = maxUploads, freeSpace = { free }),
    )

    private fun fileCount() = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.count() }

    @Test
    fun anUploadOverTheQuotaStopsAndLeavesNoFile() {
        val module = files().module("acme", "crm")
        module.put("a.bin", "", ByteArray(60).inputStream(), maxBytes = 1_000)
        val refused = assertFailsWith<StorageQuotaException> { module.put("b.bin", "", ByteArray(60).inputStream(), maxBytes = 1_000) }
        assertEquals(StorageQuotaException.QuotaExceeded, refused.code)
        assertEquals(413, refused.httpStatus)
        assertEquals(1, fileCount())
        assertEquals(60, module.usage().usedBytes)
    }

    @Test
    fun theQuotaCountsEveryModuleAndExistingFilesOnDisk() {
        Files.createDirectories(root.resolve("acme/tareas/2026/01"))
        Files.write(root.resolve("acme/tareas/2026/01/old"), ByteArray(90))
        val module = files().module("acme", "crm")
        assertEquals(90, module.usage().usedBytes)
        assertFailsWith<StorageQuotaException> { module.checkRoom(20) }
        assertFailsWith<StorageQuotaException> { module.put("b.bin", "", ByteArray(20).inputStream(), maxBytes = 1_000) }
        // Another company is not affected.
        files().module("otra", "crm").put("c.bin", "", ByteArray(20).inputStream(), maxBytes = 1_000)
    }

    @Test
    fun deletingGivesTheSpaceBack() {
        val module = files().module("acme", "crm")
        val stored = module.put("a.bin", "", ByteArray(80).inputStream(), maxBytes = 1_000)
        assertFailsWith<StorageQuotaException> { module.checkRoom(30) }
        module.delete(stored)
        module.checkRoom(30)
        module.put("b.bin", "", ByteArray(30).inputStream(), maxBytes = 1_000)
    }

    @Test
    fun unlimitedWhenTheQuotaIsNull() {
        quota = null
        files().module("acme", "crm").put("a.bin", "", ByteArray(5_000).inputStream(), maxBytes = 10_000)
    }

    @Test
    fun theFreeDiskFloorRefusesUploads() {
        quota = null
        free = 500
        val module = files(minFree = 1_000).module("acme", "crm")
        val refused = assertFailsWith<StorageQuotaException> { module.put("a.bin", "", ByteArray(10).inputStream(), maxBytes = 1_000) }
        assertEquals(StorageQuotaException.DiskFull, refused.code)
        assertFailsWith<StorageQuotaException> { module.checkRoom(null) }
        assertEquals(0, fileCount())
    }

    @Test
    fun tooManyUploadsInProgressAreRefusedAndTheSlotIsGivenBack() {
        quota = null
        val files = files(maxUploads = 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : InputStream() {
            var sent = false
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent) return -1
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                sent = true
                b[off] = 1
                return 1
            }
        }
        val first = thread { files.module("acme", "crm").put("a.bin", "", slow, maxBytes = 1_000) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val refused = assertFailsWith<StorageQuotaException> { files.module("acme", "tareas").put("b.bin", "", "x".byteInputStream(), maxBytes = 1_000) }
        assertEquals(StorageQuotaException.Busy, refused.code)
        // Another company has its own slots.
        files.module("otra", "crm").put("c.bin", "", "x".byteInputStream(), maxBytes = 1_000)
        release.countDown()
        first.join()
        files.module("acme", "tareas").put("b.bin", "", "x".byteInputStream(), maxBytes = 1_000)
    }

    @Test
    fun aFailedUploadDoesNotKeepItsBytesInTheQuota() {
        val module = files().module("acme", "crm")
        assertFailsWith<FileTooLargeException> { module.put("a.bin", "", ByteArray(50).inputStream(), maxBytes = 40) }
        module.put("b.bin", "", ByteArray(95).inputStream(), maxBytes = 1_000)
    }

    @Test
    fun withoutAGuardNothingIsLimited() {
        CompanyFiles.local(root).module("acme", "crm").put("a.bin", "", ByteArray(5_000).inputStream(), maxBytes = 10_000)
    }

    @Test
    fun bytesAreShownReadably() {
        assertEquals("100 GB", formatBytes(100L * 1024 * 1024 * 1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("12 B", formatBytes(12))
    }
}
