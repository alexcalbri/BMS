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

    /** Tells every subscriber; returns the grants they ended. Never throws. */
    fun credentialsChanged(event: CredentialsChanged): Int = listeners.entries.sumOf { (moduleId, listener) ->
        try {
            listener.credentialsChanged(event)
        } catch (failure: Throwable) {
            log.error("Module {} could not end the grants of user {} after {}", moduleId, event.userId, event.reason, failure)
            0
        }
    }
}
