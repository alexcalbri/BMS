package com.ideasdeveloper.bms.modules.agentes

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.AgentSignIn
import com.ideasdeveloper.bms.server.modules.VerifiedUser
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Sign-in of the OAuth page in one or two steps (TWO_FACTOR.md §2, "AI agents"): the password, and for an
 * account with two-step verification on, a second page that asks for the authenticator code (or a recovery
 * code). Core checks both every time ([com.ideasdeveloper.bms.server.modules.ModuleAccessService.signInForAgent]),
 * so the second step cannot be skipped: what the first step leaves here is only what the second one needs to
 * send again (kept in memory, never in the page), bound to the OAuth client, its redirect URI and the client
 * address, for [lifetimeSeconds] and at most [maxAttempts] wrong codes.
 */
class AgentSignInFlow(
    private val clock: () -> Instant = Instant::now,
    private val lifetimeSeconds: Long = 300,
    private val maxAttempts: Int = 5,
) {
    /** What the page shows next. */
    sealed interface Step {
        data class Accepted(val user: VerifiedUser) : Step
        /** The second page; [error] when a code was wrong or missing. */
        data class AskCode(val pending: String, val error: String?) : Step
        /** Back to the password page with [error]. */
        data class Again(val error: String) : Step
    }

    /** Where the sign-in comes from: the second step must come from the same place. */
    data class Origin(val clientId: String, val redirectUri: String, val address: String)

    // Core's sign-in: (company, user, password, code) -> outcome.
    fun interface SignIn {
        fun attempt(company: String?, username: String, password: String, code: String?): AgentSignIn
    }

    private class Pending(val company: String?, val username: String, val password: String, val origin: Origin, val expiresAt: Instant) {
        @Volatile var attempts = 0
    }

    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<String, Pending>()

    fun password(company: String?, username: String, password: String, origin: Origin, signIn: SignIn): Step =
        when (val result = signIn.attempt(company, username, password, null)) {
            is AgentSignIn.Verified -> Step.Accepted(result.user)
            AgentSignIn.SecondFactorRequired -> Step.AskCode(remember(Pending(company, username, password, origin, clock().plusSeconds(lifetimeSeconds))), null)
            AgentSignIn.TooManyAttempts -> Step.Again(tooManyMessage())
            AgentSignIn.InvalidCode, AgentSignIn.Rejected -> Step.Again(rejectedMessage())
        }

    fun code(pendingId: String, code: String, origin: Origin, signIn: SignIn): Step {
        val entry = pending[pendingId]?.takeIf { it.expiresAt.isAfter(clock()) && it.origin == origin }
            ?: return Step.Again(expiredMessage()).also { pending.remove(pendingId) }
        val typed = code.trim()
        if (typed.isEmpty()) return Step.AskCode(pendingId, tr("Escribe el codigo de tu aplicacion de autenticacion o un codigo de recuperacion."))
        return when (val result = signIn.attempt(entry.company, entry.username, entry.password, typed)) {
            is AgentSignIn.Verified -> {
                pending.remove(pendingId)
                Step.Accepted(result.user)
            }
            AgentSignIn.InvalidCode, AgentSignIn.SecondFactorRequired -> {
                entry.attempts++
                if (entry.attempts >= maxAttempts) {
                    pending.remove(pendingId)
                    Step.Again(expiredMessage())
                } else {
                    Step.AskCode(pendingId, tr("El codigo no es correcto. Vuelve a intentarlo."))
                }
            }
            AgentSignIn.TooManyAttempts -> Step.Again(tooManyMessage()).also { pending.remove(pendingId) }
            AgentSignIn.Rejected -> Step.Again(rejectedMessage()).also { pending.remove(pendingId) }
        }
    }

    private fun remember(entry: Pending): String {
        val now = clock()
        if (pending.size > 1_000) pending.entries.removeIf { !it.value.expiresAt.isAfter(now) }
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        pending[id] = entry
        return id
    }

    fun pendingCount(): Int = pending.size

    private fun rejectedMessage() = tr("Usuario, contrasena o empresa incorrectos.")
    private fun tooManyMessage() = tr("Demasiados codigos incorrectos. Espera unos minutos antes de volver a intentarlo.")
    private fun expiredMessage() = tr("La verificacion vencio. Inicia sesion de nuevo.")
}
