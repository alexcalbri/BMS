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
 * sealed into a hash chain by the database (tenant V011, activity_log_seal); [check] recomputes the whole
 * chain with PostgreSQL's built-in functions only (never the company's own functions, which a compromised
 * role could have replaced), checks the triggers and functions of V011, whether the log is hardened
 * (owned by [AuditRole]) and every anchor the server wrote for that database in provisioning_audit_log.
 */
object ActivityIntegrity {
    const val AuditRole = "bms_audit"
    const val AnchorAction = "activity.anchor"
    const val FailedAction = "activity.integrity_failed"
    const val NotHardenedAction = "activity.not_hardened"
    const val Migration = "V011__create_activity_log"
    val Genesis: String = "0".repeat(64)
    private const val MaxProblems = 50

    // What V011 creates, exactly: a changed body, owner-made trigger or a disabled one is a failure.
    private data class FunctionSpec(val sourceSha256: String, val securityDefiner: Boolean)

    private val functions = mapOf(
        "activity_log_append_only" to FunctionSpec("45089d99a85cd2b976c095af6c9f508be04047e0b2f5c4e32e4238ac105d48d2", false),
        "activity_log_before_insert" to FunctionSpec("4c9cf680b089eb6210c03c60e7ed14c9c21914c862778e42838b5e8b3fd2eacf", false),
        "activity_log_seal" to FunctionSpec("99752b867e206098b8d8b2fa704d8aa0ae8f966a199b12865bf5c357ea5eddfc", true),
    )
    private const val FunctionConfig = "search_path=pg_catalog, pg_temp"

    internal val triggers = mapOf(
        "activity_log_before_insert" to "CREATE TRIGGER activity_log_before_insert BEFORE INSERT ON public.activity_log FOR EACH ROW EXECUTE FUNCTION public.activity_log_before_insert()",
        "activity_log_no_change" to "CREATE TRIGGER activity_log_no_change BEFORE DELETE OR UPDATE ON public.activity_log FOR EACH ROW EXECUTE FUNCTION public.activity_log_append_only()",
        "activity_log_no_truncate" to "CREATE TRIGGER activity_log_no_truncate BEFORE TRUNCATE ON public.activity_log FOR EACH STATEMENT EXECUTE FUNCTION public.activity_log_append_only()",
        "activity_log_seal" to "CREATE CONSTRAINT TRIGGER activity_log_seal AFTER INSERT ON public.activity_log DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.activity_log_seal()",
    )
    private val eventTriggers = setOf("bms_activity_log_no_drop", "bms_activity_log_no_move")

    // The row hash of V011's activity_log_seal, from the stored columns, with built-ins only (search_path is pg_catalog).
    private const val RowHash = """pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(COALESCE(prev_hash, '') || '|' || pg_catalog.jsonb_build_array(
            chain_seq, id, pg_catalog.to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            actor_kind, actor_user_id, actor_name, actor_display, agent_name, real_actor_kind,
            real_actor_user_id, real_actor_name, module_id, entity_type, entity_id, entity_label,
            action, summary, changes, origin)::text, 'UTF8')), 'hex')"""

    /** One anchor: how far the chain of database [databaseOid] reached when the server looked. */
    data class Anchor(
        val lastSeq: Long,
        val lastId: Long?,
        val lastHash: String,
        val count: Long,
        val databaseOid: Long?,
        val hardened: Boolean,
        val reason: String,
        val at: String,
    ) {
        fun details(companyId: String?, firstAt: String? = null, lastAt: String? = null): String = auditDetails(
            "lastSeq" to lastSeq, "lastId" to lastId, "lastHash" to lastHash, "count" to count, "databaseOid" to databaseOid,
            "hardened" to hardened, "reason" to reason, "companyId" to companyId, "firstAt" to firstAt, "lastAt" to lastAt,
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
    ) {
        fun anchor(reason: String): Anchor = Anchor(lastSeq, lastId, lastHash, rows, databaseOid, hardened, reason, Instant.now().toString())

        fun report(companyCode: String): ActivityIntegrityReport = ActivityIntegrityReport(
            companyCode = companyCode, ok = ok, hardened = hardened, rows = rows, lastSeq = lastSeq, lastId = lastId,
            lastHash = lastHash.takeIf { lastSeq > 0 }, anchorsChecked = anchorsChecked, firstBadId = firstBadId, problems = problems,
            notHardenedReasons = notHardened, checkedAt = Instant.now().toString(),
        )
    }

    /**
     * Checks the log of [tenant] (connected as the runtime role) against [anchors] (all anchors of the company, oldest
     * first). Runs in its own read-only transaction and never writes.
     */
    fun check(tenant: Connection, anchors: List<Anchor>, statementTimeoutMillis: Long = 600_000): Result {
        val autoCommit = tenant.autoCommit
        tenant.autoCommit = false
        try {
            tenant.createStatement().use { statement ->
                // One snapshot for the chain, the head and the catalog: rows sealed meanwhile do not look like a mismatch.
                statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
                statement.execute("SET LOCAL statement_timeout = $statementTimeoutMillis")
                // Built-ins only: nothing the company database defines can stand in for them.
                statement.execute("SET LOCAL search_path = pg_catalog, pg_temp")
            }
            return inspect(tenant, anchors)
        } finally {
            runCatching { tenant.rollback() }
            runCatching { tenant.autoCommit = autoCommit }
        }
    }

    private fun inspect(tenant: Connection, anchors: List<Anchor>): Result {
        val problems = mutableListOf<String>()
        var firstBad: Long? = null
        var more = 0
        fun bad(id: Long?, problem: String) {
            if (problems.size < MaxProblems) problems += problem else more++
            if (firstBad == null && id != null) firstBad = id
        }
        val databaseOid = scalar(tenant, "SELECT oid::bigint FROM pg_database WHERE datname = current_database()")!!.toLong()
        val v011 = scalar(
            tenant,
            "SELECT CASE WHEN to_regclass('public.schema_migrations') IS NULL THEN 'false' ELSE (SELECT EXISTS (SELECT 1 FROM public.schema_migrations WHERE module = 'tenant' AND version = '$Migration'))::text END",
        ) == "true"
        val present = scalar(tenant, "SELECT to_regclass('public.activity_log') IS NOT NULL") == "t"
        val ours = anchors.filter { it.databaseOid == databaseOid }
        // The latest anchor of the company was of another database that was not deleted by the server: replaced.
        anchors.lastOrNull()?.let { latest ->
            if (latest.databaseOid != null && latest.databaseOid != databaseOid && latest.reason != "deletion") {
                bad(null, "La base de datos de la empresa no es la que tenia el registro anclado el ${latest.at} (fue borrada o reemplazada).")
            }
        }
        if (!present) {
            if (v011) bad(null, "Falta la tabla activity_log aunque la migracion V011 consta como aplicada.")
            if (ours.isNotEmpty()) bad(null, "Falta la tabla activity_log y hay ${ours.size} anclas de su cadena.")
            return Result(problems.isEmpty(), false, false, listOf("La empresa aun no tiene el registro de actividad (V011)."), 0, 0, null, Genesis,
                null, null, databaseOid, firstBad, problems, ours.size)
        }

        // Functions and triggers exactly as V011 created them.
        val owners = mutableMapOf<String, String>()
        tenant.prepareStatement(
            """
            SELECT p.proname, pg_get_userbyid(p.proowner), encode(sha256(convert_to(p.prosrc, 'UTF8')), 'hex'), p.prosecdef,
                   COALESCE(array_to_string(p.proconfig, ';'), '')
            FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace AND p.proname = ANY (?)
            """.trimIndent()
        ).use { query ->
            query.setArray(1, tenant.createArrayOf("text", functions.keys.toTypedArray()))
            query.executeQuery().use { rows ->
                val seen = mutableSetOf<String>()
                while (rows.next()) {
                    val name = rows.getString(1)
                    val spec = functions.getValue(name)
                    if (!seen.add(name)) bad(null, "La funcion $name tiene una sobrecarga que V011 no crea.")
                    owners[name] = rows.getString(2)
                    if (rows.getString(3) != spec.sourceSha256 || rows.getBoolean(4) != spec.securityDefiner || rows.getString(5) != FunctionConfig) {
                        bad(null, "La funcion $name fue cambiada: no es la de la migracion V011.")
                    }
                }
                (functions.keys - seen).forEach { bad(null, "Falta la funcion $it.") }
            }
        }
        tenant.prepareStatement(
            "SELECT tgname, tgenabled, pg_get_triggerdef(oid) FROM pg_trigger WHERE tgrelid = 'public.activity_log'::regclass AND NOT tgisinternal"
        ).use { query ->
            query.executeQuery().use { rows ->
                val seen = mutableSetOf<String>()
                while (rows.next()) {
                    val name = rows.getString(1)
                    val expected = triggers[name] ?: continue
                    seen += name
                    if (rows.getString(2) !in setOf("O", "A")) bad(null, "El disparador $name esta desactivado (tgenabled = ${rows.getString(2)}).")
                    if (rows.getString(3) != expected) bad(null, "El disparador $name fue cambiado.")
                }
                (triggers.keys - seen).forEach { bad(null, "Falta el disparador $it.") }
            }
        }

        // Hardened: everything belongs to bms_audit, which the runtime role (current_user) is not a member of.
        val notHardened = mutableListOf<String>()
        tenant.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT c.relname, pg_get_userbyid(c.relowner) FROM pg_class c
                WHERE c.oid IN ('public.activity_log'::regclass, 'public.activity_log_id_seq'::regclass)
                   OR c.oid = to_regclass('public.activity_log_chain_head')
                """.trimIndent()
            ).use { rows -> while (rows.next()) owners[rows.getString(1)] = rows.getString(2) }
        }
        owners.filterValues { it != AuditRole }.keys.sorted().forEach { notHardened += "$it pertenece a ${owners[it]}, no a $AuditRole." }
        val audit = scalar(tenant, "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$AuditRole')") == "t"
        if (!audit) notHardened += "No existe el rol $AuditRole."
        if (audit && scalar(tenant, "SELECT pg_has_role(current_user, '$AuditRole', 'MEMBER')") == "t") {
            notHardened += "El rol de la aplicacion es miembro de $AuditRole."
        }
        listOf("UPDATE", "DELETE", "TRUNCATE", "TRIGGER", "REFERENCES").filter { privilege ->
            scalar(tenant, "SELECT has_table_privilege(current_user, 'public.activity_log', '$privilege')") == "t"
        }.takeIf { it.isNotEmpty() }?.let { notHardened += "El rol de la aplicacion tiene ${it.joinToString(", ")} sobre activity_log." }
        val guards = tenant.createStatement().use { statement ->
            statement.executeQuery("SELECT evtname FROM pg_event_trigger WHERE evtenabled <> 'D'").use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        (eventTriggers - guards).sorted().forEach { notHardened += "Falta el disparador de eventos $it." }
        val hardened = notHardened.isEmpty()
        if (!hardened && ours.any { it.hardened }) {
            bad(null, "El registro estaba protegido ($AuditRole) y ya no lo esta: " + notHardened.joinToString(" "))
        }

        // The chain, in sealing order: contiguous, linked, every hash recomputed.
        val anchorAt = ours.groupBy { it.lastSeq }
        var expectedSeq = 1L
        var previous = Genesis
        var rows = 0L
        var lastId: Long? = null
        var firstAt: String? = null
        var lastAt: String? = null
        val matched = mutableSetOf<Anchor>()
        tenant.prepareStatement(
            "SELECT id, chain_seq, prev_hash, row_hash, $RowHash, occurred_at::text FROM public.activity_log ORDER BY chain_seq NULLS LAST, id"
        ).use { query ->
            query.fetchSize = 2_000
            query.executeQuery().use { result ->
                while (result.next()) {
                    rows++
                    val id = result.getLong(1)
                    val seq = result.getObject(2) as Long?
                    val at = result.getString(6)
                    if (firstAt == null || at < firstAt!!) firstAt = at
                    if (lastAt == null || at > lastAt!!) lastAt = at
                    if (seq == null) {
                        bad(id, "La fila $id no esta sellada en la cadena (se escribio sin su disparador).")
                        continue
                    }
                    if (seq != expectedSeq) {
                        if (seq > expectedSeq) bad(id, "Faltan las filas $expectedSeq a ${seq - 1} de la cadena antes de la fila $id.")
                        else bad(id, "La fila $id repite la posicion $seq de la cadena.")
                    }
                    if (result.getString(3) != previous) bad(id, "La fila $id no enlaza con la fila anterior de la cadena.")
                    val stored = result.getString(4)
                    if (stored != result.getString(5)) bad(id, "La fila $id fue modificada: su hash no coincide con su contenido.")
                    anchorAt[seq]?.forEach { anchor ->
                        matched += anchor
                        if (anchor.lastHash != stored) bad(id, "La fila $id no coincide con el ancla del ${anchor.at}.")
                    }
                    previous = stored.orEmpty()
                    expectedSeq = seq + 1
                    lastId = id
                }
            }
        }
        val lastSeq = expectedSeq - 1
        ours.filter { it !in matched && it.lastSeq > 0 }.forEach { anchor ->
            bad(null, "Faltan filas: el ancla del ${anchor.at} llegaba a la posicion ${anchor.lastSeq} de la cadena y la cadena llega a $lastSeq.")
        }
        val head = tenant.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT last_seq, last_hash FROM public.activity_log_chain_head WHERE id = 1"
            ).use { result -> if (result.next()) result.getLong(1) to result.getString(2) else null }
        }
        when {
            head == null -> bad(null, "Falta la cabeza de la cadena (activity_log_chain_head).")
            head.first != lastSeq || head.second != previous ->
                bad(null, "La cabeza de la cadena dice ${head.first} filas y la tabla tiene $lastSeq: se borraron filas del final o se altero la cabeza.")
        }
        if (more > 0) problems += "Y $more problemas mas."
        return Result(
            problems.isEmpty(), true, hardened, notHardened, rows, lastSeq, lastId, previous, firstAt, lastAt, databaseOid,
            firstBad, problems, ours.size,
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
            val last = anchors(company.code).lastOrNull { it.databaseOid == result.databaseOid }
            if (last == null || last.lastSeq != result.lastSeq || last.lastHash != result.lastHash || last.hardened != result.hardened || reason != "hourly") {
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
