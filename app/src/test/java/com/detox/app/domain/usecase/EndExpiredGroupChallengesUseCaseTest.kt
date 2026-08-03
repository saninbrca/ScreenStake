package com.detox.app.domain.usecase

import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.util.DateUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Pins the offline enforcement end for group challenges.
 *
 * The bug this guards against: a group challenge whose end date had passed kept showing the overlay
 * and locked the user out of the monitored app while OFFLINE, because nothing in the enforcement
 * path consulted `endDate` — the only exit from `status = 'active'` was a server round-trip.
 *
 * The other half is just as load-bearing and is asserted explicitly below: ending enforcement must
 * settle NOTHING. [ChallengeRepository.updateChallengeStatus] is the door to `markChallengeFailed` /
 * the Firestore mirror, so this use case must never call it — only the local-only
 * [ChallengeRepository.endGroupChallengeLocally].
 */
class EndExpiredGroupChallengesUseCaseTest {

    private lateinit var challengeRepository: ChallengeRepository
    private lateinit var useCase: EndExpiredGroupChallengesUseCase

    private val day = DateUtils.MILLIS_PER_DAY
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        challengeRepository = mockk()
        coEvery { challengeRepository.endGroupChallengeLocally(any()) } returns Result.success(Unit)
        coEvery { challengeRepository.updateChallengeStatus(any(), any(), any()) } returns Result.success(Unit)
        useCase = EndExpiredGroupChallengesUseCase(challengeRepository)
    }

    private fun challenge(
        id: String,
        startDate: Long = now - 10 * day,
        endDate: Long = now - 2 * day,          // ended two days ago by default
        groupChallengeId: String? = "g_123",
        stripePaymentIntentId: String? = "pi_buyin",
    ) = Challenge(
        id = id,
        appPackageName = "com.tiktok",
        appPackageNames = listOf("com.tiktok"),
        appDisplayName = "TikTok",
        // Group shadow rows carry mode = HARD and the participant's buy-in PI — the selection here
        // must key on groupChallengeId, never on mode.
        mode = ChallengeMode.HARD,
        limitType = LimitType.TIME,
        limitValueMinutes = 60,
        limitValueSessions = null,
        startDate = startDate,
        endDate = endDate,
        amountCents = 1000,
        stripePaymentIntentId = stripePaymentIntentId,
        customMotivation = null,
        status = ChallengeStatus.ACTIVE,
        createdAt = startDate,
        groupChallengeId = groupChallengeId,
    )

    @Test
    fun `ends a group challenge whose end date has passed`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("group_g_123")))

        useCase()

        coVerify(exactly = 1) { challengeRepository.endGroupChallengeLocally("group_g_123") }
    }

    @Test
    fun `ending enforcement never settles - no updateChallengeStatus, ever`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("group_g_123")))

        useCase()

        // updateChallengeStatus is the money-adjacent path (Firestore mirror + markChallengeFailed
        // CF). Stopping the overlay must never reach it — the buy-in and the server's settlement
        // obligation are untouched by this use case.
        coVerify(exactly = 0) { challengeRepository.updateChallengeStatus(any(), any(), any()) }
    }

    @Test
    fun `still enforces on the challenge's own last day`() = runTest {
        // endDate = 23:59:59.999 TODAY (invariant #18). The final day is part of the challenge, so
        // enforcement must survive it — a `>=` day compare here would free the app at 00:00.
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("group_g_123", endDate = DateUtils.endOfDayMillis(now, 1))))

        useCase()

        coVerify(exactly = 0) { challengeRepository.endGroupChallengeLocally(any()) }
    }

    @Test
    fun `ends the moment the last day is over - the 00 30 incident`() = runTest {
        // Ended yesterday 23:59:59.999, it is now the next calendar day (the reported lock-out was
        // at 00:30). Day-key based, so the few minutes past midnight are enough.
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("group_g_123", endDate = DateUtils.endOfDayMillis(now - day, 1))))

        useCase()

        coVerify(exactly = 1) { challengeRepository.endGroupChallengeLocally("group_g_123") }
    }

    @Test
    fun `never touches a solo challenge, even one long past its end date`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("solo1", groupChallengeId = null)))

        useCase()

        coVerify(exactly = 0) { challengeRepository.endGroupChallengeLocally(any()) }
        coVerify(exactly = 0) { challengeRepository.updateChallengeStatus(any(), any(), any()) }
    }

    @Test
    fun `never ends an open-ended challenge`() = runTest {
        // The ~100-year sentinel reads as "long past due" to a naive compare.
        val start = now - 10 * day
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(
                listOf(
                    challenge(
                        "group_g_123",
                        startDate = start,
                        endDate = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
                    )
                )
            )

        useCase()

        coVerify(exactly = 0) { challengeRepository.endGroupChallengeLocally(any()) }
    }

    @Test
    fun `an unreadable challenge list is a no-op, never a blind end`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.failure(IllegalStateException("db locked"))

        useCase()

        coVerify(exactly = 0) { challengeRepository.endGroupChallengeLocally(any()) }
    }

    @Test
    fun `ends every expired group challenge, leaving the running one alone`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(
            listOf(
                challenge("group_g_1", groupChallengeId = "g_1"),
                challenge("group_g_2", groupChallengeId = "g_2", endDate = DateUtils.endOfDayMillis(now, 5)),
                challenge("group_g_3", groupChallengeId = "g_3"),
            )
        )

        useCase()

        coVerify(exactly = 1) { challengeRepository.endGroupChallengeLocally("group_g_1") }
        coVerify(exactly = 1) { challengeRepository.endGroupChallengeLocally("group_g_3") }
        coVerify(exactly = 0) { challengeRepository.endGroupChallengeLocally("group_g_2") }
    }
}
