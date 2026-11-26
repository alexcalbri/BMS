package com.ideasdeveloper.bms.server.admins

import com.ideasdeveloper.bms.server.auth.application.SessionService
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationSessionRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationUserRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.auth.application.PasswordAttemptLimiter
import com.ideasdeveloper.bms.server.company.api.CreateCompanyUserRequest
import com.ideasdeveloper.bms.server.company.api.companyRoutes
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningConfig
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningException
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.company.application.CompanySelfService
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.PackageInstallQueue
import com.ideasdeveloper.bms.server.modules.RemoteModuleCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import com.ideasdeveloper.bms.server.company.application.ServerOwnerAuthorizer
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real SQL of server administrators (control V015) against a test PostgreSQL: sign-in, the forced
 * password change, live permission and scope checks, the company switcher, revocation and audit.
 * Runs only with BMS_SERVER_ADMINS_PG_URL (jdbc:postgresql://host:port/postgres?user=postgres) of a
 * DISPOSABLE server whose user may create databases: it creates and drops bms_sa_* databases.
 */
class ServerAdminsPostgresTest {
    private val url: String? = System.getenv("BMS_SERVER_ADMINS_PG_URL")?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_sa_central"
    private val companies = listOf("acme", "otra")
    private val workspaceDb = "bms_sa_servidor"
    private val opened = mutableListOf<LoginDatabases>()

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")

    private fun urlOf(database: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$database$1")

    private fun connect(database: String): Connection = DriverManager.getConnection(urlOf(database))

    private fun source(database: String) = PGSimpleDataSource().apply { setURL(urlOf(database)) }

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    @AfterTest
    fun close() {
        opened.forEach { it.close() }
    }

    private fun reset() {
        DriverManager.getConnection(url!!).use { admin ->
            (listOf(central, workspaceDb) + companies.map { "bms_sa_$it" }).forEach { name ->
                admin.run("DROP DATABASE IF EXISTS $name WITH (FORCE)")
                admin.run("CREATE DATABASE $name")
            }
            // The owner's PostgreSQL role: privileged work runs as it (SET ROLE).
            admin.run("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sa_owner') THEN CREATE ROLE sa_owner; END IF; END $$")
        }
        connect(central).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            File(root, "database/control/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                .forEach { db.run(it.readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', 'sa_owner', TRUE, 'Duena', 'duena@example.com')")
            db.run("INSERT INTO server_owners (user_id) VALUES ('$ownerId')")
            companies.forEach { code -> db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), '$code', 'Empresa $code', 'bms_sa_$code', gen_random_uuid())") }
            db.run("INSERT INTO companies (id, code, name, database_name, owner_group, is_server_workspace) VALUES (gen_random_uuid(), 'servidor', 'Espacio', '$workspaceDb', gen_random_uuid(), TRUE)")
        }
        connect(workspaceDb).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials")
                .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
        }
        companies.forEach { code ->
            connect("bms_sa_$code").use { db ->
                listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile")
                    .forEach { db.run(migration("database/core/migrations/$it.sql")) }
                listOf("V001__create_business_owner", "V003__create_tenant_modules", "V005__create_application_user_credentials")
                    .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
                val id = UUID.randomUUID()
                db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$id', '${code}_owner', TRUE, 'Dueno $code', '$code@example.com')")
                db.run("INSERT INTO business_owner (user_id) VALUES ('$id')")
            }
        }
    }

    private val ownerId = UUID.randomUUID()

    private val prefix get() = urlOf("X").substringBefore("/X") + "/"
    private val pgUser get() = Regex("user=([^&]+)").find(url!!)?.groupValues?.get(1) ?: "postgres"

    private fun loginDatabases(limiter: PasswordAttemptLimiter? = null): LoginDatabases {
        val data = source(central)
        return (
            if (limiter == null) LoginDatabases(data, Database.connect(data), urlOf(central), "[]", pgUser, "", prefix, 3600)
            else LoginDatabases(data, Database.connect(data), urlOf(central), "[]", pgUser, "", prefix, 3600, limiter)
        ).also { opened += it }
    }

    private fun provisioning(databases: LoginDatabases, queue: PackageInstallQueue? = null, queued: QueuedInstallActors = QueuedInstallActors.Unchecked) = CompanyProvisioningService(
        central = source(central),
        config = CompanyProvisioningConfig(urlOf("postgres").substringBefore("?"), pgUser, "", prefix, File(root, "database").path),
        loginDatabases = databases,
        moduleRegistry = ModuleRegistry(emptyList()),
        remoteModuleCatalog = RemoteModuleCatalog(),
        defaultCatalogUrl = "",
        modulePackagesRoot = java.nio.file.Files.createTempDirectory("sa-modules"),
        modulePackageBuilder = null,
        requestRestart = {},
        installQueue = queue,
        queuedActors = queued,
    )

    private fun actorOf(store: ServerAdminStore, id: String) = store.find(UUID.fromString(id))!!.let {
        ServerActor(it.userId, it.login, ServerActorKind.Admin, it.permissions, it.allCompanies, it.companies, "sa_owner")
    }

    private fun auditRows(action: String): List<Pair<String, String>> = connect(central).use { db ->
        db.prepareStatement("SELECT actor_role, details::text FROM provisioning_audit_log WHERE action = ? ORDER BY created_at").use { q ->
            q.setString(1, action)
            q.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
        }
    }

    private fun scalar(database: String, sql: String): String? = connect(database).use { db ->
        db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
    }

    private fun owner() = ServerActor(ownerId, "sa_owner", ServerActorKind.Owner, emptySet(), true, emptySet(), "sa_owner")

    private class Setup(val databases: LoginDatabases, val store: ServerAdminStore, val service: ServerAdminService, val actors: ServerOwnerAuthorizer)

    private fun setup(
        limiter: PasswordAttemptLimiter? = null,
        workspaceStaff: (LoginDatabases) -> ((UUID, AuditActor, String) -> Unit) = { { _, _, _ -> } },
    ): Setup {
        reset()
        val databases = loginDatabases(limiter)
        val data = source(central)
        val store = databases.admins
        val service = ServerAdminService(
            store = store,
            loginTaken = { login -> store.centralLoginTaken(login) || data.connection.use { c -> c.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?").use { q -> q.setString(1, login); q.executeQuery().use { it.next() } } } },
            revokeCompanySessions = { databases.revokeCompanySessionsOpenedBy(it) },
            audit = { actor, action, details -> data.connection.use { ProvisioningAudit.insert(it, actor, action, null, null, details) } },
            deactivateWorkspaceStaff = workspaceStaff(databases),
        )
        val exposed = Database.connect(data)
        val sessions = SessionService(ApplicationSessionRepository(exposed), ApplicationUserRepository(exposed), SessionTokenGenerator(), 3600)
        return Setup(databases, store, service, ServerOwnerAuthorizer(data, sessions))
    }

    private val initial = "inicial-0123456789"
    private val changed = "cambiada-0123456789"

    private fun Setup.createAna(vararg permissions: ServerPermission) =
        service.create(owner(), "ana", "Ana Admin", "ana@example.com", initial, permissions.map { it.id }, allCompanies = false, companies = listOf("acme"))

    private fun tenantRevoked(code: String, token: String): Boolean = connect("bms_sa_$code").use { db ->
        db.prepareStatement("SELECT revoked_at IS NOT NULL FROM application_sessions WHERE token_hash = ?").use { q ->
            q.setString(1, SessionTokenGenerator().hash(token))
            q.executeQuery().use { it.next() && it.getBoolean(1) }
        }
    }

    @Test
    fun signInForcedPasswordChangeAndLiveChecks() {
        if (url == null) return
        val s = setup()
        val ana = s.createAna(ServerPermission.CompaniesEnter, ServerPermission.Settings)
        assertTrue(ana.mustChangePassword)

        // Same answer for unknown user, wrong password and (below) inactive administrator.
        assertNull(s.databases.service.login(LoginCredentials("ana", "otra-contrasena-mala")))
        assertNull(s.databases.service.login(LoginCredentials("nadie", initial)))
        val login = assertNotNull(s.databases.service.login(LoginCredentials("ana", initial)))
        assertEquals("server_admin", login.role)
        assertEquals("server", login.scope)
        assertTrue(login.mustChangePassword)
        assertEquals(listOf("server.companies.enter", "server.settings"), login.serverPermissions)
        assertEquals(listOf("acme"), login.serverCompanies)
        assertTrue(login.enabledModules.isEmpty())

        // Until the password changes nothing else works, not even the switcher.
        val first = s.actors.resolve(login.accessToken)
        assertIs<ServerActorLookup.PasswordChangeRequired>(first)
        assertNull(s.databases.switchTargets(null, login.accessToken))
        assertFailsWith<ServerAdminException> { s.service.changeOwnPassword(first.actor, login.accessToken, "equivocada-123456789", changed) }
        s.service.changeOwnPassword(first.actor, login.accessToken, initial, changed)
        val actor = assertIs<ServerActorLookup.Found>(s.actors.resolve(login.accessToken)).actor
        assertFalse(actor.isOwner)
        assertTrue(actor.has(ServerPermission.Settings))
        assertFalse(actor.has(ServerPermission.CompaniesView))
        assertNull(s.databases.service.login(LoginCredentials("ana", initial)))

        // Switcher: only companies in scope.
        assertEquals(listOf("acme"), s.databases.switchTargets(null, login.accessToken)!!.companies.map { it.code })
        assertNull(s.databases.switchTo(null, login.accessToken, "otra"))
        val company = assertNotNull(s.databases.switchTo(null, login.accessToken, "acme"))
        assertTrue(company.serverOwnerAccess)
        assertEquals("business_owner", company.role)
        assertTrue(s.databases.companySessionAllowed("acme", company.accessToken))
        assertTrue(s.databases.openedByServerAdmin("acme", company.accessToken))
        // From inside the company it may go back to the server but not to an out-of-scope company.
        assertNull(s.databases.switchTo("acme", company.accessToken, "otra"))

        // Audit of entering names the administrator.
        connect(central).use { db ->
            db.prepareStatement("SELECT actor_role, actor_user_id, actor_kind, actor_name FROM provisioning_audit_log WHERE action = 'server_admin.entered_company'").use { q ->
                q.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("ana", rows.getString(1))
                    assertEquals(UUID.fromString(ana.id), rows.getObject(2, UUID::class.java))
                    assertEquals("server_admin", rows.getString(3))
                    assertEquals("Ana Admin", rows.getString(4))
                }
            }
        }

        // Taking the permission away closes the company session at its next use.
        s.service.setPermissions(owner(), ana.id, listOf(ServerPermission.Settings.id), null, null)
        assertFalse(s.databases.companySessionAllowed("acme", company.accessToken))
        assertTrue(tenantRevoked("acme", company.accessToken))
        assertEquals(emptyList(), s.databases.switchTargets(null, login.accessToken)!!.companies)

        // Deactivating: the server session and company sessions stop at once.
        s.service.setPermissions(owner(), ana.id, listOf(ServerPermission.CompaniesEnter.id), null, null)
        val again = assertNotNull(s.databases.switchTo(null, login.accessToken, "acme"))
        s.service.setActive(owner(), ana.id, false)
        assertEquals(ServerActorLookup.None, s.actors.resolve(login.accessToken))
        assertTrue(tenantRevoked("acme", again.accessToken))
        assertFalse(s.databases.companySessionAllowed("acme", again.accessToken))
        assertNull(s.databases.service.login(LoginCredentials("ana", changed)))
        // Reactivated, the old token stays revoked.
        s.service.setActive(owner(), ana.id, true)
        assertEquals(ServerActorLookup.None, s.actors.resolve(login.accessToken))

        // Deleting keeps the login reserved; it can never sign in again.
        s.service.delete(owner(), ana.id)
        assertNull(s.databases.service.login(LoginCredentials("ana", changed)))
        assertFailsWith<ServerAdminException> { s.createAna() }
    }

    @Test
    fun anOwnerGroupReachingOutOfScopeBlocksEntering() {
        if (url == null) return
        val s = setup()
        val ana = s.createAna(ServerPermission.CompaniesEnter)
        val token = s.databases.service.login(LoginCredentials("ana", initial))!!.accessToken
        s.service.changeOwnPassword(assertIs<ServerActorLookup.PasswordChangeRequired>(s.actors.resolve(token)).actor, token, initial, changed)
        assertNotNull(s.databases.switchTo(null, token, "acme"))
        // otra joins the owner group of acme: inside acme the business owner would reach otra.
        connect(central).use { it.run("UPDATE companies SET owner_group = (SELECT owner_group FROM companies WHERE code = 'acme') WHERE code = 'otra'") }
        assertEquals(emptyList(), s.databases.switchTargets(null, token)!!.companies)
        assertNull(s.databases.switchTo(null, token, "acme"))
        s.service.setPermissions(owner(), ana.id, listOf(ServerPermission.CompaniesEnter.id), allCompanies = false, companies = listOf("acme", "otra"))
        assertEquals(setOf("acme", "otra"), s.databases.switchTargets(null, token)!!.companies.map { it.code }.toSet())
    }

    @Test
    fun onlyTheOwnerManagesAndIdentitiesNeverOverlap() {
        if (url == null) return
        val s = setup()
        val ana = s.createAna(*ServerPermission.entries.toTypedArray())
        val anaActor = ServerActor(UUID.fromString(ana.id), "ana", ServerActorKind.Admin, ServerPermission.entries.toSet(), true, emptySet(), "sa_owner")
        // Every permission does not let an administrator manage administrators (themself included).
        assertFailsWith<ServerPermissionException> { s.service.setPermissions(anaActor, ana.id, ServerPermission.entries.map { it.id }, true, null) }
        assertFailsWith<ServerPermissionException> { s.service.resetPassword(anaActor, ana.id, changed) }
        assertFailsWith<ServerPermissionException> { s.service.create(anaActor, "luis", "Luis", "luis@example.com", initial, emptyList(), true, emptyList()) }
        assertFailsWith<ServerPermissionException> { s.service.list(anaActor) }

        // Logins: not the owner, not a PostgreSQL role, not unknown permissions or companies.
        assertFailsWith<ServerAdminException> { s.service.create(owner(), "sa_owner", "X", "x@example.com", initial, emptyList(), true, emptyList()) }
        assertFailsWith<ServerAdminException> { s.service.create(owner(), "postgres", "X", "x@example.com", initial, emptyList(), true, emptyList()) }
        assertFailsWith<ServerAdminException> { s.service.create(owner(), "luis", "X", "x@example.com", "corta", emptyList(), true, emptyList()) }
        assertFailsWith<ServerAdminException> { s.service.create(owner(), "luis", "X", "x@example.com", initial, listOf("server.admins.manage"), true, emptyList()) }
        assertFailsWith<ServerAdminException> { s.service.create(owner(), "luis", "X", "x@example.com", initial, emptyList(), false, listOf("noexiste")) }

        // The database itself refuses an owner as administrator, an administrator as owner and a role as login.
        connect(central).use { db ->
            assertFailsWith<SQLException> { db.run("INSERT INTO server_admins (user_id, password_hash) VALUES ('$ownerId', 'pbkdf2_sha256\$1\$a\$b')") }
            assertFailsWith<SQLException> { db.run("INSERT INTO server_owners (user_id) VALUES ('${ana.id}')") }
            val roleUser = UUID.randomUUID()
            db.run("INSERT INTO application_users (id, postgres_role, is_active) VALUES ('$roleUser', 'postgres', TRUE)")
            assertFailsWith<SQLException> { db.run("INSERT INTO server_admins (user_id, password_hash) VALUES ('$roleUser', 'pbkdf2_sha256\$1\$a\$b')") }
            // Unknown stored permissions are ignored when read.
            db.run("INSERT INTO server_admin_permissions (user_id, permission) VALUES ('${ana.id}', 'server.unknown')")
        }
        assertTrue(s.store.find(UUID.fromString(ana.id))!!.permissions.none { it.id == "server.unknown" })
    }

    @Test
    fun auditIsScopedAndNamesTheAdministrator() {
        if (url == null) return
        val s = setup()
        val ana = s.createAna(ServerPermission.AuditView)
        val anaActor = assertNotNull(s.store.find(UUID.fromString(ana.id))).let {
            ServerActor(it.userId, it.login, ServerActorKind.Admin, it.permissions, it.allCompanies, it.companies, "sa_owner")
        }
        source(central).connection.use { db ->
            ProvisioningAudit.insert(db, anaActor.audit, "company.updated", "acme", null, """{"name":"A"}""")
            ProvisioningAudit.insert(db, AuditActor.owner("sa_owner", ownerId), "company.updated", "otra", null, "{}")
            ProvisioningAudit.insert(db, AuditActor.owner("sa_owner", ownerId), "server.renamed", null, null, "{}")
            ProvisioningAudit.insert(db, AuditActor.system("sa_owner"), "extension.incompatible", "acme", null, "{}")
        }
        val audit = ServerAudit(source(central))
        val scoped = audit.page(anaActor, AuditFilter())
        // Rows of acme and their own (the creation of the administrator is a server row, not theirs); never otra or the server rename.
        assertTrue(scoped.items.all { it.companyCode == "acme" || it.actorUserId == ana.id }, "${scoped.items.map { it.action to it.companyCode }}")
        assertTrue(scoped.items.none { it.companyCode == "otra" || it.action == "server.renamed" })
        val mine = scoped.items.first { it.action == "company.updated" }
        assertEquals("server_admin", mine.actorKind)
        assertEquals("ana", mine.actorRole)
        assertEquals("Ana Admin", mine.actorName)
        assertEquals("A", mine.details["name"].toString().trim('"'))
        val all = audit.page(owner(), AuditFilter(pageSize = 2))
        assertEquals(2, all.items.size)
        assertTrue(all.hasMore)
        assertEquals("server_admin.created", audit.page(owner(), AuditFilter(action = "admin.created")).items.single().action)
        assertTrue(audit.page(owner(), AuditFilter(company = "otra")).items.all { it.companyCode == "otra" })
    }

    private fun Setup.signedIn(admin: String = "ana"): String {
        val token = databases.service.login(LoginCredentials(admin, initial))!!.accessToken
        service.changeOwnPassword(assertIs<ServerActorLookup.PasswordChangeRequired>(actors.resolve(token)).actor, token, initial, changed)
        return token
    }

    private fun switcherRows(token: String): List<Pair<UUID, java.time.OffsetDateTime>> = connect(central).use { db ->
        db.prepareStatement("SELECT server_owner_user_id, expires_at FROM server_owner_company_sessions WHERE token_hash = ?").use { q ->
            q.setString(1, SessionTokenGenerator().hash(token))
            q.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.getObject(2, java.time.OffsetDateTime::class.java)) } }
        }
    }

    @Test
    fun switcherRowsOutliveTheirSessionAndDescendantsStayTracked() {
        if (url == null) return
        val s = setup()
        val ana = s.createAna(ServerPermission.CompaniesEnter)
        s.service.setPermissions(owner(), ana.id, listOf(ServerPermission.CompaniesEnter.id), allCompanies = false, companies = listOf("acme", "otra"))
        // acme and otra share the owner group: inside acme the business owner may switch to otra.
        connect(central).use { it.run("UPDATE companies SET owner_group = (SELECT owner_group FROM companies WHERE code = 'acme') WHERE code = 'otra'") }
        val token = s.signedIn()
        val acme = assertNotNull(s.databases.switchTo(null, token, "acme"))

        // The row expires after the session it tracks (plus a margin), never before it.
        val sessionExpiry = connect("bms_sa_acme").use { db ->
            db.prepareStatement("SELECT expires_at FROM application_sessions WHERE token_hash = ?").use { q ->
                q.setString(1, SessionTokenGenerator().hash(acme.accessToken))
                q.executeQuery().use { it.next(); it.getObject(1, java.time.OffsetDateTime::class.java) }
            }
        }
        val row = switcherRows(acme.accessToken).single()
        assertEquals(UUID.fromString(ana.id), row.first)
        assertTrue(row.second.isAfter(sessionExpiry.plusMinutes(30)), "row ${row.second} must outlive the session $sessionExpiry")

        // Even a row whose expires_at already passed keeps the session tracked: the session's own expiry decides.
        connect(central).use { it.run("UPDATE server_owner_company_sessions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'") }
        assertTrue(s.databases.openedByServerAdmin("acme", acme.accessToken))

        // A session minted by the business owner switcher from a tracked session is tracked too.
        val otra = assertNotNull(s.databases.switchCompany("acme", acme.accessToken, "otra"))
        assertTrue(otra.serverOwnerAccess)
        assertEquals(UUID.fromString(ana.id), switcherRows(otra.accessToken).single().first)
        val entered = Json.parseToJsonElement(auditRows("server_admin.entered_company").last().second).jsonObject
        assertEquals("acme", entered["from"]!!.jsonPrimitive.content)
        assertEquals("otra_owner", entered["businessOwnerUsername"]!!.jsonPrimitive.content)
        // Out of scope (otra leaves the scope): the descendant is refused like any switcher session.
        s.service.setPermissions(owner(), ana.id, listOf(ServerPermission.CompaniesEnter.id), allCompanies = false, companies = listOf("acme"))
        assertNull(s.databases.switchCompany("acme", acme.accessToken, "otra"))
        assertFalse(s.databases.companySessionAllowed("otra", otra.accessToken))
        assertTrue(tenantRevoked("otra", otra.accessToken))

        // Logging out with another company's code does not untrack a session.
        s.databases.logout("otra", acme.accessToken)
        assertEquals(1, switcherRows(acme.accessToken).size)
        // Deactivating revokes every session the administrator opened, expired rows included.
        s.service.setActive(owner(), ana.id, false)
        assertTrue(tenantRevoked("acme", acme.accessToken))
        assertFalse(s.databases.companySessionAllowed("acme", acme.accessToken))
        // Rows are pruned only a day after they expired.
        connect(central).use { it.run("UPDATE server_owner_company_sessions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '2 days' WHERE token_hash = '${SessionTokenGenerator().hash(otra.accessToken)}'") }
        s.service.setActive(owner(), ana.id, true)
        connect(central).use { it.run("UPDATE companies SET owner_group = gen_random_uuid() WHERE code = 'otra'") }
        val again = s.databases.service.login(LoginCredentials("ana", changed))!!.accessToken
        assertNotNull(s.databases.switchTo(null, again, "acme"))
        assertEquals(0, switcherRows(otra.accessToken).size)
        assertEquals(1, switcherRows(acme.accessToken).size)
    }

    @Test
    fun serverSignInIsThrottledPerLoginWithoutLockingForEver() {
        if (url == null) return
        var now = Instant.parse("2026-11-26T10:00:00Z")
        val s = setup(limiter = PasswordAttemptLimiter(maxFailures = 3, window = Duration.ofMinutes(5), clock = { now }))
        s.createAna()
        repeat(3) { assertNull(s.databases.service.login(LoginCredentials("ana", "mala-0123456789-$it"))) }
        // Throttled: even the right password gets the same generic refusal.
        assertNull(s.databases.service.login(LoginCredentials("ANA ", initial)))
        assertNull(s.databases.service.login(LoginCredentials("ana", initial)))
        // Other logins are not affected.
        val other = s.service.create(owner(), "luis", "Luis Admin", "luis@example.com", initial, emptyList(), allCompanies = true, companies = emptyList())
        assertNotNull(s.databases.service.login(LoginCredentials(other.login, initial)))
        // Attempts while throttled do not extend it: once the window ends the right password works.
        now = now.plus(Duration.ofMinutes(6))
        assertNotNull(s.databases.service.login(LoginCredentials("ana", initial)))
    }

    @Test
    fun businessOwnerEmailUserAndPasswordNeedTheOwnerPasswordPermission() {
        if (url == null) return
        val s = setup()
        val edit = s.createAna(ServerPermission.CompaniesEdit)
        val both = s.service.create(owner(), "luis", "Luis Admin", "luis@example.com", initial, listOf(ServerPermission.CompaniesEdit.id, ServerPermission.CompaniesOwnerPassword.id), false, listOf("acme"))
        val tokens = mapOf("edit" to actorOf(s.store, edit.id), "both" to actorOf(s.store, both.id))
        val resolver = ServerActorResolver { token -> tokens[token]?.let { ServerActorLookup.Found(it) } ?: ServerActorLookup.None }
        val service = provisioning(s.databases)
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { companyRoutes(resolver, service, selfService = null) }
            }
            suspend fun put(token: String, body: String) = client.put("/companies/acme/business-owner") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            // companies.edit alone: a new email (password recovery goes there) is refused, the name is not.
            val email = put("edit", """{"username":"acme_owner","displayName":"Dueno acme","email":"atacante@example.com"}""")
            assertEquals(HttpStatusCode.Forbidden, email.status, email.bodyAsText())
            assertTrue("SERVER_PERMISSION_REQUIRED" in email.bodyAsText())
            assertEquals("acme@example.com", scalar("bms_sa_acme", "SELECT email FROM application_users WHERE postgres_role = 'acme_owner'"))
            assertEquals(HttpStatusCode.Forbidden, put("edit", """{"username":"acme_owner","displayName":"Dueno acme","email":"acme@example.com","password":"nueva-contrasena-123"}""").status)
            // Same email in another case is no change.
            val name = put("edit", """{"username":"acme_owner","displayName":"Nombre Nuevo","email":"ACME@example.com"}""")
            assertEquals(HttpStatusCode.OK, name.status, name.bodyAsText())
            assertEquals("Nombre Nuevo", scalar("bms_sa_acme", "SELECT display_name FROM application_users WHERE postgres_role = 'acme_owner'"))
            // With owner_password the email may change; the audit says what changed.
            val withPermission = put("both", """{"username":"acme_owner","displayName":"Nombre Nuevo","email":"nuevo@example.com"}""")
            assertEquals(HttpStatusCode.OK, withPermission.status, withPermission.bodyAsText())
            val rows = auditRows("company.owner_updated").map { Json.parseToJsonElement(it.second).jsonObject }
            assertEquals(listOf("false", "true"), rows.map { it["emailChanged"].toString() })
            assertTrue(rows.all { it["usernameChanged"].toString() == "false" && it["passwordChanged"].toString() == "false" })
            assertEquals(listOf("ana", "luis"), auditRows("company.owner_updated").map { it.first })

            // GET is scoped like PUT, and the refusal does not name the out-of-scope company.
            assertEquals(HttpStatusCode.OK, client.get("/companies/acme/business-owner") { header(HttpHeaders.Authorization, "Bearer edit") }.status)
            connect(central).use { it.run("UPDATE companies SET owner_group = (SELECT owner_group FROM companies WHERE code = 'acme') WHERE code = 'otra'") }
            val hidden = client.get("/companies/acme/business-owner") { header(HttpHeaders.Authorization, "Bearer edit") }
            assertEquals(HttpStatusCode.Forbidden, hidden.status)
            assertFalse("otra" in hidden.bodyAsText(), hidden.bodyAsText())
            val hiddenPut = put("both", """{"username":"acme_owner","displayName":"Nombre Nuevo","email":"nuevo@example.com"}""")
            assertEquals(HttpStatusCode.Forbidden, hiddenPut.status)
            assertFalse("otra" in hiddenPut.bodyAsText(), hiddenPut.bodyAsText())
        }
    }

    @Test
    fun auditDetailsAreJsonAndAFailedAuditRollsTheChangeBack() {
        if (url == null) return
        val s = setup()
        val ana = actorOf(s.store, s.createAna(ServerPermission.Settings).id)
        val service = provisioning(s.databases)
        // Control characters are refused at validation (server, company and user names).
        assertFailsWith<CompanyProvisioningException> { service.updateServerName("Mi\u0001Servidor", "sa_owner", ana.audit) }
        assertFalse(com.ideasdeveloper.bms.server.auth.application.UserProfileRules.validName("Ana\u0000"))
        // Quotes, backslashes and other text are stored as JSON, never broken by hand-built escaping.
        assertEquals("Servidor \"A\" \\ B", service.updateServerName("Servidor \"A\" \\ B", "sa_owner", ana.audit).name)
        assertEquals("Servidor \"A\" \\ B", Json.parseToJsonElement(auditRows("server.renamed").single().second).jsonObject["name"]!!.jsonPrimitive.content)
        // A NUL inside details (jsonb refuses it even escaped) does not break the insert.
        source(central).connection.use { ProvisioningAudit.insert(it, ana.audit, "test.nul", null, null, com.ideasdeveloper.bms.server.company.application.auditDetails("v" to "a\u0000b\u0001")) }
        assertEquals("a\uFFFDb\u0001", Json.parseToJsonElement(auditRows("test.nul").single().second).jsonObject["v"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { ProvisioningAudit.normalizedDetails("[1]") }

        // The audit insert fails: the rename is rolled back with it.
        connect(central).use { it.run("ALTER TABLE provisioning_audit_log ADD CONSTRAINT no_more CHECK (action <> 'server.renamed') NOT VALID") }
        assertFails { service.updateServerName("Otro nombre", "sa_owner", ana.audit) }
        assertEquals("Servidor \"A\" \\ B", s.databases.serverName())
    }

    @Test
    fun workspaceStaffOfAnAdministratorCloseWithThem() {
        if (url == null) return
        lateinit var self: CompanySelfService
        val s = setup(workspaceStaff = { { id, by, reason -> self.deactivateWorkspaceStaffOf(id, by, reason) } })
        val ana = s.createAna(ServerPermission.Workspace)
        val luis = s.service.create(owner(), "luis", "Luis Admin", "luis@example.com", initial, listOf(ServerPermission.Workspace.id), true, emptyList())
        val tokens = mapOf("ana" to actorOf(s.store, ana.id), "luis" to actorOf(s.store, luis.id), "owner" to owner())
        self = CompanySelfService(
            central = source(central),
            loginDatabases = s.databases,
            moduleRegistry = ModuleRegistry(emptyList()),
            backupsRoot = java.nio.file.Files.createTempDirectory("sa-backups"),
            serverActors = ServerActorResolver { token -> tokens[token]?.let { ServerActorLookup.Found(it) } ?: ServerActorLookup.None },
        )
        val staff = self.createUser("servidor", "ana", CreateCompanyUserRequest("caja", "caja-0123456789", emptyList(), "Caja Uno", "caja@example.com"))
        val other = self.createUser("servidor", "luis", CreateCompanyUserRequest("otro", "otro-0123456789", emptyList(), "Otro Uno", "otro@example.com"))
        // Who created each account is recorded centrally, and each creation is audited as that administrator.
        assertEquals(ana.id, scalar(central, "SELECT created_by FROM server_workspace_staff WHERE user_id = '${staff.userId}'"))
        assertEquals("server_admin", scalar(central, "SELECT created_by_kind FROM server_workspace_staff WHERE user_id = '${staff.userId}'"))
        assertEquals(listOf("ana", "luis"), auditRows("server_workspace.user_created").map { it.first })
        val session = assertNotNull(s.databases.service.login(LoginCredentials("caja", "caja-0123456789", "servidor"))).accessToken

        // luis sets a new password on ana's account: from now on luis knows it, so it follows luis.
        self.updateUser("servidor", "luis", staff.userId, com.ideasdeveloper.bms.server.company.api.UpdateCompanyUserRequest("caja", "Caja Uno", "caja@example.com", true, emptyList(), "caja-nueva-0123456789"))
        assertEquals(luis.id, scalar(central, "SELECT created_by FROM server_workspace_staff WHERE user_id = '${staff.userId}'"))

        // Deactivating luis closes both accounts whose credentials luis knows, and their sessions.
        s.service.setActive(owner(), luis.id, false)
        assertEquals("f", scalar(workspaceDb, "SELECT is_active FROM application_users WHERE id = '${staff.userId}'"))
        assertEquals("f", scalar(workspaceDb, "SELECT is_active FROM application_users WHERE id = '${other.userId}'"))
        assertEquals("t", scalar(workspaceDb, "SELECT revoked_at IS NOT NULL FROM application_sessions WHERE token_hash = '${SessionTokenGenerator().hash(session)}'"))
        assertNull(s.databases.service.login(LoginCredentials("caja", "caja-nueva-0123456789", "servidor")))
        val closed = auditRows("server_workspace.staff_deactivated").single()
        assertEquals("sa_owner", closed.first)
        assertTrue(staff.userId in closed.second && "admin_deactivated" in closed.second, closed.second)

        // Losing server.workspace does the same for the accounts of ana: one she created, and one of the
        // owner whose email she changed (password recovery would go to her mailbox).
        val third = self.createUser("servidor", "ana", CreateCompanyUserRequest("tercero", "tercero-0123456789", emptyList(), "Tercero Uno", "t@example.com"))
        val owners = self.createUser("servidor", "owner", CreateCompanyUserRequest("delowner", "delowner-0123456789", emptyList(), "Del Owner", "d@example.com"))
        assertEquals(ownerId.toString(), scalar(central, "SELECT created_by FROM server_workspace_staff WHERE user_id = '${owners.userId}'"))
        self.updateUser("servidor", "ana", owners.userId, com.ideasdeveloper.bms.server.company.api.UpdateCompanyUserRequest("delowner", "Del Owner", "ana-buzon@example.com", true, emptyList()))
        assertEquals(ana.id, scalar(central, "SELECT created_by FROM server_workspace_staff WHERE user_id = '${owners.userId}'"))
        assertTrue("\"emailChanged\": true" in auditRows("server_workspace.user_updated").last().second)
        s.service.setPermissions(owner(), ana.id, emptyList(), null, null)
        assertEquals("f", scalar(workspaceDb, "SELECT is_active FROM application_users WHERE id = '${third.userId}'"))
        assertEquals("f", scalar(workspaceDb, "SELECT is_active FROM application_users WHERE id = '${owners.userId}'"))

        // A failed audit insert stops the workspace change itself.
        connect(central).use { it.run("ALTER TABLE provisioning_audit_log ADD CONSTRAINT no_more CHECK (action <> 'server_workspace.user_created') NOT VALID") }
        assertFails { self.createUser("servidor", "luis", CreateCompanyUserRequest("cuarto", "cuarto-0123456789", emptyList(), "Cuarto Uno", "c@example.com")) }
        assertNull(scalar(workspaceDb, "SELECT id FROM application_users WHERE postgres_role = 'cuarto'"))
    }

    @Test
    fun queuedInstallsRunOnlyWithTheRequestersCurrentRights() {
        if (url == null) return
        val s = setup()
        val ana = s.createAna(ServerPermission.ModulesInstall)
        assertEquals(AuditActor.SystemKind, s.store.queuedInstallActor("", ServerPermission.ModulesInstall)?.kind)
        assertEquals(AuditActor.ServerOwnerKind, s.store.queuedInstallActor("sa_owner", ServerPermission.ModulesInstall)?.kind)
        // Must change the password first: not yet.
        assertNull(s.store.queuedInstallActor("ana", ServerPermission.ModulesInstall))
        s.signedIn()
        assertEquals(UUID.fromString(ana.id), s.store.queuedInstallActor("ana", ServerPermission.ModulesInstall)?.userId)
        assertNull(s.store.queuedInstallActor("ana", ServerPermission.ExtensionsInstall))
        assertNull(s.store.queuedInstallActor("nadie", ServerPermission.ModulesInstall))
        s.service.setActive(owner(), ana.id, false)
        assertNull(s.store.queuedInstallActor("ana", ServerPermission.ModulesInstall))

        // A module install queued by ana is dropped (and audited) when it resumes after she was deactivated.
        val file = java.nio.file.Files.createTempDirectory("sa-queue").resolve("pending-installs.json")
        PackageInstallQueue(file) {}.add(PackageInstallQueue.Module, "crm", "ana")
        val queue = PackageInstallQueue(file) {}
        provisioning(s.databases, queue, QueuedInstallActors(s.store::queuedInstallActor)).resumePendingInstalls()
        assertEquals(emptyList(), queue.pending())
        val dropped = auditRows("module.install_dropped").single()
        assertEquals("ana", Json.parseToJsonElement(dropped.second).jsonObject["requestedBy"]!!.jsonPrimitive.content)
    }

    @Test
    fun ownerAndAdministratorRowsCannotBeRacedIntoTheSameUser() {
        if (url == null) return
        setup()
        val id = UUID.randomUUID()
        connect(central).use { it.run("INSERT INTO application_users (id, postgres_role, is_active) VALUES ('$id', 'carrera', TRUE)") }
        connect(central).use { first ->
            first.autoCommit = false
            first.run("INSERT INTO server_admins (user_id, password_hash) VALUES ('$id', 'pbkdf2_sha256\$1\$a\$b')")
            // Before the first commits, a second transaction makes the same user an owner: it must wait and then fail.
            val second = java.util.concurrent.CompletableFuture.supplyAsync {
                runCatching { connect(central).use { it.run("INSERT INTO server_owners (user_id) VALUES ('$id')") } }
            }
            Thread.sleep(500)
            assertFalse(second.isDone, "the second insert must wait for the advisory lock")
            first.commit()
            val outcome = second.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(outcome.isFailure, "a user became owner and administrator at once")
        }
        assertEquals("0", scalar(central, "SELECT count(*) FROM server_owners WHERE user_id = '$id'"))
    }
}
