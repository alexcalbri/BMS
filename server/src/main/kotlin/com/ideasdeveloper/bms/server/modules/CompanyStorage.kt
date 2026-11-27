package com.ideasdeveloper.bms.server.modules

import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.auditDetails
import com.ideasdeveloper.bms.server.i18n.tr
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

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

/** "1.5 GB", "320 MB", "12 KB" (decimal units are not needed: the owner sets quotas in GB). */
fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0 || value >= 10) "${value.toLong()} ${units[unit]}" else "%.1f %s".format(java.util.Locale.ROOT, value, units[unit])
}

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
        val base = root ?: return 0
        if (!companyPattern.matches(code)) return 0
        val directory = base.toAbsolutePath().normalize().resolve(code)
        if (!Files.isDirectory(directory)) return 0
        return Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
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

/**
 * Quotas set by the server owner, in server_settings (no migration): the default of every company
 * and an override per company. 0 means unlimited. Read through a short cache: every upload asks.
 */
class StorageQuotas(private val central: DataSource, private val clock: () -> Long = System::currentTimeMillis) {
    private class Cached(val value: Long?, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    private fun setting(key: String): Long? {
        val now = clock()
        cache[key]?.takeIf { now - it.at < CacheMillis }?.let { return it.value }
        val value = try {
            central.connection.use { connection ->
                connection.prepareStatement("SELECT setting_value FROM server_settings WHERE setting_key = ?").use { query ->
                    query.setString(1, key)
                    query.executeQuery().use { rows -> if (rows.next()) rows.getString(1)?.trim()?.toLongOrNull() else null }
                }
            }
        } catch (_: java.sql.SQLException) {
            // The database is down: keep what was known rather than lifting every limit.
            return cache[key]?.value
        }
        cache[key] = Cached(value, now)
        return value
    }

    fun defaultQuotaBytes(): Long = setting(DefaultKey)?.takeIf { it >= 0 } ?: DefaultQuotaBytes

    /** The company's own quota, null when it follows the default. */
    fun customQuotaBytes(companyCode: String): Long? = setting(companyKey(companyCode))?.takeIf { it >= 0 }

    /** The quota that applies now; null = unlimited. */
    fun effectiveQuotaBytes(companyCode: String): Long? = (customQuotaBytes(companyCode) ?: defaultQuotaBytes()).takeIf { it > 0 }

    /** Sets the default (audited in the same transaction). */
    fun setDefault(quotaBytes: Long, actor: AuditActor) {
        validate(quotaBytes)
        val previous = defaultQuotaBytes()
        write(DefaultKey, quotaBytes, actor, "storage.default_quota_changed", null, auditDetails("from" to previous, "to" to quotaBytes))
    }

    /** Sets (or with null removes) the quota of one company (audited in the same transaction). */
    fun setCompany(companyCode: String, quotaBytes: Long?, actor: AuditActor) {
        quotaBytes?.let(::validate)
        val previous = customQuotaBytes(companyCode)
        write(companyKey(companyCode), quotaBytes, actor, "company.storage_quota_changed", companyCode, auditDetails("from" to previous, "to" to quotaBytes))
    }

    private fun validate(quotaBytes: Long) {
        if (quotaBytes < 0 || quotaBytes > MaxQuotaBytes) throw IllegalArgumentException(tr("La cuota debe estar entre 0 (sin limite) y 1 PB."))
    }

    private fun write(key: String, value: Long?, actor: AuditActor, action: String, companyCode: String?, details: String) {
        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (value == null) {
                    connection.prepareStatement("DELETE FROM server_settings WHERE setting_key = ?").use { it.setString(1, key); it.executeUpdate() }
                } else {
                    connection.prepareStatement(
                        """
                        INSERT INTO server_settings (setting_key, setting_value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)
                        ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = CURRENT_TIMESTAMP
                        """.trimIndent()
                    ).use { upsert ->
                        upsert.setString(1, key)
                        upsert.setString(2, value.toString())
                        upsert.executeUpdate()
                    }
                }
                ProvisioningAudit.insert(connection, actor, action, companyCode, null, details)
                connection.commit()
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            } finally {
                runCatching { connection.autoCommit = true }
            }
        }
        cache.remove(key)
    }

    companion object {
        const val DefaultQuotaBytes = 100L * 1024 * 1024 * 1024
        const val MaxQuotaBytes = 1024L * 1024 * 1024 * 1024 * 1024
        const val DefaultKey = "storage.quota.default"
        const val CacheMillis = 30_000L
        fun companyKey(companyCode: String) = "storage.quota.company.$companyCode"
    }
}
