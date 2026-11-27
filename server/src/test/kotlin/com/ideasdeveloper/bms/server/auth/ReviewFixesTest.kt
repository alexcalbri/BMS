package com.ideasdeveloper.bms.server.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ideasdeveloper.bms.server.app.coreUpdateRequiresApprovedCommit
import com.ideasdeveloper.bms.server.app.publicBase
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorPolicy
import com.ideasdeveloper.bms.server.extensions.packages.BMSPublicKeys
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Low-severity items of the security review (items 4, 6, 7, 8). */
class ReviewFixesTest {
    private fun failing(state: String): DataSource = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
        if (method.name == "getConnection") throw SQLException("boom", state) else null
    } as DataSource

    @Test
    fun theAdministratorRuleFailsClosed() {
        // Before the migration (no table) there is no rule; any other failure requires two-step verification.
        assertFalse(TwoFactorPolicy.requireForAdmins(failing("42P01")))
        assertTrue(TwoFactorPolicy.requireForAdmins(failing("08001")))
    }

    @Test
    fun coreUpdatesRequireTheApprovedCommitUnlessTurnedOff() {
        assertTrue(coreUpdateRequiresApprovedCommit(null))
        assertTrue(coreUpdateRequiresApprovedCommit("true"))
        assertTrue(coreUpdateRequiresApprovedCommit(" yes "))
        assertFalse(coreUpdateRequiresApprovedCommit("false"))
        assertFalse(coreUpdateRequiresApprovedCommit(" FALSE "))
    }

    @Test
    fun linksUseOnlyAKnownHost() = testApplication {
        application {
            routing { get("/base") { call.respondText(publicBase(call) { it == "bms.example.com" } ?: "none") } }
        }
        assertEquals("http://bms.example.com", client.get("/base") { header("Host", "bms.example.com") }.bodyAsText())
        assertEquals("none", client.get("/base") { header("Host", "evil.example") }.bodyAsText())
        assertEquals("none", client.get("/base") { header("Host", "bms.example.com@evil.example") }.bodyAsText())
    }

    @Test
    fun aRejectedKeysFolderIsAnError() {
        val logged = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(BMSPublicKeys::class.java) as Logger
        logger.addAppender(logged)
        try {
            val directory = Files.createTempDirectory("package-keys")
            Files.writeString(directory.resolve("bms-2026.pub"), "AAAA")
            val keys = BMSPublicKeys.load(directory, bundled = mapOf("bms-2026" to ""), trusted = { false })
            assertEquals("", keys["bms-2026"])
            assertTrue(logged.list.any { it.level == Level.ERROR && it.formattedMessage.contains(directory.toString()) })
        } finally {
            logger.detachAppender(logged)
        }
    }
}
