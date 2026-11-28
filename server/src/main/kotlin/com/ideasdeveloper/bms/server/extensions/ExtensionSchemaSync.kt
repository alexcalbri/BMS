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

/** Schema of each extension per company (EXTENSIONS.md, Updating a package): [ExtensionService.syncSchemas]. */
internal class ExtensionSchemaSync(
    private val service: ExtensionService,
    private val connections: ExtensionConnections,
    private val storage: ExtensionStorage,
    private val registry: ExtensionRegistry,
    private val clock: () -> Instant,
    private val migrationRunner: ExtensionMigrationRunner,
) {
    private val log = LoggerFactory.getLogger(ExtensionService::class.java)
    // Companies the last sync could not read, so an unreachable one is logged when it starts failing, not every hour.
    private val unreachableCompanies = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
    fun syncSchemas(): ExtensionService.SchemaSyncReport {
        val companies = try {
            service.activeCompanyCodes()
        } catch (failure: Exception) {
            log.error("Extension schemas not checked: the company list could not be read", failure)
            return ExtensionService.SchemaSyncReport(0, 0, listOf("*"))
        }
        var updated = 0
        var suspended = 0
        val unreachable = mutableListOf<String>()
        for (company in companies) {
            try {
                val (done, failed) = syncCompany(company)
                updated += done
                suspended += failed
                if (unreachableCompanies.remove(company)) log.info("Extension schemas of company {} checked again", company)
            } catch (failure: Exception) {
                unreachable += company
                if (unreachableCompanies.add(company)) {
                    log.warn("Extension schemas of company {} not checked ({}); retried every hour", company, failure.message)
                }
            }
        }
        if (updated > 0 || suspended > 0) log.info("Extension schemas: {} brought up to date, {} suspended", updated, suspended)
        return ExtensionService.SchemaSyncReport(updated, suspended, unreachable)
    }

    private fun syncCompany(companyCode: String): Pair<Int, Int> {
        val tenant = connections.tenant(companyCode) ?: return 0 to 0
        return tenant.use {
            var updated = 0
            var suspended = 0
            val applied = runCatching { storage.appliedMigrations(tenant) }.getOrNull()
            for (state in storage.schemaRows(tenant)) {
                if (state.row.status == ExtensionStatus.Removed) continue
                val extension = registry.get(state.row.extensionId) ?: continue
                if (!schemaBehind(state, extension, applied)) continue
                try {
                    migrationRunner.run(companyCode, extension)
                } catch (failure: Exception) {
                    suspendForSchema(companyCode, tenant, extension, state, failure)
                    suspended++
                    continue
                }
                tenant.inTransaction { markSchemaCurrent(tenant, extension) }
                service.audit(
                    AuditActor.system(), "extension.schema_updated", companyCode, extension.manifest.module,
                    service.details("extension" to extension.manifest.id, "from" to state.row.version, "to" to extension.manifest.version),
                )
                updated++
            }
            updated to suspended
        }
    }

    /** The company's schema of [extension] is not the loaded version's: another version recorded, a missing migration or a suspension. */
    internal fun schemaBehind(state: TenantExtensionSchemaRow, extension: ServerExtension, applied: Map<String, Set<String>>?): Boolean {
        if (state.row.version != extension.manifest.version || state.schemaError != null) return true
        if (applied == null) return false
        val done = applied[extension.manifest.id].orEmpty()
        return extension.migrationPaths.any { it.substringAfterLast('/').removeSuffix(".sql") !in done }
    }

    /**
     * After the migrations of the loaded version applied: the row records that version and a suspension ends (the previous
     * status comes back, unless the module is now below the extension's minimum). Call it inside a transaction.
     */
    internal fun markSchemaCurrent(tenant: Connection, extension: ServerExtension) {
        val manifest = extension.manifest
        val now = Timestamp.from(clock())
        if (!storage.hasSchemaState(tenant)) {
            tenant.prepareStatement("UPDATE tenant_extensions SET version = ?, updated_at = ? WHERE extension_id = ? AND version <> ? AND status <> 'removed'").use { statement ->
                statement.setString(1, manifest.version)
                statement.setTimestamp(2, now)
                statement.setString(3, manifest.id)
                statement.setString(4, manifest.version)
                statement.executeUpdate()
            }
            return
        }
        val restored = tenant.prepareStatement(
            """
            UPDATE tenant_extensions
            SET version = ?, updated_at = ?,
                status = CASE WHEN status = 'incompatible' AND status_before_error IS NOT NULL AND ? THEN status_before_error ELSE status END,
                schema_error = NULL, status_before_error = NULL
            WHERE extension_id = ? AND status <> 'removed' AND (version <> ? OR schema_error IS NOT NULL OR status_before_error IS NOT NULL)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, manifest.version)
            statement.setTimestamp(2, now)
            statement.setBoolean(3, service.compatible(manifest))
            statement.setString(4, manifest.id)
            statement.setString(5, manifest.version)
            statement.executeUpdate()
        }
        if (restored > 0) {
            service.recordActivity(tenant, null, extension, com.ideasdeveloper.bms.server.activity.ActivityActions.Updated, "El servidor aplico las migraciones de la extension ${manifest.displayName} ${manifest.version}.")
        }
    }

    private fun suspendForSchema(companyCode: String, tenant: Connection, extension: ServerExtension, state: TenantExtensionSchemaRow, failure: Exception) {
        val manifest = extension.manifest
        val reason = (failure.message ?: failure.javaClass.simpleName).lines().first().take(400)
        val already = state.schemaError
        try {
            tenant.inTransaction {
                if (state.hasSchemaState) {
                    tenant.prepareStatement(
                        """
                        UPDATE tenant_extensions
                        SET status = 'incompatible', updated_at = ?, schema_error = ?,
                            status_before_error = COALESCE(status_before_error, CASE WHEN status IN ('installed', 'enabled', 'disabled') THEN status END)
                        WHERE extension_id = ? AND status <> 'removed'
                        """.trimIndent()
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(clock()))
                        statement.setString(2, reason)
                        statement.setString(3, manifest.id)
                        statement.executeUpdate()
                    }
                } else if (state.row.status != ExtensionStatus.Incompatible) {
                    service.setStatus(tenant, manifest.id, ExtensionStatus.Incompatible)
                }
                if (already == null) {
                    service.recordActivity(tenant, null, extension, com.ideasdeveloper.bms.server.activity.ActivityActions.StatusChanged, "El servidor suspendio la extension ${manifest.displayName}: no se pudieron aplicar las migraciones de la version ${manifest.version}.")
                }
            }
        } catch (written: Exception) {
            log.error("Extension {} could not be suspended in company {}", manifest.id, companyCode, written)
        }
        // Logged, told and audited once per suspension (and again only if the error changes), not on every retry.
        if (already == reason) {
            log.debug("Extension {} still suspended in company {}: {}", manifest.id, companyCode, reason)
            return
        }
        log.error("Extension {} {} suspended in company {}: its migrations failed", manifest.id, manifest.version, companyCode, failure)
        runCatching {
            service.notifyBusinessOwner(tenant, manifest.module) {
                NotificationText(
                    tr("Extension suspendida: {0}", trIn(I18n.extensionNamespace(manifest.id), manifest.displayName)),
                    tr("No se pudo poner al dia la base de datos de la empresa para la version {0} ({1}). La extension queda desactivada; el servidor lo vuelve a intentar cada hora y al reiniciar.", manifest.version, reason),
                )
            }
        }.onFailure { log.warn("Business owner of company {} not told about extension {}: {}", companyCode, manifest.id, it.message) }
        service.audit(AuditActor.system(), "extension.suspended", companyCode, manifest.module, service.details("extension" to manifest.id, "version" to manifest.version, "error" to reason))
    }
}
