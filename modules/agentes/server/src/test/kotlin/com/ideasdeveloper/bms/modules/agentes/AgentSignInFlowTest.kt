package com.ideasdeveloper.bms.modules.agentes

import com.ideasdeveloper.bms.server.modules.AgentSignIn
import com.ideasdeveloper.bms.server.modules.VerifiedUser
import io.ktor.http.Parameters
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The OAuth page's sign-in with two-step verification (TWO_FACTOR.md §2): an account with it on reaches the
 * consent only after Core accepted password AND code; the second page never carries the password; the pending
 * step is bound to the client, redirect URI and address, expires, and allows a few wrong codes only.
 */
class AgentSignInFlowTest {
    private val user = VerifiedUser(UUID.randomUUID(), "luis", "acme", serverOwner = false)
    private val origin = AgentSignInFlow.Origin("client-1", "https://claude.ai/api/mcp/auth_callback", "203.0.113.7")

    // Core stand-in: "luis" / "clave" with two-step verification, code 123456.
    private class FakeCore(val twoFactor: Boolean = true) : AgentSignInFlow.SignIn {
        val calls = mutableListOf<Pair<String, String?>>()
        var tooMany = false
        override fun attempt(company: String?, username: String, password: String, code: String?): AgentSignIn {
            calls += password to code
            if (username != "luis" || password != "clave" || company != "acme") return AgentSignIn.Rejected
            if (!twoFactor) return AgentSignIn.Verified(VerifiedUser(UUID(0, 1), "luis", "acme", serverOwner = false))
            if (tooMany) return AgentSignIn.TooManyAttempts
            return when (code) {
                null -> AgentSignIn.SecondFactorRequired
                "123456" -> AgentSignIn.Verified(VerifiedUser(UUID(0, 1), "luis", "acme", serverOwner = false))
                else -> AgentSignIn.InvalidCode
            }
        }
    }

    @Test
    fun anAccountWithoutTwoStepVerificationSignsInWithThePassword() {
        val step = AgentSignInFlow().password("acme", "luis", "clave", origin, FakeCore(twoFactor = false))
        assertIs<AgentSignInFlow.Step.Accepted>(step)
    }

    @Test
    fun theSecondStepIsRequiredAndSendsThePasswordAgainToCore() {
        val flow = AgentSignInFlow()
        val core = FakeCore()
        val ask = assertIs<AgentSignInFlow.Step.AskCode>(flow.password("acme", "luis", "clave", origin, core))
        // Without a code nothing is accepted, and a wrong one is not either.
        assertIs<AgentSignInFlow.Step.AskCode>(flow.code(ask.pending, "  ", origin, core))
        val wrong = assertIs<AgentSignInFlow.Step.AskCode>(flow.code(ask.pending, "000000", origin, core))
        assertTrue(wrong.error!!.isNotBlank())
        assertIs<AgentSignInFlow.Step.Accepted>(flow.code(ask.pending, "123456", origin, core))
        // Core saw password and code together on every check of the second step.
        assertEquals(listOf("clave" to null, "clave" to "000000", "clave" to "123456"), core.calls)
        // A finished sign-in cannot be reused.
        assertIs<AgentSignInFlow.Step.Again>(flow.code(ask.pending, "123456", origin, core))
        assertEquals(0, flow.pendingCount())
    }

    @Test
    fun aWrongPasswordNeverReachesTheSecondStep() {
        val flow = AgentSignInFlow()
        assertIs<AgentSignInFlow.Step.Again>(flow.password("acme", "luis", "mala", origin, FakeCore()))
        assertEquals(0, flow.pendingCount())
        // An invented pending id goes back to the password page.
        assertIs<AgentSignInFlow.Step.Again>(flow.code("inventado", "123456", origin, FakeCore()))
    }

    @Test
    fun thePendingStepIsBoundToTheClientAndAddressAndExpires() {
        var now = Instant.parse("2026-11-27T10:00:00Z")
        val flow = AgentSignInFlow(clock = { now })
        val core = FakeCore()
        val ask = assertIs<AgentSignInFlow.Step.AskCode>(flow.password("acme", "luis", "clave", origin, core))
        assertIs<AgentSignInFlow.Step.Again>(flow.code(ask.pending, "123456", origin.copy(address = "198.51.100.1"), core))
        val second = assertIs<AgentSignInFlow.Step.AskCode>(flow.password("acme", "luis", "clave", origin, core))
        assertIs<AgentSignInFlow.Step.Again>(flow.code(second.pending, "123456", origin.copy(clientId = "otro"), core))
        val third = assertIs<AgentSignInFlow.Step.AskCode>(flow.password("acme", "luis", "clave", origin, core))
        now = now.plusSeconds(301)
        assertIs<AgentSignInFlow.Step.Again>(flow.code(third.pending, "123456", origin, core))
    }

    @Test
    fun wrongCodesEndThePendingStepAndCoreLimitsAreShown() {
        val flow = AgentSignInFlow(maxAttempts = 3)
        val core = FakeCore()
        val ask = assertIs<AgentSignInFlow.Step.AskCode>(flow.password("acme", "luis", "clave", origin, core))
        repeat(2) { assertIs<AgentSignInFlow.Step.AskCode>(flow.code(ask.pending, "000000", origin, core)) }
        assertIs<AgentSignInFlow.Step.Again>(flow.code(ask.pending, "000000", origin, core))
        assertIs<AgentSignInFlow.Step.Again>(flow.code(ask.pending, "123456", origin, core))
        // Core's per-account limit (TWO_FACTOR_TOO_MANY_ATTEMPTS) ends it too.
        val again = assertIs<AgentSignInFlow.Step.AskCode>(flow.password("acme", "luis", "clave", origin, core))
        core.tooMany = true
        assertIs<AgentSignInFlow.Step.Again>(flow.code(again.pending, "123456", origin, core))
        assertEquals(0, flow.pendingCount())
    }

    @Test
    fun theCodePageCarriesTheOAuthRequestButNeverThePassword() {
        val client = OAuthClients.register(ByteArray(32), kotlinx.serialization.json.Json.parseToJsonElement(
            """{"client_name":"Claude","redirect_uris":["https://claude.ai/api/mcp/auth_callback"],"token_endpoint_auth_method":"none"}""",
        ) as kotlinx.serialization.json.JsonObject).first
        val request = OAuthRequest(client, "https://claude.ai/api/mcp/auth_callback", "st", "c".repeat(43))
        val form = Parameters.build {
            append("client_id", client.id); append("redirect_uri", request.redirectUri); append("state", "st\"><script>")
            append("company", "acme"); append("username", "luis"); append("password", "clave-secreta")
        }
        val html = OAuthPages.twoFactor(request, form, "pend<id>", error = null)
        assertFalse(html.contains("clave-secreta"))
        assertFalse(html.contains("name=\"password\""))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("name=\"step\" value=\"code\""))
        assertTrue(html.contains("name=\"pending\" value=\"pend&lt;id&gt;\""))
        assertTrue(html.contains("name=\"code\""))
        assertTrue(html.contains("name=\"client_id\""))
    }

    // Review item 8: the passwords held for the second step are bounded.
    @Test
    fun theHeldSignInsAreCappedAndOnePerUserAndAddress() {
        var now = Instant.parse("2026-11-27T10:00:00Z")
        val flow = AgentSignInFlow(clock = { now }, maxPending = 2)
        val core = FakeCore()
        assertIs<AgentSignInFlow.Step.AskCode>(flow.password("acme", "luis", "clave", origin, core))
        // The same user from the same address replaces the earlier one.
        val second = assertIs<AgentSignInFlow.Step.AskCode>(flow.password("acme", "luis", "clave", origin, core))
        assertEquals(1, flow.pendingCount())
        assertIs<AgentSignInFlow.Step.AskCode>(flow.password("acme", "luis", "clave", origin.copy(address = "198.51.100.2"), core))
        // Full: nothing more is held.
        assertIs<AgentSignInFlow.Step.Again>(flow.password("acme", "luis", "clave", origin.copy(address = "198.51.100.3"), core))
        assertEquals(2, flow.pendingCount())
        // Expired entries go on the next sign-in.
        now = now.plusSeconds(301)
        assertIs<AgentSignInFlow.Step.AskCode>(flow.password("acme", "luis", "clave", origin.copy(address = "198.51.100.3"), core))
        assertEquals(1, flow.pendingCount())
        assertIs<AgentSignInFlow.Step.Again>(flow.code(second.pending, "123456", origin, core))
    }
}
