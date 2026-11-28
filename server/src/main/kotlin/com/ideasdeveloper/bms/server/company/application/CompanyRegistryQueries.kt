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

internal data class CompanyRow(
    val code: String,
    val name: String,
    val logoUrl: String?,
    val primaryColor: String,
    val secondaryColor: String,
    val accentColor: String,
    val databaseName: String,
    val isActive: Boolean,
)

internal class CompanyRegistryQueries(
    private val central: DataSource,
    private val loginDatabases: LoginDatabases,
    private val moduleRegistry: ModuleRegistry,
    private val tenantSchema: TenantSchemaProvisioner,
) {
    fun loadCompanyModules(databaseName: String): List<CompanyModuleResponse> {
        val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return moduleRegistry.definitions.map { definition ->
            definition.toCompanyModuleResponse(enabled = definition.locked)
        }

        val states = mutableMapOf<String, Boolean>()
        tenantRuntimeConnection(tenantConfig).use { connection ->
            connection.prepareStatement("SELECT module_id, status FROM tenant_modules").use { query ->
                query.executeQuery().use { rows ->
                    while (rows.next()) {
                        states[rows.getString("module_id")] = rows.getString("status") == "enabled"
                    }
                }
            }
        }
        return moduleRegistry.definitions.map { definition ->
            definition.toCompanyModuleResponse(enabled = if (definition.locked) true else states[definition.id] == true)
        }
    }

    fun countCompaniesWithModuleEnabled(moduleId: String): Int {
        val companies = central.connection.use { connection ->
            connection.prepareStatement("SELECT database_name FROM companies ORDER BY code").use { query ->
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getString("database_name"))
                        }
                    }
                }
            }
        }
        return companies.count { databaseName ->
            val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: tenantSchema.tenantConfig(databaseName)
            tenantRuntimeConnection(tenantConfig).use { connection ->
                connection.prepareStatement(
                    "SELECT 1 FROM tenant_modules WHERE module_id = ? AND status = 'enabled'"
                ).use { query ->
                    query.setString(1, moduleId)
                    query.executeQuery().use { rows -> rows.next() }
                }
            }
        }
    }

    fun tenantRuntimeConnection(tenant: TenantDatabaseConfig): Connection {
        val source = PGSimpleDataSource().apply {
            setURL(tenant.jdbcUrl)
            setUser(tenant.user)
            setPassword(tenant.password)
        }
        return source.connection
    }

    fun insertCompany(connection: Connection, companyId: UUID, request: CreateCompanyRequest, databaseName: String, ownerGroup: UUID = companyId) {
        connection.prepareStatement(
            """
            INSERT INTO companies (
                id, code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active, owner_group
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, companyId)
            insert.setString(2, request.code.trim())
            insert.setString(3, request.name.trim())
            insert.setString(4, request.logoUrl?.trim()?.takeIf { it.isNotBlank() })
            insert.setString(5, request.primaryColor)
            insert.setString(6, request.secondaryColor)
            insert.setString(7, request.accentColor)
            insert.setString(8, databaseName)
            insert.setObject(9, ownerGroup)
            insert.executeUpdate()
        }
    }

    fun findCompany(connection: Connection, companyCode: String): CompanyRow? {
        connection.prepareStatement(
            "SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active FROM companies WHERE code = ? AND is_active = TRUE"
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows ->
                return if (rows.next()) {
                    CompanyRow(
                        code = rows.getString("code"),
                        name = rows.getString("name"),
                        logoUrl = rows.getString("logo_url"),
                        primaryColor = rows.getString("primary_color"),
                        secondaryColor = rows.getString("secondary_color"),
                        accentColor = rows.getString("accent_color"),
                        databaseName = rows.getString("database_name"),
                        isActive = rows.getBoolean("is_active"),
                    )
                } else {
                    null
                }
            }
        }
    }


    fun findCompanyAnyState(connection: Connection, companyCode: String): CompanyRow? {
        connection.prepareStatement(
            "SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active FROM companies WHERE code = ?"
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows ->
                return if (rows.next()) {
                    CompanyRow(
                        code = rows.getString("code"),
                        name = rows.getString("name"),
                        logoUrl = rows.getString("logo_url"),
                        primaryColor = rows.getString("primary_color"),
                        secondaryColor = rows.getString("secondary_color"),
                        accentColor = rows.getString("accent_color"),
                        databaseName = rows.getString("database_name"),
                        isActive = rows.getBoolean("is_active"),
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun ModuleDefinition.toCompanyModuleResponse(enabled: Boolean): CompanyModuleResponse =
        CompanyModuleResponse(
            moduleId = id,
            displayName = trIn(I18n.moduleNamespace(id), displayName),
            enabled = enabled,
            locked = locked,
        )
}
