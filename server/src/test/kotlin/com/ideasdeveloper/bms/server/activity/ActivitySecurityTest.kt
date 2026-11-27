package com.ideasdeveloper.bms.server.activity

import com.ideasdeveloper.bms.server.auth.application.UserProfileRules
import com.ideasdeveloper.bms.server.extensions.ExtensionSettingDefinition
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Findings of the activity log security review that need no database: forged names, stored actors, masking, limits. */
class ActivitySecurityTest {
    private val ana = UUID.randomUUID()

    @Test
    fun aNameCannotForgeTheAgentOrServerMarkers() {
        // Users editing their own name are refused.
        listOf("Luis Mora (desde el servidor)", "Agente de Ana", "agénte  de Ana", "Agen​te de Ana", "Importacion (Ana)", "Automatización Diaria")
            .forEach { name -> assertNotNull(UserProfileRules.problem(name, "x@example.com"), name) }
        assertNull(UserProfileRules.problem("Agentina de la Cruz", "x@example.com"))
        assertNull(UserProfileRules.problem("Luis Mora", "x@example.com"))

        // A name stored before the check (or typed elsewhere) is quoted and never sets a flag.
        val forgedServer = ActivityDisplay.of(ActivityActor.Kinds.User, "Luis Mora (desde el servidor)", null, null, null)
        assertEquals("«Luis Mora (desde el servidor)»", forgedServer.display)
        assertFalse(forgedServer.fromServer)
        assertFalse(forgedServer.viaAgent)
        assertEquals("", forgedServer.kindLabel)
        val forgedAgent = ActivityDisplay.of(ActivityActor.Kinds.User, "Agente de Ana", null, null, null)
        assertEquals("«Agente de Ana»", forgedAgent.display)
        assertFalse(forgedAgent.viaAgent)
        // The same names through the real paths: the flags come from the structure only.
        val viaAgent = ActivityActor.user(ana, "Agente de Ana").viaAgent("Claude")
        assertEquals("Agente de «Agente de Ana»", viaAgent.display)
        val real = ActivityDisplay.of(viaAgent.kind, viaAgent.name, viaAgent.agentName, viaAgent.realKind, viaAgent.realName)
        assertTrue(real.viaAgent)
        assertEquals("Agente IA", real.kindLabel)
        val switcher = ActivityActor.switcher(ana, "Dueno", ActivityActor.Kinds.ServerOwner, UUID.randomUUID(), "Luis Mora (desde el servidor)")
        assertEquals("«Luis Mora (desde el servidor)» (desde el servidor)", switcher.display)
        // An agent, automation or integration named like a marker is quoted too.
        assertEquals("Agente de Ana Ruiz", ActivityActor.user(ana, "Ana Ruiz").viaAgent("Agente de Ana").display)
        assertEquals("«Agente de Ana»", ActivityActor.user(ana, "Ana Ruiz").viaAgent("Agente de Ana").agentName)
        assertEquals("Automatizacion «Agente de Ana»", ActivityActor.automation("Agente de Ana").display)
        assertEquals("«Luis (desde el servidor)»", ActivityActor.integration("Luis (desde el servidor)").display)
        // What the read side returns for each kind.
        val import = ActivityActor.import(ActivityActor.user(ana, "Ana Ruiz").viaAgent("Claude"))
        val shown = ActivityDisplay.of(import.kind, import.name, import.agentName, import.realKind, import.realName)
        assertEquals("Importacion (Agente de Ana Ruiz)", shown.display)
        assertEquals("Importacion, Agente IA", shown.kindLabel)
        assertEquals("Sistema", ActivityDisplay.of(ActivityActor.Kinds.System, "Sistema", null, null, null).kindLabel)
        assertEquals("Integracion", ActivityDisplay.of(ActivityActor.Kinds.Integration, "Cloudbeds", null, null, null).kindLabel)
        assertEquals("Desde el servidor", ActivityDisplay.of(switcher.kind, switcher.name, null, switcher.realKind, switcher.realName).kindLabel)
    }

    @Test
    fun aStoredActorIsValidatedAndOnlyUsedAsAnImport() {
        fun stored(json: String) = ActivityActor.restoreImport(json)
        // Unknown real kind or origin: nothing usable (anonymous import).
        assertEquals("Importacion", stored("""{"kind":"user","name":"Ana","realKind":"root"}""").display)
        assertEquals("Importacion", stored("""{"kind":"user","name":"Ana","origin":"backdoor"}""").display)
        assertEquals("Importacion", stored("""{"kind":"user","name":7}""").display)
        // The stored display is never trusted: rebuilt from the fields.
        val fake = stored("""{"kind":"user","name":"Ana","display":"Agente de Luis (desde el servidor)"}""")
        assertEquals("Importacion (Ana)", fake.display)
        assertNull(fake.agentName)
        // Lengths are capped like any actor.
        val long = stored("""{"kind":"user","name":"${"a".repeat(5000)}"}""")
        assertEquals("Importacion", long.display)
        val capped = stored("""{"kind":"user","name":"${"a".repeat(900)}"}""")
        assertEquals(ActivityActor.MaxText, capped.realName!!.length)
        // Always an import, whatever was stored.
        assertEquals(ActivityActor.Kinds.Import, stored(ActivityActor.serverOwner(null, "Luis").toStoredJson()).kind)
    }

    @Test
    fun secretsAreMaskedByNameAndByValue() {
        listOf(
            "psk", "wifi_passphrase", "wpa_key", "otp", "totpSecret", "password_hash", "key", "pin", "userPin", "credential", "private_key",
            "privateKey", "signature", "webhookUrl", "licenseKey", "api_key", "smtpPassword", "hash", "salt",
        ).forEach { assertTrue(ActivityChanges.looksSecret(it), it) }
        listOf("fieldKey", "keyColumn", "email", "name", "mapping", "sendEmailConfirmation", "cf_firmado", "author", "code", "spinner", "shipping")
            .forEach { assertFalse(ActivityChanges.looksSecret(it), it) }
        // A value with a credential is masked whatever the field is called.
        listOf(
            "https://ana:clave123@hooks.example.com/x", "https://api.example.com/v1?token=abcdef123456", "postgres://u:p@db/x",
            "-----BEGIN RSA PRIVATE KEY-----", "sk-live_abcdefghijklmnop",
        ).forEach { value ->
            assertTrue(ActivityChanges.valueLooksSecret(value), value)
            val change = ActivityChanges.diff(mapOf("destino" to "a"), mapOf("destino" to value), mapOf("destino" to "Destino")).single()
            assertTrue(change.secret, value)
            assertNull(change.new)
            assertTrue(ActivityChange.of("destino", "Destino", null, value).secret)
        }
        assertFalse(ActivityChanges.valueLooksSecret("https://www.example.com/precios?page=2"))
    }

    @Test
    fun extensionSettingsAreLoggedOnlyWhenTheManifestSaysSo() {
        // Unmarked (no type, no loggable): only "changed", even if it looks harmless.
        assertFalse(ExtensionSettingDefinition("board").valueLoggable)
        assertFalse(ExtensionSettingDefinition("endpoint", type = "url").valueLoggable)
        assertFalse(ExtensionSettingDefinition("wifi", type = "password", loggable = true).valueLoggable)
        assertFalse(ExtensionSettingDefinition("token", secret = true, loggable = true).valueLoggable)
        assertFalse(ExtensionSettingDefinition("board", type = "text", loggable = false).valueLoggable)
        assertTrue(ExtensionSettingDefinition("board", type = "text").valueLoggable)
        assertTrue(ExtensionSettingDefinition("limit", type = "number").valueLoggable)
        assertTrue(ExtensionSettingDefinition("custom", loggable = true).valueLoggable)
    }

    @Test
    fun agentReadsAreLimitedPerKey() {
        var now = 0L
        val limiter = ActivityReadLimiter(limit = 3, windowMillis = 60_000, maxKeys = 2, clock = { now })
        repeat(3) { assertTrue(limiter.tryAcquire("a")) }
        assertFalse(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"))
        now += 60_001
        assertTrue(limiter.tryAcquire("a"))
        // Bounded memory: a third key drops the oldest window.
        assertTrue(limiter.tryAcquire("c"))
    }

    @Test
    fun deepPagesAreRefused() {
        assertEquals(0, ActivityFilter.of(mapOf("page" to "-5")::get).pageIndex)
        assertEquals(ActivityLogReader.MaxPage, ActivityFilter(page = 10_000_000).pageIndex)
        kotlin.test.assertFailsWith<ActivityFilterException> { ActivityFilter.of(mapOf("page" to "1001")::get) }
    }
}
