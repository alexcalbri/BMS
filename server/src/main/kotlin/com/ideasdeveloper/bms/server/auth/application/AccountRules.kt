package com.ideasdeveloper.bms.server.auth.application

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.CoreActivity
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.security.ApplicationPasswordHasher
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.SessionLanguages
import com.ideasdeveloper.bms.server.i18n.companyLanguage
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.userLanguage
import com.ideasdeveloper.bms.server.modules.MailException
import com.ideasdeveloper.bms.server.modules.MailSender
import com.ideasdeveloper.bms.server.modules.SmtpSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.postgresql.ds.PGSimpleDataSource
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

class AccountException(val code: String, message: String) : Exception(message)

/**
 * Regla del cambio de correo propio: cambiarlo exige la contrasena actual (si no, una sesion robada
 * redirigiria "olvide mi contrasena" a otro correo). Comparacion sin mayusculas ni espacios.
 */
object EmailChangeRule {
    // Solo A-Z pasan a minuscula: lowercase() de Unicode trata, por ejemplo, el signo Kelvin (U+212A) como "k".
    private fun normalize(email: String): String =
        email.trim().map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")

    fun isChange(storedEmail: String?, requestedEmail: String): Boolean =
        normalize(storedEmail.orEmpty()) != normalize(requestedEmail)

    /**
     * Lanza PASSWORD_REQUIRED, INVALID_PASSWORD o TOO_MANY_ATTEMPTS; [passwordMatches] solo se llama si el
     * correo cambia y hay contrasena. Con [attempts] y [attemptKey] cuenta los fallos por usuario.
     */
    fun check(
        storedEmail: String?,
        requestedEmail: String,
        currentPassword: String?,
        attempts: PasswordAttemptLimiter? = null,
        attemptKey: String = "",
        passwordMatches: (String) -> Boolean,
    ) {
        if (!isChange(storedEmail, requestedEmail)) return
        PasswordConfirmation.require(
            currentPassword, attempts, attemptKey, tr("Escribe tu contrasena actual para cambiar el correo."), passwordMatches,
        )
    }
}

/** Confirmacion de la contrasena actual antes de un cambio sensible, con tope de fallos por usuario. */
object PasswordConfirmation {
    /** El business owner confirma la contrasena al cambiar su correo o al poner una contrasena nueva. */
    fun ownerChangeNeedsPassword(storedEmail: String?, requestedEmail: String, newPassword: String): Boolean =
        newPassword.isNotEmpty() || EmailChangeRule.isChange(storedEmail, requestedEmail)

    fun require(
        currentPassword: String?,
        attempts: PasswordAttemptLimiter?,
        attemptKey: String,
        requiredMessage: String,
        passwordMatches: (String) -> Boolean,
    ) {
        if (currentPassword.isNullOrEmpty()) throw AccountException("PASSWORD_REQUIRED", requiredMessage)
        // El bloqueo se mira antes de comparar: un usuario bloqueado no sirve de oraculo de contrasenas.
        if (attempts != null && attempts.isBlocked(attemptKey)) {
            throw AccountException("TOO_MANY_ATTEMPTS", tr("Demasiados intentos. Espera unos minutos e intentalo de nuevo."))
        }
        if (!passwordMatches(currentPassword)) {
            attempts?.recordFailure(attemptKey)
            throw AccountException("INVALID_PASSWORD", tr("La contrasena actual no es correcta."))
        }
    }
}

/**
 * Tope de intentos FALLIDOS de contrasena actual por usuario y empresa (en memoria: la tabla de throttles
 * del reset tiene ventana de 24 h y su propio tope, y compartirla mezclaria ambos contadores). Un acierto no cuenta.
 */
class PasswordAttemptLimiter(
    private val maxFailures: Int = MaxFailures,
    private val window: Duration = Window,
    private val clock: () -> Instant = Instant::now,
) {
    private class Entry(val windowStart: Instant, var failures: Int)

    private val entries = ConcurrentHashMap<String, Entry>()

    companion object {
        const val MaxFailures = 5
        val Window: Duration = Duration.ofMinutes(15)
        const val MaxEntries = 10_000
    }

    fun isBlocked(key: String): Boolean {
        val entry = entries[key] ?: return false
        synchronized(entry) { return entry.failures >= maxFailures && live(entry, clock()) }
    }

    fun recordFailure(key: String) {
        val now = clock()
        if (entries.size >= MaxEntries) entries.entries.removeIf { !live(it.value, now) }
        if (entries.size >= MaxEntries && !entries.containsKey(key)) return
        val entry = entries.compute(key) { _, old -> if (old == null || !live(old, now)) Entry(now, 0) else old }!!
        synchronized(entry) { entry.failures++ }
    }

    /** Failures recorded for [key] in its current window (0 when none or expired). */
    fun failures(key: String): Int {
        val entry = entries[key] ?: return 0
        synchronized(entry) { return if (live(entry, clock())) entry.failures else 0 }
    }

    /** Forgets [key] (a successful sign-in). */
    fun reset(key: String) {
        entries.remove(key)
    }

    private fun live(entry: Entry, now: Instant) = entry.windowStart.plus(window).isAfter(now)
}

@Serializable
data class AccountProfile(
    val username: String,
    val displayName: String?,
    val email: String?,
    val profileComplete: Boolean,
    val userCode: String,
    // The user's own language ("es", "en"), null when they follow the company default (I18N.md §7).
    val language: String? = null,
)

/** Value of UpdateAccountProfileRequest.language when the request does not change the language. */
const val KeepLanguage = "keep"

/** How a password is set for each kind of user; owners sign in with a database role (CompanyProvisioningService). */
class AccountPasswords(
    val serverOwner: (userId: UUID, password: String) -> Unit,
    val businessOwner: (companyCode: String, password: String) -> Unit,
    // Server administrators (central database, server_admins): sets the hash and revokes their sessions.
    val serverAdmin: (userId: UUID, password: String) -> Unit = { _, _ ->
        throw AccountException("PASSWORD_NOT_UPDATED", tr("No se pudo cambiar la contrasena."))
    },
)
