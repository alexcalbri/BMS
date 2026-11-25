package com.ideasdeveloper.bms.modules.tareas

import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TareasCleanupTest {
    @Test
    fun `un archivo que ya no existe cuenta como borrado`() {
        assertTrue(TareasRepository.isMissingFile(NoSuchFileException("x")))
        assertTrue(TareasRepository.isMissingFile(FileNotFoundException("x")))
        assertTrue(TareasRepository.isMissingFile(IllegalStateException("fallo", NoSuchFileException("x"))))
    }

    @Test
    fun `otros fallos no se tratan como archivo inexistente`() {
        assertFalse(TareasRepository.isMissingFile(java.io.IOException("disco lleno")))
        assertFalse(TareasRepository.isMissingFile(IllegalStateException("sin almacenamiento")))
    }
}
