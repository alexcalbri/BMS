package com.ideasdeveloper.bms.server.modules

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.domain.LoginSuccessResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.application.CompanyLinks
import com.ideasdeveloper.bms.server.company.application.ServerWorkspace
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.i18n.CompanyLanguages
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.SessionLanguages
import com.ideasdeveloper.bms.server.i18n.TextTranslation
import com.ideasdeveloper.bms.server.i18n.TextTranslator
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.trIn
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * Valida acceso server-side a rutas propias de módulos.
 *
 * La visibilidad del dashboard ayuda a la experiencia, pero no autoriza nada por si sola.
 * Toda ruta de un módulo que lea o modifique datos de tenant debe exigir:
 * - Bearer token de una sesión vigente.
 * - X-Company-Code para resolver la base de datos tenant.
 * - Módulo activo para la empresa.
 * - Permiso declarado por el módulo, excepto business_owner que administra su propia empresa.
 */
class ModuleAccessService(
    private val central: DataSource,
    private val loginDatabases: LoginDatabases,
    // Server-wide SMTP configured by the server administrator; modules may allow a company override.
    val serverMail: SmtpSettings? = null,
    // Whether the server owner installed the module; routes of every compiled module exist from startup.
    private val moduleInstalled: (String) -> Boolean = { true },
    // The MCP of each installed module (ModuleRegistry.mcp).
    private val moduleMcp: () -> List<RegisteredModuleMcp> = { emptyList() },
    // The app's login (ScopedLoginService.login), for module sign-in pages such as OAuth.
    private val login: (LoginCredentials) -> LoginSuccessResponse? = { null },
    // What each installed module adds to a customer's history (ModuleRegistry.customerTimelines).
    private val customerTimelines: () -> List<RegisteredCustomerTimeline> = { emptyList() },
    // File storage of the companies (see CompanyFiles); modules reach it through files().
    private val companyFiles: CompanyFiles = CompanyFiles.local(java.nio.file.Path.of("build/company-files")),
    // Where customer history events are stored (ModuleRegistry.customerHistoryWriter).
    private val customerHistoryWriter: () -> CustomerHistoryWriter? = { null },
    // Copies customers changed by modules to the companies they are shared with (ModuleRegistry.customerChangeListener).
    private val customerChangeListener: () -> CustomerChangeListener? = { null },
    // The customer's notes for other modules (ModuleRegistry.customerNotesReader).
    private val customerNotesReader: () -> CustomerNotesReader? = { null },
    // Notification center of the Core: modules notify users through it (see NotificationService).
    val notifications: NotificationService = NotificationService(),
    // Encrypts secrets before modules store them (BMS_MASTER_KEY, see SecretBox).
    val secrets: com.ideasdeveloper.bms.server.security.SecretBox = com.ideasdeveloper.bms.server.security.MasterKeySecretBox(null),
    // Customer references of installed modules and of every known extension (see customerReferences).
    private val customerReferenceSources: () -> List<CustomerReferenceSource> = { emptyList() },
    // Registered extension id -> id of its module, to place history rows written by extensions.
    private val extensionModules: () -> Map<String, String> = { emptyMap() },
    // Display name of a module id (ModuleRegistry), for activity log rows.
    private val moduleNames: (String) -> String? = { null },
) {
    private val log = org.slf4j.LoggerFactory.getLogger(ModuleAccessService::class.java)
    private val tenantPools = TenantPools()

    /** Closes the tenant connection pools; call it when the server stops. */
    fun close() = tenantPools.close()

    /**
     * Customer references of the modules enabled in this company and of its enabled extensions, keeping only valid
     * names and tables that exist in this database (one query for all tables). Invalid ones are dropped with a log.
     */
    fun customerReferences(tenant: Connection): List<CustomerReference> = customerAccess.customerReferences(tenant)
    private val tokenGenerator = SessionTokenGenerator()
    private val agentSignIn = ModuleAgentSignIn(this, central, loginDatabases, login, moduleMcp, tokenGenerator)
    private val customerAccess = ModuleCustomerAccess(
        this, central, moduleInstalled, customerReferenceSources, customerTimelines, customerHistoryWriter, customerChangeListener,
        customerNotesReader, extensionModules,
    )

    /**
     * The notifier of [moduleId]: creates notifications that always carry that module as their origin.
     * Its `notify(tenant, userId) { tr(...) }` overload renders each notification in the recipient's language.
     */
    fun notificationsFor(moduleId: String): ModuleNotifier = ModuleNotifier(moduleId, notifications, ::recipientLanguage)

    /**
     * Translation of texts written by users (stage names, notes) into the viewer's language, with a cache
     * per company (I18N.md §8). Without a registered provider the texts come back as they are.
     */
    val textTranslation: TextTranslation by lazy {
        TextTranslation(
            openTenant = { companyCode -> tenantConnectionForCompany(companyCode) },
            companyLanguage = ::companyLanguage,
        )
    }

    /** Registers the machine translation provider of a module (the CRM); see [TextTranslation]. */
    fun registerTextTranslator(id: String, translator: TextTranslator) = textTranslation.registerTextTranslator(id, translator)

    /** Default language of the company ("es" or "en"; "es" when unknown). Cached for a few minutes. */
    fun companyLanguage(companyCode: String): String = CompanyLanguages.resolve(companyCode) {
        central.connection.use { connection -> com.ideasdeveloper.bms.server.i18n.companyLanguage(connection, companyCode) }
    }

    /**
     * Changes the default language of the company (the caller checks who may edit company data, as for
     * its name). False when the company does not exist; IllegalArgumentException for a language the
     * server does not support (see I18n.Languages).
     */
    fun setCompanyLanguage(companyCode: String, language: String): Boolean =
        noNewConnection().let { central.connection }.use { connection -> com.ideasdeveloper.bms.server.i18n.setCompanyLanguage(connection, companyCode, language) }

    /**
     * Language to render something for [userId] of the company behind [tenant]: the user's own, else the
     * company default, else "es" (I18N.md §7). For notifications, mail to staff and jobs.
     */
    fun recipientLanguage(tenant: Connection, userId: UUID): String {
        val own = try {
            com.ideasdeveloper.bms.server.i18n.userLanguage(tenant, userId)
        } catch (_: Exception) {
            null
        }
        if (own != null) return own
        val company = try {
            companyCodeOf(tenant)
        } catch (_: Exception) {
            null
        }
        return company?.let(::companyLanguage) ?: I18n.Source
    }

    // database name -> company code: a notifyEach over many recipients opens one central connection, not one each.
    private val companyCodes = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun companyCodeOf(tenant: Connection): String? {
        val database = tenant.createStatement().use { statement ->
            statement.executeQuery("SELECT current_database()").use { rows -> if (rows.next()) rows.getString(1) else null }
        } ?: return null
        companyCodes[database]?.let { return it }
        val code = companyOf(tenant)?.code ?: return null
        if (companyCodes.size > 10_000) companyCodes.clear()
        companyCodes[database] = code
        return code
    }

    // The request now knows its user: their stored language (from memory when fresh) wins over Accept-Language.
    private fun applySessionLanguage(scope: String?, userId: UUID, token: String, connection: Connection?) {
        try {
            val key = SessionLanguages.userKey(scope, userId)
            SessionLanguages.rememberSession(token, key)
            val language = SessionLanguages.resolve(key) {
                if (connection != null) com.ideasdeveloper.bms.server.i18n.userLanguage(connection, userId)
                else central.connection.use { com.ideasdeveloper.bms.server.i18n.userLanguage(it, userId) }
            }
            I18n.overrideRequestLanguage(language)
        } catch (failure: Exception) {
            log.debug("Language of user {} not read: {}", userId, failure.message)
        }
    }

    /**
     * The company activity log: `access.activity.record(connection, permissions.activityActor, entry)`
     * on the same connection and transaction as the change (ACTIVITY_LOG_MODULE_GUIDE.md).
     */
    val activity: ActivityLog get() = ActivityLog

    /** Name of [moduleId] as activity rows show it ("Empresa" for Core's own "core" rows), Spanish as stored. */
    fun activityModuleName(moduleId: String): String =
        if (moduleId == com.ideasdeveloper.bms.server.activity.CoreModuleId) "Empresa" else moduleNames(moduleId) ?: moduleId

    /**
     * The activity actor of the session [token] of [userId] in [companyCode]: a switcher session is
     * the business owner user plus the server person who opened it ("<nombre> (desde el servidor)").
     */
    fun sessionActor(companyCode: String, token: String, userId: UUID, businessOwner: Boolean): ActivityActor {
        val tenantActor = tenantConnectionForCompany(companyCode)?.use { tenantUserActor(it, userId, businessOwner) }
            ?: fallbackActor(businessOwner, false, userId)
        if (!businessOwner || token.isEmpty()) return tenantActor
        val opener = loginDatabases.companySessionOpener(companyCode, token) ?: return tenantActor
        return ActivityActor.switcher(userId, tenantActor.name, opener.kind, opener.userId, opener.name)
    }

    /** A tenant user (or business owner) of [tenant] as the activity log names them. */
    fun tenantUserActor(tenant: Connection, userId: UUID, businessOwner: Boolean): ActivityActor {
        val name = personName(tenant, userId) ?: if (businessOwner) "Business owner" else "Usuario"
        return if (businessOwner) ActivityActor.businessOwner(userId, name) else ActivityActor.user(userId, name)
    }

    /**
     * A server owner or administrator (central [userId]) acting in [companyCode]: themself in the
     * server workspace, "<nombre> (desde el servidor)" in a company (a server owner's agent there).
     */
    fun serverPersonActor(userId: UUID, companyCode: String): ActivityActor {
        val person = loginDatabases.serverPerson(userId)
        val kind = person?.kind ?: ActivityActor.Kinds.ServerOwner
        val name = person?.name ?: "Dueno del servidor"
        val actor = if (kind == ActivityActor.Kinds.ServerAdmin) ActivityActor.serverAdmin(userId, name) else ActivityActor.serverOwner(userId, name)
        return if (companyCode == ServerWorkspace.Code) actor else actor.copy(display = "$name (desde el servidor)")
    }

    private fun personName(connection: Connection, userId: UUID): String? = try {
        connection.prepareStatement("SELECT display_name, postgres_role FROM application_users WHERE id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1)?.takeIf { it.isNotBlank() } ?: rows.getString(2) else null }
        }
    } catch (_: SQLException) {
        null
    }

    /** Where [moduleId] stores the files of [companyCode]; the table of the module keeps the returned refs. */
    fun files(companyCode: String, moduleId: String): ModuleFiles = companyFiles.module(companyCode, moduleId)

    // Public module endpoints (e.g. a captive portal) must check this before serving anything.
    fun isInstalled(moduleId: String): Boolean = moduleInstalled(moduleId)

    // The MCP of every installed module; the MCP endpoint filters it per company and user.
    fun moduleMcp(): List<RegisteredModuleMcp> = moduleMcp.invoke()

    // Agent tools of every installed module.
    fun mcpTools(): List<RegisteredMcpTool> = moduleMcp().flatMap { it.tools }

    /**
     * Checks a user's password like the app's login and leaves no session behind, for module pages
     * that sign users in themselves (OAuth authorization of AI agents). Without [companyCode] it
     * signs in a server owner, who then acts in the server workspace. Null when rejected or when a
     * server owner has no server workspace, and for an account with two-step verification on (use
     * [signInForAgent], which asks for the code). Callers must rate limit attempts. [clientAddress]
     * is recorded with the sign-in and keys the per-login throttle (clientAddress(call) of the page).
     */
    fun verifyCredentials(companyCode: String?, username: String, password: String, allowAuditAdmin: Boolean = false, clientAddress: String = ""): VerifiedUser? =
        agentSignIn.verifyCredentials(companyCode, username, password, allowAuditAdmin, clientAddress)

    /** [verifyCredentials] for the request [call] (its client address is recorded). */
    fun verifyCredentials(call: ApplicationCall, companyCode: String?, username: String, password: String, allowAuditAdmin: Boolean = false): VerifiedUser? =
        agentSignIn.verifyCredentials(call, companyCode, username, password, allowAuditAdmin)

    /**
     * The sign-in of an AI agent's OAuth page with the second step (TWO_FACTOR.md §2): password and,
     * for an account with two-step verification on, [twoFactorCode] (authenticator or recovery code).
     * Without the code such an account gets [AgentSignIn.SecondFactorRequired] (ask for it and call
     * again with both); a wrong code [AgentSignIn.InvalidCode]; too many [AgentSignIn.TooManyAttempts].
     */
    fun signInForAgent(
        companyCode: String?,
        username: String,
        password: String,
        twoFactorCode: String?,
        clientAddress: String,
        allowAuditAdmin: Boolean = false,
    ): AgentSignIn = agentSignIn.signInForAgent(companyCode, username, password, twoFactorCode, clientAddress, allowAuditAdmin)

    /**
     * [signInForAgent] for the request [call]: its client address (X-Real-IP behind the local Nginx, the same rule
     * as the app's sign-in) is recorded with the sign-in, keys the per-login throttle and binds the second step.
     */
    fun signInForAgent(
        call: ApplicationCall,
        companyCode: String?,
        username: String,
        password: String,
        twoFactorCode: String?,
        allowAuditAdmin: Boolean = false,
    ): AgentSignIn = agentSignIn.signInForAgent(call, companyCode, username, password, twoFactorCode, allowAuditAdmin)

    /**
     * What the AI agent of server administrator [userId] may use now (ACTIVITY_LOG.md §7): only the
     * two audit read tools of the server MCP (list_server_audit, list_company_activity), never any
     * other server tool, module tool, company or serverOwner flag. Null unless the user is an active
     * administrator (not the owner) holding server.audit.view without a pending password change.
     * Read live: call it on every request and every tool call.
     */
    fun serverAuditAgent(userId: UUID): ServerAuditAgent? = agentSignIn.serverAuditAgent(userId)

    /** Audits an event of a server administrator's agent (connected, revoked, refused) in provisioning_audit_log. */
    fun auditServerAdminAgent(userId: UUID, login: String, action: String, details: Map<String, Any?>) =
        agentSignIn.auditServerAdminAgent(userId, login, action, details)

    /**
     * Module access of a user for requests authenticated without a session, such as an AI agent token
     * stored in the tenant. Same rules as [permissions]: an inactive user (or a server owner that no
     * longer is one) gets null, and permissions are read now, so revoking one applies to the next call.
     */
    fun userAccess(tenant: Connection, companyCode: String, userId: UUID, serverOwner: Boolean): UserModuleAccess? {
        val enabled = enabledModules(tenant)
        if (serverOwner) {
            if (companyCode != ServerWorkspace.Code || !isServerWorkspace(companyCode) || !isActiveServerOwner(userId)) return null
            return UserModuleAccess(companyCode, userId, businessOwner = true, serverOwner = true, granted = emptySet(), enabled, actorResolver = { serverPersonActor(userId, companyCode) }, installed = moduleInstalled)
        }
        val businessOwner = tenant.prepareStatement(
            """
            SELECT EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id)
            FROM application_users users
            WHERE users.id = ? AND users.is_active = TRUE
            """.trimIndent()
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getBoolean(1) else null }
        } ?: return null
        val granted = if (businessOwner) emptySet() else tenant.prepareStatement(
            "SELECT permission_id FROM application_user_permissions WHERE user_id = ?"
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        return UserModuleAccess(companyCode, userId, businessOwner, serverOwner = false, granted, enabled, actorResolver = { tenantUserActor(tenant, userId, businessOwner) }, installed = moduleInstalled)
    }

    /**
     * A server owner acting in any active company of the server, with every permission there, like
     * in the server workspace (their AI agent manages the companies of the server). Null when the user
     * is no longer an active server owner or the company is not active. The caller closes the connection.
     */
    fun serverOwnerAccess(companyCode: String, userId: UUID): Pair<Connection, UserModuleAccess>? {
        if (!isActiveServerOwner(userId)) return null
        val tenant = tenantConnectionForCompany(companyCode) ?: return null
        val enabled = try {
            enabledModules(tenant)
        } catch (failure: SQLException) {
            tenant.close()
            throw failure
        }
        return tenant to UserModuleAccess(companyCode, userId, businessOwner = true, serverOwner = true, granted = emptySet(), enabled, actorResolver = { serverPersonActor(userId, companyCode) }, installed = moduleInstalled)
    }

    /**
     * Companies where the AI agent of the business owner of [companyCode] may act: that company and
     * the others of its owner group, see [OwnerGroupScope.companies]. Only [companyCode] for a company
     * in no group; empty when it is not active, is suspended in its group or is the server workspace.
     */
    fun ownerGroupCompanies(companyCode: String): List<GroupCompany> =
        central.connection.use { connection -> OwnerGroupScope.companies(connection, companyCode) }

    /**
     * The business owner of [fromCode] acting in [toCode], another company of the same owner group,
     * as the business owner of [toCode]: each company may have its own user, so being the same person
     * means the same owner group and the same business owner email in both, read now. [fromTenant] and [fromUserId] are
     * the caller's own company and user; that user must be its active business owner now. Null when
     * any rule fails, without saying which, so the answer never tells whether a company exists.
     * The caller closes the connection.
     */
    fun groupOwnerAccess(fromTenant: Connection, fromCode: String, fromUserId: UUID, toCode: String): Pair<Connection, UserModuleAccess>? {
        if (fromCode == toCode || !CompanyCodePattern.matches(toCode) || !CompanyCodePattern.matches(fromCode)) return null
        val fromOwner = OwnerGroupScope.activeBusinessOwner(fromTenant)?.takeIf { it.id == fromUserId } ?: return null
        if (ownerGroupCompanies(fromCode).none { it.code == toCode }) return null
        val tenant = tenantConnectionForCompany(toCode) ?: return null
        val granted = try {
            // The group alone is not enough: both companies must have the same business owner email now,
            // so a company linked by mistake or whose owner changed is out without waiting for anyone to suspend it.
            OwnerGroupScope.activeBusinessOwner(tenant)
                ?.takeIf { OwnerGroupScope.sameOwner(fromOwner, it) }
                ?.let { owner -> userAccess(tenant, toCode, owner.id, serverOwner = false) }
                ?.takeIf { it.businessOwner }
        } catch (failure: Exception) {
            tenant.close()
            throw failure
        }
        if (granted == null) {
            tenant.close()
            return null
        }
        return tenant to granted
    }

    internal fun enabledModules(tenant: Connection): Set<String> =
        tenant.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled'").use { query ->
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }

    /**
     * Name and email of the user behind [permissions], to record who made a change: a server owner
     * from the central database, anyone else from [tenant]. Users created before names were required
     * may lack them until they sign in again; the name then falls back to the username.
     */
    fun userIdentity(tenant: Connection, permissions: ModulePermissions): UserIdentity? {
        val userId = permissions.userId ?: return null
        fun read(connection: Connection) = connection.prepareStatement(
            "SELECT postgres_role, display_name, email FROM application_users WHERE id = ?"
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows ->
                if (rows.next()) UserIdentity(userId, rows.getString(2)?.takeIf { it.isNotBlank() } ?: rows.getString(1), rows.getString(3), rows.getString(1)) else null
            }
        }
        return try {
            if (permissions.serverOwner) central.connection.use(::read) else read(tenant)
        } catch (_: SQLException) {
            // Before core migration V003 the profile columns do not exist yet.
            null
        }
    }

    /**
     * A customer's history from every installed module that is enabled for the company and whose
     * permission the user holds, newest first. A module that fails is left out (and logged), so one
     * module never hides the others' history.
     */
    fun customerHistory(tenant: Connection, permissions: ModulePermissions, customerId: UUID, limit: Int = 100): List<CustomerHistoryEntry> =
        customerAccess.customerHistory(tenant, permissions, customerId, limit)

    /**
     * Writes [event] in the customer's history of the company of [tenant] (the company's tab of the
     * customer in Clientes). Does nothing and returns false when no installed module stores customer
     * history or the company does not use it. Never breaks the caller: inside a transaction the write
     * runs in a savepoint, and a failure is logged and rolled back to it.
     */
    fun recordCustomerEvent(tenant: Connection, event: CustomerHistoryEvent): Boolean = customerAccess.recordCustomerEvent(tenant, event)

    /**
     * The customer's notes (tastes, preferences, general information), newest first, to show them
     * where the customer is attended (the CRM lead). Null when no installed module keeps customer
     * notes or they cannot be read; hide the notes then.
     */
    fun customerNotes(tenant: Connection, customerId: UUID, limit: Int = 5): List<CustomerNote>? = customerAccess.customerNotes(tenant, customerId, limit)

    /**
     * Call after changing a customer's data (name, email, phone, custom fields) in [tenant]: the
     * customer is one record for the companies it is shared with, so the change is copied to them now.
     * Call it once the change is committed. Never throws; a failure is logged.
     */
    fun customerChanged(tenant: Connection, customerId: UUID) = customerAccess.customerChanged(tenant, customerId)

    /**
     * The active companies of the business owner of [companyCode] (its owner group), itself included,
     * by name. Only the company itself for the server workspace or a company in no group.
     */
    fun groupCompanies(companyCode: String): List<GroupCompany> = customerAccess.groupCompanies(companyCode)

    /**
     * Companies linked with [companyCode] for [purpose], it first and then by name: active, of the same
     * owner group as it (checked on every read), in the same link group, never the server workspace.
     * Only it when it is not linked.
     */
    fun linkedCompanies(companyCode: String, purpose: String): List<GroupCompany> = customerAccess.linkedCompanies(companyCode, purpose)

    /**
     * Links [targetCode] to the group of [companyCode] (and [companyCode] too when it was not). One
     * transaction that locks the owner group's companies: both active, different, none the server
     * workspace and the same owner group, else [CompanyLinkException]. The caller checks the business owner.
     */
    fun linkCompany(companyCode: String, targetCode: String, purpose: String, linkedBy: String): List<GroupCompany> =
        customerAccess.linkCompany(companyCode, targetCode, purpose, linkedBy)

    /**
     * Removes [targetCode] (it may be [companyCode]) from the group of [companyCode]; the same owner
     * group is required. A company left alone is removed too. Returns what stays linked on the side of
     * [companyCode] (only it when it no longer is).
     */
    fun unlinkCompany(companyCode: String, targetCode: String, purpose: String): List<GroupCompany> =
        customerAccess.unlinkCompany(companyCode, targetCode, purpose)

    /**
     * Whether the user of [permissions] may see in the customer's file the history rows written by
     * [moduleId]. See [visibleCustomerEventModules]; this opens a query per call, so for many rows use that.
     */
    fun customerEventsVisible(tenant: Connection, permissions: ModulePermissions, moduleId: String): Boolean =
        customerAccess.customerEventsVisible(tenant, permissions, moduleId)

    /**
     * Of [moduleIds], the modules whose customer history rows the user may see: Clientes always; an
     * installed module only when enabled in [tenant] and the user is business owner or server owner, or
     * holds the permission of its customer timeline (without a timeline, any permission of the module,
     * whose ids start with "moduleId."); a registered extension id is visible when its module is enabled in
     * [tenant]; any other id (an uninstalled or unknown module) only to business owner or server owner.
     * Reads the enabled modules once for the whole set.
     */
    fun visibleCustomerEventModules(tenant: Connection, permissions: ModulePermissions, moduleIds: Set<String>): Set<String> =
        customerAccess.visibleCustomerEventModules(tenant, permissions, moduleIds)

    /** The company whose database [tenant] is connected to, or null for one the server does not know. */
    fun companyOf(tenant: Connection): GroupCompany? = customerAccess.companyOf(tenant)

    // Central control-plane connection for module-owned central tables such as public_domains.
    fun centralConnection(): Connection {
        noNewConnection()
        return central.connection
    }

    /**
     * Runs [block] (a confirmation preview of the in-app assistant) with no way to open another connection or write to
     * the control database through this service: a preview works on the one read-only connection it was given. Only
     * for the calling thread; scopes nest.
     */
    fun <T> withoutNewConnections(block: () -> T): T {
        val before = readOnlyScope.get()
        readOnlyScope.set(true)
        try {
            return block()
        } finally {
            if (before == null) readOnlyScope.remove() else readOnlyScope.set(before)
        }
    }

    internal fun noNewConnection() {
        check(readOnlyScope.get() != true) { "Una vista previa no puede abrir otra conexion ni escribir." }
    }

    private val readOnlyScope = ThreadLocal<Boolean>()

    // Web module pages authenticate with cookies scoped to /modules/{moduleId}/ instead of headers.
    fun companyCode(call: ApplicationCall): String? = (call.request.header("X-Company-Code") ?: call.request.cookies[CompanyCookie])
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun accessToken(call: ApplicationCall): String? = (
        call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?: call.request.cookies[TokenCookie]
        )
        ?.trim()
        // Un token con otro formato no puede ser una sesion: se rechaza (401) antes de abrir
        // cualquier conexion a PostgreSQL, para que un Bearer basura no agote conexiones.
        ?.takeIf { SessionTokenPattern.matches(it) }

    /**
     * Opens an active company's tenant database without a user session.
     *
     * Only for public module endpoints (for example a captive portal) that resolved the
     * company from server-side data such as a registered domain, never from client input.
     */
    /**
     * Pooled runtime connection to a company database by its name, active or not (the server's reads of the
     * companies' activity logs); null when the name is not a configured or valid tenant database.
     */
    fun tenantConnectionForDatabase(databaseName: String): Connection? {
        noNewConnection()
        val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return null
        return tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password)
    }

    /**
     * A connection of its own to the company database, NOT from the pool: the caller closes it and it is gone (physically),
     * so nothing it did to its session can reach the next borrower. For code that must run on a connection it may leave in
     * any state, such as the confirmation previews of the in-app assistant.
     */
    fun dedicatedTenantConnectionForCompany(companyCode: String): Connection? {
        noNewConnection()
        val databaseName = findActiveDatabase(companyCode) ?: return null
        val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return null
        val connection = java.sql.DriverManager.getConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password)
        try {
            connection.createStatement().use { it.execute("SET search_path TO public") }
        } catch (failure: Exception) {
            runCatching { connection.close() }
            throw failure
        }
        return connection
    }

    fun tenantConnectionForCompany(companyCode: String): Connection? {
        noNewConnection()
        val databaseName = findActiveDatabase(companyCode) ?: return null
        val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return null
        return tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password)
    }

    /**
     * Tenant database of a company for a public webhook route (/modules/{moduleId}/hooks/{company}/...),
     * or null when the company is not active or does not use the installed module. The company comes
     * from the URL, so the module must authenticate the request (for example a signature checked
     * with a secret stored in this tenant) before trusting or storing anything.
     */
    fun tenantConnectionForWebhook(companyCode: String, moduleId: String): Connection? {
        if (!isInstalled(moduleId) || !CompanyCodePattern.matches(companyCode)) return null
        val connection = tenantConnectionForCompany(companyCode) ?: return null
        val enabled = try {
            moduleEnabled(connection, moduleId)
        } catch (failure: SQLException) {
            connection.close()
            throw failure
        }
        if (!enabled) {
            connection.close()
            return null
        }
        return connection
    }

    // Codes of the active companies (the server workspace included); used by the Core's own jobs.
    fun activeCompanies(): List<String> = central.connection.use { connection ->
        connection.prepareStatement("SELECT code FROM companies WHERE is_active = TRUE ORDER BY code").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    /**
     * Validates the session and company of a request that belongs to the Core, not to a module (the
     * notification center): who the user is, with no module permission involved.
     */
    /** Who really opened the company session [accessToken] of [companyCode] (switchers), or null for the account's own session. */
    fun sessionOpener(companyCode: String, accessToken: String): com.ideasdeveloper.bms.server.auth.infrastructure.database.SessionOpener? =
        loginDatabases.sessionOpener(companyCode, accessToken)

    /** The person behind the session of [permissions] (switchers), or null for the account's own session or an agent. */
    fun sessionOpener(permissions: ModulePermissions): com.ideasdeveloper.bms.server.auth.infrastructure.database.SessionOpener? =
        permissions.accessToken.takeIf { it.isNotBlank() }?.let { loginDatabases.sessionOpener(permissions.companyCode, it) }

    /** Whether [opener] could still enter [companyCode] as the switcher allows it (re-checked on every use of what it opened). */
    fun openerMayEnter(companyCode: String, opener: com.ideasdeveloper.bms.server.auth.infrastructure.database.SessionOpener): Boolean =
        loginDatabases.openerMayEnter(companyCode, opener)

    /** Whether the call carries a valid session: a company one (X-Company-Code) or a server one (none). */
    fun hasSession(call: ApplicationCall): Boolean {
        val token = accessToken(call) ?: return false
        return try {
            loginDatabases.sessionAccount(companyCode(call), token) != null
        } catch (_: SQLException) {
            false
        }
    }

    fun userSession(call: ApplicationCall): UserSessionLookup {
        val token = accessToken(call)
            ?: return UserSessionLookup.Denied(ModuleAccessDecision.Unauthorized("missing_token", tr("La sesion es requerida.")))
        val companyCode = companyCode(call)
            ?: return UserSessionLookup.Denied(ModuleAccessDecision.Unauthorized("missing_company", tr("La empresa es requerida.")))
        val databaseName = findActiveDatabase(companyCode)
            ?: return UserSessionLookup.Denied(ModuleAccessDecision.Forbidden("company_not_found", tr("La empresa no esta activa.")))
        val tenantConfig = loginDatabases.tenantConfig(databaseName)
            ?: return UserSessionLookup.Denied(ModuleAccessDecision.Forbidden("tenant_not_configured", tr("La base de datos de la empresa no esta configurada.")))
        // Server owners hold a central session and act in the server workspace.
        if (companyCode == ServerWorkspace.Code && isServerWorkspace(companyCode)) {
            serverOwnerSessionUser(token)?.let {
                applySessionLanguage(null, it, token, null)
                return UserSessionLookup.Granted(companyCode, it)
            }
        }
        val userId = tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password).use { connection ->
            connection.prepareStatement(
                """
                SELECT users.id
                FROM application_sessions sessions
                JOIN application_users users ON users.id = sessions.user_id
                WHERE sessions.token_hash = ?
                  AND sessions.revoked_at IS NULL
                  AND sessions.expires_at > CURRENT_TIMESTAMP
                  AND users.is_active = TRUE
                """.trimIndent()
            ).use { query ->
                query.setString(1, tokenGenerator.hash(token))
                query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
            }?.also { applySessionLanguage(companyCode, it, token, connection) }
        }?.takeIf { loginDatabases.companySessionAllowed(companyCode, token) }
            ?: return UserSessionLookup.Denied(ModuleAccessDecision.Unauthorized("invalid_session", tr("La sesion no es valida.")))
        return UserSessionLookup.Granted(companyCode, userId)
    }

    /**
     * Who may read the company activity log of this request (GET /companies/me/activity): its
     * business owner, a user holding empresa.activity.view, or the server owner in the server
     * workspace. Same session rules as [userSession] (switcher sessions re-checked).
     */
    fun activityReader(call: ApplicationCall): ActivityReaderLookup {
        val (companyCode, userId) = when (val lookup = userSession(call)) {
            is UserSessionLookup.Denied -> return ActivityReaderLookup.Denied(lookup.decision)
            is UserSessionLookup.Granted -> lookup.companyCode to lookup.userId
        }
        val token = accessToken(call).orEmpty()
        if (companyCode == ServerWorkspace.Code && isActiveServerOwner(userId)) {
            return ActivityReaderLookup.Granted(companyCode, userId) { serverPersonActor(userId, companyCode) }
        }
        val (businessOwner, permitted) = tenantConnectionForCompany(companyCode)?.use { tenant ->
            tenant.prepareStatement(
                """
                SELECT EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id),
                       EXISTS (SELECT 1 FROM application_user_permissions granted WHERE granted.user_id = users.id AND granted.permission_id = ?)
                FROM application_users users WHERE users.id = ? AND users.is_active
                """.trimIndent()
            ).use { query ->
                query.setString(1, com.ideasdeveloper.bms.ActivityPermissionIds.View)
                query.setObject(2, userId)
                query.executeQuery().use { rows -> if (rows.next()) rows.getBoolean(1) to rows.getBoolean(2) else false to false }
            }
        } ?: (false to false)
        if (!businessOwner && !permitted) {
            return ActivityReaderLookup.Denied(
                ModuleAccessDecision.Forbidden("ACTIVITY_PERMISSION_REQUIRED", tr("Necesitas el permiso para ver el registro de actividad.")),
            )
        }
        return ActivityReaderLookup.Granted(companyCode, userId) { sessionActor(companyCode, token, userId, businessOwner) }
    }

    // Active companies (the server workspace included) that have [moduleId] enabled; used by module jobs.
    fun companiesWithModule(moduleId: String): List<String> {
        if (!isInstalled(moduleId)) return emptyList()
        val companies = central.connection.use { connection ->
            connection.prepareStatement("SELECT code, database_name FROM companies WHERE is_active = TRUE ORDER BY code").use { query ->
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
            }
        }
        return companies.filter { (_, databaseName) ->
            val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return@filter false
            runCatching {
                tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password).use { moduleEnabled(it, moduleId) }
            }.getOrDefault(false)
        }.map { it.first }
    }

    fun authorize(call: ApplicationCall, moduleId: String, permissionId: String): ModuleAccessDecision =
        when (val lookup = permissions(call, moduleId)) {
            is ModulePermissionLookup.Granted -> if (lookup.permissions.has(permissionId)) {
                ModuleAccessDecision.Allowed
            } else {
                ModuleAccessDecision.Forbidden("missing_permission", tr("No tienes permiso para usar esta funcion."))
            }
            is ModulePermissionLookup.Denied -> lookup.decision
        }

    /**
     * Validates session, company and module state once and returns every permission the user holds
     * in the module, so a screen can show only the sections the user may use.
     */
    fun permissions(call: ApplicationCall, moduleId: String): ModulePermissionLookup {
        if (!moduleInstalled(moduleId)) {
            return ModulePermissionLookup.Denied(ModuleAccessDecision.Forbidden("module_not_installed", tr("El modulo no esta instalado en el servidor.")))
        }
        val token = accessToken(call)
            ?: return ModulePermissionLookup.Denied(ModuleAccessDecision.Unauthorized("missing_token", tr("La sesion es requerida.")))
        val companyCode = companyCode(call)
            ?: return ModulePermissionLookup.Denied(ModuleAccessDecision.Unauthorized("missing_company", tr("La empresa es requerida.")))

        val databaseName = findActiveDatabase(companyCode)
            ?: return ModulePermissionLookup.Denied(ModuleAccessDecision.Forbidden("company_not_found", tr("La empresa no esta activa.")))
        val tenantConfig = loginDatabases.tenantConfig(databaseName)
            ?: return ModulePermissionLookup.Denied(ModuleAccessDecision.Forbidden("tenant_not_configured", tr("La base de datos de la empresa no esta configurada.")))

        // Server owners sign in centrally and hold every permission in the server workspace; its
        // staff use the tenant sessions and permissions below, like a company's users.
        val serverOwnerId = if (companyCode == ServerWorkspace.Code && isServerWorkspace(companyCode)) serverOwnerSessionUser(token) else null
        if (serverOwnerId != null) {
            applySessionLanguage(null, serverOwnerId, token, null)
            return tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password).use { connection ->
                if (!moduleEnabled(connection, moduleId)) {
                    ModulePermissionLookup.Denied(ModuleAccessDecision.Forbidden("module_disabled", tr("El modulo no esta activo en el espacio del servidor.")))
                } else {
                    ModulePermissionLookup.Granted(
                        ModulePermissions(
                            companyCode, token, businessOwner = true, granted = emptySet(), userId = serverOwnerId, serverOwner = true,
                            actorResolver = { serverPersonActor(serverOwnerId, companyCode) },
                        )
                    )
                }
            }
        }

        return tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password).use { connection ->
            val user = connection.prepareStatement(
                """
                SELECT users.id,
                       EXISTS (SELECT 1 FROM business_owner owner WHERE owner.user_id = users.id) AS business_owner
                FROM application_sessions sessions
                JOIN application_users users ON users.id = sessions.user_id
                WHERE sessions.token_hash = ?
                  AND sessions.revoked_at IS NULL
                  AND sessions.expires_at > CURRENT_TIMESTAMP
                  AND users.is_active = TRUE
                """.trimIndent()
            ).use { query ->
                query.setString(1, tokenGenerator.hash(token))
                query.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        ModuleUser(
                            id = rows.getObject("id", UUID::class.java),
                            businessOwner = rows.getBoolean("business_owner"),
                        )
                    }
                }
            } ?: return ModulePermissionLookup.Denied(ModuleAccessDecision.Unauthorized("invalid_session", tr("La sesion no es valida.")))
            // A business owner session opened from the switcher is re-checked on every use.
            if (user.businessOwner && !loginDatabases.companySessionAllowed(companyCode, token)) {
                return ModulePermissionLookup.Denied(ModuleAccessDecision.Unauthorized("invalid_session", tr("La sesion no es valida.")))
            }
            applySessionLanguage(companyCode, user.id, token, connection)

            if (!moduleEnabled(connection, moduleId)) {
                return ModulePermissionLookup.Denied(ModuleAccessDecision.Forbidden("module_disabled", tr("El modulo no esta activo para esta empresa.")))
            }

            val granted = if (user.businessOwner) emptySet() else connection.prepareStatement(
                "SELECT permission_id FROM application_user_permissions WHERE user_id = ?"
            ).use { query ->
                query.setObject(1, user.id)
                query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
            ModulePermissionLookup.Granted(
                ModulePermissions(
                    companyCode, token, user.businessOwner, granted, userId = user.id,
                    actorResolver = { sessionActor(companyCode, token, user.id, user.businessOwner) },
                )
            )
        }
    }

    fun tenantConnection(call: ApplicationCall): Connection? {
        noNewConnection()
        val companyCode = companyCode(call) ?: return null
        val databaseName = findActiveDatabase(companyCode) ?: return null
        val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return null
        return tenantConnection(tenantConfig.jdbcUrl, tenantConfig.user, tenantConfig.password)
    }

    companion object {
        const val TokenCookie = "bms_module_token"
        const val CompanyCookie = "bms_module_company"
        // Formato de un token de sesion de la app: 32 bytes en base64url sin relleno (43 caracteres).
        private val SessionTokenPattern = Regex("^[A-Za-z0-9_-]{43}$")
        // Company codes as created by provisioning; anything else in a webhook URL is rejected early.
        private val CompanyCodePattern = Regex("[a-z][a-z0-9_]{0,62}")
    }

    private fun isServerWorkspace(companyCode: String): Boolean = try {
        central.connection.use { connection ->
            connection.prepareStatement("SELECT is_server_workspace FROM companies WHERE code = ?").use { query ->
                query.setString(1, companyCode)
                query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
        }
    } catch (_: SQLException) {
        false
    }

    internal fun isActiveServerOwner(userId: UUID): Boolean = central.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT 1
            FROM server_owners owners
            JOIN application_users users ON users.id = owners.user_id
            WHERE owners.user_id = ? AND users.is_active = TRUE
            """.trimIndent()
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> rows.next() }
        }
    }

    // Central user id of an active server_owner session, or null.
    private fun serverOwnerSessionUser(token: String): UUID? = central.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT sessions.user_id
            FROM application_sessions sessions
            JOIN server_owners owners ON owners.user_id = sessions.user_id
            JOIN application_users users ON users.id = sessions.user_id
            WHERE sessions.token_hash = ?
              AND sessions.revoked_at IS NULL
              AND sessions.expires_at > CURRENT_TIMESTAMP
              AND users.is_active = TRUE
            """.trimIndent()
        ).use { query ->
            query.setString(1, tokenGenerator.hash(token))
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }
    }

    private fun findActiveDatabase(companyCode: String): String? = central.connection.use { connection ->
        connection.prepareStatement("SELECT database_name FROM companies WHERE code = ? AND is_active = TRUE").use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    // Pooled: closing the returned connection gives it back to the pool of that company database.
    private fun tenantConnection(jdbcUrl: String, username: String, password: String): Connection =
        tenantPools.connection(jdbcUrl, username, password)

    private fun moduleEnabled(connection: Connection, moduleId: String): Boolean = connection.prepareStatement(
        "SELECT 1 FROM tenant_modules WHERE module_id = ? AND status = 'enabled'"
    ).use { query ->
        query.setString(1, moduleId)
        query.executeQuery().use { rows -> rows.next() }
    }
}

// Clientes owns the customers table and always shows its own history rows.
internal const val CustomersModuleId = "clientes"

// Base modules that every company has without a tenant_modules row (company administration; its agent tools).
internal val AlwaysEnabledModules = setOf("empresa")

// Before Core resolves anyone (tests building permissions by hand): the kind and id, no name.
internal fun fallbackActor(businessOwner: Boolean, serverOwner: Boolean, userId: UUID?): ActivityActor = when {
    serverOwner -> ActivityActor.serverOwner(userId, "Dueno del servidor")
    businessOwner -> ActivityActor.businessOwner(userId ?: UUID(0, 0), "Business owner")
    else -> ActivityActor.user(userId ?: UUID(0, 0), "Usuario")
}

/**
 * Like [requireModulePermission] but returns all the user's permissions in the module, or
 * responds 401/403 and returns null. Use it for screens that adapt to what the user may do.
 */
suspend fun ApplicationCall.requireModuleAccess(access: ModuleAccessService, moduleId: String): ModulePermissions? {
    val lookup = try {
        withContext(Dispatchers.IO) { access.permissions(this@requireModuleAccess, moduleId) }
    } catch (_: SQLException) {
        ModulePermissionLookup.Denied(ModuleAccessDecision.Forbidden("tenant_access_error", tr("No se pudo validar el acceso a la empresa.")))
    }
    return when (lookup) {
        is ModulePermissionLookup.Granted -> lookup.permissions
        is ModulePermissionLookup.Denied -> {
            when (val decision = lookup.decision) {
                is ModuleAccessDecision.Unauthorized -> respond(HttpStatusCode.Unauthorized, ModuleAccessErrorResponse(decision.code, decision.message))
                is ModuleAccessDecision.Forbidden -> respond(HttpStatusCode.Forbidden, ModuleAccessErrorResponse(decision.code, decision.message))
                ModuleAccessDecision.Allowed -> Unit
            }
            null
        }
    }
}

/**
 * Guard reutilizable para nuevas vistas/rutas de módulos.
 *
 * Cada endpoint debe llamar a este helper con el permiso mas especifico posible
 * antes de responder datos o ejecutar acciones del módulo.
 */
suspend fun ApplicationCall.requireModulePermission(
    access: ModuleAccessService,
    moduleId: String,
    permissionId: String,
): Boolean {
    val decision = try {
        withContext(Dispatchers.IO) { access.authorize(this@requireModulePermission, moduleId, permissionId) }
    } catch (_: SQLException) {
        ModuleAccessDecision.Forbidden("tenant_access_error", tr("No se pudo validar el acceso a la empresa."))
    }

    return when (decision) {
        ModuleAccessDecision.Allowed -> true
        is ModuleAccessDecision.Unauthorized -> {
            respond(HttpStatusCode.Unauthorized, ModuleAccessErrorResponse(decision.code, decision.message))
            false
        }
        is ModuleAccessDecision.Forbidden -> {
            respond(HttpStatusCode.Forbidden, ModuleAccessErrorResponse(decision.code, decision.message))
            false
        }
    }
}

/**
 * Pure decision of [ModuleAccessService.visibleCustomerEventModules] for one history source id: Clientes always;
 * an installed module when enabled and the user is admin or holds its permission; a registered extension
 * ([extensionModule] is its module) when that module is enabled and the user is admin or holds that module's permission; anything else only for [admin].
 */
fun customerEventVisibility(
    id: String,
    installed: Boolean,
    extensionModule: String?,
    enabledModules: Set<String>,
    admin: Boolean,
    hasModulePermission: (moduleId: String) -> Boolean,
): Boolean = when {
    id == "clientes" -> true
    installed -> id in enabledModules && (admin || hasModulePermission(id))
    extensionModule != null -> extensionModule in enabledModules && (admin || hasModulePermission(extensionModule))
    else -> admin
}
