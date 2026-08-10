package com.finite.focus.domain.usecase

import com.finite.focus.domain.model.AppDailyUsage
import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.model.LimitType
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.domain.repository.DailyLogRepository
import com.finite.focus.domain.repository.UsageStatsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CheckDailyLimitUseCaseTest {

    private lateinit var challengeRepository: ChallengeRepository
    private lateinit var usageStatsRepository: UsageStatsRepository
    private lateinit var dailyLogRepository: DailyLogRepository
    private lateinit var useCase: CheckDailyLimitUseCase

    @Before
    fun setUp() {
        challengeRepository = mockk()
        usageStatsRepository = mockk()
        dailyLogRepository = mockk()
        // No overlay-paused time by default — limit math runs against raw usage.
        coEvery { dailyLogRepository.getOverlayPausedMs(any(), any()) } returns Result.success(0L)
        useCase = CheckDailyLimitUseCase(challengeRepository, usageStatsRepository, dailyLogRepository)
    }

    @Test
    fun `returns failure when no active challenge exists`() = runTest {
        coEvery { challengeRepository.getActiveChallengeForApp("com.tiktok") } returns Result.success(null)

        val result = useCase("com.tiktok")

        assertTrue(result.isFailure)
    }

    @Test
    fun `time limit not exceeded returns correct remaining`() = runTest {
        coEvery { challengeRepository.getActiveChallengeForApp("com.tiktok") } returns Result.success(
            createChallenge(LimitType.TIME, 60, null)
        )
        coEvery { usageStatsRepository.getTodayUsageForApp("com.tiktok") } returns AppDailyUsage(35, 5)

        val result = useCase("com.tiktok")

        assertTrue(result.isSuccess)
        val status = result.getOrThrow()
        assertFalse(status.limitExceeded)
        assertEquals(25, status.remainingMinutes)
        assertEquals(35, status.todayMinutes)
    }

    @Test
    fun `time limit exceeded`() = runTest {
        coEvery { challengeRepository.getActiveChallengeForApp("com.tiktok") } returns Result.success(
            createChallenge(LimitType.TIME, 60, null)
        )
        coEvery { usageStatsRepository.getTodayUsageForApp("com.tiktok") } returns AppDailyUsage(75, 10)

        val result = useCase("com.tiktok")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().limitExceeded)
        assertEquals(0, result.getOrThrow().remainingMinutes)
    }

    @Test
    fun `multi-app time limit sums usage across all tracked packages`() = runTest {
        // The limit belongs to the challenge, not to each app: 40 + 25 breaches a 60 min limit
        // even though neither app reaches it alone. Opening the SECOND app must still see the
        // combined total — measuring only the foreground package gave each app its own full limit.
        val challenge = createChallenge(LimitType.TIME, 60, null)
            .copy(appPackageNames = listOf("com.tiktok", "com.instagram"))
        coEvery { challengeRepository.getActiveChallengeForApp("com.instagram") } returns
            Result.success(challenge)
        coEvery { usageStatsRepository.getTodayUsageForApp("com.tiktok") } returns AppDailyUsage(40, 6)
        coEvery { usageStatsRepository.getTodayUsageForApp("com.instagram") } returns AppDailyUsage(25, 4)

        val result = useCase("com.instagram")

        assertTrue(result.isSuccess)
        val status = result.getOrThrow()
        assertEquals(65, status.todayMinutes)
        assertTrue(status.limitExceeded)
        assertEquals(0, status.remainingMinutes)
    }

    @Test
    fun `multi-app time limit not exceeded below the shared limit`() = runTest {
        // Combined usage still under the shared limit — the gate must let the user through.
        val challenge = createChallenge(LimitType.TIME, 60, null)
            .copy(appPackageNames = listOf("com.tiktok", "com.instagram"))
        coEvery { challengeRepository.getActiveChallengeForApp("com.tiktok") } returns
            Result.success(challenge)
        coEvery { usageStatsRepository.getTodayUsageForApp("com.tiktok") } returns AppDailyUsage(20, 3)
        coEvery { usageStatsRepository.getTodayUsageForApp("com.instagram") } returns AppDailyUsage(15, 2)

        val result = useCase("com.tiktok")

        assertTrue(result.isSuccess)
        val status = result.getOrThrow()
        assertEquals(35, status.todayMinutes)
        assertFalse(status.limitExceeded)
        assertEquals(25, status.remainingMinutes)
    }

    @Test
    fun `sessions limit exceeded`() = runTest {
        coEvery { challengeRepository.getActiveChallengeForApp("com.tiktok") } returns Result.success(
            createChallenge(LimitType.SESSIONS, 5, 5)
        )
        coEvery { usageStatsRepository.getTodayUsageForApp("com.tiktok") } returns AppDailyUsage(20, 5)
        // SESSIONS limit gates on conscious opens (Room), not raw UsageStats opens —
        // 5 conscious opens against a 5-session cap must trip the limit.
        coEvery { dailyLogRepository.getConsciousOpens(any(), any()) } returns Result.success(5)

        val result = useCase("com.tiktok")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().limitExceeded)
        assertEquals(0, result.getOrThrow().remainingOpens)
    }

    private fun createChallenge(
        limitType: LimitType,
        limitMinutes: Int,
        limitSessions: Int?
    ) = Challenge(
        id = "test-id",
        appPackageName = "com.tiktok",
        appPackageNames = listOf("com.tiktok"),
        appDisplayName = "TikTok",
        mode = ChallengeMode.SOFT,
        limitType = limitType,
        limitValueMinutes = limitMinutes,
        limitValueSessions = limitSessions,
        startDate = 0L,
        endDate = Long.MAX_VALUE,
        amountCents = null,
        stripePaymentIntentId = null,
        customMotivation = null,
        status = ChallengeStatus.ACTIVE,
        createdAt = 0L
    )
}
