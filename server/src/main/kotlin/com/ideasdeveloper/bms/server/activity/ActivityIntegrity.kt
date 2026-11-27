package com.ideasdeveloper.bms.server.activity

import com.ideasdeveloper.bms.ActivityIntegrityReport
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.auditDetails
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 * the whole chain with PostgreSQL's built-in functions only (never the company's own functions, which a compromised
 * role could have replaced), compares every object of the log with V011 (the deviation query shared with
 * scripts/ubuntu/activity-harden.sh: triggers, rules, row security, inheritance, columns, constraints, indexes,
 * functions, owners, grants, per-database settings), and checks every anchor the server wrote for the company in
 * provisioning_audit_log. It reads in short transactions and bounded chunks, and takes no lock that blocks writers.
 */
object ActivityIntegrity {
    const val AuditRole = "bms_audit"
    const val AnchorAction = "activity.anchor"
    const val FailedAction = "activity.integrity_failed"
    const val NotHardenedAction = "activity.not_hardened"
    const val Migration = "V011__create_activity_log"
    val Genesis: String = "0".repeat(64)
    private const val MaxProblems = 50
    private const val ChunkRows = 5_000

    /**
     * The functions of the log as each tenant migration that defines them leaves them (sha256 of prosrc). A release
     * that changes them (a later V0xx, applied to hardened companies by activity-harden.sh) adds its entry and keeps
     * the older ones: a function must be exactly one of these (ACTIVITY_LOG.md §9, "Migrations").
     */
    internal data class AuditVersion(val migration: String, val functions: Map<String, String>)

    internal val versions = listOf(
        AuditVersion(
            Migration,
            mapOf(
                "activity_log_append_only" to "b9a98b70ecff1413d3ddf7b46c3630e4c92c5f822aede3ca6bd962bcfaeda5be",
                "activity_log_before_insert" to "10300c25697cb3b9ca55b5f868f13190e319481156ee4e1d0cca2c8d83aa191c",
                "activity_log_seal" to "42706921fd21093c593ca0ae0dfe6138eade686012183d5543d93051e5459611",
            ),
        ),
    )

    /** sha256 of the body of bms_audit.activity_log_guard() as scripts/ubuntu/activity-harden.sql creates it. */
    internal const val GuardSha256 = "0a092808680c57d8d1dee7a6b9f03fc9ba2856c9095e36ef616407f03c03b742"

    /** The deviation query (resource shared with activity-harden.sh). */
    internal val deviationQuery: String by lazy {
        ActivityIntegrity::class.java.getResourceAsStream("/activity/activity-deviations.sql")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }

    // The row hash of V011's activity_log_seal, from the stored columns, with built-ins only (search_path is pg_catalog).
    private const val RowHash = """pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(c.prev_hash || '|' || pg_catalog.jsonb_build_array(
            c.chain_seq, a.id, pg_catalog.to_char(a.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            a.actor_kind, a.actor_user_id, a.actor_name, a.actor_display, a.agent_name, a.real_actor_kind,
            a.real_actor_user_id, a.real_actor_name, a.module_id, a.entity_type, a.entity_id, a.entity_label,
            a.action, a.summary, a.changes, a.origin)::text, 'UTF8')), 'hex')"""

    /** One anchor: how far the chain of the company reached when the server looked (database oid for information only). */
    data class Anchor(
        val lastSeq: Long,
        val lastId: Long?,
        val lastHash: String,
        val count: Long,
        val databaseOid: Long?,
        val hardened: Boolean,
        val reason: String,
        val at: String,
        val hardenedAt: String? = null,
    ) {
        fun details(companyId: String?, firstAt: String? = null, lastAt: String? = null): String = auditDetails(
            "lastSeq" to lastSeq, "lastId" to lastId, "lastHash" to lastHash, "count" to count, "databaseOid" to databaseOid,
            "hardened" to hardened, "reason" to reason, "companyId" to companyId, "firstAt" to firstAt, "lastAt" to lastAt,
            "hardenedAt" to hardenedAt,
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
                    hardened = (json["hardened"] as? JsonPrimitive)?.booleanOrNull == true,
                    reason = text("reason").orEmpty(),
                    at = at,
                    hardenedAt = text("hardenedAt"),
                )
            }.getOrNull()
        }
    }

    /** What [check] found in one company database. */
    data class Result(
        val ok: Boolean,
        val tablePresent: Boolean,
        val hardened: Boolean,
        val notHardened: List<String>,
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
        val hardenedAt: String? = null,
        val hardenedSeq: Long? = null,
        val notes: List<String> = emptyList(),
    ) {
        fun anchor(reason: String): Anchor =
            Anchor(lastSeq, lastId, lastHash, rows, databaseOid, hardened, reason, Instant.now().toString(), hardenedAt)

        fun report(companyCode: String): ActivityIntegrityReport = ActivityIntegrityReport(
            companyCode = companyCode, ok = ok, hardened = hardened, rows = rows, lastSeq = lastSeq, lastId = lastId,
            lastHash = lastHash.takeIf { lastSeq > 0 }, anchorsChecked = anchorsChecked, firstBadId = firstBadId, problems = problems,
            notHardenedReasons = notHardened, checkedAt = Instant.now().toString(), hardenedAt = hardenedAt, hardenedSeq = hardenedSeq,
            coverage = coverage(), notes = notes,
        )

        /** What the guarantee covers, in one Spanish sentence (ACTIVITY_LOG.md §9). */
        fun coverage(): String = when {
            !tablePresent -> "La empresa aun no tiene registro de actividad."
            hardenedAt == null -> "El registro aun no esta protegido (bms_audit): solo se detectan cambios, no se impiden."
            else -> "Protegido desde $hardenedAt: las filas hasta la posicion ${hardenedSeq ?: 0} de la cadena se escribieron antes " +
                "y no estan cubiertas por la garantia; las posteriores si."
        }
    }

    /**
     * Checks the log of [tenant] (connected as the runtime role) against [anchors] (all anchors of the company, oldest
     * first). Reads in short read-only transactions (catalogs, head) and in chunks of [ChunkRows] chain positions, each
     * its own statement, so no snapshot is held for long and writers never wait; never writes.
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
            }
            return inspect(tenant, anchors)
        } finally {
            runCatching {
                tenant.createStatement().use { statement ->
                    statement.execute("RESET statement_timeout")
                    statement.execute("RESET search_path")
                    statement.execute("RESET default_transaction_read_only")
                    statement.execute("RESET lock_timeout")
                }
            }
            runCatching { tenant.autoCommit = autoCommit }
        }
    }

    private class Head(val lastSeq: Long, val lastHash: String, val hardenedAt: String?, val hardenedSeq: Long?)

    private fun inspect(tenant: Connection, anchors: List<Anchor>): Result {
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var firstBad: Long? = null
        var more = 0
        fun bad(id: Long?, problem: String) {
            if (problems.size < MaxProblems) problems += problem else more++
            if (firstBad == null && id != null) firstBad = id
        }
        val databaseOid = scalar(tenant, "SELECT oid::bigint FROM pg_database WHERE datname = current_database()")!!.toLong()
        val applied = scalar(
            tenant,
            "SELECT CASE WHEN to_regclass('public.schema_migrations') IS NULL THEN '' ELSE (SELECT string_agg(version, ',') FROM public.schema_migrations WHERE module = 'tenant') END",
        ).orEmpty().split(',').toSet()
        val v011 = Migration in applied
        val present = scalar(tenant, "SELECT to_regclass('public.activity_log') IS NOT NULL") == "t"
        // Only the anchors since the company's last deletion (a code used again later starts a new chain).
        val ours = anchors.drop(anchors.indexOfLast { it.reason == "deletion" } + 1)
        if (!present) {
            if (v011) bad(null, "Falta la tabla activity_log aunque la migracion V011 consta como aplicada.")
            if (ours.any { it.lastSeq > 0 }) bad(null, "Falta la tabla activity_log y hay ${ours.size} anclas de su cadena.")
            return Result(problems.isEmpty(), false, false, listOf("La empresa aun no tiene el registro de actividad (V011)."), 0, 0, null, Genesis,
                null, null, databaseOid, firstBad, problems, ours.size)
        }

        // Objects and settings exactly as V011 leaves them (the query activity-harden.sh runs too).
        // The source of every version this release knows is accepted (an older migration's body is still the migration's).
        val sources = versions.flatMap { it.functions.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, hashes) -> hashes.distinct().joinToString("|") }
        val notHardened = mutableListOf<String>()
        tenant.autoCommit = false
        val head: Head?
        try {
            tenant.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED, READ ONLY") }
            tenant.prepareStatement("SELECT set_config('bms.runtime_user', current_user, true), set_config('bms.audit_functions', ?, true)").use { query ->
                query.setString(1, (sources + ("guard" to GuardSha256)).entries.joinToString(";") { "${it.key}=${it.value}" })
                query.executeQuery().close()
            }
            tenant.createStatement().use { statement ->
                statement.executeQuery(deviationQuery).use { rows ->
                    while (rows.next()) {
                        when (rows.getString(1)) {
                            "hardening" -> notHardened += rows.getString(2)
                            else -> bad(null, rows.getString(2))
                        }
                    }
                }
            }
            head = if (scalar(tenant, "SELECT relkind FROM pg_class WHERE oid = to_regclass('public.activity_log_chain_head')") == "r") {
                tenant.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT last_seq, last_hash, to_char(hardened_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'), hardened_seq FROM public.activity_log_chain_head WHERE id = 1"
                    ).use { r -> if (r.next()) Head(r.getLong(1), r.getString(2), r.getString(3), r.getObject(4) as Long?) else null }
                }
            } else null
            tenant.commit()
        } finally {
            runCatching { tenant.rollback() }
            tenant.autoCommit = true
        }
        val hardened = notHardened.isEmpty() && head?.hardenedAt != null
        if (notHardened.isEmpty() && head?.hardenedAt == null) notHardened += "La cabeza de la cadena no dice cuando se protegio el registro."
        if (!hardened && ours.any { it.hardened }) {
            bad(null, "El registro estaba protegido ($AuditRole) y ya no lo esta: " + notHardened.joinToString(" "))
        }
        ours.lastOrNull { it.hardenedAt != null }?.let { anchored ->
            if (head?.hardenedAt != anchored.hardenedAt) {
                bad(null, "La fecha de proteccion del registro cambio (era ${anchored.hardenedAt}, ahora ${head?.hardenedAt ?: "ninguna"}): " +
                    "se desprotegio y se volvio a proteger, y las filas de entre medio no estan cubiertas.")
            }
        }
        if (head == null) {
            bad(null, "Falta la cabeza de la cadena (activity_log_chain_head).")
        }
        val chainPresent = scalar(tenant, "SELECT relkind FROM pg_class WHERE oid = to_regclass('public.activity_log_chain')") == "r"

        // The chain, in sealing order up to the head read above: contiguous, linked, every hash recomputed, in chunks.
        val anchorAt = ours.filter { it.lastSeq > 0 }.groupBy { it.lastSeq }
        val end = head?.lastSeq ?: 0
        var expectedSeq = 1L
        var previous = Genesis
        var rows = 0L
        var lastId: Long? = null
        val matched = mutableSetOf<Anchor>()
        if (chainPresent) {
            while (expectedSeq <= end) {
                var read = 0
                tenant.prepareStatement(
                    """
                    SELECT c.chain_seq, c.id, c.prev_hash, c.row_hash, CASE WHEN a.id IS NULL THEN NULL ELSE $RowHash END
                    FROM public.activity_log_chain c LEFT JOIN public.activity_log a ON a.id = c.id
                    WHERE c.chain_seq >= ? AND c.chain_seq <= ? ORDER BY c.chain_seq LIMIT ?
                    """.trimIndent()
                ).use { query ->
                    query.setLong(1, expectedSeq)
                    query.setLong(2, end)
                    query.setInt(3, ChunkRows)
                    query.executeQuery().use { result ->
                        while (result.next()) {
                            read++
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
                }
                if (read == 0) {
                    bad(null, "Faltan las posiciones $expectedSeq a $end de la cadena (la cabeza dice $end).")
                    break
                }
            }
        }
        val lastSeq = minOf(expectedSeq - 1, end)
        if (head != null && previous != head.lastHash && lastSeq == end) {
            bad(null, "La cabeza de la cadena no coincide con su ultima fila: se borraron filas del final o se altero la cabeza.")
        }
        // Nothing after the head (read again, with the end of the chain, in one snapshot).
        if (chainPresent && head != null) {
            tenant.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT (SELECT max(chain_seq) FROM public.activity_log_chain), h.last_seq FROM public.activity_log_chain_head h WHERE h.id = 1"
                ).use { r ->
                    if (r.next()) {
                        val max = r.getObject(1) as Long?
                        if (max != null && max > r.getLong(2)) bad(null, "La cadena llega a la posicion $max y la cabeza dice ${r.getLong(2)}: se altero la cabeza.")
                    }
                }
            }
        }
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

        // Rows without a seal (written with the trigger disabled, or a setting forged to skip it), and the dates.
        tenant.createStatement().use { statement ->
            statement.executeQuery(
                (if (chainPresent) "SELECT a.id, count(*) OVER () FROM public.activity_log a WHERE NOT EXISTS (SELECT 1 FROM public.activity_log_chain c WHERE c.id = a.id)"
                else "SELECT a.id, count(*) OVER () FROM public.activity_log a") + " ORDER BY a.id LIMIT 20"
            ).use { r ->
                var total = 0L
                while (r.next()) {
                    total = r.getLong(2)
                    bad(r.getLong(1), "La fila ${r.getLong(1)} no esta sellada en la cadena (se escribio sin su disparador).")
                }
                if (total > 20) bad(null, "Hay $total filas sin sellar.")
                rows += total
            }
        }
        val (firstAt, lastAt) = tenant.createStatement().use { statement ->
            statement.executeQuery("SELECT min(occurred_at)::text, max(occurred_at)::text FROM public.activity_log").use { r ->
                if (r.next()) r.getString(1) to r.getString(2) else null to null
            }
        }
        if (more > 0) problems += "Y $more problemas mas."
        return Result(
            problems.isEmpty(), true, hardened, notHardened, rows, lastSeq, lastId, previous, firstAt, lastAt, databaseOid,
            firstBad, problems, ours.size, head?.hardenedAt, head?.hardenedSeq, notes,
        )
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
    @Volatile private var notHardenedReported: String? = null

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
            if (last == null || last.lastSeq != result.lastSeq || last.lastHash != result.lastHash || last.hardened != result.hardened ||
                last.hardenedAt != result.hardenedAt || last.databaseOid != result.databaseOid || reason != "hourly"
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

    /** The hourly job: every company checked and anchored; the ones not hardened yet are reported once per change. */
    fun runAll(): List<ActivityIntegrityReport> {
        val reports = companies().mapNotNull { company -> anchor(company.code, "hourly") }
        val notHardened = reports.filter { !it.hardened }.map { it.companyCode }
        val signature = notHardened.joinToString(",")
        if (notHardened.isNotEmpty()) {
            log.warn("Activity log not hardened yet (run scripts/ubuntu/activity-harden.sh as root): {}", notHardened)
        }
        if (signature != notHardenedReported) {
            notHardenedReported = signature
            runCatching {
                central.connection.use { connection ->
                    ProvisioningAudit.insert(connection, AuditActor.system(), ActivityIntegrity.NotHardenedAction, null, null,
                        auditDetails("companies" to notHardened, "count" to notHardened.size))
                }
            }.onFailure { log.warn("Not-hardened report not written", it) }
        }
        return reports
    }

    /** A problem found outside a check (Core found the table missing after V011): recorded and notified like one. */
    fun alarm(databaseName: String, problem: String) {
        val company = runCatching { companies().firstOrNull { it.databaseName == databaseName } }.getOrNull()
        record(company?.code, listOf(problem), null)
    }

    private fun unreadable(company: Company, block: () -> ActivityIntegrity.Result): ActivityIntegrity.Result = try {
        block()
    } catch (failure: java.sql.SQLException) {
        // An unreachable database is reported as such; a database that does not exist any more is a failure.
        if (failure.sqlState == "3D000") ActivityIntegrity.Result(false, false, false, listOf("No existe la base de datos."), 0, 0, null,
            ActivityIntegrity.Genesis, null, null, 0, null, listOf("No existe la base de datos ${company.databaseName} de la empresa."), 0)
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
