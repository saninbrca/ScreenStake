package com.detox.app.domain.model

import com.detox.app.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the copy boundary to the server's `capture_method` branch
 * (`createPaymentIntent`: `isGroupChallenge ? false : durationDays > 7`).
 *
 * The failure this guards against is silent: a wrong band tells the user their money is safe when
 * it has already left the card, or promises a charge on breach that happened days earlier.
 */
class StakeCaptureTest {

    // ── isImmediateCapture: the 7/8-day boundary ────────────────────────────────

    @Test
    fun `exactly 7 days is still a hold, captured only on a breach`() {
        assertFalse(StakeCapture.isImmediateCapture(7))
    }

    @Test
    fun `8 days is charged at creation`() {
        assertTrue(StakeCapture.isImmediateCapture(8))
    }

    @Test
    fun `group buy-ins are never immediate capture, however long the challenge`() {
        assertFalse(StakeCapture.isImmediateCapture(90, isGroupChallenge = true))
    }

    // ── isStakeChargedWhileActive ───────────────────────────────────────────────

    @Test
    fun `an active group buy-in is already charged - startGroupChallenge captured it`() {
        assertTrue(StakeCapture.isStakeChargedWhileActive(durationDays = 3, isGroupChallenge = true))
    }

    @Test
    fun `an active short solo stake has not been charged yet`() {
        assertFalse(StakeCapture.isStakeChargedWhileActive(durationDays = 7, isGroupChallenge = false))
    }

    // ── durationDaysOf: exact inverse of DateUtils#endOfDayMillis ───────────────

    @Test
    fun `durationDaysOf recovers the duration that was sent to createPaymentIntent`() {
        val start = System.currentTimeMillis()
        for (days in 1..90) {
            assertEquals(
                "durationDays round-trip broke at $days",
                days,
                StakeCapture.durationDaysOf(start, DateUtils.endOfDayMillis(start, days)),
            )
        }
    }

    @Test
    fun `the recovered duration keeps the band on both sides of the boundary`() {
        val start = System.currentTimeMillis()
        val sevenDay = DateUtils.endOfDayMillis(start, 7)
        val eightDay = DateUtils.endOfDayMillis(start, 8)
        assertFalse(StakeCapture.isImmediateCapture(StakeCapture.durationDaysOf(start, sevenDay)))
        assertTrue(StakeCapture.isImmediateCapture(StakeCapture.durationDaysOf(start, eightDay)))
    }

    @Test
    fun `a missing or nonsensical date span never claims the stake was charged`() {
        assertFalse(StakeCapture.isImmediateCapture(StakeCapture.durationDaysOf(0L, 0L)))
        assertFalse(StakeCapture.isImmediateCapture(StakeCapture.durationDaysOf(2_000L, 1_000L)))
    }
}
