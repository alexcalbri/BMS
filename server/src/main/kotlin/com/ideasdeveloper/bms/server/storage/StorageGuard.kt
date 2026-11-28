package com.ideasdeveloper.bms.server.storage

import com.ideasdeveloper.bms.server.modules.ModuleFiles
import com.ideasdeveloper.bms.server.modules.StorageQuotaException
import com.ideasdeveloper.bms.server.modules.StorageUsage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The limits of [CompanyFiles] for every company: its quota ([quotaFor], null = unlimited), a free
 * disk floor on the files volume and a cap on uploads in progress at the same time. Usage is what
 * the company's folder under [root] holds, measured at most every [remeasureMillis] and adjusted in
 * memory by every write, delete and upload in progress in between.
 */
class StorageGuard(
    private val root: Path?,
    private val quotaFor: (companyCode: String) -> Long?,
    val minFreeBytes: Long = DefaultMinFreeBytes,
    val maxConcurrentUploads: Int = DefaultMaxConcurrentUploads,
    private val freeSpace: () -> Long? = { root?.let(::usableSpace) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val remeasureMillis: Long = 10 * 60_000L,
    // Other folders of the company that count in its quota, {root}/{company} each (its backups).
    private val extraRoots: List<Path> = emptyList(),
) {
    internal class Company {
        @Volatile var measuredAt = Long.MIN_VALUE
        val used = AtomicLong(0)
        val inFlight = AtomicLong(0)
        val uploads = AtomicInteger(0)
    }

    private val companies = ConcurrentHashMap<String, Company>()
    private val companyPattern = Regex("[a-z][a-z0-9_]{0,62}")

    private fun company(code: String): Company {
        val entry = companies.computeIfAbsent(code) { Company() }
        val now = clock()
        if (entry.measuredAt == Long.MIN_VALUE || now - entry.measuredAt >= remeasureMillis) {
            synchronized(entry) {
                if (entry.measuredAt == Long.MIN_VALUE || now - entry.measuredAt >= remeasureMillis) {
                    entry.used.set(measure(code))
                    entry.measuredAt = now
                }
            }
        }
        return entry
    }

    // Bytes of every file under {root}/{company}; 0 without a local root or folder.
    private fun measure(code: String): Long {
        if (!companyPattern.matches(code)) return 0
        return (listOfNotNull(root) + extraRoots).sumOf { base ->
            val directory = base.toAbsolutePath().normalize().resolve(code)
            if (!Files.isDirectory(directory)) 0L else Files.walk(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
            }
        }
    }

    fun usage(companyCode: String): StorageUsage {
        val entry = company(companyCode)
        return StorageUsage(entry.used.get(), quotaFor(companyCode), freeSpace())
    }

    /** See [ModuleFiles.checkRoom]. */
    fun checkRoom(companyCode: String, expectedBytes: Long?) {
        val entry = company(companyCode)
        checkDisk(expectedBytes ?: 0)
        val quota = quotaFor(companyCode) ?: return
        val taken = entry.used.get() + entry.inFlight.get()
        if (taken >= quota || (expectedBytes != null && taken + expectedBytes > quota)) throw StorageQuotaException.quota(entry.used.get(), quota)
    }

    private fun checkDisk(extra: Long) {
        val free = freeSpace() ?: return
        if (free - extra.coerceAtLeast(0) < minFreeBytes) throw StorageQuotaException.diskFull()
    }

    /** Starts an upload of [companyCode]: refused with STORAGE_BUSY over the cap. Close it in a finally. */
    fun begin(companyCode: String): Upload {
        val entry = company(companyCode)
        if (entry.uploads.incrementAndGet() > maxConcurrentUploads) {
            entry.uploads.decrementAndGet()
            throw StorageQuotaException.busy()
        }
        return Upload(companyCode, entry)
    }

    /** One upload in progress: what it read counts in the quota at once (so parallel uploads cannot overshoot it). */
    inner class Upload internal constructor(private val companyCode: String, private val company: Company) : AutoCloseable {
        private var charged = 0L
        private var sinceDiskCheck = 0L
        private var closed = false

        fun checkRoom() = checkRoom(companyCode, null)

        fun charge(bytes: Long) {
            charged += bytes
            val taken = company.inFlight.addAndGet(bytes) + company.used.get()
            val quota = quotaFor(companyCode)
            if (quota != null && taken > quota) throw StorageQuotaException.quota(company.used.get(), quota)
            sinceDiskCheck += bytes
            if (sinceDiskCheck >= DiskCheckEveryBytes) {
                sinceDiskCheck = 0
                checkDisk(0)
            }
        }

        /** The file was kept: what it read moves from "in progress" to "used". */
        fun stored(bytes: Long) {
            company.inFlight.addAndGet(-charged)
            charged = 0
            company.used.addAndGet(bytes)
        }

        override fun close() {
            if (closed) return
            closed = true
            if (charged != 0L) company.inFlight.addAndGet(-charged)
            charged = 0
            company.uploads.decrementAndGet()
        }
    }

    fun removed(companyCode: String, bytes: Long) {
        if (bytes <= 0) return
        companies[companyCode]?.used?.updateAndGet { (it - bytes).coerceAtLeast(0) }
    }

    fun forget(companyCode: String) {
        companies.remove(companyCode)
    }

    companion object {
        const val DefaultMinFreeBytes = 2L * 1024 * 1024 * 1024
        const val DefaultMaxConcurrentUploads = 4
        const val DiskCheckEveryBytes = 8L * 1024 * 1024

        fun usableSpace(path: Path): Long? = runCatching {
            var existing: Path? = path.toAbsolutePath()
            while (existing != null && !Files.exists(existing)) existing = existing.parent
            existing?.let { Files.getFileStore(it).usableSpace }
        }.getOrNull()

        fun totalSpace(path: Path): Long? = runCatching {
            var existing: Path? = path.toAbsolutePath()
            while (existing != null && !Files.exists(existing)) existing = existing.parent
            existing?.let { Files.getFileStore(it).totalSpace }
        }.getOrNull()
    }
}
