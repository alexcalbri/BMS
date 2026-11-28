package com.ideasdeveloper.bms.server.company.application

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Imported backups are streamed with size, entry and expanded-size limits (SECURITY_FIXES.md B4). */
class BackupImportLimitsTest {
    private fun zip(entries: Int, bytesEach: Int): java.nio.file.Path {
        val file = Files.createTempFile("backup", ".zip")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            repeat(entries) { index ->
                zip.putNextEntry(ZipEntry("e$index.txt"))
                zip.write(ByteArray(bytesEach))
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun theUploadIsCutAtTheLimit() {
        val out = ByteArrayOutputStream()
        assertEquals(10, BackupImportLimits.copyLimited(ByteArray(10).inputStream(), out, maxBytes = 10))
        assertFailsWith<CompanySelfException> { BackupImportLimits.copyLimited(ByteArray(11).inputStream(), ByteArrayOutputStream(), maxBytes = 10) }
    }

    @Test
    fun tooManyEntriesOrAZipBombAreRefused() {
        BackupImportLimits.validateZip(zip(3, 10), maxEntries = 3, maxUncompressed = 30)
        assertFailsWith<CompanySelfException> { BackupImportLimits.validateZip(zip(4, 1), maxEntries = 3) }
        // 1 MB of zeros compresses to about 1 KB: the expanded size is what counts.
        val bomb = zip(1, 1024 * 1024)
        assertEquals(true, Files.size(bomb) < 10_000)
        assertFailsWith<CompanySelfException> { BackupImportLimits.validateZip(bomb, maxUncompressed = 100_000) }
    }

    @Test
    fun emptyOrNotAZipIsRefused() {
        assertFailsWith<CompanySelfException> { BackupImportLimits.validateZip(zip(0, 0)) }
        val text = Files.createTempFile("backup", ".zip").also { Files.writeString(it, "no soy un zip") }
        assertFailsWith<CompanySelfException> { BackupImportLimits.validateZip(text) }
    }

    private fun zipBytes(bytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("a.txt")); zip.write(ByteArray(bytes) { (it % 251).toByte() }); zip.closeEntry() }
        return out.toByteArray()
    }

    // Review item 3: the import goes through the company's quota, free-disk floor and caps, before writing anything.
    @Test
    fun theImportIsChargedToTheQuotaAndChecksComeBeforeWriting() {
        val root = Files.createTempDirectory("backups")
        val directory = Files.createDirectories(root.resolve("acme"))
        val zip = zipBytes(4_000)
        var quota: Long? = zip.size + 100L
        val guard = com.ideasdeveloper.bms.server.storage.StorageGuard(null, { quota }, minFreeBytes = 0, freeSpace = { 10_000_000_000 }, extraRoots = listOf(root))
        BackupImportLimits.importInto(directory, directory.resolve("acme-backup-20260101T000000Z.zip"), zip.inputStream(), zip.size.toLong(), "acme", guard)
        assertEquals(zip.size.toLong(), guard.usage("acme").usedBytes)
        // Over the quota: refused, and no file (not even the .part) is left.
        assertFailsWith<CompanySelfException> {
            BackupImportLimits.importInto(directory, directory.resolve("acme-backup-20260102T000000Z.zip"), zip.inputStream(), null, "acme", guard)
        }
        assertEquals(listOf("acme-backup-20260101T000000Z.zip"), Files.list(directory).use { it.map { p -> p.fileName.toString() }.toList() })
        // Declared too large: refused before anything is written.
        quota = null
        val floor = com.ideasdeveloper.bms.server.storage.StorageGuard(null, { null }, minFreeBytes = 1_000, freeSpace = { 500 }, extraRoots = listOf(root))
        assertFailsWith<CompanySelfException> {
            BackupImportLimits.importInto(directory, directory.resolve("acme-backup-20260103T000000Z.zip"), object : java.io.InputStream() {
                override fun read(): Int = error("nothing may be read before the checks")
            }, 10, "acme", floor)
        }
        assertEquals(1, Files.list(directory).use { it.count() })
    }

    @Test
    fun theNumberOfStoredBackupsIsCapped() {
        val directory = Files.createTempDirectory("backups-cap")
        repeat(BackupImportLimits.MaxStoredBackups) { Files.write(directory.resolve("old$it.zip"), ByteArray(1)) }
        assertFailsWith<CompanySelfException> {
            BackupImportLimits.importInto(directory, directory.resolve("acme-backup-20260109T000000Z.zip"), zipBytes(10).inputStream(), null, "acme", null)
        }
    }
}
