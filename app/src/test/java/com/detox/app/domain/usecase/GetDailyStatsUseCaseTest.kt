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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetDailyStatsUseCaseTest {

    private lateinit var challengeRepository: ChallengeRepository
    private lateinit var usageStatsRepository: UsageStatsRepository
    private lateinit var calculatePointsUseCase: CalculatePointsUseCase
    private lateinit var useCase: GetDailyStatsUseCase

    @Before
    fun setUp() {
        challengeRepository = mockk()
        usageStatsRepository = mockk()
        calculatePointsUseCase = CalculatePointsUseCase()
        useCase = GetDailyStatsUseCase(challengeRepository, usageStatsRepository, calculatePointsUseCase)
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
            appDisplayName = "TikTok",
            mode = ChallengeMode.SOFT,
            limitType = LimitType.TIME,
            limitValueMinutes = 60,
            limitValueSessions = null,
            startDate = System.currentTimeMillis(),
            endDate = System.currentTimeMillis() + 7 * 86_400_000L,
            amountCents = null,
            stripePaymentIntentId = null,
            emergencyCode = null,
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
        assertEquals(false, stats[0].limitExceeded)
        assertTrue(stats[0].pointsEarnedToday > 0)
    }

    @Test
    fun `returns failure when repository fails`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.failure(RuntimeException("DB error"))

        val result = useCase()

        assertTrue(result.isFailure)
    }
}
