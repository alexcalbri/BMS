package com.ideasdeveloper.bms.server.auth

import com.ideasdeveloper.bms.server.http.ClientAddresses
import com.ideasdeveloper.bms.server.auth.application.ScopedLoginService
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.twofactor.RecoveryCodes
import com.ideasdeveloper.bms.server.auth.twofactor.Totp
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorChallenges
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwoFactorUnitTest {
    private val rfcSecret = "12345678901234567890".toByteArray()

    @Test
    fun totpMatchesTheRfc6238Vectors() {
        assertEquals("287082", Totp.code(rfcSecret, Totp.step(59)))
        assertEquals("081804", Totp.code(rfcSecret, Totp.step(1111111109)))
        assertEquals("005924", Totp.code(rfcSecret, Totp.step(1234567890)))
        assertEquals("279037", Totp.code(rfcSecret, Totp.step(2000000000)))
    }

    @Test
    fun aCodeWorksInTheWindowAndNeverTwice() {
        val now = Totp.step(1234567890)
        val code = Totp.code(rfcSecret, now)
        assertEquals(now, Totp.matchingStep(rfcSecret, code, now, lastUsedStep = 0))
        assertEquals(now, Totp.matchingStep(rfcSecret, code, now + 1, lastUsedStep = 0))
        assertNull(Totp.matchingStep(rfcSecret, code, now + 2, lastUsedStep = 0))
        // Already used (or an older one): refused.
        assertNull(Totp.matchingStep(rfcSecret, code, now, lastUsedStep = now))
        assertNull(Totp.matchingStep(rfcSecret, "12345", now, 0))
        assertNull(Totp.matchingStep(rfcSecret, "abcdef", now, 0))
    }

    @Test
    fun base32RoundTripsAndTheUriIsForAuthenticatorApps() {
        val secret = Totp.newSecret()
        assertEquals(20, secret.size)
        assertTrue(Totp.decodeBase32(Totp.base32(secret)).contentEquals(secret))
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(rfcSecret))
        val uri = Totp.otpauthUri("BMS (acme)", "ana", "ABC")
        assertEquals("otpauth://totp/BMS%20%28acme%29:ana?secret=ABC&issuer=BMS%20%28acme%29&algorithm=SHA1&digits=6&period=30", uri)
    }

    @Test
    fun recoveryCodesAreTenDistinctAndHashedWithTheUser() {
        val codes = RecoveryCodes.generate()
        assertEquals(10, codes.toSet().size)
        assertTrue(codes.all { Regex("[A-Z2-7]{5}-[A-Z2-7]{5}").matches(it) })
        val normalized = RecoveryCodes.normalize(codes[0].lowercase().replace("-", " "))
        assertEquals(codes[0].replace("-", ""), normalized)
        assertNull(RecoveryCodes.normalize("123456"))
        val user = UUID.randomUUID()
        assertNotEquals(RecoveryCodes.hash(user, normalized!!), RecoveryCodes.hash(UUID.randomUUID(), normalized))
        assertEquals(64, RecoveryCodes.hash(user, normalized).length)
    }

    @Test
    fun challengesAreBoundToTheAddressLimitedAndSingleUse() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val challenges = TwoFactorChallenges { now }
        val issued = challenges.issue(UUID.randomUUID(), "ana", "acme", "10.0.0.1")
        assertNull(challenges.attempt(issued.token, "10.0.0.2"))
        repeat(TwoFactorChallenges.MaxAttempts) { assertNotNull(challenges.attempt(issued.token, "10.0.0.1")) }
        assertNull(challenges.attempt(issued.token, "10.0.0.1"))

        val other = challenges.issue(UUID.randomUUID(), "ana", null, "10.0.0.1")
        challenges.finish(other.token)
        assertNull(challenges.attempt(other.token, "10.0.0.1"))

        val late = challenges.issue(UUID.randomUUID(), "ana", null, "10.0.0.1")
        now = now.plus(TwoFactorChallenges.Lifetime).plusSeconds(1)
        assertNull(challenges.attempt(late.token, "10.0.0.1"))
        assertNull(challenges.attempt("not-a-token", "10.0.0.1"))
    }

    @Test
    fun ipv6ClientsAreGroupedByTheirSlash64() {
        assertEquals("2001:db8:1:2::/64", ClientAddresses.group("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
        assertEquals("2001:db8:1:2::/64", ClientAddresses.group("2001:0db8:0001:0002::1"))
        assertEquals(ClientAddresses.group("2001:db8::1"), ClientAddresses.group("2001:db8:0:0:ffff::2"))
        assertNotEquals(ClientAddresses.group("2001:db8:0:1::1"), ClientAddresses.group("2001:db8:0:2::1"))
        assertEquals("10.0.0.1", ClientAddresses.group("10.0.0.1"))
        assertEquals("10.0.0.1", ClientAddresses.group("::ffff:10.0.0.1"))
        assertEquals("0:0:0:0::/64", ClientAddresses.group("::1"))
        assertEquals("desconocida", ClientAddresses.group("desconocida"))
    }

    @Test
    fun anUnknownCompanyStillDoesTheWorkOfASignIn() {
        var spent = 0
        val service = ScopedLoginService(
            serverScope = { null },
            findActiveDatabase = { code -> if (code == "acme") "bms_acme" else null },
            companyScope = { null },
            unknownCompany = { spent++ },
        )
        assertNull(service.login(LoginCredentials("ana", "x", "otra")))
        assertEquals(1, spent)
        assertNull(service.login(LoginCredentials("ana", "x", "acme")))
        assertEquals(2, spent)
        assertFalse(spent == 0)
    }
}
