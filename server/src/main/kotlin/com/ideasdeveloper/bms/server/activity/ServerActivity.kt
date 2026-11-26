package com.ideasdeveloper.bms.server.activity

import com.ideasdeveloper.bms.ActivityLogPage
import com.ideasdeveloper.bms.ActivityLogRow
import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerPermission
import com.ideasdeveloper.bms.server.admins.ServerPermissionException
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.auditDetails
import com.ideasdeveloper.bms.server.i18n.tr
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * The companies' activity logs for the server (GET /server/activity, the server agent tools): the
 * owner sees every company (the server workspace included), an administrator with server.audit.view
 * only the companies of their scope (never the workspace). Without a company it merges the newest
 * rows of every company in scope, reading at most [MaxMergedDepth] rows per company, so deeper pages
 * need a company filter. Each row is tagged here with the code of the database it was read from:
 * nothing a company stored decides which company a row is shown as.
 */
class ServerActivity(
    private val central: DataSource,
    // Opens a company database by its name (runtime role); null when it is not configured.
    private val openTenant: (databaseName: String) -> Connection?,
    private val moduleName: (String) -> String,
) {
    private val log = LoggerFactory.getLogger(ServerActivity::class.java)

    companion object {
        const val MaxMergedDepth = 1_000
        const val MaxMergedExportPerCompany = 2_000
        private const val StatementTimeout = "15s"
    }

    private data class Company(val code: String, val databaseName: String)

    /** Companies of [actor]'s scope with a database, by code; [only] narrows to one (checked in scope first). */
    private fun companies(actor: ServerActor, only: String?): List<Company> {
        actor.require(ServerPermission.AuditView)
        val code = only?.trim()?.takeIf { it.isNotEmpty() }
        if (code != null) actor.requireCompany(ServerPermission.AuditView, code)
        val all = central.connection.use { connection ->
            connection.prepareStatement("SELECT code, database_name FROM companies ORDER BY code").use { query ->
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(Company(rows.getString(1), rows.getString(2))) } }
            }
        }
        val inScope = all.filter { actor.mayActOn(it.code) }
        if (code == null) return inScope
        return inScope.filter { it.code == code }.ifEmpty { throw ActivityNotFoundException(tr("Empresa no encontrada: {0}.", code)) }
    }

    private fun <T> withTenant(company: Company, block: (Connection) -> T): T {
        val connection = openTenant(company.databaseName) ?: throw java.sql.SQLException("Tenant ${company.code} not configured", "08001")
        return connection.use { tenant ->
            val autoCommit = tenant.autoCommit
            tenant.autoCommit = false
            try {
                // Read only and bounded: a slow or huge company log never holds the request for long.
                tenant.createStatement().use { statement ->
                    statement.execute("SET TRANSACTION READ ONLY")
                    statement.execute("SET LOCAL statement_timeout = '$StatementTimeout'")
                }
                block(tenant).also { tenant.rollback() }
            } catch (failure: Throwable) {
                runCatching { tenant.rollback() }
                throw failure
            } finally {
                runCatching { tenant.autoCommit = autoCommit }
            }
        }
    }

    fun page(actor: ServerActor, company: String?, filter: ActivityFilter): ActivityLogPage {
        val companies = companies(actor, company)
        if (company?.trim()?.isNotEmpty() == true) {
            val only = companies.single()
            return withTenant(only) { ActivityLogReader.page(it, filter, moduleName, only.code) }
        }
        val depth = (filter.pageIndex + 1) * filter.size
        if (depth > MaxMergedDepth) {
            throw ActivityFilterException(tr("Para ver registros mas antiguos elige una empresa o acota las fechas."))
        }
        val (rows, unavailable) = merged(companies, filter, depth)
        val start = filter.pageIndex * filter.size
        val window = rows.drop(start)
        return ActivityLogPage(window.take(filter.size), filter.pageIndex, filter.size, hasMore = window.size > filter.size, unavailableCompanies = unavailable)
    }

    /** Up to [ActivityLogReader.MaxExportRows] rows as CSV; the export itself is audited (a bulk read). */
    fun export(actor: ServerActor, company: String?, filter: ActivityFilter): String {
        val companies = companies(actor, company)
        val rows = if (company?.trim()?.isNotEmpty() == true) {
            val only = companies.single()
            withTenant(only) { ActivityLogReader.rows(it, filter, ActivityLogReader.MaxExportRows, 0, moduleName, only.code) }
        } else {
            merged(companies, filter, MaxMergedExportPerCompany).first.take(ActivityLogReader.MaxExportRows)
        }
        central.connection.use { connection ->
            ProvisioningAudit.insert(
                connection, actor.audit, "server_activity.exported", company?.trim()?.takeIf { it.isNotEmpty() }, null,
                auditDetails("filters" to filter.describe().entries.map { "${it.key}=${it.value}" }, "rows" to rows.size),
            )
        }
        return ActivityLogReader.csv(rows, withCompany = true)
    }

    private fun merged(companies: List<Company>, filter: ActivityFilter, perCompany: Int): Pair<List<ActivityLogRow>, List<String>> {
        val unavailable = mutableListOf<String>()
        val rows = companies.flatMap { company ->
            try {
                withTenant(company) { ActivityLogReader.rows(it, filter, perCompany, 0, moduleName, company.code) }
            } catch (failure: Exception) {
                log.warn("Activity log of company {} could not be read: {}", company.code, failure.message)
                unavailable += company.code
                emptyList()
            }
        }
        val sorted = rows.sortedWith(
            compareByDescending<ActivityLogRow> { OffsetDateTime.parse(it.occurredAt).toInstant() }
                .thenBy { it.companyCode }
                .thenByDescending { it.id },
        )
        return sorted to unavailable
    }
}

class ActivityNotFoundException(message: String) : Exception(message)

/** Refusals of the read routes as {status, code, message}. */
internal fun activityRefusal(failure: Throwable): Triple<Int, String, String>? = when (failure) {
    is ServerPermissionException -> Triple(403, failure.code, failure.message.orEmpty())
    is ActivityFilterException -> Triple(400, "INVALID_FILTER", failure.message.orEmpty())
    is ActivityNotFoundException -> Triple(404, "COMPANY_NOT_FOUND", failure.message.orEmpty())
    else -> null
}
