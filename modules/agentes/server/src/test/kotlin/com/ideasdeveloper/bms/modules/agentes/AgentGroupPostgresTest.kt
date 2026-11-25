package com.ideasdeveloper.bms.modules.agentes

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SQL real de las conexiones de agentes con alcance de grupo contra un PostgreSQL de pruebas. Solo corre
 * con BMS_OWNER_GROUP_PG_URL apuntando a una base VACIA y desechable: aplica las migraciones del modulo.
 */
class AgentGroupPostgresTest {
    private val url: String? = System.getenv("BMS_OWNER_GROUP_PG_URL")?.takeIf { it.isNotBlank() }
    private val owner = AgentOwner(UUID.randomUUID(), serverOwner = false, name = "Ana")

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "modules/agentes/migrations").isDirectory }

    private fun withDb(block: (Connection) -> Unit) {
        val target = url ?: return
        DriverManager.getConnection(target).use { db ->
            db.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS agent_group_activity, agent_activity, agent_refresh_history, agent_tokens CASCADE")
                File(root(), "modules/agentes/migrations").listFiles().orEmpty().filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                    .forEach { st.execute(it.readText()) }
            }
            block(db)
        }
    }

    @Test
    fun groupScopeIsOffUnlessAskedFor() = withDb { db ->
        val (plain, _) = AgentTokens.create(db, "uno", owner, "Claude", null, readOnly = false, expiresAt = null)
        val (group, _) = AgentTokens.create(db, "uno", owner, "Claude grupo", null, readOnly = true, expiresAt = null, groupScope = true)
        val oauth = AgentTokens.createOAuth(db, "uno", owner, "client", "Claude web", null, false, null, java.time.Duration.ofHours(1), groupScope = true).token
        assertFalse(plain.groupScope)
        assertTrue(group.groupScope)
        assertTrue(oauth.groupScope)
        assertEquals(listOf(true, true, false), AgentTokens.list(db, owner.userId).sortedBy { it.name }.map { it.groupScope }.sortedDescending())
        // A row written before the column existed reads as off.
        db.createStatement().use { it.execute("UPDATE agent_tokens SET group_scope = DEFAULT WHERE id = '${group.id}'") }
        assertFalse(AgentTokens.find(db, group.id)!!.groupScope)
    }

    @Test
    fun aRevokedOrExpiredConnectionStopsBetweenCalls() = withDb { db ->
        val (token, _) = AgentTokens.create(db, "uno", owner, "Claude", null, readOnly = false, expiresAt = null)
        val (expired, _) = AgentTokens.create(db, "uno", owner, "Vencido", null, readOnly = false, expiresAt = Instant.now().minusSeconds(60))
        assertTrue(AgentTokens.stillActive(db, token.id))
        assertFalse(AgentTokens.stillActive(db, expired.id))
        assertFalse(AgentTokens.stillActive(db, UUID.randomUUID()))
        AgentTokens.revoke(db, token.id, "Ana")
        assertFalse(AgentTokens.stillActive(db, token.id))
    }

    @Test
    fun callsFromOtherCompaniesAreKeptWhereTheyRan() = withDb { db ->
        AgentTokens.logGroupCall(db, "uno", "Claude", "Ana", "crm_search_leads", "ok")
        AgentTokens.logGroupCall(db, "uno", "Claude", "Ana", "crm_delete_lead", "error")
        val rows = AgentTokens.groupActivity(db, 10)
        assertEquals(setOf("crm_search_leads" to "ok", "crm_delete_lead" to "error"), rows.map { it.tool to it.outcome }.toSet())
        assertTrue(rows.all { it.originCompany == "uno" && it.agentName == "Claude" && it.userName == "Ana" })
        assertEquals(1, AgentTokens.groupActivity(db, 1).size)
        db.createStatement().use { it.execute("UPDATE agent_group_activity SET created_at = CURRENT_TIMESTAMP - interval '100 days' WHERE tool = 'crm_delete_lead'") }
        AgentTokens.purgeActivity(db, 90)
        assertEquals(listOf("crm_search_leads"), AgentTokens.groupActivity(db, 10).map { it.tool })
    }
}
