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

internal class CompanyActivityRecorder(
    private val central: DataSource,
    private val config: CompanyProvisioningConfig,
    private val loginDatabases: LoginDatabases,
    private val registry: CompanyRegistryQueries,
) {
    private val log = LoggerFactory.getLogger(CompanyProvisioningService::class.java)

    /**
     * Runs [change] (a change in the central database, committed inside it) with the company's activity row for it
     * written first on an open transaction of the company database, committed only once [change] returned: a failed
     * row stops the change, a failed change leaves no row. Only a failed tenant commit after the central one can
     * leave the change without its row; that is logged and audited as activity.row_not_written.
     */
    fun <T> withCompanyActivity(
        databaseName: String, actor: AuditActor, entityType: String, entityId: String?, label: String, action: String, summary: String,
        changes: List<com.ideasdeveloper.bms.server.activity.ActivityChange> = emptyList(), change: () -> T,
    ): T {
        val tenantConfig = loginDatabases.tenantConfig(databaseName)
            ?: throw CompanyProvisioningException(tr("La empresa no tiene conexion tenant configurada."))
        return registry.tenantRuntimeConnection(tenantConfig).use { tenant ->
            tenant.autoCommit = false
            val result = try {
                com.ideasdeveloper.bms.server.activity.CoreActivity.record(
                    tenant, com.ideasdeveloper.bms.server.activity.ActivityActor.fromAudit(actor, actorName(actor)), entityType, entityId, label, action, summary, changes,
                )
                change()
            } catch (failure: Throwable) {
                runCatching { tenant.rollback() }
                throw failure
            }
            try {
                tenant.commit()
            } catch (failure: Exception) {
                log.error("El cambio {} de {} se confirmo pero su fila del registro de actividad no", action, databaseName, failure)
                runCatching {
                    central.connection.use { insertAudit(it, actor, "activity.row_not_written", null, null, auditDetails("databaseName" to databaseName, "action" to action, "summary" to summary)) }
                }
            }
            result
        }
    }

    /**
     * The company's activity row for a change made from the server by [actor], on [tenant] (the change's own
     * connection, in its transaction). A provisioning connection runs as the server owner's role, which may hold no
     * grant on a log another role owns (an older update.sh applied V011 as the superuser): the row is written as the
     * runtime role, which has them (the provisioning login may SET ROLE to it), and the role is set back.
     */
    fun recordOnTenant(
        tenant: Connection, actor: AuditActor, entityType: String, entityId: String?, label: String, action: String, summary: String,
        changes: List<com.ideasdeveloper.bms.server.activity.ActivityChange> = emptyList(),
    ) {
        check(!tenant.autoCommit) { "recordOnTenant needs the change's open transaction" }
        val current = tenant.createStatement().use { statement ->
            statement.executeQuery("SELECT current_user, session_user").use { rows -> rows.next(); rows.getString(1) to rows.getString(2) }
        }
        val switched = current.first != config.runtimeUser
        if (switched) tenant.createStatement().use { it.execute("SET LOCAL ROLE ${quoteIdentifier(config.runtimeUser)}") }
        // A failure aborts the transaction, whose rollback also undoes the SET LOCAL.
        com.ideasdeveloper.bms.server.activity.CoreActivity.record(
            tenant, com.ideasdeveloper.bms.server.activity.ActivityActor.fromAudit(actor, actorName(actor)), entityType, entityId, label, action, summary, changes,
        )
        if (switched) tenant.createStatement().use { it.execute("SET LOCAL ROLE ${quoteIdentifier(current.first)}") }
    }

    // Display name of a server person for the activity log (their login when unknown).
    fun actorName(actor: AuditActor): String? = actor.userId?.let { id -> loginDatabases.serverPerson(id)?.name }

    fun previousLogo(connection: Connection, code: String, fallback: String?): String? =
        connection.prepareStatement("SELECT logo_url FROM companies WHERE code = ?").use { query ->
            query.setString(1, code)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else fallback }
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
