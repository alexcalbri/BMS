package com.ideasdeveloper.bms.server.storage

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.FileTooLargeException
import com.ideasdeveloper.bms.server.modules.ModuleFiles
import com.ideasdeveloper.bms.server.modules.StorageUsage
import com.ideasdeveloper.bms.server.modules.StoredFileRef
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.UUID

/**
 * Where a backend keeps the files of the modules. Each company uses one backend for new files
 * (chosen in [CompanyFiles]); every stored file records the backend and reference it was written
 * with, so changing a company to another backend (e.g. Google Drive) keeps its old files readable.
 */
interface FileBackend {
    // Stored with each file, e.g. "local" or "gdrive". Never change it once files exist.
    val id: String

    // Writes the whole stream and returns the reference to find the file again. [name] is only a
    // hint for backends that show files to people (a Drive folder); local storage ignores it.
    fun write(companyCode: String, moduleId: String, name: String, contentType: String, content: InputStream): String

    fun read(companyCode: String, moduleId: String, ref: String): InputStream?

    // Removing a file that is already gone is not an error.
    fun delete(companyCode: String, moduleId: String, ref: String)

    // Everything of a company, when the company is deleted.
    fun deleteCompany(companyCode: String) = Unit
}

/**
 * Files on the server disk under [root]/{company}/{module}/{yyyy}/{mm}/{uuid}. The name the user
 * gave never reaches the disk, so it cannot escape the folder or collide.
 */
class LocalFileBackend(private val root: Path) : FileBackend {
    override val id = Local

    private val companyPattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val refPattern = Regex("[0-9]{4}/[0-9]{2}/[0-9a-f-]{36}")

    private fun moduleDir(companyCode: String, moduleId: String): Path {
        require(companyPattern.matches(companyCode) && companyPattern.matches(moduleId)) { "Empresa o modulo invalido." }
        return root.toAbsolutePath().normalize().resolve(companyCode).resolve(moduleId)
    }

    private fun resolve(companyCode: String, moduleId: String, ref: String): Path {
        require(refPattern.matches(ref)) { "Referencia de archivo invalida." }
        return moduleDir(companyCode, moduleId).resolve(ref)
    }

    override fun write(companyCode: String, moduleId: String, name: String, contentType: String, content: InputStream): String {
        val today = LocalDate.now()
        // Locale.ROOT: %d uses the default locale's digits (Arabic-Indic under ar-EG or fa-IR), which refPattern rejects.
        val ref = "%04d/%02d/%s".format(java.util.Locale.ROOT, today.year, today.monthValue, UUID.randomUUID())
        val target = resolve(companyCode, moduleId, ref)
        Files.createDirectories(target.parent)
        // Written aside and moved at the end: a cut upload never leaves half a file under the ref.
        val partial = target.resolveSibling("${target.fileName}.part")
        try {
            Files.copy(content, partial, StandardCopyOption.REPLACE_EXISTING)
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Exception) {
            Files.deleteIfExists(partial)
            throw failure
        }
        return ref
    }

    override fun read(companyCode: String, moduleId: String, ref: String): InputStream? =
        resolve(companyCode, moduleId, ref).takeIf { Files.isRegularFile(it) }?.let { Files.newInputStream(it) }

    override fun delete(companyCode: String, moduleId: String, ref: String) {
        Files.deleteIfExists(resolve(companyCode, moduleId, ref))
    }

    override fun deleteCompany(companyCode: String) {
        require(companyPattern.matches(companyCode)) { "Empresa invalida." }
        val directory = root.toAbsolutePath().normalize().resolve(companyCode)
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        const val Local = "local"
    }
}

/**
 * The file storage of every company. New files go to the backend [backendFor] chooses for the
 * company (today always the local disk; a per-company setting, e.g. Google Drive configured by
 * the business owner or the server owner, plugs in here); existing files are read from the
 * backend they were written to. [guard] enforces the storage quota, the free-disk floor and the
 * cap on uploads in progress (STORAGE_QUOTA.md); without one nothing is limited (tests, tools).
 */
class CompanyFiles(
    private val backends: List<FileBackend>,
    private val backendFor: (companyCode: String) -> String = { LocalFileBackend.Local },
    val guard: StorageGuard? = null,
) {
    init {
        require(backends.map { it.id }.toSet().size == backends.size) { "Cada backend de archivos necesita un id distinto." }
    }

    private fun backend(id: String) = backends.firstOrNull { it.id == id }
        ?: throw IllegalStateException(tr("El almacenamiento '{0}' no esta disponible en este servidor.", id))

    fun module(companyCode: String, moduleId: String) = ModuleFiles(this, companyCode, moduleId)

    fun deleteCompany(companyCode: String) {
        backends.forEach { it.deleteCompany(companyCode) }
        guard?.forget(companyCode)
    }

    internal fun checkRoom(companyCode: String, expectedBytes: Long?) {
        guard?.checkRoom(companyCode, expectedBytes)
    }

    internal fun usage(companyCode: String): StorageUsage = guard?.usage(companyCode) ?: StorageUsage(0, null, null)

    internal fun write(companyCode: String, moduleId: String, name: String, contentType: String, content: InputStream, maxBytes: Long): StoredFileRef {
        val backend = backend(backendFor(companyCode))
        val upload = guard?.begin(companyCode)
        try {
            upload?.checkRoom()
            val counted = LimitedInputStream(content, maxBytes, upload)
            val ref = backend.write(companyCode, moduleId, name, contentType, counted)
            upload?.stored(counted.count)
            return StoredFileRef(backend.id, ref, counted.count)
        } finally {
            upload?.close()
        }
    }

    internal fun read(companyCode: String, moduleId: String, file: StoredFileRef): InputStream? = backend(file.backend).read(companyCode, moduleId, file.ref)

    internal fun delete(companyCode: String, moduleId: String, file: StoredFileRef) {
        backend(file.backend).delete(companyCode, moduleId, file.ref)
        guard?.removed(companyCode, file.size)
    }

    companion object {
        fun local(root: Path) = CompanyFiles(listOf(LocalFileBackend(root)))

        /** The server's storage: local disk under [root] with the limits of [guard]. */
        fun local(root: Path, guard: StorageGuard) = CompanyFiles(listOf(LocalFileBackend(root)), guard = guard)
    }
}

// Counts what passes and fails as soon as the limit is crossed, so an oversize upload stops early.
// With an [upload] every chunk is also charged to the company's quota and the disk floor.
private class LimitedInputStream(input: InputStream, private val maxBytes: Long, private val upload: StorageGuard.Upload? = null) : FilterInputStream(input) {
    var count = 0L
        private set

    private fun add(read: Int): Int {
        if (read > 0) {
            count += read
            if (count > maxBytes) throw FileTooLargeException(maxBytes)
            upload?.charge(read.toLong())
        }
        return read
    }

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = add(super.read(b, off, len))
}
