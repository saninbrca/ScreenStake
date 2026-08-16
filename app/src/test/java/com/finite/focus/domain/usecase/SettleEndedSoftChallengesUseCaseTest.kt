package com.finite.focus.domain.usecase

import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.model.DailyLog
import com.finite.focus.domain.model.LimitType
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.domain.repository.DailyLogRepository
import com.finite.focus.util.DateUtils
import com.finite.focus.util.InstallInfoProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Behavioural tests for the soft backstop, under both [SettleEndedSoftChallengesUseCase.Trigger]
 * grades. The backstop must:
 *  - finalise a fixed-end SOFT challenge whose endDate has passed — FAILED iff ANY DailyLog in the
 *    challenge's history recorded a limit breach, otherwise COMPLETED, and
 *  - NEVER touch open-ended, Hard, staked (stripePaymentIntentId), or group challenges.
 *
 * The verdict reads the WHOLE history via `getLogsForChallengeOnce` — never just today's row, which
 * let a challenge that broke its limit on an earlier day settle as a win. That call is stubbed
 * explicitly in [setUp]: the use case fail-opens on an ordinary read error
 * ([com.finite.focus.util.readHistoryForVerdict]), so an unstubbed mock would throw, be swallowed,
 * and silently settle every challenge as COMPLETED — making the COMPLETED cases pass without ever
 * exercising the verdict.
 *
 * That fail-open covers ordinary errors ONLY. A cancelled read abstains instead — see the
 * "Abstain" block near the end of this class.
 */
class SettleEndedSoftChallengesUseCaseTest {

    private lateinit var challengeRepository: ChallengeRepository
    private lateinit var dailyLogRepository: DailyLogRepository
    private lateinit var installInfoProvider: InstallInfoProvider
    private lateinit var useCase: SettleEndedSoftChallengesUseCase

    private val day = DateUtils.MILLIS_PER_DAY
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        challengeRepository = mockk()
        dailyLogRepository = mockk()
        // Default: installed long before any test challenge started, i.e. "observed live", so the
        // pre-existing FAILED/COMPLETED assertions below exercise the ordinary verdict. The
        // reinstall cases override this.
        installInfoProvider = mockk()
        every { installInfoProvider.installedAt } returns now - 365 * day
        // Default: clean history → not exceeded. Individual tests override as needed.
        // Must be stubbed, not left to the fail-open: an unstubbed call throws a MockKException
        // that the use case swallows into an empty history, which would make every COMPLETED
        // assertion below pass through the error path instead of the settlement verdict.
        coEvery { dailyLogRepository.getLogsForChallengeOnce(any()) } returns emptyList()
        coEvery { challengeRepository.updateChallengeStatus(any(), any(), any()) } returns Result.success(Unit)
        useCase = SettleEndedSoftChallengesUseCase(
            challengeRepository, dailyLogRepository, installInfoProvider
        )
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

    // ── ENFORCEMENT_STOP trigger ────────────────────────────────────────────────
    // Reached from the overlay funnel and the 60 s sweep, at arbitrary times of day, so that a
    // finished Soft SOLO challenge stops blocking offline without the user opening Finite. The
    // predicate difference from SETTLEMENT is the entire point and the first two tests pin it.

    /** A challenge whose LAST day is today: `hasReachedEnd` is already true, `hasPassedEnd` is not. */
    private fun endsTodayChallenge(id: String = "soft1") =
        challenge(id, startDate = now - 3 * day, endDate = DateUtils.endOfDayMillis(now, 1))

    @Test
    fun `ENFORCEMENT_STOP does not free a soft solo challenge on its final day`() = runTest {
        // The trap this trigger exists for: SETTLEMENT's `>=` fires ON the last day, so running it
        // from the enforcement path at 00:05 would end blocking a full day early and settle on a
        // history still being written.
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(endsTodayChallenge()))

        useCase(SettleEndedSoftChallengesUseCase.Trigger.ENFORCEMENT_STOP)

        coVerify(exactly = 0) { challengeRepository.updateChallengeStatus(any(), any(), any()) }
    }

    @Test
    fun `SETTLEMENT still settles on the final day — default behaviour unchanged`() = runTest {
        // Same challenge, other trigger: the 23:59 worker and the Dashboard backstop must keep
        // settling on the final day exactly as before.
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(endsTodayChallenge()))

        useCase()

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.COMPLETED, null)
        }
    }

    @Test
    fun `ENFORCEMENT_STOP settles a soft solo challenge once its last day is fully over`() = runTest {
        // Default helper endDate is two days ago — strictly past the end.
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } returns
            listOf(dailyLog(limitExceeded = false))

        useCase(SettleEndedSoftChallengesUseCase.Trigger.ENFORCEMENT_STOP)

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.COMPLETED, null)
        }
    }

    @Test
    fun `ENFORCEMENT_STOP keeps the verdict — a breached history still settles as FAILED`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } returns
            listOf(dailyLog(limitExceeded = true))

        useCase(SettleEndedSoftChallengesUseCase.Trigger.ENFORCEMENT_STOP)

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.FAILED, "limit_exceeded")
        }
    }

    @Test
    fun `ENFORCEMENT_STOP never touches hard, staked, group or open-ended rows`() = runTest {
        // The enforcement path sees every active row, so the money-free guards matter more here
        // than anywhere: Hard solo must keep status='active' (DailyEvaluationWorker finds its
        // capture/refund work by that status) and a group row has its own end mechanism.
        val start = now - 10 * day
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(
            listOf(
                challenge("hard1", mode = ChallengeMode.HARD),
                challenge("staked1", stripePaymentIntentId = "pi_123"),
                challenge("group1", groupChallengeId = "g_123"),
                challenge(
                    "open1",
                    startDate = start,
                    endDate = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
                ),
            )
        )

        useCase(SettleEndedSoftChallengesUseCase.Trigger.ENFORCEMENT_STOP)

        coVerify(exactly = 0) { challengeRepository.updateChallengeStatus(any(), any(), any()) }
    }

    // ── Unobserved-install verdict (ENDED_UNVERIFIED) ────────────────────────────
    // The install is stamped AFTER the challenge ended, i.e. the reinstall case: nothing on this
    // device could have watched the window.

    private fun installedAfterTheChallengeEnded() {
        every { installInfoProvider.installedAt } returns now - day
    }

    @Test
    fun `POSITIVE PROOF outranks provenance — a surviving breach log still settles FAILED`() = runTest {
        // The rule this commit exists for. The challenge was never observed by this install, but a
        // restored log positively proves a breach, so it must lose through the ordinary path —
        // NOT be excused as "unverifiable". Presence of evidence is evidence.
        installedAfterTheChallengeEnded()
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } returns
            listOf(dailyLog(limitExceeded = true))

        useCase()

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.FAILED, "limit_exceeded")
        }
        coVerify(exactly = 0) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.ENDED_UNVERIFIED, any())
        }
    }

    @Test
    fun `no breach log on an unobserved install settles ENDED_UNVERIFIED, never COMPLETED`() = runTest {
        // Absence of evidence is NOT evidence of absence: Room died with the old install, and a
        // clean day writes no row anyway. Celebrating here is the original bug.
        installedAfterTheChallengeEnded()
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } returns emptyList()

        useCase()

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.ENDED_UNVERIFIED, null)
        }
        coVerify(exactly = 0) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.COMPLETED, any())
        }
    }

    @Test
    fun `clean restored logs on an unobserved install are still ENDED_UNVERIFIED`() = runTest {
        // Partial evidence is not proof of a clean run — some days' logs may simply never have
        // reached Firestore. Only a BREACH row is conclusive; clean rows are not.
        installedAfterTheChallengeEnded()
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } returns listOf(
            dailyLog(limitExceeded = false, id = "log1", date = DateUtils.dayKey(now - 4 * day)),
            dailyLog(limitExceeded = false, id = "log2", date = DateUtils.dayKey(now - 3 * day)),
        )

        useCase()

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.ENDED_UNVERIFIED, null)
        }
    }

    @Test
    fun `unreadable history on an unobserved install settles ENDED_UNVERIFIED, not COMPLETED`() = runTest {
        // The fail-open still refuses to manufacture a loss, but it no longer manufactures a WIN
        // either when the install could not have observed the challenge.
        installedAfterTheChallengeEnded()
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } throws
            RuntimeException("Room unavailable")

        useCase()

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.ENDED_UNVERIFIED, null)
        }
    }

    @Test
    fun `observed install with a clean history still settles COMPLETED`() = runTest {
        // Regression fence: the downgrade must apply ONLY to unobserved installs. A user who kept
        // the app installed through the whole challenge keeps their win.
        every { installInfoProvider.installedAt } returns now - 60 * day
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } returns
            listOf(dailyLog(limitExceeded = false))

        useCase()

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.COMPLETED, null)
        }
    }

    // ── Abstain (cancelled read) ─────────────────────────────────────────────────
    // A cancelled read is not an unreadable history: no read happened, so there is no evidence of
    // any kind. The use case must settle NOTHING and leave the row active for the next pass. These
    // sit outside the FAILED > ENDED_UNVERIFIED > COMPLETED ladder — none of the three may appear.

    @Test
    fun `a cancelled history read settles nothing — the challenge stays ACTIVE`() = runTest {
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } throws
            CancellationException("ViewModel cleared mid-read")

        var propagated: Throwable? = null
        try {
            useCase()
        } catch (e: CancellationException) {
            propagated = e
        }

        assertNotNull("cancellation must propagate, not be swallowed into an empty history", propagated)
        // The whole point: no status write of ANY kind. Before this fix the same input produced
        // COMPLETED — a fabricated win for a challenge whose history was never read.
        coVerify(exactly = 0) { challengeRepository.updateChallengeStatus(any(), any(), any()) }
    }

    @Test
    fun `a cancelled read on an unobserved install does NOT settle ENDED_UNVERIFIED`() = runTest {
        // The abstain sits ABOVE the ladder, not inside it. ENDED_UNVERIFIED answers a different
        // question — could this install ever have observed the window — and is just as fabricated
        // as COMPLETED when the answer came from a read that never happened.
        installedAfterTheChallengeEnded()
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } throws
            CancellationException("scope torn down")

        try {
            useCase()
        } catch (e: CancellationException) {
            // expected
        }

        coVerify(exactly = 0) { challengeRepository.updateChallengeStatus(any(), any(), any()) }
    }

    @Test
    fun `a cancelled read stops the pass — later challenges are not settled on a dead scope`() = runTest {
        // Unwinding out of invoke() IS the abstain, and it is deliberately not a `continue`: the
        // scope is already gone, so every following challenge would read on a dead coroutine too.
        // Neither the cancelled challenge nor the one behind it may be settled.
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1"), challenge("soft2")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } throws
            CancellationException("scope torn down")
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft2") } returns
            listOf(dailyLog(limitExceeded = false))

        try {
            useCase()
        } catch (e: CancellationException) {
            // expected
        }

        coVerify(exactly = 0) { challengeRepository.updateChallengeStatus(any(), any(), any()) }
    }

    @Test
    fun `unknown install time falls back to the normal verdict`() = runTest {
        // PackageManager failure ⇒ installedAt = 0 ⇒ fail-safe: behave exactly as before this
        // feature existed rather than marking everything unverifiable.
        every { installInfoProvider.installedAt } returns 0L
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.success(listOf(challenge("soft1")))
        coEvery { dailyLogRepository.getLogsForChallengeOnce("soft1") } returns emptyList()

        useCase()

        coVerify(exactly = 1) {
            challengeRepository.updateChallengeStatus("soft1", ChallengeStatus.COMPLETED, null)
        }
    }
}
