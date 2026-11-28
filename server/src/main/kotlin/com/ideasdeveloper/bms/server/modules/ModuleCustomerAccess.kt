package com.ideasdeveloper.bms.server.modules

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.domain.LoginSuccessResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.application.CompanyLinks
import com.ideasdeveloper.bms.server.company.application.ServerWorkspace
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.i18n.CompanyLanguages
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.SessionLanguages
import com.ideasdeveloper.bms.server.i18n.TextTranslation
import com.ideasdeveloper.bms.server.i18n.TextTranslator
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.trIn
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

// Clientes y su historial entre modulos: referencias, historial, notas, cambios y empresas vinculadas o del grupo del dueno.
// Colaborador interno de ModuleAccessService, que conserva la API publica.
internal class ModuleCustomerAccess(
    private val owner: ModuleAccessService,
    private val central: DataSource,
    private val moduleInstalled: (String) -> Boolean,
    private val customerReferenceSources: () -> List<CustomerReferenceSource>,
    private val customerTimelines: () -> List<RegisteredCustomerTimeline>,
    private val customerHistoryWriter: () -> CustomerHistoryWriter?,
    private val customerChangeListener: () -> CustomerChangeListener?,
    private val customerNotesReader: () -> CustomerNotesReader?,
    private val extensionModules: () -> Map<String, String>,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(ModuleAccessService::class.java)

    /**
     * Customer references of the modules enabled in this company and of its enabled extensions, keeping only valid
     * names and tables that exist in this database (one query for all tables). Invalid ones are dropped with a log.
     */
    fun customerReferences(tenant: Connection): List<CustomerReference> {
        val sources = customerReferenceSources().filter { it.references.isNotEmpty() }
        if (sources.isEmpty()) return emptyList()
        val modules = enabledIds(tenant, "SELECT module_id FROM tenant_modules WHERE status = 'enabled'")
        val extensions = if (tableExists(tenant, "tenant_extensions")) {
            enabledIds(tenant, "SELECT extension_id FROM tenant_extensions WHERE status = 'enabled'")
        } else {
            emptySet()
        }
        val active = sources.filter { source ->
            source.moduleId in modules && (source.extensionId == null || source.extensionId in extensions)
        }
        val valid = active.flatMap { source ->
            source.references.filter { reference ->
                val problem = CustomerReference.problemFor(reference, source.moduleId, source.extensionId)
                if (problem != null) {
                    log.warn("Referencia a clientes ignorada en {}/{}: {}", source.moduleId, source.extensionId ?: "-", problem)
                }
                problem == null
            }
        }.distinct()
        if (valid.isEmpty()) return emptyList()
        val tables = valid.map { it.table }.distinct()
        val existing = tenant.prepareStatement("SELECT t FROM unnest(?::text[]) AS t WHERE to_regclass('public.' || t) IS NOT NULL").use { query ->
            query.setArray(1, tenant.createArrayOf("text", tables.toTypedArray()))
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        return valid.filter { it.table in existing }
    }

    private fun enabledIds(tenant: Connection, sql: String): Set<String> = tenant.prepareStatement(sql).use { query ->
        query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
    }

    private fun tableExists(tenant: Connection, table: String): Boolean =
        tenant.prepareStatement("SELECT to_regclass('public.' || ?) IS NOT NULL").use { query ->
            query.setString(1, table)
            query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    /**
     * A customer's history from every installed module that is enabled for the company and whose
     * permission the user holds, newest first. A module that fails is left out (and logged), so one
     * module never hides the others' history.
     */
    fun customerHistory(tenant: Connection, permissions: ModulePermissions, customerId: UUID, limit: Int = 100): List<CustomerHistoryEntry> {
        val enabled = enabledModules(tenant)
        return customerTimelines()
            .filter { it.moduleId in enabled && isInstalled(it.moduleId) && permissions.has(it.timeline.permission) }
            .flatMap { registered ->
                try {
                    registered.timeline.entries(CustomerTimelineQuery(customerId, tenant, permissions, owner, limit)).take(limit).map { registered to it }
                } catch (failure: Exception) {
                    log.warn("Customer history of module {} failed: {}", registered.moduleId, failure.message)
                    emptyList()
                }
            }
            .sortedByDescending { it.second.at }
            .take(limit)
            .map { (registered, entry) ->
                CustomerHistoryEntry(entry.at.toString(), registered.moduleId, trIn(I18n.moduleNamespace(registered.moduleId), registered.moduleName), entry.kind, entry.title, entry.detail, entry.author, entry.link)
            }
    }

    /**
     * Writes [event] in the customer's history of the company of [tenant] (the company's tab of the
     * customer in Clientes). Does nothing and returns false when no installed module stores customer
     * history or the company does not use it. Never breaks the caller: inside a transaction the write
     * runs in a savepoint, and a failure is logged and rolled back to it.
     */
    fun recordCustomerEvent(tenant: Connection, event: CustomerHistoryEvent): Boolean {
        val writer = customerHistoryWriter() ?: return false
        val savepoint = if (tenant.autoCommit) null else tenant.setSavepoint()
        return try {
            writer.write(tenant, event)
            savepoint?.let { tenant.releaseSavepoint(it) }
            true
        } catch (failure: Exception) {
            savepoint?.let { runCatching { tenant.rollback(it) } }
            log.warn("Customer history event of module {} not written: {}", event.moduleId, failure.message)
            false
        }
    }

    /**
     * The customer's notes (tastes, preferences, general information), newest first, to show them
     * where the customer is attended (the CRM lead). Null when no installed module keeps customer
     * notes or they cannot be read; hide the notes then.
     */
    fun customerNotes(tenant: Connection, customerId: UUID, limit: Int = 5): List<CustomerNote>? {
        val reader = customerNotesReader() ?: return null
        return try {
            reader.notes(owner, tenant, customerId, limit)
        } catch (failure: Exception) {
            log.warn("Notes of customer {} not read: {}", customerId, failure.message)
            null
        }
    }

    /**
     * Call after changing a customer's data (name, email, phone, custom fields) in [tenant]: the
     * customer is one record for the companies it is shared with, so the change is copied to them now.
     * Call it once the change is committed. Never throws; a failure is logged.
     */
    fun customerChanged(tenant: Connection, customerId: UUID) {
        val listener = customerChangeListener() ?: return
        try {
            listener.changed(owner, tenant, customerId)
        } catch (failure: Exception) {
            log.warn("Customer {} not copied to the companies it is shared with: {}", customerId, failure.message)
        }
    }

    /**
     * The active companies of the business owner of [companyCode] (its owner group), itself included,
     * by name. Only the company itself for the server workspace or a company in no group.
     */
    fun groupCompanies(companyCode: String): List<GroupCompany> = central.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT target.code, target.name
            FROM companies origin
            JOIN companies target ON target.code = origin.code OR (origin.owner_group IS NOT NULL AND target.owner_group = origin.owner_group)
            WHERE origin.code = ? AND target.is_active AND NOT target.is_server_workspace
            ORDER BY CASE WHEN target.code = origin.code THEN 0 ELSE 1 END, lower(target.name)
            """.trimIndent()
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(GroupCompany(rows.getString(1), rows.getString(2))) }
            }
        }
    }

    /**
     * Companies linked with [companyCode] for [purpose], it first and then by name: active, of the same
     * owner group as it (checked on every read), in the same link group, never the server workspace.
     * Only it when it is not linked.
     */
    fun linkedCompanies(companyCode: String, purpose: String): List<GroupCompany> = central.connection.use { connection ->
        CompanyLinks.linked(connection, companyCode, purpose)
    }

    /**
     * Links [targetCode] to the group of [companyCode] (and [companyCode] too when it was not). One
     * transaction that locks the owner group's companies: both active, different, none the server
     * workspace and the same owner group, else [CompanyLinkException]. The caller checks the business owner.
     */
    fun linkCompany(companyCode: String, targetCode: String, purpose: String, linkedBy: String): List<GroupCompany> =
        noNewConnection().let { central.connection }.use { connection -> CompanyLinks.link(connection, companyCode, targetCode, purpose, linkedBy) }

    /**
     * Removes [targetCode] (it may be [companyCode]) from the group of [companyCode]; the same owner
     * group is required. A company left alone is removed too. Returns what stays linked on the side of
     * [companyCode] (only it when it no longer is).
     */
    fun unlinkCompany(companyCode: String, targetCode: String, purpose: String): List<GroupCompany> =
        noNewConnection().let { central.connection }.use { connection -> CompanyLinks.unlink(connection, companyCode, targetCode, purpose) }

    /**
     * Whether the user of [permissions] may see in the customer's file the history rows written by
     * [moduleId]. See [visibleCustomerEventModules]; this opens a query per call, so for many rows use that.
     */
    fun customerEventsVisible(tenant: Connection, permissions: ModulePermissions, moduleId: String): Boolean =
        moduleId in visibleCustomerEventModules(tenant, permissions, setOf(moduleId))

    /**
     * Of [moduleIds], the modules whose customer history rows the user may see: Clientes always; an
     * installed module only when enabled in [tenant] and the user is business owner or server owner, or
     * holds the permission of its customer timeline (without a timeline, any permission of the module,
     * whose ids start with "moduleId."); a registered extension id is visible when its module is enabled in
     * [tenant]; any other id (an uninstalled or unknown module) only to business owner or server owner.
     * Reads the enabled modules once for the whole set.
     */
    fun visibleCustomerEventModules(tenant: Connection, permissions: ModulePermissions, moduleIds: Set<String>): Set<String> {
        if (moduleIds.isEmpty()) return emptySet()
        val timelines = customerTimelines().associateBy { it.moduleId }
        val extensions = extensionModules()
        val candidates = moduleIds.filter { it != CustomersModuleId && (moduleInstalled(it) || it in extensions) }
        val enabled = if (candidates.isEmpty()) emptySet() else enabledModules(tenant)
        val admin = permissions.businessOwner || permissions.serverOwner
        return moduleIds.filterTo(mutableSetOf()) { id ->
            customerEventVisibility(
                id = id,
                installed = moduleInstalled(id),
                extensionModule = extensions[id],
                enabledModules = enabled,
                admin = admin,
                hasModulePermission = { moduleId -> timelines[moduleId]?.let { permissions.has(it.timeline.permission) } ?: permissions.hasAnyWithPrefix("$moduleId.") },
            )
        }
    }

    /** The company whose database [tenant] is connected to, or null for one the server does not know. */
    fun companyOf(tenant: Connection): GroupCompany? {
        val database = tenant.createStatement().use { statement ->
            statement.executeQuery("SELECT current_database()").use { rows -> if (rows.next()) rows.getString(1) else null }
        } ?: return null
        return central.connection.use { connection ->
            connection.prepareStatement("SELECT code, name FROM companies WHERE database_name = ?").use { query ->
                query.setString(1, database)
                query.executeQuery().use { rows -> if (rows.next()) GroupCompany(rows.getString(1), rows.getString(2)) else null }
            }
        }
    }

    private fun enabledModules(tenant: Connection): Set<String> = owner.enabledModules(tenant)

    private fun isInstalled(moduleId: String): Boolean = owner.isInstalled(moduleId)

    private fun noNewConnection() = owner.noNewConnection()
}
