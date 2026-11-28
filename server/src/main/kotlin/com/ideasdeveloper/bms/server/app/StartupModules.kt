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
import com.ideasdeveloper.bms.server.storage.StorageAdmin
import com.ideasdeveloper.bms.server.storage.storageRoutes
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
import com.ideasdeveloper.bms.server.storage.CompanyFiles
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
import com.ideasdeveloper.bms.server.notifications.notificationRoutes
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

/** Discovery and loading of the server modules (classpath and installed packages) at startup. */
internal fun loadServerModules() =
    ServiceLoader.load(ServerModuleProvider::class.java)
        .map { it.create() }
        .sortedBy { it.definition.id }

/**
 * Loads the packages built on this server for installed optional modules. A package that fails to
 * load (for example after a core update that broke it) is skipped so the server still starts.
 */
internal fun loadPackagedModules(
    central: DataSource,
    classpathModuleIds: Set<String>,
    requirements: MutableMap<String, ModuleRequirements>,
    packagesRoot: Path,
): List<ServerModule> {
    val log = LoggerFactory.getLogger("ModulePackages")
    val packages = try {
        central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT module_id, installed_package_path, package_sha256 FROM server_modules WHERE installed_package_path LIKE '%.jar'"
            ).use { query ->
                query.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(Triple(rows.getString("module_id"), rows.getString("installed_package_path"), rows.getString("package_sha256"))) }
                }
            }
        }
    } catch (_: SQLException) {
        emptyList()
    }
    return packages.flatMap { (moduleId, path, sha256) ->
        // B8: only a jar inside the modules folder whose hash is the one recorded at install (ModuleJarIntegrity).
        val checked = if (moduleId in classpathModuleIds || !Files.isRegularFile(Path.of(path))) null
            else com.ideasdeveloper.bms.server.modules.ModuleJarIntegrity.check(moduleId, path, sha256, packagesRoot) { actual ->
                com.ideasdeveloper.bms.server.modules.ModuleJarIntegrity.recordFirst(central, moduleId, actual)
            }
        val jar = when (checked) {
            is com.ideasdeveloper.bms.server.modules.ModuleJarIntegrity.Result.Verified -> checked.jar
            is com.ideasdeveloper.bms.server.modules.ModuleJarIntegrity.Result.Recorded -> checked.jar
            else -> Path.of(path)
        }
        when {
            // A module compiled into this server (base module or :server:run) wins over a package.
            moduleId in classpathModuleIds -> emptyList()
            !Files.isRegularFile(jar) -> {
                log.error("Package of module {} not found at {}; reinstall the module.", moduleId, jar)
                emptyList()
            }
            checked is com.ideasdeveloper.bms.server.modules.ModuleJarIntegrity.Result.Refused -> {
                log.error("Package of module {} was NOT loaded: {}. Reinstall the module.", moduleId, checked.reason)
                emptyList()
            }
            else -> try {
                ModulePackageLoader.load(jar).filter { it.definition.id == moduleId }
                    .also { loaded -> if (loaded.isNotEmpty()) requirements[moduleId] = ModulePackageLoader.readRequirements(jar, moduleId) }
            } catch (failure: Throwable) {
                log.error("Package of module {} could not be loaded; reinstall it for this server version.", moduleId, failure)
                emptyList()
            }
        }
    }
}

// systemd (Restart=on-failure) starts the server again within seconds and the new package loads.
// Jar of an optional module built on this server, which extension packages compile against; null for base modules.
internal fun installedModuleJar(central: DataSource, moduleId: String, packagesRoot: Path): Path? = try {
    central.connection.use { connection ->
        connection.prepareStatement("SELECT installed_package_path, package_sha256 FROM server_modules WHERE module_id = ? AND installed_package_path LIKE '%.jar'").use { query ->
            query.setString(1, moduleId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
        }
    }?.let { (path, sha256) ->
        // The same check as loading it (B8): extensions compile against a verified jar only.
        when (val checked = com.ideasdeveloper.bms.server.modules.ModuleJarIntegrity.check(moduleId, path, sha256, packagesRoot) { false }) {
            is com.ideasdeveloper.bms.server.modules.ModuleJarIntegrity.Result.Verified -> checked.jar
            else -> null
        }
    }
} catch (_: SQLException) {
    null
}

internal fun restartToLoadModules() {
    Thread({
        Thread.sleep(2_000)
        LoggerFactory.getLogger("ModulePackages").info("Restarting to load the installed module package.")
        exitProcess(RestartExitCode)
    }, "module-restart").start()
}

private const val RestartExitCode = 3

internal fun loadInstalledServerModuleDefinitions(central: DataSource, modules: List<ServerModule>): List<ModuleDefinition> {
    val lockedDefinitions = modules.filter { it.definition.locked }.map { it.definition }
    return try {
        central.connection.use { connection ->
            connection.prepareStatement("SELECT module_id, display_name, description, version, locked FROM server_modules ORDER BY module_id").use { query ->
                query.executeQuery().use { rows ->
                    buildList {
                        addAll(lockedDefinitions)
                        while (rows.next()) {
                            // Only modules whose code is loaded count as installed. Rows left by older
                            // servers or by a package that no longer loads show as available to install.
                            val moduleId = rows.getString("module_id")
                            modules.firstOrNull { it.definition.id == moduleId }?.definition?.let(::add)
                        }
                    }.distinctBy { it.id }
                }
            }
        }
    } catch (_: SQLException) {
        lockedDefinitions
    }
}
