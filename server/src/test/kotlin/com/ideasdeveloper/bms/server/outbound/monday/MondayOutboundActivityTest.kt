package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.McpToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Proxy
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The MCP tools of the sending to monday leave their rows in the company activity log, with the agent as the actor. */
class MondayOutboundActivityTest {
    private val person = ActivityLogTesting.user("Ana Ruiz")
    private val permissions = ActivityLogTesting.permissions(actor = person).throughAgent("Claude")

    // A connection inside a transaction (nothing is written: rows are captured); any other use fails.
    private val inTransaction: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        if (method.name == "getAutoCommit") false else throw UnsupportedOperationException("no database: ${method.name}")
    } as Connection

    private class Queue(var failed: Set<Long> = setOf(7L, 8L)) : MondayQueueView {
        override fun summary(tenant: Connection) = MondayQueueSummary()
        override fun events(tenant: Connection, statuses: Set<String>, limit: Int) = emptyList<MondayQueueEvent>()
        override fun retry(tenant: Connection, id: Long) = id in failed
        override fun retryFailed(tenant: Connection) = failed.size
        override fun discard(tenant: Connection, id: Long) = id in failed
    }

    private val store = MemoryConfigStore().also { it.rows["lead"] = TestConfig }
    private val queue = Queue()
    private val tools = MondayOutboundMcp.tools("monday_", "crm.settings", TestSink(store, FakeMondayApi()), TestSource(), queue, { TestToken }, { null })
        .associateBy { it.name }

    private fun call(vararg arguments: Pair<String, Any>) = McpToolCall(
        JsonObject(arguments.toMap().mapValues { (_, value) -> if (value is Boolean) JsonPrimitive(value) else JsonPrimitive(value.toString()) }),
        permissions, inTransaction, ActivityLogTesting.accessWithoutDatabase(), "Claude", "crm",
    )

    @Test
    fun configureLogsOldAndNewWithTheAgentAndNothingWhenNothingChanges() {
        val (_, rows) = ActivityLogTesting.capture { tools.getValue("monday_outbound_configure").handler(call("record_type" to "lead", "send_notes" to false, "enabled" to false)) }
        val row = rows.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertEquals("Agente de Ana Ruiz", row.display)
        assertEquals("crm", row.entry.moduleId)
        assertEquals("Si", row.change("sendNotes")!!.old)
        assertEquals("No", row.change("sendNotes")!!.new)
        assertEquals("No", row.change("enabled")!!.new)
        assertEquals(false, store.rows.getValue("lead").sendNotes)
        // The token is never part of it, and the same values again leave no row.
        assertFalse(rows.toString().contains(TestToken))
        val (_, again) = ActivityLogTesting.capture { tools.getValue("monday_outbound_configure").handler(call("record_type" to "lead", "send_notes" to false, "enabled" to false)) }
        assertTrue(again.isEmpty())
    }

    @Test
    fun retryAndDiscardLeaveOneRowEachOnlyWhenTheyDidSomething() {
        val (_, one) = ActivityLogTesting.capture { tools.getValue("monday_outbound_retry").handler(call("id" to "7")) }
        assertEquals("Reintento el envio a monday 7.", one.single().entry.summary)
        assertEquals("Agente de Ana Ruiz", one.single().display)
        val (_, all) = ActivityLogTesting.capture { tools.getValue("monday_outbound_retry").handler(call()) }
        assertEquals("2", all.single().change("count")!!.new)
        val (_, discarded) = ActivityLogTesting.capture { tools.getValue("monday_outbound_discard").handler(call("id" to "8")) }
        assertEquals("Descartado", discarded.single().change("status")!!.new)
        assertEquals("8", discarded.single().entry.entityId)
        // Nothing to retry or discard: the tool refuses and nothing is logged.
        val (failure, none) = ActivityLogTesting.capture { runCatching { tools.getValue("monday_outbound_discard").handler(call("id" to "99")) }.exceptionOrNull() }
        assertTrue(failure is McpToolException)
        assertTrue(none.isEmpty())
        assertFailsWith<McpToolException> { tools.getValue("monday_outbound_retry").handler(call("id" to "99")) }
    }
}
