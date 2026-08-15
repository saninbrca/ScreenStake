package com.finite.focus.util

import com.finite.focus.domain.model.ChallengeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [isUnobservedSoftChallenge] — the predicate that decides whether a soft challenge gets a
 * real settlement verdict or the neutral ENDED_UNVERIFIED outcome.
 *
 * Two failure directions matter and they are NOT symmetric:
 *  - Too eager ⇒ a legitimate winner is denied their win. Covered by the "same install" and
 *    "logout/login" cases.
 *  - Too lax ⇒ the original bug returns: a breached challenge is celebrated after a reinstall.
 *    Covered by [reinstallAfterEnd_isUnobserved].
 */
class SettlementObservabilityTest {

    private val day = 86_400_000L
    private val start = 1_800_000_000_000L
    private val end = start + 10 * day

    private fun check(
        mode: ChallengeMode = ChallengeMode.SOFT,
        pi: String? = null,
        groupId: String? = null,
        startDate: Long = start,
        endDate: Long = end,
        installedAt: Long,
    ) = isUnobservedSoftChallenge(mode, pi, groupId, startDate, endDate, installedAt)

    // ── The bug this exists to stop ──────────────────────────────────────────────

    @Test
    fun reinstallAfterEnd_isUnobserved() {
        // Installed a day AFTER the challenge already ended: nothing on this device saw the window.
        assertTrue(check(installedAt = end + day))
    }

    @Test
    fun installedExactlyAtEnd_isNotUnobserved() {
        // Strictly `>`: an install at the very last millisecond still overlaps the window.
        assertFalse(check(installedAt = end))
    }

    // ── Must NOT fire: legitimate runs keep their real verdict ───────────────────

    @Test
    fun installedBeforeStart_settlesNormally() {
        assertFalse(check(installedAt = start - day))
    }

    @Test
    fun installedMidChallenge_settlesNormally() {
        assertFalse(check(installedAt = start + 5 * day))
    }

    @Test
    fun logoutLoginOnSameInstall_settlesNormally() {
        // Logout clears Room (invariant #23) but does NOT change firstInstallTime. The user is on
        // the phone that tracked the whole challenge, so their real verdict must survive — this is
        // the case that a naive "Room is empty" trigger would have broken.
        assertFalse(check(installedAt = start - 30 * day))
    }

    // ── Money fence ──────────────────────────────────────────────────────────────

    @Test
    fun hardMode_neverUnobserved() {
        assertFalse(check(mode = ChallengeMode.HARD, installedAt = end + day))
    }

    @Test
    fun challengeWithPaymentIntent_neverUnobserved() {
        assertFalse(check(pi = "pi_123", installedAt = end + day))
    }

    @Test
    fun groupShadowRow_neverUnobserved() {
        assertFalse(check(groupId = "group_abc", installedAt = end + day))
    }

    // ── Fail-safe: uncertainty settles normally, never "unverified" ──────────────

    @Test
    fun unknownInstallTime_settlesNormally() {
        assertFalse(check(installedAt = 0L))
    }

    @Test
    fun zeroEndDate_settlesNormally() {
        assertFalse(check(endDate = 0L, installedAt = end + day))
    }

    @Test
    fun zeroStartDate_settlesNormally() {
        assertFalse(check(startDate = 0L, installedAt = end + day))
    }

    @Test
    fun openEndedChallenge_neverUnobserved() {
        // The ~100-year sentinel never "ends", so it can never be already-over at install time.
        val openEnd = start + DateUtils.NO_END_DATE_DAYS * day
        assertFalse(check(endDate = openEnd, installedAt = start + 400 * day))
    }
}
