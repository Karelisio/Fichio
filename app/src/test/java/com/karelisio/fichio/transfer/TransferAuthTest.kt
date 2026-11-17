package com.karelisio.fichio.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferAuthTest {
    private var now = 1_000_000L
    private val changes = mutableListOf<String?>()
    private val auth = TransferAuth(clock = { now }, onCodeChanged = { changes += it })

    @Test fun codeHasSixDigits() {
        repeat(50) { assertTrue(auth.newCode().matches(Regex("\\d{6}"))) }
    }

    @Test fun codeIsSingleUse() {
        val code = auth.newCode()
        val r = auth.submit(code)
        assertTrue(r is TransferAuth.Result.Success)
        assertNull(auth.currentCode())
        assertEquals(TransferAuth.Result.NoCode, auth.submit(code))
        assertNull(changes.last())
    }

    @Test fun tokenIsRandom32BytesAndValidated() {
        val t1 = (auth.submit(auth.newCode()) as TransferAuth.Result.Success).token
        val t2 = (auth.submit(auth.newCode()) as TransferAuth.Result.Success).token
        assertEquals(64, t1.length)
        assertTrue(t1.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(t1, t2)
        assertTrue(auth.isValidToken(t1))
        assertTrue(auth.isValidToken(t2))
        assertFalse(auth.isValidToken(null))
        assertFalse(auth.isValidToken(""))
        assertFalse(auth.isValidToken(t1.dropLast(1) + if (t1.last() == '0') "1" else "0"))
        assertEquals(2, auth.sessionCount())
    }

    @Test fun sessionsExpire() {
        val t = (auth.submit(auth.newCode()) as TransferAuth.Result.Success).token
        now += auth.sessionTtlMs + 1
        assertFalse(auth.isValidToken(t))
    }

    @Test fun codeExpires() {
        val code = auth.newCode()
        now += auth.codeTtlMs + 1
        assertNull(auth.currentCode())
        assertEquals(TransferAuth.Result.NoCode, auth.submit(code))
    }

    @Test fun attemptsAreLimitedThenCodeRotatesWithCooldown() {
        val code = auth.newCode()
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(auth.maxAttempts - 1) { i ->
            val r = auth.submit(wrong)
            assertEquals(TransferAuth.Result.Invalid(auth.maxAttempts - 1 - i), r)
        }
        assertEquals(TransferAuth.Result.Locked, auth.submit(wrong))
        val fresh = auth.currentCode()!!
        // Old code is dead; even the right new code is refused during the cooldown.
        assertEquals(TransferAuth.Result.Locked, auth.submit(fresh))
        now += auth.cooldownMs + 1
        if (fresh != code) assertTrue(auth.submit(code) is TransferAuth.Result.Invalid)
        assertTrue(auth.submit(fresh) is TransferAuth.Result.Success)
    }

    @Test fun malformedInputIsRejected() {
        auth.newCode()
        assertTrue(auth.submit(null) is TransferAuth.Result.Invalid)
        assertTrue(auth.submit("12") is TransferAuth.Result.Invalid)
        assertTrue(auth.submit("abcdef") is TransferAuth.Result.Invalid)
    }

    @Test fun inputWithSpacesIsAccepted() {
        val code = auth.newCode()
        assertTrue(auth.submit(code.substring(0, 3) + " " + code.substring(3)) is TransferAuth.Result.Success)
    }

    @Test fun revokeAllInvalidatesEverything() {
        val t = (auth.submit(auth.newCode()) as TransferAuth.Result.Success).token
        auth.newCode()
        auth.revokeAll()
        assertFalse(auth.isValidToken(t))
        assertNull(auth.currentCode())
    }
}
