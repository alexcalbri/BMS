package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.auth.infrastructure.database.SessionOpener
import com.ideasdeveloper.bms.server.security.MasterKeySecretBox
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-user request and stream limits, conversation owners of switcher sessions and the server workspace. */
class AssistantLimitsTest {
    private val noDatabase = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, _, _ -> throw java.sql.SQLException("no database", "08001") } as DataSource
    private val service = AssistantService(
        ActivityLogTesting.accessWithoutDatabase(), AssistantServerConfig(noDatabase, MasterKeySecretBox(null)), MasterKeySecretBox(null), FakeModelClients(),
    )

    @Test
    fun requestsPerMinuteAndStreamsAtOnceArePerUser() {
        var now = 0L
        val limits = AssistantUserLimits(perMinute = 3, streamsPerUser = 2) { now }
        repeat(3) { assertTrue(limits.allow("acme/ana")) }
        assertFalse(limits.allow("acme/ana"))
        assertTrue(limits.allow("acme/leo"))
        now += 60_000
        assertTrue(limits.allow("acme/ana"))
        assertNull(limits.openStream("acme/ana", "acme"))
        assertNull(limits.openStream("acme/ana", "acme"))
        assertEquals("ASSISTANT_RATE_LIMITED", limits.openStream("acme/ana", "acme")?.code)
        limits.release("acme/ana", "acme")
        assertNull(limits.openStream("acme/ana", "acme"))
    }

    @Test
    fun streamsHaveACapPerCompanyAndOnTheWholeServer() {
        val limits = AssistantUserLimits(streamsPerUser = 5, streamsPerCompany = 2, streamsOnServer = 3)
        assertNull(limits.openStream("acme/1", "acme"))
        assertNull(limits.openStream("acme/2", "acme"))
        assertEquals(429, limits.openStream("acme/3", "acme")!!.status)
        assertNull(limits.openStream("otra/1", "otra"))
        val full = limits.openStream("otra/2", "otra")!!
        assertEquals(503 to "ASSISTANT_BUSY", full.status to full.code)
        assertEquals(3, limits.openStreams)
        limits.release("acme/1", "acme")
        assertNull(limits.openStream("otra/2", "otra"))
        // New conversations have their own rate.
        val creating = AssistantUserLimits(conversationsPerMinute = 2)
        assertTrue(creating.allowNewConversation("u") && creating.allowNewConversation("u"))
        assertFalse(creating.allowNewConversation("u"))
    }

    @Test
    fun anUnreadableSessionOpenerNeverFallsBackToTheAccountsOwnConversations() {
        val untouched = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> error("not used") } as Connection
        val refusal = kotlin.test.assertFailsWith<AssistantRefusal> { service.context(untouched, "acme", UUID.randomUUID(), "a".repeat(43)) }
        assertEquals(503, refusal.status)
    }

    @Test
    fun switcherSessionsHaveTheirOwnConversations() {
        val person = UUID.randomUUID()
        assertEquals("self", service.ownerKey(null))
        assertEquals("server:$person", service.ownerKey(SessionOpener(SessionOpener.Server, person, null)))
        assertEquals("company:otra:$person", service.ownerKey(SessionOpener(SessionOpener.Company, person, "otra")))
    }

    @Test
    fun theServerWorkspaceIsLeftOut() {
        val untouched = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> error("not used") } as Connection
        assertNull(service.context(untouched, "servidor", UUID.randomUUID(), ""))
        assertNull(service.context(untouched, "SERVIDOR", UUID.randomUUID(), ""))
    }
}
