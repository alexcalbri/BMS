package com.ideasdeveloper.bms.server.auth.infrastructure.database

import com.ideasdeveloper.bms.server.auth.domain.ApplicationUser
import com.ideasdeveloper.bms.server.auth.infrastructure.security.ApplicationPasswordHasher
import com.ideasdeveloper.bms.server.auth.infrastructure.security.PasswordHash
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ApplicationUserRepository(
    private val database: Database,
) {

    fun findByPostgresRole(postgresRole: String): ApplicationUser? {
        return transaction(db = database) {
            ApplicationUsersTable
                .selectAll()
                .where {
                    ApplicationUsersTable.postgresRole eq postgresRole
                }
                .singleOrNull()
                ?.let { row ->
                    ApplicationUser(
                        id = row[ApplicationUsersTable.id],
                        postgresRole = row[ApplicationUsersTable.postgresRole],
                        isActive = row[ApplicationUsersTable.isActive],
                    )
                }
        }
    }
    fun findById(userId: Uuid): ApplicationUser? {
        return transaction(db = database) {
            ApplicationUsersTable
                .selectAll()
                .where {
                    ApplicationUsersTable.id eq userId
                }
                .singleOrNull()
                ?.let { row ->
                    ApplicationUser(
                        id = row[ApplicationUsersTable.id],
                        postgresRole = row[ApplicationUsersTable.postgresRole],
                        isActive = row[ApplicationUsersTable.isActive],
                    )
                }
        }
    }

    /**
     * Whether [password] is the application password of the active user [postgresRole]. Every case
     * does the same PBKDF2 work, ApplicationPasswordHasher.Iterations (B2): an unknown or inactive user
     * computes a dummy hash at that cost, and a hash stored with fewer iterations is topped up with a
     * dummy for the difference (before it is re-hashed at the current cost on success).
     */
    fun verifyApplicationPassword(postgresRole: String, password: String): Boolean {
        return transaction(db = database) {
            val row = ApplicationUsersTable
                .innerJoin(ApplicationUserCredentialsTable)
                .selectAll()
                .where {
                    (ApplicationUsersTable.postgresRole eq postgresRole) and
                        (ApplicationUsersTable.isActive eq true)
                }
                .singleOrNull()
                ?: run {
                    spendDummyCheck(password)
                    return@transaction false
                }

            val stored = row[ApplicationUserCredentialsTable.iterations]
            val valid = ApplicationPasswordHasher.verify(
                password = password,
                salt = row[ApplicationUserCredentialsTable.passwordSalt],
                expectedHash = row[ApplicationUserCredentialsTable.passwordHash],
                iterations = stored,
            )
            if (stored < ApplicationPasswordHasher.Iterations) {
                ApplicationPasswordHasher.verify(password, DummySalt, DummyHash, ApplicationPasswordHasher.Iterations - stored)
            }
            // Hash guardado con menos iteraciones: se re-hashea con el coste actual al iniciar sesion.
            if (valid && stored < ApplicationPasswordHasher.Iterations) {
                val upgraded = ApplicationPasswordHasher.hash(password)
                ApplicationUserCredentialsTable.update({
                    ApplicationUserCredentialsTable.userId eq row[ApplicationUsersTable.id]
                }) {
                    it[passwordSalt] = upgraded.salt
                    it[passwordHash] = upgraded.hash
                    it[iterations] = upgraded.iterations
                }
            }
            valid
        }
    }

    /** True si el usuario es dueno (business owner o server owner) de la base de este repositorio. */
    fun isOwner(userId: Uuid): Boolean =
        existsIn(BusinessOwnerTable, BusinessOwnerTable.userId, userId) ||
            existsIn(ServerOwnersTable, ServerOwnersTable.userId, userId)

    // Tablas que no existen en esta base (tenant sin server_owners, central sin business_owner). Se recuerdan
    // para no repetir la consulta fallida (Exposed la reintenta) en cada inicio de sesion.
    private val missingTables = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Solo el error "tabla inexistente" (SQLState 42P01) cuenta como "no es dueno"; cualquier otro
    // fallo (conexion, tiempo de espera) se relanza para que el login falle de forma visible.
    private fun existsIn(table: Table, column: Column<Uuid>, userId: Uuid): Boolean {
        if (table.tableName in missingTables) return false
        return try {
            transaction(db = database) { !table.selectAll().where { column eq userId }.empty() }
        } catch (failure: Exception) {
            if (generateSequence<Throwable>(failure) { it.cause }.any { (it as? java.sql.SQLException)?.sqlState == "42P01" }) {
                missingTables.add(table.tableName)
                false
            } else {
                throw failure
            }
        }
    }

    private object BusinessOwnerTable : Table("business_owner") {
        val userId = uuid("user_id")
    }

    private object ServerOwnersTable : Table("server_owners") {
        val userId = uuid("user_id")
    }

    companion object {
        // Hash ficticio (clave y sal fijas) solo para igualar el tiempo cuando el usuario no existe.
        private const val DummySalt = "AAAAAAAAAAAAAAAAAAAAAA=="
        private const val DummyHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        /** One PBKDF2 at the current cost, for paths where no real hash is checked (unknown user, company or login). */
        fun spendDummyCheck(password: String) {
            ApplicationPasswordHasher.verify(password, DummySalt, DummyHash, ApplicationPasswordHasher.Iterations)
        }
    }

    fun setApplicationPassword(userId: Uuid, passwordHash: PasswordHash) {
        transaction(db = database) {
            ApplicationUserCredentialsTable.insert {
                it[ApplicationUserCredentialsTable.userId] = userId
                it[passwordSalt] = passwordHash.salt
                it[ApplicationUserCredentialsTable.passwordHash] = passwordHash.hash
                it[iterations] = passwordHash.iterations
            }
        }
    }
}
