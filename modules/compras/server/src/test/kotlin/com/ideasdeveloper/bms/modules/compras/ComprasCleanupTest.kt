package com.ideasdeveloper.bms.modules.compras

import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComprasCleanupTest {
    @Test
    fun missingFileCountsAsDeleted() {
        assertTrue(ComprasAttachments.isMissingFile(NoSuchFileException("a")))
        assertTrue(ComprasAttachments.isMissingFile(FileNotFoundException("a")))
        assertTrue(ComprasAttachments.isMissingFile(IllegalStateException("fallo", NoSuchFileException("a"))))
    }

    @Test
    fun otherFailuresAreNotMissingFile() {
        assertFalse(ComprasAttachments.isMissingFile(IOException("disco lleno")))
        assertFalse(ComprasAttachments.isMissingFile(IllegalStateException("x")))
    }
}
