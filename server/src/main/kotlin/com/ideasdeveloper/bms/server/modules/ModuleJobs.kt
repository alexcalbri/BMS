package com.ideasdeveloper.bms.server.modules

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Periodic work of a module (polling an external API, sending scheduled emails...). The server runs
 * it for every active company that has the module enabled, about every [interval] (checked once a
 * minute, so intervals below one minute behave as one minute), or once per local calendar day at
 * [dailyAt] when configured. Jobs run one after another on a single background thread (those marked
 * [ModuleJob.longRunning] on a second one, never twice at once per company): keep each run
 * short and bounded (process a batch, continue next time).
 */
class ModuleJob(
    // Unique within the module, e.g. "welcome-emails".
    val id: String,
    val interval: Duration,
    // When set, the job is due once per local calendar day at or after this time.
    val dailyAt: LocalTime? = null,
    // Resolves a company-specific schedule from its tenant database.
    val scheduleFor: ((Connection) -> ModuleJobSchedule)? = null,
    // Slow work (imports, syncs): runs on its own thread so it never delays other jobs, and is not started again while still running.
    val longRunning: Boolean = false,
    // Last, so existing trailing-lambda calls keep binding to it.
    val run: (ModuleJobContext) -> Unit,
) {
    constructor(id: String, interval: Duration, run: (ModuleJobContext) -> Unit) : this(id, interval, null, null, false, run)
}

data class ModuleJobSchedule(val interval: Duration? = null, val dailyAt: LocalTime? = null) {
    init {
        require((interval != null) xor (dailyAt != null)) { "A module job schedule needs either an interval or a daily time." }
        require(interval == null || !interval.isNegative && !interval.isZero) { "A module job interval must be positive." }
    }
}

// What a job receives: the company it runs for, its tenant database (closed after the run) and services.
class ModuleJobContext(
    val companyCode: String,
    val tenant: Connection,
    val access: ModuleAccessService,
    // Creates notifications for users with the job's module as their origin.
    val notifier: ModuleNotifier,
)

/**
 * Decides which jobs are due. A failed run counts as a run, so it is retried at the next interval or
 * scheduled calendar day instead of every tick.
 */
internal class ModuleJobClock {
    private val lastRuns = ConcurrentHashMap<String, Instant>()

    fun isDue(moduleId: String, job: ModuleJob, companyCode: String, now: Instant): Boolean =
        isDue(moduleId, job, companyCode, now, job.scheduleForDefault())

    fun isDue(moduleId: String, job: ModuleJob, companyCode: String, now: Instant, schedule: ModuleJobSchedule): Boolean {
        val last = lastRuns[key(moduleId, job, companyCode)]
        schedule.dailyAt?.let { dailyAt ->
            val localNow = now.atZone(ZoneId.systemDefault())
            return !localNow.toLocalTime().isBefore(dailyAt) &&
                (last?.atZone(ZoneId.systemDefault())?.toLocalDate()?.isBefore(localNow.toLocalDate()) ?: true)
        }
        return last == null || Duration.between(last, now) >= schedule.interval!!
    }

    /** Cheap check before opening a connection: a job scheduled from the database may be due, so it passes; any other is decided now. */
    fun mayBeDue(moduleId: String, job: ModuleJob, companyCode: String, now: Instant): Boolean =
        job.scheduleFor != null || isDue(moduleId, job, companyCode, now)

    fun ran(moduleId: String, job: ModuleJob, companyCode: String, at: Instant) {
        lastRuns[key(moduleId, job, companyCode)] = at
    }

    private fun key(moduleId: String, job: ModuleJob, companyCode: String) = "$moduleId/${job.id}/$companyCode"
}

private fun ModuleJob.scheduleForDefault(): ModuleJobSchedule =
    dailyAt?.let { ModuleJobSchedule(dailyAt = it) } ?: ModuleJobSchedule(interval = interval)

/**
 * Runs [action] without letting anything escape, not even an Error (NoClassDefFoundError, LinkageError,
 * OutOfMemoryError): it is logged and the caller keeps going. An interruption restores the flag and returns.
 */
fun runGuarded(log: org.slf4j.Logger, description: String, action: () -> Unit) {
    try {
        action()
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
    } catch (failure: Throwable) {
        log.error(description, failure)
    }
}

/**
 * Recorre [items] ejecutando [action] con cada uno dentro de [runGuarded]: el fallo de uno (incluso un Error)
 * no impide los siguientes. Si el hilo fue interrumpido, sale del recorrido.
 */
fun <T> runEachGuarded(log: org.slf4j.Logger, items: Iterable<T>, description: (T) -> String, action: (T) -> Unit) {
    for (item in items) {
        if (Thread.currentThread().isInterrupted) return
        runGuarded(log, description(item)) { action(item) }
    }
}

class ModuleJobScheduler(
    private val registry: ModuleRegistry,
    private val access: ModuleAccessService,
    private val tick: Duration = Duration.ofMinutes(1),
    // False while the startup migrations of modules and extensions run: no pass starts before them.
    private val ready: () -> Boolean = { true },
    // A module whose migrations failed in a company (module id, company code): its jobs skip that company.
    private val blocked: (String, String) -> Boolean = { _, _ -> false },
) {
    private val log = LoggerFactory.getLogger(ModuleJobScheduler::class.java)
    private val clock = ModuleJobClock()
    private val longRuns = ModuleJobRunGuard()
    @Volatile private var executor: ScheduledExecutorService? = null
    // Long jobs (imports, syncs) run here, so they never delay the short jobs of the tick thread.
    @Volatile private var longExecutor: ExecutorService? = null

    fun start() {
        if (executor != null) return
        longExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "module-jobs-long").apply { isDaemon = true } }
        executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "module-jobs").apply { isDaemon = true } }.also {
            // The first tick waits a little so startup work (migrations) goes first.
            it.scheduleWithFixedDelay(::runDueJobsSafely, 30, maxOf(1L, tick.seconds), TimeUnit.SECONDS)
        }
    }

    fun stop() {
        val tickExecutor = executor
        val longJobs = longExecutor
        executor = null
        longExecutor = null
        // Let a running job finish for a moment before interrupting it.
        listOfNotNull(tickExecutor, longJobs).forEach { it.shutdown() }
        listOfNotNull(tickExecutor, longJobs).forEach { target ->
            try {
                if (!target.awaitTermination(2, TimeUnit.SECONDS)) target.shutdownNow()
            } catch (interrupted: InterruptedException) {
                target.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
    }

    // Cuerpo del planificador: nada (ni un Error) puede escapar, o scheduleWithFixedDelay cancelaria las pasadas siguientes.
    private fun runDueJobsSafely() = runGuarded(log, "Module jobs pass failed", ::runDueJobs)

    internal fun runDueJobs() {
        if (!ready()) return
        try {
            // Jobs grouped by company so a pass opens at most one connection per company.
            val byCompany = linkedMapOf<String, MutableList<Pair<String, ModuleJob>>>()
            runGuarded(log, "Core jobs listing failed") {
                access.activeCompanies().forEach { companyCode ->
                    coreJobs.forEach { byCompany.getOrPut(companyCode) { mutableListOf() }.add(CoreJobsId to it) }
                }
            }
            runEachGuarded(log, registry.installedModuleIds().sorted(), { "Jobs of module $it failed" }) { moduleId ->
                val jobs = registry.module(moduleId)?.jobs.orEmpty()
                if (jobs.isNotEmpty()) {
                    access.companiesWithModule(moduleId).filterNot { blocked(moduleId, it) }.forEach { companyCode ->
                        jobs.forEach { byCompany.getOrPut(companyCode) { mutableListOf() }.add(moduleId to it) }
                    }
                }
            }
            runEachGuarded(log, byCompany.entries.toList(), { "Jobs failed for company ${it.key}" }) { (companyCode, jobs) ->
                runForCompany(companyCode, jobs)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            // Never let an error (even a LinkageError or OutOfMemoryError) stop the scheduler thread.
            log.error("Module jobs tick failed", failure)
        }
    }

    // Work of the Core itself (not of a module), for every active company: it clears notifications
    // older than 90 days once a day.
    private val coreJobs = listOf(
        ModuleJob("notifications-cleanup", Duration.ofHours(24)) { context ->
            // A company whose migration is not applied yet has nothing to clean (and no error to log).
            if (hasNotificationsTable(context.tenant)) context.access.notifications.purgeExpired(context.tenant)
        },
    )

    /**
     * Runs the due jobs of one company. Jobs whose schedule does not depend on the database are checked before
     * connecting, so a company with nothing due costs no connection; the rest share one connection.
     */
    private fun runForCompany(companyCode: String, jobs: List<Pair<String, ModuleJob>>) {
        val now = Instant.now()
        val candidates = jobs.filter { (moduleId, job) ->
            clock.mayBeDue(moduleId, job, companyCode, now) &&
                !(job.longRunning && longRuns.isRunning(runKey(moduleId, job, companyCode)))
        }
        if (candidates.isEmpty()) return
        var tenant: Connection? = null
        try {
            runEachGuarded(log, candidates, { "Job ${it.second.id} of module ${it.first} failed for company $companyCode" }) { (moduleId, job) ->
                if (job.longRunning && job.scheduleFor == null) {
                    startLong(moduleId, job, companyCode)
                    return@runEachGuarded
                }
                // A previous short job must not leave an open transaction or autoCommit off for the next one.
                tenant?.let(::resetConnection)
                val connection = tenant ?: access.tenantConnectionForCompany(companyCode)?.also { tenant = it } ?: return@runEachGuarded
                val schedule = job.scheduleFor?.invoke(connection) ?: job.scheduleForDefault()
                val at = Instant.now()
                if (!clock.isDue(moduleId, job, companyCode, at, schedule)) return@runEachGuarded
                if (job.longRunning) {
                    startLong(moduleId, job, companyCode)
                } else {
                    clock.ran(moduleId, job, companyCode, at)
                    job.run(ModuleJobContext(companyCode, connection, access, access.notificationsFor(moduleId)))
                }
            }
        } finally {
            runGuarded(log, "Closing the tenant connection of company $companyCode failed") { tenant?.close() }
        }
    }

    private fun resetConnection(connection: Connection) {
        if (!connection.autoCommit) {
            connection.rollback()
            connection.autoCommit = true
        }
    }

    // Hands a long job to its own thread with its own connection, unless its previous run is still going.
    private fun startLong(moduleId: String, job: ModuleJob, companyCode: String) {
        val key = runKey(moduleId, job, companyCode)
        if (!longRuns.tryStart(key)) return
        val target = longExecutor
        if (target == null) {
            longRuns.finish(key)
            return
        }
        clock.ran(moduleId, job, companyCode, Instant.now())
        try {
            target.execute {
                try {
                    runGuarded(log, "Job ${job.id} of module $moduleId failed for company $companyCode") {
                        access.tenantConnectionForCompany(companyCode)?.use { tenant ->
                            job.run(ModuleJobContext(companyCode, tenant, access, access.notificationsFor(moduleId)))
                        }
                    }
                } finally {
                    longRuns.finish(key)
                }
            }
        } catch (rejected: RejectedExecutionException) {
            longRuns.finish(key)
        }
    }

    private fun runKey(moduleId: String, job: ModuleJob, companyCode: String) = "$moduleId/${job.id}/$companyCode"

    private fun hasNotificationsTable(tenant: Connection): Boolean =
        tenant.prepareStatement("SELECT to_regclass('notifications') IS NOT NULL").use { query ->
            query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    private companion object {
        // Id under which the Core's own jobs are scheduled; no module may use it.
        const val CoreJobsId = "core"
    }
}

/** Remembers which long jobs are running (module/job/company) so a slow run is never started twice at once. */
class ModuleJobRunGuard {
    private val running = ConcurrentHashMap.newKeySet<String>()

    fun isRunning(key: String): Boolean = key in running

    /** True when [key] was not running and is now marked as running. */
    fun tryStart(key: String): Boolean = running.add(key)

    fun finish(key: String) {
        running.remove(key)
    }
}

/**
 * Repeated failures of one step of a periodic job (an import source in a company...), so a broken step neither runs
 * again every minute nor floods the log. The first failure is logged once with its stack trace; while it keeps failing
 * the step waits longer each time ([FirstWait] doubling up to [MaxWait]) and only a different error is logged again. A
 * schema error (SQLState class 42: a table or column that does not exist, i.e. a migration not applied, or no right on
 * it) waits [MaxWait] from the first failure and, after [MaxSchemaAttempts] attempts, is not run again until the server
 * restarts or [reset] is called (the step is logged once as given up). A success clears the step and says so once.
 */
class JobBackoff(private val clock: () -> Instant = Instant::now) {
    private class State(val failures: Int, val nextAt: Instant, val signature: String, val gaveUp: Boolean)

    private val states = ConcurrentHashMap<String, State>()

    /** Whether [key] may run now (it never failed, its wait is over and it was not given up). */
    fun due(key: String): Boolean = states[key]?.let { !it.gaveUp && !clock().isBefore(it.nextAt) } ?: true

    /** Runs [block] for [key] when due: its result, or null when skipped or failed (the failure handled here). */
    fun <T> run(log: org.slf4j.Logger, key: String, description: String, block: () -> T): T? {
        if (!due(key)) return null
        return try {
            block().also { succeeded(log, key, description) }
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (cancellation: java.util.concurrent.CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failed(log, key, description, failure)
            null
        }
    }

    fun succeeded(log: org.slf4j.Logger, key: String, description: String) {
        val previous = states.remove(key) ?: return
        log.info("{}: funciona de nuevo tras {} fallos seguidos.", description, previous.failures)
    }

    fun failed(log: org.slf4j.Logger, key: String, description: String, failure: Throwable) {
        val now = clock()
        val schema = isSchemaError(failure)
        val signature = signatureOf(failure)
        val previous = states[key]
        val failures = (previous?.failures ?: 0) + 1
        val wait = if (schema) MaxWait else FirstWait.multipliedBy(1L shl minOf(failures - 1, 10)).coerceAtMost(MaxWait)
        val gaveUp = schema && failures >= MaxSchemaAttempts
        states[key] = State(failures, now.plus(wait), signature, gaveUp)
        when {
            previous == null || previous.signature != signature ->
                log.error("{} (se reintenta en {} min{}).", description, wait.toMinutes(), if (schema) "; falta una migracion o un permiso de la base" else "", failure)
            gaveUp -> log.error("{}: sigue fallando con un error de esquema tras {} intentos; no se reintenta hasta reiniciar el servidor: {}", description, failures, signature)
            else -> log.debug("{}: fallo {} seguido: {}", description, failures, signature)
        }
    }

    /** Forgets [key] (after a migration or a fix): it runs at the next pass. */
    fun reset(key: String) {
        states.remove(key)
    }

    /** Forgets every key that starts with [prefix] (e.g. "tareas/acme/"). */
    fun resetAll(prefix: String = "") {
        states.keys.removeIf { it.startsWith(prefix) }
    }

    companion object {
        val FirstWait: Duration = Duration.ofMinutes(1)
        val MaxWait: Duration = Duration.ofMinutes(60)
        const val MaxSchemaAttempts = 3

        /** One instance for the imports of every module (keys "module/company/source"). */
        val imports = JobBackoff()

        /** A missing table, column or function, or a missing privilege (SQLState class 42), anywhere in the causes. */
        fun isSchemaError(failure: Throwable): Boolean =
            generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(10)
                .any { it is java.sql.SQLException && it.sqlState?.startsWith("42") == true }

        private fun signatureOf(failure: Throwable): String {
            val sql = generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(10).filterIsInstance<java.sql.SQLException>().firstOrNull()
            return (sql?.let { "${it.javaClass.simpleName} ${it.sqlState}: ${it.message}" } ?: "${failure.javaClass.simpleName}: ${failure.message}")
                .lines().first().take(300)
        }
    }
}
