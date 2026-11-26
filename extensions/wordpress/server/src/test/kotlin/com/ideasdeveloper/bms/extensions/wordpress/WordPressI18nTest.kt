package com.ideasdeveloper.bms.extensions.wordpress

import com.ideasdeveloper.bms.modules.crm.CrmWordPressException
import com.ideasdeveloper.bms.modules.crm.CrmWordPressSettings
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import java.net.InetAddress
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WordPressI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(CrmWordPressExtension)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("wordpress", CrmWordPressExtension.manifest)

    @Test
    fun siteErrorsAreTranslatedAndSpanishIsUnchanged() {
        val noDot = "https://localhost"
        assertEquals(
            "La direccion del sitio debe ser un dominio publico (con punto).",
            assertFailsWith<CrmWordPressException> { CrmWordPress.checkSite(noDot) }.message,
        )
        I18n.withLanguage("en") {
            assertEquals(
                "The site address must be a public domain (with a dot).",
                assertFailsWith<CrmWordPressException> { CrmWordPress.checkSite(noDot) }.message,
            )
            // A name that resolves inside the network is refused, whatever the language.
            assertEquals(
                "The site address points to a private or local network: not allowed.",
                assertFailsWith<CrmWordPressException> {
                    CrmWordPress.checkSite("https://mi-sitio.com") { listOf(InetAddress.getByName("10.0.0.5")) }
                }.message,
            )
        }
    }

    @Test
    fun missingSiteAddressIsTranslatedBeforeAnyCall() {
        val settings = CrmWordPressSettings(enabled = true, siteUrl = null, secret = null, siteName = "", lastEventAt = null, lastError = "")
        I18n.withLanguage("en") {
            assertEquals(
                "Enter the site address in Integrations.",
                assertFailsWith<CrmWordPressException> { CrmWordPress.rpc(settings, "ping") }.message,
            )
        }
    }

    @Test
    fun readingLabelFollowsTheLanguageAndTheBrandStays() {
        assertEquals("Leyendo CT Leads Pro", WordPressImport.readingLabel)
        assertEquals("CT Leads Pro", WordPressImport.label)
        I18n.withLanguage("en") {
            assertEquals("Reading CT Leads Pro", WordPressImport.readingLabel)
            assertEquals("CT Leads Pro", WordPressImport.label)
        }
    }
}
