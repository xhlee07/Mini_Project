package com.ctfly.app

/** Elapsed background time survives process death and cannot be bypassed by changing the clock. */
object SessionRules {
    const val STAFF_IDLE_MS = 15 * 60 * 1000L

    fun expired(
        lastWall: Long,
        lastElapsed: Long,
        lastBoot: Int,
        wall: Long,
        elapsed: Long,
        boot: Int,
    ): Boolean {
        if (lastWall <= 0 || lastElapsed < 0 || lastBoot != boot) return true
        val monotonicGap = elapsed - lastElapsed
        val wallGap = wall - lastWall
        // Reboot, clock rollback and large clock adjustments require a fresh verification.
        return monotonicGap < 0 ||
            wallGap < 0 ||
            kotlin.math.abs(wallGap - monotonicGap) > 120_000 ||
            monotonicGap >= STAFF_IDLE_MS
    }

    fun fingerprintPhone(smallestDisplayDp: Int, telephony: Boolean, foldable: Boolean): Boolean =
        smallestDisplayDp in 1 until 600 && telephony && !foldable
}
