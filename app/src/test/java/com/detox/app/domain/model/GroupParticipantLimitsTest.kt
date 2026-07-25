package com.detox.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the step-4 "most you could win" figure to the settlement Cloud Function's maths
 * (`completeGroupChallenge`): own stake back at 80%, plus the failed participants' pot minus
 * a 10% app fee, taken whole by a sole winner.
 *
 * If `completeGroupChallenge` ever changes its percentages, these assertions fail — which is
 * the point: the number shown before payment must be one the payout code can actually produce.
 */
class GroupParticipantLimitsTest {

    @Test
    fun `full lobby at the ten euro buy-in`() {
        // stake 1000: refund floor(800) + pot 19*1000 - floor(1900) = 800 + 17100
        assertEquals(17_900, maxPossibleWinCents(stakeCents = 1_000, maxParticipants = 20))
    }

    @Test
    fun `smallest offered cap`() {
        // stake 1000: 800 + (2*1000 - 200) = 800 + 1800
        assertEquals(2_600, maxPossibleWinCents(stakeCents = 1_000, maxParticipants = 3))
    }

    @Test
    fun `hard minimum cap is a duel`() {
        // stake 1000: 800 + (1000 - 100)
        assertEquals(1_700, maxPossibleWinCents(stakeCents = 1_000, maxParticipants = 2))
    }

    @Test
    fun `full lobby at the fifty euro buy-in`() {
        // stake 5000: 4000 + (95000 - 9500)
        assertEquals(89_500, maxPossibleWinCents(stakeCents = 5_000, maxParticipants = 20))
    }

    @Test
    fun `fees always round down, never in the user's favour`() {
        // 3 players at €33.33 → failedPot 6666, fee floor(666.6) = 666, refund floor(2666.4) = 2666
        assertEquals(2_666 + 6_000, maxPossibleWinCents(stakeCents = 3_333, maxParticipants = 3))
    }

    @Test
    fun `a lone participant can win nothing beyond their own reduced stake`() {
        assertEquals(800, maxPossibleWinCents(stakeCents = 1_000, maxParticipants = 1))
    }

    @Test
    fun `picker range respects the hard floor and the server cap`() {
        assertEquals(GroupParticipantLimits.PICKER_MIN, GroupParticipantLimits.PICKER_VALUES.first())
        assertEquals(GroupParticipantLimits.MAX, GroupParticipantLimits.PICKER_VALUES.last())
        assertTrue(GroupParticipantLimits.PICKER_MIN >= GroupParticipantLimits.HARD_MIN)
        assertEquals(GroupParticipantLimits.MAX, GroupParticipantLimits.DEFAULT)
    }

    @Test
    fun `a bigger cap is always worth at least as much`() {
        val wins = GroupParticipantLimits.PICKER_VALUES.map { maxPossibleWinCents(1_000, it) }
        assertEquals(wins.sorted(), wins)
    }
}
