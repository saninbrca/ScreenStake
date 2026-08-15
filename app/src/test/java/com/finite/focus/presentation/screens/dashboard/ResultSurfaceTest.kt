package com.finite.focus.presentation.screens.dashboard

import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards [resultSurfaceFor] — the mapping that decides which result surface a settled challenge
 * raises. Getting it wrong is user-visible in the worst way: a green celebration on a loss, or a red
 * loss screen on a win.
 *
 * The Dashboard queue routes every drained challenge through this, so these cases also pin down
 * which statuses are result-bearing at all.
 */
class ResultSurfaceTest {

    @Test
    fun `completed maps to the win dialog in both modes`() {
        assertEquals(
            ResultSurface.WIN,
            resultSurfaceFor(ChallengeStatus.COMPLETED, ChallengeMode.SOFT)
        )
        assertEquals(
            ResultSurface.WIN,
            resultSurfaceFor(ChallengeStatus.COMPLETED, ChallengeMode.HARD)
        )
    }

    @Test
    fun `a soft loss goes to the full-screen result, not the dialog`() {
        // Load-bearing: the soft loss is a navigation target, so the queue must stop draining after
        // it rather than stack dialogs behind a screen the user can no longer see.
        assertEquals(
            ResultSurface.SOFT_LOSS_SCREEN,
            resultSurfaceFor(ChallengeStatus.FAILED, ChallengeMode.SOFT)
        )
    }

    @Test
    fun `a hard loss goes to the red dialog`() {
        assertEquals(
            ResultSurface.HARD_LOSS,
            resultSurfaceFor(ChallengeStatus.FAILED, ChallengeMode.HARD)
        )
    }

    @Test
    fun `ended_unverified maps to the neutral surface, never the win dialog`() {
        // The whole point of ENDED_UNVERIFIED: an unobserved challenge must not be celebrated.
        val surface = resultSurfaceFor(ChallengeStatus.ENDED_UNVERIFIED, ChallengeMode.SOFT)
        assertEquals(ResultSurface.UNVERIFIED, surface)
    }

    @Test
    fun `active and group-local ended raise nothing`() {
        // ENDED is the group "awaiting settlement" state — the outcome is genuinely unknown and the
        // group screens own it, so it must never produce a solo result popup.
        assertEquals(
            ResultSurface.NONE,
            resultSurfaceFor(ChallengeStatus.ACTIVE, ChallengeMode.SOFT)
        )
        assertEquals(
            ResultSurface.NONE,
            resultSurfaceFor(ChallengeStatus.ENDED, ChallengeMode.HARD)
        )
    }
}
