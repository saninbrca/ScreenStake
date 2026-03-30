package com.detox.app.domain.usecase

import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CreateChallengeUseCaseTest {

    private lateinit var challengeRepository: ChallengeRepository
    private lateinit var useCase: CreateChallengeUseCase

    @Before
    fun setUp() {
        challengeRepository = mockk()
        useCase = CreateChallengeUseCase(challengeRepository)
    }

    @Test
    fun `successful challenge creation returns challenge id`() = runTest {
        coEvery { challengeRepository.getActiveChallengeForApp(any()) } returns Result.success(null)
        coEvery { challengeRepository.createChallenge(any()) } returns Result.success(Unit)

        val result = useCase(
            appPackageName = "com.tiktok",
            appDisplayName = "TikTok",
            limitType = LimitType.TIME,
            limitValueMinutes = 60,
            limitValueSessions = null,
            durationDays = 7,
            customMotivation = "Stay focused!"
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.isNotEmpty())
        coVerify { challengeRepository.createChallenge(any()) }
    }

    @Test
    fun `fails when limit minutes is zero`() = runTest {
        val result = useCase(
            appPackageName = "com.tiktok",
            appDisplayName = "TikTok",
            limitType = LimitType.TIME,
            limitValueMinutes = 0,
            limitValueSessions = null,
            durationDays = 7,
            customMotivation = null
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `fails when sessions type has null sessions count`() = runTest {
        val result = useCase(
            appPackageName = "com.tiktok",
            appDisplayName = "TikTok",
            limitType = LimitType.SESSIONS,
            limitValueMinutes = 5,
            limitValueSessions = null,
            durationDays = 7,
            customMotivation = null
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `fails when duplicate active challenge exists for same app`() = runTest {
        val existingChallenge = Challenge(
            id = "existing",
            appPackageName = "com.tiktok",
            appDisplayName = "TikTok",
            mode = ChallengeMode.SOFT,
            limitType = LimitType.TIME,
            limitValueMinutes = 60,
            limitValueSessions = null,
            startDate = 0L,
            endDate = 0L,
            amountCents = null,
            stripePaymentIntentId = null,
            emergencyCode = null,
            customMotivation = null,
            status = ChallengeStatus.ACTIVE,
            createdAt = 0L
        )
        coEvery { challengeRepository.getActiveChallengeForApp("com.tiktok") } returns Result.success(existingChallenge)

        val result = useCase(
            appPackageName = "com.tiktok",
            appDisplayName = "TikTok",
            limitType = LimitType.TIME,
            limitValueMinutes = 60,
            limitValueSessions = null,
            durationDays = 7,
            customMotivation = null
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `fails when duration is out of range`() = runTest {
        val result = useCase(
            appPackageName = "com.tiktok",
            appDisplayName = "TikTok",
            limitType = LimitType.TIME,
            limitValueMinutes = 60,
            limitValueSessions = null,
            durationDays = 0,
            customMotivation = null
        )

        assertTrue(result.isFailure)
    }
}
