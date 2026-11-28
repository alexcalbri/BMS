package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.install.ServerModuleInstaller
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.auth.application.EmailChangeRule
import com.ideasdeveloper.bms.server.modules.PackageInstallQueue
import com.ideasdeveloper.bms.server.modules.CompanyFiles
import com.ideasdeveloper.bms.server.security.ScramSha256
import com.ideasdeveloper.bms.server.company.api.ServerNameResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ServerNameSetting
import com.ideasdeveloper.bms.server.company.api.DuplicateOwnerRequest
import com.ideasdeveloper.bms.server.company.api.BusinessOwnerResponse
import com.ideasdeveloper.bms.server.company.api.UpdateBusinessOwnerRequest
import com.ideasdeveloper.bms.server.modules.SettingsCopy
import com.ideasdeveloper.bms.server.auth.infrastructure.database.TenantDatabaseConfig
import com.ideasdeveloper.bms.server.auth.application.UserProfileRules
import com.ideasdeveloper.bms.server.company.api.CreateCompanyRequest
import com.ideasdeveloper.bms.server.company.api.CreateCompanyResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.api.CompanyModuleResponse
import com.ideasdeveloper.bms.server.company.api.CompanySummaryResponse
import com.ideasdeveloper.bms.server.company.api.ServerModuleCatalogResponse
import com.ideasdeveloper.bms.server.company.api.ServerModuleResponse
import com.ideasdeveloper.bms.server.company.api.UpdateCompanyRequest
import com.ideasdeveloper.bms.server.modules.ModuleDefinition
import com.ideasdeveloper.bms.server.modules.ModuleRequirementGate
import com.ideasdeveloper.bms.server.app.BMSVersion
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.bms.server.modules.RemoteModulePackage
import com.ideasdeveloper.bms.ModuleVersion
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.trIn
import com.ideasdeveloper.bms.server.modules.GitHubLocation
import com.ideasdeveloper.bms.server.modules.ModuleBuildException
import com.ideasdeveloper.bms.server.modules.ModulePackageBuilder
import com.ideasdeveloper.bms.server.modules.ModulePackageInspection
import com.ideasdeveloper.bms.server.modules.ModuleUnsignedAuthorizationResponse
import com.ideasdeveloper.bms.server.modules.ServerModule
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.postgresql.ds.PGSimpleDataSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource
import kotlin.io.path.name

internal class BusinessOwnerAccounts(
    private val central: DataSource,
    private val loginDatabases: LoginDatabases,
    private val tenantSchema: TenantSchemaProvisioner,
    private val postgresRolePattern: Regex,
    private val firstServerOwnerRole: () -> String?,
    private val rejectServerWorkspace: (String) -> Unit,
    private val findCompanyAnyState: (Connection, String) -> CompanyRow?,
    private val tenantRuntimeConnection: (TenantDatabaseConfig) -> Connection,
    private val actorName: (AuditActor) -> String?,
) {
    private val log = LoggerFactory.getLogger(CompanyProvisioningService::class.java)

    /**
     * Sets a new password for the company's business owner and closes their open sessions. Only the
     * server owner does this, from the app: companies created by an AI agent get a random password
     * nobody knows, because credentials never go through the MCP.
     */
    fun resetBusinessOwnerPassword(companyCode: String, password: String, provisioningRole: String, actor: AuditActor) =
        changeBusinessOwnerPassword(companyCode, password, provisioningRole, "company.owner_password_reset", actor)

    /**
     * New password of a business owner who proved, with a code sent to their email, that they own the
     * account (AccountService). Runs as the first server owner's role, which can change company roles.
     */
    fun setBusinessOwnerPasswordByEmailCode(companyCode: String, password: String) {
        val role = firstServerOwnerRole() ?: throw CompanyProvisioningException(tr("El servidor no tiene un dueno activo."))
        changeBusinessOwnerPassword(companyCode, password, role, "company.owner_password_recovered", null)
    }

    /** New password of a server owner who proved with an email code that they own the account. */
    fun setServerOwnerPasswordByEmailCode(userId: UUID, password: String) {
        val role = serverOwnerRole(userId) ?: throw CompanyProvisioningException(tr("El usuario ya no es dueno del servidor."))
        if (password.length < 16 || password.any { it !in ' '..'~' }) {
            throw CompanyProvisioningException(tr("La contrasena del dueno del servidor debe tener al menos 16 caracteres ASCII."))
        }
        // Any role may change its own password.
        tenantSchema.adminConnection(role).use { admin ->
            admin.autoCommit = true
            admin.createStatement().use { statement ->
                silenceStatementLog(statement)
                statement.executeUpdate("ALTER ROLE ${quoteIdentifier(role)} PASSWORD ${quoteLiteral(ScramSha256.verifier(password))}")
            }
        }
        central.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL"
            ).use { revoke ->
                revoke.setObject(1, userId)
                revoke.executeUpdate()
            }
            insertAudit(connection, AuditActor.owner(role, userId), "server_owner.password_recovered", null, null, "{}")
        }
    }

    // [actor] null: the business owner recovering their own password (email code).
    private fun changeBusinessOwnerPassword(companyCode: String, password: String, provisioningRole: String, auditAction: String, actor: AuditActor?) {
        val normalizedCompanyCode = companyCode.trim()
        rejectServerWorkspace(normalizedCompanyCode)
        if (password.length < 12) {
            throw CompanyProvisioningException(tr("La contrasena del business owner debe tener al menos 12 caracteres."))
        }
        val company = central.connection.use { connection ->
            findCompanyAnyState(connection, normalizedCompanyCode)
                ?: throw CompanyProvisioningException(tr("Empresa no encontrada."))
        }
        val tenantConfig = loginDatabases.tenantConfig(company.databaseName)
            ?: throw CompanyProvisioningException(tr("La empresa no tiene conexion tenant configurada."))
        val (ownerId, ownerRole) = tenantRuntimeConnection(tenantConfig).use { tenant ->
            tenant.prepareStatement(
                """
                SELECT users.id, users.postgres_role
                FROM business_owner owner
                JOIN application_users users ON users.id = owner.user_id
                LIMIT 1
                """.trimIndent()
            ).use { query ->
                query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) to rows.getString(2) else null }
            }
        } ?: throw CompanyProvisioningException(tr("La empresa no tiene business owner."))

        // Audit row first, in an open central transaction committed after the change: a failed insert stops it.
        central.inCentralTransaction { connection ->
            insertAudit(connection, actor ?: AuditActor.businessOwner(ownerRole), auditAction, normalizedCompanyCode, null, auditDetails("businessOwnerUsername" to ownerRole))
            tenantSchema.adminConnection(provisioningRole).use { admin ->
                admin.autoCommit = true
                admin.createStatement().use { statement ->
                    silenceStatementLog(statement)
                    statement.executeUpdate("ALTER ROLE ${quoteIdentifier(ownerRole)} PASSWORD ${quoteLiteral(ScramSha256.verifier(password))}")
                }
            }
            tenantRuntimeConnection(tenantConfig).use { tenant -> com.ideasdeveloper.bms.server.activity.ActivityLog.atomically(tenant) {
                // A reset from the server is recorded in the company (recovery by email code is recorded by AccountService),
                // in one tenant transaction with the credential and session changes.
                if (actor != null) {
                    com.ideasdeveloper.bms.server.activity.CoreActivity.record(
                        tenant, com.ideasdeveloper.bms.server.activity.ActivityActor.fromAudit(actor, actorName(actor)), "user", ownerId.toString(), ownerRole,
                        com.ideasdeveloper.bms.server.activity.ActivityActions.PasswordChanged, "Puso desde el servidor una contrasena nueva al business owner $ownerRole.",
                        listOf(com.ideasdeveloper.bms.server.activity.ActivityChange.secret("password", "Contrasena")),
                    )
                }
                // An application password would keep the old one working; owners sign in with their role.
                tenant.prepareStatement("DELETE FROM application_user_credentials WHERE user_id = ?").use { delete ->
                    delete.setObject(1, ownerId)
                    delete.executeUpdate()
                }
                tenant.prepareStatement(
                    "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL"
                ).use { revoke ->
                    revoke.setObject(1, ownerId)
                    revoke.executeUpdate()
                }
            } }
        }
    }

    /** A central user (server owner or administrator, also a removed one) already uses [username] as login. */
    internal fun centralLoginTaken(username: String): Boolean = central.connection.use { it.exists("application_users", "postgres_role", username) }

    /**
     * Whether [login] can be the login of a new server administrator: not a PostgreSQL role, not a
     * central user and not a user of any company. Fails closed when a company cannot be checked.
     */
    fun loginNameTaken(login: String): Boolean =
        central.connection.use { it.existsRole(login) } || centralLoginTaken(login) || roleUsedInAnyCompany(login)

    /**
     * Codes of every company whose business owner has the same PostgreSQL role as the one of
     * [companyCode] (duplicated companies share it), [companyCode] included. Changing that owner's
     * user, email or password changes it in all of them.
     */
    fun companiesSharingOwner(companyCode: String): Set<String> {
        val role = ownerOf(companyCode.trim()).role
        val codes = central.connection.use { connection ->
            connection.prepareStatement("SELECT code, database_name FROM companies").use { query ->
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
            }
        }
        // Fails closed: a company whose database cannot be read counts as sharing the owner.
        return codes.filter { (_, databaseName) ->
            runCatching {
                tenantRuntimeConnection(loginDatabases.tenantConfig(databaseName) ?: tenantSchema.tenantConfig(databaseName)).use { connection ->
                    connection.prepareStatement(
                        "SELECT 1 FROM business_owner owner JOIN application_users users ON users.id = owner.user_id WHERE users.postgres_role = ?"
                    ).use { query ->
                        query.setString(1, role)
                        query.executeQuery().use { it.next() }
                    }
                }
            }.getOrElse { failure -> !(failure is java.sql.SQLException && failure.sqlState == "42P01") }
        }.map { it.first }.toSet() + companyCode.trim()
    }

    /** Codes of the companies in the owner group of [companyCode], itself included. */
    fun ownerGroupOf(companyCode: String): Set<String> = loginDatabases.ownerGroupOf(companyCode.trim()) + companyCode.trim()

    /** True si [username] ya es `postgres_role` de algun usuario en cualquier empresa. */
    internal fun roleUsedInAnyCompany(username: String): Boolean =
        companyTenants().any { tenant -> roleUsedInTenant(tenant, username) }

    /**
     * Falla cerrado: solo una tabla inexistente (SQLState 42P01, empresa aun sin esquema) cuenta como
     * "no usado". Cualquier otro error (base inaccesible, conexion, permisos) impide verificar la
     * unicidad y se rechaza la operacion.
     */
    private fun roleUsedInTenant(tenant: TenantDatabaseConfig, username: String): Boolean =
        try {
            tenantRuntimeConnection(tenant).use { it.exists("application_users", "postgres_role", username) }
        } catch (failure: java.sql.SQLException) {
            if (failure.sqlState == "42P01") false
            else {
                log.warn("No se pudo verificar la unicidad del usuario en una empresa", failure)
                throw CompanyProvisioningException(tr("No se pudo verificar la unicidad del usuario"))
            }
        } catch (failure: Exception) {
            log.warn("No se pudo verificar la unicidad del usuario en una empresa", failure)
            throw CompanyProvisioningException(tr("No se pudo verificar la unicidad del usuario"))
        }

    /**
     * Defensa en profundidad: los CREATE/ALTER ROLE ya envian el verificador SCRAM-SHA-256
     * ([ScramSha256.verifier]) y no la contrasena en texto, asi que el texto plano nunca viaja ni se
     * registra. Aun asi se pide a PostgreSQL no registrar estos DDL en el log. Se hace por sesion (la
     * conexion de aprovisionamiento es de un solo uso); si el rol no puede cambiar el parametro solo
     * se avisa en el log, sin consecuencia para la contrasena.
     */
    internal fun silenceStatementLog(statement: java.sql.Statement) {
        try {
            statement.execute("SET log_statement = 'none'")
        } catch (failure: java.sql.SQLException) {
            log.warn("No se pudo desactivar log_statement: las contrasenas del DDL pueden quedar en el log de PostgreSQL", failure)
        }
    }

    private class OwnerRef(val id: UUID, val role: String, val displayName: String?, val email: String?)

    /** User, name and email of the business owner of [companyCode]. */
    fun businessOwner(companyCode: String): BusinessOwnerResponse {
        val owner = ownerOf(companyCode.trim())
        val twoFactor = runCatching {
            loginDatabases.accountSource(companyCode.trim())?.connection?.use { connection ->
                loginDatabases.twoFactor.takeIf { it.available(connection) }?.isEnabled(connection, owner.id)
            }
        }.getOrNull()
        return BusinessOwnerResponse(owner.role, owner.displayName.orEmpty(), owner.email.orEmpty(), twoFactor, owner.id.toString())
    }

    /**
     * New user, name, email and, when given, password of the business owner of [companyCode], by the
     * server owner. The owner's PostgreSQL role is shared by every company it owns (duplicated
     * companies keep it), so the change applies to all of them. Renaming the role clears its password
     * in PostgreSQL, so a new user needs a new password.
     */
    fun updateBusinessOwner(companyCode: String, request: UpdateBusinessOwnerRequest, provisioningRole: String, actor: AuditActor?): BusinessOwnerResponse {
        val code = companyCode.trim()
        val owner = ownerOf(code)
        val username = request.username.trim()
        val displayName = request.displayName.trim()
        val email = request.email.trim()
        val password = request.password
        UserProfileRules.problem(displayName, email)?.let { throw CompanyProvisioningException(tr("Business owner: {0}", it)) }
        if (!postgresRolePattern.matches(username)) {
            throw CompanyProvisioningException(tr("El usuario debe iniciar con una letra minuscula y usar solo letras, numeros o guion bajo."))
        }
        val renamed = username != owner.role
        if (renamed && password.isEmpty()) {
            throw CompanyProvisioningException(tr("Para cambiar el usuario del business owner escribe tambien una contrasena nueva."))
        }
        if (password.isNotEmpty() && password.length < 12) {
            throw CompanyProvisioningException(tr("La contrasena del business owner debe tener al menos 12 caracteres."))
        }
        val tenants = companyTenants()
        val ownerTenants = tenants.filter { tenant -> ownerRoleIn(tenant, owner.role) }
        if (renamed) {
            val taken = tenantSchema.adminConnection(provisioningRole).use { it.existsRole(username) } ||
                centralLoginTaken(username) ||
                tenants.any { tenant -> roleUsedInTenant(tenant, username) }
            if (taken) throw CompanyProvisioningException(tr("Ya existe un usuario con ese nombre."))
        }

        // Audit row first, in an open central transaction committed after the change: a failed insert stops it.
        central.inCentralTransaction { connection ->
            insertAudit(
                connection, actor ?: AuditActor.businessOwner(owner.role), "company.owner_updated", code, null,
                auditDetails(
                    "businessOwnerUsername" to username,
                    "previousUsername" to owner.role,
                    "usernameChanged" to renamed,
                    "emailChanged" to EmailChangeRule.isChange(owner.email, email),
                    "passwordChanged" to password.isNotEmpty(),
                ),
            )
            tenantSchema.adminConnection(provisioningRole).use { admin ->
                admin.autoCommit = true
                admin.createStatement().use { statement ->
                    silenceStatementLog(statement)
                    if (renamed) statement.executeUpdate("ALTER ROLE ${quoteIdentifier(owner.role)} RENAME TO ${quoteIdentifier(username)}")
                    if (password.isNotEmpty()) statement.executeUpdate("ALTER ROLE ${quoteIdentifier(username)} PASSWORD ${quoteLiteral(ScramSha256.verifier(password))}")
                }
            }
            try {
                ownerTenants.forEach { tenant ->
                    tenantRuntimeConnection(tenant).use { connection ->
                      // The change and (from the server) its activity row in one company transaction.
                      connection.autoCommit = false
                      try {
                        val previous = connection.prepareStatement(
                            "SELECT display_name, email FROM application_users WHERE postgres_role = ? AND id IN (SELECT user_id FROM business_owner)"
                        ).use { query ->
                            query.setString(1, owner.role)
                            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null to null }
                        }
                        val ownerIds = connection.prepareStatement(
                            """
                            UPDATE application_users SET postgres_role = ?, display_name = ?, email = ?
                            WHERE postgres_role = ? AND id IN (SELECT user_id FROM business_owner)
                            RETURNING id
                            """.trimIndent()
                        ).use { update ->
                            update.setString(1, username)
                            update.setString(2, displayName)
                            update.setString(3, email)
                            update.setString(4, owner.role)
                            update.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
                        }
                        if (password.isNotEmpty()) {
                            ownerIds.forEach { id ->
                                // Owners sign in with their role; an application password would keep the old one working.
                                connection.prepareStatement("DELETE FROM application_user_credentials WHERE user_id = ?").use { delete ->
                                    delete.setObject(1, id)
                                    delete.executeUpdate()
                                }
                                connection.prepareStatement(
                                    "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL"
                                ).use { revoke ->
                                    revoke.setObject(1, id)
                                    revoke.executeUpdate()
                                }
                            }
                        }
                        // A change made from the server (the business owner's own edit is logged by CompanySelfService).
                        if (actor != null) {
                            val changes = com.ideasdeveloper.bms.server.activity.ActivityChanges.diff(
                                mapOf("username" to owner.role, "displayName" to previous.first, "email" to previous.second),
                                mapOf("username" to username, "displayName" to displayName, "email" to email),
                                mapOf("username" to "Usuario", "displayName" to "Nombre", "email" to "Correo"),
                            ) + listOfNotNull(if (password.isNotEmpty()) com.ideasdeveloper.bms.server.activity.ActivityChange.secret("password", "Contrasena") else null)
                            ownerIds.forEach { id ->
                                com.ideasdeveloper.bms.server.activity.CoreActivity.record(
                                    connection, com.ideasdeveloper.bms.server.activity.ActivityActor.fromAudit(actor, actorName(actor)), "user", id.toString(), username,
                                    com.ideasdeveloper.bms.server.activity.ActivityActions.Updated, "Modifico desde el servidor los datos del business owner $username.", changes,
                                )
                            }
                        }
                        connection.commit()
                      } catch (failure: Throwable) {
                        runCatching { connection.rollback() }
                        throw failure
                      } finally {
                        runCatching { connection.autoCommit = true }
                      }
                    }
                }
            } catch (failure: Exception) {
                // Without its row the owner could not sign in with the new user: give the role its name back.
                if (renamed) {
                    runCatching {
                        tenantSchema.adminConnection(provisioningRole).use { admin ->
                            admin.autoCommit = true
                            admin.createStatement().use { it.executeUpdate("ALTER ROLE ${quoteIdentifier(username)} RENAME TO ${quoteIdentifier(owner.role)}") }
                        }
                    }
                }
                throw failure
            }
        }
        return BusinessOwnerResponse(username, displayName, email)
    }

    /** The business owner edits their own data; runs as the first server owner's role, like password recovery. */
    fun updateBusinessOwnerBySelf(companyCode: String, request: UpdateBusinessOwnerRequest): BusinessOwnerResponse {
        val role = firstServerOwnerRole() ?: throw CompanyProvisioningException(tr("El servidor no tiene un dueno activo."))
        return updateBusinessOwner(companyCode, request, role, null)
    }

    private fun ownerOf(companyCode: String): OwnerRef {
        rejectServerWorkspace(companyCode)
        val company = central.connection.use { connection -> findCompanyAnyState(connection, companyCode) }
            ?: throw CompanyProvisioningException(tr("Empresa no encontrada."))
        val tenant = loginDatabases.tenantConfig(company.databaseName) ?: tenantSchema.tenantConfig(company.databaseName)
        return tenantRuntimeConnection(tenant).use { connection ->
            connection.prepareStatement(
                """
                SELECT users.id, users.postgres_role, users.display_name, users.email
                FROM business_owner owner
                JOIN application_users users ON users.id = owner.user_id
                LIMIT 1
                """.trimIndent()
            ).use { query ->
                query.executeQuery().use { rows ->
                    if (rows.next()) OwnerRef(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getString(4)) else null
                }
            }
        } ?: throw CompanyProvisioningException(tr("La empresa no tiene business owner."))
    }

    // Tenant databases of every company, active or not, and of the server workspace.
    private fun companyTenants(): List<TenantDatabaseConfig> = central.connection.use { connection ->
        connection.prepareStatement("SELECT database_name FROM companies").use { query ->
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val name = rows.getString(1)
                        add(loginDatabases.tenantConfig(name) ?: tenantSchema.tenantConfig(name))
                    }
                }
            }
        }
    }

    // Whether [role] is the business owner of [tenant]; a database that cannot be opened counts as no.
    private fun ownerRoleIn(tenant: TenantDatabaseConfig, role: String): Boolean = runCatching {
        tenantRuntimeConnection(tenant).use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM business_owner owner JOIN application_users users ON users.id = owner.user_id WHERE users.postgres_role = ?"
            ).use { query ->
                query.setString(1, role)
                query.executeQuery().use { it.next() }
            }
        }
    }.getOrDefault(false)

    // Postgres role of an active server owner, the role their changes run as; null when not one.
    fun serverOwnerRole(userId: UUID): String? = central.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT users.postgres_role
            FROM server_owners owners
            JOIN application_users users ON users.id = owners.user_id
            WHERE owners.user_id = ? AND users.is_active
            """.trimIndent()
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    private fun insertAudit(
        connection: Connection,
        actor: AuditActor,
        action: String,
        companyCode: String?,
        moduleId: String?,
        details: String,
    ) = ProvisioningAudit.insert(connection, actor, action, companyCode, moduleId, details)
}
