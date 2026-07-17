package com.detox.app.presentation.screens.challengecreation

import com.detox.app.domain.model.LimitType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the per-path visible-step lists that drive wizard navigation and the
 * "Schritt X von Y" counter (see [visibleSteps]):
 *  - APP: all 7 steps; with TIME_WINDOW the step-4 value picker is skipped.
 *  - Block-only (Website tab — custom domains and/or adult): 24/7 hard block, so the
 *    minute-limit steps (3+4) AND the time-window step (5) are skipped.
 *
 * Testing the extracted pure function (like [Step2BlockingSourceGateTest]) exercises the
 * exact production logic without constructing the Hilt-injected ViewModel.
 */
class VisibleStepsTest {

    // ── APP path (activeTab == 0) ────────────────────────────────────────────────

    @Test
    fun `app path shows all seven steps`() {
        val state = ChallengeCreationState(activeTab = 0, limitType = LimitType.TIME)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), visibleSteps(state))
    }

    @Test
    fun `app path with no limit type yet shows all seven steps`() {
        val state = ChallengeCreationState(activeTab = 0, limitType = null)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), visibleSteps(state))
    }

    @Test
    fun `app path with TIME_WINDOW skips the step-4 value picker`() {
        val state = ChallengeCreationState(activeTab = 0, limitType = LimitType.TIME_WINDOW)
        assertEquals(listOf(1, 2, 3, 5, 6, 7), visibleSteps(state))
    }

    // ── Block-only paths (activeTab == 1) ────────────────────────────────────────

    @Test
    fun `website block path skips limit and schedule steps`() {
        val state = ChallengeCreationState(activeTab = 1, manualDomains = listOf("reddit.com"))
        assertEquals(listOf(1, 2, 6, 7), visibleSteps(state))
    }

    @Test
    fun `adult-only path skips limit and schedule steps`() {
        val state = ChallengeCreationState(activeTab = 1, blockAdultContent = true)
        assertEquals(listOf(1, 2, 6, 7), visibleSteps(state))
    }

    @Test
    fun `block path ignores stale limit type from an earlier app-path visit`() {
        // User picked TIME on the Apps tab, went back, switched to the Website tab: the
        // leftover limitType must not resurrect the limit steps.
        val state = ChallengeCreationState(
            activeTab = 1,
            manualDomains = listOf("reddit.com"),
            limitType = LimitType.TIME,
        )
        assertEquals(listOf(1, 2, 6, 7), visibleSteps(state))
    }

    @Test
    fun `domains plus adult combined stay on the lean block path`() {
        val state = ChallengeCreationState(
            activeTab = 1,
            manualDomains = listOf("reddit.com"),
            blockAdultContent = true,
        )
        assertEquals(listOf(1, 2, 6, 7), visibleSteps(state))
    }
}
