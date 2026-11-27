package com.ideasdeveloper.bms.server.auth.twofactor

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.CoreActivity
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.auth.application.UserProfileRules
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.auditDetails
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.companyLanguage
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.userLanguage
import com.ideasdeveloper.bms.server.modules.MailSender
import com.ideasdeveloper.bms.server.modules.SmtpSettings
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class EmailRecoveryPending(val startedAt: String, val startedFrom: String, val codeDueAt: String, val codeSent: Boolean, val codeExpiresAt: String?)

@Serializable
data class EmailRecoveryStatus(val available: Boolean, val pending: EmailRecoveryPending?)

/** Outcome of entering the emailed code at the second step. */
sealed interface EmailRecoveryCheck {
    data object Completed : EmailRecoveryCheck
    data object Invalid : EmailRecoveryCheck
    data object Throttled : EmailRecoveryCheck
}

/**
 * Delayed recovery of two-step verification by email (TWO_FACTOR.md §7), on the account's own database
 * (control V018 / tenant V013). Start: a warning with a cancel link at once; after [wait] the job mails a
 * one-time code valid [CodeLifetime]; entering it turns two-step verification off. Only hashes of the
 * cancel token and of the code are stored, and neither is ever logged or recorded.
 *
 * [sources] gives the database of an account (null: the central one) and [companies] the active
 * companies for the job. [notify] adds the in-app notification (company null: the server workspace).
 */
/** The background of [TwoFactorEmailRecovery.start]: off the request thread, so every outcome answers alike. */
private val BackgroundStarts = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

class TwoFactorEmailRecovery(
    private val central: DataSource,
    private val sources: (companyCode: String?) -> DataSource?,
    private val companies: () -> List<String>,
    private val mail: () -> SmtpSettings?,
    private val twoFactor: TwoFactorStore,
    private val notify: (companyCode: String?, userId: UUID, title: String, text: String) -> Unit = { _, _, _, _ -> },
    private val sendMail: (SmtpSettings, String, String, String) -> Unit = { settings, to, subject, body -> MailSender.sendText(settings, to, subject, body) },
    private val clock: () -> Instant = Instant::now,
    // How long the code waits after the start. The constant is the rule; tests shorten it.
    val wait: Duration = DefaultWait,
    // Where the start's work runs (off the request); tests run it inline.
    private val background: (() -> Unit) -> Unit = { task -> BackgroundStarts.launch { task() } },
) {
    companion object {
        val DefaultWait: Duration = Duration.ofDays(7)
        val CodeLifetime: Duration = Duration.ofHours(24)
        // A code issued but not mailed (send failed, server stopped) is re-issued, replacing it, after this.
        val ResendAfter: Duration = Duration.ofMinutes(15)
        const val MaxStartsPerWindow = 3
        val StartWindow: Duration = Duration.ofDays(7)
        const val MaxFailures = 5
        val FailureWindow: Duration = Duration.ofMinutes(15)
        const val Table = "application_user_2fa_email_recovery"
        private const val Open = "('pending', 'code_sent')"
        private const val Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val TokenPattern = Regex("[A-Za-z0-9_-]{43}")
        private val CodeShape = Regex("[A-Z2-7]{12}")
        private val CompanyPattern = Regex("[a-z][a-z0-9_]{0,62}")

        fun normalizeCode(code: String): String? = code.uppercase().filter { it != '-' && !it.isWhitespace() }.takeIf { CodeShape.matches(it) }
    }

    private val log = LoggerFactory.getLogger(TwoFactorEmailRecovery::class.java)
    private val random = SecureRandom()
    private val tokens = SessionTokenGenerator()
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    /** Whether the server can send email at all (the option is offered only then). */
    fun offered(): Boolean = mail()?.isUsable == true

    fun available(connection: Connection): Boolean = try {
        connection.prepareStatement("SELECT to_regclass('public.$Table') IS NOT NULL").use { q -> q.executeQuery().use { it.next() && it.getBoolean(1) } }
    } catch (_: SQLException) {
        false
    }

    private class Account(val id: UUID, val username: String, val email: String?, val server: Boolean, val owner: Boolean)

    private fun account(connection: Connection, userId: UUID, companyCode: String?): Account? =
        connection.prepareStatement("SELECT postgres_role, email FROM application_users WHERE id = ? AND is_active").use { q ->
            q.setObject(1, userId)
            q.executeQuery().use { rows -> if (rows.next()) Pair(rows.getString(1), rows.getString(2)) else null }
        }?.let { (login, email) ->
            val owner = companyCode == null && connection.prepareStatement("SELECT 1 FROM server_owners WHERE user_id = ?").use { q ->
                q.setObject(1, userId); q.executeQuery().use { it.next() }
            }
            Account(userId, login, email?.trim(), companyCode == null, owner)
        }

    private fun language(connection: Connection, companyCode: String?, userId: UUID): String =
        userLanguage(connection, userId) ?: companyCode?.let { code -> runCatching { central.connection.use { companyLanguage(it, code) } }.getOrNull() } ?: I18n.Source

    // A row of the server audit or of the company's activity log, on [connection] (in its transaction).
    private fun record(connection: Connection, companyCode: String?, account: Account, bySystem: Boolean, action: String, summary: String, details: Map<String, Any?> = emptyMap()) {
        if (companyCode == null) {
            val actor = if (bySystem) AuditActor.system() else AuditActor(account.username, account.id, if (account.owner) AuditActor.ServerOwnerKind else AuditActor.ServerAdminKind)
            ProvisioningAudit.insert(connection, actor, "two_factor.$action", null, null, auditDetails(*(details + ("account" to account.username)).toList().toTypedArray()))
            return
        }
        val person = CoreActivity.tenantPerson(connection, account.id) ?: ActivityActor.user(account.id, account.username)
        val actor = if (bySystem) ActivityActor.system() else person
        CoreActivity.record(connection, actor, "user", account.id.toString(), account.username, ActivityActions.SettingsChanged, summary)
    }

    private fun <T> transaction(connection: Connection, block: () -> T): T {
        val previous = connection.autoCommit
        connection.autoCommit = false
        try {
            return block().also { connection.commit() }
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            runCatching { connection.autoCommit = previous }
        }
    }

    private fun mailTo(settings: SmtpSettings, to: String, language: String, build: () -> Pair<String, String>) {
        val (subject, body) = I18n.withLanguage(language) { build() }
        sendMail(settings, to, subject, body)
    }

    /**
     * Starts a process for the account behind a valid step-1 challenge. All the work (checks, the row, the
     * warning mail) runs in [background], so every outcome answers alike and at once. Nothing is started
     * (and nothing said) without two-step verification, without a valid email, with one already open or with
     * too many starts. [publicBase] null: the warning has no link and says to cancel from the account section.
     */
    fun start(companyCode: String?, userId: UUID, clientAddress: String, publicBase: String?) {
        background {
            try {
                startNow(companyCode, userId, clientAddress, publicBase)
            } catch (failure: Exception) {
                log.error("A two-step email recovery could not be started for user {}", userId, failure)
            }
        }
    }

    private fun startNow(companyCode: String?, userId: UUID, clientAddress: String, publicBase: String?) {
        val settings = mail()?.takeIf { it.isUsable } ?: return
        val source = sources(companyCode) ?: return
        val now = clock()
        val token = tokens.generate()
        val address = clientAddress.ifBlank { "desconocida" }.take(64)
        val due = now.plus(wait)
        val started = source.connection.use { connection ->
            if (!available(connection) || !twoFactor.isEnabled(connection, userId)) return
            val account = account(connection, userId, companyCode) ?: return
            val email = account.email?.takeIf { UserProfileRules.validEmail(it) } ?: return
            val recent = connection.prepareStatement("SELECT count(*) FROM $Table WHERE user_id = ? AND started_at > ?").use { q ->
                q.setObject(1, userId); q.setObject(2, utc(now.minus(StartWindow)))
                q.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            if (recent >= MaxStartsPerWindow) return
            val id = UUID.randomUUID()
            try {
                transaction(connection) {
                    connection.prepareStatement(
                        """
                        INSERT INTO $Table (id, user_id, status, email, started_at, started_from, code_due_at, cancel_token_hash, notice_base)
                        VALUES (?, ?, 'pending', ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { insert ->
                        insert.setObject(1, id); insert.setObject(2, userId); insert.setString(3, email)
                        insert.setObject(4, utc(now)); insert.setString(5, address); insert.setObject(6, utc(due)); insert.setString(7, token.hash)
                        insert.setString(8, publicBase)
                        insert.executeUpdate()
                    }
                    record(connection, companyCode, account, bySystem = false, "email_recovery_started",
                        "Inicio la recuperacion del acceso por correo desde $address; el codigo se enviara el ${stamp.format(due)}.",
                        mapOf("address" to address, "codeDueAt" to due.toString()))
                }
            } catch (failure: SQLException) {
                // Another start won the race for the one open process: same silent answer.
                if (failure.sqlState == "23505") return
                throw failure
            }
            Triple(account, email, language(connection, companyCode, userId)) to id
        }
        val (who, id) = started
        val (account, email, language) = who
        if (sendWarning(settings, email, language, account.username, companyCode, now, due, address, publicBase, token.value)) {
            source.connection.use { connection -> warningMailed(connection, id, token.hash) }
        }
        runCatching {
            I18n.withLanguage(language) {
                notify(companyCode, userId, tr("Recuperacion del acceso iniciada"), tr("Alguien inicio la recuperacion del acceso por correo. Si no fuiste tu, cancelala en Verificacion en dos pasos."))
            }
        }.onFailure { log.warn("Recovery start notification for {} failed", account.username, it) }
    }

    // The first email: when, from where, when the code comes, and the cancel link (or where to cancel without one).
    // False when it could not be sent (logged; the job sends it again with a new link).
    private fun sendWarning(settings: SmtpSettings, email: String, language: String, username: String, companyCode: String?, startedAt: Instant, due: Instant, address: String, publicBase: String?, token: String): Boolean {
        val link = publicBase?.let { base -> base.trimEnd('/') + "/auth/two-factor/recovery-cancel?t=" + token + (companyCode?.let { "&c=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: "") }
        return try {
            mailTo(settings, email, language) {
                tr("Se inicio la recuperacion del acceso a tu cuenta de BMS") to (
                    tr("Hola {0}:", username) + "\n\n" +
                        tr("El {0}, desde la direccion {1}, alguien que conoce tu contrasena inicio la recuperacion del acceso a tu cuenta {2} porque no puede usar la verificacion en dos pasos.", stamp.format(startedAt), address, username) + "\n\n" +
                        tr("El {0} enviaremos a este correo un codigo que permite entrar una vez y desactiva la verificacion en dos pasos.", stamp.format(due)) + "\n\n" +
                        (if (link != null) tr("Si no fuiste tu, cancela la recuperacion con este enlace y cambia tu contrasena:") + "\n\n    " + link + "\n"
                        else tr("Si no fuiste tu, inicia sesion en la app y cancela la recuperacion en tu cuenta, seccion Verificacion en dos pasos, y cambia tu contrasena.") + "\n")
                    )
            }
            true
        } catch (failure: Exception) {
            log.error("Recovery start notice for {} could not be mailed; it is sent again later", username, failure)
            false
        }
    }

    private fun warningMailed(connection: Connection, id: UUID, tokenHash: String) {
        connection.prepareStatement("UPDATE $Table SET warning_mailed_at = ? WHERE id = ? AND cancel_token_hash = ? AND warning_mailed_at IS NULL").use { u ->
            u.setObject(1, utc(clock())); u.setObject(2, id); u.setString(3, tokenHash); u.executeUpdate()
        }
    }

    /** The open process of the account, for GET /auth/two-factor. */
    fun status(connection: Connection, userId: UUID): EmailRecoveryStatus {
        if (!available(connection)) return EmailRecoveryStatus(false, null)
        val email = connection.prepareStatement("SELECT email FROM application_users WHERE id = ?").use { q ->
            q.setObject(1, userId); q.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
        val available = offered() && twoFactor.isEnabled(connection, userId) && email?.let { UserProfileRules.validEmail(it) } == true
        val pending = connection.prepareStatement(
            "SELECT started_at, started_from, code_due_at, status, code_expires_at, code_mailed_at FROM $Table WHERE user_id = ? AND status IN $Open"
        ).use { q ->
            q.setObject(1, userId)
            q.executeQuery().use { rows ->
                if (!rows.next()) null else {
                    val sent = rows.getString(4) == "code_sent" && rows.getObject(6) != null
                    EmailRecoveryPending(
                        rows.getObject(1, OffsetDateTime::class.java).toInstant().toString(), rows.getString(2),
                        rows.getObject(3, OffsetDateTime::class.java).toInstant().toString(), sent,
                        if (sent) rows.getObject(5, OffsetDateTime::class.java)?.toInstant()?.toString() else null,
                    )
                }
            }
        }
        return EmailRecoveryStatus(available, pending)
    }

    /**
     * Cancels the open process of [userId] ([reason]: user, sign_in, email_change, disabled) and records it.
     * True when there was one. [notifyByMail] tells the account (a sign-in that cancelled it).
     */
    fun cancelOpen(connection: Connection, companyCode: String?, userId: UUID, reason: String, notifyByMail: Boolean = false): Boolean {
        if (!available(connection)) return false
        val account = account(connection, userId, companyCode) ?: return false
        val cancelled = transaction(connection) {
            val done = connection.prepareStatement(
                "UPDATE $Table SET status = 'cancelled', ended_at = ?, end_reason = ?, code_hash = NULL WHERE user_id = ? AND status IN $Open"
            ).use { update ->
                update.setObject(1, utc(clock())); update.setString(2, reason); update.setObject(3, userId)
                update.executeUpdate() > 0
            }
            if (done) {
                val summary = when (reason) {
                    "sign_in" -> "Se cancelo la recuperacion del acceso por correo: la cuenta inicio sesion con la verificacion en dos pasos."
                    "email_change" -> "Se cancelo la recuperacion del acceso por correo: cambio el correo de la cuenta."
                    "disabled" -> "Se cancelo la recuperacion del acceso por correo: se desactivo la verificacion en dos pasos."
                    else -> "Cancelo la recuperacion del acceso por correo."
                }
                record(connection, companyCode, account, bySystem = reason != "user", "email_recovery_cancelled", summary, mapOf("by" to reason))
            }
            done
        }
        if (cancelled && notifyByMail) {
            val settings = mail()?.takeIf { it.isUsable }
            val email = account.email?.takeIf { UserProfileRules.validEmail(it) }
            if (settings != null && email != null) {
                runCatching {
                    mailTo(settings, email, language(connection, companyCode, userId)) {
                        tr("Se cancelo la recuperacion del acceso a tu cuenta de BMS") to
                            (tr("Hola {0}:", account.username) + "\n\n" + tr("La recuperacion del acceso por correo que estaba en curso se cancelo porque la cuenta inicio sesion con la verificacion en dos pasos.") + "\n")
                    }
                }.onFailure { log.warn("Recovery cancel notice for {} could not be mailed", account.username, it) }
            }
        }
        return cancelled
    }

    /** Whether the cancel link [token] of [companyCode] still cancels something, and the account's language. */
    fun linkTarget(companyCode: String?, token: String): String? {
        if (!TokenPattern.matches(token) || (companyCode != null && !CompanyPattern.matches(companyCode))) return null
        val source = sources(companyCode) ?: return null
        return source.connection.use { connection ->
            if (!available(connection)) return null
            connection.prepareStatement("SELECT user_id FROM $Table WHERE cancel_token_hash = ? AND status IN $Open").use { q ->
                q.setString(1, tokens.hash(token))
                q.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
            }?.let { language(connection, companyCode, it) }
        }
    }

    /** Cancels by the link of the first email (single use: the process ends). True when it cancelled one. */
    fun cancelByLink(companyCode: String?, token: String): Boolean {
        if (!TokenPattern.matches(token) || (companyCode != null && !CompanyPattern.matches(companyCode))) return false
        val source = sources(companyCode) ?: return false
        return source.connection.use { connection ->
            if (!available(connection)) return false
            transaction(connection) {
                val userId = connection.prepareStatement(
                    "UPDATE $Table SET status = 'cancelled', ended_at = ?, end_reason = 'link', code_hash = NULL WHERE cancel_token_hash = ? AND status IN $Open RETURNING user_id"
                ).use { update ->
                    update.setObject(1, utc(clock())); update.setString(2, tokens.hash(token))
                    update.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
                } ?: return@transaction false
                val account = account(connection, userId, companyCode) ?: Account(userId, userId.toString(), null, companyCode == null, false)
                record(connection, companyCode, account, bySystem = false, "email_recovery_cancelled", "Cancelo la recuperacion del acceso por correo con el enlace del aviso.", mapOf("by" to "link"))
                true
            }
        }
    }

    /**
     * Checks the emailed [code] of [userId] (TWO_FACTOR.md §7.4). On success, in one transaction: the
     * process completes and two-step verification is turned off (secret and recovery codes deleted).
     */
    fun complete(companyCode: String?, connection: Connection, userId: UUID, code: String): EmailRecoveryCheck {
        if (!available(connection)) return EmailRecoveryCheck.Invalid
        val now = clock()
        val row = connection.prepareStatement("SELECT id, email, code_hash, code_expires_at FROM $Table WHERE user_id = ? AND status = 'code_sent'").use { q ->
            q.setObject(1, userId)
            q.executeQuery().use { rows ->
                if (rows.next()) listOf(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getObject(4, OffsetDateTime::class.java)) else null
            }
        } ?: return EmailRecoveryCheck.Invalid
        val id = row[0] as UUID
        val account = account(connection, userId, companyCode) ?: return EmailRecoveryCheck.Invalid
        if (!sameEmail(account.email, row[1] as String)) {
            cancelOpen(connection, companyCode, userId, "email_change")
            return EmailRecoveryCheck.Invalid
        }
        val expires = (row[3] as OffsetDateTime?)?.toInstant()
        if (expires == null || !expires.isAfter(now)) {
            expire(connection, companyCode, id, account)
            return EmailRecoveryCheck.Invalid
        }
        if (!claimAttempt(connection, id, now)) return EmailRecoveryCheck.Throttled
        val normalized = normalizeCode(code)
        val given = codeHash(id, normalized ?: "AAAAAAAAAAAA").toByteArray(Charsets.US_ASCII)
        val stored = (row[2] as String?)?.trim()?.toByteArray(Charsets.US_ASCII) ?: ByteArray(64)
        if (!MessageDigest.isEqual(stored, given) || normalized == null) return EmailRecoveryCheck.Invalid
        return transaction(connection) {
            val done = connection.prepareStatement("UPDATE $Table SET status = 'completed', ended_at = ?, end_reason = 'code', code_hash = NULL WHERE id = ? AND status = 'code_sent'").use { u ->
                u.setObject(1, utc(now)); u.setObject(2, id); u.executeUpdate() == 1
            }
            if (!done) return@transaction EmailRecoveryCheck.Invalid
            connection.prepareStatement("DELETE FROM application_user_recovery_codes WHERE user_id = ?").use { it.setObject(1, userId); it.executeUpdate() }
            connection.prepareStatement("DELETE FROM application_user_two_factor WHERE user_id = ?").use { it.setObject(1, userId); it.executeUpdate() }
            record(connection, companyCode, account, bySystem = false, "email_recovery_completed",
                "Recupero el acceso con el codigo enviado a su correo; se desactivo la verificacion en dos pasos.")
            EmailRecoveryCheck.Completed
        }
    }

    private fun sameEmail(current: String?, atStart: String): Boolean = current?.trim()?.lowercase() == atStart.trim().lowercase()

    private fun claimAttempt(connection: Connection, id: UUID, now: Instant): Boolean {
        val expired = utc(now.minus(FailureWindow))
        return connection.prepareStatement(
            """
            UPDATE $Table SET
                failed_attempts = CASE WHEN failure_window_start IS NULL OR failure_window_start <= ? THEN 1 ELSE failed_attempts + 1 END,
                failure_window_start = CASE WHEN failure_window_start IS NULL OR failure_window_start <= ? THEN ? ELSE failure_window_start END
            WHERE id = ? AND (failure_window_start IS NULL OR failure_window_start <= ? OR failed_attempts < ?)
            RETURNING failed_attempts
            """.trimIndent()
        ).use { u ->
            u.setObject(1, expired); u.setObject(2, expired); u.setObject(3, utc(now)); u.setObject(4, id); u.setObject(5, expired); u.setInt(6, MaxFailures)
            u.executeQuery().use { it.next() }
        }
    }

    private fun codeHash(id: UUID, normalized: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest("bms-2fa-email-recovery-v1:$id:$normalized".toByteArray(Charsets.UTF_8)),
    )

    private fun newCode(): String {
        val chars = CharArray(12) { Alphabet[random.nextInt(Alphabet.length)] }
        return String(chars, 0, 4) + "-" + String(chars, 4, 4) + "-" + String(chars, 8, 4)
    }

    private fun expire(connection: Connection, companyCode: String?, id: UUID, account: Account) {
        transaction(connection) {
            val done = connection.prepareStatement("UPDATE $Table SET status = 'expired', ended_at = ?, end_reason = 'expired', code_hash = NULL WHERE id = ? AND status IN $Open").use { u ->
                u.setObject(1, utc(clock())); u.setObject(2, id); u.executeUpdate() == 1
            }
            if (done) record(connection, companyCode, account, bySystem = true, "email_recovery_expired", "Vencio sin usarse el codigo de recuperacion del acceso por correo.")
        }
    }

    /**
     * The periodic job (every few minutes, any number of times, also after a restart mid-run): for every
     * database, issues the code of each due process with one claiming UPDATE (two runs never leave two
     * valid codes: a re-issue replaces the code), mails it, and expires unused ones. Returns codes mailed.
     */
    // Warnings that could not be mailed at the start: sent again, each time with a new cancel link (only the hash of
    // a token is kept, so the old link cannot be repeated; the new one replaces it).
    private fun resendWarnings(connection: Connection, companyCode: String?, settings: SmtpSettings, now: Instant) {
        val rows = try {
            connection.prepareStatement(
                "SELECT id, user_id, email, started_at, started_from, code_due_at, notice_base, cancel_token_hash FROM $Table WHERE status IN $Open AND warning_mailed_at IS NULL AND started_at <= ?"
            ).use { q ->
                q.setObject(1, utc(now.minus(ResendAfter)))
                q.executeQuery().use { r ->
                    buildList<UnsentWarning> {
                        while (r.next()) add(
                            UnsentWarning(
                                r.getObject(1, UUID::class.java), r.getObject(2, UUID::class.java), r.getString(3),
                                r.getObject(4, OffsetDateTime::class.java).toInstant(), r.getString(5),
                                r.getObject(6, OffsetDateTime::class.java).toInstant(), r.getString(7), r.getString(8),
                            ),
                        )
                    }
                }
            }
        } catch (failure: SQLException) {
            if (failure.sqlState == "42703") return else throw failure
        }
        for (row in rows) {
            val token = tokens.generate()
            val claimed = connection.prepareStatement("UPDATE $Table SET cancel_token_hash = ? WHERE id = ? AND cancel_token_hash = ? AND warning_mailed_at IS NULL").use { u ->
                u.setString(1, token.hash); u.setObject(2, row.id); u.setString(3, row.tokenHash); u.executeUpdate() == 1
            }
            if (!claimed) continue
            val account = account(connection, row.userId, companyCode) ?: continue
            val language = language(connection, companyCode, row.userId)
            if (sendWarning(settings, row.email, language, account.username, companyCode, row.startedAt, row.due, row.address, row.base, token.value)) {
                warningMailed(connection, row.id, token.hash)
            }
        }
    }

    private class UnsentWarning(val id: UUID, val userId: UUID, val email: String, val startedAt: Instant, val address: String, val due: Instant, val base: String?, val tokenHash: String)

    fun runDue(): Int {
        val settings = mail()?.takeIf { it.isUsable } ?: return 0
        var mailed = 0
        (listOf<String?>(null) + companies()).forEach { companyCode ->
            try {
                val source = sources(companyCode) ?: return@forEach
                source.connection.use { connection ->
                    if (available(connection)) mailed += runDueIn(connection, companyCode, settings)
                }
            } catch (failure: Exception) {
                log.error("Two-step email recovery job failed for {}", companyCode ?: "the server", failure)
            }
        }
        return mailed
    }

    private fun runDueIn(connection: Connection, companyCode: String?, settings: SmtpSettings): Int {
        val now = clock()
        resendWarnings(connection, companyCode, settings, now)
        // Unused codes and their processes end.
        connection.prepareStatement("SELECT id, user_id FROM $Table WHERE status = 'code_sent' AND code_expires_at <= ?").use { q ->
            q.setObject(1, utc(now)); q.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.getObject(2, UUID::class.java)) } }
        }.forEach { (id, userId) -> account(connection, userId, companyCode)?.let { expire(connection, companyCode, id, it) } }
        val due = connection.prepareStatement(
            """
            SELECT id, user_id, email FROM $Table
            WHERE (status = 'pending' AND code_due_at <= ?) OR (status = 'code_sent' AND code_mailed_at IS NULL AND code_issued_at <= ?)
            """.trimIndent()
        ).use { q ->
            q.setObject(1, utc(now)); q.setObject(2, utc(now.minus(ResendAfter)))
            q.executeQuery().use { rows -> buildList { while (rows.next()) add(Triple(rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getString(3))) } }
        }
        var mailed = 0
        for ((id, userId, emailAtStart) in due) {
            val account = account(connection, userId, companyCode) ?: continue
            if (!sameEmail(account.email, emailAtStart) || account.email?.let { UserProfileRules.validEmail(it) } != true) {
                cancelOpen(connection, companyCode, userId, "email_change")
                continue
            }
            val code = newCode()
            val issued = connection.prepareStatement(
                """
                UPDATE $Table SET status = 'code_sent', code_hash = ?, code_issued_at = ?, code_expires_at = ?, code_mailed_at = NULL,
                    failed_attempts = 0, failure_window_start = NULL
                WHERE id = ? AND ((status = 'pending' AND code_due_at <= ?) OR (status = 'code_sent' AND code_mailed_at IS NULL AND code_issued_at <= ?))
                """.trimIndent()
            ).use { u ->
                u.setString(1, codeHash(id, normalizeCode(code)!!)); u.setObject(2, utc(now)); u.setObject(3, utc(now.plus(CodeLifetime)))
                u.setObject(4, id); u.setObject(5, utc(now)); u.setObject(6, utc(now.minus(ResendAfter)))
                u.executeUpdate() == 1
            }
            // Another run claimed it first: that run mails its own code.
            if (!issued) continue
            val language = language(connection, companyCode, userId)
            try {
                mailTo(settings, account.email!!, language) {
                    tr("Tu codigo para recuperar el acceso a BMS") to (
                        tr("Hola {0}:", account.username) + "\n\n" +
                            tr("Este es el codigo para recuperar el acceso a la cuenta {0}. Escribelo en la app al iniciar sesion, en la verificacion en dos pasos, opcion \"Tengo el codigo del correo\":", account.username) +
                            "\n\n    " + code + "\n\n" +
                            tr("Vence en {0} horas y sirve una vez. Al usarlo se desactiva la verificacion en dos pasos y tendras que configurarla de nuevo. Si no pediste esta recuperacion, cambia tu contrasena.", CodeLifetime.toHours()) + "\n"
                        )
                }
            } catch (failure: Exception) {
                // Not mailed: a later run issues a new code (this one stops working then).
                log.warn("Recovery code for {} could not be mailed; it is issued again later", account.username, failure)
                continue
            }
            transaction(connection) {
                val first = connection.prepareStatement("UPDATE $Table SET code_mailed_at = ? WHERE id = ? AND code_hash = ? AND code_mailed_at IS NULL").use { u ->
                    u.setObject(1, utc(clock())); u.setObject(2, id); u.setString(3, codeHash(id, normalizeCode(code)!!)); u.executeUpdate() == 1
                }
                if (first) record(connection, companyCode, account, bySystem = true, "email_recovery_code_sent", "Se envio al correo de la cuenta el codigo de recuperacion del acceso.")
            }
            mailed++
        }
        return mailed
    }
}
