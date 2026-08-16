package com.finite.focus.service

import com.finite.focus.domain.model.DailyLog
import com.finite.focus.domain.repository.DailyLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.fail
import java.io.IOException

/**
 * Guards [evaluateChallengeViolated] — `DailyEvaluationWorker`'s whole-challenge breach verdict,
 * and the money-gating one of the two: `violated` decides `capturePayment` vs
 * `cancelOrRefundPayment` on every Hard Mode path in the worker.
 *
 * The abstain assertion here is the important one. All three call sites of this verdict sit ABOVE
 * their Stripe branch inside a single `try` with no inner catch, so "this function threw" is
 * exactly equivalent to "no status was written and no Stripe call was made this pass" — the
 * challenge keeps `status = 'active'`, which is how the worker finds its capture/refund work next
 * cycle.
 */
class DailyEvaluationVerdictTest {

    private lateinit var dailyLogRepository: DailyLogRepository

    private val challengeId = "c1"

    @Before
    fun setUp() {
        dailyLogRepository = mockk()
    }

    private fun log(limitExceeded: Boolean, id: String = "log1") = DailyLog(
        id = id,
        challengeId = challengeId,
        date = 0L,
        totalMinutes = 0,
        openCount = 0,
        pointsEarned = 0,
        limitExceeded = limitExceeded,
        moneyLostCents = 0,
    )

    // ── Abstain: a cancelled read decides nothing ────────────────────────────────

    @Test
    fun `a cancelled history read abstains — it never returns a verdict`() = runTest {
        coEvery { dailyLogRepository.getLogsForChallengeOnce(challengeId) } throws
            CancellationException("worker stopped")

        val propagated = try {
            val verdict = evaluateChallengeViolated(challengeId, todayExceeded = false, dailyLogRepository)
            fail("expected an abstain, got verdict=$verdict")
            null
        } catch (e: CancellationException) {
            e
        }

        // Returning false here is what settled a breached challenge as a win; returning true would
        // be the mirror-image bug (a capture on evidence we never read). Neither is acceptable —
        // the only correct outcome is "no answer".
        assertNotNull("cancellation must propagate so the worker settles nothing", propagated)
    }

    @Test
    fun `the short-circuit still wins — today's breach needs no history read at all`() = runTest {
        // todayExceeded is positive proof already in hand, so no read happens and there is nothing
        // to cancel. Pinned so the abstain can never block a loss the worker has already observed.
        val violated = evaluateChallengeViolated(challengeId, todayExceeded = true, dailyLogRepository)

        assertTrue(violated)
        coVerify(exactly = 0) { dailyLogRepository.getLogsForChallengeOnce(any()) }
    }

    // ── Fail open: an unreadable history is unchanged ────────────────────────────

    @Test
    fun `an unreadable history still fails open to clean — never a manufactured loss`() = runTest {
        coEvery { dailyLogRepository.getLogsForChallengeOnce(challengeId) } throws
            IOException("Room unavailable")

        assertFalse(evaluateChallengeViolated(challengeId, todayExceeded = false, dailyLogRepository))
    }

    // ── The verdict itself is untouched ──────────────────────────────────────────

    @Test
    fun `a breach anywhere in the history loses the whole challenge`() = runTest {
        coEvery { dailyLogRepository.getLogsForChallengeOnce(challengeId) } returns listOf(
            log(limitExceeded = true, id = "log1"),
            log(limitExceeded = false, id = "log2"),
        )

        assertTrue(evaluateChallengeViolated(challengeId, todayExceeded = false, dailyLogRepository))
    }

    @Test
    fun `a fully clean history is not a violation`() = runTest {
        coEvery { dailyLogRepository.getLogsForChallengeOnce(challengeId) } returns listOf(
            log(limitExceeded = false, id = "log1"),
            log(limitExceeded = false, id = "log2"),
        )

        assertFalse(evaluateChallengeViolated(challengeId, todayExceeded = false, dailyLogRepository))
    }

    @Test
    fun `an empty history reads as clean — the deliberate server-matching fail-open`() = runTest {
        coEvery { dailyLogRepository.getLogsForChallengeOnce(challengeId) } returns emptyList()

        assertFalse(evaluateChallengeViolated(challengeId, todayExceeded = false, dailyLogRepository))
    }
}
