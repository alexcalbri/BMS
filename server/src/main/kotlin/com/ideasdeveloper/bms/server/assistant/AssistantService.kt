package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.AssistantIds
import com.ideasdeveloper.bms.AssistantStatusResponse
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerActorKind
import com.ideasdeveloper.bms.server.admins.ServerActorLookup
import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.admins.ServerPermission
import com.ideasdeveloper.bms.server.auth.infrastructure.database.SessionOpener
import com.ideasdeveloper.bms.server.company.application.ServerWorkspace
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.security.SecretBox
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher

/** Why the assistant is not available, with the text the app shows. */
class AssistantUnavailable(val reason: String, val message: String)

/** Everything a request decided about the caller's company: settings, key source and limit. */
class AssistantAvailability(
    val visible: Boolean,
    val unavailable: AssistantUnavailable?,
    val server: AssistantServerSettings,
    val company: AssistantCompanySettings,
    val allowance: AssistantAllowance,
    val keySource: String?,
    val monthlyLimit: Long?,
    val limitedSources: Set<String>,
    val monthUsed: Long,
)

/**
 * One request of the assistant: the caller, the session actor and the store of the place (a company or the server
 * workspace). [mayUse] and [mayManage] are what the person may do NOW (decided from the session, never from the request):
 * use the assistant, and change its switch here. [serverPerson] is the server owner or administrator behind the session
 * (their own session in the workspace, or the one who opened a switcher session), read live for this request.
 */
class AssistantContext(
    val caller: AssistantCaller,
    val hasUsePermission: Boolean,
    private val actorResolver: () -> ActivityActor,
    val store: AssistantStore,
    val mayUse: Boolean = caller.businessOwner || hasUsePermission,
    val mayManage: Boolean = caller.businessOwner,
    val serverPerson: ServerActor? = null,
    val workspace: Boolean = false,
) {
    val actor: ActivityActor by lazy(actorResolver)
}

/**
 * Requests and streams (GLOBAL_AGENT.md, Contract, "Limits"): per user requests per minute and new conversations per
 * minute; streams at once per user, per company and on the whole server. In memory: a restart forgets the windows,
 * which only ever lets a few more requests through; streams in progress end with the process.
 */
class AssistantUserLimits(
    private val perMinute: Int = 10,
    private val streamsPerUser: Int = 2,
    private val streamsPerCompany: Int = 8,
    val streamsOnServer: Int = 32,
    private val conversationsPerMinute: Int = 20,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Window(var start: Long, var count: Int)
    private val windows = ConcurrentHashMap<String, Window>()
    private val users = ConcurrentHashMap<String, AtomicInteger>()
    private val companies = ConcurrentHashMap<String, AtomicInteger>()
    private val server = AtomicInteger()

    private fun window(key: String, max: Int): Boolean {
        val now = clock()
        if (windows.size > 20_000) windows.entries.removeIf { now - it.value.start >= 60_000 }
        val window = windows.computeIfAbsent(key) { Window(now, 0) }
        synchronized(window) {
            if (now - window.start >= 60_000) {
                window.start = now
                window.count = 0
            }
            window.count++
            return window.count <= max
        }
    }

    fun allow(key: String): Boolean = window(key, perMinute)

    fun allowNewConversation(key: String): Boolean = window("new/$key", conversationsPerMinute)

    /** Why a stream cannot open now (a refusal to answer before streaming), or null when its slot was taken. */
    fun openStream(user: String, company: String): AssistantRefusal? {
        if (!take(server, streamsOnServer)) {
            return AssistantRefusal(503, "ASSISTANT_BUSY", tr("El asistente esta atendiendo demasiadas conversaciones en este servidor; intenta de nuevo en un momento."))
        }
        val companyCount = companies.computeIfAbsent(company) { AtomicInteger() }
        if (!take(companyCount, streamsPerCompany)) {
            server.decrementAndGet()
            return AssistantRefusal(429, "ASSISTANT_RATE_LIMITED", tr("La empresa tiene demasiadas respuestas del asistente en curso; espera a que terminen."))
        }
        val userCount = users.computeIfAbsent(user) { AtomicInteger() }
        if (!take(userCount, streamsPerUser)) {
            companyCount.decrementAndGet()
            server.decrementAndGet()
            return AssistantRefusal(429, "ASSISTANT_RATE_LIMITED", tr("Ya tienes otras respuestas del asistente en curso; espera a que terminen."))
        }
        return null
    }

    fun release(user: String, company: String) {
        users[user]?.let { if (it.decrementAndGet() <= 0) users.remove(user, it) }
        companies[company]?.let { if (it.decrementAndGet() <= 0) companies.remove(company, it) }
        server.decrementAndGet()
    }

    val openStreams: Int get() = server.get()

    private fun take(counter: AtomicInteger, max: Int): Boolean {
        while (true) {
            val current = counter.get()
            if (current >= max) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }
}

/**
 * The assistant's company side: who calls (from the session), whether the assistant is available to them, the runtime
 * settings of a request (key and limit) and the company's store and engine. The routes stay thin on top of it.
 */
class AssistantService(
    val access: ModuleAccessService,
    val serverConfig: AssistantServerConfig,
    private val secrets: SecretBox,
    private val models: AssistantModelClients,
    val limits: AssistantUserLimits = AssistantUserLimits(),
    private val clock: () -> Instant = Instant::now,
    private val turnLimits: AssistantTurnLimits = AssistantTurnLimits(),
    // Host names of the server itself (BMS_PUBLIC_BASE_URL): links there are not foreign.
    private val serverHosts: Set<String> = emptySet(),
    // Who the server owner or administrator behind a central session token is, read now. Null: the server workspace and
    // the server people's switcher sessions are out (every one of them is refused), as in Core 0.14.0.
    private val serverActors: ServerActorResolver? = null,
    // The rights of a central user now (who opened a switcher session, or the person of a workspace turn).
    private val liveServerActor: ((UUID) -> ServerActorLookup)? = null,
) {
    private val log = LoggerFactory.getLogger(AssistantService::class.java)

    /**
     * Where turns run: a pool of its own, as large as the server-wide stream limit, so a busy assistant never takes the
     * threads of the rest of the server (Dispatchers.IO) and never waits for them.
     */
    val dispatcher: kotlinx.coroutines.CoroutineDispatcher = java.util.concurrent.Executors.newFixedThreadPool(limits.streamsOnServer) { task ->
        Thread(task, "assistant-turn").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    fun close() = (dispatcher as kotlinx.coroutines.ExecutorCoroutineDispatcher).close()

    fun store(companyCode: String): AssistantStore = JdbcAssistantStore {
        access.tenantConnectionForCompany(companyCode) ?: throw java.sql.SQLException("company not available", "08001")
    }

    /** The engine of [store]; with [companyCode] it also notifies the owner's inbox (a store without a connection source never does). */
    fun engine(store: AssistantStore, companyCode: String? = null): AssistantEngine {
        val notifier = companyCode?.takeIf { store.connections != null }?.let { code ->
            AssistantNotifier(access, code) { userId -> (liveServerActor?.invoke(userId) as? ServerActorLookup.Found)?.actor?.isOwner == true }
        }
        return AssistantEngine(store, models, clock, turnLimits, notifier = notifier)
    }

    /**
     * The caller of a company request: [companyCode] and [userId] come from the validated session (userSession),
     * [token] is that session's token. Null for the server workspace (its people go through [serverContext]; its staff
     * accounts are out) or an inactive user.
     */
    fun context(tenant: Connection, companyCode: String, userId: UUID, token: String): AssistantContext? {
        if (companyCode.equals(ServerWorkspace.Code, ignoreCase = true)) return null
        // Who is behind the session decides whose conversations it sees: when that cannot be read, nothing is shown
        // (never the account's own conversations by default).
        val opener = token.takeIf { it.isNotEmpty() }?.let {
            try {
                access.sessionOpener(companyCode, it)
            } catch (failure: Exception) {
                log.warn("Assistant could not read the opener of a session in company {}: {}", companyCode, failure.javaClass.name)
                throw AssistantRefusal(503, "ASSISTANT_UNAVAILABLE", tr("No se pudo abrir el asistente de la empresa."))
            }
        }
        val user = access.userAccess(tenant, companyCode, userId, serverOwner = false) ?: return null
        val person = access.tenantUserActor(tenant, userId, user.businessOwner)
        val company = access.companyOf(tenant)
        val caller = AssistantCaller(
            companyCode = companyCode,
            companyName = company?.name ?: companyCode,
            userId = userId,
            ownerKey = ownerKey(opener),
            displayName = person.name,
            language = I18n.language(),
            businessOwner = user.businessOwner,
            ownHosts = serverHosts + ownDomains(companyCode),
        )
        val permitted = user.permissions(EmpresaModuleId)?.has(AssistantIds.UsePermission) == true
        val store = store(companyCode)
        val resolver = { access.sessionActor(companyCode, token, userId, user.businessOwner) }
        if (opener?.kind != SessionOpener.Server) return AssistantContext(caller, permitted, resolver, store)
        // A session opened from the server: the business owner's tools, but the right to use the assistant and to change
        // its switch is the server person's own, read now (the empresa permission does not count, and a business owner
        // session an administrator opened is not the business owner's assistant).
        val server = (liveServerActor?.invoke(opener.userId) as? ServerActorLookup.Found)?.actor
        val mayUse = server != null && (server.isOwner || (server.has(ServerPermission.AssistantUse) && server.mayActOn(companyCode)))
        val mayManage = server != null && (server.isOwner || (server.has(ServerPermission.AssistantManage) && server.mayActOn(companyCode)))
        return AssistantContext(caller, permitted, resolver, store, mayUse = mayUse, mayManage = mayManage, serverPerson = server)
    }

    /**
     * The request of the server owner or an administrator in the server workspace ([tenant] is the workspace's database):
     * the owner always may use it; an administrator needs server.assistant.use. Conversations are the person's own (their
     * central user id, owner key "self"). Null when the workspace company cannot be read.
     */
    fun serverContext(tenant: Connection, actor: ServerActor): AssistantContext? {
        val company = access.companyOf(tenant)
        // Their name as the server's audit and activity logs show it (display name, else login); the owner's session actor carries none.
        val name = actor.displayName?.takeIf { it.isNotBlank() } ?: access.serverPersonActor(actor.userId, ServerWorkspace.Code).name.ifBlank { actor.login }
        val caller = AssistantCaller(
            companyCode = ServerWorkspace.Code,
            companyName = company?.name ?: ServerWorkspace.Name,
            userId = actor.userId,
            ownerKey = ownerKey(null),
            displayName = name,
            language = I18n.language(),
            businessOwner = actor.isOwner,
            ownHosts = serverHosts + ownDomains(ServerWorkspace.Code),
            serverRole = if (actor.isOwner) "owner" else "admin",
        )
        val mayUse = actor.isOwner || actor.has(ServerPermission.AssistantUse)
        // The workspace has no switch of its own: it is the server's, which an administrator changes with every company in scope.
        val mayManage = actor.isOwner || (actor.has(ServerPermission.AssistantManage) && actor.companyScope == null)
        return AssistantContext(
            caller, mayUse, { access.serverPersonActor(actor.userId, ServerWorkspace.Code) }, store(ServerWorkspace.Code),
            mayUse = mayUse, mayManage = mayManage, serverPerson = actor, workspace = true,
        )
    }

    /** The server person behind [token] now (owner, or administrator without a pending password change), or null. */
    fun serverPerson(token: String): ServerActor? =
        token.takeIf { it.isNotEmpty() }?.let { (serverActors?.resolve(it) as? ServerActorLookup.Found)?.actor }

    private fun ownDomains(companyCode: String): Set<String> = try {
        access.centralConnection().use { central ->
            central.prepareStatement("SELECT domain FROM public_domains WHERE company_code = ?").use { query ->
                query.setString(1, companyCode)
                query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1).lowercase()) } }
            }
        }
    } catch (_: java.sql.SQLException) {
        emptySet()
    }

    /** Which conversations a session sees: the account's own, or those of the person behind a switcher session. */
    fun ownerKey(opener: SessionOpener?): String = when {
        opener == null -> "self"
        opener.kind == SessionOpener.Server -> "server:${opener.userId}"
        else -> "company:${opener.companyCode.orEmpty()}:${opener.userId}"
    }

    fun availability(tenant: Connection, context: AssistantContext): AssistantAvailability {
        val server = serverConfig.read()
        val allowance = serverConfig.allowance(context.caller.companyCode)
        // The workspace has no switch and no key of its own: the server's switch and key are the ones (rule 3 does not exist there).
        val company = if (context.workspace) AssistantCompanySettings(enabled = server.enabled) else AssistantCompanyConfig.read(tenant)
        val keySource = when {
            !context.workspace && company.ownKeySet -> "company"
            server.keySet -> "server"
            else -> null
        }
        val limitedSources = if (server.limitOwnKeyCompanies) setOf("server", "company") else setOf("server")
        val monthlyLimit = serverConfig.monthlyLimit(server, allowance)
        val used = runCatching { context.store.monthUsage(YearMonth.from(clock().atZone(ZoneOffset.UTC)), limitedSources) }.getOrDefault(0L)
        val reason = when {
            !server.enabled -> AssistantUnavailable(AssistantIds.ReasonServerDisabled, tr("El dueno del servidor no activo el asistente."))
            !allowance.allowed && context.workspace ->
                AssistantUnavailable(AssistantIds.ReasonCompanyNotAllowed, tr("El dueno del servidor no permite el asistente en el espacio del servidor."))
            !allowance.allowed -> AssistantUnavailable(AssistantIds.ReasonCompanyNotAllowed, tr("El servidor no permite el asistente en esta empresa."))
            !company.enabled -> AssistantUnavailable(AssistantIds.ReasonCompanyDisabled, tr("El dueno de la empresa no activo el asistente."))
            keySource == null -> AssistantUnavailable(AssistantIds.ReasonNoApiKey, tr("No hay una clave de API configurada para el asistente."))
            !secrets.available -> AssistantUnavailable(AssistantIds.ReasonSecretsUnavailable, tr("El servidor no puede abrir la clave del asistente (falta BMS_MASTER_KEY)."))
            !context.mayUse && context.serverPerson != null ->
                AssistantUnavailable(AssistantIds.ReasonPermissionRequired, tr("Necesitas el permiso {0}.", AssistantIds.ServerUsePermission))
            !context.mayUse -> AssistantUnavailable(AssistantIds.ReasonPermissionRequired, tr("No tienes permiso para usar el asistente."))
            monthlyLimit != null && keySource in limitedSources && used >= monthlyLimit ->
                AssistantUnavailable(AssistantIds.ReasonLimitReached, tr("La empresa alcanzo su limite mensual del asistente."))
            else -> null
        }
        val visible = context.mayManage ||
            (context.mayUse && reason?.reason !in setOf(AssistantIds.ReasonServerDisabled, AssistantIds.ReasonCompanyNotAllowed, AssistantIds.ReasonCompanyDisabled))
        return AssistantAvailability(visible, reason, server, company, allowance, keySource, monthlyLimit, limitedSources, used)
    }

    fun status(availability: AssistantAvailability, context: AssistantContext): AssistantStatusResponse = AssistantStatusResponse(
        visible = availability.visible,
        available = availability.unavailable == null,
        reason = availability.unavailable?.reason,
        message = availability.unavailable?.message,
        canManage = context.mayManage,
        workspace = context.workspace,
        serverPerson = context.serverPerson != null,
        manageHint = if (context.mayManage) null else manageHint(availability.unavailable?.reason, context),
        model = availability.server.model,
        keySource = availability.keySource,
        monthlyLimitTokens = availability.monthlyLimit,
        monthUsedTokens = availability.monthUsed,
        limitApplies = availability.keySource == null || availability.keySource in availability.limitedSources,
        confirmationHours = turnLimits.confirmationTtl.toHours().toInt(),
        maxMessageChars = turnLimits.maxMessageChars,
        disclosure = if (context.workspace) serverAssistantDisclosure() else assistantDisclosure(),
    )

    /** Who can fix [reason], when this session cannot (one sentence for the screen that explains why it is off). */
    private fun manageHint(reason: String?, context: AssistantContext): String? = when (reason) {
        AssistantIds.ReasonServerDisabled, AssistantIds.ReasonCompanyNotAllowed, AssistantIds.ReasonNoApiKey, AssistantIds.ReasonSecretsUnavailable ->
            tr("Lo configura el dueno del servidor (Ajustes del servidor > Asistente de IA).")
        AssistantIds.ReasonCompanyDisabled ->
            tr("Lo activa el dueno de la empresa (Ajustes > Asistente de IA) o el dueno del servidor.")
        AssistantIds.ReasonPermissionRequired ->
            if (context.serverPerson != null) tr("Pidele el permiso {0} al dueno del servidor.", AssistantIds.ServerUsePermission)
            else tr("Pide el permiso al dueno de la empresa.")
        else -> null
    }

    /** The runtime of a model request: the key opened now (never kept), the model settings and the limit. */
    fun runtime(tenant: Connection, availability: AssistantAvailability): AssistantRuntime {
        availability.unavailable?.let { throw AssistantRefusal(403, it.reason, it.message) }
        val key = try {
            when (availability.keySource) {
                "company" -> AssistantCompanyConfig.ownKey(tenant, secrets)
                else -> serverConfig.apiKey()
            }
        } catch (failure: Exception) {
            log.error("Assistant key of a company could not be opened: {}", failure.javaClass.name)
            null
        } ?: throw AssistantRefusal(403, AssistantIds.ReasonSecretsUnavailable, tr("El servidor no puede abrir la clave del asistente (falta BMS_MASTER_KEY)."))
        return AssistantRuntime(
            model = availability.server.model,
            effort = availability.server.effort,
            apiKey = key,
            keySource = availability.keySource!!,
            monthlyLimit = availability.monthlyLimit,
            limitedSources = availability.limitedSources,
        )
    }

    fun toolbox(context: AssistantContext): AssistantToolbox {
        val caller = context.caller
        if (!context.workspace) return ModuleAssistantToolbox(access, caller.companyCode, caller.userId) { context.actor }
        // The server workspace: the owner's own tools there, or the administrator's (the same as their MCP agents).
        val person = context.serverPerson ?: return ModuleAssistantToolbox(access, caller.companyCode, caller.userId, AssistantGrantSource { null }) { context.actor }
        val grants = if (person.isOwner) ServerOwnerGrants(access, person.userId)
        else ServerAdminGrants(access, person.userId, liveServerActor ?: { ServerActorLookup.None })
        return ModuleAssistantToolbox(
            access, caller.companyCode, caller.userId, grants,
            executed = { tool, arguments, ok ->
                // The tool wrote its own row (who did it, as the owner); this one says it was the assistant of that person.
                serverConfig.auditAssistantTool(person.audit, caller.displayName, tool.qualifiedName, arguments, ok)
            },
            callPolicy = ServerCallPolicy(::companyOptedIn, ::companyName),
        ) { context.actor }
    }

    /**
     * Whether the assistant may read data of company [code] without asking: the server allows it there AND the company's own
     * switch is on (the server workspace has no switch of its own: the server's). Anything that cannot be read counts as "no".
     */
    internal fun companyOptedIn(code: String): Boolean = try {
        serverConfig.allowance(code).allowed &&
            (code.equals(ServerWorkspace.Code, ignoreCase = true) || access.tenantConnectionForCompany(code)?.use { AssistantCompanyConfig.read(it).enabled } == true)
    } catch (failure: Exception) {
        log.warn("Assistant could not read whether company {} opted in: {}", code, failure.javaClass.name)
        false
    }

    // The name of a company for a card (a plain read of the control database), null when unknown.
    private fun companyName(code: String): String? = try {
        access.centralConnection().use { central ->
            central.prepareStatement("SELECT name FROM companies WHERE code = ?").use { query ->
                query.setString(1, code)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The daily job of one company: expired confirmations answered, conversations past the retention deleted, usage
     * rows older than 13 months deleted.
     */
    fun daily(companyCode: String, databaseName: String? = null) {
        // Suspended (inactive) companies too: their database is reached by its name.
        val store = if (databaseName == null) store(companyCode) else JdbcAssistantStore {
            access.tenantConnectionForDatabase(databaseName) ?: throw java.sql.SQLException("company not available", "08001")
        }
        val expired = engine(store, companyCode).expireAll()
        val retention = serverConfig.read().retentionDays
        val now = clock()
        val purged = store.purge(now.minus(java.time.Duration.ofDays(retention.toLong())), now.atZone(ZoneOffset.UTC).toLocalDate().minusMonths(13).withDayOfMonth(1))
        if (expired > 0 || purged > 0) log.info("Assistant upkeep of company {}: {} confirmations expired, {} conversations deleted", companyCode, expired, purged)
    }

    companion object {
        const val EmpresaModuleId = "empresa"
        // Conversations a person may keep (archived included); past it, delete some first.
        const val MaxConversationsPerUser = 500
    }
}
