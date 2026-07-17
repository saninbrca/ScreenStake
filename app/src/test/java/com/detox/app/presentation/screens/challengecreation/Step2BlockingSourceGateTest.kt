package com.detox.app.presentation.screens.challengecreation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the tab-aware Step-2 gate that prevents creating a challenge that blocks nothing.
 *
 * Tests the real production predicate [step2HasValidBlockingSource] (which
 * [ChallengeCreationViewModel.canGoNext] delegates to for step 2). Testing the extracted pure
 * function avoids constructing the full Hilt-injected, coroutine-driven ViewModel while still
 * exercising the exact logic that gates the Next/Erstellen button.
 */
class Step2BlockingSourceGateTest {

    private val noConflicts = emptyMap<String, String>()

    // ── Apps tab (activeTab == 0) ────────────────────────────────────────────────

    @Test
    fun `apps tab passes with at least one selected app`() {
        val state = ChallengeCreationState(activeTab = 0, selectedApps = setOf("com.instagram.android"))
        assertTrue(step2HasValidBlockingSource(state, noConflicts))
    }

    @Test
    fun `apps tab fails with zero apps even if domains and adult are set`() {
        // On the Apps tab, website-tab sources are not the primary block — only a selected app counts.
        val state = ChallengeCreationState(
            activeTab = 0,
            selectedApps = emptySet(),
            manualDomains = listOf("reddit.com"),
            blockAdultContent = true,
        )
        assertFalse(step2HasValidBlockingSource(state, noConflicts))
    }

    @Test
    fun `apps tab fails when the only selected app is in conflict`() {
        val state = ChallengeCreationState(activeTab = 0, selectedApps = setOf("com.tiktok"))
        val conflicts = mapOf("com.tiktok" to "TikTok")
        assertFalse(step2HasValidBlockingSource(state, conflicts))
    }

    // ── Website tab (activeTab == 1) ─────────────────────────────────────────────

    @Test
    fun `website tab passes with at least one custom domain`() {
        val state = ChallengeCreationState(activeTab = 1, manualDomains = listOf("instagram.com"))
        assertTrue(step2HasValidBlockingSource(state, noConflicts))
    }

    @Test
    fun `website tab passes with adult-only blocking and no domains (Doc A stays valid)`() {
        val state = ChallengeCreationState(
            activeTab = 1,
            manualDomains = emptyList(),
            blockAdultContent = true,
        )
        assertTrue(step2HasValidBlockingSource(state, noConflicts))
    }

    @Test
    fun `website tab FAILS when only a leftover app selection is set (Doc B repro)`() {
        // Exact Doc-B path: app selected on the Apps tab, user switches to the Website tab, adds no
        // domain, leaves adult off. selectedApps is discarded at submit for website mode, so the gate
        // must be false — a leftover app selection must NOT enable creation of a blocks-nothing doc.
        val state = ChallengeCreationState(
            activeTab = 1,
            selectedApps = setOf("com.instagram.android"),
            manualDomains = emptyList(),
            blockAdultContent = false,
        )
        assertFalse(step2HasValidBlockingSource(state, noConflicts))
    }

    @Test
    fun `website tab fails with no domain, no adult, no app`() {
        val state = ChallengeCreationState(activeTab = 1)
        assertFalse(step2HasValidBlockingSource(state, noConflicts))
    }
}
