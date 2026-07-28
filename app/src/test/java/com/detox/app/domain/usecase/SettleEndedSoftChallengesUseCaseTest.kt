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
 *  - finalise a fixed-end SOFT challenge whose endDate has passed — FAILED iff ANY DailyLog in the
 *    challenge's history recorded a limit breach, otherwise COMPLETED, and
 *  - NEVER touch open-ended, Hard, staked (stripePaymentIntentId), or group challenges.
 *
 * The verdict reads the WHOLE history via `getLogsForChallengeOnce` — never just today's row, which
 * let a challenge that broke its limit on an earlier day settle as a win. That call is stubbed
 * explicitly in [setUp]: the use case fail-opens on a read error (`runCatching { … }.getOrElse
 * { emptyList() }`), so an unstubbed mock would throw, be swallowed, and silently settle every
 * challenge as COMPLETED — making the COMPLETED cases pass without ever exercising the verdict.
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
        // Default: clean history → not exceeded. Individual tests override as needed.
        // Must be stubbed, not left to the fail-open: an unstubbed call throws a MockKException
        // that the use case swallows into an empty history, which would make every COMPLETED
        // assertion below pass through the error path instead of the settlement verdict.
        coEvery { dailyLogRepository.getLogsForChallengeOnce(any()) } returns emptyList()
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

    /** A log from somewhere inside the default challenge window (started 10 days ago, ended 2 ago). */
    private fun dailyLog(
        limitExceeded: Boolean,
        id: String = "log1",
        date: Long = DateUtils.dayKey(now - 3 * day),
    ) = DailyLog(
        id = id,
        challengeId = "soft1",
        date = date,
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
        // A real history, every day clean — not an empty one standing in for a read error.
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } returns listOf(
            dailyLog(limitExceeded = false, id = "log1", date = DateUtils.dayKey(now - 4 * day)),
            dailyLog(limitExceeded = false, id = "log2", date = DateUtils.dayKey(now - 3 * day)),
        )

        useCase()

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.COMPLETED, null)
        }
    }

    @Test
    fun `fail-open — an unreadable history settles as COMPLETED, never a manufactured loss`() = runTest {
        // Deliberate production behaviour, asserted rather than left implicit: a read error must
        // never invent a breach. It is also why no COMPLETED assertion in this class can, on its
        // own, prove the verdict ran — the error path produces the same status. The two FAILED
        // tests are the discriminating ones: the fail-open can never produce FAILED.
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } throws
            RuntimeException("Room unavailable")

        useCase()

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.COMPLETED, null)
        }
    }

    @Test
    fun `fails a fixed-end soft challenge when its history recorded a limit breach`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } returns
            listOf(dailyLog(limitExceeded = true))

        useCase()

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.FAILED, "limit_exceeded")
        }
    }

    @Test
    fun `fails when an EARLIER day broke the limit and the final day was clean`() = runTest {
        // The whole point of the history verdict: reading only the last day let a challenge that
        // already broke its limit settle as a win.
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } returns listOf(
            dailyLog(limitExceeded = true, id = "log1", date = DateUtils.dayKey(now - 6 * day)),
            dailyLog(limitExceeded = false, id = "log2", date = DateUtils.dayKey(now - 2 * day)),
        )

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
