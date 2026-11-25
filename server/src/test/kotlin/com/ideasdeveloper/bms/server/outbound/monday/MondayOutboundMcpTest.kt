package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiRequest
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MondayOutboundMcpTest {
    private val view = object : MondayQueueView {
        override fun summary(tenant: Connection) = MondayQueueSummary()
        override fun events(tenant: Connection, statuses: Set<String>, limit: Int) = emptyList<MondayQueueEvent>()
        override fun retry(tenant: Connection, id: Long) = false
        override fun retryFailed(tenant: Connection) = 0
        override fun discard(tenant: Connection, id: Long) = false
    }

    @Test
    fun `las herramientas MCP tienen los nombres, el permiso y los marcadores esperados`() {
        val api = FakeMondayApi()
        val tools = MondayOutboundMcp.tools("monday_", "crm.leads.export", TestSink(MemoryConfigStore(), api), TestSource(), view, { TestToken }, { null })
        assertEquals(
            listOf("monday_outbound_status", "monday_outbound_boards", "monday_outbound_configure", "monday_outbound_retry", "monday_outbound_discard"),
            tools.map { it.name },
        )
        assertTrue(tools.all { it.permission == "crm.leads.export" })
        assertEquals(listOf(true, true, false, false, false), tools.map { it.readOnly })
        assertTrue(tools.none { it.inputSchema.toString().contains("token") })
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `statusNodes muestra pendientes con error y enviados con avisos`() {
        val pending = listOf(
            MondayQueueEvent(1, "created", "lead", "L1", "pending", attempts = 2, detail = "monday respondio HTTP 502."),
            MondayQueueEvent(2, "note", "lead", "L1", "pending"),
        )
        val sent = listOf(MondayQueueEvent(3, "updated", "lead", "L1", "sent", detail = "Elemento 5 actualizado", hasWarnings = true))
        val nodes = MondayOutboundUi.statusNodes(MondayQueueSummary(pending = 2, sent = 1), emptyList(), pending, sent) { op, params ->
            UiRequest("/x", params = params + ("op" to op))
        }
        val titles = nodes.filterIsInstance<UiCard>().map { it.title }
        assertTrue("Pendientes con error" in titles)
        assertTrue("Enviados con avisos" in titles)
    }
}
