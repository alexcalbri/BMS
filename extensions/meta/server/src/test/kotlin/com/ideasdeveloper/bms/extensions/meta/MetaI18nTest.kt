package com.ideasdeveloper.bms.extensions.meta

import com.ideasdeveloper.bms.modules.crm.CrmMetaServerApp
import com.ideasdeveloper.bms.modules.crm.CrmMetaSettings
import com.ideasdeveloper.bms.modules.crm.CrmValidationException
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MetaI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(CrmMetaExtension)

    private val nothingConnected = CrmMetaSettings(false, null, null, null, null, null, null, null, null, null, true)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("meta", CrmMetaExtension.manifest)

    @Test
    fun validationMessagesFollowTheLanguageAndSpanishIsUnchanged() {
        assertEquals(
            "El ID de la pagina solo lleva numeros.",
            assertFailsWith<CrmValidationException> { CrmMeta.merge(nothingConnected, mapOf("pageId" to "abc")) }.message,
        )
        I18n.withLanguage("en") {
            assertEquals(
                "The page ID only takes numbers.",
                assertFailsWith<CrmValidationException> { CrmMeta.merge(nothingConnected, mapOf("pageId" to "abc")) }.message,
            )
        }
    }

    @Test
    fun storedWhatsAppTextIsNeverTranslated() {
        // The text of a message is stored in the inbox: the same Spanish label in every language.
        val image = JsonObject(mapOf("type" to JsonPrimitive("image"), "image" to JsonObject(mapOf("caption" to JsonPrimitive("Mira")))))
        I18n.withLanguage("en") { assertEquals("[Imagen de WhatsApp] Mira", CrmMeta.whatsAppText(image)) }
    }

    @Test
    fun graphHintsAndTheConnectionPageAreTranslated() {
        val expired = JsonObject(mapOf("error" to JsonObject(mapOf("code" to JsonPrimitive(190)))))
        assertEquals("el token es invalido o vencio.", CrmMeta.graphHint(expired, 400))
        I18n.withLanguage("en") {
            assertEquals("the token is invalid or expired.", CrmMeta.graphHint(expired, 400))
            val page = CrmMetaServer.connectPage(CrmMetaServerApp(), "empresa", "ticket")
            assertTrue(page.contains("<title>Connect with Meta</title>"), "title in English")
            assertTrue(page.contains("""<html lang="en">"""), "page language")
            assertTrue(page.contains("""T = {"""), "script messages table")
        }
        assertTrue(CrmMetaServer.connectPage(CrmMetaServerApp(), "empresa", "ticket").contains("<title>Conectar con Meta</title>"))
    }

    @Test
    fun manifestTextsAreTranslated() {
        assertEquals("Meta (WhatsApp, Messenger e Instagram)", CrmMetaExtension.manifest.displayName)
        I18n.withLanguage("en") {
            assertEquals("Meta (WhatsApp, Messenger and Instagram)", I18n.translate(CrmMetaExtension.manifest.displayName))
        }
    }
}
