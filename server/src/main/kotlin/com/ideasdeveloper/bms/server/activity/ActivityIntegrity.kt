package com.ideasdeveloper.bms.server.activity

import com.ideasdeveloper.bms.ActivityIntegrityReport
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.auditDetails
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * Tamper evidence of the company activity logs (ACTIVITY_LOG.md §9). Each committed row of activity_log is
 * sealed into a hash chain (activity_log_chain) by the database (tenant V011, activity_log_seal); [check] recomputes
 * the whole chain with PostgreSQL's built-in functions only (never the company's own functions), compares every
 * object of the log with V011 (the deviation query: triggers, rules, row security, inheritance, columns and their
 * types, constraints, indexes, extended statistics, casts, functions, owners) and checks every anchor the server
 * wrote for the company in provisioning_audit_log. Detection only: it never changes the company database.
 *
 * Reading is safe against the company database's own objects: it runs as the runtime role with a pinned search_path,
 * schema-qualified SQL and row_security off, reads catalogs first, and reads rows of a table only once the catalogs show
 * it is a plain heap table without row security whose columns all have built-in types (no attacker-defined policy,
 * output function, cast or operator can then run), checked again under ACCESS SHARE right before each read. Anything
 * else is reported and that table is not read. It reads in short transactions and bounded chunks, and takes no lock
 * that blocks writers.
 */
object ActivityIntegrity {
    const val AnchorAction = "activity.anchor"
    const val FailedAction = "activity.integrity_failed"
    const val Migration = "V011__create_activity_log"
    val Genesis: String = "0".repeat(64)
    private const val MaxProblems = 50
    private const val ChunkRows = 5_000

    /** Rows without a seal are reported once they are older than this (an honest row is sealed when it commits). */
    const val UnsealedGraceSeconds = 120

    /**
     * The functions of the log as each tenant migration that defines them leaves them (sha256 of prosrc). A release
     * that changes them (a later V0xx) adds its entry and keeps the older ones: a function must be exactly one of these.
     */
    internal data class AuditVersion(val migration: String, val functions: Map<String, String>)

    internal val versions = listOf(
        AuditVersion(
            Migration,
            mapOf(
                "activity_log_append_only" to "b9a98b70ecff1413d3ddf7b46c3630e4c92c5f822aede3ca6bd962bcfaeda5be",
                "activity_log_before_insert" to "cf6c98f0ae0abb076a9e13820aa0064de8a0930902dca33701fd6ccd64c657f2",
                "activity_log_seal" to "06e4c722441e96b01e28d730f4a416bf4350000524fbbe542f512e89f1e65aa1",
            ),
        ),
    )

    /** The deviation query (server/src/main/resources/activity/activity-deviations.sql). */
    internal val deviationQuery: String by lazy {
        ActivityIntegrity::class.java.getResourceAsStream("/activity/activity-deviations.sql")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }

    // The row hash of V011's activity_log_seal, from the stored columns, with built-ins only (search_path is pg_catalog).
    private const val RowHash = """pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(c.prev_hash || '|' || pg_catalog.jsonb_build_array(
            c.chain_seq, a.id, pg_catalog.to_char(a.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            a.actor_kind, a.actor_user_id, a.actor_name, a.actor_display, a.agent_name, a.real_actor_kind,
            a.real_actor_user_id, a.real_actor_name, a.module_id, a.entity_type, a.entity_id, a.entity_label,
            a.action, a.summary, a.changes, a.origin)::text, 'UTF8')), 'hex')"""

    /**
     * Why a table cannot be read safely, from the catalogs only (null: it can). A table that is not a plain heap table,
     * enforces row security, or has a column whose type is not built-in (oid below 16384) is never read: its rows could
     * make PostgreSQL run functions the company database defines (policies, output functions, casts, operators).
     */
    private const val Unreadable = """
        SELECT CASE
            WHEN c.oid IS NULL THEN NULL
            WHEN c.relkind <> 'r' THEN 'no es una tabla normal'
            WHEN c.relam <> (SELECT oid FROM pg_catalog.pg_am WHERE amname = 'heap') THEN 'no usa el metodo de acceso heap'
            WHEN c.relrowsecurity OR c.relforcerowsecurity THEN 'tiene seguridad por filas'
            ELSE (SELECT 'la columna ' || a.attname || ' es de un tipo que no es de PostgreSQL'
                  FROM pg_catalog.pg_attribute a
                  WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped AND a.atttypid >= 16384
                  ORDER BY a.attnum LIMIT 1)
        END
        FROM (SELECT pg_catalog.to_regclass(?) AS oid) r LEFT JOIN pg_catalog.pg_class c ON c.oid = r.oid"""

    /** One anchor: how far the chain of the company reached when the server looked (database oid for information only). */
    data class Anchor(
        val lastSeq: Long,
        val lastId: Long?,
        val lastHash: String,
        val count: Long,
        val databaseOid: Long?,
        val reason: String,
        val at: String,
    ) {
        fun details(companyId: String?, firstAt: String? = null, lastAt: String? = null): String = auditDetails(
            "lastSeq" to lastSeq, "lastId" to lastId, "lastHash" to lastHash, "count" to count, "databaseOid" to databaseOid,
            "reason" to reason, "companyId" to companyId, "firstAt" to firstAt, "lastAt" to lastAt,
        )

        companion object {
            fun parse(details: String, at: String): Anchor? = runCatching {
                val json = Json.parseToJsonElement(details) as JsonObject
                fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
                Anchor(
                    lastSeq = (json["lastSeq"] as JsonPrimitive).longOrNull ?: return null,
                    lastId = (json["lastId"] as? JsonPrimitive)?.longOrNull,
                    lastHash = text("lastHash") ?: return null,
                    count = (json["count"] as? JsonPrimitive)?.longOrNull ?: 0,
                    databaseOid = (json["databaseOid"] as? JsonPrimitive)?.longOrNull,
                    reason = text("reason").orEmpty(),
                    at = at,
                )
            }.getOrNull()
        }
    }

    /** What [check] found in one company database. */
    data class Result(
        val ok: Boolean,
        val tablePresent: Boolean,
        val rows: Long,
        val lastSeq: Long,
        val lastId: Long?,
        val lastHash: String,
        val firstAt: String?,
        val lastAt: String?,
        val databaseOid: Long,
        val firstBadId: Long?,
        val problems: List<String>,
        val anchorsChecked: Int,
        val notes: List<String> = emptyList(),
    ) {
        fun anchor(reason: String): Anchor = Anchor(lastSeq, lastId, lastHash, rows, databaseOid, reason, Instant.now().toString())

        fun report(companyCode: String): ActivityIntegrityReport = ActivityIntegrityReport(
            companyCode = companyCode, ok = ok, rows = rows, lastSeq = lastSeq, lastId = lastId,
            lastHash = lastHash.takeIf { lastSeq > 0 }, anchorsChecked = anchorsChecked, firstBadId = firstBadId, problems = problems,
            checkedAt = Instant.now().toString(), notes = notes,
        )
    }

    /**
     * Checks the log of [tenant] (connected as the runtime role) against [anchors] (all anchors of the company, oldest
     * first). Reads in short read-only transactions (catalogs, end of the chain) and in chunks of [ChunkRows] chain
     * positions, each its own statement, so no snapshot is held for long and writers never wait; never writes.
     */
    fun check(tenant: Connection, anchors: List<Anchor>, statementTimeoutMillis: Long = 60_000): Result {
        val autoCommit = tenant.autoCommit
        try {
            tenant.autoCommit = true
            tenant.createStatement().use { statement ->
                statement.execute("SET statement_timeout = $statementTimeoutMillis")
                // Built-ins only: nothing the company database defines can stand in for them.
                statement.execute("SET search_path = pg_catalog, pg_temp")
                statement.execute("SET default_transaction_read_only = on")
                statement.execute("SET lock_timeout = '5s'")
                // A table with row security is never read (checked first); should one be read anyway, it fails, no policy runs.
                statement.execute("SET row_security = off")
            }
            return inspect(tenant, anchors)
        } finally {
            runCatching {
                tenant.createStatement().use { statement ->
                    statement.execute("RESET statement_timeout")
                    statement.execute("RESET search_path")
                    statement.execute("RESET default_transaction_read_only")
                    statement.execute("RESET lock_timeout")
                    statement.execute("RESET row_security")
                }
            }
            runCatching { tenant.autoCommit = autoCommit }
        }
    }

    private fun unreadable(tenant: Connection, table: String): String? =
        tenant.prepareStatement(Unreadable).use { query ->
            query.setString(1, table)
            query.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    /** Thrown inside [guarded] when a table stopped being safe to read since the catalogs were checked. */
    private class Unsafe(val table: String, val reason: String) : Exception()

    /**
     * Runs [block] in a short read-only transaction holding ACCESS SHARE on [tables] (writers are not blocked; the DDL
     * that could change what reading them executes is, until it commits), after checking again from the catalogs that
     * each can be read safely: what [block] reads is what was checked.
     */
    private fun <T> guarded(tenant: Connection, tables: List<String>, block: () -> T): T {
        tenant.autoCommit = false
        try {
            tenant.createStatement().use { statement ->
                statement.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED, READ ONLY")
                statement.execute("LOCK TABLE ONLY ${tables.joinToString(", ")} IN ACCESS SHARE MODE")
            }
            tables.forEach { table -> unreadable(tenant, table)?.let { throw Unsafe(table, it) } }
            return block().also { tenant.commit() }
        } finally {
            runCatching { tenant.rollback() }
            tenant.autoCommit = true
        }
    }

    private fun inspect(tenant: Connection, anchors: List<Anchor>): Result {
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var firstBad: Long? = null
        var more = 0
        fun bad(id: Long?, problem: String) {
            if (problems.size < MaxProblems) problems += problem else more++
            if (firstBad == null && id != null) firstBad = id
        }
        val databaseOid = scalar(tenant, "SELECT oid::bigint FROM pg_catalog.pg_database WHERE datname = pg_catalog.current_database()")!!.toLong()
        // The migration history, read only when the runtime role may and its table is plain with built-in column types.
        val historyPresent = scalar(
            tenant,
            "SELECT pg_catalog.to_regclass('public.schema_migrations') IS NOT NULL AND pg_catalog.has_table_privilege('public.schema_migrations', 'SELECT')",
        ) == "t"
        val historyProblem = if (historyPresent) unreadable(tenant, "public.schema_migrations") else null
        val v011 = historyPresent && historyProblem == null && runCatching {
            guarded(tenant, listOf("public.schema_migrations")) {
                scalar(tenant, "SELECT EXISTS (SELECT 1 FROM ONLY public.schema_migrations m WHERE m.module = 'tenant' AND m.version = '$Migration')") == "t"
            }
        }.getOrElse { failure -> if (failure is Unsafe) false else throw failure }
        if (historyProblem != null) bad(null, "No se leyo el historial de migraciones (schema_migrations): $historyProblem.")
        val present = scalar(tenant, "SELECT pg_catalog.to_regclass('public.activity_log') IS NOT NULL") == "t"
        // Only the anchors since the company's last deletion (a code used again later starts a new chain).
        val ours = anchors.drop(anchors.indexOfLast { it.reason == "deletion" } + 1)
        if (!present) {
            if (v011) bad(null, "Falta la tabla activity_log aunque la migracion V011 consta como aplicada.")
            if (ours.any { it.lastSeq > 0 }) bad(null, "Falta la tabla activity_log y hay ${ours.size} anclas de su cadena.")
            return Result(problems.isEmpty(), false, 0, 0, null, Genesis, null, null, databaseOid, firstBad, problems, ours.size)
        }

        // Objects exactly as V011 leaves them (catalogs only), and whether each table can be read, in one short transaction.
        // The source of every version this release knows is accepted (an older migration's body is still the migration's).
        val sources = versions.flatMap { it.functions.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, hashes) -> hashes.distinct().joinToString("|") }
        val logProblem: String?
        val chainProblem: String?
        tenant.autoCommit = false
        try {
            tenant.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED, READ ONLY") }
            tenant.prepareStatement("SELECT pg_catalog.set_config('bms.activity_functions', ?, true)").use { query ->
                query.setString(1, sources.entries.joinToString(";") { "${it.key}=${it.value}" })
                query.executeQuery().close()
            }
            tenant.createStatement().use { statement ->
                statement.executeQuery(deviationQuery).use { rows -> while (rows.next()) bad(null, rows.getString(1)) }
            }
            logProblem = unreadable(tenant, "public.activity_log")
            chainProblem = if (scalar(tenant, "SELECT pg_catalog.to_regclass('public.activity_log_chain') IS NOT NULL") == "t") {
                unreadable(tenant, "public.activity_log_chain")
            } else "no existe"
            tenant.commit()
        } finally {
            runCatching { tenant.rollback() }
            tenant.autoCommit = true
        }
        if (logProblem != null) bad(null, "No se leyeron las filas de activity_log: $logProblem.")
        if (chainProblem != null) bad(null, "No se leyo la cadena (activity_log_chain): $chainProblem.")
        fun stopped(): Result {
            if (more > 0) problems += "Y $more problemas mas."
            return Result(false, true, 0, 0, null, Genesis, null, null, databaseOid, firstBad, problems, ours.size)
        }
        if (logProblem != null || chainProblem != null) return stopped()
        // From here on every read of the two tables is guarded: re-checked under ACCESS SHARE first.
        val both = listOf("public.activity_log", "public.activity_log_chain")
        fun <T> read(block: () -> T): T? = try {
            guarded(tenant, both, block)
        } catch (unsafe: Unsafe) {
            bad(null, "${unsafe.table} cambio durante la verificacion (${unsafe.reason}): no se siguio leyendo.")
            null
        }
        val end = read { scalar(tenant, "SELECT pg_catalog.max(c.chain_seq) FROM ONLY public.activity_log_chain c")?.toLong() ?: 0 } ?: return stopped()

        // The chain, in sealing order up to its end read above: contiguous, linked, every hash recomputed, in chunks.
        val anchorAt = ours.filter { it.lastSeq > 0 }.groupBy { it.lastSeq }
        var expectedSeq = 1L
        var previous = Genesis
        var rows = 0L
        var lastId: Long? = null
        val matched = mutableSetOf<Anchor>()
        while (expectedSeq <= end) {
            var fetched = 0
            read { tenant.prepareStatement(
                """
                SELECT c.chain_seq, c.id, c.prev_hash, c.row_hash, CASE WHEN a.id IS NULL THEN NULL ELSE $RowHash END
                FROM ONLY public.activity_log_chain c LEFT JOIN ONLY public.activity_log a ON a.id = c.id
                WHERE c.chain_seq >= ? AND c.chain_seq <= ? ORDER BY c.chain_seq LIMIT ?
                """.trimIndent()
            ).use { query ->
                query.setLong(1, expectedSeq)
                query.setLong(2, end)
                query.setInt(3, ChunkRows)
                query.executeQuery().use { result ->
                    while (result.next()) {
                        fetched++
                        val seq = result.getLong(1)
                        val id = result.getLong(2)
                        if (seq != expectedSeq) bad(id, "Faltan las posiciones $expectedSeq a ${seq - 1} de la cadena antes de la fila $id.")
                        if (result.getString(3) != previous) bad(id, "La fila $id no enlaza con la fila anterior de la cadena.")
                        val stored = result.getString(4)
                        val recomputed = result.getString(5)
                        when {
                            recomputed == null -> bad(id, "La fila $id (posicion $seq de la cadena) fue borrada.")
                            stored != recomputed -> bad(id, "La fila $id fue modificada: su hash no coincide con su contenido.")
                            else -> rows++
                        }
                        anchorAt[seq]?.forEach { anchor ->
                            matched += anchor
                            if (anchor.lastHash != stored) bad(id, "La fila $id no coincide con el ancla del ${anchor.at}.")
                        }
                        previous = stored
                        expectedSeq = seq + 1
                        lastId = id
                    }
                }
            } } ?: return stopped()
            if (fetched == 0) break
        }
        val lastSeq = minOf(expectedSeq - 1, end)
        ours.filter { it !in matched && it.lastSeq > 0 }.forEach { anchor ->
            if (anchor.databaseOid != null && anchor.databaseOid != databaseOid && anchor.lastSeq > end) {
                bad(null, "La base de datos de la empresa no es la que tenia el registro anclado el ${anchor.at} (fue reemplazada, o restaurada de una copia anterior a esa fecha).")
            } else {
                bad(null, "Faltan filas: el ancla del ${anchor.at} llegaba a la posicion ${anchor.lastSeq} de la cadena y la cadena llega a $lastSeq.")
            }
        }
        if (ours.any { it.databaseOid != null && it.databaseOid != databaseOid } && matched.any { it.databaseOid != databaseOid }) {
            notes += "La base de datos fue restaurada o recreada (otro oid) con la misma cadena: sus anclas anteriores coinciden."
        }

        // Rows without a seal (written with the trigger disabled, or a setting forged to skip it) past the grace period.
        read { tenant.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT a.id, pg_catalog.count(*) OVER () FROM ONLY public.activity_log a " +
                    "WHERE a.occurred_at < pg_catalog.now() - pg_catalog.make_interval(secs => $UnsealedGraceSeconds) " +
                    "AND NOT EXISTS (SELECT 1 FROM ONLY public.activity_log_chain c WHERE c.id = a.id) ORDER BY a.id LIMIT 20"
            ).use { r ->
                var total = 0L
                while (r.next()) {
                    total = r.getLong(2)
                    bad(r.getLong(1), "La fila ${r.getLong(1)} no esta sellada en la cadena (se escribio sin su disparador).")
                }
                if (total > 20) bad(null, "Hay $total filas sin sellar.")
                rows += total
            }
        } } ?: return stopped()
        val (firstAt, lastAt) = read {
            tenant.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_catalog.min(a.occurred_at)::text, pg_catalog.max(a.occurred_at)::text FROM ONLY public.activity_log a").use { r ->
                    if (r.next()) r.getString(1) to r.getString(2) else null to null
                }
            }
        } ?: return stopped()
        if (more > 0) problems += "Y $more problemas mas."
        return Result(problems.isEmpty(), true, rows, lastSeq, lastId, previous, firstAt, lastAt, databaseOid, firstBad, problems, ours.size, notes)
    }

    private fun scalar(connection: Connection, sql: String): String? =
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }

    internal fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}

/**
 * Anchors and verification of every company's log (ACTIVITY_LOG.md §9): [runAll] is the hourly job; [anchor] also runs
 * when a company is deactivated and [finalAnchor] before it is deleted; [verify] serves GET /server/activity/verify.
 * A failure is written once (per distinct finding, while the server runs) to provisioning_audit_log as
 * activity.integrity_failed and sent to the server owners through [notifyOwners].
 */
class ActivityIntegrityService(
    private val central: DataSource,
    // A connection to a company database as the runtime role (not pooled: a full check reads the whole table).
    private val connect: (databaseName: String) -> Connection?,
    private val notifyOwners: (title: String, text: String) -> Unit = { _, _ -> },
) {
    private val log = LoggerFactory.getLogger(ActivityIntegrityService::class.java)
    private val reported = ConcurrentHashMap<String, String>()

    data class Company(val id: String, val code: String, val databaseName: String, val active: Boolean)

    fun companies(): List<Company> = central.connection.use { connection ->
        connection.prepareStatement("SELECT id::text, code, database_name, is_active FROM companies ORDER BY code").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(Company(rows.getString(1), rows.getString(2), rows.getString(3), rows.getBoolean(4))) } }
        }
    }

    private fun company(code: String): Company? = companies().firstOrNull { it.code == code }

    fun anchors(companyCode: String): List<ActivityIntegrity.Anchor> = central.connection.use { connection ->
        connection.prepareStatement(
            "SELECT details::text, created_at FROM provisioning_audit_log WHERE action = ? AND company_code = ? ORDER BY created_at, id"
        ).use { query ->
            query.setString(1, ActivityIntegrity.AnchorAction)
            query.setString(2, companyCode)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) ActivityIntegrity.Anchor.parse(rows.getString(1), rows.getObject(2, java.time.OffsetDateTime::class.java).toString())?.let(::add) }
            }
        }
    }

    private fun check(company: Company): ActivityIntegrity.Result {
        val tenant = connect(company.databaseName) ?: throw java.sql.SQLException("Tenant ${company.code} not configured", "08001")
        return tenant.use { ActivityIntegrity.check(it, anchors(company.code)) }
    }

    /** Checks one company now (GET /server/activity/verify); a failure is recorded and notified. */
    fun verify(companyCode: String): ActivityIntegrityReport {
        val company = company(companyCode) ?: throw ActivityNotFoundException("Empresa no encontrada: $companyCode.")
        return outcome(company, unreadable(company) { check(company) }).report(company.code)
    }

    /** Checks every company (GET /server/activity/verify without company). */
    fun verifyAll(): List<ActivityIntegrityReport> = companies().map { company -> outcome(company, unreadable(company) { check(company) }).report(company.code) }

    /**
     * Writes an anchor of [companyCode]'s chain as it is now ([reason]: hourly, deactivation...), unless nothing was added
     * since the last one. Never throws: a company that cannot be read is reported as a failure instead.
     */
    fun anchor(companyCode: String, reason: String): ActivityIntegrityReport? = try {
        val company = company(companyCode) ?: return null
        val result = outcome(company, unreadable(company) { check(company) })
        if (result.tablePresent) {
            val last = anchors(company.code).lastOrNull()
            if (last == null || last.lastSeq != result.lastSeq || last.lastHash != result.lastHash || last.databaseOid != result.databaseOid ||
                reason != "hourly"
            ) {
                central.connection.use { connection ->
                    ProvisioningAudit.insert(connection, AuditActor.system(), ActivityIntegrity.AnchorAction, company.code, null, result.anchor(reason).details(company.id))
                }
            }
        }
        result.report(company.code)
    } catch (failure: Exception) {
        // Fails closed: a log that cannot be checked (timeout, lost connection...) is reported, not skipped.
        log.error("Activity log anchor of {} not written", companyCode, failure)
        record(companyCode, listOf("No se pudo verificar el registro de actividad: ${failure.javaClass.simpleName} ${failure.message.orEmpty().take(200)}"), null)
        null
    }

    /**
     * Before a company database is dropped: its final anchor and a compact summary (rows, first and last date) in the server
     * audit. The rows themselves are not archived. Returns the details written, or null when the log could not be read.
     */
    fun finalAnchor(companyCode: String): ActivityIntegrityReport? = try {
        val company = company(companyCode) ?: return null
        val result = outcome(company, unreadable(company) { check(company) })
        if (result.tablePresent) {
            central.connection.use { connection ->
                ProvisioningAudit.insert(
                    connection, AuditActor.system(), ActivityIntegrity.AnchorAction, company.code, null,
                    result.anchor("deletion").details(company.id, result.firstAt, result.lastAt),
                )
            }
        }
        result.report(company.code)
    } catch (failure: Exception) {
        log.error("Final activity log anchor of {} not written", companyCode, failure)
        record(companyCode, listOf("No se pudo escribir el ancla final antes de borrar la empresa: ${failure.javaClass.simpleName} ${failure.message.orEmpty().take(200)}"), null)
        null
    }

    /** The hourly job: every company checked and anchored. */
    fun runAll(): List<ActivityIntegrityReport> = companies().mapNotNull { company -> anchor(company.code, "hourly") }

    /** A problem found outside a check (Core found the table missing after V011): recorded and notified like one. */
    fun alarm(databaseName: String, problem: String) {
        val company = runCatching { companies().firstOrNull { it.databaseName == databaseName } }.getOrNull()
        record(company?.code, listOf(problem), null)
    }

    private fun unreadable(company: Company, block: () -> ActivityIntegrity.Result): ActivityIntegrity.Result = try {
        block()
    } catch (failure: java.sql.SQLException) {
        // An unreachable database is reported as such; a database that does not exist any more is a failure.
        if (failure.sqlState == "3D000") ActivityIntegrity.Result(false, false, 0, 0, null, ActivityIntegrity.Genesis, null, null, 0, null,
            listOf("No existe la base de datos ${company.databaseName} de la empresa."), 0)
        else throw failure
    }

    private fun outcome(company: Company, result: ActivityIntegrity.Result): ActivityIntegrity.Result {
        if (result.ok) reported.remove(company.code) else record(company.code, result.problems, result.firstBadId)
        return result
    }

    private fun record(companyCode: String?, problems: List<String>, firstBadId: Long?) {
        val key = companyCode ?: "-"
        val signature = ActivityIntegrity.sha256(problems.joinToString("\n") + "|" + firstBadId)
        if (reported.put(key, signature) == signature) return
        log.error("Activity log integrity of {} failed: {}", key, problems)
        runCatching {
            central.connection.use { connection ->
                ProvisioningAudit.insert(connection, AuditActor.system(), ActivityIntegrity.FailedAction, companyCode, null,
                    auditDetails("problems" to problems.take(20), "firstBadId" to firstBadId))
            }
        }.onFailure { log.error("activity.integrity_failed of {} not written", key, it) }
        runCatching {
            notifyOwners(
                "Registro de actividad alterado" + (companyCode?.let { " en $it" } ?: ""),
                problems.take(3).joinToString(" ") + (firstBadId?.let { " Primera fila afectada: $it." } ?: ""),
            )
        }.onFailure { log.error("Server owners not notified of the activity log integrity failure of {}", key, it) }
    }
}
