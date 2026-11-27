package com.ideasdeveloper.bms.server.auth.twofactor

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.security.SecretBox
import com.ideasdeveloper.bms.server.security.SecretDecryptionException
import com.ideasdeveloper.bms.server.security.SecretsUnavailableException
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A refusal of a two-step verification operation; [code] is the contract's error code (TWO_FACTOR.md). */
class TwoFactorException(val code: String, message: String) : Exception(message) {
    companion object {
        const val Unavailable = "TWO_FACTOR_UNAVAILABLE"
        const val InvalidCode = "TWO_FACTOR_INVALID_CODE"
        const val TooManyAttempts = "TWO_FACTOR_TOO_MANY_ATTEMPTS"
        const val AlreadyEnabled = "TWO_FACTOR_ALREADY_ENABLED"
        const val NotPending = "TWO_FACTOR_NOT_PENDING"
        const val NotEnabled = "TWO_FACTOR_NOT_ENABLED"
        const val RequiredByServer = "TWO_FACTOR_REQUIRED_BY_SERVER"

        fun unavailable() = TwoFactorException(Unavailable, tr("La verificacion en dos pasos no esta disponible en este servidor."))
        fun invalidCode() = TwoFactorException(InvalidCode, tr("El codigo no es valido."))
        fun tooManyAttempts() = TwoFactorException(TooManyAttempts, tr("Demasiados codigos incorrectos. Espera unos minutos e intentalo de nuevo."))
    }
}

/** Result of checking a code typed by the user. */
sealed interface CodeCheck {
    data object Authenticator : CodeCheck
    data class Recovery(val left: Int) : CodeCheck
    data object Invalid : CodeCheck
    data object Throttled : CodeCheck
}

/** State of an account, as GET /auth/two-factor shows it. */
data class TwoFactorState(val enabled: Boolean, val pending: Boolean, val enabledAt: OffsetDateTime?, val recoveryCodesLeft: Int)

/**
 * The two-step verification rows of one database (control V017 / tenant V012) on a connection to it.
 * The TOTP secret is sealed with [secrets] (the data key of that database, wrapped with BMS_MASTER_KEY);
 * recovery codes are kept as salted hashes. Wrong codes are throttled per account in the row itself
 * ([MaxFailures] per [FailureWindow]), so a restart does not reset the count.
 */
class TwoFactorStore(private val secrets: SecretBox, private val clock: () -> Instant = Instant::now) {
    companion object {
        const val MaxFailures = 5
        val FailureWindow: Duration = Duration.ofMinutes(15)
        private const val Table = "application_user_two_factor"
    }

    private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    /** Whether this database has the tables (before the migration nobody can have it on). */
    fun available(connection: Connection): Boolean = try {
        connection.prepareStatement("SELECT to_regclass('public.$Table') IS NOT NULL AND to_regclass('public.application_user_recovery_codes') IS NOT NULL").use { query ->
            query.executeQuery().use { it.next() && it.getBoolean(1) }
        }
    } catch (_: SQLException) {
        false
    }

    /** Whether the account has it on. False without the tables. */
    fun isEnabled(connection: Connection, userId: UUID): Boolean {
        if (!available(connection)) return false
        return connection.prepareStatement("SELECT 1 FROM $Table WHERE user_id = ? AND enabled_at IS NOT NULL").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { it.next() }
        }
    }

    fun state(connection: Connection, userId: UUID): TwoFactorState {
        if (!available(connection)) return TwoFactorState(enabled = false, pending = false, enabledAt = null, recoveryCodesLeft = 0)
        val row = connection.prepareStatement("SELECT enabled_at FROM $Table WHERE user_id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) Pair(true, rows.getObject(1, OffsetDateTime::class.java)) else null }
        }
        val left = connection.prepareStatement("SELECT count(*) FROM application_user_recovery_codes WHERE user_id = ? AND used_at IS NULL").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
        val enabledAt = row?.second
        return TwoFactorState(enabled = enabledAt != null, pending = row != null && enabledAt == null, enabledAt = enabledAt, recoveryCodesLeft = if (enabledAt != null) left else 0)
    }

    /**
     * Starts (or restarts) an enrolment: a new secret, sealed, not active until [enable]. Returns the
     * secret in Base32 for the user's authenticator. Refused while it is on.
     */
    fun startSetup(connection: Connection, userId: UUID): String {
        if (!available(connection) || !secrets.available) throw TwoFactorException.unavailable()
        if (isEnabled(connection, userId)) {
            throw TwoFactorException(TwoFactorException.AlreadyEnabled, tr("La verificacion en dos pasos ya esta activa. Desactivala antes de configurarla de nuevo."))
        }
        val secret = Totp.base32(Totp.newSecret())
        val sealed = try {
            secrets.seal(connection, secret)
        } catch (_: SecretsUnavailableException) {
            throw TwoFactorException.unavailable()
        }
        connection.prepareStatement(
            """
            INSERT INTO $Table (user_id, secret_sealed, enabled_at, last_used_step, failed_attempts, failure_window_start, created_at, updated_at)
            VALUES (?, ?, NULL, 0, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id) DO UPDATE SET secret_sealed = EXCLUDED.secret_sealed, last_used_step = 0, updated_at = CURRENT_TIMESTAMP
            WHERE $Table.enabled_at IS NULL
            """.trimIndent()
        ).use { upsert ->
            upsert.setObject(1, userId)
            upsert.setString(2, sealed)
            if (upsert.executeUpdate() != 1) throw TwoFactorException(TwoFactorException.AlreadyEnabled, tr("La verificacion en dos pasos ya esta activa. Desactivala antes de configurarla de nuevo."))
        }
        return secret
    }

    /**
     * Confirms an enrolment with a code of the new secret: turns it on and returns the new recovery
     * codes (shown once). Everything in one transaction on [connection]; [inTransaction] runs before
     * the commit (activity log / audit row: if it fails nothing changes).
     */
    fun enable(connection: Connection, userId: UUID, code: String, inTransaction: (Connection) -> Unit = {}): List<String> {
        if (!available(connection)) throw TwoFactorException.unavailable()
        val pending = connection.prepareStatement("SELECT enabled_at IS NULL FROM $Table WHERE user_id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getBoolean(1) else null }
        }
        if (pending == null) throw TwoFactorException(TwoFactorException.NotPending, tr("Primero configura la verificacion en dos pasos."))
        if (!pending) throw TwoFactorException(TwoFactorException.AlreadyEnabled, tr("La verificacion en dos pasos ya esta activa. Desactivala antes de configurarla de nuevo."))
        when (check(connection, userId, code, allowRecovery = false, enabledOnly = false)) {
            CodeCheck.Authenticator -> Unit
            CodeCheck.Throttled -> throw TwoFactorException.tooManyAttempts()
            else -> throw TwoFactorException.invalidCode()
        }
        return transaction(connection) {
            connection.prepareStatement("UPDATE $Table SET enabled_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE user_id = ? AND enabled_at IS NULL").use { update ->
                update.setObject(1, userId)
                if (update.executeUpdate() != 1) throw TwoFactorException(TwoFactorException.NotPending, tr("Primero configura la verificacion en dos pasos."))
            }
            val codes = replaceRecoveryCodes(connection, userId)
            inTransaction(connection)
            codes
        }
    }

    /**
     * Starts replacing the authenticator of an account that has it on (TWO_FACTOR.md §9): a new secret is kept
     * apart (pending) until [confirmReplace]; the current one keeps working meanwhile. Returns it in Base32.
     */
    fun startReplace(connection: Connection, userId: UUID): String {
        if (!available(connection) || !secrets.available) throw TwoFactorException.unavailable()
        if (!isEnabled(connection, userId)) throw notEnabled()
        val secret = Totp.base32(Totp.newSecret())
        val sealed = try {
            secrets.seal(connection, secret)
        } catch (_: SecretsUnavailableException) {
            throw TwoFactorException.unavailable()
        }
        connection.prepareStatement("UPDATE $Table SET pending_secret_sealed = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ? AND enabled_at IS NOT NULL").use { update ->
            update.setString(1, sealed)
            update.setObject(2, userId)
            if (update.executeUpdate() != 1) throw notEnabled()
        }
        return secret
    }

    /**
     * Confirms the replacement with a code of the NEW authenticator: the new secret becomes the secret, the old
     * one and every recovery code stop working, and new recovery codes are returned; [inTransaction] runs before
     * the commit. Wrong codes count in the account's limit.
     */
    fun confirmReplace(connection: Connection, userId: UUID, code: String, inTransaction: (Connection) -> Unit = {}): List<String> {
        if (!available(connection)) throw TwoFactorException.unavailable()
        val pending = connection.prepareStatement("SELECT pending_secret_sealed FROM $Table WHERE user_id = ? AND enabled_at IS NOT NULL").use { q ->
            q.setObject(1, userId)
            q.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        } ?: throw TwoFactorException(TwoFactorException.NotPending, tr("Primero configura el nuevo autenticador."))
        if (!claimAttempt(connection, userId)) throw TwoFactorException.tooManyAttempts()
        val secret = try {
            Totp.decodeBase32(secrets.open(connection, pending))
        } catch (_: SecretsUnavailableException) {
            throw TwoFactorException.unavailable()
        } catch (_: SecretDecryptionException) {
            throw TwoFactorException.unavailable()
        }
        val step = Totp.matchingStep(secret, code, Totp.step(clock().epochSecond), 0)
        secret.fill(0)
        if (step == null) throw TwoFactorException.invalidCode()
        return transaction(connection) {
            connection.prepareStatement(
                "UPDATE $Table SET secret_sealed = pending_secret_sealed, pending_secret_sealed = NULL, last_used_step = ?, failed_attempts = 0, failure_window_start = NULL, updated_at = CURRENT_TIMESTAMP WHERE user_id = ? AND pending_secret_sealed = ?"
            ).use { update ->
                update.setLong(1, step); update.setObject(2, userId); update.setString(3, pending)
                if (update.executeUpdate() != 1) throw TwoFactorException(TwoFactorException.NotPending, tr("Primero configura el nuevo autenticador."))
            }
            val codes = replaceRecoveryCodes(connection, userId)
            inTransaction(connection)
            codes
        }
    }

    /** New recovery codes; the old ones stop working. */
    fun regenerate(connection: Connection, userId: UUID, inTransaction: (Connection) -> Unit = {}): List<String> {
        if (!isEnabled(connection, userId)) throw notEnabled()
        return transaction(connection) {
            val codes = replaceRecoveryCodes(connection, userId)
            inTransaction(connection)
            codes
        }
    }

    /** Turns it off: the secret and the recovery codes are deleted. */
    fun disable(connection: Connection, userId: UUID, inTransaction: (Connection) -> Unit = {}) {
        if (!isEnabled(connection, userId)) throw notEnabled()
        transaction(connection) {
            connection.prepareStatement("DELETE FROM application_user_recovery_codes WHERE user_id = ?").use { it.setObject(1, userId); it.executeUpdate() }
            connection.prepareStatement("DELETE FROM $Table WHERE user_id = ?").use { it.setObject(1, userId); it.executeUpdate() }
            inTransaction(connection)
        }
    }

    fun notEnabled() = TwoFactorException(TwoFactorException.NotEnabled, tr("La verificacion en dos pasos no esta activa."))

    /**
     * Checks [code] (authenticator, or with [allowRecovery] a recovery code, which is then spent) for
     * an account that has it on ([enabledOnly]) or is enrolling. Every attempt is counted first and
     * refused without comparing once [MaxFailures] were wrong in [FailureWindow]; a right code clears
     * the count. A code is accepted once: the time step it used is stored.
     */
    fun check(connection: Connection, userId: UUID, code: String, allowRecovery: Boolean, enabledOnly: Boolean = true): CodeCheck {
        if (!available(connection)) throw TwoFactorException.unavailable()
        val row = connection.prepareStatement("SELECT secret_sealed, last_used_step, enabled_at IS NOT NULL FROM $Table WHERE user_id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) Triple(rows.getString(1), rows.getLong(2), rows.getBoolean(3)) else null }
        } ?: return CodeCheck.Invalid
        if (enabledOnly && !row.third) return CodeCheck.Invalid
        if (!claimAttempt(connection, userId)) return CodeCheck.Throttled
        val recovery = if (allowRecovery) RecoveryCodes.normalize(code) else null
        val result = if (recovery != null) {
            spendRecoveryCode(connection, userId, recovery)
        } else {
            val secret = try {
                Totp.decodeBase32(secrets.open(connection, row.first))
            } catch (_: SecretsUnavailableException) {
                throw TwoFactorException.unavailable()
            } catch (_: SecretDecryptionException) {
                throw TwoFactorException.unavailable()
            }
            val step = Totp.matchingStep(secret, code, Totp.step(clock().epochSecond), row.second)
            secret.fill(0)
            if (step != null && useStep(connection, userId, step)) CodeCheck.Authenticator else CodeCheck.Invalid
        }
        if (result != CodeCheck.Invalid) clearFailures(connection, userId)
        return result
    }

    // Counts one attempt in the window (one statement: atomic between requests); false when the window is used up.
    private fun claimAttempt(connection: Connection, userId: UUID): Boolean {
        val now = clock()
        val expired = utc(now.minus(FailureWindow))
        return connection.prepareStatement(
            """
            UPDATE $Table SET
                failed_attempts = CASE WHEN failure_window_start IS NULL OR failure_window_start <= ? THEN 1 ELSE failed_attempts + 1 END,
                failure_window_start = CASE WHEN failure_window_start IS NULL OR failure_window_start <= ? THEN ? ELSE failure_window_start END
            WHERE user_id = ? AND (failure_window_start IS NULL OR failure_window_start <= ? OR failed_attempts < ?)
            RETURNING failed_attempts
            """.trimIndent()
        ).use { update ->
            update.setObject(1, expired)
            update.setObject(2, expired)
            update.setObject(3, utc(now))
            update.setObject(4, userId)
            update.setObject(5, expired)
            update.setInt(6, MaxFailures)
            update.executeQuery().use { it.next() }
        }
    }

    private fun clearFailures(connection: Connection, userId: UUID) {
        connection.prepareStatement("UPDATE $Table SET failed_attempts = 0, failure_window_start = NULL WHERE user_id = ?").use { update ->
            update.setObject(1, userId)
            update.executeUpdate()
        }
    }

    // Only one request can use a time step (and never an older one).
    private fun useStep(connection: Connection, userId: UUID, step: Long): Boolean =
        connection.prepareStatement("UPDATE $Table SET last_used_step = ? WHERE user_id = ? AND last_used_step < ?").use { update ->
            update.setLong(1, step)
            update.setObject(2, userId)
            update.setLong(3, step)
            update.executeUpdate() == 1
        }

    // Compares the hash with every unused code of the account (no early exit), then spends the one that matched.
    private fun spendRecoveryCode(connection: Connection, userId: UUID, normalized: String): CodeCheck {
        val given = RecoveryCodes.hash(userId, normalized).toByteArray(Charsets.US_ASCII)
        val stored = connection.prepareStatement("SELECT code_hash FROM application_user_recovery_codes WHERE user_id = ? AND used_at IS NULL").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1).trim()) } }
        }
        var match: String? = null
        for (hash in stored) {
            if (MessageDigest.isEqual(hash.toByteArray(Charsets.US_ASCII), given) && match == null) match = hash
        }
        val found = match ?: return CodeCheck.Invalid
        val spent = connection.prepareStatement("UPDATE application_user_recovery_codes SET used_at = CURRENT_TIMESTAMP WHERE user_id = ? AND code_hash = ? AND used_at IS NULL").use { update ->
            update.setObject(1, userId)
            update.setString(2, found)
            update.executeUpdate() == 1
        }
        if (!spent) return CodeCheck.Invalid
        return CodeCheck.Recovery(stored.size - 1)
    }

    private fun replaceRecoveryCodes(connection: Connection, userId: UUID): List<String> {
        connection.prepareStatement("DELETE FROM application_user_recovery_codes WHERE user_id = ?").use { it.setObject(1, userId); it.executeUpdate() }
        val codes = RecoveryCodes.generate()
        connection.prepareStatement("INSERT INTO application_user_recovery_codes (user_id, code_hash) VALUES (?, ?)").use { insert ->
            codes.forEach { code ->
                insert.setObject(1, userId)
                insert.setString(2, RecoveryCodes.hash(userId, RecoveryCodes.normalize(code)!!))
                insert.addBatch()
            }
            insert.executeBatch()
        }
        return codes
    }

    private fun <T> transaction(connection: Connection, block: () -> T): T {
        val previous = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            runCatching { connection.autoCommit = previous }
        }
    }
}
