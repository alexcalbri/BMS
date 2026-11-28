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
import com.ideasdeveloper.bms.server.company.application.ServerOwnerAuthorizer
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

/** State of GET /version: the cached comparison of installed and latest versions. */
// Valor en memoria con expiracion: evita recalcular (y repetir peticiones salientes) en cada llamada.
// Un fallo tambien se guarda, con un TTL corto, para no reintentar la carga en cada peticion.
internal class ExpiringCache<T : Any>(private val ttlMillis: Long, private val failureTtlMillis: Long) {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var value: T? = null
    private var failure: Throwable? = null
    private var expiresAt = 0L

    suspend fun get(load: () -> T): T = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now < expiresAt) {
            value?.let { return@withLock it }
            failure?.let { throw it }
        }
        try {
            // La carga hace HTTP bloqueante: fuera de los hilos del dispatcher de peticiones.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load() }.also {
                value = it
                failure = null
                expiresAt = now + ttlMillis
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            value = null
            failure = error
            expiresAt = now + failureTtlMillis
            throw error
        }
    }
}

internal suspend fun <T> withContextIO(block: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

internal val versionCache = ExpiringCache<VersionResponse>(ttlMillis = 5 * 60_000L, failureTtlMillis = 45_000L)

internal fun versionResponse(
    catalogUrl: String,
    central: DataSource,
    remoteModuleCatalog: RemoteModuleCatalog,
    defaultModuleCatalogUrl: String,
): VersionResponse {
    val remote = VersionCatalogClient.fetch(catalogUrl)
    return VersionResponse(
        latestCoreVersion = remote?.latestCoreVersion,
        latestAppVersion = remote?.latestAppVersion,
        moduleUpdates = moduleUpdates(central, remoteModuleCatalog, defaultModuleCatalogUrl),
    )
}

private fun moduleUpdates(
    central: DataSource,
    remoteModuleCatalog: RemoteModuleCatalog,
    defaultModuleCatalogUrl: String,
): List<ModuleVersionUpdate> {
    val installed = installedModuleVersions(central)
    if (installed.isEmpty()) return emptyList()
    val latestById = remoteModuleCatalog.modules(moduleCatalogUrl(central, defaultModuleCatalogUrl))
        .associateBy { it.definition.id }
    return installed.mapNotNull { installedModule ->
        val latest = latestById[installedModule.moduleId]?.definition ?: return@mapNotNull null
        latest.version
            .takeIf { it.isNotBlank() && it != installedModule.version }
            ?.let {
                ModuleVersionUpdate(
                    moduleId = installedModule.moduleId,
                    displayName = latest.displayName,
                    currentVersion = installedModule.version,
                    latestVersion = it,
                )
            }
    }.sortedBy { it.moduleId }
}

private fun installedModuleVersions(central: DataSource): List<InstalledModuleVersion> =
    try {
        central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT module_id, display_name, version FROM server_modules ORDER BY module_id"
            ).use { query ->
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                InstalledModuleVersion(
                                    moduleId = rows.getString("module_id"),
                                    displayName = rows.getString("display_name"),
                                    version = rows.getString("version"),
                                )
                            )
                        }
                    }
                }
            }
        }
    } catch (_: SQLException) {
        emptyList()
    }

private fun moduleCatalogUrl(central: DataSource, defaultModuleCatalogUrl: String): String =
    try {
        central.connection.use { connection ->
            connection.prepareStatement("SELECT setting_value FROM server_settings WHERE setting_key = ?").use { query ->
                query.setString(1, "module.catalog.url")
                query.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1) else defaultModuleCatalogUrl
                }
            }
        }
    } catch (_: SQLException) {
        defaultModuleCatalogUrl
    }.trim()

private data class InstalledModuleVersion(
    val moduleId: String,
    val displayName: String,
    val version: String,
)
