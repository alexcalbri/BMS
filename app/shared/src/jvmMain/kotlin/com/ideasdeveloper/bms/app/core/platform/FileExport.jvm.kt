package com.ideasdeveloper.bms.app.core.platform

import com.ideasdeveloper.bms.app.core.i18n.tr
import java.io.File

actual fun saveTextFile(fileName: String, content: String, mimeType: String): String =
    // El BOM permite que Excel abra el CSV como UTF-8.
    saveBinaryFile(fileName, ("﻿" + content).toByteArray(Charsets.UTF_8), mimeType)

actual fun saveBinaryFile(fileName: String, bytes: ByteArray, mimeType: String): String {
    val home = File(System.getProperty("user.home"))
    val directory = File(home, "Downloads").takeIf { it.isDirectory } ?: home
    val target = File(directory, File(fileName).name)
    return try {
        target.writeBytes(bytes)
        tr("Archivo guardado en {0}", target.absolutePath)
    } catch (exception: Exception) {
        tr("No se pudo guardar el archivo: {0}", exception.message)
    }
}
