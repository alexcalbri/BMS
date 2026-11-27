package com.ideasdeveloper.bms.modules.agentes

import com.ideasdeveloper.bms.server.auth.application.CredentialsChanged
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who really connected an agent (V006) and the revocation by that person (final verification item 1). The SQL part
 * runs with BMS_TEST_PG_URL in its own database bms_agop_test.
 */
class AgentOpenerTest {
    private val url: String? = System.getenv("BMS_TEST_PG_URL")?.takeIf { it.isNotBlank() }
    private val owner = AgentOwner(UUID.randomUUID(), serverOwner = false, name = "Dueno de beta")
    private val serverPerson = UUID.randomUUID()
    private val acmeOwner = UUID.randomUUID()

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "modules/agentes/migrations").isDirectory }

    private fun withDb(block: (Connection) -> Unit) {
        val target = url ?: return
        val db = "bms_agop_test"
        DriverManager.getConnection(target).use { admin ->
            admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS $db WITH (FORCE)"); it.execute("CREATE DATABASE $db") }
        }
        try {
            DriverManager.getConnection(target.replace(Regex("/[^/?]+(\\?|$)"), "/$db$1")).use { connection ->
                connection.createStatement().use { st ->
                    File(root(), "modules/agentes/migrations").listFiles().orEmpty().filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                        .forEach { st.execute(it.readText()) }
                }
                block(connection)
            }
        } finally {
            DriverManager.getConnection(target).use { admin -> admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS $db WITH (FORCE)") } }
        }
    }

    @Test
    fun connectionsAreRevokedByThePersonWhoReallyOpenedThem() = withDb { db ->
        val (own, _) = AgentTokens.create(db, "beta", owner, "Propio", null, readOnly = false, expiresAt = null)
        val (byServer, _) = AgentTokens.create(db, "beta", owner, "Del servidor", null, false, null, opener = TokenOpener(TokenOpener.Server, serverPerson, null))
        val (byAcme, _) = AgentTokens.create(db, "beta", owner, "De acme", null, false, null, opener = TokenOpener(TokenOpener.Company, acmeOwner, "acme"))
        val (legacy, _) = AgentTokens.create(db, "beta", owner, "Antiguo", null, false, null)
        db.createStatement().use { it.execute("UPDATE agent_tokens SET opener_kind = NULL WHERE id = '${legacy.id}'") }
        assertEquals(TokenOpener.Self, AgentTokens.find(db, own.id)!!.openerKind)
        assertNull(AgentTokens.find(db, legacy.id)!!.openerKind)
        assertTrue(AgentTokens.find(db, byServer.id)!!.openedThroughSwitcher)

        // The server person's credentials change: only what that person opened here.
        assertEquals(1, AgentTokens.revokeOpenedBy(db, CredentialsChanged(null, serverPerson, CredentialsChanged.TwoFactorEnabled), "x"))
        assertFalse(AgentTokens.stillActive(db, byServer.id))
        // The owner of acme (business-owner switcher): the one opened from acme, not a same id from another company.
        assertEquals(0, AgentTokens.revokeOpenedBy(db, CredentialsChanged("otra", acmeOwner, CredentialsChanged.PasswordReset), "x"))
        assertEquals(1, AgentTokens.revokeOpenedBy(db, CredentialsChanged("acme", acmeOwner, CredentialsChanged.PasswordReset), "x"))
        assertFalse(AgentTokens.stillActive(db, byAcme.id))
        // The account's own and the legacy ones are untouched by those (the account's own event revokes them).
        assertTrue(AgentTokens.stillActive(db, own.id))
        assertTrue(AgentTokens.stillActive(db, legacy.id))
        assertEquals(2, AgentTokens.revokeAllOfUser(db, owner.userId, "x"))
    }

    @Test
    fun theOpenerIsReCheckedAndCachedBriefly() {
        var now = 0L
        var allowed = true
        var calls = 0
        val checks = OpenerChecks(ttlMillis = 1_000) { now }
        val token = AgentToken(UUID.randomUUID(), UUID.randomUUID(), false, "x", "x", "x", null, false, java.time.Instant.EPOCH, null, null, null, null,
            openerKind = TokenOpener.Server, openerUser = serverPerson)
        fun ask() = checks.allowed(token, "beta") { calls++; allowed }
        assertTrue(ask())
        allowed = false
        assertTrue(ask())
        assertEquals(1, calls)
        now = 1_001
        assertFalse(ask())
        // Without a recorded person the connection is refused.
        assertFalse(OpenerChecks().allowed(token.copy(openerUser = null), "beta") { true })
    }
}
