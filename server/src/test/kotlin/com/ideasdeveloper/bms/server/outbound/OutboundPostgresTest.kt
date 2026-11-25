package com.ideasdeveloper.bms.server.outbound

import com.ideasdeveloper.bms.server.outbound.monday.JdbcMondayOutboundConfigStore
import com.ideasdeveloper.bms.server.outbound.monday.MondayColumnRef
import com.ideasdeveloper.bms.server.outbound.monday.MondayOutboundConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SQL real de la cola de salida contra un PostgreSQL de pruebas. Solo corre con BMS_OUTBOUND_PG_URL
 * (jdbc:postgresql://host/base?user=...&password=...) apuntando a una base VACIA y desechable: crea y borra tablas.
 */
class OutboundPostgresTest {
    private val url: String? = System.getenv("BMS_OUTBOUND_PG_URL")?.takeIf { it.isNotBlank() }
    private val now = Instant.parse("2026-11-19T12:00:00Z")

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "database/tenant/migrations").isDirectory }

    private fun withDb(block: (Connection) -> Unit) {
        val target = url ?: return
        DriverManager.getConnection(target).use { db ->
            db.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS outbound_events, outbound_links, integration_connections, ext_monday__outbound, tenant_extension_objects, tenant_extension_settings, tenant_extensions, tenant_data_keys CASCADE")
                st.execute(File(root(), "database/tenant/migrations/V006__create_extensions.sql").readText())
                st.execute(File(root(), "database/tenant/migrations/V009__outbound.sql").readText())
                st.execute(File(root(), "extensions/monday/migrations/V002__outbound.sql").readText())
                st.execute("INSERT INTO tenant_extensions (extension_id, module_id, version, status, enabled_at) VALUES ('monday', 'crm', '1.1.0', 'enabled', now()), ('otra', 'crm', '1.0.0', 'disabled', NULL)")
            }
            block(db)
        }
    }

    private val store = JdbcOutboundStore

    private fun created(id: String) = OutboundEvent.created("crm", "lead", id)

    private fun updated(id: String, vararg keys: String) = OutboundEvent.updated("crm", "lead", id, keys.toList())

    @Test
    fun queueLifecycleAgainstPostgres() = withDb { db ->
        assertEquals(setOf("monday"), store.enabledSinks(db, listOf("monday", "otra", "nadie")))

        assertFalse(store.hasWork(db))
        store.enqueue(db, "monday", created("A"), now)
        assertTrue(store.hasWork(db))
        store.enqueue(db, "monday", updated("A", "stage"), now)
        // Un updated pendiente sin intentar del mismo registro se une con el nuevo.
        store.enqueue(db, "monday", updated("A", "owners", "stage"), now)
        store.enqueue(db, "monday", created("B"), now)
        assertEquals(3, store.events(db, "monday", emptyList(), 50).size)

        // Solo la cabeza de cada registro: created A y created B; el updated de A espera.
        val first = store.claimDue(db, listOf("monday"), now, 10)
        assertEquals(listOf("A", "B"), first.map { it.event.recordId })
        assertTrue(first.all { it.event.kind == OutboundEventKind.Created })
        assertTrue(store.claimDue(db, listOf("monday"), now, 10).isEmpty())

        val (a, b) = first
        assertTrue(store.markSent(db, a.id, now, "ok | Avisos: sin columna"))
        assertFalse(store.markSent(db, a.id, now, "otra vez"), "un aviso ya enviado no se vuelve a marcar")
        assertTrue(store.markFailed(db, b.id, now, 1, "columna invalida"))

        val next = store.claimDue(db, listOf("monday"), now, 10)
        assertEquals(1, next.size)
        assertEquals(OutboundEventKind.Updated, next.single().event.kind)
        assertEquals(listOf("stage", "owners"), next.single().event.changed)

        // B fallo en el alta: sus notas esperan.
        store.enqueue(db, "monday", OutboundEvent.note("crm", "lead", "B", "n1", "hola", null), now)
        assertTrue(store.claimDue(db, listOf("monday"), now, 10).isEmpty())

        assertTrue(store.markRetry(db, next.single().id, now, 1, now.plus(Duration.ofMinutes(1)), "red"))
        assertTrue(store.claimDue(db, listOf("monday"), now, 10).isEmpty())
        assertEquals(1, store.claimDue(db, listOf("monday"), now.plus(Duration.ofMinutes(2)), 10).size)

        val summary = store.summary(db, "monday", now)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.sentLast24h)

        assertTrue(store.retry(db, "monday", b.id, now))
        val retried = store.claimDue(db, listOf("monday"), now, 10)
        assertEquals(listOf("B"), retried.map { it.event.recordId })
        // B (reintentado) y el updated de A quedaron en sending: vuelven a la cola.
        assertEquals(2, store.releaseStale(db, now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))))

        store.enqueueFailed(db, "monday", created("C"), now, "No se pudo poner en cola (X)")
        assertTrue(store.events(db, "monday", listOf(OutboundStatus.Failed), 50).any { it.recordId == "C" })
        assertTrue(store.discard(db, "monday", store.events(db, "monday", listOf(OutboundStatus.Failed), 50).first { it.recordId == "C" }.id, now))

        // El alta enviada de A y el aviso descartado de C.
        assertEquals(2, store.purge(db, now.plus(Duration.ofDays(1)), now.minus(Duration.ofDays(90))))
        assertTrue(store.forget(db, "monday") > 0)
        assertTrue(store.events(db, "monday", emptyList(), 50).isEmpty())
    }

    @Test
    fun linksConnectionsAndConfigAgainstPostgres() = withDb { db ->
        assertNull(JdbcOutboundLinks.get(db, "monday", "lead", "A"))
        JdbcOutboundLinks.put(db, "monday", "lead", "A", OutboundLink("123", "456"))
        JdbcOutboundLinks.put(db, "monday", "lead", "A", OutboundLink("124", "456"))
        assertEquals(OutboundLink("124", "456"), JdbcOutboundLinks.get(db, "monday", "lead", "A"))

        IntegrationConnections.save(db, "monday", "enc:v1:sellado", "dueno")
        assertEquals("enc:v1:sellado", IntegrationConnections.sealed(db, "monday"))
        IntegrationConnections.save(db, "monday", null, "dueno")
        assertNull(IntegrationConnections.sealed(db, "monday"))

        val config = JdbcMondayOutboundConfigStore("ext_monday__outbound")
        assertNull(config.get(db, "lead"))
        config.save(
            db,
            MondayOutboundConfig(
                "lead", enabled = true, boardId = "987", groupId = "topics",
                columns = mapOf("email" to MondayColumnRef("email_1", "email")), keyColumn = "text_key", phoneCountry = "PA",
            ),
        )
        val saved = config.get(db, "lead")!!
        assertTrue(saved.enabled)
        assertEquals("987", saved.boardId)
        assertEquals(MondayColumnRef("email_1", "email"), saved.columns["email"])
        config.delete(db)
        assertNull(config.get(db, "lead"))
    }
}
