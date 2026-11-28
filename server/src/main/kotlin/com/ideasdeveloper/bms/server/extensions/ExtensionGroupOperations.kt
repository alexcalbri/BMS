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

/** Linked groups of companies and the group operations of [ExtensionService] (apply, uninstall, retry); owns the operation runner. */
internal class ExtensionGroupOperations(
    private val service: ExtensionService,
    private val connections: ExtensionConnections,
    private val storage: ExtensionStorage,
    private val modules: ModuleRegistry,
    private val operations: ExtensionOperationStore,
) {
    private val runner = OperationRunner(operations)

    fun group(companyCode: String, actor: ExtensionActor): GroupResponse {
        service.authorize(actor, companyCode)
        // A scoped administrator does not see the companies of the group outside their scope.
        val members = groupMembers(companyCode).filter { actor.serverAdmin?.includes(it.code) ?: true }
        return GroupResponse(companyCode, members.map { member ->
            GroupMemberView(member.code, member.name, member.suspended, ownerEmailMatches = !evaluateGroupOwner(member.code, persist = false).suspendedNow)
        })
    }

    /** Server owner: puts [companyCode] in the group of [targetCode]; both need the same business owner email. */
    fun linkGroup(companyCode: String, targetCode: String, actor: ExtensionActor): GroupResponse {
        service.requireServerOwner(actor)
        if (companyCode == targetCode) throw ExtensionException("invalid_group", tr("Elige otra empresa para vincular."), ExtensionException.Kind.BadRequest)
        val (company, target) = connections.central().use { central -> companyRow(central, companyCode) to companyRow(central, targetCode) }
        company?.takeUnless { it.serverWorkspace } ?: throw ExtensionException("company_not_found", tr("Empresa no encontrada."), ExtensionException.Kind.NotFound)
        target?.takeUnless { it.serverWorkspace } ?: throw ExtensionException("company_not_found", tr("La empresa a vincular no existe."), ExtensionException.Kind.NotFound)
        val email = ownerEmailOf(companyCode)
        if (email.isNullOrBlank() || !email.equals(ownerEmailOf(targetCode), ignoreCase = true)) {
            throw ExtensionException("different_business_owner", tr("Las empresas deben tener el mismo correo de business owner para vincularse."))
        }
        connections.central().use { central ->
            central.inTransaction {
                central.prepareStatement(
                    "UPDATE companies SET owner_group = (SELECT owner_group FROM companies WHERE code = ?), group_owner_email = ?, group_suspended = FALSE WHERE code = ?"
                ).use { update ->
                    update.setString(1, targetCode)
                    update.setString(2, email.lowercase())
                    update.setString(3, companyCode)
                    update.executeUpdate()
                }
                // The company it joined may predate group_owner_email: record the baseline there too.
                central.prepareStatement("UPDATE companies SET group_owner_email = ? WHERE code = ? AND group_owner_email IS NULL").use { update ->
                    update.setString(1, email.lowercase())
                    update.setString(2, targetCode)
                    update.executeUpdate()
                }
            }
        }
        service.audit(actor.audit, "extension.group.linked", companyCode, null, service.details("linkedTo" to targetCode))
        return group(companyCode, actor)
    }

    fun unlinkGroup(companyCode: String, actor: ExtensionActor): GroupResponse {
        service.requireServerOwner(actor)
        connections.central().use { central ->
            companyRow(central, companyCode)?.takeUnless { it.serverWorkspace } ?: throw ExtensionException("company_not_found", tr("Empresa no encontrada."), ExtensionException.Kind.NotFound)
            central.inTransaction {
                central.prepareStatement("UPDATE companies SET owner_group = id, group_owner_email = NULL, group_suspended = FALSE WHERE code = ?").use { update ->
                    update.setString(1, companyCode)
                    update.executeUpdate()
                }
            }
        }
        service.audit(actor.audit, "extension.group.unlinked", companyCode, null, service.details())
        return group(companyCode, actor)
    }

    /** Server owner: accepts the current business owner email of a suspended company and lifts the suspension. */
    fun reviewSuspension(companyCode: String, actor: ExtensionActor): GroupResponse {
        service.requireServerOwner(actor)
        val email = ownerEmailOf(companyCode)?.takeIf { it.isNotBlank() }
            ?: throw ExtensionException("owner_email_missing", tr("El business owner de la empresa no tiene correo."))
        connections.central().use { central ->
            companyRow(central, companyCode)?.takeUnless { it.serverWorkspace } ?: throw ExtensionException("company_not_found", tr("Empresa no encontrada."), ExtensionException.Kind.NotFound)
            central.prepareStatement("UPDATE companies SET group_owner_email = ?, group_suspended = FALSE WHERE code = ?").use { update ->
                update.setString(1, email.lowercase())
                update.setString(2, companyCode)
                update.executeUpdate()
            }
        }
        service.audit(actor.audit, "extension.group.reviewed", companyCode, null, service.details())
        return group(companyCode, actor)
    }

    /**
     * Records the business owner email of a company as its group baseline (call it when a company is
     * created in a group or linked). A company with no baseline yet gets one on its first check.
     */
    fun recordGroupOwner(companyCode: String) {
        val email = ownerEmailOf(companyCode)?.takeIf { it.isNotBlank() } ?: return
        connections.central().use { central ->
            central.prepareStatement("UPDATE companies SET group_owner_email = ? WHERE code = ? AND group_owner_email IS NULL").use { update ->
                update.setString(1, email.lowercase())
                update.setString(2, companyCode)
                update.executeUpdate()
            }
        }
    }

    /**
     * Compares the business owner email of the company now with the one recorded for its group and
     * suspends it in its group when they differ. True when the company may take part in group
     * operations. Call it before every apply.
     */
    fun checkGroupOwner(companyCode: String): Boolean = !evaluateGroupOwner(companyCode, persist = true).blocked

    fun previewGroupOperation(sourceCompany: String, extensionId: String, request: GroupOperationRequest, actor: ExtensionActor): GroupOperationPreview {
        service.authorize(actor, sourceCompany)
        val extension = service.requireExtension(extensionId)
        val operation = service.requireOperation(request.operation)
        val plan = planGroupOperation(sourceCompany, extension, operation, request, persist = false, scope = actor.serverAdmin)
        val sourceSettings = if (operation == GroupOperationType.Apply) copyableSourceSettings(sourceCompany, extension) else emptyMap()
        val targets = plan.targets.map { code ->
            val problem = plan.problems[code]
            val name = plan.names[code].orEmpty()
            GroupTargetPreview(code, name, if (problem != null) emptyList() else steps(code, extension, operation, sourceSettings.size), problem)
        }
        return GroupOperationPreview(extensionId, operation, sourceCompany, targets)
    }

    /** Runs the operation on each target in its own transaction; a failed target does not stop the others. */
    fun applyToGroup(sourceCompany: String, extensionId: String, request: GroupOperationRequest, actor: ExtensionActor): GroupOperationResult {
        service.authorize(actor, sourceCompany)
        val extension = service.requireExtension(extensionId)
        val operation = service.requireOperation(request.operation)
        val plan = planGroupOperation(sourceCompany, extension, operation, request, persist = true, scope = actor.serverAdmin)
        val execute = executorFor(sourceCompany, extension, operation, request, actor)
        val operationId = runner.start(
            actor.role, operation, extensionId, sourceCompany, plan.targets,
            validate = { code -> validateTarget(sourceCompany, extension, code) },
            execute = execute,
        )
        val result = result(operationId)
        service.audit(actor.audit, "extension.$operation.group", sourceCompany, extension.manifest.module, service.details("extension" to extensionId, "operationId" to operationId.toString(), "status" to result.status))
        return result
    }

    /** Retries only the failed targets of an operation, inside that same operation. */
    fun retryFailed(sourceCompany: String, extensionId: String, operationId: Long, request: GroupOperationRequest, actor: ExtensionActor): GroupOperationResult {
        service.authorize(actor, sourceCompany)
        val extension = service.requireExtension(extensionId)
        val header = operations.header(operationId)
        if (header == null || header.extensionId != extensionId || header.sourceCompany != sourceCompany) {
            throw ExtensionException("operation_not_found", tr("La operacion no existe."), ExtensionException.Kind.NotFound)
        }
        if (header.operation !in setOf(GroupOperationType.Apply, GroupOperationType.Uninstall)) {
            throw ExtensionException("operation_not_retryable", tr("Solo se reintentan operaciones sobre un grupo."))
        }
        actor.serverAdmin?.let { scope ->
            operations.targets(operationId).firstOrNull { !scope.includes(it.companyCode) }?.let {
                throw ExtensionException("forbidden", tr("La empresa {0} no esta en tu alcance.", it.companyCode), ExtensionException.Kind.Forbidden)
            }
        }
        if (!checkGroupOwner(sourceCompany)) {
            throw ExtensionException("group_suspended", tr("La empresa de origen esta suspendida en su grupo."))
        }
        val execute = executorFor(sourceCompany, extension, header.operation, request, actor)
        runner.retryFailed(operationId, validate = { code -> validateTarget(sourceCompany, extension, code) }, execute = execute)
        val result = result(operationId)
        service.audit(actor.audit, "extension.${header.operation}.retry", sourceCompany, extension.manifest.module, service.details("extension" to extensionId, "operationId" to operationId.toString(), "status" to result.status))
        return result
    }

    /**
     * Call it at startup: operations a restart left running end as failed, and their pending targets
     * as failed ("interrumpida"), so the actor can retry them. Returns how many operations it closed.
     */
    fun recoverInterrupted(): Int = operations.failInterrupted()

    fun result(operationId: Long): GroupOperationResult {
        val header = operations.header(operationId) ?: throw ExtensionException("operation_not_found", tr("La operacion no existe."), ExtensionException.Kind.NotFound)
        val targets = operations.targets(operationId)
        return GroupOperationResult(
            operationId, header.extensionId, header.operation, header.sourceCompany.orEmpty(), OperationStatus.of(targets),
            targets.map { GroupTargetResult(it.companyCode, it.status, it.reason, it.attempts) },
        )
    }

    // ---- Group planning ----

    private class GroupPlan(val targets: List<String>, val problems: Map<String, String>, val names: Map<String, String>)

    private fun planGroupOperation(sourceCompany: String, extension: ServerExtension, operation: String, request: GroupOperationRequest, persist: Boolean, scope: ServerAdminScope?): GroupPlan {
        val manifest = extension.manifest
        val source = evaluateGroupOwner(sourceCompany, persist)
        if (source.blocked) throw ExtensionException("group_suspended", tr("La empresa de origen esta suspendida en su grupo o no se pudo verificar su business owner."))
        service.withTenant(sourceCompany) { tenant ->
            val row = service.requireInstalled(tenant, manifest.id)
            if (operation == GroupOperationType.Apply && row.status in setOf(ExtensionStatus.Incompatible)) {
                throw ExtensionException("extension_incompatible", tr("La extension de la empresa de origen es incompatible."))
            }
        }
        if (operation == GroupOperationType.Uninstall) UninstallChoices.resolve(manifest.uninstall.options, request.choices)
        val targets = request.targets.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (targets.size > ExtensionService.MaxGroupTargets) {
            throw ExtensionException("too_many_targets", tr("Elige como maximo {0} empresas por operacion.", ExtensionService.MaxGroupTargets), ExtensionException.Kind.BadRequest)
        }
        if (targets.isEmpty()) throw ExtensionException("no_targets", tr("Elige al menos una empresa de destino."), ExtensionException.Kind.BadRequest)
        scope?.let { admin ->
            targets.firstOrNull { !admin.includes(it) }?.let {
                throw ExtensionException("forbidden", tr("La empresa {0} no esta en tu alcance.", it), ExtensionException.Kind.Forbidden)
            }
        }
        val members = groupMembers(sourceCompany).associateBy { it.code }
        val problems = HashMap<String, String>()
        for (code in targets) {
            val problem = targetProblem(sourceCompany, extension, code, members[code], persist)
            if (problem != null) problems[code] = problem
        }
        return GroupPlan(targets, problems, members.mapValues { it.value.name })
    }

    // Validation of a target while the operation runs (the same checks again, persisting a suspension).
    private fun validateTarget(sourceCompany: String, extension: ServerExtension, code: String): String? =
        targetProblem(sourceCompany, extension, code, groupMembers(sourceCompany).firstOrNull { it.code == code }, persist = true)

    private fun targetProblem(sourceCompany: String, extension: ServerExtension, code: String, @Suppress("UNUSED_PARAMETER") member: GroupMember?, persist: Boolean): String? {
        val manifest = extension.manifest
        val (source, target) = connections.central().use { central -> companyRow(central, sourceCompany) to companyRow(central, code) }
        val usable = target != null && !target.serverWorkspace
        val inGroup = usable && target!!.active && source != null && target.ownerGroup == source.ownerGroup
        val targetOwner = if (inGroup) evaluateGroupOwner(code, persist) else null
        val moduleEnabled = inGroup && runCatching { service.withTenant(code) { manifest.module in service.enabledModules(it) } }.getOrDefault(false)
        val facts = GroupTargetFacts(
            companyCode = code,
            exists = usable,
            active = target?.active == true,
            sameGroup = inGroup || (usable && source != null && target!!.ownerGroup == source.ownerGroup),
            suspended = target?.suspended == true || targetOwner?.suspendedNow == true,
            ownerEmail = targetOwner?.currentEmail,
            expectedOwnerEmail = if (inGroup) ownerEmailOf(sourceCompany) else null,
            moduleEnabled = moduleEnabled,
            moduleInstalled = modules.isInstalled(manifest.module),
            compatible = service.compatible(manifest),
        )
        return GroupTargetValidator.problem(facts, sourceCompany)
    }

    private fun steps(code: String, extension: ServerExtension, operation: String, copyableSettings: Int): List<String> {
        val manifest = extension.manifest
        val row = runCatching { service.withTenant(code) { storage.row(it, manifest.id) } }.getOrNull()
        if (operation == GroupOperationType.Uninstall) {
            return if (row == null || row.status == ExtensionStatus.Removed) listOf(tr("La extension no esta instalada: no se hace nada"))
            else listOf(tr("Desinstalar la extension (se borran sus ajustes)"))
        }
        return buildList {
            if (row == null || row.status == ExtensionStatus.Removed) add(tr("Instalar la extension"))
            if (manifest.migrations.isNotEmpty() || extension.migrationPaths.isNotEmpty()) add(tr("Ejecutar las migraciones pendientes"))
            add(tr("Copiar {0} ajustes de la aplicacion, cifrados de nuevo para la empresa; los ajustes de conexion no se copian", copyableSettings))
            if (row?.status != ExtensionStatus.Enabled) add(tr("Activar la extension"))
        }
    }

    private fun executorFor(sourceCompany: String, extension: ServerExtension, operation: String, request: GroupOperationRequest, actor: ExtensionActor): (String) -> Unit {
        val manifest = extension.manifest
        if (operation == GroupOperationType.Uninstall) {
            val choices = UninstallChoices.resolve(manifest.uninstall.options, request.choices)
            return { code ->
                val installed = service.withTenant(code) { storage.row(it, manifest.id)?.status }
                if (installed != null && installed != ExtensionStatus.Removed) service.uninstallOne(code, extension, choices, actor)
                service.audit(actor.audit, "extension.uninstall", code, manifest.module, service.details("extension" to manifest.id, "from" to sourceCompany, choices = choices))
            }
        }
        // Read once for all targets: plain values live only in this closure while the operation runs.
        val values = copyableSourceSettings(sourceCompany, extension)
        val secretKeys = manifest.settings.filter { it.secret }.map { it.key }.toSet()
        return { code ->
            service.installOne(code, extension, actor)
            service.withTenant(code) { tenant ->
                tenant.inTransaction {
                    values.forEach { (key, value) -> storage.putSetting(tenant, manifest.id, key, value, secret = key in secretKeys) }
                }
            }
            service.enableOne(code, extension, actor)
            service.audit(actor.audit, "extension.apply", code, manifest.module, service.details("extension" to manifest.id, "from" to sourceCompany, keys = values.keys.sorted()))
        }
    }

    /** Settings of the source that may be copied (scope other than "connection"), opened with the source's key. */
    private fun copyableSourceSettings(sourceCompany: String, extension: ServerExtension): Map<String, String> =
        service.withTenant(sourceCompany) { tenant ->
            SettingsCopy.copyable(extension.manifest.settings, storage.settings(tenant, extension.manifest.id).all(includeSecrets = true))
        }

    // ---- Group ownership ----

    private data class GroupOwnerState(val blocked: Boolean, val suspendedNow: Boolean, val currentEmail: String?)

    private fun evaluateGroupOwner(companyCode: String, persist: Boolean): GroupOwnerState {
        val row = connections.central().use { companyRow(it, companyCode) } ?: return GroupOwnerState(blocked = true, suspendedNow = false, currentEmail = null)
        val current = ownerEmailOf(companyCode)?.lowercase()?.takeIf { it.isNotBlank() }
        if (current == null) return GroupOwnerState(blocked = true, suspendedNow = row.suspended, currentEmail = null)
        if (row.suspended) return GroupOwnerState(blocked = true, suspendedNow = true, currentEmail = current)
        val recorded = row.ownerEmail?.lowercase()
        if (recorded == null) {
            // Linked before the baseline existed: trust the current owner and remember it.
            if (persist) recordGroupOwner(companyCode)
            return GroupOwnerState(blocked = false, suspendedNow = false, currentEmail = current)
        }
        if (recorded == current) return GroupOwnerState(blocked = false, suspendedNow = false, currentEmail = current)
        if (persist) {
            connections.central().use { central ->
                central.prepareStatement("UPDATE companies SET group_suspended = TRUE WHERE code = ?").use { update ->
                    update.setString(1, companyCode)
                    update.executeUpdate()
                }
            }
            service.audit(AuditActor.system(), "extension.group.suspended", companyCode, null, service.details())
        }
        return GroupOwnerState(blocked = true, suspendedNow = true, currentEmail = current)
    }

    private class GroupMember(val code: String, val name: String, val suspended: Boolean)

    private fun groupMembers(companyCode: String): List<GroupMember> = connections.central().use { central ->
        central.prepareStatement(
            """
            SELECT member.code, member.name, member.group_suspended
            FROM companies origin
            JOIN companies member ON member.owner_group = origin.owner_group AND member.code <> origin.code
            WHERE origin.code = ? AND member.is_active AND NOT member.is_server_workspace
            ORDER BY lower(member.name)
            """.trimIndent()
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(GroupMember(rows.getString(1), rows.getString(2), rows.getBoolean(3))) } }
        }
    }

    private class CompanyGroupRow(
        val code: String,
        val active: Boolean,
        val serverWorkspace: Boolean,
        val ownerGroup: String?,
        val suspended: Boolean,
        val ownerEmail: String?,
    )

    private fun companyRow(central: Connection, code: String): CompanyGroupRow? =
        central.prepareStatement(
            "SELECT code, is_active, is_server_workspace, owner_group::text, group_suspended, group_owner_email FROM companies WHERE code = ?"
        ).use { query ->
            query.setString(1, code)
            query.executeQuery().use { rows ->
                if (rows.next()) CompanyGroupRow(rows.getString(1), rows.getBoolean(2), rows.getBoolean(3), rows.getString(4), rows.getBoolean(5), rows.getString(6)) else null
            }
        }

    private fun ownerEmailOf(companyCode: String): String? = connections.tenant(companyCode)?.use { tenant ->
        tenant.prepareStatement(
            "SELECT users.email FROM business_owner owner JOIN application_users users ON users.id = owner.user_id ORDER BY users.email LIMIT 1"
        ).use { query -> query.executeQuery().use { rows -> if (rows.next()) rows.getString(1)?.trim()?.lowercase() else null } }
    }
}
