package com.finite.focus.service

import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.model.LimitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the selection of `PermissionCheckWorker.failAllHardChallenges` to SOLO Hard Mode.
 *
 * The failure this guards against is silent and expensive: a group shadow row carries
 * `mode = "hard"` and the participant's BUY-IN PaymentIntent, so a mode-only filter would capture
 * the group stake while the group doc still lists the participant as active — `completeGroupChallenge`
 * would then refund them as a winner (captured stake + refund + pot share). Nothing but a
 * non-existent `users/{uid}/challenges/group_<groupId>` doc used to stand in the way.
 */
class PermissionFailSelectionTest {

    private fun challenge(
        id: String,
        mode: ChallengeMode,
        groupChallengeId: String? = null,
        stripePaymentIntentId: String? = "pi_123",
    ) = softChallenge(id, mode, groupChallengeId, stripePaymentIntentId)

    /** Soft rows are money-free: a Soft challenge never carries a PaymentIntent in practice. */
    private fun soft(id: String, groupChallengeId: String? = null) =
        softChallenge(id, ChallengeMode.SOFT, groupChallengeId, stripePaymentIntentId = null)

    private fun softChallenge(
        id: String,
        mode: ChallengeMode,
        groupChallengeId: String? = null,
        stripePaymentIntentId: String? = "pi_123",
    ) = Challenge(
        id = id,
        appPackageName = "com.tiktok",
        appPackageNames = listOf("com.tiktok"),
        appDisplayName = "TikTok",
        mode = mode,
        limitType = LimitType.TIME,
        limitValueMinutes = 60,
        limitValueSessions = null,
        startDate = 0L,
        endDate = 0L,
        amountCents = 1000,
        stripePaymentIntentId = stripePaymentIntentId,
        customMotivation = null,
        status = ChallengeStatus.ACTIVE,
        createdAt = 0L,
        groupChallengeId = groupChallengeId,
    )

    @Test
    fun `solo hard mode is still processed`() {
        assertTrue(isSoloHardPermissionFailEligible(challenge("hard1", ChallengeMode.HARD)))
    }

    @Test
    fun `group shadow row is excluded even though its mode is hard`() {
        assertFalse(
            isSoloHardPermissionFailEligible(
                // Exactly what GroupChallengeRepositoryImpl.syncToTracking writes: id "group_<groupId>",
                // mode hard, and the participant's group buy-in PaymentIntent.
                challenge("group_g_123", ChallengeMode.HARD, groupChallengeId = "g_123")
            )
        )
    }

    @Test
    fun `a group shadow row without a PaymentIntent is excluded too - no FAILED without capture`() {
        assertFalse(
            isSoloHardPermissionFailEligible(
                challenge("group_g_456", ChallengeMode.HARD, groupChallengeId = "g_456", stripePaymentIntentId = null)
            )
        )
    }

    @Test
    fun `soft mode is never touched by the permission CAPTURE path`() {
        // Soft can now be FAILED by permission loss, but never through the money path: the capture
        // branch stays solo-Hard-only, so no Soft row can ever reach capturePayment.
        assertFalse(isSoloHardPermissionFailEligible(challenge("soft1", ChallengeMode.SOFT)))
    }

    // ── Soft fail selection ──────────────────────────────────────────────────────

    @Test
    fun `solo soft mode is eligible for the money-free fail path`() {
        assertTrue(isSoloSoftPermissionFailEligible(soft("soft1")))
    }

    @Test
    fun `hard rows never enter the money-free soft path`() {
        // The money fence in the other direction: a Hard row must never be failed without a capture.
        assertFalse(isSoloSoftPermissionFailEligible(challenge("hard1", ChallengeMode.HARD)))
    }

    @Test
    fun `a soft row carrying a PaymentIntent is excluded - no FAILED without a capture`() {
        // Should not exist, but if it ever did, the money-free path must refuse it: it neither
        // captures nor consults the settlement guard, so it would record a loss on an uncaptured stake.
        assertFalse(
            isSoloSoftPermissionFailEligible(
                softChallenge("soft_with_pi", ChallengeMode.SOFT, stripePaymentIntentId = "pi_999")
            )
        )
    }

    @Test
    fun `group soft shadow rows stay with the group settlement CFs`() {
        assertFalse(isSoloSoftPermissionFailEligible(soft("group_g_3", groupChallengeId = "g_3")))
    }

    // ── Dismissal-halving exemption (Addition 2) ─────────────────────────────────
    // trackPermissionIgnore counts "opened the app while the permission was off", NOT "saw the
    // warning and refused". Halving that grace is an anti-cheat trade a Hard user accepted at
    // creation; a Soft user has no stake and may simply own a phone that killed the service.

    @Test
    fun `hard keeps the dismissal-halving`() {
        val elapsed = 4 * 3_600_000L
        val halved = effectiveDeadlineMs(elapsed, ignored = 1, hardInPlay = true)
        assertTrue("expected an accelerated deadline", halved < PERMISSION_DEADLINE_MS)
        // elapsed + (24h - elapsed)/2 = 4h + 10h = 14h
        assertEquals(14 * 3_600_000L, halved)
    }

    @Test
    fun `soft-only audience always gets the full 24h grace`() {
        assertEquals(
            PERMISSION_DEADLINE_MS,
            effectiveDeadlineMs(4 * 3_600_000L, ignored = 1, hardInPlay = false)
        )
        assertEquals(
            PERMISSION_DEADLINE_MS,
            effectiveDeadlineMs(4 * 3_600_000L, ignored = 5, hardInPlay = false)
        )
    }

    @Test
    fun `no dismissal means the full deadline for everyone`() {
        assertEquals(
            PERMISSION_DEADLINE_MS,
            effectiveDeadlineMs(4 * 3_600_000L, ignored = 0, hardInPlay = true)
        )
    }

    @Test
    fun `halving never applies past the acceleration threshold`() {
        val elapsed = PERMISSION_ACCELERATE_THRESHOLD_MS + 1
        assertEquals(PERMISSION_DEADLINE_MS, effectiveDeadlineMs(elapsed, 1, hardInPlay = true))
    }

    // ── Audience selection (drives escalation copy + the halving) ────────────────
    // The escalations used to be posted unconditionally in stake wording. These pin the audience to
    // the SAME predicates the deadline pass selects on, so a warning can never describe a
    // population the deadline does not act on.

    @Test
    fun `soft-only user is never addressed in stake wording`() {
        val audience = permissionAudience(listOf(soft("soft1")))
        assertTrue(audience.hasSoft)
        assertFalse(audience.hasHard)
    }

    @Test
    fun `hard-only user keeps the stake wording`() {
        val audience = permissionAudience(listOf(challenge("hard1", ChallengeMode.HARD)))
        assertTrue(audience.hasHard)
        assertFalse(audience.hasSoft)
    }

    @Test
    fun `a user holding both is in both audiences - fixing soft must not mute hard`() {
        val audience = permissionAudience(
            listOf(challenge("hard1", ChallengeMode.HARD), soft("soft1"))
        )
        assertTrue(audience.hasHard)
        assertTrue(audience.hasSoft)
    }

    @Test
    fun `no active challenge means nobody is warned`() {
        val audience = permissionAudience(emptyList())
        assertFalse(audience.hasHard)
        assertFalse(audience.hasSoft)
    }

    @Test
    fun `group shadow rows put nobody in either audience`() {
        val audience = permissionAudience(
            listOf(
                challenge("group_g_1", ChallengeMode.HARD, groupChallengeId = "g_1"),
                soft("group_g_2", groupChallengeId = "g_2"),
            )
        )
        assertFalse(audience.hasHard)
        assertFalse(audience.hasSoft)
    }
}
