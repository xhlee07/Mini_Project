package com.ctfly.app

import com.ctfly.app.domain.SessionRules
import org.junit.Assert.*
import org.junit.Test

class SessionRulesTest {
    private val wall = 1_800_000_000_000L
    private val elapsed = 2_000_000L

    private fun expired(gap: Long) =
        SessionRules.expired(wall, elapsed, 4, wall + gap, elapsed + gap, 4)

    @Test
    fun fifteenMinutesIsTheExactIdleBoundary() {
        assertFalse(expired(14 * 60_000 + 59_999))
        assertTrue(expired(15 * 60_000))
        assertTrue(expired(20 * 60_000))
    }

    @Test
    fun MissingCheckpointRebootAndClockChangesRequireVerification() {
        assertTrue(SessionRules.expired(0, -1, -1, wall, elapsed, 4))
        assertTrue(SessionRules.expired(wall, elapsed, 4, wall + 1000, 500, 5))
        assertTrue(SessionRules.expired(wall, elapsed, 4, wall - 1, elapsed + 1000, 4))
        assertTrue(SessionRules.expired(wall, elapsed, 4, wall + 300_000, elapsed + 1000, 4))
        assertFalse(SessionRules.expired(wall, elapsed, 4, wall + 1001, elapsed + 1000, 4))
    }

    @Test
    fun TabletFoldableAndUncertainDevicesUsePassword() {
        assertTrue(SessionRules.fingerprintPhone(393, true, false))
        assertFalse(SessionRules.fingerprintPhone(600, true, false))
        assertFalse(SessionRules.fingerprintPhone(800, false, false))
        assertFalse(SessionRules.fingerprintPhone(393, true, true))
        assertFalse(SessionRules.fingerprintPhone(393, false, false))
        assertFalse(SessionRules.fingerprintPhone(0, true, false))
    }
}
