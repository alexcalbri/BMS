package com.ideasdeveloper.bms.modules.clientes

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.CompanyLinkException
import com.ideasdeveloper.bms.server.modules.GroupCompany
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModuleJob
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.sql.Savepoint
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

data class LinkJob(
    val from: GroupCompany,
    val to: GroupCompany,
    val status: String,
    val copied: Int,
    val merged: Int,
    val review: Int,
    val total: Int,
    val error: String,
)

data class LinkStatus(
    val linked: List<GroupCompany>,
    val candidates: List<GroupCompany>,
    val jobs: List<LinkJob>,
    val reviewCount: Int,
)

data class MergeItem(
    val id: UUID,
    // Company whose database holds the decision (where the merged customer was created).
    val company: GroupCompany,
    val keptId: UUID,
    val keptName: String,
    val keptCompany: String,
    val mergedId: UUID,
    val mergedName: String,
    val email: String,
    val status: String,
    val reason: String,
    // Why a pair is in review: name_differs, accounts, code, incomplete, copy, edit ('' for a merge done).
    val reasonCode: String,
    val at: OffsetDateTime,
)

/**
 * Linked companies share every customer (Core company_links, purpose [LinkPurpose]). Each company
 * copies its own customers to the others in the background ([job]): a first full copy per destination
 * (customer_link_jobs.cursor_id, by id) and then the pending pass (an own customer with shared = FALSE is
 * one not copied yet). Both work in batches of [BatchSize], each batch in ONE transaction per database, so
 * thousands of customers never block a request and the work survives a restart. A customer with the email
 * and the same name of an active customer of another linked company is merged with it (ClientesMerge); the
 * same email with another name, or anything that cannot be copied or merged, waits in "Por revisar".
 * Safety: a company only reads or writes the databases of the companies linked with it at that moment, and
 * sees the copies of a company only while that company is in its customer_link_origins.
 */
object ClientesLinking {
    const val LinkPurpose: String = "clientes"

    private val log = LoggerFactory.getLogger(ClientesLinking::class.java)

    // Short batches: the group lock is released between them so a request that waits gets its turn.
    // 50: every row opens a savepoint (a subtransaction); more than 64 per transaction overflows PostgreSQL's subxid cache.
    const val BatchSize = 50
    private val RunBudgetNanos = TimeUnit.SECONDS.toNanos(20)

    // How long an HTTP route waits for the group lock before answering that a copy is running.
    private const val HttpLockWaitMillis = 5_000L
    // Shown to the owner: marked with msg() and translated (tr) where it is thrown and where it is compared.
    val BusyMessage = msg("Hay una copia en curso, intenta en un minuto.")

    // Generic error stored in customer_link_jobs.error (Spanish, as stored); the screen translates it with tr().
    private val ProcessFailed = msg("Un cliente no se pudo procesar; el detalle esta en el registro del servidor.")

    // Who the activity log names for what the background job does (a person's own change that triggers sharing uses ClientesLog.sharingActor).
    private val JobActor: ActivityActor get() = ActivityActor.job("Compartir clientes")

    /** What happened to one customer when it was copied to one company. */
    enum class ShareOutcome { Copied, Merged, Review, Skipped }

    // ---- Locks ------------------------------------------------------------------------------------

    // One FAIR lock per owner group (named by the smallest code of the active companies of the group), shared by the job, shareNew,
    // separate, retry, mergeManually and propagate. Fair: a request that waits gets the lock between two batches of the job.
    // The key comes from groupCompanies (active companies only); a company that is deactivated could change it for a moment.
    // TODO(Core): an ownerGroupKey(code) that includes inactive companies would make it fully stable.
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    // The key of an owner is cached: if the central database fails for a moment the lock must not fall back to another key.
    private val lockKeys = ConcurrentHashMap<String, String>()

    private fun lockFor(access: ModuleAccessService, code: String): ReentrantLock {
        val key = try {
            ((access.groupCompanies(code).map { it.code }) + code).minOrNull()!!.also { lockKeys[code] = it }
        } catch (failure: Exception) {
            lockKeys[code] ?: throw failure
        }
        return locks.computeIfAbsent(key) { ReentrantLock(true) }
    }

    /** Runs [block] holding the group lock when it is free within [millis]. Never runs without it: null when it was not obtained. */
    fun <T : Any> withShortLock(access: ModuleAccessService, code: String, millis: Long = 1_500, block: () -> T): T? {
        val lock = lockFor(access, code)
        if (!lock.tryLock(millis, TimeUnit.MILLISECONDS)) return null
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    // HTTP routes never wait without a limit: busy means a copy is running.
    private fun <T : Any> withHttpLock(access: ModuleAccessService, code: String, block: () -> T): T =
        withShortLock(access, code, HttpLockWaitMillis, block) ?: throw ClientesSharingException(tr(BusyMessage))

    /** Class and SQLState of a failure for the log: the SQL message carries customer data (emails), so it is never logged. */
    fun describe(failure: Throwable?): String {
        var current = failure
        var depth = 0
        while (current != null && depth++ < 8) {
            if (current is SQLException) return "${current.javaClass.simpleName} sqlState=${current.sqlState ?: "-"}"
            current = current.cause
        }
        return failure?.javaClass?.simpleName ?: "-"
    }

    // ---- Pure rules (tested without a database) ---------------------------------------------------

    /** Own customers not shared yet, oldest id first: what other modules inserted directly, or what a failed attempt left. Merged losers and open reviews are left out. */
    fun pendingSql(withCursor: Boolean, limit: Int = BatchSize): String =
        "SELECT ${ClientesSharing.RowColumns} FROM customers WHERE origin_company_code = '' AND NOT shared" +
            " AND NOT EXISTS (SELECT 1 FROM customer_merges m WHERE m.merged_id = customers.id AND m.status IN ('merged', 'review'))" +
            (if (withCursor) " AND id > ?" else "") + " ORDER BY id LIMIT $limit"

    /** Every own customer (archived included) after the cursor of one destination, by id: the first full copy. */
    fun ownAfterSql(withCursor: Boolean, limit: Int = BatchSize): String =
        "SELECT ${ClientesSharing.RowColumns} FROM customers WHERE origin_company_code = ''" +
            (if (withCursor) " AND id > ?" else "") + " ORDER BY id LIMIT $limit"

    /** A customer is marked shared only when it reached every other linked company (copied, merged or deliberately skipped). */
    fun fullyShared(outcomes: Collection<ShareOutcome>, expected: Int): Boolean =
        expected > 0 && outcomes.size == expected && outcomes.none { it == ShareOutcome.Review }

    /** Cursor of a destination after a batch: it moves only when the destination was reached; a destination that is down keeps it. */
    fun cursorAfter(current: UUID?, lastDone: UUID?, reached: Boolean): UUID? = if (reached && lastDone != null) lastDone else current

    /** Companies whose copies [self] sees: the other linked ones, none when it is not linked with anybody. */
    fun desiredOrigins(group: Collection<String>, self: String): Set<String> =
        if (group.size <= 1) emptySet() else group.filter { it != self }.toSet()

    /** Rows of customer_link_origins to add and to remove so that [current] becomes [desired]; both empty means nothing is written. */
    fun originChanges(current: Set<String>, desired: Set<String>): Pair<Set<String>, Set<String>> = (desired - current) to (current - desired)

    /** Stable id of the "could not copy to [targetCode]" review of a customer: one row per target, never overwritten by another target. */
    fun copyReviewKey(customerId: UUID, targetCode: String): UUID =
        UUID.nameUUIDFromBytes("$customerId:$targetCode".toByteArray(Charsets.UTF_8))

    /** Whether the review row is a "could not copy" one rather than a pair of customers. */
    fun isCopyReview(mergedId: UUID, keptId: UUID, keptCompany: String): Boolean = keptId == copyReviewKey(mergedId, keptCompany)

    /** Targets that have no job row yet. */
    fun missingTargets(existing: Set<String>, wanted: List<String>): List<String> = wanted.filter { it !in existing }

    /** Job rows whose destination is no longer linked. */
    fun staleTargets(existing: Set<String>, wanted: List<String>): List<String> = existing.filter { it !in wanted }

    /** Whether this company's database has the V008 and V010 tables (its Clientes is up to date). */
    fun supportsLinking(tenant: Connection): Boolean =
        tenant.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT to_regclass('customer_merges') IS NOT NULL AND to_regclass('customer_link_jobs') IS NOT NULL AND to_regclass('customer_link_origins') IS NOT NULL"
            ).use { rows -> rows.next() && rows.getBoolean(1) }
        }

    /**
     * Writes a row of customer_activity; [customerId] null is an event of the company. user_id is only valid in the author's own company.
     * The same event goes to the company activity log of [db] ([action], customer named [label]), in the same transaction.
     */
    fun activity(
        db: Connection,
        customerId: UUID?,
        kind: String,
        body: String,
        author: ClientesAuthor,
        withUser: Boolean,
        label: String = "",
        action: String = ActivityActions.Updated,
    ) {
        db.prepareStatement(
            "INSERT INTO customer_activity (id, customer_id, kind, body, user_id, author) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, customerId)
            insert.setString(3, kind)
            insert.setString(4, body.take(5_000))
            insert.setObject(5, if (withUser) author.userId else null)
            insert.setString(6, author.name)
            insert.executeUpdate()
        }
        if (customerId == null) {
            ClientesLog.record(db, author.actor, ClientesLog.CompanyLink, null, label, ActivityActions.SettingsChanged, body)
        } else {
            ClientesLog.record(db, author.actor, ClientesLog.Customer, customerId, label.ifBlank { ClientesLog.customerName(db, customerId) }, action, body)
        }
    }

    // ---- Status -----------------------------------------------------------------------------------

    fun status(access: ModuleAccessService, currentCode: String): LinkStatus {
        val linked = access.linkedCompanies(currentCode, LinkPurpose)
        val candidates = access.groupCompanies(currentCode).filter { company -> company.code != currentCode && linked.none { it.code == company.code } }
        val names = linked.associateBy { it.code }
        val jobs = mutableListOf<LinkJob>()
        var reviewCount = 0
        if (linked.size > 1) {
            linked.forEach { company ->
                try {
                    access.tenantConnectionForCompany(company.code)?.use { db ->
                        if (!supportsLinking(db)) return@use
                        db.prepareStatement(
                            "SELECT target_company_code, status, copied, merged, review, total, error FROM customer_link_jobs ORDER BY target_company_code"
                        ).use { query ->
                            query.executeQuery().use { rows ->
                                while (rows.next()) {
                                    val target = names[rows.getString("target_company_code")]
                                    if (target != null) {
                                        jobs += LinkJob(company, target, rows.getString("status"), rows.getInt("copied"), rows.getInt("merged"), rows.getInt("review"), rows.getInt("total"), rows.getString("error"))
                                    }
                                }
                            }
                        }
                        reviewCount += count(db, "SELECT count(*) FROM customer_merges WHERE status = 'review'")
                    }
                } catch (failure: Exception) {
                    log.warn("Could not read the link status of company {} ({})", company.code, describe(failure))
                }
            }
        }
        return LinkStatus(linked, candidates, jobs, reviewCount)
    }

    private fun count(db: Connection, sql: String): Int =
        db.createStatement().use { statement -> statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getInt(1) else 0 } }

    // ---- Link and unlink --------------------------------------------------------------------------

    // The database of [code]: the request's own connection for the current company. Null when it does not open.
    // Not ClientesSharing.onCompany on purpose: unlink must reach a company that is no longer linked.
    private fun <T> openFor(access: ModuleAccessService, tenant: Connection, currentCode: String, code: String, block: (Connection) -> T): T? =
        if (code == currentCode) block(tenant) else access.tenantConnectionForCompany(code)?.use(block)

    // One try in one company; false (and a log without SQL detail) when it did not open or failed.
    private fun attempt(access: ModuleAccessService, tenant: Connection, currentCode: String, code: String, block: (Connection) -> Unit): Boolean =
        try {
            openFor(access, tenant, currentCode, code) { db ->
                if (supportsLinking(db)) ClientesLog.atomically(db) { block(db) }
                Unit
            } != null
        } catch (failure: ActivityWriteException) {
            // The activity row is part of the change: when it cannot be written the request fails (never swallowed).
            throw failure
        } catch (failure: Exception) {
            log.warn("Could not update the link data of company {} ({})", code, describe(failure))
            false
        }

    // Makes customer_link_origins of [db] say exactly [wanted]; reads first and writes only the difference.
    private fun reconcileOrigins(db: Connection, wanted: Set<String>) {
        val current = db.createStatement().use { statement ->
            statement.executeQuery("SELECT company_code FROM customer_link_origins").use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        val (add, remove) = originChanges(current, wanted)
        if (add.isNotEmpty()) {
            db.prepareStatement("INSERT INTO customer_link_origins (company_code) VALUES (?) ON CONFLICT DO NOTHING").use { insert ->
                add.forEach { code ->
                    insert.setString(1, code)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
        }
        if (remove.isNotEmpty()) {
            db.prepareStatement("DELETE FROM customer_link_origins WHERE company_code = ANY (?)").use { delete ->
                delete.setArray(1, db.createArrayOf("varchar", remove.toTypedArray()))
                delete.executeUpdate()
            }
        }
    }

    /**
     * Links [targetCode] with [currentCode] (the Core checks both belong to the same owner and are
     * active). Nothing is copied here: the job does it. Records the event, creates the pending jobs and
     * adds the origins (one row per company) in every company of the group; a company that fails is tried
     * once more at the end of the request and, if it still fails, the job reconciles it within a minute.
     * It takes no group lock: it only writes a few small rows, never customers. (unlink does take it.)
     */
    fun link(access: ModuleAccessService, tenant: Connection, currentCode: String, targetCode: String, author: ClientesAuthor): String {
        val group = try {
            access.linkCompany(currentCode, targetCode, LinkPurpose, author.userId?.toString() ?: author.name)
        } catch (failure: CompanyLinkException) {
            throw ClientesSharingException(failure.message ?: tr("No se pudo vincular la empresa."))
        }
        val names = group.associate { it.code to it.name }
        val targetName = names[targetCode] ?: targetCode
        val codes = group.map { it.code }
        fun prepare(company: GroupCompany): Boolean = attempt(access, tenant, currentCode, company.code) { db ->
            reconcileOrigins(db, desiredOrigins(codes, company.code))
            createJobs(db, group.filter { it.code != company.code })
            when (company.code) {
                currentCode -> activity(db, null, "linked", "Se vinculo con $targetName: los clientes se comparten entre las empresas vinculadas.", author, withUser = true, label = targetName)
                targetCode -> activity(db, null, "linked", "Se vinculo con ${names[currentCode] ?: currentCode}: los clientes se comparten entre las empresas vinculadas.", author, withUser = false, label = names[currentCode] ?: currentCode)
                else -> Unit
            }
        }
        val failed = group.filter { !prepare(it) }
        failed.forEach { prepare(it) }
        return tr("Se vinculo {0}. Todos los clientes se estan compartiendo en segundo plano; puede tardar unos minutos y lo que no se pueda unir queda en Por revisar.", targetName)
    }

    /**
     * Unlinks [targetCode] (it may be [currentCode], or a company that was deactivated) from the group;
     * the Core validates it. Hiding is instant and writes no customer: the origin row of the unlinked company is
     * removed from customer_link_origins of every company that stays (and all of them from the unlinked one), and
     * the jobs towards it are deleted. Merges already done are kept. A database that cannot be opened is tried once
     * more at the end of the request; if it still fails the job of that company reconciles it within a minute.
     */
    fun unlink(access: ModuleAccessService, tenant: Connection, currentCode: String, targetCode: String, author: ClientesAuthor): String =
        // Under the group lock: a batch of the job never copies to a company while it is being unlinked.
        withHttpLock(access, currentCode) { unlinkLocked(access, tenant, currentCode, targetCode, author) }

    private fun unlinkLocked(access: ModuleAccessService, tenant: Connection, currentCode: String, targetCode: String, author: ClientesAuthor): String {
        val before = access.linkedCompanies(currentCode, LinkPurpose)
        val targetName = before.firstOrNull { it.code == targetCode }?.name
            ?: runCatching { access.groupCompanies(currentCode).firstOrNull { it.code == targetCode }?.name }.getOrNull()
            ?: targetCode
        try {
            access.unlinkCompany(currentCode, targetCode, LinkPurpose)
        } catch (failure: CompanyLinkException) {
            throw ClientesSharingException(failure.message ?: tr("No se pudo desvincular la empresa."))
        }
        val remaining = before.filter { it.code != targetCode }
        val remainingCodes = remaining.map { it.code }
        val currentName = before.firstOrNull { it.code == currentCode }?.name ?: currentCode
        fun hide(code: String): Boolean = attempt(access, tenant, currentCode, code) { db ->
            val isTarget = code == targetCode
            reconcileOrigins(db, if (isTarget) emptySet() else desiredOrigins(remainingCodes, code))
            if (isTarget || remaining.size <= 1) {
                db.prepareStatement("DELETE FROM customer_link_jobs").use { it.executeUpdate() }
            } else {
                db.prepareStatement("DELETE FROM customer_link_jobs WHERE target_company_code = ?").use { delete ->
                    delete.setString(1, targetCode)
                    delete.executeUpdate()
                }
            }
            if (isTarget) {
                activity(db, null, "unlinked", "Se desvinculo de ${if (targetCode == currentCode) remaining.joinToString { it.name } else currentName}: los clientes de las otras empresas dejan de verse.", author, withUser = targetCode == currentCode, label = if (targetCode == currentCode) remaining.joinToString { it.name } else currentName)
            } else if (code == currentCode || targetCode == currentCode) {
                activity(db, null, "unlinked", "Se desvinculo $targetName.", author, withUser = code == currentCode, label = targetName)
            }
        }
        // The current company first: its own view changes at once.
        val order = (listOf(currentCode) + remainingCodes + targetCode).distinct()
        val failed = order.filter { !hide(it) }
        failed.forEach { hide(it) }
        return tr("Se desvinculo {0}. Sus clientes dejan de verse entre las dos empresas; las fusiones ya hechas se conservan.", targetName)
    }

    // Creates the job rows of new destinations (no customer is rewritten: the first full copy starts at cursor NULL).
    private fun createJobs(db: Connection, targets: List<GroupCompany>) {
        val existing = db.createStatement().use { statement ->
            statement.executeQuery("SELECT target_company_code FROM customer_link_jobs").use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        insertJobs(db, missingTargets(existing, targets.map { it.code }))
    }

    private fun insertJobs(db: Connection, missing: List<String>) {
        if (missing.isEmpty()) return
        db.prepareStatement("INSERT INTO customer_link_jobs (target_company_code) VALUES (?) ON CONFLICT (target_company_code) DO NOTHING").use { insert ->
            missing.forEach { code ->
                insert.setString(1, code)
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    // ---- Share a new customer ---------------------------------------------------------------------

    /**
     * Right after a customer is created in [currentCode], tries to copy it to the linked companies. If
     * the lock is busy (about 2 seconds), a company does not open or anything fails it does nothing
     * more: the pending pass of the job copies every own customer that is not shared yet.
     */
    fun shareNew(access: ModuleAccessService, tenant: Connection, currentCode: String, customerId: UUID, by: ActivityActor) {
        try {
            if (!supportsLinking(tenant)) return
            val row = ClientesSharing.row(tenant, customerId) ?: return
            shareBatch(access, lockFor(access, currentCode), currentCode, tenant, listOf(row), Long.MAX_VALUE, HashMap(), lockWaitMillis = 2_000, only = null, actor = ClientesLog.sharingActor(by))
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: ActivityWriteException) {
            throw failure
        } catch (failure: Exception) {
            log.warn("Could not share the new customer {} right now; the job will ({})", customerId, describe(failure))
        }
    }

    // ---- Background job ---------------------------------------------------------------------------

    // longRunning: one run can take up to ~20 seconds (several batches).
    val job: ModuleJob = ModuleJob("link-sync", Duration.ofMinutes(1), longRunning = true) { context ->
        sync(context.access, context.tenant, context.companyCode)
    }

    private class JobRow(val target: String, val status: String, val cursor: UUID?)

    /**
     * One bounded run (about 20 seconds) for company [code]: reconcile customer_link_origins with the Core (cheap:
     * it compares the sets before writing), reconcile the job rows, the first full copy of each destination that is
     * not done (by cursor), then the pending pass. A destination that is down keeps its cursor; nothing is repeated
     * from the start. A customer left in review (customer_merges with a copyReviewKey, or the pair) is excluded from
     * the pending pass until the owner resolves it with Reintentar, Fusionar o Separar.
     */
    fun sync(access: ModuleAccessService, tenant: Connection, code: String) {
        if (!supportsLinking(tenant)) return
        try {
            val group = access.linkedCompanies(code, LinkPurpose)
            val others = group.filter { it.code != code }
            reconcileOrigins(tenant, desiredOrigins(group.map { it.code }, code))
            val jobs = syncJobRows(tenant, others)
            if (others.isEmpty()) return
            // A customer merged into another is never copied again.
            tenant.createStatement().use {
                it.executeUpdate("UPDATE customers SET shared = FALSE WHERE shared AND origin_company_code = '' AND id IN (SELECT merged_id FROM customer_merges WHERE status = 'merged')")
            }
            val deadline = System.nanoTime() + RunBudgetNanos
            val lock = lockFor(access, code)
            val keyCache = HashMap<String, List<ClientesMerge.ForeignKey>>()
            for (job in jobs.filter { it.status != "done" }) {
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) return
                initialCopy(access, lock, code, tenant, job, deadline, keyCache)
            }
            sharePending(access, lock, code, tenant, deadline, keyCache)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: ClientesSharingException) {
            log.warn("The link job of company {} stopped: {}", code, failure.message)
        } catch (failure: Exception) {
            log.warn("The link job of company {} failed ({})", code, describe(failure))
        }
    }

    // Deletes the job rows of companies no longer linked and creates the missing ones (pending, cursor NULL); writes only when something differs.
    private fun syncJobRows(tenant: Connection, others: List<GroupCompany>): List<JobRow> {
        val rows = tenant.createStatement().use { statement ->
            statement.executeQuery("SELECT target_company_code, status, cursor_id FROM customer_link_jobs").use { result ->
                buildList { while (result.next()) add(JobRow(result.getString(1), result.getString(2), result.getObject(3, UUID::class.java))) }
            }
        }
        val wanted = others.map { it.code }
        val stale = staleTargets(rows.map { it.target }.toSet(), wanted)
        if (stale.isNotEmpty()) {
            tenant.prepareStatement("DELETE FROM customer_link_jobs WHERE target_company_code = ANY (?)").use { delete ->
                delete.setArray(1, tenant.createArrayOf("varchar", stale.toTypedArray()))
                delete.executeUpdate()
            }
        }
        val missing = missingTargets(rows.map { it.target }.toSet(), wanted)
        insertJobs(tenant, missing)
        return rows.filter { it.target in wanted } + missing.map { JobRow(it, "pending", null) }
    }

    // Counters of one batch per target: copied, merged, review.
    private class Tally {
        val counts = HashMap<String, IntArray>()
        fun add(target: String, outcome: ShareOutcome) {
            val c = counts.getOrPut(target) { IntArray(3) }
            when (outcome) {
                ShareOutcome.Copied -> c[0]++
                ShareOutcome.Merged -> c[1]++
                ShareOutcome.Review -> c[2]++
                ShareOutcome.Skipped -> Unit
            }
        }
    }

    // Marks the companies that did not open on their job row (their cursor is not touched).
    private fun flushErrors(tenant: Connection, unavailable: Map<String, String>) {
        unavailable.forEach { (target, message) ->
            tenant.prepareStatement("UPDATE customer_link_jobs SET status = 'error', error = ?, updated_at = CURRENT_TIMESTAMP WHERE target_company_code = ?").use { update ->
                update.setString(1, message.take(1_900))
                update.setString(2, target)
                update.executeUpdate()
            }
        }
    }

    // Counters and cursor of the first full copy of [only], written in the same transaction as the copies (so they never disagree).
    private fun writeProgress(tenant: Connection, tally: Tally, unavailable: Map<String, String>, only: String?, lastDone: UUID?) {
        if (only != null) {
            val c = tally.counts[only] ?: IntArray(3)
            tenant.prepareStatement(
                "UPDATE customer_link_jobs SET copied = copied + ?, merged = merged + ?, review = review + ?, cursor_id = COALESCE(?, cursor_id), updated_at = CURRENT_TIMESTAMP WHERE target_company_code = ?"
            ).use { update ->
                update.setInt(1, c[0])
                update.setInt(2, c[1])
                update.setInt(3, c[2])
                update.setObject(4, lastDone)
                update.setString(5, only)
                update.executeUpdate()
            }
        }
        flushErrors(tenant, unavailable)
    }

    private fun ownRowsAfter(tenant: Connection, after: UUID?): List<ClientesSharing.Row> =
        tenant.prepareStatement(ownAfterSql(withCursor = after != null)).use { query ->
            if (after != null) query.setObject(1, after)
            query.executeQuery().use { result -> buildList { while (result.next()) add(ClientesSharing.readRow(result)) } }
        }

    private fun pendingRows(tenant: Connection, after: UUID?): List<ClientesSharing.Row> =
        tenant.prepareStatement(pendingSql(withCursor = after != null)).use { query ->
            if (after != null) query.setObject(1, after)
            query.executeQuery().use { result -> buildList { while (result.next()) add(ClientesSharing.readRow(result)) } }
        }

    // First full copy of every own customer to one destination, from its cursor; ends with the job done.
    private fun initialCopy(
        access: ModuleAccessService,
        lock: ReentrantLock,
        code: String,
        tenant: Connection,
        job: JobRow,
        deadline: Long,
        keyCache: MutableMap<String, List<ClientesMerge.ForeignKey>>,
    ) {
        var cursor = job.cursor
        tenant.prepareStatement(
            "UPDATE customer_link_jobs SET status = 'running', error = '', total = CASE WHEN cursor_id IS NULL THEN (SELECT count(*) FROM customers WHERE origin_company_code = '') ELSE total END, updated_at = CURRENT_TIMESTAMP WHERE target_company_code = ?"
        ).use { update ->
            update.setString(1, job.target)
            update.executeUpdate()
        }
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
            val rows = ownRowsAfter(tenant, cursor)
            if (rows.isEmpty()) {
                tenant.prepareStatement("UPDATE customer_link_jobs SET status = 'done', error = '', updated_at = CURRENT_TIMESTAMP WHERE target_company_code = ?").use { update ->
                    update.setString(1, job.target)
                    update.executeUpdate()
                }
                return
            }
            val result = shareBatch(access, lock, code, tenant, rows, deadline, keyCache, lockWaitMillis = 500, only = job.target, actor = JobActor)
            if (result.stop) return
            cursor = cursorAfter(cursor, result.lastDone, reached = true)
        }
    }

    // Batches of own customers not copied yet; each batch holds the lock and its connections only while it runs.
    // Where the pending pass of each company stopped (in memory): the next run goes on from there and starts again after the end,
    // so customers that cannot be finished (a destination is down) are not reprocessed from the start every minute.
    private val pendingCursors = ConcurrentHashMap<String, UUID>()

    private fun sharePending(
        access: ModuleAccessService,
        lock: ReentrantLock,
        code: String,
        tenant: Connection,
        deadline: Long,
        keyCache: MutableMap<String, List<ClientesMerge.ForeignKey>>,
    ) {
        var after: UUID? = pendingCursors[code]
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
            val rows = pendingRows(tenant, after)
            if (rows.isEmpty()) {
                pendingCursors.remove(code)
                return
            }
            val result = shareBatch(access, lock, code, tenant, rows, deadline, keyCache, lockWaitMillis = 500, only = null, actor = JobActor)
            if (result.stop) return
            val done = result.lastDone ?: return
            after = done
            pendingCursors[code] = done
            if (rows.size < BatchSize && done == rows.last().id) {
                pendingCursors.remove(code)
                return
            }
        }
    }

    // lastDone: id of the last row the batch finished (null when none); stop: the pass must end (lock busy, deadline, a company went down).
    private class BatchResult(val lastDone: UUID?, val stop: Boolean)

    // Commits the targets first and the own database (cursor, marks, reviews) last, so a failure repeats work instead of losing it.
    private fun commitAll(all: Map<String, Connection>, tenant: Connection) {
        all.values.filter { it !== tenant && !it.autoCommit }.forEach { it.commit() }
        if (!tenant.autoCommit) tenant.commit()
    }

    // What a batch tells the code below it: how to commit the batch so far, and how many commits happened (a commit, also the one a merge
    // makes by itself, destroys every savepoint taken before it, so a savepoint is only used while the generation is the same).
    private class BatchHooks(val commit: () -> Unit) {
        var generation = 0
        fun committed() { generation++ }
    }

    private class Mark(val db: Connection, val savepoint: Savepoint?, val generation: Int)

    private fun mark(db: Connection, hooks: BatchHooks): Mark = Mark(db, if (!db.autoCommit) db.setSavepoint() else null, hooks.generation)

    private fun release(mark: Mark?, hooks: BatchHooks) {
        if (mark?.savepoint != null && mark.generation == hooks.generation) runCatching { mark.db.releaseSavepoint(mark.savepoint) }
    }

    // Undoes what happened since [mark]: back to its savepoint, or (when a commit destroyed it) the uncommitted rest of the transaction.
    private fun undo(db: Connection, mark: Mark?, hooks: BatchHooks) {
        if (db.autoCommit) return
        try {
            if (mark?.savepoint != null && mark.generation == hooks.generation) db.rollback(mark.savepoint) else db.rollback()
        } catch (failure: SQLException) {
            runCatching { db.rollback() }
        }
    }

    private fun rollbackAll(all: Map<String, Connection>) {
        all.values.forEach { connection -> runCatching { if (!connection.autoCommit) connection.rollback() } }
    }

    /**
     * Copies [rows] (own customers) to every other linked company, or only to [only] (the first full copy of that
     * destination). Order: wait for the group lock (short; busy means stop), read the linked group once, open each
     * destination once, put every database in ONE transaction, process the rows (a savepoint per row and destination,
     * so a bad row becomes a review without losing the batch), then commit targets first and the own database last.
     * Stops early (without losing what was done) when the deadline passes or a request is waiting for the lock. A
     * destination that does not open at the start is skipped (its error is written) and the others go on; one that is
     * lost in the middle rolls the whole batch back: nothing advances, its cursor stays where it was. Always releases
     * the lock and closes the connections it opened.
     */
    private fun shareBatch(
        access: ModuleAccessService,
        lock: ReentrantLock,
        code: String,
        tenant: Connection,
        rows: List<ClientesSharing.Row>,
        deadline: Long,
        keyCache: MutableMap<String, List<ClientesMerge.ForeignKey>>,
        lockWaitMillis: Long,
        only: String?,
        actor: ActivityActor,
    ): BatchResult {
        if (!lock.tryLock(lockWaitMillis, TimeUnit.MILLISECONDS)) return BatchResult(null, true)
        try {
            val group = access.linkedCompanies(code, LinkPurpose)
            val others = group.filter { it.code != code }
            val targets = if (only == null) others else others.filter { it.code == only }
            if (targets.isEmpty()) return BatchResult(null, true)
            val connections = LinkedHashMap<String, Connection>()
            val unavailable = LinkedHashMap<String, String>()
            val previousAuto = LinkedHashMap<Connection, Boolean>()
            try {
                targets.forEach { company -> openTarget(access, company, connections, unavailable) }
                if (connections.isEmpty()) {
                    // Nobody to copy to right now (the destination of a first copy is down: its cursor stays): only the error is written.
                    flushErrors(tenant, unavailable)
                    return BatchResult(null, true)
                }
                val all = LinkedHashMap<String, Connection>().apply { put(code, tenant); putAll(connections) }
                all.values.forEach { previousAuto[it] = it.autoCommit; it.autoCommit = false }
                val skips = connections.keys.associateWith { target -> skipSet(tenant, rows.map { it.id }, target) }
                val tally = Tally()
                val hooks = BatchHooks { commitAll(all, tenant) }
                var lastDone: UUID? = null
                var stop = false
                var processed = 0
                try {
                    for (row in rows) {
                        if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) {
                            stop = true
                            break
                        }
                        // Somebody waits for the lock: finish here (what is done is committed) and let them in.
                        if (processed > 0 && lock.hasQueuedThreads()) break
                        // A savepoint of the own database per row too: a row that breaks its writes (review, mark) does not abort the batch.
                        var own: Mark? = null
                        try {
                            own = mark(tenant, hooks)
                            shareRow(access, group, code, tenant, row, all, skips, keyCache, tally, mark = only == null, hooks = hooks, actor = actor)
                            release(own, hooks)
                        } catch (failure: ClientesMerge.CompanyUnavailableException) {
                            throw failure
                        } catch (failure: InterruptedException) {
                            throw failure
                        } catch (failure: Exception) {
                            if (isConnectionFailure(failure)) throw failure
                            undo(tenant, own, hooks)
                            log.warn("Could not process customer {} of company {} ({})", row.id, code, describe(failure))
                            unavailable[only ?: targets.first().code] = ProcessFailed
                        }
                        lastDone = row.id
                        processed++
                    }
                    // A company that was unlinked while the batch ran must not receive anything: nothing of this batch is kept.
                    val stillLinked = access.linkedCompanies(code, LinkPurpose).map { it.code }.toSet()
                    if (connections.keys.any { it !in stillLinked }) {
                        rollbackAll(all)
                        return BatchResult(null, true)
                    }
                    writeProgress(tenant, tally, unavailable, only, lastDone)
                    // One row per destination with the totals of the batch (the copies themselves are not logged one by one).
                    val originName = group.firstOrNull { it.code == code }?.name ?: code
                    connections.forEach { (target, db) ->
                        tally.counts[target]?.let { c -> ClientesLog.batchReceived(db, actor, originName, c[0], c[1], c[2]) }
                    }
                    commitAll(all, tenant)
                    return BatchResult(lastDone, stop)
                } catch (failure: ClientesMerge.CompanyUnavailableException) {
                    // A company went down in the middle: nothing of this batch is kept, the cursor does not move.
                    rollbackAll(all)
                    failure.company?.let { unavailable[it] = failure.message.orEmpty() }
                    runCatching {
                        flushErrors(tenant, unavailable)
                        tenant.commit()
                    }
                    return BatchResult(null, true)
                } catch (failure: Throwable) {
                    rollbackAll(all)
                    throw failure
                }
            } finally {
                previousAuto.forEach { (connection, auto) -> runCatching { connection.autoCommit = auto } }
                connections.values.forEach { runCatching { it.close() } }
            }
        } finally {
            lock.unlock()
        }
    }

    // Opens one destination; one that does not open, has an old Clientes, or whose check throws is not leaked.
    private fun openTarget(access: ModuleAccessService, company: GroupCompany, connections: MutableMap<String, Connection>, unavailable: MutableMap<String, String>) {
        val db = try {
            connect(access, company)
        } catch (failure: ClientesMerge.CompanyUnavailableException) {
            unavailable[company.code] = failure.message.orEmpty()
            return
        }
        val supported = try {
            supportsLinking(db)
        } catch (failure: Exception) {
            runCatching { db.close() }
            if (isConnectionFailure(failure)) {
                unavailable[company.code] = tr("No se pudo abrir la empresa {0}; se reintentara mas tarde.", company.name)
                return
            }
            throw failure
        }
        if (supported) {
            connections[company.code] = db
        } else {
            runCatching { db.close() }
            unavailable[company.code] = tr("Actualiza el modulo Clientes de {0}.", company.name)
        }
    }

    // One customer to every available destination; marks it shared only when all the linked companies have it.
    private fun shareRow(
        access: ModuleAccessService,
        group: List<GroupCompany>,
        code: String,
        tenant: Connection,
        row: ClientesSharing.Row,
        connections: Map<String, Connection>,
        skips: Map<String, Set<UUID>>,
        keyCache: MutableMap<String, List<ClientesMerge.ForeignKey>>,
        tally: Tally,
        mark: Boolean,
        hooks: BatchHooks,
        actor: ActivityActor,
    ) {
        val others = group.filter { it.code != code }
        val outcomes = mutableListOf<ShareOutcome>()
        for (company in others) {
            if (!connections.containsKey(company.code)) continue
            val outcome = copyGuarded(access, group, code, tenant, company, connections, row, skips[company.code].orEmpty(), keyCache, hooks, actor)
            outcomes += outcome
            tally.add(company.code, outcome)
        }
        if (mark && fullyShared(outcomes, others.size)) {
            tenant.prepareStatement("UPDATE customers SET shared = TRUE WHERE id = ? AND origin_company_code = '' AND NOT shared").use { update ->
                update.setObject(1, row.id)
                update.executeUpdate()
            }
        }
    }

    /**
     * Opens the database of [company]. A pool timeout or a connection error is [ClientesMerge.CompanyUnavailableException]
     * (not a review): the work is retried later.
     */
    fun connect(access: ModuleAccessService, company: GroupCompany): Connection {
        val connection = try {
            access.tenantConnectionForCompany(company.code)
        } catch (failure: Exception) {
            if (isConnectionFailure(failure)) {
                log.warn("Company {} did not give a connection ({})", company.code, describe(failure))
                throw ClientesMerge.CompanyUnavailableException(tr("No se pudo abrir la empresa {0}; se reintentara mas tarde.", company.name), company.code)
            }
            throw failure
        }
        return connection ?: throw ClientesMerge.CompanyUnavailableException(tr("No se pudo abrir la empresa {0}; se reintentara mas tarde.", company.name), company.code)
    }

    /** Whether [failure] (or its cause) says the database could not be reached: pool timeout, SQLState 08xxx, refused or broken connection. */
    fun isConnectionFailure(failure: Throwable?): Boolean {
        var current = failure
        var depth = 0
        while (current != null && depth++ < 8) {
            if (current is SQLException) {
                if (current.sqlState?.startsWith("08") == true) return true
                if (current is SQLTransientConnectionException || current is SQLNonTransientConnectionException || current is SQLTimeoutException) return true
            }
            val message = current.message.orEmpty()
            if (message.contains("request timed out", ignoreCase = true) || message.contains("Connection is not available", ignoreCase = true) ||
                message.contains("Connection refused", ignoreCase = true) || message.contains("has been closed", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    // One customer, one company, inside a savepoint of the destination: a failure becomes a review and the batch goes on; a company that cannot be reached is not a review.
    private fun copyGuarded(
        access: ModuleAccessService,
        group: List<GroupCompany>,
        code: String,
        tenant: Connection,
        target: GroupCompany,
        connections: Map<String, Connection>,
        row: ClientesSharing.Row,
        skip: Set<UUID>,
        keyCache: MutableMap<String, List<ClientesMerge.ForeignKey>>,
        hooks: BatchHooks,
        actor: ActivityActor,
    ): ShareOutcome {
        val targetDb = connections.getValue(target.code)
        var savepoint: Mark? = null
        return try {
            savepoint = mark(targetDb, hooks)
            copyOne(access, group, code, tenant, target, connections, row, skip, keyCache, hooks, actor).also { release(savepoint, hooks) }
        } catch (failure: ClientesMerge.CompanyUnavailableException) {
            throw failure
        } catch (failure: InterruptedException) {
            throw failure
        } catch (failure: Exception) {
            if (isConnectionFailure(failure)) {
                log.warn("Lost the connection while copying customer {} to company {} ({})", row.id, target.code, describe(failure))
                throw ClientesMerge.CompanyUnavailableException(tr("Se perdio la conexion con {0}; se reintentara mas tarde.", target.name), target.code)
            }
            undo(targetDb, savepoint, hooks)
            log.warn("Could not copy customer {} to company {} ({})", row.id, target.code, describe(failure))
            recordCopyReview(tenant, row, target.code, "", "No se pudo copiar a ${target.name}. El detalle esta en el registro del servidor; puedes reintentar.", "copy", actor)
            ShareOutcome.Review
        }
    }

    // Customers that must not be copied to [targetCode]: merged into another one, or whose copy the owner refused.
    private fun skipSet(tenant: Connection, ids: List<UUID>, targetCode: String): Set<UUID> =
        tenant.prepareStatement(
            """
            SELECT merged_id FROM customer_merges WHERE merged_id = ANY (?) AND status = 'merged'
            UNION
            SELECT merged_id FROM customer_merges WHERE kept_id = ANY (?) AND kept_company_code = ? AND status = 'separated'
            """.trimIndent()
        ).use { query ->
            query.setArray(1, tenant.createArrayOf("uuid", ids.toTypedArray()))
            query.setArray(2, tenant.createArrayOf("uuid", ids.map { copyReviewKey(it, targetCode) }.toTypedArray()))
            query.setString(3, targetCode)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }

    /**
     * Copies own customer [row] of [code] to [target] (its connection is in [connections], with the own one
     * under [code] and every other open company): merging with a customer that has the same email AND the same name,
     * leaving the pair in review (name_differs) when only the email matches, or copying. A review leaves a mark in the
     * own database so the pending pass does not process the customer again every minute until the owner resolves it.
     * [hooks] commits the batch so far: a merge commits and rolls back by itself across every database.
     */
    private fun copyOne(
        access: ModuleAccessService,
        group: List<GroupCompany>,
        code: String,
        tenant: Connection,
        target: GroupCompany,
        connections: Map<String, Connection>,
        row: ClientesSharing.Row,
        skip: Set<UUID>,
        keyCache: MutableMap<String, List<ClientesMerge.ForeignKey>>,
        hooks: BatchHooks,
        actor: ActivityActor,
    ): ShareOutcome {
        if (row.id in skip) return ShareOutcome.Skipped
        val targetDb = connections.getValue(target.code)
        val incoming = ClientesMerge.Candidate(row, code)
        if (row.status != "archived" && row.email.isNotBlank()) {
            val (existing, decision) = decisionFor(access, group, code, tenant, connections, target.code, incoming)
            when (decision) {
                is ClientesMerge.Decision.Skip -> return ShareOutcome.Skipped
                is ClientesMerge.Decision.NameDiffers -> {
                    // Same email, another name: no card is touched, copied or completed. The pair waits for the owner, in the own database.
                    val other = existing!!
                    ClientesMerge.recordPairReview(
                        tenant, incoming, other, target.code, row.email, "name_differs",
                        ClientesMerge.nameDiffersReason(row.displayName, other.row.displayName, target.name, row.email), actor,
                    )
                    return ShareOutcome.Review
                }
                is ClientesMerge.Decision.Merge -> {
                    // The batch so far is committed first; the merge commits by itself, so every savepoint taken before is gone.
                    hooks.commit()
                    hooks.committed()
                    val result = try {
                        ClientesMerge.merge(
                            access, group, connections,
                            decision.loser, decision.winner, row.email, ClientesAuthor.system(actor), userCompany = null, keyCache = keyCache,
                        )
                    } finally {
                        hooks.committed()
                    }
                    if (result is ClientesMerge.Result.Merged) return ShareOutcome.Merged
                    // The pair's row lives in the loser's company; when that is not this one, mark the own customer too.
                    if (decision.loser.origin != code) {
                        recordCopyReview(tenant, row, target.code, existing!!.row.displayName, "No se pudo fusionar con ${existing.row.displayName} de ${target.name}. Revisa la decision en Por revisar.", "copy", actor)
                    }
                    return ShareOutcome.Review
                }
                is ClientesMerge.Decision.Copy -> Unit
            }
        }
        val sameCode = targetDb.prepareStatement("SELECT display_name FROM customers WHERE customer_code = ? AND id <> ? LIMIT 1").use { query ->
            query.setString(1, row.code)
            query.setObject(2, row.id)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        if (sameCode != null) {
            recordCopyReview(tenant, row, target.code, sameCode, "Codigo repetido: el cliente $sameCode de ${target.name} ya usa el codigo ${row.code}. No se copio.", "code", actor)
            return ShareOutcome.Review
        }
        ClientesSharing.upsertCopy(targetDb, row, origin = code, shared = true)
        return ShareOutcome.Copied
    }

    // Active customer of [target] with the same email as [row] (own or a copy), the oldest first.
    private fun duplicateOf(target: Connection, targetCode: String, row: ClientesSharing.Row): ClientesMerge.Candidate? =
        target.prepareStatement(
            """
            SELECT ${ClientesSharing.RowColumns}, origin_company_code FROM customers
            WHERE lower(primary_email) = lower(?) AND id <> ? AND status <> 'archived' AND ${ClientesSharing.Visible}
            ORDER BY created_at, id LIMIT 1
            """.trimIndent()
        ).use { query ->
            query.setString(1, row.email)
            query.setObject(2, row.id)
            query.executeQuery().use { rows ->
                if (rows.next()) ClientesMerge.Candidate(ClientesSharing.readRow(rows), rows.getString("origin_company_code").ifBlank { targetCode }) else null
            }
        }

    /**
     * What to do with [incoming] against the active customer of [targetCode] with the same email: the duplicate found (or
     * null) and the decision. [connections] holds the open databases (the current company's under [currentCode], the target's).
     */
    internal fun decisionFor(
        access: ModuleAccessService,
        group: List<GroupCompany>,
        currentCode: String,
        tenant: Connection,
        connections: Map<String, Connection>,
        targetCode: String,
        incoming: ClientesMerge.Candidate,
    ): Pair<ClientesMerge.Candidate?, ClientesMerge.Decision> {
        val targetDb = connections[targetCode] ?: return null to ClientesMerge.Decision.Copy
        val existing = duplicateOf(targetDb, targetCode, incoming.row) ?: return null to ClientesMerge.Decision.Copy
        val separated = existing.origin != incoming.origin && separatedPair(access, group, currentCode, tenant, connections, incoming, existing)
        return existing to ClientesMerge.decide(incoming, existing, group.map { it.code }.toSet(), separated)
    }

    // Whether the owner separated this pair, in the database of either customer's origin company or the current one (an open connection is reused).
    private fun separatedPair(
        access: ModuleAccessService,
        group: List<GroupCompany>,
        currentCode: String,
        tenant: Connection,
        connections: Map<String, Connection>,
        incoming: ClientesMerge.Candidate,
        existing: ClientesMerge.Candidate,
    ): Boolean {
        val check: (Connection) -> Boolean = { db -> supportsLinking(db) && isSeparated(db, incoming.row.id, existing.row.id) }
        for (origin in listOf(currentCode, incoming.origin, existing.origin).distinct()) {
            val open = if (origin == currentCode) tenant else connections[origin]
            val separated = if (open != null) {
                check(open)
            } else {
                val company = group.firstOrNull { it.code == origin } ?: continue
                connect(access, company).use(check)
            }
            if (separated) return true
        }
        return false
    }

    private fun isSeparated(db: Connection, a: UUID, b: UUID): Boolean =
        db.prepareStatement(
            "SELECT 1 FROM customer_merges WHERE status = 'separated' AND ((merged_id = ? AND kept_id = ?) OR (merged_id = ? AND kept_id = ?))"
        ).use { query ->
            query.setObject(1, a)
            query.setObject(2, b)
            query.setObject(3, b)
            query.setObject(4, a)
            query.executeQuery().use { it.next() }
        }

    // A customer that cannot be copied to [targetCode]: kept_id is a stable id per customer and target (copyReviewKey), kept_company_code is the target.
    // reasonCode: copy, code or edit.
    internal fun recordCopyReview(db: Connection, row: ClientesSharing.Row, targetCode: String, otherName: String, reason: String, reasonCode: String, actor: ActivityActor) {
        db.prepareStatement(
            """
            INSERT INTO customer_merges (id, kept_id, kept_company_code, kept_name, merged_id, merged_name, email, status, reason, reason_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'review', ?, ?)
            ON CONFLICT (merged_id, kept_id) DO UPDATE SET reason = EXCLUDED.reason, kept_name = EXCLUDED.kept_name, reason_code = EXCLUDED.reason_code
            WHERE customer_merges.status = 'review'
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, copyReviewKey(row.id, targetCode))
            insert.setString(3, targetCode)
            insert.setString(4, otherName)
            insert.setObject(5, row.id)
            insert.setString(6, row.displayName)
            insert.setString(7, row.email)
            insert.setString(8, reason.take(1_900))
            insert.setString(9, reasonCode)
            insert.executeUpdate()
        }
        ClientesLog.reviewCreated(db, actor, row.id, row.displayName, reason)
    }

    /**
     * An edit that could not be sent to the other companies right now (the group lock was busy): the origin goes back to
     * shared = FALSE so the pending pass copies it, a copy edited here leaves a review "edit" (Reintentar sends it again).
     */
    internal fun deferEdit(tenant: Connection, currentCode: String, origin: String, row: ClientesSharing.Row, actor: ActivityActor) {
        try {
            if (origin == currentCode) {
                tenant.prepareStatement("UPDATE customers SET shared = FALSE WHERE id = ? AND origin_company_code = '' AND shared").use { update ->
                    update.setObject(1, row.id)
                    update.executeUpdate()
                }
            } else {
                recordCopyReview(
                    tenant, row, currentCode, "",
                    "El cambio de ${row.displayName} no llego a las demas empresas porque habia una copia en curso. Pulsa Reintentar.", "edit", actor,
                )
            }
        } catch (failure: ActivityWriteException) {
            throw failure
        } catch (failure: Exception) {
            log.warn("Could not leave the edit of customer {} to the job ({})", row.id, describe(failure))
        }
    }

    // ---- Reviews, separate, retry and merge by hand -----------------------------------------------

    /** Decisions of every linked company: the ones to review first, then the merges done (they can be separated). */
    fun reviews(access: ModuleAccessService, currentCode: String, limit: Int = 100): List<MergeItem> {
        val group = access.linkedCompanies(currentCode, LinkPurpose)
        val names = group.associate { it.code to it.name }
        val cap = limit.coerceIn(1, 500)
        return group.flatMap { company ->
            try {
                access.tenantConnectionForCompany(company.code)?.use { db ->
                    if (!supportsLinking(db)) return@use emptyList()
                    db.prepareStatement(
                        """
                        SELECT id, kept_id, kept_company_code, kept_name, merged_id, merged_name, email, status, reason, reason_code, created_at
                        FROM customer_merges WHERE status IN ('review', 'merged')
                        ORDER BY CASE status WHEN 'review' THEN 0 ELSE 1 END, created_at DESC LIMIT ?
                        """.trimIndent()
                    ).use { query ->
                        query.setInt(1, cap)
                        query.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) {
                                    val keptCompany = rows.getString("kept_company_code")
                                    add(
                                        MergeItem(
                                            id = rows.getObject("id", UUID::class.java),
                                            company = company,
                                            keptId = rows.getObject("kept_id", UUID::class.java),
                                            keptName = rows.getString("kept_name"),
                                            keptCompany = names[keptCompany] ?: keptCompany,
                                            mergedId = rows.getObject("merged_id", UUID::class.java),
                                            mergedName = rows.getString("merged_name"),
                                            email = rows.getString("email"),
                                            status = rows.getString("status"),
                                            reason = rows.getString("reason"),
                                            reasonCode = rows.getString("reason_code"),
                                            at = rows.getObject("created_at", OffsetDateTime::class.java),
                                        )
                                    )
                                }
                            }
                        }
                    }
                }.orEmpty()
            } catch (failure: Exception) {
                log.warn("Could not read the reviews of company {} ({})", company.code, describe(failure))
                emptyList()
            }
        }.sortedWith(compareBy<MergeItem> { if (it.status == "review") 0 else 1 }.thenByDescending { it.at }).take(cap)
    }

    private class MergeRow(
        val id: UUID,
        val keptId: UUID,
        val keptCompany: String,
        val mergedId: UUID,
        val email: String,
        val status: String,
        val reasonCode: String,
        val moved: List<ClientesMerge.Moved>,
    ) {
        // A customer that could not be copied (see copyReviewKey) instead of two customers to merge.
        val copyProblem: Boolean get() = isCopyReview(mergedId, keptId, keptCompany)
    }

    private fun loadMerge(db: Connection, mergeId: UUID): MergeRow? =
        db.prepareStatement("SELECT kept_id, kept_company_code, merged_id, email, status, reason_code, moved::text AS moved FROM customer_merges WHERE id = ?").use { query ->
            query.setObject(1, mergeId)
            query.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                MergeRow(
                    mergeId,
                    rows.getObject("kept_id", UUID::class.java),
                    rows.getString("kept_company_code"),
                    rows.getObject("merged_id", UUID::class.java),
                    rows.getString("email"),
                    rows.getString("status"),
                    rows.getString("reason_code"),
                    ClientesMerge.parseMoved(rows.getString("moved")),
                )
            }
        }

    // Linked companies read inside the lock; [companyCode] must be one of them.
    private fun requireLinked(access: ModuleAccessService, currentCode: String, companyCode: String): List<GroupCompany> {
        val group = access.linkedCompanies(currentCode, LinkPurpose)
        if (group.none { it.code == companyCode }) throw ClientesSharingException(tr("Esa empresa no esta vinculada con la actual."))
        return group
    }

    /**
     * The owner decides that two customers are not the same ("dejar separados"): merges are undone, reviews are closed
     * and the pair is never merged automatically again. It is also the way to keep a pair apart that waits in review
     * (name_differs, edit...). Waits at most a few seconds for the group lock.
     */
    fun separate(access: ModuleAccessService, currentCode: String, companyCode: String, mergeId: UUID, author: ClientesAuthor, tenant: Connection? = null): String =
        withHttpLock(access, currentCode) {
            val group = requireLinked(access, currentCode, companyCode)
            val names = group.associate { it.code to it.name }
            useDb(access, tenant, currentCode, companyCode, tr("No se pudo abrir la empresa.")) { db ->
                val row = loadMerge(db, mergeId) ?: throw ClientesSharingException(tr("No se encontro esa decision."))
                when (row.status) {
                    "separated" -> throw ClientesSharingException(tr("Ya estaban separados."))
                    "review" -> {
                        ClientesLog.atomically(db) {
                            db.prepareStatement("UPDATE customer_merges SET status = 'separated', resolved_by = ?, resolved_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'review'").use { update ->
                                update.setString(1, author.name)
                                update.setObject(2, mergeId)
                                update.executeUpdate()
                            }
                            val name = ClientesLog.customerName(db, row.mergedId)
                            ClientesLog.record(
                                db, author.actor, ClientesLog.Review, row.mergedId, name, ActivityActions.Updated,
                                if (row.copyProblem) "Dejo sin copiar al cliente $name: se cerro la revision." else "Dejo separados al cliente $name y su posible duplicado: se cerro la revision.",
                                listOf(ActivityChange.of("status", "Estado", "Por revisar", "Separado")),
                            )
                        }
                        when {
                            row.copyProblem && row.reasonCode == "edit" -> tr("Listo: se descarta ese cambio pendiente; las demas empresas lo recibiran en la proxima edicion.")
                            row.copyProblem -> tr("Listo: ese cliente no se copiara a esa empresa.")
                            else -> {
                                // An edit that was held back by this pair now goes through (they are different customers).
                                if (row.reasonCode == "edit") {
                                    runCatching { ClientesSharing.propagate(access, db, companyCode, row.mergedId, author.actor) }
                                        .onFailure { log.warn("Could not send the held edit of customer {} ({})", row.mergedId, describe(it)) }
                                }
                                tr("Listo: los dos clientes quedan separados y no se fusionaran automaticamente.")
                            }
                        }
                    }
                    else -> separateMerged(access, group, names, currentCode, companyCode, db, row, author, tenant)
                }
            }
        }

    private fun separateMerged(
        access: ModuleAccessService,
        group: List<GroupCompany>,
        names: Map<String, String>,
        currentCode: String,
        companyCode: String,
        db: Connection,
        row: MergeRow,
        author: ClientesAuthor,
        tenant: Connection?,
    ): String {
        // Every linked company is involved (the winner's copies may have completed fields), and every company named in the log must still be linked.
        val named = (row.moved.map { it.company } + companyCode + row.keptCompany).toSet()
        named.firstOrNull { it !in names }?.let {
            throw ClientesSharingException(tr("No se puede separar: la empresa {0} ya no esta vinculada. Vincula de nuevo las empresas y vuelve a intentarlo.", it))
        }
        val opened = mutableListOf<Connection>()
        val previous = LinkedHashMap<Connection, Boolean>()
        try {
            val connections = LinkedHashMap<String, Connection>()
            names.keys.forEach { code ->
                val connection = when {
                    code == companyCode -> db
                    // The connection the route already holds is reused (the pool is small).
                    code == currentCode && tenant != null -> tenant
                    else -> access.tenantConnectionForCompany(code)?.also { opened += it }
                        ?: throw ClientesSharingException(tr("No se pudo abrir la empresa {0}; intenta de nuevo.", names[code]))
                }
                if (!supportsLinking(connection)) throw ClientesSharingException(tr("Actualiza el modulo Clientes de {0}.", names[code]))
                connections[code] = connection
            }
            connections.values.forEach { previous[it] = it.autoCommit; it.autoCommit = false }
            try {
                val loserRow = ClientesSharing.row(db, row.mergedId) ?: throw ClientesSharingException(tr("El cliente fusionado ya no existe."))
                val winnerRow = ClientesSharing.row(connections.getValue(row.keptCompany), row.keptId) ?: throw ClientesSharingException(tr("El cliente que se conserva ya no existe."))
                val report = ClientesMerge.separate(
                    access, connections, names, row.id,
                    ClientesMerge.Candidate(loserRow, companyCode), ClientesMerge.Candidate(winnerRow, row.keptCompany),
                    row.moved, author, userCompany = currentCode,
                )
                // The loser's database goes last, like in a merge.
                connections.values.filter { it !== db }.forEach { it.commit() }
                db.commit()
                val hadFilled = row.moved.any { it.kind == "filled" }
                return listOfNotNull(
                    tr("Separados: {0} y {1} vuelven a ser dos clientes. Se devolvieron {2} registros.", loserRow.displayName, winnerRow.displayName, report.returned),
                    if (report.irreversible > 0) tr("{0} registros no se pudieron devolver y siguen con el cliente conservado.", report.irreversible) else null,
                    if (report.notesGone > 0) tr("{0} notas ya no existian y no se restauraron.", report.notesGone) else null,
                    if (hadFilled && report.filledReverted) tr("Se devolvieron los datos que se habian completado.")
                    else if (hadFilled) tr("Los datos completados al fusionar se conservan porque se editaron despues.")
                    else null,
                ).joinToString(" ")
            } catch (failure: Throwable) {
                connections.values.forEach { runCatching { it.rollback() } }
                throw failure
            }
        } finally {
            previous.forEach { (connection, auto) -> runCatching { connection.autoCommit = auto } }
            opened.forEach { runCatching { it.close() } }
        }
    }

    /** Tries again a decision left in review (a customer not copied, a held edit, or a merge that failed). Waits at most a few seconds for the group lock. */
    fun retry(access: ModuleAccessService, currentCode: String, companyCode: String, mergeId: UUID, author: ClientesAuthor, tenant: Connection? = null): String =
        withHttpLock(access, currentCode) {
            val group = requireLinked(access, currentCode, companyCode)
            useDb(access, tenant, currentCode, companyCode, tr("No se pudo abrir la empresa.")) { db ->
                val row = loadMerge(db, mergeId) ?: throw ClientesSharingException(tr("No se encontro esa decision."))
                if (row.status != "review") throw ClientesSharingException(tr("Esa decision ya no esta por revisar."))
                val loserRow = ClientesSharing.row(db, row.mergedId) ?: throw ClientesSharingException(tr("El cliente ya no existe."))
                if (row.copyProblem && row.reasonCode == "edit") {
                    // A held edit: close the mark and send the change again (a failure leaves a new mark).
                    db.prepareStatement("DELETE FROM customer_merges WHERE id = ? AND status = 'review'").use { delete ->
                        delete.setObject(1, mergeId)
                        delete.executeUpdate()
                    }
                    val failed = ClientesSharing.propagate(access, db, companyCode, row.mergedId, author.actor)
                    val stillHeld = db.prepareStatement("SELECT 1 FROM customer_merges WHERE merged_id = ? AND status = 'review' AND reason_code = 'edit'").use { q ->
                        q.setObject(1, row.mergedId)
                        q.executeQuery().use { it.next() }
                    }
                    if (failed.isNotEmpty() || stillHeld) throw ClientesSharingException(tr("Sigue por revisar: el cambio no llego a {0}.", failed.joinToString().ifBlank { tr("las demas empresas") }))
                    return@useDb tr("Listo: el cambio de {0} llego a las demas empresas.", loserRow.displayName)
                }
                if (!row.copyProblem && (row.reasonCode == "name_differs" || row.reasonCode == "edit")) {
                    throw ClientesSharingException(tr("Los nombres son distintos: usa Fusionar a mano o Dejar separados."))
                }
                // The other company must still be linked before it is opened.
                val other = group.firstOrNull { it.code == row.keptCompany && it.code != companyCode }
                    ?: throw ClientesSharingException(tr("La empresa del otro cliente ya no esta vinculada."))
                useDb(access, tenant, currentCode, other.code, tr("No se pudo abrir {0}.", other.name)) { destination ->
                    if (!supportsLinking(destination)) throw ClientesSharingException(tr("Actualiza el modulo Clientes de {0}.", other.name))
                    if (row.copyProblem) {
                        val outcome = copyGuarded(access, group, companyCode, db, other, mapOf(companyCode to db, other.code to destination), loserRow, emptySet(), HashMap(), hooks = BatchHooks {}, actor = author.actor)
                        when (outcome) {
                            ShareOutcome.Review -> {
                                val reason = db.prepareStatement("SELECT reason FROM customer_merges WHERE id = ?").use { q ->
                                    q.setObject(1, mergeId)
                                    q.executeQuery().use { it.next(); it.getString(1) }
                                }
                                throw ClientesSharingException(tr("Sigue por revisar. {0}", reason))
                            }
                            ShareOutcome.Skipped ->
                                throw ClientesSharingException(tr("Ese cliente se omitio porque ya esta separado o fusionado; la decision no cambia."))
                            ShareOutcome.Copied, ShareOutcome.Merged -> {
                                ClientesLog.batchReceived(destination, author.actor, group.firstOrNull { it.code == companyCode }?.name ?: companyCode, if (outcome == ShareOutcome.Copied) 1 else 0, if (outcome == ShareOutcome.Merged) 1 else 0, 0)
                                ClientesLog.atomically(db) {
                                    db.prepareStatement("DELETE FROM customer_merges WHERE id = ? AND status = 'review'").use { delete ->
                                        delete.setObject(1, mergeId)
                                        delete.executeUpdate()
                                    }
                                    ClientesLog.record(
                                        db, author.actor, ClientesLog.Review, loserRow.id, loserRow.displayName, ActivityActions.Updated,
                                        "Reintento copiar al cliente ${loserRow.displayName} a ${other.name}: se copio y se cerro la revision.",
                                        listOf(ActivityChange.of("status", "Estado", "Por revisar", "Resuelto")),
                                    )
                                }
                                tr("Listo: {0} se copio a {1}.", loserRow.displayName, other.name)
                            }
                        }
                    } else {
                        val winnerRow = ClientesSharing.row(destination, row.keptId) ?: throw ClientesSharingException(tr("El otro cliente ya no existe."))
                        // Both must still be active with the same email, or the merge no longer makes sense.
                        if (loserRow.status == "archived" || winnerRow.status == "archived" || loserRow.email.isBlank() || !ClientesMerge.sameEmail(loserRow.email, winnerRow.email)) {
                            throw ClientesSharingException(tr("Los clientes ya no coinciden (correo distinto o alguno archivado). Usa Separar para cerrar esta decision."))
                        }
                        val result = ClientesMerge.merge(
                            access, group, provided(tenant, currentCode, companyCode to db, other.code to destination),
                            ClientesMerge.Candidate(loserRow, companyCode), ClientesMerge.Candidate(winnerRow, row.keptCompany),
                            row.email, author, userCompany = currentCode,
                        )
                        when (result) {
                            is ClientesMerge.Result.Merged -> tr("Listo: {0} se fusiono con {1}.", loserRow.displayName, winnerRow.displayName)
                            is ClientesMerge.Result.Review -> throw ClientesSharingException(tr("Sigue por revisar. {0}", result.reason))
                        }
                    }
                }
            }
        }

    // Runs [block] on the database of [code]: the connection the route already holds when it is the same company (the pool is small), else a new one.
    private fun <T : Any> useDb(access: ModuleAccessService, tenant: Connection?, currentCode: String, code: String, unavailable: String, block: (Connection) -> T): T =
        if (tenant != null && code == currentCode) block(tenant)
        else (access.tenantConnectionForCompany(code) ?: throw ClientesSharingException(unavailable)).use(block)

    // Connections given to a merge: the two companies of the pair plus the route's own, so the merge does not open a second one to it.
    private fun provided(tenant: Connection?, currentCode: String, first: Pair<String, Connection>, second: Pair<String, Connection>): Map<String, Connection> =
        linkedMapOf(first, second).also { if (tenant != null && currentCode !in it) it[currentCode] = tenant }

    // Origin company of a customer as seen from the database of [companyCode]: its own customers have origin ''.
    private fun originOf(db: Connection, id: UUID, companyCode: String): String =
        db.prepareStatement("SELECT origin_company_code FROM customers WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1).ifBlank { companyCode } else companyCode }
        }

    /**
     * The owner merges by hand a pair left in review (typically name_differs: same email, another name). The OLDEST card
     * is kept, the other is archived and its rows follow it (ClientesMerge). Nothing is completed when the names differ.
     * Not for the "could not copy" marks (use Reintentar or Dejar separados). Waits at most a few seconds for the group lock.
     */
    fun mergeManually(access: ModuleAccessService, currentCode: String, companyCode: String, mergeId: UUID, author: ClientesAuthor, tenant: Connection? = null): String =
        withHttpLock(access, currentCode) {
            val group = requireLinked(access, currentCode, companyCode)
            val names = group.associate { it.code to it.name }
            useDb(access, tenant, currentCode, companyCode, tr("No se pudo abrir la empresa.")) { db ->
                val row = loadMerge(db, mergeId) ?: throw ClientesSharingException(tr("No se encontro esa decision."))
                if (row.status != "review") throw ClientesSharingException(tr("Esa decision ya no esta por revisar."))
                if (row.copyProblem) throw ClientesSharingException(tr("Ese pendiente no es un par de clientes con el mismo correo; usa Reintentar o Dejar separados."))
                val other = group.firstOrNull { it.code == row.keptCompany && it.code != companyCode }
                    ?: throw ClientesSharingException(tr("La empresa del otro cliente ya no esta vinculada."))
                val mine = ClientesSharing.row(db, row.mergedId) ?: throw ClientesSharingException(tr("El cliente ya no existe."))
                useDb(access, tenant, currentCode, other.code, tr("No se pudo abrir {0}.", other.name)) { destination ->
                    if (!supportsLinking(destination)) throw ClientesSharingException(tr("Actualiza el modulo Clientes de {0}.", other.name))
                    val theirs = ClientesSharing.row(destination, row.keptId) ?: throw ClientesSharingException(tr("El otro cliente ya no existe."))
                    if (mine.status == "archived" || theirs.status == "archived" || mine.email.isBlank() || !ClientesMerge.sameEmail(mine.email, theirs.email)) {
                        throw ClientesSharingException(tr("Los clientes ya no coinciden (correo distinto o alguno archivado). Usa Dejar separados para cerrar esta decision."))
                    }
                    val first = ClientesMerge.Candidate(mine, originOf(db, mine.id, companyCode))
                    val second = ClientesMerge.Candidate(theirs, originOf(destination, theirs.id, other.code))
                    if (first.origin !in names || second.origin !in names) throw ClientesSharingException(tr("Una de las empresas de esos clientes ya no esta vinculada."))
                    if (first.origin == second.origin) throw ClientesSharingException(tr("Los dos clientes son de la misma empresa: no se fusionan desde aqui."))
                    val (winner, loser) = ClientesMerge.order(first, second)
                    val result = ClientesMerge.merge(
                        access, group, provided(tenant, currentCode, companyCode to db, other.code to destination),
                        loser, winner, row.email, author, userCompany = currentCode,
                    )
                    when (result) {
                        is ClientesMerge.Result.Merged -> {
                            // The review row stays when the merge was written with the other orientation (the loser is the one in the other company): close it.
                            db.prepareStatement("DELETE FROM customer_merges WHERE id = ? AND status = 'review'").use { delete ->
                                delete.setObject(1, mergeId)
                                delete.executeUpdate()
                            }
                            val same = ClientesMerge.sameName(winner.row.displayName, loser.row.displayName)
                            val done = tr("Listo: se conserva {0} (el mas antiguo) y {1} queda archivado.", winner.row.displayName, loser.row.displayName)
                            if (same) done else done + " " + tr("Los nombres eran distintos: no se completo ningun dato.")
                        }
                        is ClientesMerge.Result.Review -> throw ClientesSharingException(tr("Sigue por revisar. {0}", result.reason))
                    }
                }
            }
        }
}
