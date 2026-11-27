package com.ideasdeveloper.bms.server.activity

import com.ideasdeveloper.bms.ActivityLogChange
import com.ideasdeveloper.bms.ActivityLogPage
import com.ideasdeveloper.bms.ActivityLogRow
import com.ideasdeveloper.bms.server.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A filter of the activity log; blank values are no filter. Dates are days (UTC), [to] included. */
data class ActivityFilter(
    // A user id (actor or real actor), or text matched against names, display and username.
    val user: String? = null,
    val module: String? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val action: String? = null,
    val text: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val page: Int = 0,
    val pageSize: Int = ActivityLogReader.DefaultPageSize,
) {
    val size: Int get() = pageSize.coerceIn(1, ActivityLogReader.MaxPageSize)
    // Deep OFFSETs make the database read and drop every row before them: past MaxPage, narrow the dates.
    val pageIndex: Int get() = page.coerceIn(0, ActivityLogReader.MaxPage)

    /** The filters actually used, for the log of reads made by agents (never more than short texts). */
    fun describe(): Map<String, String> = buildMap {
        user?.trim()?.takeIf { it.isNotEmpty() }?.let { put("usuario", it.take(100)) }
        module?.trim()?.takeIf { it.isNotEmpty() }?.let { put("modulo", it.take(100)) }
        entityType?.trim()?.takeIf { it.isNotEmpty() }?.let { put("tipo", it.take(100)) }
        entityId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("registro", it.take(100)) }
        action?.trim()?.takeIf { it.isNotEmpty() }?.let { put("accion", it.take(100)) }
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { put("texto", it.take(100)) }
        from?.let { put("desde", it.toString()) }
        to?.let { put("hasta", it.toString()) }
        put("pagina", pageIndex.toString())
    }

    companion object {
        /** Parses the query parameters of the read routes (user, module, entityType, entityId, action, q, from, to, page, pageSize). */
        fun of(parameters: (String) -> String?): ActivityFilter = ActivityFilter(
            user = parameters("user"),
            module = parameters("module"),
            entityType = parameters("entityType"),
            entityId = parameters("entityId"),
            action = parameters("action"),
            text = parameters("q") ?: parameters("text"),
            from = day(parameters("from")),
            to = day(parameters("to")),
            page = parameters("page")?.trim()?.toIntOrNull()?.also { number ->
                if (number > ActivityLogReader.MaxPage) throw ActivityFilterException(tr("La pagina debe estar entre 0 y {0}: acota las fechas para ver registros mas antiguos.", ActivityLogReader.MaxPage))
            } ?: 0,
            pageSize = parameters("pageSize")?.trim()?.toIntOrNull() ?: ActivityLogReader.DefaultPageSize,
        )

        fun day(value: String?): LocalDate? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { LocalDate.parse(text) }.getOrElse { throw ActivityFilterException(tr("Fecha no valida: usa AAAA-MM-DD.")) }
        }
    }
}

class ActivityFilterException(message: String) : Exception(message)

/**
 * Limit of the agents' reads of the logs (empresa_list_activity, servidor_list_company_activity...): at most
 * [limit] calls per key (user and company) in each [windowMillis]; the routes use Ktor's RateLimit
 * ([ActivityRateLimit]). Memory is bounded: past [maxKeys] keys the oldest windows are dropped.
 */
class ActivityReadLimiter(
    private val limit: Int = 30,
    private val windowMillis: Long = 60_000,
    private val maxKeys: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Window(val start: Long, var count: Int)
    private val windows = LinkedHashMap<String, Window>()

    /** True when one more call of [key] is allowed now (and counts it). */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val current = windows[key]?.takeIf { now - it.start < windowMillis }
            ?: Window(now, 0).also {
                windows.remove(key)
                if (windows.size >= maxKeys) windows.entries.iterator().let { oldest -> oldest.next(); oldest.remove() }
                windows[key] = it
            }
        if (current.count >= limit) return false
        current.count++
        return true
    }

    companion object {
        /** The one shared by every agent tool that reads an activity log or the server audit. */
        val Agents = ActivityReadLimiter()
        const val RefusalMessage = "Demasiadas consultas al registro de actividad en poco tiempo; espera un minuto y vuelve a intentarlo."
    }
}

/**
 * Reads the activity log of one company database, newest first. Never writes. A company whose
 * database does not have the table yet (before tenant V011) has an empty log.
 */
object ActivityLogReader {
    const val DefaultPageSize = 50
    const val MaxPageSize = 200
    const val MaxExportRows = 10_000
    const val MaxPage = 1_000
    // Bound of one read of the company route (and of the agent tools): a slow or huge log never holds a connection for long.
    const val StatementTimeout = "15s"

    /**
     * Runs [block] on [tenant] in a read-only transaction with [StatementTimeout] (SET LOCAL), restoring the
     * connection's auto-commit afterwards. [write] lets the block also insert (the export's own row): then the
     * transaction is not read-only and commits.
     */
    fun <T> bounded(tenant: Connection, write: Boolean = false, block: (Connection) -> T): T {
        if (!tenant.autoCommit) {
            // Already inside the caller's transaction: only bound it; the caller commits or rolls back.
            tenant.createStatement().use { it.execute("SET LOCAL statement_timeout = '$StatementTimeout'") }
            return block(tenant)
        }
        val autoCommit = tenant.autoCommit
        tenant.autoCommit = false
        try {
            tenant.createStatement().use { statement ->
                if (!write) statement.execute("SET TRANSACTION READ ONLY")
                statement.execute("SET LOCAL statement_timeout = '$StatementTimeout'")
            }
            val result = block(tenant)
            if (write) tenant.commit() else tenant.rollback()
            return result
        } catch (failure: Throwable) {
            runCatching { tenant.rollback() }
            throw failure
        } finally {
            runCatching { tenant.autoCommit = autoCommit }
        }
    }

    /** [moduleName] turns a module id into its name (Spanish, as stored); [companyCode] tags rows of the server route. */
    fun page(tenant: Connection, filter: ActivityFilter, moduleName: (String) -> String, companyCode: String? = null): ActivityLogPage {
        val rows = rows(tenant, filter, limit = filter.size + 1, offset = filter.pageIndex.toLong() * filter.size, moduleName, companyCode)
        return ActivityLogPage(rows.take(filter.size), filter.pageIndex, filter.size, hasMore = rows.size > filter.size)
    }

    /** One row by id, or null. */
    fun entry(tenant: Connection, id: Long, moduleName: (String) -> String, companyCode: String? = null): ActivityLogRow? =
        select(tenant, "WHERE id = ?", listOf { st, i -> st.setLong(i, id) }, 1, 0, moduleName, companyCode).firstOrNull()

    /** Up to [limit] rows from [offset] matching [filter], newest first. */
    fun rows(tenant: Connection, filter: ActivityFilter, limit: Int, offset: Long, moduleName: (String) -> String, companyCode: String? = null): List<ActivityLogRow> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<(PreparedStatement, Int) -> Unit>()
        fun like(text: String) = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        filter.user?.trim()?.takeIf { it.isNotEmpty() }?.let { who ->
            val id = runCatching { UUID.fromString(who) }.getOrNull()
            if (id != null) {
                conditions += "(actor_user_id = ? OR real_actor_user_id = ?)"
                values += { st, i -> st.setObject(i, id) }
                values += { st, i -> st.setObject(i, id) }
            } else {
                conditions += """(actor_name ILIKE ? ESCAPE '\' OR actor_display ILIKE ? ESCAPE '\' OR real_actor_name ILIKE ? ESCAPE '\'
                    OR actor_user_id IN (SELECT users.id FROM application_users users WHERE users.postgres_role ILIKE ? ESCAPE '\'))"""
                repeat(4) { values += { st, i -> st.setString(i, like(who)) } }
            }
        }
        filter.module?.trim()?.takeIf { it.isNotEmpty() }?.let { module ->
            conditions += "module_id = ?"
            values += { st, i -> st.setString(i, module) }
        }
        filter.entityType?.trim()?.takeIf { it.isNotEmpty() }?.let { type ->
            conditions += "entity_type = ?"
            values += { st, i -> st.setString(i, type) }
        }
        filter.entityId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
            conditions += "entity_id = ?"
            values += { st, i -> st.setString(i, id) }
        }
        filter.action?.trim()?.takeIf { it.isNotEmpty() }?.let { action ->
            conditions += "action = ?"
            values += { st, i -> st.setString(i, action) }
        }
        filter.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            conditions += "(summary ILIKE ? ESCAPE '\\' OR entity_label ILIKE ? ESCAPE '\\' OR actor_display ILIKE ? ESCAPE '\\' OR entity_id ILIKE ? ESCAPE '\\')"
            repeat(4) { values += { st, i -> st.setString(i, like(text)) } }
        }
        filter.from?.let { day ->
            conditions += "occurred_at >= ?"
            values += { st, i -> st.setObject(i, day.atStartOfDay().atOffset(ZoneOffset.UTC)) }
        }
        filter.to?.let { day ->
            conditions += "occurred_at < ?"
            values += { st, i -> st.setObject(i, day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)) }
        }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        return select(tenant, where, values, limit, offset, moduleName, companyCode)
    }

    private fun select(
        tenant: Connection,
        where: String,
        values: List<(PreparedStatement, Int) -> Unit>,
        limit: Int,
        offset: Long,
        moduleName: (String) -> String,
        companyCode: String?,
    ): List<ActivityLogRow> = try {
        tenant.prepareStatement(
            """
            SELECT id, occurred_at, actor_kind, actor_user_id, actor_name, actor_display, agent_name, real_actor_kind,
                   real_actor_user_id, real_actor_name, module_id, entity_type, entity_id, entity_label, action, summary,
                   changes::text, origin
            FROM public.activity_log
            $where
            ORDER BY occurred_at DESC, id DESC
            LIMIT ? OFFSET ?
            """.trimIndent()
        ).use { query ->
            values.forEachIndexed { index, bind -> bind(query, index + 1) }
            query.setInt(values.size + 1, limit)
            query.setLong(values.size + 2, offset)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val module = rows.getString(11)
                        val action = rows.getString(15)
                        // Who it was, rebuilt from the structured columns: the stored display is never shown.
                        val who = ActivityDisplay.of(rows.getString(3), rows.getString(5), rows.getString(7), rows.getString(8), rows.getString(10), rows.getString(18))
                        add(
                            ActivityLogRow(
                                id = rows.getLong(1),
                                occurredAt = rows.getObject(2, OffsetDateTime::class.java).toString(),
                                companyCode = companyCode,
                                actorKind = rows.getString(3),
                                actorUserId = rows.getObject(4)?.toString(),
                                actorName = rows.getString(5),
                                actorDisplay = who.display,
                                agentName = rows.getString(7),
                                realActorKind = rows.getString(8),
                                realActorUserId = rows.getObject(9)?.toString(),
                                realActorName = rows.getString(10),
                                moduleId = module,
                                moduleName = moduleName(module),
                                entityType = rows.getString(12),
                                entityId = rows.getString(13),
                                entityLabel = rows.getString(14),
                                action = action,
                                actionLabel = ActivityActions.label(action),
                                summary = rows.getString(16),
                                changes = changes(rows.getString(17)),
                                origin = rows.getString(18),
                                viaAgent = who.viaAgent,
                                viaAssistant = who.viaAssistant,
                                fromServer = who.fromServer,
                                actorKindLabel = who.kindLabel,
                            ),
                        )
                    }
                }
            }
        }
    } catch (failure: SQLException) {
        // Before tenant V011 the company has no log yet.
        if (failure.sqlState == "42P01") emptyList() else throw failure
    }

    private fun changes(json: String?): List<ActivityLogChange> {
        val array = runCatching { Json.parseToJsonElement(json.orEmpty()) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull
            ActivityLogChange(
                field = text("field").orEmpty(),
                label = text("label").orEmpty(),
                old = text("old"),
                new = text("new"),
                secret = (item["secret"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
    }

    /** CSV (RFC 4180, UTF-8 with BOM for spreadsheets) of [rows]; cells that a spreadsheet would run as a formula are prefixed with '. */
    fun csv(rows: List<ActivityLogRow>, withCompany: Boolean): String = buildString {
        append('﻿')
        val header = buildList {
            add("Fecha")
            if (withCompany) add("Empresa")
            addAll(listOf("Quien", "Tipo de actor", "Agente", "Modulo", "Tipo", "Id", "Registro", "Accion", "Resumen", "Cambios", "Origen"))
        }
        append(header.joinToString(",") { cell(it) })
        append("\r\n")
        rows.forEach { row ->
            val changes = row.changes.joinToString("; ") { change ->
                if (change.secret) "${change.label}: (cambiado)" else "${change.label}: ${change.old ?: "-"} -> ${change.new ?: "-"}"
            }
            val cells = buildList {
                add(row.occurredAt)
                if (withCompany) add(row.companyCode.orEmpty())
                addAll(listOf(row.actorDisplay, row.actorKindLabel, row.agentName?.let(ActivityNames::neutral).orEmpty(), row.moduleName, row.entityType, row.entityId.orEmpty(), row.entityLabel,
                    row.actionLabel, row.summary, changes, row.origin.orEmpty()))
            }
            append(cells.joinToString(",") { cell(it) })
            append("\r\n")
        }
    }

    private fun cell(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
