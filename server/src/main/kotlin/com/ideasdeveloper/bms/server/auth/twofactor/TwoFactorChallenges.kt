package com.ideasdeveloper.bms.server.auth.twofactor

import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.i18n.tr
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Step 1 of a sign-in passed (right password) for an account with two-step verification on: no
 * session was opened, the caller gets [token] for step 2 (POST /auth/login/two-factor).
 */
class TwoFactorRequiredException(val token: String, val expiresInSeconds: Long) :
    Exception(tr("Escribe el codigo de tu aplicacion de autenticacion."))

/**
 * Pending second steps, in memory (a restart only sends people back to step 1). Each challenge is
 * bound to the account, the company (null: the server) and the client address of step 1, lives
 * [Lifetime], allows [MaxAttempts] codes and works once. Only the hash of the token is kept.
 */
class TwoFactorChallenges(private val clock: () -> Instant = Instant::now) {
    class Challenge(val userId: UUID, val username: String, val companyCode: String?, val clientAddress: String, val expiresAt: Instant) {
        @Volatile var attempts = 0
    }

    private val tokens = SessionTokenGenerator()
    private val challenges = ConcurrentHashMap<String, Challenge>()
    private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")

    companion object {
        val Lifetime: Duration = Duration.ofMinutes(5)
        const val MaxAttempts = 5
        const val MaxPending = 10_000
    }

    fun issue(userId: UUID, username: String, companyCode: String?, clientAddress: String): TwoFactorRequiredException {
        val now = clock()
        if (challenges.size >= MaxPending) challenges.entries.removeIf { !it.value.expiresAt.isAfter(now) }
        // Full of live challenges (a flood): the oldest go, the newest sign-ins still work.
        if (challenges.size >= MaxPending) {
            challenges.entries.sortedBy { it.value.expiresAt }.take(challenges.size - MaxPending + 1).forEach { challenges.remove(it.key) }
        }
        val token = tokens.generate()
        challenges[token.hash] = Challenge(userId, username, companyCode, clientAddress, now.plus(Lifetime))
        return TwoFactorRequiredException(token.value, Lifetime.seconds)
    }

    /** The live challenge of [token] for [clientAddress], counting one attempt; null when unknown, expired, used up or from elsewhere. */
    fun attempt(token: String, clientAddress: String): Challenge? {
        if (!tokenPattern.matches(token)) return null
        val hash = tokens.hash(token)
        val challenge = challenges[hash] ?: return null
        if (!challenge.expiresAt.isAfter(clock()) || challenge.clientAddress != clientAddress) {
            if (!challenge.expiresAt.isAfter(clock())) challenges.remove(hash)
            return null
        }
        synchronized(challenge) {
            if (challenge.attempts >= MaxAttempts) {
                challenges.remove(hash)
                return null
            }
            challenge.attempts++
        }
        return challenge
    }

    fun attemptsLeft(challenge: Challenge): Int = (MaxAttempts - challenge.attempts).coerceAtLeast(0)

    /** Ends the challenge of [token] (a success, or the account cannot go on). */
    fun finish(token: String) {
        if (tokenPattern.matches(token)) challenges.remove(tokens.hash(token))
    }
}

/** The second step was refused: [code] is the contract's error code, [attemptsLeft] for a wrong code. */
class TwoFactorSignInException(val code: String, message: String, val attemptsLeft: Int? = null) : Exception(message) {
    companion object {
        const val ChallengeExpired = "TWO_FACTOR_CHALLENGE_EXPIRED"

        fun expired() = TwoFactorSignInException(ChallengeExpired, tr("La verificacion vencio. Vuelve a iniciar sesion."))
    }
}
