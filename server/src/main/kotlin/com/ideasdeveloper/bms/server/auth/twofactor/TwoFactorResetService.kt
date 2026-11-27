package com.ideasdeveloper.bms.server.auth.twofactor

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.CoreActivity
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.auth.application.AccountException
import com.ideasdeveloper.bms.server.auth.application.AccountSecurityEvents
import com.ideasdeveloper.bms.server.auth.application.CredentialsChanged
import com.ideasdeveloper.bms.server.auth.application.PasswordAttemptLimiter
import com.ideasdeveloper.bms.server.auth.application.PasswordConfirmation
import com.ideasdeveloper.bms.server.auth.application.UserProfileRules
import com.ideasdeveloper.bms.server.auth.application.recordGrantsNotEnded
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.auditDetails
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.companyLanguage
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.userLanguage
import com.ideasdeveloper.bms.server.modules.MailSender
import com.ideasdeveloper.bms.server.modules.SmtpSettings
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class TwoFactorResetResponse(
    val userId: String,
    val twoFactorEnabled: Boolean,
    val sessionsClosed: Int,
    val agentsDisconnected: Int,
    val agentsDisconnectFailed: Boolean,
    val message: String,
)

/** Refusals of [TwoFactorResetService]; [code] as TWO_FACTOR.md §8.2, [status] the HTTP status. */
class TwoFactorResetException(val code: String, val status: Int, message: String) : Exception(message)

/**
 * Turning two-step verification off for someone else (TWO_FACTOR.md §8): who may is decided by the caller
 * (business-owner route or ServerRoutePolicies); this service applies the guardrails common to every actor
 * (re-authentication with the actor's password, a passed second step when the actor uses one, at most
 * [MaxResetsPerHour] resets per actor) and the effect on the target.
 */
class TwoFactorResetService(
    private val loginDatabases: LoginDatabases,
    private val passwordAttempts: PasswordAttemptLimiter = PasswordAttemptLimiter(),
    private val mail: () -> SmtpSettings? = { null },
    private val notify: (companyCode: String?, userId: UUID, title: String, text: String) -> Unit = { _, _, _, _ -> },
    private val sendMail: (SmtpSettings, String, String, String) -> Unit = { settings, to, subject, body -> MailSender.sendText(settings, to, subject, body) },
    private val clock: () -> Instant = Instant::now,
) {
    companion object {
        const val MaxResetsPerHour = 10
    }

    private val log = LoggerFactory.getLogger(TwoFactorResetService::class.java)
    private val store get() = loginDatabases.twoFactor
    private val resets = ConcurrentHashMap<String, MutableList<Instant>>()
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    /** Who acts: [companyCode] is the actor's company for a business owner, null for a server person. */
    class Actor(
        val audit: AuditActor,
        val userId: UUID,
        val login: String,
        val name: String,
        // The actor's own account: its company (null: a server account); its password and its two-step state are read there.
        val companyCode: String?,
        val activity: ActivityActor,
        // Where the acting session lives when it is not the actor's own account (a company session opened by a
        // switcher, whose second-step mark was inherited): its company; null = the actor's own [companyCode].
        val sessionCompanyCode: String? = companyCode,
    )

    /** The actor's guardrails: their password, a passed second step when they use one, and the rate. */
    fun checkActor(actor: Actor, token: String, currentPassword: String?) {
        val actorUsesIt = loginDatabases.accountSource(actor.companyCode)?.connection?.use { store.isEnabled(it, actor.userId) } == true
        if (actorUsesIt && !loginDatabases.sessionPassedSecondFactor(actor.sessionCompanyCode, token)) {
            throw TwoFactorResetException("TWO_FACTOR_REAUTH_REQUIRED", 403, tr("Para hacerlo tienes que haber iniciado sesion con tu codigo de verificacion en dos pasos. Cierra sesion y vuelve a entrar con tu codigo."))
        }
        PasswordConfirmation.require(currentPassword, passwordAttempts, "two-factor-reset/${actor.companyCode.orEmpty()}/${actor.login}", tr("Escribe tu contrasena actual.")) { password ->
            loginDatabases.verifyPassword(actor.companyCode, actor.login, password)
        }
        val key = "${actor.audit.kind}/${actor.userId}"
        val now = clock()
        val recent = resets.computeIfAbsent(key) { mutableListOf() }
        synchronized(recent) {
            recent.removeIf { it.isBefore(now.minus(Duration.ofHours(1))) }
            if (recent.size >= MaxResetsPerHour) throw AccountException("TOO_MANY_ATTEMPTS", tr("Demasiados intentos. Espera unos minutos e intentalo de nuevo."))
            recent += now
        }
    }

    /** Whether [userId] of [companyCode] (null: central) has it on; null when it cannot be told. */
    fun enabled(companyCode: String?, userId: UUID): Boolean? = runCatching {
        loginDatabases.accountSource(companyCode)?.connection?.use { connection ->
            if (store.available(connection)) store.isEnabled(connection, userId) else null
        }
    }.getOrNull()

    /**
     * Turns it off for [targetUserId] of [companyCode] (null: a server administrator). Call [checkActor] and the
     * permission checks first. Everything but the notices happens before it answers.
     */
    fun reset(actor: Actor, companyCode: String?, targetUserId: UUID): TwoFactorResetResponse {
        val source = loginDatabases.accountSource(companyCode)
            ?: throw TwoFactorResetException("USER_NOT_FOUND", 404, tr("Usuario no encontrado."))
        val (login, email, language, closed) = source.connection.use { connection ->
            val row = connection.prepareStatement("SELECT postgres_role, email FROM application_users WHERE id = ?").use { q ->
                q.setObject(1, targetUserId)
                q.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
            } ?: throw TwoFactorResetException("USER_NOT_FOUND", 404, tr("Usuario no encontrado."))
            if (!store.available(connection)) throw TwoFactorResetException(TwoFactorException.Unavailable, 503, tr("La verificacion en dos pasos no esta disponible en este servidor."))
            if (!store.isEnabled(connection, targetUserId)) throw TwoFactorResetException(TwoFactorException.NotEnabled, 409, tr("La verificacion en dos pasos no esta activa."))
            val previous = connection.autoCommit
            connection.autoCommit = false
            val sessions = try {
                connection.prepareStatement("DELETE FROM application_user_recovery_codes WHERE user_id = ?").use { it.setObject(1, targetUserId); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM application_user_two_factor WHERE user_id = ?").use { it.setObject(1, targetUserId); it.executeUpdate() }
                val revoked = connection.prepareStatement("UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL").use { u ->
                    u.setObject(1, targetUserId); u.executeUpdate()
                }
                if (companyCode != null) {
                    CoreActivity.record(connection, actor.activity, "user", targetUserId.toString(), row.first, ActivityActions.Disabled,
                        "Desactivo la verificacion en dos pasos de ${row.first} (perdio su autenticador y sus codigos).")
                }
                connection.commit()
                revoked
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            } finally {
                runCatching { connection.autoCommit = previous }
            }
            runCatching { loginDatabases.emailRecovery?.cancelOpen(connection, companyCode, targetUserId, "reset") }
                .onFailure { log.error("A pending email recovery of {} could not be cancelled after a reset", row.first, it) }
            val language = userLanguage(connection, targetUserId)
                ?: companyCode?.let { code -> runCatching { loginDatabases.centralSource.connection.use { companyLanguage(it, code) } }.getOrNull() } ?: I18n.Source
            listOf(row.first, row.second, language, sessions)
        }
        login as String; language as String; closed as Int
        if (companyCode == null) {
            runCatching { loginDatabases.revokeCompanySessionsOpenedBy(targetUserId) }
                .onFailure { log.error("Company sessions of {} could not be revoked after a reset", login, it) }
        }
        val event = CredentialsChanged(companyCode, targetUserId, CredentialsChanged.TwoFactorReset)
        val agents = AccountSecurityEvents.credentialsChanged(event)
        recordGrantsNotEnded(loginDatabases.centralSource, event, login, agents)
        loginDatabases.centralSource.connection.use { connection ->
            ProvisioningAudit.insert(
                connection, actor.audit, "two_factor.reset_by_other", companyCode, null,
                auditDetails("target" to login, "targetUserId" to targetUserId.toString(), "actorKind" to actor.audit.kind,
                    "sessionsClosed" to closed, "agentsDisconnected" to agents.ended, "agentsDisconnectFailed" to !agents.complete),
            )
        }
        tell(companyCode, targetUserId, login, email as String?, language, actor.name)
        val message = tr("Se desactivo la verificacion en dos pasos de {0}. Se cerraron sus sesiones; debe iniciar sesion de nuevo y volver a configurarla.", login) +
            if (agents.complete) "" else " " + tr("No se pudieron desconectar sus agentes de IA: revocalos a mano en Agentes IA.")
        return TwoFactorResetResponse(targetUserId.toString(), false, closed, agents.ended, !agents.complete, message)
    }

    // The target hears who did it and when, by email (its language) and in the app.
    private fun tell(companyCode: String?, userId: UUID, login: String, email: String?, language: String, actorName: String) {
        val now = stamp.format(clock())
        I18n.withLanguage(language) {
            val subject = tr("Se desactivo la verificacion en dos pasos de tu cuenta de BMS")
            val text = tr("{0} desactivo la verificacion en dos pasos de tu cuenta {1} el {2}. Inicia sesion con tu contrasena y vuelve a configurarla. Si no lo pediste, avisa a tu administrador.", actorName, login, now)
            val settings = mail()?.takeIf { it.isUsable }
            val address = email?.trim()?.takeIf { UserProfileRules.validEmail(it) }
            if (settings != null && address != null) {
                runCatching { sendMail(settings, address, subject, tr("Hola {0}:", login) + "\n\n" + text + "\n") }
                    .onFailure { log.warn("Two-step verification reset notice for {} could not be mailed", login, it) }
            }
            runCatching { notify(companyCode, userId, subject, text) }.onFailure { log.warn("Reset notification for {} failed", login, it) }
        }
    }
}
