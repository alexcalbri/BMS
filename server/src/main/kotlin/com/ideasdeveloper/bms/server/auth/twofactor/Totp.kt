package com.ideasdeveloper.bms.server.auth.twofactor

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30 s) and RFC 4648 Base32, without external libraries
 * (TWO_FACTOR.md §1). Comparisons do the same work whatever the input: every candidate is
 * computed and compared with [MessageDigest.isEqual], with no early exit.
 */
object Totp {
    const val Digits = 6
    const val PeriodSeconds = 30L
    const val SecretBytes = 20
    // One period before and after the current one (clock drift).
    const val Window = 1
    private const val Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val random = SecureRandom()
    private val sixDigits = Regex("[0-9]{6}")

    fun newSecret(): ByteArray = ByteArray(SecretBytes).also(random::nextBytes)

    fun step(epochSeconds: Long): Long = Math.floorDiv(epochSeconds, PeriodSeconds)

    /** The code of [secret] for time step [step] (RFC 4226 dynamic truncation). */
    fun code(secret: ByteArray, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val counter = ByteArray(8) { index -> (step ushr (8 * (7 - index))).toByte() }
        val hash = mac.doFinal(counter)
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(Digits, '0')
    }

    /**
     * The time step [code] matches within ±[Window] of [nowStep] and after [lastUsedStep] (a code
     * already used, or an older one, never works again), or null. Always computes and compares every
     * candidate of the window.
     */
    fun matchingStep(secret: ByteArray, code: String, nowStep: Long, lastUsedStep: Long): Long? {
        val typed = code.filter { !it.isWhitespace() }
        val wellFormed = sixDigits.matches(typed)
        val given = (if (wellFormed) typed else "000000").toByteArray(Charsets.US_ASCII)
        var found: Long? = null
        for (delta in -Window..Window) {
            val candidate = nowStep + delta
            val equal = MessageDigest.isEqual(code(secret, candidate).toByteArray(Charsets.US_ASCII), given)
            if (equal && wellFormed && candidate > lastUsedStep && found == null) found = candidate
        }
        return found
    }

    fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(Alphabet[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(Alphabet[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    fun decodeBase32(text: String): ByteArray {
        val clean = text.uppercase().filter { it != '=' && !it.isWhitespace() && it != '-' }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (char in clean) {
            val value = Alphabet.indexOf(char)
            require(value >= 0) { "Base32 invalido" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer ushr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /** otpauth:// URI for authenticator apps (Key Uri Format). */
    fun otpauthUri(issuer: String, account: String, secretBase32: String): String {
        fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${encode(issuer)}:${encode(account)}?secret=$secretBase32&issuer=${encode(issuer)}" +
            "&algorithm=SHA1&digits=$Digits&period=$PeriodSeconds"
    }
}

/** One-time recovery codes: XXXXX-XXXXX in Base32 (50 bits), stored only as salted SHA-256. */
object RecoveryCodes {
    const val Count = 10
    private const val Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val random = SecureRandom()
    private val shape = Regex("[A-Z2-7]{10}")

    fun generate(): List<String> = List(Count) {
        val chars = CharArray(10) { Alphabet[random.nextInt(Alphabet.length)] }
        String(chars, 0, 5) + "-" + String(chars, 5, 5)
    }

    /** The code as stored: upper case, without dash or spaces; null when it cannot be a recovery code. */
    fun normalize(code: String): String? = code.uppercase().filter { it != '-' && !it.isWhitespace() }.takeIf { shape.matches(it) }

    fun hash(userId: UUID, normalized: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest("bms-recovery-v1:$userId:$normalized".toByteArray(Charsets.UTF_8)),
    )

    /** Whether [code] looks like a recovery code (rather than six digits). */
    fun looksLikeRecovery(code: String): Boolean = normalize(code) != null
}
