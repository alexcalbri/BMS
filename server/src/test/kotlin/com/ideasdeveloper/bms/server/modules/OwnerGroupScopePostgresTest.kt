package com.ideasdeveloper.bms.server.modules

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SQL real de la regla del grupo de dueno contra un PostgreSQL de pruebas. Solo corre con
 * BMS_OWNER_GROUP_PG_URL (jdbc:postgresql://host/base?user=...&password=...) apuntando a una base
 * VACIA y desechable: crea y borra tablas.
 */
class OwnerGroupScopePostgresTest {
    private val url: String? = System.getenv("BMS_OWNER_GROUP_PG_URL")?.takeIf { it.isNotBlank() }
    private val groupA = UUID.randomUUID()
    private val groupB = UUID.randomUUID()

    private fun withDb(block: (Connection) -> Unit) {
        val target = url ?: return
        DriverManager.getConnection(target).use { db ->
            db.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS business_owner, application_users, companies")
                // The columns the rule reads, with the types of the control migrations (V001, V006, V008, V011).
                st.execute(
                    """
                    CREATE TABLE companies (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        code TEXT NOT NULL UNIQUE,
                        name TEXT NOT NULL,
                        is_active BOOLEAN NOT NULL DEFAULT TRUE,
                        is_server_workspace BOOLEAN NOT NULL DEFAULT FALSE,
                        owner_group UUID NOT NULL,
                        group_suspended BOOLEAN NOT NULL DEFAULT FALSE
                    )
                    """.trimIndent()
                )
                st.execute("CREATE TABLE application_users (id UUID PRIMARY KEY, is_active BOOLEAN NOT NULL DEFAULT TRUE)")
                st.execute("CREATE TABLE business_owner (singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton), user_id UUID NOT NULL UNIQUE REFERENCES application_users(id))")
            }
            block(db)
            db.createStatement().use { it.execute("DROP TABLE IF EXISTS business_owner, application_users, companies") }
        }
    }

    private fun Connection.company(code: String, group: UUID, active: Boolean = true, workspace: Boolean = false, suspended: Boolean = false) {
        prepareStatement("INSERT INTO companies (code, name, is_active, is_server_workspace, owner_group, group_suspended) VALUES (?, ?, ?, ?, ?, ?)").use { insert ->
            insert.setString(1, code)
            insert.setString(2, "Empresa $code")
            insert.setBoolean(3, active)
            insert.setBoolean(4, workspace)
            insert.setObject(5, group)
            insert.setBoolean(6, suspended)
            insert.executeUpdate()
        }
    }

    private fun codes(db: Connection, from: String) = OwnerGroupScope.companies(db, from).map { it.code }

    @Test
    fun onlyActiveCompaniesOfTheSameGroupAreReachable() = withDb { db ->
        db.company("uno", groupA)
        db.company("dos", groupA)
        db.company("inactiva", groupA, active = false)
        db.company("suspendida", groupA, suspended = true)
        db.company("servidor", groupA, workspace = true)
        db.company("ajena", groupB)
        db.company("sola", UUID.randomUUID())

        // Same group: the company itself first, then the others; nothing inactive, suspended, of the server or of another group.
        assertEquals(listOf("uno", "dos"), codes(db, "uno"))
        assertEquals(listOf("dos", "uno"), codes(db, "dos"))
        assertEquals(listOf("ajena"), codes(db, "ajena"))
        assertEquals(listOf("sola"), codes(db, "sola"))
        // A company that is inactive, suspended in its group or the server workspace reaches nothing, itself included.
        assertEquals(emptyList(), codes(db, "inactiva"))
        assertEquals(emptyList(), codes(db, "suspendida"))
        assertEquals(emptyList(), codes(db, "servidor"))
        assertEquals(emptyList(), codes(db, "no_existe"))
        assertEquals(emptyList(), codes(db, "uno' OR '1'='1"))
    }

    @Test
    fun leavingTheGroupTakesEffectOnTheNextRead() = withDb { db ->
        db.company("uno", groupA)
        db.company("dos", groupA)
        assertEquals(listOf("uno", "dos"), codes(db, "uno"))
        db.createStatement().use { it.execute("UPDATE companies SET owner_group = id WHERE code = 'dos'") }
        assertEquals(listOf("uno"), codes(db, "uno"))
        assertEquals(listOf("dos"), codes(db, "dos"))
    }

    @Test
    fun businessOwnerMustBeActive() = withDb { db ->
        assertNull(OwnerGroupScope.activeBusinessOwner(db))
        val owner = UUID.randomUUID()
        db.prepareStatement("INSERT INTO application_users (id) VALUES (?)").use { it.setObject(1, owner); it.executeUpdate() }
        db.prepareStatement("INSERT INTO application_users (id) VALUES (?)").use { it.setObject(1, UUID.randomUUID()); it.executeUpdate() }
        db.prepareStatement("INSERT INTO business_owner (user_id) VALUES (?)").use { it.setObject(1, owner); it.executeUpdate() }
        assertEquals(owner, OwnerGroupScope.activeBusinessOwner(db))
        db.createStatement().use { it.execute("UPDATE application_users SET is_active = FALSE") }
        assertNull(OwnerGroupScope.activeBusinessOwner(db))
    }
}
