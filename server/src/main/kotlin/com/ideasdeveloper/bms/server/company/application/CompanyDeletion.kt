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

internal class CompanyDeletion(
    private val central: DataSource,
    private val config: CompanyProvisioningConfig,
    private val loginDatabases: LoginDatabases,
    private val tenantSchema: TenantSchemaProvisioner,
    private val registry: CompanyRegistryQueries,
    private val companyFiles: CompanyFiles?,
    private val activityIntegrity: com.ideasdeveloper.bms.server.activity.ActivityIntegrityService?,
    private val rejectServerWorkspace: (String) -> Unit,
) {
    private val log = LoggerFactory.getLogger(CompanyProvisioningService::class.java)

    // The role also owns another company when it was granted CONNECT on another database (duplicates).
    private fun ownerRoleUsedElsewhere(admin: Connection, role: String, databaseName: String): Boolean =
        admin.prepareStatement(
            """
            SELECT 1 FROM pg_database database, aclexplode(database.datacl) acl
            WHERE database.datname <> ? AND acl.grantee = (SELECT oid FROM pg_roles WHERE rolname = ?)
            LIMIT 1
            """.trimIndent()
        ).use { query ->
            query.setString(1, databaseName)
            query.setString(2, role)
            query.executeQuery().use { it.next() }
        }

    fun deleteInactiveCompany(companyCode: String, provisioningRole: String, actor: AuditActor) {
        val normalizedCompanyCode = companyCode.trim()
        rejectServerWorkspace(normalizedCompanyCode)
        val company = central.connection.use { connection ->
            registry.findCompanyAnyState(connection, normalizedCompanyCode)
                ?: throw CompanyProvisioningException(tr("Empresa no encontrada."))
        }
        if (company.isActive) {
            throw CompanyProvisioningException(tr("Primero debes desactivar la empresa antes de eliminarla."))
        }

        val tenantConfig = loginDatabases.tenantConfig(company.databaseName)
        var ownerRole: String? = null
        var tenantDatabaseDropped = false
        var ownerRoleDropped = false
        // The log goes with the database (it is not archived): its final anchor and a summary stay in the server audit.
        val finalLog = activityIntegrity?.finalAnchor(normalizedCompanyCode)

        tenantSchema.adminConnection(provisioningRole).use { admin ->
            admin.autoCommit = true
            val tenantDatabaseExists = admin.existsDatabase(company.databaseName)
            if (tenantDatabaseExists && tenantConfig != null) {
                ownerRole = registry.tenantRuntimeConnection(tenantConfig).use { tenant ->
                    tenant.prepareStatement(
                        """
                        SELECT users.postgres_role
                        FROM business_owner owner
                        JOIN application_users users ON users.id = owner.user_id
                        LIMIT 1
                        """.trimIndent()
                    ).use { query ->
                        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                    }
                }
            }

            loginDatabases.unregisterTenant(company.databaseName)

            admin.createStatement().use { statement ->
                if (tenantDatabaseExists) {
                    statement.executeUpdate("REVOKE CONNECT ON DATABASE ${quoteIdentifier(company.databaseName)} FROM PUBLIC")
                    dropDatabaseForced(statement, company.databaseName, config.runtimeUser)
                    tenantDatabaseDropped = true
                }
                // Without its database nobody can reach these files any more.
                runCatching { companyFiles?.deleteCompany(company.code) }
                    .onFailure { log.warn("No se pudieron borrar los archivos de la empresa {}", company.code, it) }
                val ownerRoleToDrop = ownerRole
                if (!ownerRoleToDrop.isNullOrBlank() && admin.existsRole(ownerRoleToDrop) && !ownerRoleUsedElsewhere(admin, ownerRoleToDrop, company.databaseName)) {
                    statement.executeUpdate("DROP ROLE IF EXISTS ${quoteIdentifier(ownerRoleToDrop)}")
                    ownerRoleDropped = true
                }
            }
        }

        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                insertAudit(
                    connection = connection,
                    actor = actor,
                    action = "company.deleted",
                    companyCode = normalizedCompanyCode,
                    moduleId = null,
                    details = auditDetails(
                        "databaseName" to company.databaseName, "ownerRole" to ownerRole.orEmpty(), "tenantDatabaseDropped" to tenantDatabaseDropped,
                        "ownerRoleDropped" to ownerRoleDropped, "activityRows" to finalLog?.rows, "activityLastId" to finalLog?.lastId,
                        "activityLastHash" to finalLog?.lastHash, "activityIntact" to finalLog?.ok,
                    ),
                )
                connection.prepareStatement("DELETE FROM companies WHERE code = ? AND is_active = FALSE").use { delete ->
                    delete.setString(1, normalizedCompanyCode)
                    if (delete.executeUpdate() != 1) {
                        throw CompanyProvisioningException(tr("No se pudo eliminar la empresa desactivada."))
                    }
                }
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
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
