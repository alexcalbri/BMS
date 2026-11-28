package com.ideasdeveloper.bms.server.company

import com.ideasdeveloper.bms.server.company.application.CompanyLogoUrl
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The rule of the logo URL of a company: https, at most 300 characters, no credentials; checked only when it changes. */
class CompanyLogoUrlTest {
    @Test
    fun anOrdinaryHttpsLogoIsFine() {
        assertNull(CompanyLogoUrl.problem("https://cdn.example.com/acme/logo.png"))
        assertNull(CompanyLogoUrl.problem("HTTPS://cdn.example.com:8443/logo.png?v=2"))
    }

    @Test
    fun everyOtherShapeIsRefusedWithAMessage() {
        listOf(
            "http://cdn.example.com/logo.png", "ftp://cdn.example.com/logo.png", "//cdn.example.com/logo.png", "/logo.png", "logo.png",
            "javascript:alert(1)", "data:image/png;base64,AAAA", "file:///etc/passwd",
            "https://user:secret@cdn.example.com/logo.png", "https://user@cdn.example.com/logo.png", "https://:secret@cdn.example.com/logo.png",
            "https://", "https:///logo.png", "https://cdn example.com/logo.png", "https://cdn.example.com/lo\ngo.png", "https://cdn.example.com/lo\u0000go.png",
            "https://" + "a".repeat(300) + ".example.com/logo.png",
        ).forEach { url -> assertNotNull(CompanyLogoUrl.problem(url), url) }
        assertEquals("La URL del logo debe empezar por https://.", CompanyLogoUrl.problem("http://cdn.example.com/logo.png"))
        assertEquals("La URL del logo no puede llevar usuario ni contrasena.", CompanyLogoUrl.problem("https://user:secret@cdn.example.com/logo.png"))
        assertEquals("La URL del logo no puede tener mas de 300 caracteres.", CompanyLogoUrl.problem("https://" + "a".repeat(300) + ".example.com/logo.png"))
    }

    @Test
    fun theLimitIsTheThreeHundredthCharacter() {
        val prefix = "https://cdn.example.com/"
        assertNull(CompanyLogoUrl.problem(prefix + "a".repeat(300 - prefix.length)))
        assertNotNull(CompanyLogoUrl.problem(prefix + "a".repeat(301 - prefix.length)))
    }

    @Test
    fun onlyAChangedLogoIsChecked() {
        // A stored value that breaks the rule is kept as it is (and does not stop other edits) ...
        assertEquals("http://old.example.com/logo.png", CompanyLogoUrl.checked("  http://old.example.com/logo.png ", "http://old.example.com/logo.png"))
        // ... but a different one is refused, and so is a new one where there was none.
        assertFailsWith<CompanyProvisioningException> { CompanyLogoUrl.checked("http://new.example.com/logo.png", "http://old.example.com/logo.png") }
        assertFailsWith<CompanyProvisioningException> { CompanyLogoUrl.checked("http://new.example.com/logo.png", null) }
        assertFailsWith<CompanyProvisioningException> { CompanyLogoUrl.checked("https://u:p@new.example.com/logo.png", "https://new.example.com/logo.png") }
        // Clearing it is always allowed; a good one passes.
        assertNull(CompanyLogoUrl.checked(null, "http://old.example.com/logo.png"))
        assertNull(CompanyLogoUrl.checked("   ", "http://old.example.com/logo.png"))
        assertEquals("https://new.example.com/logo.png", CompanyLogoUrl.checked(" https://new.example.com/logo.png ", "http://old.example.com/logo.png"))
    }
}
