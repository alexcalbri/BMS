package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.install.ServerModuleInstaller
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.auth.application.EmailChangeRule
import com.ideasdeveloper.bms.server.modules.PackageInstallQueue
import com.ideasdeveloper.bms.server.storage.CompanyFiles
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

internal class ServerWorkspaceProvisioner(
    private val central: DataSource,
    private val config: CompanyProvisioningConfig,
    private val loginDatabases: LoginDatabases,
    private val tenantSchema: TenantSchemaProvisioner,
    private val tenantRuntimeConnection: (TenantDatabaseConfig) -> Connection,
) {
    private val log = LoggerFactory.getLogger(CompanyProvisioningService::class.java)

    // Code of the server workspace, creating it with its own tenant database on first use.
    fun ensureServerWorkspace(provisioningRole: String, actor: AuditActor): String {
        serverWorkspaceRow()?.let { return it.code }
        val code = ServerWorkspace.Code
        val databaseName = ServerWorkspace.DatabaseName
        central.connection.use { connection ->
            if (connection.exists("companies", "code", code)) {
                throw CompanyProvisioningException(tr("Ya existe una empresa con el codigo {0}; cambia su codigo para crear el espacio del servidor.", code))
            }
        }
        tenantSchema.adminConnection(provisioningRole).use { admin ->
            admin.autoCommit = true
            // A database left by an interrupted attempt is reused; its migrations are idempotent.
            if (!admin.existsDatabase(databaseName)) {
                admin.createStatement().use { statement ->
                    statement.executeUpdate("CREATE DATABASE ${quoteIdentifier(databaseName)}")
                    statement.executeUpdate("REVOKE CONNECT ON DATABASE ${quoteIdentifier(databaseName)} FROM PUBLIC")
                    statement.executeUpdate("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO ${quoteIdentifier(config.runtimeUser)}")
                    tenantSchema.grantProvisioningConnect(statement, databaseName)
                }
            }
        }
        val tenantConfig = tenantSchema.tenantConfig(databaseName)
        tenantSchema.tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { tenant ->
            tenantSchema.applyTenantCoreMigrations(tenant)
            tenantSchema.applyBaseModuleMigrations(tenant)
            tenantSchema.grantTenantPermissions(tenant, ownerUsername = null)
        }
        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO companies (
                        id, code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active, is_server_workspace
                    ) VALUES (?, ?, ?, NULL, '#667EEA', '#764BA2', '#FFFFFF', ?, TRUE, TRUE)
                    """.trimIndent()
                ).use { insert ->
                    insert.setObject(1, UUID.randomUUID())
                    insert.setString(2, code)
                    insert.setString(3, ServerWorkspace.Name)
                    insert.setString(4, databaseName)
                    insert.executeUpdate()
                }
                insertAudit(connection, actor, "server_workspace.created", code, null, auditDetails("databaseName" to databaseName))
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
        loginDatabases.registerTenant(tenantConfig)
        log.info("Server workspace created in database {}", databaseName)
        return code
    }

    fun serverWorkspaceRow(): CompanyRow? = try {
        central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active FROM companies WHERE is_server_workspace"
            ).use { query ->
                query.executeQuery().use { rows ->
                    if (!rows.next()) null else CompanyRow(
                        code = rows.getString("code"),
                        name = rows.getString("name"),
                        logoUrl = rows.getString("logo_url"),
                        primaryColor = rows.getString("primary_color"),
                        secondaryColor = rows.getString("secondary_color"),
                        accentColor = rows.getString("accent_color"),
                        databaseName = rows.getString("database_name"),
                        isActive = rows.getBoolean("is_active"),
                    )
                }
            }
        }
    } catch (_: java.sql.SQLException) {
        // Before control migration V006 there is no workspace column.
        null
    }

    // Modules enabled in the server workspace, or null when it does not exist yet.
    fun serverWorkspaceModules(): Set<String>? {
        val workspace = serverWorkspaceRow() ?: return null
        val tenant = loginDatabases.tenantConfig(workspace.databaseName) ?: return emptySet()
        return tenantRuntimeConnection(tenant).use { connection ->
            connection.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled'").use { query ->
                query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
        }
    }

    fun rejectServerWorkspace(companyCode: String) {
        if (companyCode == ServerWorkspace.Code && serverWorkspaceRow()?.code == companyCode) {
            throw CompanyProvisioningException(tr("El espacio del servidor no se administra como una empresa."))
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
