package com.detox.app.domain.usecase

import com.detox.app.domain.model.AppDailyUsage
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
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
    private lateinit var useCase: CheckDailyLimitUseCase

    @Before
    fun setUp() {
        challengeRepository = mockk()
        usageStatsRepository = mockk()
        useCase = CheckDailyLimitUseCase(challengeRepository, usageStatsRepository)
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
    fun `sessions limit exceeded`() = runTest {
        coEvery { challengeRepository.getActiveChallengeForApp("com.tiktok") } returns Result.success(
            createChallenge(LimitType.SESSIONS, 5, 5)
        )
        coEvery { usageStatsRepository.getTodayUsageForApp("com.tiktok") } returns AppDailyUsage(20, 5)

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
        appDisplayName = "TikTok",
        mode = ChallengeMode.SOFT,
        limitType = limitType,
        limitValueMinutes = limitMinutes,
        limitValueSessions = limitSessions,
        startDate = 0L,
        endDate = Long.MAX_VALUE,
        amountCents = null,
        stripePaymentIntentId = null,
        emergencyCode = null,
        customMotivation = null,
        status = ChallengeStatus.ACTIVE,
        createdAt = 0L
    )
}
