package com.detox.app.service

import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
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
    fun `soft mode is never touched by the permission capture path`() {
        assertFalse(isSoloHardPermissionFailEligible(challenge("soft1", ChallengeMode.SOFT)))
    }

    // ── Deadline notification selection ──────────────────────────────────────────
    // The capture path above is SOLO-HARD-ONLY, but the 24h notification used to be posted
    // unconditionally as "❌ Challenge failed — your stake has been charged". These pin the
    // notice to what actually happened to each challenge type.

    @Test
    fun `soft-only user is told enforcement stopped, never that the challenge failed`() {
        val notices = permissionDeadlineNotices(listOf(challenge("soft1", ChallengeMode.SOFT)))
        assertTrue(notices.softPaused)
        // The lie this fix exists to remove: nothing was failed and there is no stake to charge.
        assertFalse(notices.hardFailed)
    }

    @Test
    fun `hard-only user keeps the failed-and-charged notice`() {
        val notices = permissionDeadlineNotices(listOf(challenge("hard1", ChallengeMode.HARD)))
        assertTrue(notices.hardFailed)
        assertFalse(notices.softPaused)
    }

    @Test
    fun `a user holding both gets both notices - fixing soft must not mute hard`() {
        val notices = permissionDeadlineNotices(
            listOf(
                challenge("hard1", ChallengeMode.HARD),
                challenge("soft1", ChallengeMode.SOFT),
            )
        )
        assertTrue(notices.hardFailed)
        assertTrue(notices.softPaused)
    }

    @Test
    fun `no active challenge means no notice at all`() {
        val notices = permissionDeadlineNotices(emptyList())
        assertFalse(notices.hardFailed)
        assertFalse(notices.softPaused)
    }

    @Test
    fun `group shadow rows trigger neither notice`() {
        val notices = permissionDeadlineNotices(
            listOf(
                challenge("group_g_1", ChallengeMode.HARD, groupChallengeId = "g_1"),
                challenge("group_g_2", ChallengeMode.SOFT, groupChallengeId = "g_2"),
            )
        )
        assertFalse(notices.hardFailed)
        assertFalse(notices.softPaused)
    }
}
