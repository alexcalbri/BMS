package com.ideasdeveloper.bms.server.http

import com.ideasdeveloper.bms.server.app.clientAddress
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Characterises the client address: X-Real-IP is believed only when the socket peer is the local proxy,
 * and then only if it is a short string of IP characters.
 */
class ClientAddressesTest {
    private val loopbacks = listOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

    @Test
    fun loopbackPeersAreTheThreeLocalProxyForms() {
        assertEquals(setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1"), ClientAddresses.LoopbackProxies)
    }

    @Test
    fun aProxyOnLoopbackPassesTheRealClientThrough() {
        loopbacks.forEach { proxy ->
            assertEquals("203.0.113.9", ClientAddresses.resolve(proxy, "203.0.113.9"), proxy)
            assertEquals("203.0.113.9", ClientAddresses.resolve(proxy, "  203.0.113.9 "), "trimmed: $proxy")
            assertEquals("2001:db8::7", ClientAddresses.resolve(proxy, "2001:db8::7"), "ipv6: $proxy")
        }
    }

    @Test
    fun aMissingOrUnusableHeaderFallsBackToThePeer() {
        loopbacks.forEach { proxy ->
            assertEquals(proxy, ClientAddresses.resolve(proxy, null))
            assertEquals(proxy, ClientAddresses.resolve(proxy, ""))
            assertEquals(proxy, ClientAddresses.resolve(proxy, "   "))
            assertEquals(proxy, ClientAddresses.resolve(proxy, "1.2.3.4, 5.6.7.8"), "list is not an address")
            assertEquals(proxy, ClientAddresses.resolve(proxy, "1.2.3.4;x"), "foreign characters")
            assertEquals(proxy, ClientAddresses.resolve(proxy, "[2001:db8::1]"), "brackets")
            assertEquals(proxy, ClientAddresses.resolve(proxy, "1.2.3.4%eth0"), "zone id")
        }
    }

    @Test
    fun theHeaderIsCappedAtSixtyFourCharacters() {
        val long = "a".repeat(80)
        // take(64) keeps the first 64 characters (all valid) rather than rejecting the value.
        assertEquals("a".repeat(64), ClientAddresses.resolve("127.0.0.1", long))
        assertEquals("1".repeat(64), ClientAddresses.resolve("127.0.0.1", "1".repeat(64) + ";"))
        assertEquals("127.0.0.1", ClientAddresses.resolve("127.0.0.1", "1".repeat(63) + ";" + "1"))
    }

    @Test
    fun otherPeersAreNeverTrustedWithTheHeader() {
        listOf("203.0.113.5", "10.0.0.2", "2001:db8::1", "::ffff:127.0.0.1", "localhost", "").forEach { peer ->
            assertEquals(peer, ClientAddresses.resolve(peer, "198.51.100.1"), peer)
        }
    }

    @Test
    fun theTestEngineIsNotALoopbackProxyHere() = testApplication {
        // Ktor's engine reports a non-loopback peer, so the header must be ignored end to end.
        application {
            routing {
                get("/ip") { call.respondText(clientAddress(call)) }
                get("/ip2") { call.respondText(ClientAddresses.of(call)) }
            }
        }
        val direct = client.get("/ip").bodyAsText()
        assertEquals(false, direct.isBlank())
        assertEquals(direct, client.get("/ip") { header("X-Real-IP", "198.51.100.1") }.bodyAsText())
        assertEquals(direct, client.get("/ip2") { header("X-Real-IP", "198.51.100.1") }.bodyAsText())
    }

    @Test
    fun ipv6ClientsAreGroupedByTheirSlash64() {
        assertEquals("2001:db8:1:2::/64", ClientAddresses.group("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
        assertEquals("2001:db8:1:2::/64", ClientAddresses.group("[2001:0db8:0001:0002::1]"))
        assertEquals("2001:db8:1:2::/64", ClientAddresses.group("2001:db8:1:2::1%eth0"))
        assertEquals("10.0.0.1", ClientAddresses.group("::ffff:10.0.0.1"))
        assertEquals("10.0.0.1", ClientAddresses.group("::FFFF:10.0.0.1"))
        assertEquals("1::2:3.4.5.6", ClientAddresses.group("1::2:3.4.5.6"), "dotted but not mapped: as given")
        assertEquals("10.0.0.1", ClientAddresses.group(" 10.0.0.1 "))
        assertEquals("gggg::1", ClientAddresses.group("gggg::1"), "non-hex: as given")
        assertEquals("1:2:3", ClientAddresses.group("1:2:3"), "too short: as given")
        assertEquals("1:2:3:4:5:6:7:8:9", ClientAddresses.group("1:2:3:4:5:6:7:8:9"), "too long: as given")
        assertEquals("1::2::3", ClientAddresses.group("1::2::3"), "two gaps: as given")
        assertEquals("desconocida", ClientAddresses.group("desconocida"))
    }
}
