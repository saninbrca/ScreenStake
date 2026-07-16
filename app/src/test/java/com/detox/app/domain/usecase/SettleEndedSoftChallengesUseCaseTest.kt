package com.detox.app.domain.usecase

import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.util.DateUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Behavioural tests for the on-app-open soft backstop. The backstop must:
 *  - finalise a fixed-end SOFT challenge whose endDate has passed (COMPLETED, or FAILED when today's
 *    log recorded a limit breach), and
 *  - NEVER touch open-ended, Hard, staked (stripePaymentIntentId), or group challenges.
 */
class SettleEndedSoftChallengesUseCaseTest {

    private lateinit var challengeRepository: ChallengeRepository
    private lateinit var dailyLogRepository: DailyLogRepository
    private lateinit var useCase: SettleEndedSoftChallengesUseCase

    private val day = DateUtils.MILLIS_PER_DAY
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        challengeRepository = mockk()
        dailyLogRepository = mockk()
        // Default: no log today → not exceeded. Individual tests override as needed.
        coEvery { dailyLogRepository.getLogForDate(any(), any()) } returns Result.success(null)
        coEvery { challengeRepository.updateChallengeStatus(any(), any(), any()) } returns Result.success(Unit)
        useCase = SettleEndedSoftChallengesUseCase(challengeRepository, dailyLogRepository)
    }

    private fun challenge(
        id: String,
        mode: ChallengeMode = ChallengeMode.SOFT,
        startDate: Long = now - 10 * day,
        endDate: Long = now - 2 * day,          // ended two days ago by default
        stripePaymentIntentId: String? = null,
        groupChallengeId: String? = null,
    ) = Challenge(
        id = id,
        appPackageName = "com.tiktok",
        appPackageNames = listOf("com.tiktok"),
        appDisplayName = "TikTok",
        mode = mode,
        limitType = LimitType.TIME,
        limitValueMinutes = 60,
        limitValueSessions = null,
        startDate = startDate,
        endDate = endDate,
        amountCents = null,
        stripePaymentIntentId = stripePaymentIntentId,
        customMotivation = null,
        status = ChallengeStatus.ACTIVE,
        createdAt = startDate,
        groupChallengeId = groupChallengeId,
    )

    private fun dailyLog(limitExceeded: Boolean) = DailyLog(
        id = "log1",
        challengeId = "soft1",
        date = DateUtils.todayKey(),
        totalMinutes = 0,
        openCount = 0,
        pointsEarned = 0,
        limitExceeded = limitExceeded,
        moneyLostCents = 0
    )

    @Test
    fun `completes a fixed-end soft challenge whose endDate passed with no limit breach`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))

        useCase()

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.COMPLETED, null)
        }
    }

    @Test
    fun `fails a fixed-end soft challenge when today's log recorded a limit breach`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogForDate("soft1", any()) } returns
            Result.success(dailyLog(limitExceeded = true))

        useCase()

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.FAILED, "limit_exceeded")
        }
    }

    @Test
    fun `never touches an open-ended challenge`() = runTest {
        val start = now - 10 * day
        val openEnded = challenge(
            "open1",
            startDate = start,
            endDate = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        )
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(listOf(openEnded))

        useCase()

        coVerify(exactly = 0) { challengeRepository.updateChallengeStatus(any(), any(), any()) }
    }

    @Test
    fun `never touches a hard mode challenge`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("hard1", mode = ChallengeMode.HARD)))

        useCase()

        coVerify(exactly = 0) { challengeRepository.updateChallengeStatus(any(), any(), any()) }
    }

    @Test
    fun `never touches a challenge that carries a stripe payment intent`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("staked1", stripePaymentIntentId = "pi_123")))

        useCase()

        coVerify(exactly = 0) { challengeRepository.updateChallengeStatus(any(), any(), any()) }
    }

    @Test
    fun `never touches a group challenge shadow row`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("group1", groupChallengeId = "g_123")))

        useCase()

        coVerify(exactly = 0) { challengeRepository.updateChallengeStatus(any(), any(), any()) }
    }

    @Test
    fun `does not complete a fixed-end soft challenge that has not reached its end`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("future1", startDate = now, endDate = now + 5 * day)))

        useCase()

        coVerify(exactly = 0) { challengeRepository.updateChallengeStatus(any(), any(), any()) }
    }
}
