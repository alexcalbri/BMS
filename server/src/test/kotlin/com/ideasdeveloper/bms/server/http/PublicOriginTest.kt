package com.ideasdeveloper.bms.server.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Table tests of the pure functions behind [PublicOrigin]. The loopback branch cannot be reached through
 * Ktor's test engine (it reports a non-loopback peer), so it is exercised here.
 */
class PublicOriginTest {
    private val loopbacks = listOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
    private val outsiders = listOf("203.0.113.5", "10.1.2.3", "2001:db8::9", "::ffff:127.0.0.1", "localhost")
    private val known = { host: String -> host == "bms.example.com" || host == "localhost" }

    private fun links(
        remote: String = "127.0.0.1",
        proto: String? = null,
        host: String? = "bms.example.com",
        local: String = "http",
        configured: String? = null,
        knownHost: (String) -> Boolean = known,
    ) = PublicOrigin.resolve(configured, remote, proto, local, host, "server.local", knownHost)

    private fun request(
        remote: String = "127.0.0.1",
        proto: String? = null,
        host: String? = "bms.example.com",
        local: String = "http",
        configured: String? = null,
    ) = PublicOrigin.resolve(configured, remote, proto, local, host, "server.local", null)

    @Test
    fun theConfiguredBaseOnlyAcceptsHttpAndHttpsAndLosesTheTrailingSlash() {
        assertEquals("https://bms.example.com", PublicOrigin.configuredFrom(" https://bms.example.com/ "))
        assertEquals("http://10.0.0.1:8080", PublicOrigin.configuredFrom("http://10.0.0.1:8080"))
        assertEquals("https://x.example/a", PublicOrigin.configuredFrom("https://x.example/a//"))
        assertNull(PublicOrigin.configuredFrom(null))
        assertNull(PublicOrigin.configuredFrom(""))
        assertNull(PublicOrigin.configuredFrom("bms.example.com"))
        assertNull(PublicOrigin.configuredFrom("ftp://bms.example.com"))
    }

    @Test
    fun schemeTrustsForwardedProtoOnlyFromTheLocalProxyAndOnlyAsHttpOrHttps() {
        loopbacks.forEach { proxy ->
            assertEquals("https", PublicOrigin.schemeOf(proxy, "https", "http"), proxy)
            assertEquals("http", PublicOrigin.schemeOf(proxy, "http", "https"), proxy)
            assertEquals("https", PublicOrigin.schemeOf(proxy, " https , http", "http"), "first of a list, trimmed: $proxy")
            assertEquals("http", PublicOrigin.schemeOf(proxy, "ftp", "http"), "unknown value: $proxy")
            assertEquals("http", PublicOrigin.schemeOf(proxy, "HTTPS", "http"), "case matters: $proxy")
            assertEquals("http", PublicOrigin.schemeOf(proxy, "", "http"), "empty: $proxy")
            assertEquals("https", PublicOrigin.schemeOf(proxy, null, "https"), "no header: $proxy")
        }
        outsiders.forEach { peer ->
            assertEquals("http", PublicOrigin.schemeOf(peer, "https", "http"), "not trusted: $peer")
            assertEquals("https", PublicOrigin.schemeOf(peer, "http", "https"), "not trusted: $peer")
        }
    }

    @Test
    fun hostMustBeLowercaseableHostnameWithOptionalPort() {
        assertEquals("bms.example.com", PublicOrigin.hostOf("bms.example.com"))
        assertEquals("bms.example.com", PublicOrigin.hostOf("  BMS.Example.COM "))
        assertEquals("bms.example.com:8443", PublicOrigin.hostOf("bms.example.com:8443"))
        assertEquals("10.0.0.1:80", PublicOrigin.hostOf("10.0.0.1:80"))
        listOf(null, "", "   ", "bms.example.com@evil.example", "evil.example/x", "a b", "bms.example.com:", "bms.example.com:123456",
            "bms.example.com:80:80", "[::1]:8080", "bms_example.com", "bms.example.com,evil.example", "a".repeat(256))
            .forEach { assertNull(PublicOrigin.hostOf(it), "rejected: $it") }
        assertEquals("a".repeat(255), PublicOrigin.hostOf("a".repeat(255)))
    }

    @Test
    fun linksPolicyAcceptsOnlyAKnownHostFromTheRequest() {
        assertEquals("http://bms.example.com", links())
        assertEquals("http://bms.example.com", links(host = "BMS.example.com"))
        assertEquals("http://bms.example.com:8080", links(host = "bms.example.com:8080"), "the port is not part of the lookup")
        assertNull(links(host = "evil.example"))
        assertNull(links(host = "bms.example.com@evil.example"))
        assertNull(links(host = null))
        assertNull(links(host = ""))
        assertNull(links(host = "bms.example.com:99999999"))
    }

    @Test
    fun linksPolicyAsksTheLookupWithTheHostWithoutPort() {
        val asked = mutableListOf<String>()
        links(host = "Bms.Example.com:8080", knownHost = { asked += it; true })
        assertEquals(listOf("bms.example.com"), asked)
        asked.clear()
        links(host = "not valid!", knownHost = { asked += it; true })
        assertEquals(emptyList(), asked, "an invalid host never reaches the lookup")
    }

    @Test
    fun linksPolicySchemeFollowsTheProxyPolicy() {
        loopbacks.forEach { proxy ->
            assertEquals("https://bms.example.com", links(remote = proxy, proto = "https"), proxy)
            assertEquals("http://bms.example.com", links(remote = proxy, proto = "http", local = "https"), proxy)
            assertEquals("https://bms.example.com", links(remote = proxy, proto = "gopher", local = "https"), proxy)
        }
        outsiders.forEach { peer ->
            assertEquals("http://bms.example.com", links(remote = peer, proto = "https"), peer)
        }
    }

    @Test
    fun linksPolicyConfiguredBaseWinsEvenWithoutAKnownHost() {
        assertEquals("https://base.example", links(configured = "https://base.example"))
        assertEquals("https://base.example", links(configured = "https://base.example", host = "evil.example"))
        assertEquals("https://base.example", links(configured = "https://base.example", host = null, knownHost = { false }))
    }

    @Test
    fun linksPolicyNeverReadsTheForwardedHost() {
        // resolve has no parameter for X-Forwarded-Host at all: the origin comes from Host only.
        assertNull(links(host = "evil.example"))
    }

    // forRequest (the policy for URLs returned to the same client): never null, never reads X-Forwarded-Host.

    @Test
    fun requestPolicyUsesTheValidatedHostAndFallsBackToTheServerHost() {
        assertEquals("http://bms.example.com", request())
        assertEquals("http://bms.example.com", request(host = "BMS.Example.com"), "lowercased")
        assertEquals("http://bms.example.com:8080", request(host = "bms.example.com:8080"))
        assertEquals("http://server.local", request(host = null))
        assertEquals("http://server.local", request(host = ""))
        assertEquals("http://server.local", request(host = "evil.example/x"))
        assertEquals("http://server.local", request(host = "bms.example.com@evil.example"))
        assertEquals("http://server.local", request(host = "a b"))
        assertEquals("http://server.local", request(host = "a".repeat(256)))
    }

    @Test
    fun requestPolicySchemeFollowsTheProxyPolicy() {
        loopbacks.forEach { proxy ->
            assertEquals("https://bms.example.com", request(remote = proxy, proto = "https"), proxy)
            assertEquals("http://bms.example.com", request(remote = proxy, proto = "http", local = "https"), proxy)
            assertEquals("http://bms.example.com", request(remote = proxy, proto = "ftp"), proxy)
            assertEquals("https://server.local", request(remote = proxy, proto = "https, http", host = null), proxy)
        }
        outsiders.forEach { peer ->
            assertEquals("http://bms.example.com", request(remote = peer, proto = "https"), peer)
            assertEquals("https://bms.example.com", request(remote = peer, proto = "http", local = "https"), peer)
        }
    }

    @Test
    fun requestPolicyConfiguredBaseWins() {
        assertEquals("https://base.example", request(configured = "https://base.example"))
        assertEquals("https://base.example", request(configured = "https://base.example", host = null, remote = "203.0.113.5"))
    }

    @Test
    fun theCallWrappersApplyTheSamePolicies() = testApplication {
        // BMS_PUBLIC_BASE_URL is not set in the test environment, so the request headers decide.
        application {
            routing {
                get("/links") { call.respondText(PublicOrigin.forLinks(call) { it == "bms.example.com" } ?: "none") }
                get("/request") { call.respondText(PublicOrigin.forRequest(call)) }
                get("/scheme") { call.respondText(PublicOrigin.scheme(call)) }
                get("/host") { call.respondText(PublicOrigin.requestHost(call) ?: "none") }
            }
        }
        assertEquals("http://bms.example.com", client.get("/links") { header("Host", "bms.example.com") }.bodyAsText())
        assertEquals("none", client.get("/links") { header("Host", "evil.example") }.bodyAsText())
        assertEquals("http://bms.example.com", client.get("/request") { header("Host", "Bms.Example.com") }.bodyAsText())
        // The engine's peer is not a local proxy: X-Forwarded-* is ignored.
        assertEquals("http://bms.example.com", client.get("/request") {
            header("Host", "bms.example.com"); header("X-Forwarded-Proto", "https"); header("X-Forwarded-Host", "evil.example")
        }.bodyAsText())
        assertEquals("http", client.get("/scheme") { header("X-Forwarded-Proto", "https") }.bodyAsText())
        assertEquals("bms.example.com", client.get("/host") { header("Host", "bms.example.com"); header("X-Forwarded-Host", "evil.example") }.bodyAsText())
        assertEquals("none", client.get("/host") { header("Host", "bms.example.com@evil.example") }.bodyAsText())
        // Without a usable Host the origin falls back to the address the server itself answers on.
        val fallback = client.get("/request") { header("Host", "a b@evil.example/x") }.bodyAsText()
        assertEquals(false, fallback.contains("evil") || fallback.contains(" "), fallback)
        assertEquals(true, fallback.startsWith("http://localhost"), fallback)
    }
}
