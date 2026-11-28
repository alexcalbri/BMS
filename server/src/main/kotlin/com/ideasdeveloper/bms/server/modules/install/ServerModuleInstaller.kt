package com.ideasdeveloper.bms.server.modules.install

import com.ideasdeveloper.bms.server.i18n.tr
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
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningException
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.TenantSchemaProvisioner
import com.ideasdeveloper.bms.server.company.application.auditDetails
import com.ideasdeveloper.bms.server.company.application.inCentralTransaction

internal class ServerModuleInstaller(
    private val central: DataSource,
    private val loginDatabases: LoginDatabases,
    private val moduleRegistry: ModuleRegistry,
    private val remoteModuleCatalog: RemoteModuleCatalog,
    private val defaultCatalogUrl: String,
    private val modulePackagesRoot: Path,
    private val modulePackageBuilder: ModulePackageBuilder?,
    private val requestRestart: () -> Unit,
    private val installQueue: PackageInstallQueue?,
    private val queuedActors: com.ideasdeveloper.bms.server.admins.QueuedInstallActors,
    private val tenantSchema: TenantSchemaProvisioner,
    private val serverWorkspaceModules: () -> Set<String>?,
    private val ensureServerWorkspace: (String, AuditActor) -> String,
    private val serverWorkspaceCode: () -> String?,
    private val setModuleEnabled: (String, String, Boolean, String, AuditActor) -> CompanySummaryResponse,
    private val countCompaniesWithModuleEnabled: (String) -> Int,
) {
    private val log = LoggerFactory.getLogger(CompanyProvisioningService::class.java)
    // Progress of optional modules being built for installation, shown in the module list.
    private val installStates = ConcurrentHashMap<String, ModuleInstallState>()
    private val MinModuleConfirmationLength = 10
    // One build at a time: compiling uses about 1 GB of memory.
    private val buildExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "module-build").apply { isDaemon = true } }
    private val moduleCatalogSettingKey = "module.catalog.url"
    private val modules: List<ModuleDefinition>
        get() = moduleRegistry.definitions

    /**
     * Base modules, installed modules and the modules offered by the catalog URL configured now.
     * Modules of a previously configured catalog that were never installed are not shown.
     */
    fun listServerModules(): List<ServerModuleResponse> {
        ensureLockedServerModulesPersisted()
        val remotePackages = syncRemoteAvailableModules()
        val shown = moduleRegistry.availableDefinitions
            .filter { moduleRegistry.hasCode(it.id) || moduleRegistry.isInstalled(it.id) || it.id in remotePackages || installStates.containsKey(it.id) }
        val serverUsage = serverWorkspaceModules()
        return shown.map { definition -> moduleResponse(definition, remotePackages[definition.id], serverUsage) }
    }

    private fun moduleResponse(
        definition: ModuleDefinition,
        remotePackage: RemoteModulePackage?,
        serverUsage: Set<String>? = serverWorkspaceModules(),
    ): ServerModuleResponse {
        val installedPackage = installedPackage(definition.id)
        val state = installStates[definition.id]
        val installedVersion = installedPackage?.version ?: definition.version
        val catalogVersion = remotePackage?.definition?.version
        val installed = moduleRegistry.isInstalled(definition.id)
        // Base modules are updated with the server, never from the catalog.
        val comparable = installed && !definition.locked && catalogVersion != null
        return ServerModuleResponse(
            moduleId = definition.id,
            displayName = trIn(I18n.moduleNamespace(definition.id), definition.displayName),
            description = trIn(I18n.moduleNamespace(definition.id), definition.description),
            version = installedVersion,
            latestVersion = remotePackage?.definition?.version,
            locked = definition.locked,
            installed = moduleRegistry.isInstalled(definition.id),
            activeCompanyCount = countCompaniesWithModuleEnabled(definition.id),
            packageUrl = installedPackage?.packageUrl,
            packageSha256 = installedPackage?.packageSha256,
            installedPackagePath = installedPackage?.installedPackagePath,
            status = state?.status,
            statusMessage = state?.message,
            updateAvailable = comparable && ModuleVersion.isNewer(catalogVersion!!, installedVersion),
            newerThanCatalog = comparable && ModuleVersion.isNewer(installedVersion, catalogVersion!!),
            usedByServer = installed && serverUsage != null && (definition.locked || definition.id in serverUsage),
            minCoreVersion = definition.minCoreVersion,
            minAppVersion = definition.minAppVersion,
            latestMinCoreVersion = remotePackage?.definition?.minCoreVersion,
            latestMinAppVersion = remotePackage?.definition?.minAppVersion,
            // Solo se bloquea por el Core del servidor; la app la compara el cliente con su propia version.
            installBlockedReason = if (definition.locked || remotePackage == null) {
                null
            } else {
                ModuleRequirementGate.coreProblem(remotePackage.definition.minCoreVersion, BMSVersion)?.message
            },
        )
    }

    fun serverModuleCatalog(): ServerModuleCatalogResponse =
        ServerModuleCatalogResponse(catalogUrl = moduleCatalogUrl())

    fun updateServerModuleCatalog(catalogUrl: String, actor: AuditActor): ServerModuleCatalogResponse {
        val normalized = catalogUrl.trim()
        // Solo https al puerto 443 y sin credenciales; la IP se valida y se ancla en cada lectura (RemoteModuleCatalog).
        if (normalized.isNotBlank()) {
            try {
                com.ideasdeveloper.bms.server.modules.CatalogUrlPolicy.validate(normalized)
            } catch (_: com.ideasdeveloper.bms.server.modules.CatalogUrlException) {
                throw CompanyProvisioningException(tr("La URL del catalogo debe ser https (puerto 443) y sin usuario ni clave."))
            }
        }
        central.inCentralTransaction { connection ->
            connection.prepareStatement(
                """
                INSERT INTO server_settings (setting_key, setting_value, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (setting_key)
                DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = CURRENT_TIMESTAMP
                """.trimIndent()
            ).use { upsert ->
                upsert.setString(1, moduleCatalogSettingKey)
                upsert.setString(2, normalized)
                upsert.executeUpdate()
            }
            insertAudit(connection, actor, "module.catalog_updated", null, null, auditDetails("catalogUrl" to normalized))
        }
        // Saving the URL (even the same one) reads the catalog again on the next listing.
        remoteModuleCatalog.forget(normalized)
        return ServerModuleCatalogResponse(catalogUrl = normalized)
    }

    /**
     * Installs a module. Modules whose code is already loaded are activated at once. Optional
     * modules are downloaded from the configured catalog branch and built in the background; when
     * the package is ready the server restarts to load it. The response reports the progress.
     */
    fun installServerModule(moduleId: String, actor: AuditActor, appVersion: String? = null): ServerModuleResponse {
        val normalizedModuleId = moduleId.trim()
        val remotePackages = syncRemoteAvailableModules()
        val definition = moduleRegistry.availableDefinition(normalizedModuleId)
            ?: throw CompanyProvisioningException(tr("Modulo no encontrado en el catalogo configurado."))
        val remotePackage = remotePackages[definition.id]
        if (installStates[definition.id]?.status in setOf(InstallStatus.Installing, InstallStatus.Restarting)) {
            return moduleResponse(definition, remotePackage)
        }

        // Only a newer catalog version replaces a loaded package; an older one never downgrades it.
        val loadedVersion = moduleRegistry.loadedVersion(definition.id)
        val update = moduleRegistry.isPackaged(definition.id) && remotePackage != null && loadedVersion != null &&
            ModuleVersion.isNewer(remotePackage.definition.version, loadedVersion)
        val building = !moduleRegistry.hasCode(definition.id) || update
        // Versiones minimas: antes de activar o descargar nada. Se revisa lo que se va a quedar instalado.
        val target = if (building) remotePackage?.definition ?: definition else definition
        if (!definition.locked) {
            (ModuleRequirementGate.coreProblem(target.minCoreVersion, BMSVersion)
                ?: ModuleRequirementGate.appProblem(target.minAppVersion, appVersion))
                ?.let { throw CompanyProvisioningException(it.message, it.code) }
        }
        val warning = ModuleRequirementGate.missingAppVersionWarning(target.minAppVersion, appVersion)
        recordAudit(
            actor, if (update) "module.update_requested" else "module.install_requested", null, definition.id,
            auditDetails("version" to target.version, "build" to building),
        )
        if (!building) {
            activateLoadedModule(definition)
            return moduleResponse(definition, remotePackage).copy(warning = warning)
        }

        val builder = modulePackageBuilder
            ?: throw CompanyProvisioningException(tr("Este servidor no puede compilar modulos: falta la carpeta compiler/ de la instalacion. Actualiza el servidor con update.sh."))
        val source = remotePackage?.source
            ?: throw CompanyProvisioningException(tr("{0} no esta en el catalogo configurado, o el catalogo no es una carpeta de GitHub.", trIn(I18n.moduleNamespace(definition.id), definition.displayName)))
        installStates[definition.id] = ModuleInstallState(InstallStatus.Installing) { tr("En cola para compilar") }
        // Who asked is kept with the queued install, so a resumed install is re-checked with their current rights.
        installQueue?.add(PackageInstallQueue.Module, definition.id, if (actor.kind == AuditActor.SystemKind) "" else actor.login)
        installQueue?.queued()
        // An administrator always gets the enforced signature gate: an unsigned package needs its hash
        // authorized (server.modules.authorize_unsigned), whatever MODULE_SIGNATURE_ENFORCED says.
        val requireAuthorization = actor.kind == AuditActor.ServerAdminKind
        buildExecutor.execute { buildAndInstall(definition, builder, source, requireAuthorization) }
        return moduleResponse(definition, remotePackage).copy(warning = warning)
    }

    /** Descarga el modulo del catalogo y devuelve commit, URL y hash calculado, para revisarlo antes de autorizar. */
    fun inspectServerModule(moduleId: String): ModulePackageInspection {
        val id = moduleId.trim()
        val remote = syncRemoteAvailableModules()[id]
        val source = remote?.source
            ?: throw CompanyProvisioningException(tr("El modulo '{0}' no esta en el catalogo configurado, o el catalogo no es una carpeta de GitHub.", id), "NOT_FOUND")
        val builder = modulePackageBuilder
            ?: throw CompanyProvisioningException(tr("Este servidor no puede revisar modulos: falta la carpeta compiler/ de la instalacion."), "BUILDER_UNAVAILABLE")
        return try {
            builder.inspect(id, source)
        } catch (failure: ModuleBuildException) {
            throw CompanyProvisioningException(failure.message ?: tr("No se pudo descargar el modulo."), "PACKAGE_UNAVAILABLE")
        }
    }

    /**
     * El dueno del servidor autoriza un modulo sin firma, identificado por su hash, con una confirmacion
     * escrita. Debe indicar el hash y el commit que reviso; si lo descargado ahora es otra cosa, se
     * rechaza. Un modulo firmado no necesita autorizacion y uno con una firma que no vale tampoco se
     * puede autorizar (igual que las extensiones).
     */
    fun authorizeUnsignedServerModule(
        moduleId: String,
        actor: AuditActor,
        confirmationText: String,
        expectedPackageSha256: String,
        expectedCommit: String,
    ): ModuleUnsignedAuthorizationResponse {
        val id = moduleId.trim()
        val text = confirmationText.trim()
        if (text.length < MinModuleConfirmationLength) {
            throw CompanyProvisioningException(tr("Escribe la confirmacion con tus palabras (al menos {0} caracteres).", MinModuleConfirmationLength), "CONFIRMATION_REQUIRED")
        }
        if (!Regex("[A-Fa-f0-9]{64}").matches(expectedPackageSha256.trim()) || !Regex("[a-f0-9]{40}").matches(expectedCommit.trim().lowercase())) {
            throw CompanyProvisioningException(tr("Indica el hash (64 hex) y el commit (40 hex) que revisaste."), "EXPECTED_REQUIRED")
        }
        val inspection = inspectServerModule(id)
        if (!inspection.packageSha256.equals(expectedPackageSha256.trim(), ignoreCase = true) ||
            !inspection.commit.equals(expectedCommit.trim(), ignoreCase = true)
        ) {
            throw CompanyProvisioningException(
                tr("El modulo cambio desde que lo revisaste (ahora: commit {0}, hash {1}). Revisalo otra vez.", inspection.commit, inspection.packageSha256),
                "PACKAGE_CHANGED",
            )
        }
        if (inspection.signed) {
            throw CompanyProvisioningException(tr("El modulo '{0}' esta firmado; no necesita autorizacion.", id), "ALREADY_SIGNED")
        }
        inspection.signatureProblem?.let {
            throw CompanyProvisioningException(tr("El modulo '{0}' trae una firma que no vale ({1}); no se puede autorizar.", id, it), "SIGNATURE_INVALID")
        }
        val builder = modulePackageBuilder
            ?: throw CompanyProvisioningException(tr("Este servidor no puede autorizar modulos: falta la carpeta compiler/ de la instalacion."), "BUILDER_UNAVAILABLE")
        builder.authorizeUnsigned(inspection.packageSha256, id, inspection.commit, actor.login, text)
        recordAudit(
            actor, "module.package.authorize_unsigned", null, id,
            kotlinx.serialization.json.buildJsonObject {
                put("moduleId", kotlinx.serialization.json.JsonPrimitive(id))
                put("packageSha256", kotlinx.serialization.json.JsonPrimitive(inspection.packageSha256))
                put("commit", kotlinx.serialization.json.JsonPrimitive(inspection.commit))
                put("sourceUrl", kotlinx.serialization.json.JsonPrimitive(inspection.commitUrl))
                put("confirmationText", kotlinx.serialization.json.JsonPrimitive(text))
            }.toString(),
        )
        return ModuleUnsignedAuthorizationResponse(id, inspection.packageSha256, inspection.commitUrl)
    }

    private fun activateLoadedModule(definition: ModuleDefinition) {
        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                upsertServerModule(connection, definition)
                if (!moduleRegistry.isInstalled(definition.id)) {
                    moduleRegistry.install(definition.id)
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

    /**
     * Turns a module on or off in the server workspace. Turning on the first module creates the
     * workspace with its own tenant database. Base modules are always on once it exists.
     */
    fun setServerModuleUsage(moduleId: String, enabled: Boolean, provisioningRole: String, actor: AuditActor): ServerModuleResponse {
        val definition = modules.singleOrNull { it.id == moduleId.trim() }
            ?: throw CompanyProvisioningException(tr("Modulo no reconocido o no instalado en el servidor."))
        if (enabled) {
            setModuleEnabled(ensureServerWorkspace(provisioningRole, actor), definition.id, true, provisioningRole, actor)
        } else {
            if (definition.locked) {
                throw CompanyProvisioningException(tr("{0} es un modulo base y siempre esta activo en el espacio del servidor.", trIn(I18n.moduleNamespace(definition.id), definition.displayName)))
            }
            serverWorkspaceCode()?.let { setModuleEnabled(it, definition.id, false, provisioningRole, actor) }
        }
        return moduleResponse(definition, null)
    }


    /**
     * Brings every company up to date with the migrations of the modules it uses: base modules for
     * all companies and optional modules where they are enabled. Runs at startup, so a module
     * version loaded after an installation or update applies its new migrations to the companies
     * that already had the module. Applied migrations are skipped (schema_migrations).
     */
    // "module/company" pairs whose module migrations failed in the last startup catch-up.
    private val failedModuleMigrations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Whether the startup catch-up could not apply [moduleId]'s migrations in [companyCode] (its code would run on an old schema). */
    fun moduleMigrationFailed(moduleId: String, companyCode: String): Boolean = "$moduleId/$companyCode" in failedModuleMigrations

    fun applyPendingModuleMigrations() {
        val provisioningRole = firstServerOwnerRole() ?: run {
            log.warn("No server owner found; pending module migrations were not applied.")
            return
        }
        val companies = central.connection.use { connection ->
            connection.prepareStatement("SELECT code, database_name FROM companies ORDER BY code").use { query ->
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
            }
        }
        companies.forEach { (companyCode, databaseName) ->
            val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return@forEach
            try {
                tenantSchema.tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { tenant ->
                    try {
                        tenantSchema.applyUserProfileMigration(tenant)
                    } catch (failure: Exception) {
                        log.error("User profile migration failed for company {}", companyCode, failure)
                    }
                    try {
                        tenantSchema.applyAccountThrottleMigration(tenant)
                    } catch (failure: Exception) {
                        log.error("Account throttle migration failed for company {}", companyCode, failure)
                    }
                    try {
                        tenantSchema.applyOutboundMigration(tenant)
                    } catch (failure: Exception) {
                        log.error("Outbound migration failed for company {}", companyCode, failure)
                    }
                    try {
                        tenantSchema.applyNotificationsMigration(tenant)
                    } catch (failure: Exception) {
                        log.error("Notifications migration failed for company {}", companyCode, failure)
                    }
                    try {
                        tenantSchema.applyExtensionsMigration(tenant)
                    } catch (failure: Exception) {
                        log.error("Extensions migration failed for company {}", companyCode, failure)
                    }
                    try {
                        tenantSchema.applyCompanyMailMigration(tenant)
                    } catch (failure: Exception) {
                        log.error("Company mail migration failed for company {}", companyCode, failure)
                    }
                    try {
                        tenantSchema.applyLanguageMigrations(tenant)
                    } catch (failure: Exception) {
                        log.error("Language migrations failed for company {}", companyCode, failure)
                    }
                    try {
                        tenantSchema.applyActivityLogMigration(tenant)
                    } catch (failure: Exception) {
                        log.error("Activity log migration failed for company {}", companyCode, failure)
                    }
                    try {
                        tenantSchema.applyTwoFactorMigration(tenant)
                    } catch (failure: Exception) {
                        log.error("Two-step verification migration failed for company {}", companyCode, failure)
                    }
                    try {
                        tenantSchema.applyAssistantMigration(tenant)
                    } catch (failure: Exception) {
                        log.error("Assistant migration failed for company {}", companyCode, failure)
                    }
                    tenantSchema.grantRuntimeDefaults(tenant)
                    val enabled = tenant.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled'").use { query ->
                        query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
                    }
                    moduleRegistry.definitions
                        .filter { (it.locked || it.id in enabled) && moduleRegistry.module(it.id) != null }
                        .forEach { definition ->
                            try {
                                tenantSchema.applyModuleMigrations(tenant, definition)
                                failedModuleMigrations.remove("${definition.id}/$companyCode")
                            } catch (failure: Exception) {
                                // Its jobs do not run in this company (ModuleJobScheduler) until a later catch-up applies them.
                                failedModuleMigrations.add("${definition.id}/$companyCode")
                                log.error("Migrations of module {} failed for company {}", definition.id, companyCode, failure)
                                installStates[definition.id] = ModuleInstallState(InstallStatus.Attention) {
                                    tr("Una migracion fallo en la empresa {0}: {1}", companyCode, failure.message)
                                }
                            }
                        }
                }
            } catch (failure: Exception) {
                log.error("Pending module migrations could not run for company {}", companyCode, failure)
            }
        }
    }

    internal fun firstServerOwnerRole(): String? = central.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT users.postgres_role
            FROM server_owners owners
            JOIN application_users users ON users.id = owners.user_id
            WHERE users.is_active
            ORDER BY users.postgres_role
            LIMIT 1
            """.trimIndent()
        ).use { query -> query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null } }
    }

    private fun buildAndInstall(definition: ModuleDefinition, builder: ModulePackageBuilder, source: GitHubLocation, requireAuthorization: Boolean) {
        val moduleId = definition.id
        try {
            val built = builder.build(moduleId, source, requireAuthorization) { step ->
                installStates[moduleId] = ModuleInstallState(InstallStatus.Installing) { tr(step) }
            }
            central.connection.use { connection ->
                upsertServerModule(
                    connection = connection,
                    definition = definition.copy(version = built.version),
                    packageUrl = source.archiveUrl,
                    packageSha256 = built.sha256,
                    installedPackagePath = built.jar.toString(),
                )
            }
            log.info("Module {} {} built from {}@{}; the server restarts to load it once nothing else is being built.", moduleId, built.version, source.repo, source.ref)
            installStates[moduleId] = ModuleInstallState(InstallStatus.Restarting) { tr("Compilado; el servidor se reinicia para cargar {0} cuando termine lo que queda en cola", trIn(I18n.moduleNamespace(definition.id), definition.displayName)) }
            finishBuild(moduleId, needsRestart = true)
        } catch (failure: ModuleBuildException) {
            log.warn("Module {} could not be built: {}", moduleId, failure.message)
            installStates[moduleId] = ModuleInstallState(InstallStatus.Failed, failure.message.orEmpty())
            finishBuild(moduleId, needsRestart = false)
        } catch (failure: Exception) {
            log.error("Module {} installation failed", moduleId, failure)
            installStates[moduleId] = ModuleInstallState(InstallStatus.Failed) { tr("Error inesperado: {0}", failure.message) }
            finishBuild(moduleId, needsRestart = false)
        }
    }

    // The build ended, well or not: it is not resumed, and the restart waits for the other builds in the queue.
    private fun finishBuild(moduleId: String, needsRestart: Boolean) {
        val queue = installQueue
        if (queue == null) {
            if (needsRestart) requestRestart()
            return
        }
        queue.done(PackageInstallQueue.Module, moduleId)
        queue.built(needsRestart)
    }

    /** Whether the module is being installed now or waits for the restart that loads it: its extensions wait for it. */
    fun moduleInstallPending(moduleId: String): Boolean =
        installStates[moduleId]?.status in setOf(InstallStatus.Installing, InstallStatus.Restarting) ||
            installQueue?.has(PackageInstallQueue.Module, moduleId) == true

    /**
     * Module installs that a restart interrupted (asked for while another package was restarting the
     * server, or cut while building) start again. Call it once at startup, off the main thread.
     */
    fun resumePendingInstalls() {
        val queue = installQueue ?: return
        queue.pending().filter { it.kind == PackageInstallQueue.Module }.forEach { entry ->
            if (!queue.attempt(entry.kind, entry.id)) {
                log.warn("Module {} is not installed again: it was tried {} times.", entry.id, PackageInstallQueue.MaxAttempts)
                installStates[entry.id] = ModuleInstallState(InstallStatus.Failed) { tr("No se pudo instalar tras varios intentos; vuelve a pedirlo.") }
                return@forEach
            }
            // The install runs with the current rights of who asked for it; without them it is dropped.
            val actor = queuedActors.current(entry.actor, com.ideasdeveloper.bms.server.admins.ServerPermission.ModulesInstall)
            if (actor == null) {
                dropQueuedInstall(entry.id, entry.actor)
                return@forEach
            }
            try {
                installServerModule(entry.id, actor)
                // Already loaded at the wanted version: there was nothing left to build.
                if (installStates[entry.id]?.status != InstallStatus.Installing) queue.done(entry.kind, entry.id)
            } catch (failure: Exception) {
                log.warn("Pending install of module {} was dropped: {}", entry.id, failure.message)
                installStates[entry.id] = ModuleInstallState(InstallStatus.Failed, failure.message ?: tr("No se pudo reanudar la instalacion."))
                queue.done(entry.kind, entry.id)
            }
        }
    }

    // A queued module install whose requester may no longer install modules: not resumed, and audited.
    private fun dropQueuedInstall(moduleId: String, requestedBy: String) {
        log.warn("Pending install of module {} was dropped: {} may no longer install modules.", moduleId, requestedBy)
        installStates[moduleId] = ModuleInstallState(InstallStatus.Failed) { tr("La instalacion pendiente se cancelo: quien la pidio ya no puede instalar modulos.") }
        installQueue?.done(PackageInstallQueue.Module, moduleId)
        runCatching { recordAudit(AuditActor.system(), "module.install_dropped", null, moduleId, auditDetails("requestedBy" to requestedBy, "reason" to "requester_not_allowed")) }
            .onFailure { log.error("Audit entry module.install_dropped could not be written", it) }
    }

    fun deleteServerModule(moduleId: String, actor: AuditActor) {
        val normalizedModuleId = moduleId.trim()
        val definition = moduleRegistry.definition(normalizedModuleId)
            ?: throw CompanyProvisioningException(tr("Modulo no reconocido o no instalado en el servidor."))
        if (definition.locked) {
            throw CompanyProvisioningException(tr("El modulo {0} es base y no puede eliminarse del servidor.", trIn(I18n.moduleNamespace(definition.id), definition.displayName)))
        }
        val activeCompanyCount = countCompaniesWithModuleEnabled(definition.id)
        if (activeCompanyCount > 0) {
            throw CompanyProvisioningException(tr("No se puede eliminar {0}; esta activo en {1} empresa(s) o en el espacio del servidor.", trIn(I18n.moduleNamespace(definition.id), definition.displayName), activeCompanyCount))
        }

        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM server_modules WHERE module_id = ? AND locked = FALSE").use { delete ->
                    delete.setString(1, definition.id)
                    if (delete.executeUpdate() != 1) {
                        throw CompanyProvisioningException(tr("No se pudo eliminar {0} del catalogo activo.", trIn(I18n.moduleNamespace(definition.id), definition.displayName)))
                    }
                }
                moduleRegistry.remove(definition.id)
                insertAudit(connection, actor, "module.removed", null, definition.id, auditDetails("version" to definition.version))
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
        installStates.remove(definition.id)
        // The loaded classes stay in memory until the next restart, but the routes reject the module.
        if (moduleRegistry.isPackaged(definition.id)) {
            modulePackagesRoot.resolve(definition.id).toFile().deleteRecursively()
        }
    }

    private fun ensureLockedServerModulesPersisted() {
        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                moduleRegistry.availableDefinitions
                    .filter { it.locked }
                    .forEach { definition ->
                        upsertServerModule(connection, definition)
                        if (!moduleRegistry.isInstalled(definition.id)) {
                            moduleRegistry.install(definition.id)
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

    private fun syncRemoteAvailableModules(): Map<String, RemoteModulePackage> {
        return remoteModuleCatalog.modules(moduleCatalogUrl()).associateBy { remotePackage ->
            moduleRegistry.registerAvailable(remotePackage.definition)
            remotePackage.definition.id
        }
    }

    private fun moduleCatalogUrl(): String {
        return try {
            central.connection.use { connection ->
                connection.prepareStatement("SELECT setting_value FROM server_settings WHERE setting_key = ?").use { query ->
                    query.setString(1, moduleCatalogSettingKey)
                    query.executeQuery().use { rows ->
                        if (rows.next()) rows.getString(1) else defaultCatalogUrl
                    }
                }
            }
        } catch (_: Exception) {
            defaultCatalogUrl
        }.trim()
    }

    private fun installedPackage(moduleId: String): InstalledModulePackage? = central.connection.use { connection ->
        connection.prepareStatement(
            "SELECT version, package_url, package_sha256, installed_package_path FROM server_modules WHERE module_id = ?"
        ).use { query ->
            query.setString(1, moduleId)
            query.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    InstalledModulePackage(
                        version = rows.getString("version"),
                        packageUrl = rows.getString("package_url"),
                        packageSha256 = rows.getString("package_sha256"),
                        installedPackagePath = rows.getString("installed_package_path"),
                    )
                }
            }
        }
    }

    private fun upsertServerModule(
        connection: Connection,
        definition: ModuleDefinition,
        packageUrl: String? = null,
        packageSha256: String? = null,
        installedPackagePath: String? = null,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO server_modules (
                module_id, display_name, description, version, locked, package_url, package_sha256, installed_package_path
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (module_id)
            DO UPDATE SET
                display_name = EXCLUDED.display_name,
                description = EXCLUDED.description,
                version = EXCLUDED.version,
                locked = server_modules.locked OR EXCLUDED.locked,
                package_url = COALESCE(EXCLUDED.package_url, server_modules.package_url),
                package_sha256 = COALESCE(EXCLUDED.package_sha256, server_modules.package_sha256),
                installed_package_path = COALESCE(EXCLUDED.installed_package_path, server_modules.installed_package_path)
            """.trimIndent()
        ).use { upsert ->
            upsert.setString(1, definition.id)
            upsert.setString(2, definition.displayName)
            upsert.setString(3, definition.description)
            upsert.setString(4, definition.version)
            upsert.setBoolean(5, definition.locked)
            upsert.setString(6, packageUrl)
            upsert.setString(7, packageSha256)
            upsert.setString(8, installedPackagePath)
            upsert.executeUpdate()
        }
    }

    internal fun clearFailedModuleMigration(moduleId: String, companyCode: String) {
        failedModuleMigrations.remove("$moduleId/$companyCode")
    }

    private fun recordAudit(actor: AuditActor, action: String, companyCode: String?, moduleId: String?, details: String) {
        central.connection.use { connection -> insertAudit(connection, actor, action, companyCode, moduleId, details) }
    }

    private fun insertAudit(
        connection: Connection,
        actor: AuditActor,
        action: String,
        companyCode: String?,
        moduleId: String?,
        details: String,
    ) = ProvisioningAudit.insert(connection, actor, action, companyCode, moduleId, details)

    private data class InstalledModulePackage(
        val version: String,
        val packageUrl: String?,
        val packageSha256: String?,
        val installedPackagePath: String?,
    )

}

private object InstallStatus {
    const val Installing = "installing"
    const val Restarting = "restarting"
    const val Failed = "failed"
    // Installed and loaded, but something needs the server owner's attention (e.g. a migration).
    const val Attention = "attention"
}

/**
 * State of a module install shown to the server owner. The message is rendered when it is shown, in the
 * viewer's language ([text] runs then); install work runs in the background, without a request language.
 */
private class ModuleInstallState(val status: String, private val text: () -> String) {
    constructor(status: String, message: String) : this(status, { message })

    val message: String get() = text()
}
