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

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    // Language of every request (Accept-Language, then the user's stored language) and the Core catalog.
    installI18n()
    installHttpPlugins()
    DatabaseFactory.init(environment.config)
    // Read the package signing keys now: a rejected keys folder is logged as an error at startup, not at the first install.
    log.info("Package signing keys configured: {}", com.ideasdeveloper.bms.server.extensions.packages.BMSPublicKeys.installed)
    val modulePackagesRoot = Path.of(
        environment.config.propertyOrNull("modules.packagesRoot")?.getString()
            ?: "build/server-modules"
    )
    // Files modules store for each company (attachments...); back this folder up with the databases.
    // Every write is limited by the company's quota, a free-disk floor and a cap on uploads in progress (STORAGE_QUOTA.md).
    val companyFilesRoot = Path.of(environment.config.propertyOrNull("company.filesRoot")?.getString() ?: "build/company-files")
    val companyBackupsRoot = Path.of(environment.config.propertyOrNull("company.backupsRoot")?.getString() ?: "build/company-backups")
    val storageQuotas = com.ideasdeveloper.bms.server.storage.StorageQuotas(DatabaseFactory.getDataSource())
    val storageGuard = com.ideasdeveloper.bms.server.storage.StorageGuard(
        root = companyFilesRoot,
        quotaFor = storageQuotas::effectiveQuotaBytes,
        minFreeBytes = environment.config.propertyOrNull("company.minFreeDiskBytes")?.getString()?.trim()?.toLongOrNull()
            ?.coerceAtLeast(0) ?: com.ideasdeveloper.bms.server.storage.StorageGuard.DefaultMinFreeBytes,
        maxConcurrentUploads = environment.config.propertyOrNull("company.maxConcurrentUploads")?.getString()?.trim()?.toIntOrNull()
            ?.coerceAtLeast(1) ?: com.ideasdeveloper.bms.server.storage.StorageGuard.DefaultMaxConcurrentUploads,
        // The company's backups count in its quota too.
        extraRoots = listOf(companyBackupsRoot),
    )
    val companyFiles = CompanyFiles.local(companyFilesRoot, storageGuard)
    val classpathModules = loadServerModules()
    val packageRequirements = mutableMapOf<String, ModuleRequirements>()
    val packagedModules = loadPackagedModules(DatabaseFactory.getDataSource(), classpathModules.map { it.definition.id }.toSet(), packageRequirements, modulePackagesRoot)
    val serverModules = (classpathModules + packagedModules).sortedBy { it.definition.id }
    val installedServerModules = loadInstalledServerModuleDefinitions(DatabaseFactory.getDataSource(), serverModules)
    val moduleRegistry = ModuleRegistry(
        modules = serverModules,
        installedModuleIds = installedServerModules.map { it.id }.toSet(),
        packagedModuleIds = packagedModules.map { it.definition.id }.toSet(),
        packageRequirements = packageRequirements,
    ).also { registry ->
        installedServerModules.forEach { definition ->
            registry.registerInstalled(definition)
        }
    }
    val githubToken = environment.config.propertyOrNull("modules.githubToken")?.getString()?.trim()?.takeIf { it.isNotEmpty() }
    val remoteModuleCatalog = RemoteModuleCatalog(githubToken)
    // Secrets are sealed with BMS_MASTER_KEY (server.env); without it the server runs but cannot store new ones.
    val secretBox: SecretBox = MasterKeySecretBox.fromEnvironment().also { box ->
        if (!box.available) log.warn("BMS_MASTER_KEY is not configured; secrets cannot be stored until it is set in server.env.")
    }
    val loginDatabases = LoginDatabases(
        central = DatabaseFactory.getDataSource(),
        centralDatabase = DatabaseFactory.getDatabase(),
        centralUrl = environment.config.property("database.url").getString(),
        tenantConfiguration = environment.config.property("tenant.databases").getString(),
        runtimeUser = environment.config.property("database.user").getString(),
        runtimePassword = environment.config.property("database.password").getString(),
        tenantJdbcUrlPrefix = environment.config.propertyOrNull("provisioning.tenantJdbcUrlPrefix")?.getString()
            ?: environment.config.property("database.url").getString().substringBeforeLast('/') + "/",
        lifetimeSeconds = environment.config.property("auth.sessionLifetimeSeconds").getString().toLong(),
        // TOTP secrets of two-step verification are sealed with the master key (TWO_FACTOR.md).
        secrets = secretBox,
    )
    val centralSessions = SessionService(
        sessionRepository = ApplicationSessionRepository(DatabaseFactory.getDatabase()),
        userRepository = ApplicationUserRepository(DatabaseFactory.getDatabase()),
        tokenGenerator = SessionTokenGenerator(),
        sessionLifetimeSeconds = environment.config.property("auth.sessionLifetimeSeconds").getString().toLong(),
    )
    val provisioningConfig = companyProvisioningConfig()
    // Set below; the integrity service notifies the server owners through it (their notifications in the workspace).
    lateinit var moduleAccessRef: ModuleAccessService
    // Tamper evidence of the company activity logs (ACTIVITY_LOG.md §9): verification, anchors, hourly job.
    val activityIntegrity = com.ideasdeveloper.bms.server.activity.ActivityIntegrityService(
        central = DatabaseFactory.getDataSource(),
        // Not pooled: a full check reads a whole log and must not hold one of the requests' few connections.
        connect = { databaseName ->
            loginDatabases.tenantConfig(databaseName)?.let { tenant ->
                org.postgresql.ds.PGSimpleDataSource().apply {
                    setURL(tenant.jdbcUrl)
                    user = tenant.user
                    password = tenant.password
                }.connection
            }
        },
        notifyOwners = { title, text -> notifyServerOwners(DatabaseFactory.getDataSource(), moduleAccessRef, title, text) },
    )
    com.ideasdeveloper.bms.server.activity.CoreActivity.integrityAlarm = activityIntegrity::alarm
    // What the owner asked to install and is not finished: resumed after a restart, which waits for every build.
    val installQueue = PackageInstallQueue(modulePackagesRoot.toAbsolutePath().resolve("pending-installs.json"), ::restartToLoadModules)
    val companyProvisioning = CompanyProvisioningService(
        central = DatabaseFactory.getDataSource(),
        config = provisioningConfig,
        loginDatabases = loginDatabases,
        moduleRegistry = moduleRegistry,
        remoteModuleCatalog = remoteModuleCatalog,
        defaultCatalogUrl = environment.config.propertyOrNull("modules.catalogUrl")?.getString().orEmpty(),
        modulePackagesRoot = modulePackagesRoot,
        modulePackageBuilder = ModulePackageBuilder.forRunningServer(
            modulePackagesRoot,
            githubToken,
            JdbcModuleUnsignedAuthorizationStore { DatabaseFactory.getDataSource().connection },
            // Compuerta de firma de modulos (SIGNING.md): MODULE_SIGNATURE_ENFORCED=true/false manda; sin valor, activa
            // solo cuando hay una llave publica de paquetes instalada.
            signatureEnforced = com.ideasdeveloper.bms.server.modules.ModuleSignaturePolicy.enforced(),
        ),
        requestRestart = ::restartToLoadModules,
        companyFiles = companyFiles,
        installQueue = installQueue,
        // A resumed install runs with the current rights of who asked for it (SERVER_ADMINS.md §8).
        queuedActors = com.ideasdeveloper.bms.server.admins.QueuedInstallActors(loginDatabases.admins::queuedInstallActor),
        activityIntegrity = activityIntegrity,
    )
    // Applies migrations of newly loaded module versions to the companies that use them, without
    // delaying startup.
    // Extension compatibility is checked once those migrations (which create tenant_extensions) ran.
    val migrationsDone = java.util.concurrent.CountDownLatch(1)
    Thread({
        try {
            companyProvisioning.applyPendingModuleMigrations()
        } finally {
            migrationsDone.countDown()
        }
    }, "module-migrations").apply { isDaemon = true }.start()
    // Set once the extensions are loaded; their customer references are read per request.
    var extensionRegistryForReferences: ExtensionRegistry? = null
    // Built below; the server MCP reads them only when an extension tool runs.
    lateinit var extensionService: ExtensionService
    lateinit var extensionPackages: ExtensionPackageService
    // Activity logs of the companies for the server (GET /server/activity and the server agent tools).
    val activityModuleName: (String) -> String = { id ->
        if (id == com.ideasdeveloper.bms.server.activity.CoreModuleId) "Empresa" else moduleRegistry.availableDefinition(id)?.displayName ?: id
    }
    val serverActivity = com.ideasdeveloper.bms.server.activity.ServerActivity(
        central = DatabaseFactory.getDataSource(),
        // The same pooled connections as the requests of each company (a few per company, never one per read).
        openTenant = { databaseName -> moduleAccessRef.tenantConnectionForDatabase(databaseName) },
        moduleName = activityModuleName,
    )
    val serverAudit = ServerAudit(DatabaseFactory.getDataSource())
    val serverAuditTools = com.ideasdeveloper.bms.server.activity.ServerAuditMcpTools(
        central = DatabaseFactory.getDataSource(),
        audit = { serverAudit },
        activity = { serverActivity },
        liveActor = { userId -> ServerOwnerAuthorizer.liveActor(loginDatabases.admins, DatabaseFactory.getDataSource(), userId) },
    )
    val serverMcp = ServerMcpTools.mcp(
        companyProvisioning,
        extensions = { role -> ExtensionMcpTools.tools({ extensionService }, { extensionPackages }, role) },
        extensionInstructions = ExtensionMcpTools.Instructions,
        auditTools = serverAuditTools.tools,
    )
    // Crea el servicio de autoservicio para perfil y ZIPs de empresas business_owner.
    val moduleAccessService = ModuleAccessService(
        DatabaseFactory.getDataSource(),
        loginDatabases,
        serverMailSettings(),
        moduleRegistry::isInstalled,
        // The server's own MCP (companies and modules, server owners only) and each module's.
        { listOf(serverMcp) + moduleRegistry.mcp().filterNot { it.moduleId == ServerMcpModuleId } },
        loginDatabases.service::login,
        moduleRegistry::customerTimelines,
        companyFiles,
        moduleRegistry::customerHistoryWriter,
        moduleRegistry::customerChangeListener,
        moduleRegistry::customerNotesReader,
        secrets = secretBox,
        extensionModules = { extensionRegistryForReferences?.all.orEmpty().associate { it.manifest.id to it.manifest.module } },
        customerReferenceSources = {
            moduleRegistry.customerReferenceSources() + extensionRegistryForReferences?.all.orEmpty().mapNotNull { extension ->
                val references = try {
                    extension.customerReferences
                } catch (failure: Throwable) {
                    log.warn("Referencias a clientes de la extension '{}' ignoradas.", extension.manifest.id, failure)
                    emptyList()
                }
                references.takeIf { it.isNotEmpty() }?.let { CustomerReferenceSource(extension.manifest.module, extension.manifest.id, it) }
            }
        },
        moduleNames = { id -> moduleRegistry.availableDefinition(id)?.displayName },
    ).also { moduleAccessRef = it }
    // Delayed recovery of two-step verification by email (TWO_FACTOR.md §7) and its job (every 10 minutes).
    // In-app notice to an account (company: its company; server account: the server workspace).
    val notifyAccount: (String?, java.util.UUID, String, String) -> Unit = { companyCode, userId, title, text ->
            val tenant = if (companyCode != null) moduleAccessService.tenantConnectionForCompany(companyCode) else {
                DatabaseFactory.getDataSource().connection.use { connection ->
                    connection.prepareStatement("SELECT database_name FROM companies WHERE is_server_workspace AND is_active").use { query ->
                        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                    }
                }?.let { moduleAccessService.tenantConnectionForDatabase(it) }
            }
            tenant?.use { moduleAccessService.notifications.notify(it, com.ideasdeveloper.bms.server.modules.NewNotification(userId, com.ideasdeveloper.bms.server.activity.CoreModuleId, title, text)) }
    }
    val emailRecovery = com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorEmailRecovery(
        central = DatabaseFactory.getDataSource(),
        sources = loginDatabases::accountSource,
        companies = loginDatabases::activeCompanyCodes,
        mail = serverMailSettings().let { settings -> { settings } },
        twoFactor = loginDatabases.twoFactor,
        notify = notifyAccount,
    ).also { loginDatabases.emailRecovery = it }
    val emailRecoveryJob = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "two-factor-email-recovery").apply { isDaemon = true } }
    emailRecoveryJob.scheduleWithFixedDelay({
        try {
            emailRecovery.runDue()
        } catch (failure: Throwable) {
            log.error("Two-step email recovery job failed", failure)
        }
    }, 2, 10, java.util.concurrent.TimeUnit.MINUTES)
    // Extensions installed per company (EXTENSIONS.md): bundled in the modules or loaded from packages.
    val extensionConnections = ExtensionConnections.from(moduleAccessService)
    val extensionStorage = ExtensionStorage(secretBox)
    // Extension packages built on this server from the extension catalog (signed, or authorized by hash).
    val remoteExtensionCatalog = RemoteExtensionCatalog(githubToken)
    val extensionCatalogUrl = RemoteExtensionCatalog.effectiveUrl(
        environment.config.propertyOrNull("extensions.catalogUrl")?.getString().orEmpty(),
        environment.config.propertyOrNull("modules.catalogUrl")?.getString().orEmpty(),
    )
    val extensionPackagesRoot = modulePackagesRoot.toAbsolutePath().resolveSibling("server-extensions")
    val unsignedAuthorizations = JdbcUnsignedAuthorizationStore { DatabaseFactory.getDataSource().connection }
    extensionPackages = ExtensionPackageService(
        store = JdbcExtensionPackageStore { DatabaseFactory.getDataSource().connection },
        packagesRoot = extensionPackagesRoot,
        bundledIds = { serverModules.flatMap { module -> module.bundledExtensions.map { it.manifest.id } }.toSet() },
        catalogSource = { remoteExtensionCatalog.extensions(extensionCatalogUrl) },
        catalogProblem = { remoteExtensionCatalog.problem(extensionCatalogUrl) },
        builder = ExtensionPackageBuilder.forRunningServer(
            packagesRoot = extensionPackagesRoot,
            validator = ExtensionManifestValidator(
                installedModuleVersion = { id -> moduleRegistry.definition(id)?.version },
                modulePermissions = { id -> moduleRegistry.availableDefinition(id)?.extensionPermissions.orEmpty() },
            ),
            verifier = ExtensionSignatureVerifier(),
            authorizations = unsignedAuthorizations,
            moduleJar = { id -> installedModuleJar(DatabaseFactory.getDataSource(), id, modulePackagesRoot) },
            githubToken = githubToken,
        ),
        authorizations = unsignedAuthorizations,
        requestRestart = ::restartToLoadModules,
        // The services pass the login of who acted (owner or administrator); the audit names that user.
        // A blank login is the system (a queued install dropped while nobody acted).
        audit = { login, action, company, module, details ->
            val actor = if (login.isBlank()) com.ideasdeveloper.bms.server.admins.AuditActor.system() else loginDatabases.admins.auditActorFor(login)
            companyProvisioning.recordAudit(actor, action, company, module, details)
        },
        installQueue = installQueue,
        moduleLoaded = { id -> moduleRegistry.definition(id) != null },
        modulePending = { id -> companyProvisioning.moduleInstallPending(id) },
        queuedActors = com.ideasdeveloper.bms.server.admins.QueuedInstallActors(loginDatabases.admins::queuedInstallActor),
    )
    val packageExtensions = extensionPackages.loadInstalled(
        moduleClassLoader = { id -> moduleRegistry.module(id)?.javaClass?.classLoader },
    )
    val extensionRegistry = ExtensionRegistry.of(serverModules, packageExtensions)
    extensionRegistryForReferences = extensionRegistry
    serverModules.forEach { module -> module.linkExtensions(extensionRegistry.forModule(module.definition.id)) }
    extensionService = ExtensionService(
        connections = extensionConnections,
        storage = extensionStorage,
        registry = extensionRegistry,
        modules = moduleRegistry,
        notifier = ExtensionNotifier.from(moduleAccessService),
        auditor = { actor, action, company, module, details -> companyProvisioning.recordAudit(actor, action, company, module, details) },
        migrationRunner = { company, extension -> companyProvisioning.applyExtensionMigrations(company, extension) },
    )
    // Installs a restart left unfinished start again: modules first, then the extensions that waited for them.
    Thread({
        migrationsDone.await()
        try {
            companyProvisioning.resumePendingInstalls()
            extensionPackages.resumePendingInstalls()
        } catch (failure: Exception) {
            log.error("Pending installs were not resumed", failure)
        }
    }, "resume-installs").apply { isDaemon = true }.start()
    // The module jobs (which call the extensions' imports) wait for this: extension code of a new version never runs on a
    // company schema of an older one (EXTENSIONS.md, Updating a package).
    val extensionsReady = java.util.concurrent.CountDownLatch(1)
    // A module loaded at a version below an extension's minimum leaves that extension incompatible.
    Thread({
        try {
            // Group operations cut by a restart end as failed, so their companies can be retried.
            try {
                extensionService.recoverInterrupted()
            } catch (failure: Exception) {
                log.error("Interrupted extension operations were not recovered", failure)
            }
            migrationsDone.await()
            // A package (or module, or Core) update replaced the code: each company gets the new version's migrations, or
            // the extension is suspended there.
            extensionService.syncSchemas()
            serverModules.map { it.definition }.filter { extensionRegistry.forModule(it.id).isNotEmpty() }.forEach { definition ->
                try {
                    extensionService.markIncompatible(definition.id, definition.version)
                } catch (failure: Exception) {
                    log.error("Extensions of module {} were not checked", definition.id, failure)
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            log.error("Extension schemas were not checked at startup", failure)
        } finally {
            extensionsReady.countDown()
        }
    }, "extension-compatibility").apply { isDaemon = true }.start()
    // Every hour: suspended extensions (and companies that could not be read) are retried.
    val extensionSchemaJob = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "extension-schemas").apply { isDaemon = true } }
    extensionSchemaJob.scheduleWithFixedDelay({
        try {
            extensionsReady.await()
            extensionService.syncSchemas()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            log.error("Extension schemas check failed", failure)
        }
    }, 60, 60, java.util.concurrent.TimeUnit.MINUTES)
    // Name and email of each user and password recovery by email code.
    // Un solo contador de fallos de contrasena actual para /auth/profile y la edicion del business owner.
    val passwordAttempts = com.ideasdeveloper.bms.server.auth.application.PasswordAttemptLimiter()
    // Server administrators (SERVER_ADMINS.md): who is behind a central session, read live on every request.
    val serverActors = ServerOwnerAuthorizer(DatabaseFactory.getDataSource(), centralSessions)
    // Built below (it needs the account services); administrators' workspace staff are closed through it.
    lateinit var companySelfServiceRef: CompanySelfService
    val serverAdmins = com.ideasdeveloper.bms.server.admins.ServerAdminService(
        store = loginDatabases.admins,
        loginTaken = companyProvisioning::loginNameTaken,
        revokeCompanySessions = { userId -> loginDatabases.revokeCompanySessionsOpenedBy(userId) },
        audit = { actor, action, details -> companyProvisioning.recordAudit(actor, action, null, null, details) },
        passwordAttempts = passwordAttempts,
        deactivateWorkspaceStaff = { adminUserId, by, reason -> companySelfServiceRef.deactivateWorkspaceStaffOf(adminUserId, by, reason) },
        twoFactorEnabled = { id -> com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorPolicy.enabledOnServer(DatabaseFactory.getDataSource(), id) },
    )
    val accountService = AccountService(
        central = DatabaseFactory.getDataSource(),
        loginDatabases = loginDatabases,
        mail = serverMailSettings().let { settings -> { settings } },
        verifyPassword = loginDatabases::verifyPassword,
        passwordAttempts = passwordAttempts,
        passwords = AccountPasswords(
            serverOwner = { userId, password ->
                try {
                    companyProvisioning.setServerOwnerPasswordByEmailCode(userId, password)
                } catch (failure: CompanyProvisioningException) {
                    throw AccountException("PASSWORD_NOT_UPDATED", failure.message.orEmpty())
                }
            },
            businessOwner = { companyCode, password ->
                try {
                    companyProvisioning.setBusinessOwnerPasswordByEmailCode(companyCode, password)
                } catch (failure: CompanyProvisioningException) {
                    throw AccountException("PASSWORD_NOT_UPDATED", failure.message.orEmpty())
                }
            },
            serverAdmin = { userId, password ->
                try {
                    serverAdmins.recoverPassword(userId, password)
                } catch (failure: com.ideasdeveloper.bms.server.admins.ServerAdminException) {
                    throw AccountException(failure.code, failure.message.orEmpty())
                }
            },
        ),
    )
    // Periodic work declared by modules (ServerModule.jobs), for every company that uses them.
    // They start once the startup migrations of modules and extensions ran; a module whose migrations failed in a company
    // does not run its jobs there until a later catch-up applies them.
    val moduleJobs = ModuleJobScheduler(
        moduleRegistry, moduleAccessService,
        ready = { extensionsReady.count == 0L },
        blocked = companyProvisioning::moduleMigrationFailed,
    ).also { it.start() }
    // Turning another account's two-step verification off (TWO_FACTOR.md §8).
    val twoFactorResets = com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetService(
        loginDatabases, passwordAttempts, mail = serverMailSettings().let { settings -> { settings } }, notify = notifyAccount,
    )
    val companySelfService = CompanySelfService(
        central = DatabaseFactory.getDataSource(),
        loginDatabases = loginDatabases,
        moduleRegistry = moduleRegistry,
        backupsRoot = companyBackupsRoot,
        ownerEditor = companyProvisioning::updateBusinessOwnerBySelf,
        verifyOwnerPassword = { companyCode, username, password -> loginDatabases.verifyPassword(companyCode, username, password) },
        passwordAttempts = passwordAttempts,
        secrets = secretBox,
        serverActors = serverActors,
        storage = storageGuard,
        twoFactorResets = twoFactorResets,
    ).also { companySelfServiceRef = it }
    // The in-app assistant (GLOBAL_AGENT.md): Anthropic SDK clients per API key (never from the environment), the
    // server's settings in server_settings and a daily job (expired confirmations, retention).
    val assistantModels = com.ideasdeveloper.bms.server.assistant.AnthropicModelClients()
    val assistantService = com.ideasdeveloper.bms.server.assistant.AssistantService(
        access = moduleAccessService,
        serverConfig = com.ideasdeveloper.bms.server.assistant.AssistantServerConfig(DatabaseFactory.getDataSource(), secretBox),
        secrets = secretBox,
        models = assistantModels,
        serverHosts = setOfNotNull(PublicOrigin.configured()?.let { runCatching { java.net.URI(it).host?.lowercase() }.getOrNull() }),
    )
    val assistantJob = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "assistant-upkeep").apply { isDaemon = true } }
    assistantJob.scheduleWithFixedDelay({
        try {
            migrationsDone.await()
            // Every company, suspended ones included (retention still applies to them).
            val companies = DatabaseFactory.getDataSource().connection.use { connection ->
                connection.prepareStatement("SELECT code, database_name FROM companies WHERE NOT is_server_workspace ORDER BY code").use { query ->
                    query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
                }
            }
            companies.forEach { (code, database) ->
                try {
                    assistantService.daily(code, database)
                } catch (failure: Exception) {
                    log.warn("Assistant upkeep failed for company {}: {}", code, failure.javaClass.name)
                }
            }
        } catch (failure: Throwable) {
            log.error("Assistant upkeep job failed to run", failure)
        }
    }, 30, 24 * 60, java.util.concurrent.TimeUnit.MINUTES)
    // Hourly: every company's chain verified and anchored in provisioning_audit_log, after the startup catch-up.
    val integrityJob = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "activity-integrity").apply { isDaemon = true } }
    integrityJob.scheduleWithFixedDelay({
        try {
            migrationsDone.await()
            activityIntegrity.runAll()
        } catch (failure: Throwable) {
            log.error("Activity log verification failed to run", failure)
        }
    }, 10, 60, java.util.concurrent.TimeUnit.MINUTES)
    monitor.subscribe(ApplicationStopped) {
        integrityJob.shutdownNow()
        assistantJob.shutdownNow()
        assistantModels.close()
        assistantService.close()
        emailRecoveryJob.shutdownNow()
        extensionSchemaJob.shutdownNow()
        moduleJobs.stop()
        moduleAccessService.close()
        remoteModuleCatalog.close()
        remoteExtensionCatalog.close()
        loginDatabases.close()
        DatabaseFactory.close()
    }
    log.info("PostgreSQL connection verified")
    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
        rateLimit(RateLimitName("public")) {
            get("/version") {
                // En cache unos minutos: la consulta hace peticiones HTTP salientes y no debe poder
                // amplificarse desde fuera.
                val full = versionCache.get {
                    versionResponse(
                        catalogUrl = environment.config.propertyOrNull("version.catalogUrl")?.getString().orEmpty(),
                        central = DatabaseFactory.getDataSource(),
                        remoteModuleCatalog = remoteModuleCatalog,
                        defaultModuleCatalogUrl = environment.config.propertyOrNull("modules.catalogUrl")?.getString().orEmpty(),
                    )
                }
                // B7: before signing in the app only needs the Core version (compatibility) and the latest
                // app version; the rest only for a server user who may update the Core or see the modules.
                val token = call.request.headers[io.ktor.http.HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
                val actor = token?.takeIf { it.isNotEmpty() }?.let { withContextIO { (serverActors.resolve(it) as? com.ideasdeveloper.bms.server.admins.ServerActorLookup.Found)?.actor } }
                val detailed = actor != null && (actor.has(com.ideasdeveloper.bms.server.admins.ServerPermission.CoreUpdate) || actor.has(com.ideasdeveloper.bms.server.admins.ServerPermission.ModulesView))
                call.respond(if (detailed) full else full.publicView())
            }
        }

        rateLimit(RateLimitName("login")) {
            authRoutes(
                loginService = loginDatabases.service,
                switchTargets = loginDatabases::switchTargets,
                switchTo = loginDatabases::switchTo,
                logout = loginDatabases::logout,
                includeSession = false,
                completeTwoFactor = loginDatabases::completeTwoFactor,
                startEmailRecovery = loginDatabases::startEmailRecovery,
                knownPublicHost = loginDatabases::knownPublicHost,
                completeEmailRecovery = loginDatabases::completeEmailRecovery,
            )
            // The cancel link of the recovery email (no session, server-rendered page).
            twoFactorRecoveryLinkRoutes { loginDatabases.emailRecovery }
            passwordResetRoutes(accountService)
        }
        rateLimit(RateLimitName("admin")) {
            // Cierre de sesion y cambio de empresa: no deben gastar el cubo estricto del login (5/min).
            authRoutes(
                loginService = loginDatabases.service,
                switchTargets = loginDatabases::switchTargets,
                switchTo = loginDatabases::switchTo,
                logout = loginDatabases::logout,
                includeLogin = false,
            )
            accountProfileRoutes(accountService)
            // The user's own two-step verification (TWO_FACTOR.md §3).
            twoFactorRoutes(
                com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorAccountService(
                    loginDatabases, passwordAttempts = passwordAttempts, mail = serverMailSettings().let { settings -> { settings } }, notify = notifyAccount,
                ),
            )
        }
        rateLimit(RateLimitName("admin")) {
            // Registra rutas de provisioning server_owner y autoservicio business_owner.
            companyRoutes(
                authorizer = serverActors,
                provisioning = companyProvisioning,
                selfService = companySelfService,
                coreUpdates = CoreUpdateService(
                    store = JdbcCoreUpdateStore(DatabaseFactory.getDataSource()),
                    currentVersion = BMSVersion,
                    latestVersion = {
                        VersionCatalogClient.fetch(
                            environment.config.propertyOrNull("version.catalogUrl")?.getString().orEmpty()
                        )?.latestCoreVersion
                    },
                    // The request must carry the commit the owner approved (the app sends targetCommit). Only for an
                    // older app: CORE_UPDATE_REQUIRE_APPROVED_COMMIT=false lets the server pin the commit itself.
                    requireApprovedCommit = coreUpdateRequiresApprovedCommit(System.getenv("CORE_UPDATE_REQUIRE_APPROVED_COMMIT")),
                ),
                addToScope = { userId, code -> loginDatabases.admins.addCompany(userId, code) },
            )
            serverAdminRoutes(serverActors, serverAdmins, serverAudit, serverActivity, activityIntegrity)
            // Storage quotas of the companies (STORAGE_QUOTA.md), owner only.
            twoFactorPolicyRoutes(serverActors, DatabaseFactory.getDataSource())
            twoFactorResetRoutes(
                serverActors,
                com.ideasdeveloper.bms.server.auth.api.ServerTwoFactorResets(
                    twoFactorResets,
                    ownerGroupOf = loginDatabases::ownerGroupOf,
                    businessOwnerOf = { code -> runCatching { loginDatabases.businessOwnerId(code) }.getOrNull() },
                    isAdmin = { id -> loginDatabases.admins.find(id)?.usable == true },
                ),
            )
            storageRoutes(serverActors, StorageAdmin(DatabaseFactory.getDataSource(), storageQuotas, storageGuard, companyFilesRoot))
            // The company's own activity log (ACTIVITY_LOG.md §3).
            companyActivityRoutes(moduleAccessService, activityModuleName)
            // The in-app assistant (GLOBAL_AGENT.md): company routes (SSE turns) and the server's settings.
            assistantRoutes(assistantService)
            assistantServerRoutes(serverActors, com.ideasdeveloper.bms.server.assistant.AssistantServerAdmin(DatabaseFactory.getDataSource(), assistantService))
        }
        // Centro de notificaciones del Core: la campana de la app lee y marca las del usuario.
        rateLimit(RateLimitName("public")) {
            notificationRoutes(moduleRegistry, moduleAccessService)
            moduleRoutes(moduleRegistry, moduleAccessService)
        }
        rateLimit(RateLimitName("admin")) {
            extensionRoutes(
                extensionService,
                ExtensionActors(moduleAccessService, serverActors, extensionConnections),
            )
        }
        rateLimit(RateLimitName("admin")) {
            // Instalar por ZIP/URL queda apagado salvo que EXTENSION_UPLOAD_ENABLED=true (mismo patron que MODULE_SIGNATURE_ENFORCED).
            val uploadEnabled = System.getenv("EXTENSION_UPLOAD_ENABLED")?.trim()?.equals("true", ignoreCase = true) ?: false
            extensionPackageRoutes(extensionPackages, uploadEnabled, serverActors)
        }
        // Routes of every extension, mounted once behind ExtensionGate (status per company).
        rateLimit(RateLimitName("extensions")) {
            extensionRegistry.installRoutes(this, moduleAccessService, extensionStorage)
        }
    }
}
