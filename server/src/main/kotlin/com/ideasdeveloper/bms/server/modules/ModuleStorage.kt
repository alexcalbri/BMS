package com.ideasdeveloper.bms.server.modules

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.storage.formatBytes

/** What a company stores and may store (STORAGE_QUOTA.md). [quotaBytes] null = unlimited; [freeDiskBytes] null = unknown. */
data class StorageUsage(val usedBytes: Long, val quotaBytes: Long?, val freeDiskBytes: Long?)

/**
 * An upload refused for room: [code] STORAGE_QUOTA_EXCEEDED (413), STORAGE_DISK_FULL (507) or
 * STORAGE_BUSY (429). The message is translated for the request that caused it.
 */
class StorageQuotaException(val code: String, val httpStatus: Int, message: String) : Exception(message) {
    companion object {
        const val QuotaExceeded = "STORAGE_QUOTA_EXCEEDED"
        const val DiskFull = "STORAGE_DISK_FULL"
        const val Busy = "STORAGE_BUSY"

        fun quota(used: Long, quota: Long) = StorageQuotaException(
            QuotaExceeded, 413, tr("La empresa alcanzo su espacio de almacenamiento ({0} de {1}).", formatBytes(used), formatBytes(quota)),
        )

        fun diskFull() = StorageQuotaException(DiskFull, 507, tr("El servidor no tiene espacio libre suficiente para guardar archivos."))

        fun busy() = StorageQuotaException(Busy, 429, tr("Hay demasiadas subidas en curso para esta empresa. Espera a que terminen."))
    }
}
