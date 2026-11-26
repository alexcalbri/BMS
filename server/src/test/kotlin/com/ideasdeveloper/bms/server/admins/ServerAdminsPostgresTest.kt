package com.ideasdeveloper.bms.server.admins

import com.ideasdeveloper.bms.server.auth.application.SessionService
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationSessionRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationUserRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.ServerOwnerAuthorizer
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
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
            (listOf(central) + companies.map { "bms_sa_$it" }).forEach { name ->
                admin.run("DROP DATABASE IF EXISTS $name WITH (FORCE)")
                admin.run("CREATE DATABASE $name")
            }
        }
        connect(central).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            File(root, "database/control/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
                .forEach { db.run(it.readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', 'sa_owner', TRUE, 'Duena', 'duena@example.com')")
            db.run("INSERT INTO server_owners (user_id) VALUES ('$ownerId')")
            companies.forEach { code -> db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), '$code', 'Empresa $code', 'bms_sa_$code', gen_random_uuid())") }
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

    private fun loginDatabases(): LoginDatabases {
        val data = source(central)
        val prefix = urlOf("X").substringBefore("/X") + "/"
        val user = Regex("user=([^&]+)").find(url!!)?.groupValues?.get(1) ?: "postgres"
        return LoginDatabases(data, Database.connect(data), urlOf(central), "[]", user, "", prefix, 3600).also { opened += it }
    }

    private fun owner() = ServerActor(ownerId, "sa_owner", ServerActorKind.Owner, emptySet(), true, emptySet(), "sa_owner")

    private class Setup(val databases: LoginDatabases, val store: ServerAdminStore, val service: ServerAdminService, val actors: ServerOwnerAuthorizer)

    private fun setup(): Setup {
        reset()
        val databases = loginDatabases()
        val data = source(central)
        val store = databases.admins
        val service = ServerAdminService(
            store = store,
            loginTaken = { login -> store.centralLoginTaken(login) || data.connection.use { c -> c.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?").use { q -> q.setString(1, login); q.executeQuery().use { it.next() } } } },
            revokeCompanySessions = { databases.revokeCompanySessionsOpenedBy(it) },
            audit = { actor, action, details -> data.connection.use { ProvisioningAudit.insert(it, actor, action, null, null, details) } },
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
}
