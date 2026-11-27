package com.ideasdeveloper.bms.extensions.meta

import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetaDiagnosticsFloodTest {
    // A connection that only counts the statements prepared on it.
    private fun counting(catalog: String, statements: AtomicInteger) = Proxy.newProxyInstance(
        Connection::class.java.classLoader, arrayOf(Connection::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getCatalog" -> catalog
            "prepareStatement" -> {
                statements.incrementAndGet()
                Proxy.newProxyInstance(java.sql.PreparedStatement::class.java.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, inner, _ ->
                    if (inner.returnType == Int::class.javaPrimitiveType) 1 else null
                } as java.sql.PreparedStatement
            }
            else -> null
        }
    } as Connection

    @Test
    fun unauthenticatedRejectionsAreWrittenOncePerMinute() {
        val statements = AtomicInteger()
        val tenant = counting("flood_${System.nanoTime()}", statements)
        repeat(500) { CrmMeta.log(tenant, "firma_rechazada", "firma invalida o ausente") }
        // One insert and one trim: the other 499 do not touch the database.
        assertEquals(2, statements.get())
        // Other events are never throttled (a real event, a connection error).
        statements.set(0)
        repeat(3) { CrmMeta.log(tenant, "evento", "guardados=1") }
        assertEquals(6, statements.get())
    }

    @Test
    fun throttleIsPerKeyAndExpires() {
        assertFalse(CrmMeta.rejectionThrottled("a|x", 1_000))
        assertTrue(CrmMeta.rejectionThrottled("a|x", 30_000))
        assertFalse(CrmMeta.rejectionThrottled("b|x", 30_000))
        assertFalse(CrmMeta.rejectionThrottled("a|x", 62_000))
    }
}
