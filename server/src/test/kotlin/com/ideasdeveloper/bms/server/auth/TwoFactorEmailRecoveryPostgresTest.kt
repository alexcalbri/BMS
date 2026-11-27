package com.ideasdeveloper.bms.server.auth

import com.ideasdeveloper.bms.server.admins.ServerAdminPasswords
import com.ideasdeveloper.bms.server.auth.api.twoFactorRecoveryLinkRoutes
import com.ideasdeveloper.bms.server.auth.application.AccountService
import com.ideasdeveloper.bms.server.auth.application.AccountPasswords
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.security.ApplicationPasswordHasher
import com.ideasdeveloper.bms.server.auth.twofactor.Totp
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorAccountService
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorEmailRecovery
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorPolicy
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorRequiredException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorSignInException
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.SmtpSettings
import com.ideasdeveloper.bms.server.security.MasterKeySecretBox
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Delayed recovery of two-step verification by email (TWO_FACTOR.md §7) on a real PostgreSQL, with an
 * injected clock: start, warning mail, cancel by link / sign-in / email change, the job (twice), the code,
 * wrong and expired codes, and that no token or code reaches the logs or the activity log.
 * Runs only with BMS_TEST_PG_URL; creates and drops bms_tfr_* databases and tfr_* roles.
 */
class TwoFactorEmailRecoveryPostgresTest {
    private val url: String? = System.getenv("BMS_TEST_PG_URL")?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_tfr_central"
    private val company = "bms_tfr_acme"
    private val opened = mutableListOf<LoginDatabases>()
    private val masterKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() })
    private val password = "usuario-luis-123"
    private val adminPassword = "administradora-ana-123"
    private var now = Instant.parse("2026-11-27T10:00:00Z")
    private val mails = mutableListOf<Triple<String, String, String>>()
    private val notices = mutableListOf<String>()
    private val smtp = SmtpSettings("smtp.test.invalid", 587, "", "", "bms@test.invalid", trusted = true)

    private fun urlOf(database: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$database$1")
    private fun connect(database: String): Connection = DriverManager.getConnection(urlOf(database))
    private fun source(database: String) = PGSimpleDataSource().apply { setURL(urlOf(database)) }
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }
    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private val prefix get() = urlOf("X").substringBefore("/X") + "/"
    private val pgUser get() = Regex("user=([^&]+)").find(url!!)?.groupValues?.get(1) ?: "postgres"
    private fun scalar(database: String, sql: String): String? = connect(database).use { db ->
        db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
    }

    @AfterTest
    fun close() {
        opened.forEach { it.close() }
        if (url == null) return
        DriverManager.getConnection(url).use { admin ->
            listOf(central, company).forEach { admin.run("DROP DATABASE IF EXISTS $it WITH (FORCE)") }
            admin.run("DROP ROLE IF EXISTS tfr_owner")
        }
    }

    private fun reset() {
        DriverManager.getConnection(url!!).use { admin ->
            listOf(central, company).forEach { admin.run("DROP DATABASE IF EXISTS $it WITH (FORCE)"); admin.run("CREATE DATABASE $it") }
            admin.run("DROP ROLE IF EXISTS tfr_owner")
            admin.run("CREATE ROLE tfr_owner LOGIN PASSWORD 'x'")
        }
        connect(central).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            File(root, "database/control/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                .forEach { db.run(migration("database/control/migrations/${it.name}")) }
            db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), 'acme', 'Acme', '$company', gen_random_uuid())")
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES (gen_random_uuid(), 'tfr_owner', TRUE, 'Duena', 'duena@example.com')")
            db.run("INSERT INTO server_owners (user_id) SELECT id FROM application_users WHERE postgres_role = 'tfr_owner'")
            val admin = UUID.randomUUID()
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$admin', 'tfr_ana', TRUE, 'Ana', 'ana@example.com')")
            db.prepareStatement("INSERT INTO server_admins (user_id, is_active, all_companies, password_hash, must_change_password) VALUES (?, TRUE, TRUE, ?, FALSE)").use {
                it.setObject(1, admin); it.setString(2, ServerAdminPasswords.encode(adminPassword)); it.executeUpdate()
            }
        }
        connect(company).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials",
                "V006__create_extensions", "V008__account_reset_throttle", "V011__create_activity_log", "V012__create_two_factor", "V013__create_two_factor_email_recovery", "V014__session_second_factor_mark")
                .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
            val id = UUID.randomUUID()
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email, language) VALUES ('$id', 'luis', TRUE, 'Luis', 'luis@example.com', 'en')")
            val hash = ApplicationPasswordHasher.hash(password)
            db.prepareStatement("INSERT INTO application_user_credentials (user_id, password_salt, password_hash, iterations) VALUES (?, ?, ?, ?)").use {
                it.setObject(1, id); it.setString(2, hash.salt); it.setString(3, hash.hash); it.setInt(4, hash.iterations); it.executeUpdate()
            }
        }
    }

    private class Setup(val db: LoginDatabases, val recovery: TwoFactorEmailRecovery)

    private val deferred = mutableListOf<() -> Unit>()

    private fun setup(mailWorks: () -> Boolean = { true }, defer: Boolean = false): Setup {
        val data = source(central)
        val db = LoginDatabases(data, Database.connect(data), urlOf(central), "[]", pgUser, "", prefix, 3600, secrets = MasterKeySecretBox(masterKey)).also { opened += it }
        val recovery = TwoFactorEmailRecovery(
            central = data, sources = db::accountSource, companies = db::activeCompanyCodes, mail = { smtp }, twoFactor = db.twoFactor,
            notify = { _, _, title, _ -> notices += title },
            sendMail = { _, to, subject, body -> if (!mailWorks()) error("smtp down"); mails += Triple(to, subject, body) },
            clock = { now }, wait = Duration.ofDays(7),
            background = { task -> if (defer) deferred += task else task() },
        )
        db.emailRecovery = recovery
        return Setup(db, recovery)
    }

    private fun codeAt(secret: String) = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now().epochSecond))

    // Turns two-step verification on for [user] and returns its secret.
    private fun enrol(db: LoginDatabases, user: String, pass: String, code: String?): String {
        val session = db.service.login(LoginCredentials(user, pass, code, "10.0.0.1"))!!
        val service = TwoFactorAccountService(db)
        val secret = service.setup(session.accessToken, code, pass).secret
        awaitStepMargin()
        service.enable(session.accessToken, code, Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now().epochSecond) - 1))
        return secret
    }

    private fun challenge(db: LoginDatabases, user: String = "luis", pass: String = password, code: String? = "acme"): String =
        assertFailsWith<TwoFactorRequiredException> { db.service.login(LoginCredentials(user, pass, code, "10.0.0.1")) }.token

    private fun linkToken(): Pair<String, String?> {
        val body = mails.first { it.second.contains("started", ignoreCase = true) || it.second.contains("inicio", ignoreCase = true) }.third
        val link = Regex("""/auth/two-factor/recovery-cancel\?t=([A-Za-z0-9_-]{43})(&c=([a-z0-9_]+))?""").find(body)!!
        return link.groupValues[1] to link.groupValues[3].ifEmpty { null }
    }

    private fun mailedCode(): String = Regex("""[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}""").find(mails.last().third)!!.value

    private fun state(database: String = company) = scalar(database, "SELECT status || ':' || coalesce(end_reason, '') FROM application_user_2fa_email_recovery ORDER BY started_at DESC LIMIT 1")

    @Test
    fun theWholeLifecycleWithTheCodeTurnsTwoFactorOff() {
        if (url == null) return
        reset()
        val s = setup()
        enrol(s.db, "luis", password, "acme")
        val token = challenge(s.db)
        // A bad challenge is refused; a good one gives the same answer every time.
        assertFailsWith<TwoFactorSignInException> { s.db.startEmailRecovery("x".repeat(43), "10.0.0.1", "https://bms.example") }
        s.db.startEmailRecovery(token, "10.0.0.1", "https://bms.example")
        s.db.startEmailRecovery(token, "10.0.0.1", "https://bms.example")
        assertEquals("1", scalar(company, "SELECT count(*) FROM application_user_2fa_email_recovery"))
        // The warning goes at once, in the account's language, with the address, the date and the cancel link.
        assertEquals(1, mails.size)
        val (to, subject, body) = mails.single()
        assertEquals("luis@example.com", to)
        assertTrue(subject.contains("BMS") && !subject.contains("Se inicio"), subject)
        assertTrue(body.contains("10.0.0.1") && body.contains("2026-12-04") && body.contains("https://bms.example/auth/two-factor/recovery-cancel?t="), body)
        assertEquals(1, notices.size)
        // The status shows it to the signed-in user (who passes the second step: that would cancel it, so read it directly).
        assertEquals("pending:", state())
        val (cancel, _) = linkToken()
        assertNull(scalar(company, "SELECT 1 FROM application_user_2fa_email_recovery WHERE cancel_token_hash LIKE '%$cancel%'"))

        // Not before 7 days, and twice in a row sends one code.
        now = now.plus(Duration.ofDays(6))
        assertEquals(0, s.recovery.runDue())
        now = now.plus(Duration.ofDays(1)).plusSeconds(1)
        assertEquals(1, s.recovery.runDue())
        assertEquals(0, s.recovery.runDue())
        assertEquals(2, mails.size)
        val code = mailedCode()
        assertEquals("code_sent:", state())

        // Wrong code, then the right one: one session, and two-step verification is off.
        assertFailsWith<TwoFactorSignInException> { s.db.completeEmailRecovery(challenge(s.db), "AAAA-AAAA-AAAA", "10.0.0.1") }
        val signedIn = s.db.completeEmailRecovery(challenge(s.db), code.lowercase(), "10.0.0.1")
        assertTrue(signedIn.twoFactorDisabled)
        assertEquals("completed:code", state())
        assertEquals("0", scalar(company, "SELECT count(*) FROM application_user_two_factor"))
        assertEquals("0", scalar(company, "SELECT count(*) FROM application_user_recovery_codes"))
        // Single use: signing in now is a plain sign-in, the code does nothing more.
        assertNotNull(s.db.service.login(LoginCredentials("luis", password, "acme", "10.0.0.1")))

        // Recorded without the token or the code.
        val rows = connect(company).use { c -> c.createStatement().use { st -> st.executeQuery("SELECT summary || coalesce(changes::text, '') FROM activity_log").use { r -> buildList { while (r.next()) add(r.getString(1)) } } } }
        assertTrue(rows.count { it.contains("recuperacion del acceso") || it.contains("Recupero el acceso") } >= 3, rows.toString())
        assertTrue(rows.none { it.contains(cancel) || it.contains(code) || it.contains(code.replace("-", "")) })
    }

    @Test
    fun theLinkCancelsOnlyByPostAndOnce() = testApplication {
        if (url == null) return@testApplication
        reset()
        val s = setup()
        enrol(s.db, "luis", password, "acme")
        s.db.startEmailRecovery(challenge(s.db), "10.0.0.1", "https://bms.example")
        val (token, code) = linkToken()
        assertEquals("acme", code)
        application { routing { twoFactorRecoveryLinkRoutes { s.recovery } } }
        // GET (what a mail scanner does) changes nothing and has strict headers.
        val page = client.get("/auth/two-factor/recovery-cancel?t=$token&c=acme")
        assertTrue(page.bodyAsText().contains("<form method=\"post\""))
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
        assertEquals("no-referrer", page.headers["Referrer-Policy"])
        assertEquals("pending:", state())
        assertTrue(client.get("/auth/two-factor/recovery-cancel?t=$token&c=acme") { header(HttpHeaders.Accept, "application/json") }.bodyAsText().contains("true"))
        val done = client.submitForm("/auth/two-factor/recovery-cancel", parameters { append("t", token); append("c", "acme") })
        assertTrue(done.bodyAsText().contains("<h1>"))
        assertEquals("cancelled:link", state())
        // Single use.
        val again = client.submitForm("/auth/two-factor/recovery-cancel", parameters { append("t", token); append("c", "acme") }) { header(HttpHeaders.Accept, "application/json") }
        assertTrue(again.bodyAsText().contains("false"))
        // The job never mails a code for it.
        now = now.plus(Duration.ofDays(8))
        assertEquals(0, s.recovery.runDue())
        // A new one may be started.
        s.db.startEmailRecovery(challenge(s.db), "10.0.0.1", "https://bms.example")
        assertEquals("pending:", state())
    }

    @Test
    fun signingInWithTheAuthenticatorOrChangingTheEmailCancelsIt() {
        if (url == null) return
        reset()
        val s = setup()
        val secret = enrol(s.db, "luis", password, "acme")
        s.db.startEmailRecovery(challenge(s.db), "10.0.0.1", "https://bms.example")
        val signedIn = s.db.completeTwoFactor(challenge(s.db), codeAt(secret), "10.0.0.1")
        assertTrue(signedIn.emailRecoveryCancelled)
        assertEquals("cancelled:sign_in", state())
        assertTrue(mails.last().third.contains("cancel", ignoreCase = true))

        // Email change through the profile.
        now = now.plusSeconds(60)
        s.db.startEmailRecovery(challenge(s.db), "10.0.0.1", "https://bms.example")
        assertEquals("pending:", state())
        val accounts = AccountService(source(central), s.db, { smtp }, AccountPasswords({ _, _ -> }, { _, _ -> }), verifyPassword = { _, _, p -> p == password })
        accounts.updateProfile(signedIn.accessToken, "acme", "Luis", "otro@example.com", password)
        assertEquals("cancelled:email_change", state())

        // An email changed by another path is caught when the code is due.
        now = now.plusSeconds(60)
        s.db.startEmailRecovery(challenge(s.db), "10.0.0.1", "https://bms.example")
        connect(company).use { it.run("UPDATE application_users SET email = 'tercero@example.com' WHERE postgres_role = 'luis'") }
        now = now.plus(Duration.ofDays(8))
        assertEquals(0, s.recovery.runDue())
        assertEquals("cancelled:email_change", state())
    }

    @Test
    fun startsAreThrottledAndAnExpiredOrLostCodeNeverLeavesTwoValidCodes() {
        if (url == null) return
        reset()
        var smtpUp = false
        val s = setup(mailWorks = { smtpUp })
        // Everything logged meanwhile, to prove no code reaches the logs.
        val logged = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val rootLogger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        rootLogger.addAppender(logged)
        enrol(s.db, "luis", password, "acme")
        s.db.startEmailRecovery(challenge(s.db), "10.0.0.1", "https://bms.example")
        // The warning could not be mailed: the process stays (the job mails the code later).
        assertEquals("pending:", state())
        now = now.plus(Duration.ofDays(7)).plusSeconds(1)
        assertEquals(0, s.recovery.runDue())
        val firstHash = scalar(company, "SELECT code_hash FROM application_user_2fa_email_recovery")
        assertNotNull(firstHash)
        // Retried only after a while, with a new code that replaces the first one.
        smtpUp = true
        assertEquals(0, s.recovery.runDue())
        now = now.plus(TwoFactorEmailRecovery.ResendAfter).plusSeconds(1)
        assertEquals(1, s.recovery.runDue())
        val code = mailedCode()
        assertFalse(firstHash == scalar(company, "SELECT code_hash FROM application_user_2fa_email_recovery"))
        assertEquals(0, s.recovery.runDue())

        // Wrong codes are throttled like the others, even the right one then.
        repeat(5) { runCatching { s.db.completeEmailRecovery(challenge(s.db), "BBBB-BBBB-BBBB", "10.0.0.1") } }
        val throttled = assertFailsWith<TwoFactorException> { s.db.completeEmailRecovery(challenge(s.db), code, "10.0.0.1") }
        assertEquals(TwoFactorException.TooManyAttempts, throttled.code)

        // After 24 h the code is useless and the process ends.
        now = now.plus(TwoFactorEmailRecovery.CodeLifetime).plusSeconds(1)
        assertEquals(0, s.recovery.runDue())
        assertEquals("expired:expired", state())
        rootLogger.detachAppender(logged)
        assertTrue(logged.list.isNotEmpty())
        assertTrue(logged.list.none { event ->
            val text = event.formattedMessage + (event.throwableProxy?.message ?: "")
            text.contains(code) || text.contains(code.replace("-", "")) || text.contains(firstHash!!)
        })
        assertFailsWith<TwoFactorSignInException> { s.db.completeEmailRecovery(challenge(s.db), code, "10.0.0.1") }

        // At most 3 starts in 7 days (the first one is older than that now); the answer does not change.
        now = now.plusSeconds(60)
        repeat(3) {
            s.db.startEmailRecovery(challenge(s.db), "10.0.0.1", "https://bms.example")
            s.db.cancelPendingForTest(s.recovery)
        }
        s.db.startEmailRecovery(challenge(s.db), "10.0.0.1", "https://bms.example")
        assertEquals("4", scalar(company, "SELECT count(*) FROM application_user_2fa_email_recovery"))
        assertEquals("cancelled:user", state())
    }

    // Cancels through the signed-in route's service (the person passed no second step: read the open row directly).
    private fun LoginDatabases.cancelPendingForTest(recovery: TwoFactorEmailRecovery) {
        val user = UUID.fromString(scalar(company, "SELECT id FROM application_users WHERE postgres_role = 'luis'")!!)
        accountSource("acme")!!.connection.use { recovery.cancelOpen(it, "acme", user, "user") }
    }

    @Test
    fun aServerAdministratorRecoversAndMustEnrolAgainWhenRequired() {
        if (url == null) return
        reset()
        val s = setup()
        enrol(s.db, "tfr_ana", adminPassword, null)
        TwoFactorPolicy.setRequireForAdmins(source(central), true, AuditActor.owner("tfr_owner", null))
        s.db.startEmailRecovery(challenge(s.db, "tfr_ana", adminPassword, null), "10.0.0.1", "https://bms.example")
        val (_, code) = linkToken()
        assertNull(code)
        assertEquals("1", scalar(central, "SELECT count(*) FROM provisioning_audit_log WHERE action = 'two_factor.email_recovery_started'"))
        now = now.plus(Duration.ofDays(7)).plusSeconds(1)
        assertEquals(1, s.recovery.runDue())
        val signedIn = s.db.completeEmailRecovery(challenge(s.db, "tfr_ana", adminPassword, null), mailedCode(), "10.0.0.1")
        assertTrue(signedIn.twoFactorDisabled)
        assertTrue(signedIn.twoFactorSetupRequired)
        val audit = connect(central).use { c -> c.createStatement().use { st -> st.executeQuery("SELECT action || details::text FROM provisioning_audit_log WHERE action LIKE 'two_factor.email_recovery%'").use { r -> buildList { while (r.next()) add(r.getString(1)) } } } }
        assertEquals(setOf("two_factor.email_recovery_started", "two_factor.email_recovery_code_sent", "two_factor.email_recovery_completed"), audit.map { it.substringBefore('{') }.toSet())
        assertTrue(audit.none { it.contains(mailedCode()) })
    }

    // Review item 5: the start answers before any work, every outcome alike; a lost warning is sent again by the job.
    @Test
    fun theStartRunsInTheBackgroundAndALostWarningIsSentAgain() {
        if (url == null) return
        reset()
        var smtpUp = false
        val s = setup(mailWorks = { smtpUp }, defer = true)
        enrol(s.db, "luis", password, "acme")
        s.db.startEmailRecovery(challenge(s.db), "10.0.0.1", "https://bms.example")
        // Nothing happened yet while the request answered.
        assertEquals("0", scalar(company, "SELECT count(*) FROM application_user_2fa_email_recovery"))
        deferred.removeAt(0).invoke()
        assertEquals("pending:", state())
        assertNull(scalar(company, "SELECT warning_mailed_at FROM application_user_2fa_email_recovery"))
        val firstHash = scalar(company, "SELECT cancel_token_hash FROM application_user_2fa_email_recovery")
        smtpUp = true
        now = now.plus(TwoFactorEmailRecovery.ResendAfter).plusSeconds(1)
        s.recovery.runDue()
        assertNotNull(scalar(company, "SELECT warning_mailed_at FROM application_user_2fa_email_recovery"))
        // A new link replaced the one that never arrived.
        assertFalse(firstHash == scalar(company, "SELECT cancel_token_hash FROM application_user_2fa_email_recovery"))
        assertTrue(mails.single().third.contains("https://bms.example/auth/two-factor/recovery-cancel?t="))
        s.recovery.runDue()
        assertEquals(1, mails.size)
    }

    // Review item 4: without a known public base the warning has no link and says where to cancel.
    @Test
    fun withoutAKnownHostTheWarningHasNoLink() {
        if (url == null) return
        reset()
        val s = setup()
        enrol(s.db, "luis", password, "acme")
        s.db.startEmailRecovery(challenge(s.db), "10.0.0.1", null)
        val body = mails.single().third
        assertFalse(body.contains("/auth/two-factor/recovery-cancel"))
        assertTrue(body.contains("Two-step verification") || body.contains("Verificacion en dos pasos"), body)
    }
}
