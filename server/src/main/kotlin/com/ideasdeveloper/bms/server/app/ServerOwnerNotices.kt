package com.ideasdeveloper.bms.server.app

import com.ideasdeveloper.bms.server.admins.ServerAudit
import com.ideasdeveloper.bms.server.activity.companyActivityRoutes
import com.ideasdeveloper.bms.server.assistant.assistantRoutes
import com.ideasdeveloper.bms.server.assistant.assistantServerRoutes
import com.ideasdeveloper.bms.server.admins.serverAdminRoutes
import com.ideasdeveloper.bms.sayHello
import com.ideasdeveloper.bms.server.auth.api.authRoutes
import com.ideasdeveloper.bms.server.auth.application.SessionService
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationSessionRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationUserRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.auth.api.accountProfileRoutes
import com.ideasdeveloper.bms.server.auth.api.passwordResetRoutes
import com.ideasdeveloper.bms.server.auth.api.twoFactorPolicyRoutes
import com.ideasdeveloper.bms.server.auth.api.twoFactorRoutes
import com.ideasdeveloper.bms.server.auth.api.twoFactorResetRoutes
import com.ideasdeveloper.bms.server.auth.api.twoFactorRecoveryLinkRoutes
import com.ideasdeveloper.bms.server.auth.application.AccountException
import com.ideasdeveloper.bms.server.auth.application.AccountPasswords
import com.ideasdeveloper.bms.server.auth.application.AccountService
import com.ideasdeveloper.bms.server.company.api.companyRoutes
import com.ideasdeveloper.bms.server.company.api.StorageAdmin
import com.ideasdeveloper.bms.server.company.api.storageRoutes
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningException
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningConfig
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.company.application.CompanySelfService
import com.ideasdeveloper.bms.server.platform.coreupdate.CoreUpdateService
import com.ideasdeveloper.bms.server.platform.coreupdate.JdbcCoreUpdateStore
import com.ideasdeveloper.bms.server.platform.mcp.ServerMcpTools
import com.ideasdeveloper.bms.server.admins.ServerOwnerAuthorizer
import com.ideasdeveloper.bms.server.infrastructure.database.DatabaseFactory
import com.ideasdeveloper.bms.server.http.ClientAddresses
import com.ideasdeveloper.bms.server.http.PublicOrigin
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.PackageInstallQueue
import com.ideasdeveloper.bms.server.modules.CompanyFiles
import com.ideasdeveloper.bms.server.modules.ModuleDefinition
import com.ideasdeveloper.bms.server.modules.CustomerReferenceSource
import com.ideasdeveloper.bms.server.modules.ModuleJobScheduler
import com.ideasdeveloper.bms.server.modules.JdbcModuleUnsignedAuthorizationStore
import com.ideasdeveloper.bms.server.modules.ModulePackageBuilder
import com.ideasdeveloper.bms.server.modules.ModulePackageLoader
import com.ideasdeveloper.bms.server.modules.ModuleRequirements
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.bms.server.modules.ServerMcpModuleId
import com.ideasdeveloper.bms.server.modules.ServerModule
import com.ideasdeveloper.bms.server.modules.ServerModuleProvider
import com.ideasdeveloper.bms.server.modules.SmtpSettings
import com.ideasdeveloper.bms.server.modules.moduleRoutes
import com.ideasdeveloper.bms.server.extensions.ExtensionActors
import com.ideasdeveloper.bms.server.extensions.ExtensionActorLookup
import com.ideasdeveloper.bms.server.extensions.ExtensionMcpTools
import com.ideasdeveloper.bms.server.extensions.packages.ExtensionManifestValidator
import com.ideasdeveloper.bms.server.extensions.packages.ExtensionPackageBuilder
import com.ideasdeveloper.bms.server.extensions.packages.ExtensionPackageService
import com.ideasdeveloper.bms.server.extensions.packages.ExtensionSignatureVerifier
import com.ideasdeveloper.bms.server.extensions.packages.JdbcExtensionPackageStore
import com.ideasdeveloper.bms.server.extensions.packages.JdbcUnsignedAuthorizationStore
import com.ideasdeveloper.bms.server.extensions.packages.RemoteExtensionCatalog
import com.ideasdeveloper.bms.server.extensions.packages.extensionPackageRoutes
import com.ideasdeveloper.bms.server.extensions.ExtensionConnections
import com.ideasdeveloper.bms.server.extensions.ExtensionNotifier
import com.ideasdeveloper.bms.server.extensions.ExtensionRegistry
import com.ideasdeveloper.bms.server.extensions.ExtensionService
import com.ideasdeveloper.bms.server.extensions.ExtensionStorage
import com.ideasdeveloper.bms.server.extensions.extensionRoutes
import com.ideasdeveloper.bms.server.security.MasterKeySecretBox
import com.ideasdeveloper.bms.server.security.SecretBox
import com.ideasdeveloper.bms.server.modules.notificationRoutes
import com.ideasdeveloper.bms.server.i18n.installI18n
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import kotlinx.coroutines.sync.withLock
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.ServiceLoader
import javax.sql.DataSource
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/** Notifications to the server owners. */
/**
 * A notification to every active server owner, in the server workspace (where their bell reads them); nothing when the
 * workspace does not exist yet. Used for integrity failures of the activity logs.
 */
internal fun notifyServerOwners(central: DataSource, access: ModuleAccessService, title: String, text: String) {
    val (workspace, owners) = central.connection.use { connection ->
        val database = connection.prepareStatement("SELECT database_name FROM companies WHERE is_server_workspace").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        val ids = connection.prepareStatement(
            "SELECT owners.user_id FROM server_owners owners JOIN application_users users ON users.id = owners.user_id WHERE users.is_active"
        ).use { query -> query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, java.util.UUID::class.java)) } } }
        database to ids
    }
    if (workspace == null || owners.isEmpty()) return
    access.tenantConnectionForDatabase(workspace)?.use { tenant ->
        owners.forEach { owner ->
            access.notifications.notify(tenant, com.ideasdeveloper.bms.server.modules.NewNotification(owner, com.ideasdeveloper.bms.server.activity.CoreModuleId, title, text))
        }
    }
}
