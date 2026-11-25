package com.ideasdeveloper.bms.server.modules

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.security.MessageDigest
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

/**
 * Small lazy HikariCP pools, one per company database (jdbcUrl + user). Nothing is opened until the first request and
 * idle connections are released after a minute, so a server with many companies keeps little memory. Closing a returned
 * connection gives it back to the pool; Hikari restores autoCommit (true by default) and rolls back open transactions.
 */
class TenantPools(
    // A job holds its own connection plus the target one while requests keep arriving, so 3 ran out.
    private val maxPoolSize: Int = 4,
    // How long a replaced pool (changed password) keeps serving the connections already borrowed.
    private val retireDelayMillis: Long = 30_000,
) {
    private class Entry(val passwordDigest: ByteArray, val pool: HikariDataSource)

    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile private var closed = false

    fun connection(jdbcUrl: String, username: String, password: String): Connection =
        poolFor(jdbcUrl, username, password).connection

    private fun poolFor(jdbcUrl: String, username: String, password: String): HikariDataSource {
        check(!closed) { "Los pools de empresas ya estan cerrados." }
        val key = key(jdbcUrl, username)
        val digest = sha256(password)
        var replaced: HikariDataSource? = null
        val entry = entries.compute(key) { _, old ->
            if (old != null && !old.pool.isClosed && MessageDigest.isEqual(old.passwordDigest, digest)) {
                old
            } else {
                replaced = old?.pool
                Entry(digest, create(key, jdbcUrl, username, password))
            }
        }!!
        replaced?.let(::retire)
        return entry.pool
    }

    private fun create(key: String, jdbcUrl: String, username: String, password: String) = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = username
        this.password = password
        poolName = "tenant-" + key.hashCode().toUInt().toString(16)
        maximumPoolSize = maxPoolSize
        minimumIdle = 0
        idleTimeout = 60_000
        maxLifetime = 600_000
        connectionTimeout = 5_000
        isAutoCommit = true
        connectionInitSql = "SET search_path TO public"
        // Do not open connections at construction: the first borrow does it.
        initializationFailTimeout = -1
    })

    // The old pool stops handing out new connections (outside the map lock); borrowed ones finish before it is closed.
    private fun retire(pool: HikariDataSource) {
        runCatching { pool.hikariPoolMXBean?.softEvictConnections() }
        Thread({
            try {
                Thread.sleep(retireDelayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            runCatching { pool.close() }
        }, "tenant-pool-retire").apply { isDaemon = true }.start()
    }

    fun close() {
        closed = true
        val all = entries.values.toList()
        entries.clear()
        all.forEach { runCatching { it.pool.close() } }
    }

    companion object {
        /** Cache key of a database and role (the password is compared separately so a rotation replaces the pool). */
        fun key(jdbcUrl: String, username: String): String = "$jdbcUrl|$username"

        private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    }
}
