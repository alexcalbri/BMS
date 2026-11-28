package com.ideasdeveloper.bms.server.extensions

import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.trIn
import com.ideasdeveloper.bms.server.modules.NotificationText
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.security.SecretBox
import com.ideasdeveloper.bms.server.security.SecretsUnavailableException
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** Writes provisioning_audit_log (CompanyProvisioningService.insertAudit); details is a JSON object. */
typealias ExtensionAuditor = (actor: AuditActor, action: String, companyCode: String?, moduleId: String?, details: String) -> Unit

/**
 * Extensions per company (PLAN_CAMBIOS_2.md 11.3): list, install, configure, enable, disable,
 * uninstall, mark incompatible and apply to the companies of a linked group. Methods use blocking
 * JDBC; call them from Dispatchers.IO. Secrets are sealed on write and never returned.
 */
class ExtensionService(
    private val connections: ExtensionConnections,
    private val storage: ExtensionStorage,
    private val registry: ExtensionRegistry,
    private val modules: ModuleRegistry,
    private val notifier: ExtensionNotifier,
    private val auditor: ExtensionAuditor,
    private val clock: () -> Instant = Instant::now,
    private val migrationRunner: ExtensionMigrationRunner = ExtensionMigrationRunner { _, _ -> },
    private val operations: ExtensionOperationStore = JdbcExtensionOperationStore(connections),
) {
    private val log = LoggerFactory.getLogger(ExtensionService::class.java)
    private val secretBox: SecretBox get() = storage.secrets
    private val groupOperations = ExtensionGroupOperations(this, connections, storage, modules, operations)
    private val schemaSync = ExtensionSchemaSync(this, connections, storage, registry, clock, migrationRunner)

    // ---- Company extensions ----

    fun list(companyCode: String, actor: ExtensionActor): ExtensionListResponse {
        authorize(actor, companyCode)
        return withTenant(companyCode) { tenant ->
            val rows = storage.rows(tenant)
            val problems = storage.schemaRows(tenant).associate { it.row.extensionId to it.schemaError }
            val enabledModules = enabledModules(tenant)
            val extensions = registry.all.filter { modules.isInstalled(it.manifest.module) }.map { extension ->
                val manifest = extension.manifest
                val row = rows[manifest.id]?.takeIf { it.status != ExtensionStatus.Removed }
                val views = if (row != null) settingViews(tenant, manifest) else emptyList()
                ExtensionSummary(
                    id = manifest.id,
                    module = manifest.module,
                    displayName = trIn(I18n.extensionNamespace(manifest.id), manifest.displayName),
                    description = trIn(I18n.extensionNamespace(manifest.id), manifest.description),
                    version = manifest.version,
                    minModuleVersion = manifest.minModuleVersion,
                    sourceType = manifest.source.type,
                    permissions = manifest.permissions,
                    uninstallOptions = manifest.uninstall.options.map { it.copy(label = trIn(I18n.extensionNamespace(manifest.id), it.label)) },
                    status = row?.status,
                    moduleInstalled = true,
                    moduleEnabled = manifest.module in enabledModules,
                    compatible = compatible(manifest),
                    settings = views,
                    missingRequired = if (row != null) missingRequired(manifest, views) else emptyList(),
                    problem = if (row != null) problems[manifest.id] else null,
                )
            }
            ExtensionListResponse(companyCode, extensions)
        }
    }

    fun install(companyCode: String, extensionId: String, actor: ExtensionActor): ExtensionSummary {
        authorize(actor, companyCode)
        val extension = requireExtension(extensionId)
        installOne(companyCode, extension, actor)
        audit(actor.audit, "extension.install", companyCode, extension.manifest.module, details("extension" to extensionId))
        return summary(companyCode, extension)
    }

    fun configure(companyCode: String, extensionId: String, values: Map<String, String>, actor: ExtensionActor): ExtensionSummary {
        authorize(actor, companyCode)
        val extension = requireExtension(extensionId)
        val manifest = extension.manifest
        val definitions = manifest.settings.associateBy { it.key }
        values.keys.firstOrNull { it !in definitions }?.let {
            throw ExtensionException("unknown_setting", tr("El ajuste '{0}' no existe en esta extension.", it), ExtensionException.Kind.BadRequest)
        }
        // A secret sent back as the mask means "unchanged".
        val changes = values.filterNot { (key, value) -> definitions.getValue(key).secret && value == SecretBox.Mask }
        if (changes.any { (key, value) -> definitions.getValue(key).secret && value.isNotEmpty() } && !secretBox.available) {
            throw ExtensionException("SECRETS_UNAVAILABLE", tr("El servidor no tiene BMS_MASTER_KEY configurada: no se pueden guardar secretos."), ExtensionException.Kind.Unavailable)
        }
        withTenant(companyCode) { tenant ->
            try {
                tenant.inTransaction {
                    // The row stays locked until the commit, so a concurrent uninstall or enable waits.
                    val row = requireLocked(tenant, extensionId)
                    val current = storage.settings(tenant, extensionId).all(includeSecrets = true)
                    val merged = current.toMutableMap().also { map ->
                        changes.forEach { (key, value) -> if (value.isEmpty()) map.remove(key) else map[key] = value }
                    }
                    extension.validateSettings(merged)?.let { throw ExtensionException("invalid_settings", it, ExtensionException.Kind.BadRequest) }
                    if (row.status == ExtensionStatus.Enabled) {
                        manifest.settings.firstOrNull { it.required && merged[it.key].isNullOrBlank() }?.let {
                            throw ExtensionException("missing_required_settings", tr("El ajuste '{0}' es obligatorio mientras la extension esta activa.", it.key), ExtensionException.Kind.BadRequest)
                        }
                    }
                    changes.forEach { (key, value) ->
                        if (value.isEmpty()) storage.deleteSetting(tenant, extensionId, key)
                        else storage.putSetting(tenant, extensionId, key, value, definitions.getValue(key).secret)
                    }
                    recordActivity(
                        tenant, actor, extension, com.ideasdeveloper.bms.server.activity.ActivityActions.SettingsChanged,
                        "Cambio los ajustes de la extension ${manifest.displayName}.",
                        changes.keys.sorted().map { key ->
                            val definition = definitions.getValue(key)
                            // Only settings the manifest marks as plain show their values (and even then not when they look secret).
                            if (!definition.valueLoggable) com.ideasdeveloper.bms.server.activity.ActivityChange.secret(key, definition.label)
                            else com.ideasdeveloper.bms.server.activity.ActivityChange.of(key, definition.label, current[key], merged[key])
                        },
                    )
                }
            } catch (_: SecretsUnavailableException) {
                throw ExtensionException("SECRETS_UNAVAILABLE", tr("El servidor no tiene BMS_MASTER_KEY configurada: no se pueden guardar secretos."), ExtensionException.Kind.Unavailable)
            }
        }
        // Only the names of the changed settings are recorded, never their values.
        audit(actor.audit, "extension.configure", companyCode, manifest.module, details("extension" to extensionId, keys = changes.keys.sorted()))
        return summary(companyCode, extension)
    }

    fun enable(companyCode: String, extensionId: String, actor: ExtensionActor): ExtensionSummary {
        authorize(actor, companyCode)
        val extension = requireExtension(extensionId)
        enableOne(companyCode, extension, actor)
        audit(actor.audit, "extension.enable", companyCode, extension.manifest.module, details("extension" to extensionId))
        return summary(companyCode, extension)
    }

    fun disable(companyCode: String, extensionId: String, actor: ExtensionActor): ExtensionSummary {
        authorize(actor, companyCode)
        val extension = requireExtension(extensionId)
        withTenant(companyCode) { tenant ->
            tenant.inTransaction {
                val row = requireLocked(tenant, extensionId)
                if (row.status != ExtensionStatus.Disabled) {
                    ExtensionLifecycle.require(row.status, ExtensionStatus.Disabled)
                    setStatus(tenant, extensionId, ExtensionStatus.Disabled)
                    recordActivity(tenant, actor, extension, com.ideasdeveloper.bms.server.activity.ActivityActions.Disabled, "Desactivo la extension ${extension.manifest.displayName}.")
                }
            }
        }
        audit(actor.audit, "extension.disable", companyCode, extension.manifest.module, details("extension" to extensionId))
        return summary(companyCode, extension)
    }

    /** What an uninstall would do in the company; reads only. */
    fun uninstallPreview(companyCode: String, extensionId: String, actor: ExtensionActor): UninstallPreviewResponse {
        authorize(actor, companyCode)
        val extension = requireExtension(extensionId)
        return withTenant(companyCode) { tenant ->
            requireInstalled(tenant, extensionId)
            tenant.isReadOnly = true
            UninstallPreviewResponse(extensionId, companyCode, previewItems(tenant, companyCode, extension), UninstallChoices.views(extension.manifest.uninstall.options, I18n.extensionNamespace(extension.manifest.id)))
        }
    }

    fun uninstall(companyCode: String, extensionId: String, answers: Map<String, String>, actor: ExtensionActor) {
        authorize(actor, companyCode)
        val extension = requireExtension(extensionId)
        val choices = UninstallChoices.resolve(extension.manifest.uninstall.options, answers)
        uninstallOne(companyCode, extension, choices, actor)
        audit(actor.audit, "extension.uninstall", companyCode, extension.manifest.module, details("extension" to extensionId, choices = choices))
    }

    /**
     * After a module update: every extension of [moduleId] whose minimum is above [newVersion]
     * becomes incompatible in each company and the business owner is told. Returns how many
     * company/extension pairs changed. A company that fails is logged and skipped.
     */
    fun markIncompatible(moduleId: String, newVersion: String): Int {
        val affected = registry.forModule(moduleId).filterNot { ExtensionLifecycle.isCompatible(it.manifest.minModuleVersion, newVersion) }
        if (affected.isEmpty()) return 0
        var changed = 0
        for (company in activeCompanyCodes()) {
            try {
                val tenant = connections.tenant(company) ?: continue
                tenant.use {
                    val rows = storage.rows(it)
                    for (extension in affected) {
                        val row = rows[extension.manifest.id] ?: continue
                        if (row.status !in setOf(ExtensionStatus.Installed, ExtensionStatus.Enabled, ExtensionStatus.Disabled)) continue
                        setStatus(it, row.extensionId, ExtensionStatus.Incompatible)
                        changed++
                        notifyBusinessOwner(it, moduleId) {
                            NotificationText(
                                tr("Extension incompatible: {0}", trIn(I18n.extensionNamespace(extension.manifest.id), extension.manifest.displayName)),
                                tr("El modulo ahora es la version {0} y la extension necesita {1}. Esta desactivada hasta que se instale una version compatible.", newVersion, extension.manifest.minModuleVersion),
                            )
                        }
                        audit(AuditActor.system(), "extension.incompatible", company, moduleId, details("extension" to row.extensionId, "moduleVersion" to newVersion))
                    }
                }
            } catch (failure: Exception) {
                log.warn("Extensions of company {} not checked after updating module {}: {}", company, moduleId, failure.message)
            }
        }
        return changed
    }

    // ---- Schema of each extension per company (EXTENSIONS.md, Updating a package) ----

    /** What one [syncSchemas] did: rows brought to the loaded version, rows suspended, companies it could not read. */
    data class SchemaSyncReport(val updated: Int, val suspended: Int, val unreachable: List<String>)

    /**
     * Brings the schema of every loaded extension to its loaded version in each active company where it is installed (any
     * status but removed, whether or not its module is enabled there). Updating a package (or a module with bundled
     * extensions, or the Core) only replaces the code; this applies the new version's migrations through the provisioning
     * login and then records the version in tenant_extensions. Until then the gate and ExtensionAccess treat the row as
     * incompatible. A company where the migrations fail gets the extension suspended (status incompatible, the error and
     * the previous status kept in tenant V017, its business owner told, one log line); the others go on. Runs at startup
     * after the module migrations, before the module jobs, and then every hour: a suspended row is retried each time
     * and gets its previous status back once its migrations apply. Never throws.
     */
    fun syncSchemas(): SchemaSyncReport = schemaSync.syncSchemas()

    // ---- Linked groups ----

    fun group(companyCode: String, actor: ExtensionActor): GroupResponse = groupOperations.group(companyCode, actor)

    /** Server owner: puts [companyCode] in the group of [targetCode]; both need the same business owner email. */
    fun linkGroup(companyCode: String, targetCode: String, actor: ExtensionActor): GroupResponse = groupOperations.linkGroup(companyCode, targetCode, actor)

    fun unlinkGroup(companyCode: String, actor: ExtensionActor): GroupResponse = groupOperations.unlinkGroup(companyCode, actor)

    /** Server owner: accepts the current business owner email of a suspended company and lifts the suspension. */
    fun reviewSuspension(companyCode: String, actor: ExtensionActor): GroupResponse = groupOperations.reviewSuspension(companyCode, actor)

    /**
     * Records the business owner email of a company as its group baseline (call it when a company is
     * created in a group or linked). A company with no baseline yet gets one on its first check.
     */
    fun recordGroupOwner(companyCode: String) = groupOperations.recordGroupOwner(companyCode)

    /**
     * Compares the business owner email of the company now with the one recorded for its group and
     * suspends it in its group when they differ. True when the company may take part in group
     * operations. Call it before every apply.
     */
    fun checkGroupOwner(companyCode: String): Boolean = groupOperations.checkGroupOwner(companyCode)

    fun previewGroupOperation(sourceCompany: String, extensionId: String, request: GroupOperationRequest, actor: ExtensionActor): GroupOperationPreview = groupOperations.previewGroupOperation(sourceCompany, extensionId, request, actor)

    /** Runs the operation on each target in its own transaction; a failed target does not stop the others. */
    fun applyToGroup(sourceCompany: String, extensionId: String, request: GroupOperationRequest, actor: ExtensionActor): GroupOperationResult = groupOperations.applyToGroup(sourceCompany, extensionId, request, actor)

    /** Retries only the failed targets of an operation, inside that same operation. */
    fun retryFailed(sourceCompany: String, extensionId: String, operationId: Long, request: GroupOperationRequest, actor: ExtensionActor): GroupOperationResult = groupOperations.retryFailed(sourceCompany, extensionId, operationId, request, actor)

    /**
     * Call it at startup: operations a restart left running end as failed, and their pending targets
     * as failed ("interrumpida"), so the actor can retry them. Returns how many operations it closed.
     */
    fun recoverInterrupted(): Int = groupOperations.recoverInterrupted()

    fun result(operationId: Long): GroupOperationResult = groupOperations.result(operationId)

    // ---- Steps shared by single-company and group operations ----

    internal fun installOne(companyCode: String, extension: ServerExtension, actor: ExtensionActor?) {
        val manifest = extension.manifest
        if (!modules.isInstalled(manifest.module)) {
            throw ExtensionException("module_not_installed", tr("El modulo {0} no esta instalado en el servidor.", manifest.module))
        }
        if (!compatible(manifest)) {
            throw ExtensionException("extension_incompatible", tr("La extension necesita el modulo {0} {1} o superior.", manifest.module, manifest.minModuleVersion))
        }
        withTenant(companyCode) { tenant ->
            if (manifest.module !in enabledModules(tenant)) {
                throw ExtensionException("module_disabled", tr("El modulo {0} no esta activo en la empresa.", manifest.module))
            }
            // Installing again is harmless: pending migrations still run, the state is kept (and the row records that the
            // company's schema is now at this version).
            migrationRunner.run(companyCode, extension)
            tenant.inTransaction {
                val row = storage.rowForUpdate(tenant, manifest.id)
                if (row != null && row.status != ExtensionStatus.Removed) {
                    schemaSync.markSchemaCurrent(tenant, extension)
                    return@inTransaction
                }
                ExtensionLifecycle.require(row?.status, ExtensionStatus.Installed)
                // Only a removed row is overwritten: an enabled extension is never taken back to "installed".
                tenant.prepareStatement(
                    """
                    INSERT INTO tenant_extensions (extension_id, module_id, version, status, installed_by, installed_at, updated_at)
                    VALUES (?, ?, ?, 'installed', ?, ?, ?)
                    ON CONFLICT (extension_id) DO UPDATE
                    SET module_id = EXCLUDED.module_id, version = EXCLUDED.version, status = 'installed', installed_by = EXCLUDED.installed_by,
                        installed_at = EXCLUDED.installed_at, updated_at = EXCLUDED.updated_at, enabled_at = NULL
                    WHERE tenant_extensions.status = 'removed'
                    """.trimIndent()
                ).use { statement ->
                    val now = Timestamp.from(clock())
                    statement.setString(1, manifest.id)
                    statement.setString(2, manifest.module)
                    statement.setString(3, manifest.version)
                    statement.setString(4, actor?.role)
                    statement.setTimestamp(5, now)
                    statement.setTimestamp(6, now)
                    statement.executeUpdate()
                }
                recordActivity(tenant, actor, extension, com.ideasdeveloper.bms.server.activity.ActivityActions.Installed, "Instalo la extension ${manifest.displayName} ${manifest.version}.")
            }
        }
    }

    internal fun enableOne(companyCode: String, extension: ServerExtension, actor: ExtensionActor?) {
        val manifest = extension.manifest
        withTenant(companyCode) { tenant ->
            // The code of this version only runs on a schema at this version: pending migrations first (a suspended
            // extension is retried here), and a failure leaves it as it was.
            val current = storage.schemaRows(tenant).firstOrNull { it.row.extensionId == manifest.id }
            if (current != null && current.row.status != ExtensionStatus.Removed && schemaSync.schemaBehind(current, extension, storage.appliedMigrations(tenant))) {
                try {
                    migrationRunner.run(companyCode, extension)
                } catch (failure: Exception) {
                    log.warn("Extension {} not enabled in company {}: its migrations failed: {}", manifest.id, companyCode, failure.message)
                    throw ExtensionException(
                        "extension_migration_failed",
                        tr("No se pudieron aplicar las migraciones de la extension {0} {1}: {2}", manifest.displayName, manifest.version, failure.message.orEmpty().take(300)),
                    )
                }
                tenant.inTransaction { schemaSync.markSchemaCurrent(tenant, extension) }
            }
            tenant.inTransaction {
                val row = requireLocked(tenant, manifest.id)
                if (row.status == ExtensionStatus.Enabled) return@inTransaction
                ExtensionLifecycle.require(row.status, ExtensionStatus.Enabled)
                if (!compatible(manifest)) {
                    throw ExtensionException("extension_incompatible", tr("La extension necesita el modulo {0} {1} o superior.", manifest.module, manifest.minModuleVersion))
                }
                if (manifest.module !in enabledModules(tenant)) {
                    throw ExtensionException("module_disabled", tr("El modulo {0} no esta activo en la empresa.", manifest.module))
                }
                val missing = missingRequired(manifest, settingViews(tenant, manifest))
                if (missing.isNotEmpty()) {
                    throw ExtensionException("missing_required_settings", tr("Faltan ajustes obligatorios: {0}.", missing.joinToString(", ")))
                }
                extension.onEnable(ExtensionContext(companyCode, tenant, manifest.id, storage.settings(tenant, manifest.id), activityOf(actor)))
                setStatus(tenant, manifest.id, ExtensionStatus.Enabled)
                recordActivity(tenant, actor, extension, com.ideasdeveloper.bms.server.activity.ActivityActions.Enabled, "Activo la extension ${manifest.displayName}.")
            }
        }
    }

    internal fun uninstallOne(companyCode: String, extension: ServerExtension, choices: Map<String, String>, actor: ExtensionActor?) {
        val manifest = extension.manifest
        withTenant(companyCode) { tenant ->
            // One transaction: an extension that throws leaves settings, objects and status as they were.
            tenant.inTransaction {
                requireLocked(tenant, manifest.id)
                val context = ExtensionContext(companyCode, tenant, manifest.id, storage.settings(tenant, manifest.id), activityOf(actor))
                // What the choices are about to remove, counted by the extension before it does (stored text is Spanish).
                // A savepoint keeps a failing count from spoiling the uninstall itself.
                val savepoint = tenant.setSavepoint()
                val preview = try {
                    com.ideasdeveloper.bms.server.i18n.I18n.withLanguage(com.ideasdeveloper.bms.server.i18n.I18n.Source) { extension.uninstallPreview(context) }
                        .also { runCatching { tenant.releaseSavepoint(savepoint) } }
                } catch (failure: Exception) {
                    log.warn("Uninstall preview of extension {} failed before uninstalling: {}", manifest.id, failure.javaClass.simpleName)
                    runCatching { tenant.rollback(savepoint) }
                    emptyList()
                }
                extension.uninstall(context, choices)
                tenant.prepareStatement("DELETE FROM tenant_extension_settings WHERE extension_id = ?").use {
                    it.setString(1, manifest.id)
                    it.executeUpdate()
                }
                // Objects of an option that was kept stay registered; the rest are gone with the extension.
                val kept = choices.filterValues { it == UninstallDefault.Keep }.keys.toList()
                val keepClause = if (kept.isEmpty()) "" else " AND (option_id IS NULL OR option_id NOT IN (${kept.joinToString(",") { "?" }}))"
                tenant.prepareStatement("DELETE FROM tenant_extension_objects WHERE extension_id = ?$keepClause").use {
                    it.setString(1, manifest.id)
                    kept.forEachIndexed { index, option -> it.setString(index + 2, option) }
                    it.executeUpdate()
                }
                setStatus(tenant, manifest.id, ExtensionStatus.Removed)
                val (summary, changes) = UninstallSummary.of(manifest.displayName, preview, choices)
                recordActivity(tenant, actor, extension, com.ideasdeveloper.bms.server.activity.ActivityActions.Uninstalled, summary, changes)
            }
        }
        // After the commit; what lives outside the company's database is released here, best effort.
        try {
            extension.afterUninstall(companyCode)
        } catch (failure: Exception) {
            log.error("Cleanup after uninstalling extension {} from company {} failed: {}", manifest.id, companyCode, failure.javaClass.simpleName)
        }
    }

    private fun previewItems(tenant: Connection, companyCode: String, extension: ServerExtension): List<UninstallItem> {
        val manifest = extension.manifest
        val settings = storage.storedSettings(tenant, manifest.id).size
        val own = extension.uninstallPreview(ExtensionContext(companyCode, tenant, manifest.id, storage.settings(tenant, manifest.id)))
        return listOf(UninstallItem(null, tr("Ajustes de la extension"), settings, UninstallDefault.Delete)) + own
    }

    // Each owner reads it in their own language.
    internal fun notifyBusinessOwner(tenant: Connection, moduleId: String, build: () -> NotificationText) {
        val owners = tenant.prepareStatement("SELECT user_id FROM business_owner").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
        owners.forEach { notifier.notify(tenant, moduleId, it, build) }
    }

    // ---- Helpers ----

    internal fun authorize(actor: ExtensionActor, companyCode: String) {
        if (actor.serverOwner) return
        actor.serverAdmin?.let { scope ->
            if (!scope.includes(companyCode)) {
                throw ExtensionException("forbidden", tr("No puedes administrar las extensiones de esta empresa."), ExtensionException.Kind.Forbidden)
            }
            return
        }
        if (actor.companyCode == null || actor.companyCode != companyCode) {
            throw ExtensionException("forbidden", tr("No puedes administrar las extensiones de esta empresa."), ExtensionException.Kind.Forbidden)
        }
    }

    internal fun requireServerOwner(actor: ExtensionActor) {
        if (!actor.serverOwner) throw ExtensionException("server_owner_required", tr("Solo el dueno del servidor puede hacerlo."), ExtensionException.Kind.Forbidden)
    }

    internal fun requireExtension(extensionId: String): ServerExtension =
        registry.get(extensionId) ?: throw ExtensionException("extension_not_found", tr("La extension no existe en este servidor."), ExtensionException.Kind.NotFound)

    internal fun requireOperation(operation: String): String {
        if (operation != GroupOperationType.Apply && operation != GroupOperationType.Uninstall) {
            throw ExtensionException("invalid_operation", tr("La operacion debe ser apply o uninstall."), ExtensionException.Kind.BadRequest)
        }
        return operation
    }

    // Inside a transaction: the row of the extension, locked until commit.
    internal fun requireLocked(tenant: Connection, extensionId: String): TenantExtensionRow =
        storage.rowForUpdate(tenant, extensionId)?.takeIf { it.status != ExtensionStatus.Removed }
            ?: throw ExtensionException("extension_not_installed", tr("La extension no esta instalada en esta empresa."), ExtensionException.Kind.NotFound)

    // A failed audit of an owner's, business owner's or the system's change is logged, not thrown.
    // An administrator's action is never left unaudited silently: the failure reaches the caller.
    internal fun audit(actor: AuditActor, action: String, companyCode: String?, moduleId: String?, details: String) {
        try {
            auditor(actor, action, companyCode, moduleId, details)
        } catch (failure: Exception) {
            log.error("Audit entry {} could not be written: {}", action, failure.javaClass.simpleName)
            if (actor.kind == AuditActor.ServerAdminKind) throw failure
        }
    }

    internal fun requireInstalled(tenant: Connection, extensionId: String): TenantExtensionRow =
        storage.row(tenant, extensionId)?.takeIf { it.status != ExtensionStatus.Removed }
            ?: throw ExtensionException("extension_not_installed", tr("La extension no esta instalada en esta empresa."), ExtensionException.Kind.NotFound)

    internal fun compatible(manifest: ExtensionManifest) = ExtensionLifecycle.isCompatible(manifest.minModuleVersion, modules.loadedVersion(manifest.module))

    private fun missingRequired(manifest: ExtensionManifest, views: List<ExtensionSettingView>): List<String> {
        val set = views.filter { it.isSet }.map { it.key }.toSet()
        return manifest.settings.filter { it.required && it.key !in set }.map { it.key }
    }

    private fun settingViews(tenant: Connection, manifest: ExtensionManifest): List<ExtensionSettingView> {
        val stored = storage.storedSettings(tenant, manifest.id).associateBy { it.key }
        return manifest.settings.map { definition ->
            val value = stored[definition.key]
            val shown = when {
                value == null -> ""
                definition.secret || value.secret -> SecretBox.mask(value.value)
                else -> value.value
            }
            ExtensionSettingView(definition.key, trIn(I18n.extensionNamespace(manifest.id), definition.label), definition.secret, definition.required, trIn(I18n.extensionNamespace(manifest.id), definition.help), definition.scope, shown, value != null && value.value.isNotEmpty())
        }
    }

    private fun summary(companyCode: String, extension: ServerExtension): ExtensionSummary =
        withTenant(companyCode) { tenant ->
            val manifest = extension.manifest
            val row = storage.row(tenant, manifest.id)?.takeIf { it.status != ExtensionStatus.Removed }
            val views = if (row != null) settingViews(tenant, manifest) else emptyList()
            ExtensionSummary(
                manifest.id, manifest.module, trIn(I18n.extensionNamespace(manifest.id), manifest.displayName), trIn(I18n.extensionNamespace(manifest.id), manifest.description), manifest.version, manifest.minModuleVersion,
                manifest.source.type, manifest.permissions, manifest.uninstall.options.map { it.copy(label = trIn(I18n.extensionNamespace(manifest.id), it.label)) }, row?.status, modules.isInstalled(manifest.module),
                manifest.module in enabledModules(tenant), compatible(manifest), views, if (row != null) missingRequired(manifest, views) else emptyList(),
            )
        }

    internal fun enabledModules(tenant: Connection): Set<String> =
        tenant.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled'").use { query ->
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }

    // One row of the company's activity log, in the transaction of the change (ACTIVITY_LOG.md §3).
    internal fun recordActivity(tenant: Connection, actor: ExtensionActor?, extension: ServerExtension, action: String, summary: String, changes: List<com.ideasdeveloper.bms.server.activity.ActivityChange> = emptyList()) {
        com.ideasdeveloper.bms.server.activity.CoreActivity.record(
            tenant, actor?.activityActor ?: com.ideasdeveloper.bms.server.activity.ActivityActor.system(), "extension", extension.manifest.id,
            extension.manifest.displayName, action, summary, changes,
        )
    }

    private fun activityOf(actor: ExtensionActor?): com.ideasdeveloper.bms.server.activity.ActivityActor =
        actor?.activityActor ?: com.ideasdeveloper.bms.server.activity.ActivityActor.system()

    internal fun setStatus(tenant: Connection, extensionId: String, status: String) {
        tenant.prepareStatement(
            "UPDATE tenant_extensions SET status = ?, updated_at = ?, enabled_at = CASE WHEN ? = 'enabled' THEN ? ELSE enabled_at END WHERE extension_id = ?"
        ).use { statement ->
            val now = Timestamp.from(clock())
            statement.setString(1, status)
            statement.setTimestamp(2, now)
            statement.setString(3, status)
            statement.setTimestamp(4, now)
            statement.setString(5, extensionId)
            statement.executeUpdate()
        }
    }

    internal fun activeCompanyCodes(): List<String> = connections.central().use { central ->
        central.prepareStatement("SELECT code FROM companies WHERE is_active = TRUE AND NOT is_server_workspace ORDER BY code").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    internal fun <T> withTenant(companyCode: String, block: (Connection) -> T): T {
        val tenant = connections.tenant(companyCode)
            ?: throw ExtensionException("company_not_found", tr("La empresa no esta activa."), ExtensionException.Kind.NotFound)
        return tenant.use(block)
    }

    internal fun details(vararg pairs: Pair<String, String>, keys: List<String>? = null, choices: Map<String, String>? = null): String =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, value) }
            keys?.let { put("keys", JsonArray(it.map { name -> JsonPrimitive(name) })) }
            choices?.let { map -> put("choices", buildJsonObject { map.forEach { (key, value) -> put(key, value) } }) }
        }.toString()

    companion object {
        const val MaxGroupTargets = 50
    }
}
