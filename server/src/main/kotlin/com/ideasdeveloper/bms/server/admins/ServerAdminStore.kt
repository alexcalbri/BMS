package com.ideasdeveloper.bms.server.admins

import com.ideasdeveloper.bms.server.auth.infrastructure.security.ApplicationPasswordHasher
import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/** PBKDF2 hash of an administrator's password as stored in server_admins.password_hash. */
object ServerAdminPasswords {
    private const val Prefix = "pbkdf2_sha256"
    const val MinLength = 16
    const val MaxLength = 200

    // A fixed hash with the current cost: unknown users take as long as real ones at sign-in.
    private val dummy: String by lazy { encode("not-a-real-password-for-timing-only") }

    fun encode(password: String): String {
        val hash = ApplicationPasswordHasher.hash(password)
        return "$Prefix\$${hash.iterations}\$${hash.salt}\$${hash.hash}"
    }

    fun verify(password: String, stored: String?): Boolean {
        val parts = stored?.split('$')
        if (parts == null || parts.size != 4 || parts[0] != Prefix) {
            verify(password, dummy)
            return false
        }
        val iterations = parts[1].toIntOrNull() ?: return false.also { verify(password, dummy) }
        return runCatching { ApplicationPasswordHasher.verify(password, parts[2], parts[3], iterations) }.getOrDefault(false)
    }

    /** Spends the same time as a real check (unknown user, server owner). */
    fun spendDummyCheck(password: String) {
        verify(password, dummy)
    }

    fun problem(password: String): String? = when {
        password.length < MinLength -> com.ideasdeveloper.bms.server.i18n.tr("La contrasena debe tener al menos {0} caracteres.", MinLength)
        password.length > MaxLength -> com.ideasdeveloper.bms.server.i18n.tr("La contrasena es demasiado larga.")
        else -> null
    }
}

/** One administrator as stored (control V015). Unknown permission ids are dropped when read. */
class ServerAdminRow(
    val userId: UUID,
    val login: String,
    val displayName: String?,
    val email: String?,
    val userActive: Boolean,
    val active: Boolean,
    val allCompanies: Boolean,
    val mustChangePassword: Boolean,
    val passwordHash: String,
    val permissions: Set<ServerPermission>,
    val companies: Set<String>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    val usable: Boolean get() = active && userActive
}

/**
 * SQL of server administrators in the central database. Before control V015 the tables do not
 * exist: reads then answer "no administrator" (SQLState 42P01), so owners keep working.
 */
class ServerAdminStore(internal val central: DataSource) {

    private fun <T> tolerant(missing: T, block: () -> T): T = try {
        block()
    } catch (failure: SQLException) {
        if (failure.isMissingRelation()) missing else throw failure
    }

    fun find(userId: UUID): ServerAdminRow? = tolerant(null) { central.connection.use { find(it, userId) } }

    fun findByLogin(login: String): ServerAdminRow? = tolerant(null) {
        central.connection.use { connection ->
            val id = connection.prepareStatement(
                "SELECT admins.user_id FROM server_admins admins JOIN application_users users ON users.id = admins.user_id WHERE users.postgres_role = ?"
            ).use { query ->
                query.setString(1, login)
                query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
            } ?: return@use null
            find(connection, id)
        }
    }

    fun list(): List<ServerAdminRow> = tolerant(emptyList()) {
        central.connection.use { connection ->
            val ids = connection.prepareStatement(
                "SELECT admins.user_id FROM server_admins admins JOIN application_users users ON users.id = admins.user_id ORDER BY users.postgres_role"
            ).use { query -> query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } } }
            ids.mapNotNull { find(connection, it) }
        }
    }

    fun find(connection: Connection, userId: UUID): ServerAdminRow? {
        val base = connection.prepareStatement(
            """
            SELECT users.postgres_role, users.display_name, users.email, users.is_active, admins.is_active, admins.all_companies,
                   admins.must_change_password, admins.password_hash, admins.created_at, admins.updated_at
            FROM server_admins admins JOIN application_users users ON users.id = admins.user_id
            WHERE admins.user_id = ?
            """.trimIndent()
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows ->
                if (!rows.next()) null else listOf<Any?>(
                    rows.getString(1), rows.getString(2), rows.getString(3), rows.getBoolean(4), rows.getBoolean(5), rows.getBoolean(6),
                    rows.getBoolean(7), rows.getString(8), rows.getObject(9, OffsetDateTime::class.java), rows.getObject(10, OffsetDateTime::class.java),
                )
            }
        } ?: return null
        val permissions = connection.prepareStatement("SELECT permission FROM server_admin_permissions WHERE user_id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) ServerPermission.of(rows.getString(1))?.let(::add) } }
        }
        val companies = connection.prepareStatement("SELECT company_code FROM server_admin_companies WHERE user_id = ? ORDER BY company_code").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        return ServerAdminRow(
            userId = userId,
            login = base[0] as String,
            displayName = base[1] as String?,
            email = base[2] as String?,
            userActive = base[3] as Boolean,
            active = base[4] as Boolean,
            allCompanies = base[5] as Boolean,
            mustChangePassword = base[6] as Boolean,
            passwordHash = base[7] as String,
            permissions = permissions,
            companies = companies,
            createdAt = base[8] as OffsetDateTime,
            updatedAt = base[9] as OffsetDateTime,
        )
    }

    fun isOwner(userId: UUID): Boolean = central.connection.use { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM server_owners owners JOIN application_users users ON users.id = owners.user_id WHERE owners.user_id = ? AND users.is_active"
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { it.next() }
        }
    }

    /** PostgreSQL role of the first active server owner: what an administrator's work runs as. */
    fun executorRole(): String? = central.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT users.postgres_role FROM server_owners owners JOIN application_users users ON users.id = owners.user_id
            WHERE users.is_active ORDER BY users.postgres_role LIMIT 1
            """.trimIndent()
        ).use { query -> query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null } }
    }

    fun create(
        userId: UUID,
        login: String,
        displayName: String,
        email: String,
        passwordHash: String,
        permissions: Set<ServerPermission>,
        allCompanies: Boolean,
        companies: Set<String>,
        createdBy: UUID,
    ) = transaction { connection ->
        connection.prepareStatement("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES (?, ?, TRUE, ?, ?)").use { insert ->
            insert.setObject(1, userId)
            insert.setString(2, login)
            insert.setString(3, displayName)
            insert.setString(4, email)
            insert.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO server_admins (user_id, is_active, all_companies, password_hash, must_change_password, created_by) VALUES (?, TRUE, ?, ?, TRUE, ?)"
        ).use { insert ->
            insert.setObject(1, userId)
            insert.setBoolean(2, allCompanies)
            insert.setString(3, passwordHash)
            insert.setObject(4, createdBy)
            insert.executeUpdate()
        }
        replacePermissions(connection, userId, permissions)
        replaceCompanies(connection, userId, companies)
    }

    fun updateProfile(userId: UUID, displayName: String, email: String) = transaction { connection ->
        connection.prepareStatement("UPDATE application_users SET display_name = ?, email = ? WHERE id = ? AND id IN (SELECT user_id FROM server_admins)").use { update ->
            update.setString(1, displayName)
            update.setString(2, email)
            update.setObject(3, userId)
            update.executeUpdate()
        }
        touch(connection, userId)
    }

    fun setPermissions(userId: UUID, permissions: Set<ServerPermission>) = transaction { connection ->
        replacePermissions(connection, userId, permissions)
        touch(connection, userId)
    }

    fun setScope(userId: UUID, allCompanies: Boolean, companies: Set<String>) = transaction { connection ->
        connection.prepareStatement("UPDATE server_admins SET all_companies = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?").use { update ->
            update.setBoolean(1, allCompanies)
            update.setObject(2, userId)
            update.executeUpdate()
        }
        replaceCompanies(connection, userId, companies)
    }

    /** Adds [companyCode] to the scope (a company the administrator created). */
    fun addCompany(userId: UUID, companyCode: String) {
        central.connection.use { connection ->
            connection.prepareStatement("INSERT INTO server_admin_companies (user_id, company_code) VALUES (?, ?) ON CONFLICT DO NOTHING").use { insert ->
                insert.setObject(1, userId)
                insert.setString(2, companyCode)
                insert.executeUpdate()
            }
        }
    }

    fun setPassword(userId: UUID, passwordHash: String, mustChange: Boolean) = transaction { connection ->
        connection.prepareStatement(
            "UPDATE server_admins SET password_hash = ?, must_change_password = ?, password_updated_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?"
        ).use { update ->
            update.setString(1, passwordHash)
            update.setBoolean(2, mustChange)
            update.setObject(3, userId)
            update.executeUpdate()
        }
    }

    fun setActive(userId: UUID, active: Boolean) = transaction { connection ->
        connection.prepareStatement("UPDATE server_admins SET is_active = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?").use { update ->
            update.setBoolean(1, active)
            update.setObject(2, userId)
            update.executeUpdate()
        }
    }

    /**
     * Removes the administrator. The application_users row stays (without an owner or administrator
     * row it cannot sign in anywhere) so the audit keeps pointing to it and the login is never reused.
     */
    fun delete(userId: UUID) = transaction { connection ->
        connection.prepareStatement("DELETE FROM server_admins WHERE user_id = ?").use { delete ->
            delete.setObject(1, userId)
            delete.executeUpdate()
        }
    }

    /** Revokes the central sessions of [userId] except the one with [keepTokenHash]. */
    fun revokeSessions(userId: UUID, keepTokenHash: String? = null): Int = central.connection.use { connection ->
        connection.prepareStatement(
            "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL AND token_hash <> ?"
        ).use { revoke ->
            revoke.setObject(1, userId)
            revoke.setString(2, keepTokenHash ?: "")
            revoke.executeUpdate()
        }
    }

    /**
     * Who the audit names for a change recorded only with the login of who acted (extension
     * packages): the owner or administrator with that login, else the system.
     */
    fun auditActorFor(login: String): AuditActor = try {
        central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT users.id, EXISTS (SELECT 1 FROM server_owners owners WHERE owners.user_id = users.id) FROM application_users users WHERE users.postgres_role = ?"
            ).use { query ->
                query.setString(1, login)
                query.executeQuery().use { rows ->
                    if (!rows.next()) AuditActor.system(login)
                    else AuditActor(login, rows.getObject(1, UUID::class.java), if (rows.getBoolean(2)) AuditActor.ServerOwnerKind else AuditActor.ServerAdminKind)
                }
            }
        }
    } catch (_: SQLException) {
        AuditActor.system(login)
    }

    /**
     * The actor a queued install asked for by [login] runs as now (see [QueuedInstallActors]): an
     * active owner, or an active administrator who no longer must change the password and still
     * holds [permission]; null otherwise. Fails closed (null) when the database cannot answer.
     */
    fun queuedInstallActor(login: String, permission: ServerPermission): AuditActor? {
        if (login.isBlank()) return AuditActor.system()
        return try {
            central.connection.use { connection ->
                val user = connection.prepareStatement(
                    """
                    SELECT users.id, users.is_active, EXISTS (SELECT 1 FROM server_owners owners WHERE owners.user_id = users.id)
                    FROM application_users users WHERE users.postgres_role = ?
                    """.trimIndent()
                ).use { query ->
                    query.setString(1, login)
                    query.executeQuery().use { rows -> if (rows.next()) Triple(rows.getObject(1, UUID::class.java), rows.getBoolean(2), rows.getBoolean(3)) else null }
                } ?: return@use null
                val (id, userActive, owner) = user
                when {
                    !userActive -> null
                    owner -> AuditActor.owner(login, id)
                    else -> find(connection, id)
                        ?.takeIf { it.usable && !it.mustChangePassword && permission in it.permissions }
                        ?.let { AuditActor(login, id, AuditActor.ServerAdminKind) }
                }
            }
        } catch (_: SQLException) {
            null
        }
    }

    /** Whether [login] is already used by a central user (owner, administrator or a removed administrator). */
    fun centralLoginTaken(login: String): Boolean = central.connection.use { connection ->
        connection.prepareStatement("SELECT 1 FROM application_users WHERE postgres_role = ?").use { query ->
            query.setString(1, login)
            query.executeQuery().use { it.next() }
        }
    }

    fun existingCompanies(codes: Collection<String>): Set<String> = if (codes.isEmpty()) emptySet() else central.connection.use { connection ->
        connection.prepareStatement("SELECT code FROM companies WHERE code = ANY (?) AND NOT is_server_workspace").use { query ->
            query.setArray(1, connection.createArrayOf("varchar", codes.toTypedArray()))
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun replacePermissions(connection: Connection, userId: UUID, permissions: Set<ServerPermission>) {
        connection.prepareStatement("DELETE FROM server_admin_permissions WHERE user_id = ?").use { delete ->
            delete.setObject(1, userId)
            delete.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO server_admin_permissions (user_id, permission) VALUES (?, ?)").use { insert ->
            permissions.forEach { permission ->
                insert.setObject(1, userId)
                insert.setString(2, permission.id)
                insert.addBatch()
            }
            if (permissions.isNotEmpty()) insert.executeBatch()
        }
    }

    private fun replaceCompanies(connection: Connection, userId: UUID, companies: Set<String>) {
        connection.prepareStatement("DELETE FROM server_admin_companies WHERE user_id = ?").use { delete ->
            delete.setObject(1, userId)
            delete.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO server_admin_companies (user_id, company_code) VALUES (?, ?)").use { insert ->
            companies.forEach { code ->
                insert.setObject(1, userId)
                insert.setString(2, code)
                insert.addBatch()
            }
            if (companies.isNotEmpty()) insert.executeBatch()
        }
    }

    private fun touch(connection: Connection, userId: UUID) {
        connection.prepareStatement("UPDATE server_admins SET updated_at = CURRENT_TIMESTAMP WHERE user_id = ?").use { update ->
            update.setObject(1, userId)
            update.executeUpdate()
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = central.connection.use { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            runCatching { connection.autoCommit = true }
        }
    }
}

/** SQLState 42P01: a table that does not exist yet (before its migration). */
fun SQLException.isMissingRelation(): Boolean =
    generateSequence<Throwable>(this) { it.cause }.any { (it as? SQLException)?.sqlState == "42P01" }
