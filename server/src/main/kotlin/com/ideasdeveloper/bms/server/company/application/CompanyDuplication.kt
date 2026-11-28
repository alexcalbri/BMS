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

internal class CompanyDuplication(
    private val central: DataSource,
    private val config: CompanyProvisioningConfig,
    private val loginDatabases: LoginDatabases,
    private val moduleRegistry: ModuleRegistry,
    private val tenantSchema: TenantSchemaProvisioner,
    private val owners: BusinessOwnerAccounts,
    private val companyCodePattern: Regex,
    private val rejectServerWorkspace: (String) -> Unit,
    private val findCompany: (Connection, String) -> CompanyRow?,
    private val tenantRuntimeConnection: (TenantDatabaseConfig) -> Connection,
    private val validate: (String, String, String, String, CreateCompanyRequest) -> Unit,
    private val insertCompany: (Connection, UUID, CreateCompanyRequest, String, UUID) -> Unit,
    private val loadCompanyModules: (String) -> List<CompanyModuleResponse>,
    private val setModuleEnabled: (String, String, Boolean, String, AuditActor) -> CompanySummaryResponse,
) {
    private val log = LoggerFactory.getLogger(CompanyProvisioningService::class.java)

    /** See [CompanyProvisioningService.duplicateCompany]. */
    fun duplicateCompany(sourceCode: String, newCode: String, provisioningRole: String, actor: AuditActor, newOwner: DuplicateOwnerRequest?): CreateCompanyResponse {
        val code = newCode.trim()
        val source = sourceCode.trim()
        rejectServerWorkspace(source)
        if (code == ServerWorkspace.Code) throw CompanyProvisioningException(tr("El codigo {0} esta reservado para el espacio del servidor.", ServerWorkspace.Code))
        if (!companyCodePattern.matches(code)) {
            throw CompanyProvisioningException(tr("El codigo debe iniciar con una letra minuscula y usar solo letras minusculas, numeros o guion bajo (hasta 63)."))
        }
        val databaseName = "bms_$code"
        val (sourceCompany, ownerGroup) = central.connection.use { connection ->
            if (connection.exists("companies", "code", code)) throw CompanyProvisioningException(tr("Ya existe una empresa con ese codigo."))
            if (connection.exists("companies", "database_name", databaseName)) throw CompanyProvisioningException(tr("Ya existe una base de datos registrada para ese codigo."))
            val row = findCompany(connection, source) ?: throw CompanyProvisioningException(tr("La empresa a duplicar no existe o esta desactivada."))
            row to ownerGroup(connection, source)
        }
        val sourceTenant = loginDatabases.tenantConfig(sourceCompany.databaseName)
            ?: throw CompanyProvisioningException(tr("La empresa a duplicar no tiene conexion tenant configurada."))
        val newOwnerInput = newOwner?.let { input ->
            val username = input.username.trim()
            validate(code, sourceCompany.name, username, input.password, CreateCompanyRequest(code, sourceCompany.name, username, input.password))
            UserProfileRules.problem(input.name.trim(), input.email.trim())?.let { throw CompanyProvisioningException(tr("Business owner: {0}", it)) }
            input.copy(username = username, name = input.name.trim(), email = input.email.trim())
        }

        // The owner of the copy is the same person: same PostgreSQL role, name, email and password.
        data class Owner(val role: String, val name: String?, val email: String?, val credentials: List<Any?>?)
        val (sourceOwner, enabledModules) = tenantRuntimeConnection(sourceTenant).use { tenant ->
            val owner = tenant.prepareStatement(
                """
                SELECT users.id, users.postgres_role, users.display_name, users.email
                FROM business_owner owner JOIN application_users users ON users.id = owner.user_id
                LIMIT 1
                """.trimIndent()
            ).use { query ->
                query.executeQuery().use { rows ->
                    if (!rows.next()) throw CompanyProvisioningException(tr("La empresa a duplicar no tiene business owner."))
                    val userId = rows.getObject(1, UUID::class.java)
                    val credentials = tenant.prepareStatement(
                        "SELECT password_salt, password_hash, iterations FROM application_user_credentials WHERE user_id = ?"
                    ).use { credentialQuery ->
                        credentialQuery.setObject(1, userId)
                        credentialQuery.executeQuery().use { found -> if (found.next()) listOf(found.getString(1), found.getString(2), found.getInt(3)) else null }
                    }
                    Owner(rows.getString(2), rows.getString(3), rows.getString(4), credentials)
                }
            }
            val modules = tenant.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled'").use { query ->
                query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
            owner to modules
        }

        val owner = newOwnerInput?.let { Owner(it.username, it.name, it.email, null) } ?: sourceOwner
        val tenantConfig = tenantSchema.tenantConfig(databaseName)
        var registered = false
        var roleCreated = false
        try {
            tenantSchema.adminConnection(provisioningRole).use { admin ->
                admin.autoCommit = true
                if (admin.existsDatabase(databaseName)) throw CompanyProvisioningException(tr("La base de datos de la empresa ya existe."))
                if (newOwnerInput != null) {
                    if (admin.existsRole(owner.role) || owners.roleUsedInAnyCompany(owner.role) || owners.centralLoginTaken(owner.role)) {
                        throw CompanyProvisioningException(tr("El usuario PostgreSQL del business owner ya existe."))
                    }
                } else if (!admin.existsRole(owner.role)) {
                    throw CompanyProvisioningException(tr("No existe el usuario PostgreSQL del business owner."))
                }
                admin.createStatement().use { statement ->
                    owners.silenceStatementLog(statement)
                    statement.executeUpdate("CREATE DATABASE ${quoteIdentifier(databaseName)}")
                    // Por defecto PUBLIC puede conectarse a cualquier base: solo el runtime y el owner.
                    statement.executeUpdate("REVOKE CONNECT ON DATABASE ${quoteIdentifier(databaseName)} FROM PUBLIC")
                    if (newOwnerInput != null) {
                        statement.executeUpdate(
                            "CREATE ROLE ${quoteIdentifier(owner.role)} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD ${quoteLiteral(ScramSha256.verifier(newOwnerInput.password))}"
                        )
                        roleCreated = true
                    }
                    statement.executeUpdate("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO ${quoteIdentifier(config.runtimeUser)}")
                    statement.executeUpdate("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO ${quoteIdentifier(owner.role)}")
                    tenantSchema.grantProvisioningConnect(statement, databaseName)
                }
            }
            tenantSchema.tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { tenant ->
                tenantSchema.applyTenantCoreMigrations(tenant)
                tenantSchema.applyBaseModuleMigrations(tenant)
                tenantSchema.seedBusinessOwner(tenant, owner.role, owner.name.orEmpty(), owner.email.orEmpty())
                owner.credentials?.let { (salt, hash, iterations) ->
                    tenant.prepareStatement(
                        """
                        INSERT INTO application_user_credentials (user_id, password_salt, password_hash, iterations)
                        SELECT user_id, ?, ?, ? FROM business_owner
                        """.trimIndent()
                    ).use { insert ->
                        insert.setString(1, salt as String)
                        insert.setString(2, hash as String)
                        insert.setInt(3, iterations as Int)
                        insert.executeUpdate()
                    }
                }
                tenantSchema.grantTenantPermissions(tenant, owner.role)
            }
            val request = CreateCompanyRequest(
                code = code,
                name = sourceCompany.name,
                businessOwnerUsername = owner.role,
                businessOwnerPassword = "",
                businessOwnerName = owner.name.orEmpty(),
                businessOwnerEmail = owner.email.orEmpty(),
                logoUrl = sourceCompany.logoUrl,
                primaryColor = sourceCompany.primaryColor,
                secondaryColor = sourceCompany.secondaryColor,
                accentColor = sourceCompany.accentColor,
            )
            central.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val companyId = UUID.randomUUID()
                    // Same owner: same group. A new owner starts its own group.
                    insertCompany(connection, companyId, request, databaseName, if (newOwnerInput == null) ownerGroup else companyId)
                    insertAudit(
                        connection, actor, "company.duplicated", code, null,
                        auditDetails("source" to source, "databaseName" to databaseName, "businessOwnerUsername" to owner.role, "newBusinessOwner" to (newOwnerInput != null)),
                    )
                    connection.commit()
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                }
            }
            registered = true
        } catch (failure: Exception) {
            // Nothing points to a half-built database yet: remove it so the code can be used again.
            if (!registered) {
                runCatching {
                    tenantSchema.adminConnection(provisioningRole).use { admin ->
                        admin.autoCommit = true
                        admin.createStatement().use { statement ->
                            statement.executeUpdate("DROP DATABASE IF EXISTS ${quoteIdentifier(databaseName)}")
                            if (roleCreated) statement.executeUpdate("DROP ROLE IF EXISTS ${quoteIdentifier(owner.role)}")
                        }
                    }
                }.onFailure { log.warn("No se pudo borrar la base incompleta {}", databaseName, it) }
            }
            throw failure
        }
        loginDatabases.registerTenant(tenantConfig)

        // Same optional modules, then the settings of every enabled module (base ones included).
        val copied = moduleRegistry.definitions.filter { it.id in enabledModules || it.locked }
        copied.filterNot { it.locked }.forEach { definition -> setModuleEnabled(code, definition.id, true, provisioningRole, actor) }
        val failures = mutableListOf<String>()
        tenantRuntimeConnection(sourceTenant).use { from ->
            tenantSchema.tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { to ->
                copied.forEach { definition ->
                    val tables = moduleRegistry.module(definition.id)?.duplicatedSettings.orEmpty()
                    if (tables.isEmpty()) return@forEach
                    runCatching { SettingsCopy.copy(from, to, tables) }.onFailure { failure ->
                        log.warn("No se copio la configuracion de {} de {} a {}", definition.id, source, code, failure)
                        failures += definition.displayName
                    }
                }
            }
        }
        if (failures.isNotEmpty()) {
            central.connection.use { connection ->
                insertAudit(connection, actor, "company.duplicate_settings_failed", code, null, auditDetails("modules" to failures.joinToString()))
            }
        }
        return CreateCompanyResponse(
            id = code,
            code = code,
            name = sourceCompany.name,
            databaseName = databaseName,
            businessOwnerUsername = owner.role,
            enabledModules = loadCompanyModules(databaseName).filter { it.enabled }.map { it.moduleId },
            settingsNotCopied = failures.map { tr(it) },
        )
    }

    private fun ownerGroup(connection: Connection, companyCode: String): UUID =
        connection.prepareStatement("SELECT owner_group FROM companies WHERE code = ?").use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else throw CompanyProvisioningException(tr("Empresa no encontrada.")) }
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
