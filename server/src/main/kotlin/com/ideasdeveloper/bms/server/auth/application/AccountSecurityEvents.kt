package com.ideasdeveloper.bms.server.auth.application

import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The credentials of an account changed in a way that must end the access granted before (two-step
 * verification turned on, password reset by email): [companyCode] is the account's company, null for a
 * server account (owner or administrator; their agents live in the server workspace).
 */
data class CredentialsChanged(val companyCode: String?, val userId: UUID, val reason: String) {
    companion object {
        const val TwoFactorEnabled = "two_factor_enabled"
        const val PasswordReset = "password_reset"
        // Someone else turned the account's two-step verification off (TWO_FACTOR.md §8).
        const val TwoFactorReset = "two_factor_reset"
        // The account replaced its authenticator (TWO_FACTOR.md §9).
        const val TwoFactorReplaced = "two_factor_replaced"
    }
}

/** What a module does when an account's credentials change, e.g. revoke the account's AI agent tokens. */
fun interface CredentialsChangedListener {
    /** Returns how many grants it ended (for logs only). */
    fun credentialsChanged(event: CredentialsChanged): Int
}

/**
 * Hook of Core for modules that keep their own long-lived grants of an account (Agentes: agent_tokens):
 * Core never writes a module's tables, it tells the subscribers. Each module subscribes once under its
 * id (a later subscription replaces it). A failing subscriber is logged and does not stop the others.
 */
object AccountSecurityEvents {
    private val log = LoggerFactory.getLogger(AccountSecurityEvents::class.java)
    private val listeners = ConcurrentHashMap<String, CredentialsChangedListener>()

    fun subscribe(moduleId: String, listener: CredentialsChangedListener) {
        listeners[moduleId] = listener
    }

    fun unsubscribe(moduleId: String) {
        listeners.remove(moduleId)
    }

    /**
     * Tells every subscriber, retrying once a subscriber that fails. Never throws: the outcome says how many
     * grants ended and which modules could not end theirs (the caller must say so and flag it for attention).
     */
    fun credentialsChanged(event: CredentialsChanged): CredentialsChangeOutcome {
        var ended = 0
        val failed = mutableListOf<String>()
        listeners.forEach { (moduleId, listener) ->
            val result = runCatching { listener.credentialsChanged(event) }
                .recoverCatching { first ->
                    log.warn("Module {} failed to end the grants of user {} after {}; retrying once", moduleId, event.userId, event.reason, first)
                    listener.credentialsChanged(event)
                }
            result.onSuccess { ended += it }.onFailure { failure ->
                log.error("Module {} could not end the grants of user {} after {}", moduleId, event.userId, event.reason, failure)
                failed += moduleId
            }
        }
        return CredentialsChangeOutcome(ended, failed)
    }
}

/**
 * A subscriber could not end its grants (after the retry): a server audit row flagged for attention, so the owner
 * revokes those agents by hand. [companyCode] null: a server account. Never throws (it is logged).
 */
fun recordGrantsNotEnded(central: javax.sql.DataSource, event: CredentialsChanged, login: String, outcome: CredentialsChangeOutcome) {
    if (outcome.complete) return
    runCatching {
        central.connection.use { connection ->
            com.ideasdeveloper.bms.server.company.application.ProvisioningAudit.insert(
                connection, com.ideasdeveloper.bms.server.admins.AuditActor.system(), "security.agents_not_disconnected", event.companyCode, null,
                com.ideasdeveloper.bms.server.company.application.auditDetails(
                    "attention" to true, "account" to login, "userId" to event.userId.toString(), "reason" to event.reason,
                    "modules" to outcome.failedModules.joinToString(","),
                ),
            )
        }
    }.onFailure { LoggerFactory.getLogger(AccountSecurityEvents::class.java).error("The failure to end the grants of {} could not be audited", login, it) }
}

/** What [AccountSecurityEvents.credentialsChanged] achieved: [failedModules] could not end their grants. */
data class CredentialsChangeOutcome(val ended: Int, val failedModules: List<String>) {
    val complete: Boolean get() = failedModules.isEmpty()
}
