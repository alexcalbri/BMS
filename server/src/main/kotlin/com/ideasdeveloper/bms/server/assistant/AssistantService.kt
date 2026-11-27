package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.AssistantIds
import com.ideasdeveloper.bms.AssistantStatusResponse
import com.ideasdeveloper.bms.server.activity.ActivityActor
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

/** One company request: the caller, the session actor and the company's store. */
class AssistantContext(
    val caller: AssistantCaller,
    val hasUsePermission: Boolean,
    private val actorResolver: () -> ActivityActor,
    val store: AssistantStore,
) {
    val actor: ActivityActor by lazy(actorResolver)
}

/**
 * Per user: requests per minute and streams at once (GLOBAL_AGENT.md, Contract, "Limits"). In memory: a restart
 * forgets it, which only ever lets a few more requests through.
 */
class AssistantUserLimits(
    private val perMinute: Int = 10,
    private val streamsPerUser: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Window(var start: Long, var count: Int)
    private val windows = ConcurrentHashMap<String, Window>()
    private val streams = ConcurrentHashMap<String, AtomicInteger>()

    fun allow(key: String): Boolean {
        val now = clock()
        if (windows.size > 20_000) windows.entries.removeIf { now - it.value.start >= 60_000 }
        val window = windows.computeIfAbsent(key) { Window(now, 0) }
        synchronized(window) {
            if (now - window.start >= 60_000) {
                window.start = now
                window.count = 0
            }
            window.count++
            return window.count <= perMinute
        }
    }

    /** Takes a stream slot; false when the user already has [streamsPerUser] open. Give it back with [release]. */
    fun openStream(key: String): Boolean {
        val count = streams.computeIfAbsent(key) { AtomicInteger() }
        if (count.incrementAndGet() > streamsPerUser) {
            count.decrementAndGet()
            return false
        }
        return true
    }

    fun release(key: String) {
        streams[key]?.let { if (it.decrementAndGet() <= 0) streams.remove(key, it) }
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
) {
    private val log = LoggerFactory.getLogger(AssistantService::class.java)

    fun store(companyCode: String): AssistantStore = JdbcAssistantStore {
        access.tenantConnectionForCompany(companyCode) ?: throw java.sql.SQLException("company not available", "08001")
    }

    fun engine(store: AssistantStore): AssistantEngine = AssistantEngine(store, models, clock, turnLimits)

    /**
     * The caller of a company request: [companyCode] and [userId] come from the validated session (userSession),
     * [token] is that session's token. Null for the server workspace (out of this version) or an inactive user.
     */
    fun context(tenant: Connection, companyCode: String, userId: UUID, token: String): AssistantContext? {
        if (companyCode.equals(ServerWorkspace.Code, ignoreCase = true)) return null
        val user = access.userAccess(tenant, companyCode, userId, serverOwner = false) ?: return null
        val opener = token.takeIf { it.isNotEmpty() }?.let { runCatching { access.sessionOpener(companyCode, it) }.getOrNull() }
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
        )
        val permitted = user.permissions(EmpresaModuleId)?.has(AssistantIds.UsePermission) == true
        return AssistantContext(caller, permitted, { access.sessionActor(companyCode, token, userId, user.businessOwner) }, store(companyCode))
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
        val company = AssistantCompanyConfig.read(tenant)
        val keySource = when {
            company.ownKeySet -> "company"
            server.keySet -> "server"
            else -> null
        }
        val limitedSources = if (server.limitOwnKeyCompanies) setOf("server", "company") else setOf("server")
        val monthlyLimit = serverConfig.monthlyLimit(server, allowance)
        val used = runCatching { context.store.monthUsage(YearMonth.from(clock().atZone(ZoneOffset.UTC)), limitedSources) }.getOrDefault(0L)
        val reason = when {
            !server.enabled -> AssistantUnavailable(AssistantIds.ReasonServerDisabled, tr("El dueno del servidor no activo el asistente."))
            !allowance.allowed -> AssistantUnavailable(AssistantIds.ReasonCompanyNotAllowed, tr("El servidor no permite el asistente en esta empresa."))
            !company.enabled -> AssistantUnavailable(AssistantIds.ReasonCompanyDisabled, tr("El dueno de la empresa no activo el asistente."))
            keySource == null -> AssistantUnavailable(AssistantIds.ReasonNoApiKey, tr("No hay una clave de API configurada para el asistente."))
            !secrets.available -> AssistantUnavailable(AssistantIds.ReasonSecretsUnavailable, tr("El servidor no puede abrir la clave del asistente (falta BMS_MASTER_KEY)."))
            !context.caller.businessOwner && !context.hasUsePermission ->
                AssistantUnavailable(AssistantIds.ReasonPermissionRequired, tr("No tienes permiso para usar el asistente."))
            monthlyLimit != null && keySource in limitedSources && used >= monthlyLimit ->
                AssistantUnavailable(AssistantIds.ReasonLimitReached, tr("La empresa alcanzo su limite mensual del asistente."))
            else -> null
        }
        val visible = context.caller.businessOwner ||
            (context.hasUsePermission && reason?.reason !in setOf(AssistantIds.ReasonServerDisabled, AssistantIds.ReasonCompanyNotAllowed, AssistantIds.ReasonCompanyDisabled))
        return AssistantAvailability(visible, reason, server, company, allowance, keySource, monthlyLimit, limitedSources, used)
    }

    fun status(availability: AssistantAvailability, context: AssistantContext): AssistantStatusResponse = AssistantStatusResponse(
        visible = availability.visible,
        available = availability.unavailable == null,
        reason = availability.unavailable?.reason,
        message = availability.unavailable?.message,
        canManage = context.caller.businessOwner,
        model = availability.server.model,
        keySource = availability.keySource,
        monthlyLimitTokens = availability.monthlyLimit,
        monthUsedTokens = availability.monthUsed,
        limitApplies = availability.keySource == null || availability.keySource in availability.limitedSources,
        confirmationHours = turnLimits.confirmationTtl.toHours().toInt(),
        maxMessageChars = turnLimits.maxMessageChars,
        disclosure = assistantDisclosure(),
    )

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

    fun toolbox(context: AssistantContext): AssistantToolbox =
        ModuleAssistantToolbox(access, context.caller.companyCode, context.caller.userId) { context.actor }

    /**
     * The daily job of one company: expired confirmations answered, conversations past the retention deleted, usage
     * rows older than 13 months deleted.
     */
    fun daily(companyCode: String) {
        val store = store(companyCode)
        val expired = engine(store).expireAll()
        val retention = serverConfig.read().retentionDays
        val now = clock()
        val purged = store.purge(now.minus(java.time.Duration.ofDays(retention.toLong())), now.atZone(ZoneOffset.UTC).toLocalDate().minusMonths(13).withDayOfMonth(1))
        if (expired > 0 || purged > 0) log.info("Assistant upkeep of company {}: {} confirmations expired, {} conversations deleted", companyCode, expired, purged)
    }

    companion object {
        const val EmpresaModuleId = "empresa"
    }
}
