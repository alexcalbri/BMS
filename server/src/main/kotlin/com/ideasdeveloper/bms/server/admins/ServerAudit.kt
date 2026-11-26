package com.ideasdeveloper.bms.server.admins

import com.ideasdeveloper.bms.ServerAuditEntry
import com.ideasdeveloper.bms.ServerAuditPage
import com.ideasdeveloper.bms.server.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Filters of GET /server/audit; dates are days (YYYY-MM-DD, UTC), [to] included. */
data class AuditFilter(
    val company: String? = null,
    val action: String? = null,
    val actor: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val page: Int = 0,
    val pageSize: Int = ServerAudit.DefaultPageSize,
)

/**
 * The provisioning audit log, newest first. An administrator with every company sees every row;
 * a scoped one sees the rows of the companies of their scope and their own rows (no other
 * server-wide rows). Details are returned as stored.
 */
class ServerAudit(private val central: DataSource) {
    companion object {
        const val DefaultPageSize = 50
        const val MaxPageSize = 200
    }

    fun page(actor: ServerActor, filter: AuditFilter): ServerAuditPage {
        val size = filter.pageSize.coerceIn(1, MaxPageSize)
        val page = filter.page.coerceAtLeast(0)
        val conditions = mutableListOf<String>()
        val values = mutableListOf<(java.sql.PreparedStatement, Int) -> Unit>()
        actor.companyScope?.let { scope ->
            conditions += "(company_code = ANY (?) OR actor_user_id = ?)"
            values += { st, i -> st.setArray(i, st.connection.createArrayOf("varchar", scope.toTypedArray())) }
            values += { st, i -> st.setObject(i, actor.userId) }
        }
        filter.company?.trim()?.takeIf { it.isNotEmpty() }?.let { code ->
            conditions += "company_code = ?"
            values += { st, i -> st.setString(i, code) }
        }
        filter.action?.trim()?.takeIf { it.isNotEmpty() }?.let { action ->
            conditions += "action ILIKE ? ESCAPE '\\'"
            values += { st, i -> st.setString(i, "%" + action.likeEscaped() + "%") }
        }
        filter.actor?.trim()?.takeIf { it.isNotEmpty() }?.let { who ->
            conditions += "(actor_role ILIKE ? ESCAPE '\\' OR actor_name ILIKE ? ESCAPE '\\' OR actor_email ILIKE ? ESCAPE '\\')"
            repeat(3) { values += { st, i -> st.setString(i, "%" + who.likeEscaped() + "%") } }
        }
        filter.from?.let { day ->
            conditions += "created_at >= ?"
            values += { st, i -> st.setObject(i, day.atStartOfDay().atOffset(ZoneOffset.UTC)) }
        }
        filter.to?.let { day ->
            conditions += "created_at < ?"
            values += { st, i -> st.setObject(i, day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)) }
        }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        val withActor = hasActorColumns()
        val actorColumns = if (withActor) "actor_user_id, actor_kind" else "NULL::uuid AS actor_user_id, NULL::text AS actor_kind"
        // Before control V015 a scoped administrator could only be filtered by company.
        val effectiveWhere = if (withActor) where else where.replace("OR actor_user_id = ?", "OR FALSE AND ?::uuid IS NULL")
        val entries = central.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, created_at, action, company_code, module_id, actor_role, $actorColumns, actor_name, actor_email, details::text
                FROM provisioning_audit_log
                $effectiveWhere
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """.trimIndent()
            ).use { query ->
                values.forEachIndexed { index, bind -> bind(query, index + 1) }
                query.setInt(values.size + 1, size + 1)
                query.setLong(values.size + 2, page.toLong() * size)
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(
                            ServerAuditEntry(
                                id = rows.getObject(1, UUID::class.java).toString(),
                                createdAt = rows.getObject(2, OffsetDateTime::class.java).toString(),
                                action = rows.getString(3),
                                companyCode = rows.getString(4),
                                moduleId = rows.getString(5),
                                actorRole = rows.getString(6),
                                actorUserId = rows.getObject(7)?.toString(),
                                actorKind = rows.getString(8),
                                actorName = rows.getString(9),
                                actorEmail = rows.getString(10),
                                details = runCatching { Json.parseToJsonElement(rows.getString(11)).jsonObject }.getOrDefault(JsonObject(emptyMap())),
                            ),
                        )
                    }
                }
            }
        }
        return ServerAuditPage(entries.take(size), page, size, hasMore = entries.size > size)
    }

    private fun hasActorColumns(): Boolean = try {
        central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'provisioning_audit_log' AND column_name = 'actor_kind'"
            ).use { query -> query.executeQuery().use { it.next() } }
        }
    } catch (_: SQLException) {
        false
    }

    private fun String.likeEscaped(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

/** Parses a YYYY-MM-DD filter; blank is no filter. */
fun parseAuditDay(value: String?): LocalDate? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { LocalDate.parse(text) }.getOrElse { throw ServerAdminException("INVALID_DATE", tr("Fecha no valida: usa AAAA-MM-DD.")) }
}
