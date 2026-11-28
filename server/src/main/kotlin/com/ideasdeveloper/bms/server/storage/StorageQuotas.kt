package com.ideasdeveloper.bms.server.storage

import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.auditDetails
import com.ideasdeveloper.bms.server.i18n.tr
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

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
