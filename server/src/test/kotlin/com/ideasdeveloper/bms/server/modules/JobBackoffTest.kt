package com.ideasdeveloper.bms.server.modules

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An import source that keeps failing (the incident: a column its migration adds did not exist, so the pending-jobs
 * query failed every minute for every company, 11,000+ log lines a day) waits longer each time and is logged once.
 */
class JobBackoffTest {
    private var now = Instant.parse("2026-11-27T12:00:00Z")
    private val backoff = JobBackoff { now }
    private val logger = LoggerFactory.getLogger("job-backoff-test") as Logger

    private fun <T> capture(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val logged = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logged)
        try {
            return block() to logged.list.toList()
        } finally {
            logger.detachAppender(logged)
        }
    }

    private val missingColumn = { throw org.postgresql.util.PSQLException("ERROR: column \"created_by_user\" does not exist", org.postgresql.util.PSQLState.UNDEFINED_COLUMN) }

    @Test
    fun aSchemaErrorIsLoggedOnceWaitsAnHourAndIsGivenUpAfterThreeAttempts() {
        var runs = 0
        // A day of passes every minute.
        val (_, events) = capture {
            repeat(24 * 60) {
                backoff.run(logger, "membresia/esc/membresia_monday", "Membresías esc: la fuente de importación membresia_monday falló") {
                    runs++
                    missingColumn()
                }
                now = now.plus(Duration.ofMinutes(1))
            }
        }
        assertEquals(JobBackoff.MaxSchemaAttempts, runs)
        val errors = events.filter { it.level == Level.ERROR }
        // The first failure (with its stack trace) and the one line saying it is given up.
        assertEquals(2, errors.size, errors.map { it.formattedMessage }.toString())
        assertTrue(errors.first().throwableProxy != null)
        assertTrue(errors.last().formattedMessage.contains("no se reintenta"))
        assertFalse(backoff.due("membresia/esc/membresia_monday"))
        // Another company or source is not affected; a reset (after a migration) lets it run again.
        assertTrue(backoff.due("membresia/otra/membresia_monday"))
        backoff.reset("membresia/esc/membresia_monday")
        assertTrue(backoff.due("membresia/esc/membresia_monday"))
    }

    @Test
    fun anOtherFailureBacksOffUpToAnHourAndASuccessClearsIt() {
        var runs = 0
        val (_, events) = capture {
            repeat(6 * 60) {
                backoff.run(logger, "tareas/acme/tareas_monday", "Falló la importación de tareas_monday en acme") {
                    runs++
                    throw SQLException("connection refused", "08001")
                }
                now = now.plus(Duration.ofMinutes(1))
            }
        }
        // 1, 2, 4, 8, 16, 32, 60, 60, ... minutes: about a dozen attempts in six hours instead of 360, one line logged.
        assertTrue(runs in 8..14, "runs=$runs")
        assertEquals(1, events.count { it.level == Level.ERROR })
        now = now.plus(Duration.ofHours(1))
        val (value, recovered) = capture { backoff.run(logger, "tareas/acme/tareas_monday", "Falló la importación de tareas_monday en acme") { 7 } }
        assertEquals(7, value)
        assertEquals(1, recovered.count { it.level == Level.INFO })
        assertTrue(backoff.due("tareas/acme/tareas_monday"))
        assertNull(backoff.run(logger, "x", "y") { throw IllegalStateException("otro") })
        assertTrue(JobBackoff.isSchemaError(RuntimeException(SQLException("no table", "42P01"))))
        assertFalse(JobBackoff.isSchemaError(SQLException("timeout", "57014")))
    }
}
