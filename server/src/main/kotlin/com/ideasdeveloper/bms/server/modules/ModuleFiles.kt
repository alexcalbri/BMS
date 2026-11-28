package com.ideasdeveloper.bms.server.modules

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.storage.CompanyFiles
import java.io.InputStream
import java.nio.file.Files

class FileTooLargeException(val maxBytes: Long) : Exception(tr("El archivo supera el maximo de {0} MB.", maxBytes / (1024 * 1024)))

/** A file written for a module: what the module stores in its table to read or delete it later. */
data class StoredFileRef(val backend: String, val ref: String, val size: Long)

/** Files of one module in one company: what a module uses to store, read and delete its files. */
class ModuleFiles internal constructor(private val files: CompanyFiles, val companyCode: String, val moduleId: String) {
    /**
     * Stores [content] (at most [maxBytes], or [FileTooLargeException]) and returns where it went.
     * Also refused with [StorageQuotaException] when the company's quota, the free-disk floor or the
     * cap on uploads in progress is reached (STORAGE_QUOTA.md); a refused upload leaves no file.
     */
    fun put(name: String, contentType: String, content: InputStream, maxBytes: Long): StoredFileRef =
        files.write(companyCode, moduleId, name, contentType, content, maxBytes)

    /**
     * Cheap check before reading an upload body: [StorageQuotaException] when the company is over
     * its quota, [expectedBytes] (Content-Length, null when unknown) does not fit in what is left, or
     * the disk is under the free-space floor. Reserves nothing.
     */
    fun checkRoom(expectedBytes: Long? = null) = files.checkRoom(companyCode, expectedBytes)

    /** What the company uses and may use now (all modules together). */
    fun usage(): StorageUsage = files.usage(companyCode)

    fun open(file: StoredFileRef): InputStream? = files.read(companyCode, moduleId, file)

    fun delete(file: StoredFileRef) = files.delete(companyCode, moduleId, file)
}
