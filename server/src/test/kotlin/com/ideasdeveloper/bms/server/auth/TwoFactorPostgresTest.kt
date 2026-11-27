package com.ideasdeveloper.bms.server.auth

import com.ideasdeveloper.bms.server.admins.ServerActorLookup
import com.ideasdeveloper.bms.server.admins.ServerAdminPasswords
import com.ideasdeveloper.bms.server.auth.application.AccountException
import com.ideasdeveloper.bms.server.auth.application.AccountPasswords
import com.ideasdeveloper.bms.server.auth.application.AccountService
import com.ideasdeveloper.bms.server.auth.application.SessionService
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.domain.LoginSuccessResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationSessionRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationUserRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.database.SwitchNeedsSecondFactorException
import com.ideasdeveloper.bms.server.auth.infrastructure.security.ApplicationPasswordHasher
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.auth.twofactor.Totp
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorAccountService
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorPolicy
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorRequiredException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorSignInException
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.company.application.ServerOwnerAuthorizer
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.AgentSignIn
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.security.MasterKeySecretBox
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two-step verification (TWO_FACTOR.md), company sign-in throttle (B1) and equal sign-in work (B2)
 * against a real PostgreSQL. Runs only with BMS_TEST_PG_URL (jdbc:postgresql://host:port/postgres?user=postgres)
 * of a DISPOSABLE server: it creates and drops bms_tf_* databases and tf_* roles.
 */
class TwoFactorPostgresTest {
    private val url: String? = System.getenv("BMS_TEST_PG_URL")?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_tf_central"
    private val companies = listOf("acme", "beta")
    private val opened = mutableListOf<LoginDatabases>()
    private val masterKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val ownerPassword = "Duena-de-la-empresa-1"
    private val userPassword = "usuario-luis-123"
    private val adminPassword = "administradora-ana-123"
    private val adminId = UUID.randomUUID()
    private val group = UUID.randomUUID()

    private fun urlOf(database: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$database$1")
    private fun connect(database: String): Connection = DriverManager.getConnection(urlOf(database))
    private fun source(database: String) = PGSimpleDataSource().apply { setURL(urlOf(database)) }
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }
    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private val prefix get() = urlOf("X").substringBefore("/X") + "/"
    private val pgUser get() = Regex("user=([^&]+)").find(url!!)?.groupValues?.get(1) ?: "postgres"

    @AfterTest
    fun close() {
        opened.forEach { it.close() }
        if (url == null) return
        DriverManager.getConnection(url).use { admin ->
            (listOf(central) + companies.map { "bms_tf_$it" }).forEach { admin.run("DROP DATABASE IF EXISTS $it WITH (FORCE)") }
            companies.forEach { admin.run("DROP ROLE IF EXISTS tf_${it}_owner") }
        }
    }

    private fun reset() {
        DriverManager.getConnection(url!!).use { admin ->
            (listOf(central) + companies.map { "bms_tf_$it" }).forEach { name ->
                admin.run("DROP DATABASE IF EXISTS $name WITH (FORCE)")
                admin.run("CREATE DATABASE $name")
            }
            companies.forEach { code ->
                admin.run("DROP ROLE IF EXISTS tf_${code}_owner")
                admin.run("CREATE ROLE tf_${code}_owner LOGIN PASSWORD '$ownerPassword'")
            }
        }
        connect(central).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            File(root, "database/control/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                .forEach { db.run(migration("database/control/migrations/${it.name}")) }
            companies.forEach { code ->
                db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), '$code', 'Empresa $code', 'bms_tf_$code', '$group')")
            }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('${UUID.randomUUID()}', 'tf_acme_owner', TRUE, 'Duena', 'duena@example.com')")
            db.run("INSERT INTO server_owners (user_id) SELECT id FROM application_users WHERE postgres_role = 'tf_acme_owner'")
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$adminId', 'tf_ana', TRUE, 'Ana', 'ana@example.com')")
            db.prepareStatement("INSERT INTO server_admins (user_id, is_active, all_companies, password_hash, must_change_password) VALUES (?, TRUE, TRUE, ?, FALSE)").use {
                it.setObject(1, adminId)
                it.setString(2, ServerAdminPasswords.encode(adminPassword))
                it.executeUpdate()
            }
            db.run("INSERT INTO server_admin_permissions (user_id, permission) VALUES ('$adminId', 'server.companies.enter'), ('$adminId', 'server.audit.view')")
        }
        companies.forEach { code ->
            connect("bms_tf_$code").use { db ->
                listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                    .forEach { db.run(migration("database/core/migrations/$it.sql")) }
                listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials",
                    "V006__create_extensions", "V008__account_reset_throttle", "V011__create_activity_log", "V012__create_two_factor",
                    "V013__create_two_factor_email_recovery", "V014__session_second_factor_mark")
                    .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
                val owner = UUID.randomUUID()
                db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$owner', 'tf_${code}_owner', TRUE, 'Dueno $code', '$code@example.com')")
                db.run("INSERT INTO business_owner (user_id) VALUES ('$owner')")
                listOf("luis", "eva", "old", "off").forEach { name -> addUser(db, name, active = name != "off", iterations = if (name == "old") 120_000 else ApplicationPasswordHasher.Iterations) }
            }
        }
    }

    private fun addUser(db: Connection, name: String, active: Boolean, iterations: Int) {
        val id = UUID.randomUUID()
        db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$id', '$name', $active, '$name', '$name@example.com')")
        val hash = ApplicationPasswordHasher.hash(userPassword, iterations)
        db.prepareStatement("INSERT INTO application_user_credentials (user_id, password_salt, password_hash, iterations) VALUES (?, ?, ?, ?)").use {
            it.setObject(1, id); it.setString(2, hash.salt); it.setString(3, hash.hash); it.setInt(4, hash.iterations); it.executeUpdate()
        }
    }

    private fun databases(): LoginDatabases {
        val data = source(central)
        return LoginDatabases(data, Database.connect(data), urlOf(central), "[]", pgUser, "", prefix, 3600, secrets = MasterKeySecretBox(masterKey)).also { opened += it }
    }

    private fun scalar(database: String, sql: String): String? = connect(database).use { db ->
        db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
    }

    private fun userId(database: String, login: String) = UUID.fromString(scalar(database, "SELECT id FROM application_users WHERE postgres_role = '$login'")!!)

    private fun login(db: LoginDatabases, user: String, password: String, company: String?, address: String = "10.0.0.1"): LoginSuccessResponse? =
        db.service.login(LoginCredentials(user, password, company, address))

    private fun codeAt(secret: String, offset: Long = 0) = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now().epochSecond) + offset)

    // Enrols [user] through the account routes' service and returns (secret, recovery codes).
    private fun enrol(db: LoginDatabases, user: String, password: String, company: String?): Pair<String, List<String>> {
        val session = login(db, user, password, company)!!
        val service = TwoFactorAccountService(db)
        // (PostgreSQL roles of the test server may use trust authentication: only application passwords are checked here.)
        if (!user.endsWith("_owner")) assertFailsWith<AccountException> { service.setup(session.accessToken, company, "mal") }
        val setup = service.setup(session.accessToken, company, password)
        assertTrue(setup.otpauthUri.startsWith("otpauth://totp/"))
        assertFailsWith<TwoFactorException> { service.enable(session.accessToken, company, "000000".takeIf { it != codeAt(setup.secret) } ?: "111111") }
        val codes = service.enable(session.accessToken, company, codeAt(setup.secret, -1)).recoveryCodes
        assertEquals(10, codes.size)
        assertTrue(service.status(session.accessToken, company).enabled)
        return setup.secret to codes
    }

    private fun challenge(block: () -> Unit): TwoFactorRequiredException = assertFailsWith<TwoFactorRequiredException> { block() }

    @Test
    fun companySignInNeedsTheSecondStepAndNeverLeaksTheSecret() {
        if (url == null) return
        reset()
        val db = databases()
        val other = login(db, "luis", userPassword, "acme")!!
        val (secret, codes) = enrol(db, "luis", userPassword, "acme")
        val acme = "bms_tf_acme"
        val luis = userId(acme, "luis")
        // Sealed with the master key; the secret, codes and recovery codes never reach the activity log.
        val stored = scalar(acme, "SELECT secret_sealed FROM application_user_two_factor WHERE user_id = '$luis'")!!
        assertTrue(stored.startsWith("enc:v1:") && !stored.contains(secret))
        assertEquals("10", scalar(acme, "SELECT count(*) FROM application_user_recovery_codes WHERE user_id = '$luis'"))
        assertNull(scalar(acme, "SELECT code_hash FROM application_user_recovery_codes WHERE code_hash LIKE '%${codes[0].take(5)}%'"))
        // Enabling closed the other sessions.
        assertEquals("t", scalar(acme, "SELECT revoked_at IS NOT NULL FROM application_sessions WHERE token_hash = '${SessionTokenGenerator().hash(other.accessToken)}'"))
        assertEquals("1", scalar(acme, "SELECT count(*) FROM activity_log WHERE action = 'enabled' AND summary LIKE '%dos pasos%'"))

        // Step 1 with the right password opens no session.
        val sessionsBefore = scalar(acme, "SELECT count(*) FROM application_sessions")
        val first = challenge { login(db, "luis", userPassword, "acme") }
        assertEquals(sessionsBefore, scalar(acme, "SELECT count(*) FROM application_sessions"))
        // A wrong password is still a plain refusal.
        assertNull(login(db, "luis", "otra-cosa-123", "acme"))
        // Another address cannot use the challenge.
        assertFailsWith<TwoFactorSignInException> { db.completeTwoFactor(first.token, codeAt(secret), "10.9.9.9") }
        val wrong = assertFailsWith<TwoFactorSignInException> { db.completeTwoFactor(first.token, "000001", "10.0.0.1") }
        assertEquals(TwoFactorException.InvalidCode, wrong.code)
        assertNotNull(wrong.attemptsLeft)
        // The code used to enable (previous step) cannot be used again.
        assertFailsWith<TwoFactorSignInException> { db.completeTwoFactor(first.token, codeAt(secret, -1), "10.0.0.1") }
        val signedIn = db.completeTwoFactor(first.token, codeAt(secret), "10.0.0.1")
        assertEquals("luis", signedIn.username)
        assertFalse(signedIn.recoveryCodeUsed)
        // Single use: the challenge is gone, and the same code does not work in a new challenge.
        assertFailsWith<TwoFactorSignInException> { db.completeTwoFactor(first.token, codeAt(secret), "10.0.0.1") }
        val again = challenge { login(db, "luis", userPassword, "acme") }
        assertFailsWith<TwoFactorSignInException> { db.completeTwoFactor(again.token, codeAt(secret), "10.0.0.1") }

        // A recovery code works once.
        val withRecovery = db.completeTwoFactor(challenge { login(db, "luis", userPassword, "acme") }.token, codes[0].lowercase(), "10.0.0.1")
        assertTrue(withRecovery.recoveryCodeUsed)
        assertEquals(9, withRecovery.recoveryCodesLeft)
        assertFailsWith<TwoFactorSignInException> { db.completeTwoFactor(challenge { login(db, "luis", userPassword, "acme") }.token, codes[0], "10.0.0.1") }

        val logged = connect(acme).use { c -> c.createStatement().use { st -> st.executeQuery("SELECT summary || coalesce(changes::text, '') FROM activity_log").use { r -> buildList { while (r.next()) add(r.getString(1)) } } } }
        assertTrue(logged.none { row -> row.contains(secret) || codes.any { row.contains(it) || row.contains(it.replace("-", "")) } })
        assertTrue(logged.any { it.contains("codigo de recuperacion") })
    }

    @Test
    fun wrongCodesAreThrottledPerAccountAcrossChallenges() {
        if (url == null) return
        reset()
        val db = databases()
        val (secret, _) = enrol(db, "luis", userPassword, "acme")
        // The failure that enrol() provoked counts too: up to 5 in 15 minutes.
        var refused: Exception? = null
        repeat(6) {
            val token = challenge { login(db, "luis", userPassword, "acme") }.token
            refused = runCatching { db.completeTwoFactor(token, "000002", "10.0.0.1") }.exceptionOrNull() as Exception
            if (refused is TwoFactorException) return@repeat
        }
        assertIs<TwoFactorException>(refused)
        // Even the right code is refused while throttled (nothing is compared).
        val blocked = assertFailsWith<TwoFactorException> { db.completeTwoFactor(challenge { login(db, "luis", userPassword, "acme") }.token, codeAt(secret), "10.0.0.1") }
        assertEquals(TwoFactorException.TooManyAttempts, blocked.code)
        // Kept in the database: a new LoginDatabases (a restart) is still throttled.
        val restarted = databases()
        assertFailsWith<TwoFactorException> { restarted.completeTwoFactor(challenge { login(restarted, "luis", userPassword, "acme") }.token, codeAt(secret), "10.0.0.1") }
    }

    @Test
    fun agentsPasswordRecoveryAndTheOwnerSwitcherCannotSkipIt() {
        if (url == null) return
        reset()
        val db = databases()
        val (secret, codes) = enrol(db, "luis", userPassword, "acme")
        val central = source(central)

        // Agents (OAuth): the old check refuses, the new one asks for the code.
        val access = ModuleAccessService(central, db, login = db.service::login)
        assertNull(access.verifyCredentials("acme", "luis", userPassword))
        assertEquals(AgentSignIn.SecondFactorRequired, access.signInForAgent("acme", "luis", userPassword, null, "10.0.0.1"))
        assertEquals(AgentSignIn.InvalidCode, access.signInForAgent("acme", "luis", userPassword, "000003", "10.0.0.1"))
        assertIs<AgentSignIn.Verified>(access.signInForAgent("acme", "luis", userPassword, codeAt(secret), "10.0.0.1"))
        // The client address is recorded with the agent's sign-in (B5).
        assertNotNull(scalar("bms_tf_acme", "SELECT 1 FROM activity_log WHERE action = 'signed_in' AND summary LIKE '%10.0.0.1%'"))

        // Password recovery by email needs the second factor too.
        val accounts = AccountService(central, db, { null }, AccountPasswords({ _, _ -> }, { _, _ -> }))
        val luis = userId("bms_tf_acme", "luis")
        fun issue(code: String) = connect("bms_tf_acme").use { c ->
            val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("$luis:$code".toByteArray()))
            c.run("DELETE FROM password_reset_codes")
            c.run("INSERT INTO password_reset_codes (user_id, code_hash, expires_at, attempts) VALUES ('$luis', '$hash', now() + interval '10 minutes', 0)")
        }
        issue("123456")
        val required = assertFailsWith<AccountException> { accounts.confirmPasswordReset("luis", "acme", "123456", "nueva-clave-segura-1") }
        assertEquals("TWO_FACTOR_REQUIRED", required.code)
        assertEquals("INVALID_CODE", assertFailsWith<AccountException> { accounts.confirmPasswordReset("luis", "acme", "654321", "nueva-clave-segura-1", codes[1]) }.code)
        assertEquals("TWO_FACTOR_INVALID_CODE", assertFailsWith<AccountException> { accounts.confirmPasswordReset("luis", "acme", "123456", "nueva-clave-segura-1", "000004") }.code)
        val events = mutableListOf<com.ideasdeveloper.bms.server.auth.application.CredentialsChanged>()
        com.ideasdeveloper.bms.server.auth.application.AccountSecurityEvents.subscribe("test") { events += it; 1 }
        try {
            accounts.confirmPasswordReset("luis", "acme", "123456", "nueva-clave-segura-1", codes[1])
        } finally {
            com.ideasdeveloper.bms.server.auth.application.AccountSecurityEvents.unsubscribe("test")
        }
        // Review item 2: the account's agents are told to end (Agentes revokes their tokens).
        assertEquals(listOf(com.ideasdeveloper.bms.server.auth.application.CredentialsChanged("acme", userId("bms_tf_acme", "luis"), "password_reset")), events)
        assertNotNull(challenge { login(db, "luis", "nueva-clave-segura-1", "acme") })

        // Switchers: into an account that uses two-step verification only from a session that PASSED a second step.
        val (betaSecret, _) = enrol(db, "tf_beta_owner", ownerPassword, "beta")
        assertNotNull(betaSecret)
        // A server administrator without it: an owner without it is fine, one with it is refused.
        val ana = login(db, "tf_ana", adminPassword, null)!!
        assertNotNull(db.switchTo(null, ana.accessToken, "acme"))
        assertFailsWith<SwitchNeedsSecondFactorException> { db.switchTo(null, login(db, "tf_ana", adminPassword, null)!!.accessToken, "beta") }
        // The attack of the review: with only the origin password, enrol an own authenticator in that session and switch.
        val acmeOwner = login(db, "tf_acme_owner", ownerPassword, "acme")!!
        val service = TwoFactorAccountService(db)
        val setup = service.setup(acmeOwner.accessToken, "acme", ownerPassword)
        service.enable(acmeOwner.accessToken, "acme", codeAt(setup.secret, -1))
        assertFailsWith<SwitchNeedsSecondFactorException> { db.switchTo("acme", acmeOwner.accessToken, "beta") }
        // A session that passed the second step switches, and the new session keeps the mark (it can switch back).
        val acmeSigned = db.completeTwoFactor(challenge { login(db, "tf_acme_owner", ownerPassword, "acme") }.token, codeAt(setup.secret), "10.0.0.1")
        val switched = db.switchTo("acme", acmeSigned.accessToken, "beta")
        assertEquals("tf_beta_owner", switched?.username)
        assertEquals("tf_acme_owner", db.switchTo("beta", switched!!.accessToken, "acme")?.username)
        // The administrator once signed in with a code enters too.
        val (anaSecret, _) = enrol(db, "tf_ana", adminPassword, null)
        val anaSigned = db.completeTwoFactor(challenge { login(db, "tf_ana", adminPassword, null) }.token, codeAt(anaSecret), "10.0.0.1")
        assertEquals("tf_beta_owner", db.switchTo(null, anaSigned.accessToken, "beta")?.username)
    }

    @Test
    fun theOwnerCanRequireItForAdministrators() {
        if (url == null) return
        reset()
        val db = databases()
        val central = source(central)
        val exposed = Database.connect(central)
        val actors = ServerOwnerAuthorizer(central, SessionService(ApplicationSessionRepository(exposed), ApplicationUserRepository(exposed), SessionTokenGenerator(), 3600))

        val before = login(db, "tf_ana", adminPassword, null)!!
        assertFalse(before.twoFactorSetupRequired)
        assertIs<ServerActorLookup.Found>(actors.resolve(before.accessToken))

        TwoFactorPolicy.setRequireForAdmins(central, true, AuditActor.owner("duena", null))
        assertEquals("1", scalar(this.central, "SELECT count(*) FROM provisioning_audit_log WHERE action = 'server.two_factor_policy_changed'"))
        val pending = login(db, "tf_ana", adminPassword, null)!!
        assertTrue(pending.twoFactorSetupRequired)
        val lookup = actors.resolve(pending.accessToken)
        assertIs<ServerActorLookup.PasswordChangeRequired>(lookup)
        assertTrue(lookup.twoFactorSetup)
        // The switcher refuses while it is pending.
        assertNull(db.switchTo(null, pending.accessToken, "acme"))

        val (secret, _) = enrol(db, "tf_ana", adminPassword, null)
        // The central database got its own data key (control V017).
        assertNotNull(scalar(this.central, "SELECT wrapped_key FROM tenant_data_keys WHERE id = 1"))
        val token = challenge { login(db, "tf_ana", adminPassword, null) }.token
        val signedIn = db.completeTwoFactor(token, codeAt(secret), "10.0.0.1")
        assertFalse(signedIn.twoFactorSetupRequired)
        assertIs<ServerActorLookup.Found>(actors.resolve(signedIn.accessToken))
        assertEquals("1", scalar(this.central, "SELECT count(*) FROM provisioning_audit_log WHERE action = 'two_factor.enabled' AND actor_role = 'tf_ana'"))
        // Disabling is refused while the owner requires it.
        val refused = assertFailsWith<TwoFactorException> { TwoFactorAccountService(db).disable(signedIn.accessToken, null, adminPassword, codeAt(secret, 1)) }
        assertEquals(TwoFactorException.RequiredByServer, refused.code)
    }

    @Test
    fun companySignInIsThrottledPerLoginAndIpv6SlashSixtyFour() {
        if (url == null) return
        reset()
        val db = databases()
        val attacker = "2001:db8:aa:bb:"
        repeat(10) { index -> assertNull(login(db, "eva", "mal-$index", "acme", attacker + "${index + 1}::1")) }
        // Throttled for the whole /64, even with the right password.
        assertNull(login(db, "eva", userPassword, "acme", attacker + "ffff::9"))
        // Another client is not affected, and the delay counter only starts at 50.
        assertNotNull(login(db, "eva", userPassword, "acme", "10.0.0.7"))
        assertEquals(0L, db.serverLoginDelayMillis(LoginCredentials("eva", "x", "acme", "10.0.0.7")))
        // Another company's login of the same name is counted apart.
        assertNotNull(login(db, "eva", userPassword, "beta", attacker + "1::1"))
    }

    @Test
    fun unknownDisabledAndOldHashUsersCostTheSameWork() {
        if (url == null) return
        reset()
        val repository = ApplicationUserRepository(Database.connect(source("bms_tf_acme")))
        fun work(block: () -> Unit): Long = Pbkdf2Counter.measure(block)
        val expected = ApplicationPasswordHasher.Iterations.toLong()
        assertEquals(expected, work { repository.verifyApplicationPassword("nadie", userPassword) })
        assertEquals(expected, work { repository.verifyApplicationPassword("off", userPassword) })
        assertEquals(expected, work { repository.verifyApplicationPassword("eva", "mal") })
        assertEquals(expected, work { assertFalse(repository.verifyApplicationPassword("old", "mal")) })
        // An unknown company spends a sign-in's work as well.
        val db = databases()
        assertEquals(expected, work { assertNull(login(db, "eva", userPassword, "nadie")) })
    }

    // Review item 2: turning it on ends the earlier access: other sessions, switcher company sessions, agents.
    @Test
    fun enablingEndsTheEarlierAccessOfAServerAccount() {
        if (url == null) return
        reset()
        val db = databases()
        val ana = login(db, "tf_ana", adminPassword, null)!!
        val other = login(db, "tf_ana", adminPassword, null)!!
        val inAcme = db.switchTo(null, other.accessToken, "acme")!!
        val events = mutableListOf<com.ideasdeveloper.bms.server.auth.application.CredentialsChanged>()
        com.ideasdeveloper.bms.server.auth.application.AccountSecurityEvents.subscribe("test") { events += it; 2 }
        val response = try {
            val service = TwoFactorAccountService(db)
            val secret = service.setup(ana.accessToken, null, adminPassword).secret
            service.enable(ana.accessToken, null, codeAt(secret, -1))
        } finally {
            com.ideasdeveloper.bms.server.auth.application.AccountSecurityEvents.unsubscribe("test")
        }
        assertEquals(listOf(com.ideasdeveloper.bms.server.auth.application.CredentialsChanged(null, adminId, "two_factor_enabled")), events)
        assertEquals(2, response.agentsDisconnected)
        assertTrue(response.notice!!.isNotBlank())
        // The current session stays; the other one and the company session it opened are closed.
        assertNotNull(TwoFactorAccountService(db).status(ana.accessToken, null))
        assertEquals("t", scalar(central, "SELECT revoked_at IS NOT NULL FROM application_sessions WHERE token_hash = '${SessionTokenGenerator().hash(other.accessToken)}'"))
        assertEquals("t", scalar("bms_tf_acme", "SELECT revoked_at IS NOT NULL FROM application_sessions WHERE token_hash = '${SessionTokenGenerator().hash(inAcme.accessToken)}'"))
    }
}
