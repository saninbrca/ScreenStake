package com.finite.focus.domain.usecase

import android.content.Context
import com.finite.focus.domain.model.AppDailyUsage
import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.model.LimitType
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.domain.repository.DailyLogRepository
import com.finite.focus.domain.repository.GroupChallengeRepository
import com.finite.focus.domain.repository.UsageStatsRepository
import com.google.firebase.auth.FirebaseAuth
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetDailyStatsUseCaseTest {

    private lateinit var context: Context
    private lateinit var challengeRepository: ChallengeRepository
    private lateinit var usageStatsRepository: UsageStatsRepository
    private lateinit var dailyLogRepository: DailyLogRepository
    private lateinit var groupChallengeRepository: GroupChallengeRepository
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var getChallengeStreakUseCase: GetChallengeStreakUseCase
    private lateinit var useCase: GetDailyStatsUseCase

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        challengeRepository = mockk()
        usageStatsRepository = mockk()
        dailyLogRepository = mockk()
        groupChallengeRepository = mockk()
        firebaseAuth = mockk()
        // Solo, dated challenge: no signed-in group context, no Room daily log, no overlay-paused
        // time, and the streak use case is never reached (challenge isn't open-ended).
        every { firebaseAuth.currentUser } returns null
        coEvery { dailyLogRepository.getLogForDate(any(), any()) } returns Result.success(null)
        coEvery { dailyLogRepository.getOverlayPausedMs(any(), any()) } returns Result.success(0L)
        getChallengeStreakUseCase = mockk(relaxed = true)
        useCase = GetDailyStatsUseCase(
            context,
            challengeRepository,
            usageStatsRepository,
            dailyLogRepository,
            groupChallengeRepository,
            firebaseAuth,
            getChallengeStreakUseCase,
        )
    }

    @Test
    fun `empty challenges returns empty list`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(emptyList())

        val result = useCase()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `single challenge returns correct stats`() = runTest {
        val challenge = Challenge(
            id = "test-id",
            appPackageName = "com.tiktok",
            appPackageNames = listOf("com.tiktok"),
            appDisplayName = "TikTok",
            mode = ChallengeMode.SOFT,
            limitType = LimitType.TIME,
            limitValueMinutes = 60,
            limitValueSessions = null,
            startDate = System.currentTimeMillis(),
            endDate = System.currentTimeMillis() + 7 * 86_400_000L,
            amountCents = null,
            stripePaymentIntentId = null,
            customMotivation = "Stay focused!",
            status = ChallengeStatus.ACTIVE,
            createdAt = System.currentTimeMillis()
        )

        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(listOf(challenge))
        coEvery { usageStatsRepository.getTodayUsageForApp("com.tiktok") } returns AppDailyUsage(30, 5)

        val result = useCase()

        assertTrue(result.isSuccess)
        val stats = result.getOrThrow()
        assertEquals(1, stats.size)
        assertEquals("TikTok", stats[0].appDisplayName)
        assertEquals(30, stats[0].todayMinutes)
        assertEquals(60, stats[0].limitValueMinutes)
        // 30 min used against a 60 min limit must not trip limitExceeded — this is the
        // money-relevant flag the dashboard surfaces.
        assertEquals(false, stats[0].limitExceeded)
    }

    @Test
    fun `multi-app challenge sums usage across all tracked packages`() = runTest {
        // The card must reflect EVERY tracked app, not just appPackageName (the first one).
        // 30 + 45 = 75 against a 60 min limit: both the displayed minutes and the exceeded flag
        // have to agree with the 23:59 worker, which has always summed all packages.
        val challenge = Challenge(
            id = "test-id",
            appPackageName = "com.tiktok",
            appPackageNames = listOf("com.tiktok", "com.instagram"),
            appDisplayName = "TikTok, Instagram",
            mode = ChallengeMode.SOFT,
            limitType = LimitType.TIME,
            limitValueMinutes = 60,
            limitValueSessions = null,
            startDate = System.currentTimeMillis(),
            endDate = System.currentTimeMillis() + 7 * 86_400_000L,
            amountCents = null,
            stripePaymentIntentId = null,
            customMotivation = null,
            status = ChallengeStatus.ACTIVE,
            createdAt = System.currentTimeMillis()
        )

        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(listOf(challenge))
        coEvery { usageStatsRepository.getTodayUsageForApp("com.tiktok") } returns AppDailyUsage(30, 5)
        coEvery { usageStatsRepository.getTodayUsageForApp("com.instagram") } returns AppDailyUsage(45, 3)

        val result = useCase()

        assertTrue(result.isSuccess)
        val stats = result.getOrThrow()
        assertEquals(1, stats.size)
        assertEquals(75, stats[0].todayMinutes)
        assertEquals(true, stats[0].limitExceeded)
    }

    @Test
    fun `returns failure when repository fails`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.failure(RuntimeException("DB error"))

        val result = useCase()

        assertTrue(result.isFailure)
    }
}
