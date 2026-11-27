package com.ideasdeveloper.bms.modules.crm

import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A company database for tests that need no PostgreSQL: it records the SQL it receives (in [statements], where the rows of
 * the activity log can be interleaved with [mark]) and answers queries with the rows given to [rows] (the first script
 * whose text is contained in the SQL, case-insensitively). Everything else is an empty result and one updated row.
 * Values are read by position (1-based), as the CRM does.
 */
class FakeTenant {
    val statements = mutableListOf<String>()
    var autoCommit = true
    var commits = 0
    var rollbacks = 0
    private val scripts = mutableListOf<Triple<String, List<String>, List<List<Any?>>>>()
    private val updates = mutableMapOf<String, Int>()

    /** Queries whose SQL contains [match] return [result] (each row a list of column values). */
    fun rows(match: String, vararg result: List<Any?>): FakeTenant = apply { scripts += Triple(match.lowercase(), emptyList(), result.toList()) }

    /** Like [rows] for code that reads by column name ([columns] in the order of the values). */
    fun named(match: String, columns: List<String>, vararg result: List<Any?>): FakeTenant = apply { scripts += Triple(match.lowercase(), columns, result.toList()) }

    /** Statements whose SQL contains [match] update [count] rows. */
    fun updated(match: String, count: Int): FakeTenant = apply { updates[match.lowercase()] = count }

    /** Adds [text] to [statements] (the order of the log rows against the SQL). */
    fun mark(text: String) {
        statements += text
    }

    fun sqlContaining(text: String) = statements.filter { it.contains(text, ignoreCase = true) }

    val connection: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
        when (method.name) {
            "prepareStatement" -> statement(args[0] as String)
            "getAutoCommit" -> autoCommit
            "setAutoCommit" -> { autoCommit = args[0] as Boolean; null }
            "commit" -> { commits++; null }
            "rollback" -> { rollbacks++; null }
            "createArrayOf" -> Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Array::class.java)) { _, m, _ ->
                when (m.name) { "getArray" -> (args[1] as Array<*>); else -> null }
            }
            "close", "setSavepoint", "releaseSavepoint" -> null
            "isClosed" -> false
            "toString" -> "FakeTenant"
            "hashCode" -> 0
            "equals" -> false
            else -> null
        }
    } as Connection

    private fun statement(sql: String): PreparedStatement {
        statements += sql.trim().replace(Regex("\\s+"), " ")
        val lower = sql.lowercase()
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, _ ->
            when (method.name) {
                "executeUpdate" -> updates.entries.firstOrNull { lower.contains(it.key) }?.value ?: 1
                "executeQuery" -> scripts.firstOrNull { lower.contains(it.first) }.let { resultOf(it?.third.orEmpty(), it?.second.orEmpty()) }
                "executeBatch" -> IntArray(0)
                "execute" -> false
                "toString" -> "FakeStatement"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as PreparedStatement
    }

    private fun resultOf(rows: List<List<Any?>>, columns: List<String>): ResultSet {
        var index = -1
        var last: Any? = null
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, method, args ->
            fun value(): Any? = when (val column = args!![0]) {
                is Int -> rows[index].getOrNull(column - 1)
                is String -> columns.indexOf(column).takeIf { it >= 0 }?.let { rows[index].getOrNull(it) }
                else -> null
            }.also { last = it }
            when (method.name) {
                "next" -> ++index < rows.size
                "wasNull" -> last == null
                "getString" -> value()?.toString()
                "getInt" -> (value() as? Number)?.toInt() ?: 0
                "getLong" -> (value() as? Number)?.toLong() ?: 0L
                "getBoolean" -> value() as? Boolean ?: false
                "getBigDecimal" -> value()?.let { it as? BigDecimal ?: BigDecimal(it.toString()) }
                "getTimestamp" -> value()?.let { if (it is Instant) Timestamp.from(it) else it as? Timestamp }
                "getObject" -> {
                    val found = value()
                    val type = args!!.getOrNull(1) as? Class<*>
                    when {
                        found == null -> null
                        type == UUID::class.java -> found as? UUID ?: UUID.fromString(found.toString())
                        type == LocalDate::class.java -> found as? LocalDate ?: LocalDate.parse(found.toString())
                        else -> found
                    }
                }
                "getArray" -> value()?.let { stored ->
                    Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Array::class.java)) { _, m, _ ->
                        when (m.name) { "getArray" -> (stored as Collection<*>).toTypedArray(); else -> null }
                    }
                }
                "close" -> null
                "toString" -> "FakeResult"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as ResultSet
    }
}

/** A secret box for tests: sealing reverses the text, so a stored value is recognisable and never the plain one. */
object FakeSecrets {
    fun install() {
        CrmSecrets.box = object : com.ideasdeveloper.bms.server.security.SecretBox {
            override val available = true
            override fun seal(tenant: Connection, plain: String) = com.ideasdeveloper.bms.server.security.SecretBox.SealedPrefix + plain.reversed()
            override fun open(tenant: Connection, value: String) = value.removePrefix(com.ideasdeveloper.bms.server.security.SecretBox.SealedPrefix).reversed()
        }
    }

    fun sealed(plain: String) = com.ideasdeveloper.bms.server.security.SecretBox.SealedPrefix + plain.reversed()

    fun remove() {
        CrmSecrets.box = null
    }
}
