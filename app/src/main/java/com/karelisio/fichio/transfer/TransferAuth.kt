package com.karelisio.fichio.transfer

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One-time access code + session tokens of the transfer server. Pure (clock and RNG injected).
 *
 * - The 6-digit code is valid once: its first successful use opens a session and invalidates it.
 * - After [maxAttempts] wrong codes a new code is drawn and submissions are refused for
 *   [cooldownMs] (slows brute force down to a handful of guesses per code).
 * - A code expires after [codeTtlMs]; sessions after [sessionTtlMs].
 */
class TransferAuth(
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
    val maxAttempts: Int = 5,
    val codeTtlMs: Long = 15 * 60_000L,
    val sessionTtlMs: Long = 12 * 3_600_000L,
    val cooldownMs: Long = 30_000L,
    /** Notified whenever the displayed code changes (null = no valid code). */
    private val onCodeChanged: (String?) -> Unit = {},
) {
    sealed interface Result {
        data class Success(val token: String) : Result
        data class Invalid(val attemptsLeft: Int) : Result
        /** Too many failures: a new code was drawn. */
        data object Locked : Result
        /** No valid code (already used or expired): generate a new one on the phone. */
        data object NoCode : Result
    }

    private var code: String? = null
    private var codeCreatedAt = 0L
    private var failures = 0
    private var lockedUntil = 0L
    private val sessions = HashMap<String, Long>()

    /** Current code, rotating it first if it expired. */
    @Synchronized
    fun currentCode(): String? {
        val c = code ?: return null
        if (clock() - codeCreatedAt > codeTtlMs) {
            code = null
            onCodeChanged(null)
            return null
        }
        return c
    }

    /** Draws a new 6-digit code (invalidating the previous one). */
    @Synchronized
    fun newCode(): String {
        val c = String.format("%06d", random.nextInt(1_000_000))
        code = c
        codeCreatedAt = clock()
        failures = 0
        onCodeChanged(c)
        return c
    }

    @Synchronized
    fun submit(input: String?): Result {
        val now = clock()
        if (now < lockedUntil) return Result.Locked
        val expected = currentCode() ?: return Result.NoCode
        val given = input?.filter { it.isDigit() }.orEmpty()
        if (given.length == 6 && MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())) {
            code = null
            failures = 0
            onCodeChanged(null)
            val token = newToken()
            sessions[token] = now
            return Result.Success(token)
        }
        failures++
        if (failures >= maxAttempts) {
            lockedUntil = now + cooldownMs
            newCode()
            return Result.Locked
        }
        return Result.Invalid(maxAttempts - failures)
    }

    @Synchronized
    fun isValidToken(token: String?): Boolean {
        if (token.isNullOrEmpty() || token.length != 64) return false
        val now = clock()
        sessions.entries.removeAll { now - it.value > sessionTtlMs }
        // Constant-time comparison against every live token.
        var ok = false
        for (t in sessions.keys) if (MessageDigest.isEqual(t.toByteArray(), token.toByteArray())) ok = true
        return ok
    }

    @Synchronized
    fun sessionCount(): Int {
        val now = clock()
        sessions.entries.removeAll { now - it.value > sessionTtlMs }
        return sessions.size
    }

    @Synchronized
    fun revokeAll() {
        sessions.clear()
        code = null
        onCodeChanged(null)
    }

    /** 32 random bytes, hex encoded. */
    private fun newToken(): String {
        val b = ByteArray(32).also(random::nextBytes)
        return b.joinToString("") { "%02x".format(it) }
    }
}
