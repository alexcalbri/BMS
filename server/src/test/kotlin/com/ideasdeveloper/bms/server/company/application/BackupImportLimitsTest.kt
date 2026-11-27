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
}
