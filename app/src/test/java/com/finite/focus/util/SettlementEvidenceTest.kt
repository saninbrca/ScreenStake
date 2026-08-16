package com.finite.focus.util

import com.finite.focus.domain.model.DailyLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Guards [readHistoryForVerdict] — the single place that decides whether a failed evidence read
 * fails open ("clean history") or abstains.
 *
 * The two directions are not symmetric and both are load-bearing:
 *  - Fail open on a CANCELLATION ⇒ the bug this exists to stop: a coroutine that died mid-read
 *    hands back an empty history, `any { limitExceeded }` is false, and a breached Soft challenge
 *    settles as a win.
 *  - Abstain on an ORDINARY read error ⇒ the opposite regression: an unreadable history would stop
 *    settling anything, and the deliberate server-matching fail-open (never capture on data we
 *    could not read) would be gone.
 */
class SettlementEvidenceTest {

    private fun log(limitExceeded: Boolean) = DailyLog(
        id = "log1",
        challengeId = "c1",
        date = 0L,
        totalMinutes = 0,
        openCount = 0,
        pointsEarned = 0,
        limitExceeded = limitExceeded,
        moneyLostCents = 0,
    )

    // ── Abstain: cancellation must propagate ─────────────────────────────────────

    @Test
    fun `a cancelled read rethrows instead of returning an empty history`() = runTest {
        val thrown = CancellationException("scope torn down")

        val propagated = try {
            readHistoryForVerdict("c1", "test") { throw thrown }
            fail("expected the cancellation to propagate")
            null
        } catch (e: CancellationException) {
            e
        }

        assertSame("the original cancellation must propagate unwrapped", thrown, propagated)
    }

    @Test
    fun `a real kotlinx job cancellation is matched, not just a hand-built one`() = runBlocking {
        // The production shape: whatever kotlinx actually throws when the scope goes away must be
        // matched. That is JobCancellationException, never a bare CancellationException — the same
        // subclass trap that made addIgnoredExceptionForType useless in SentryEventFilter.
        var propagated: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                readHistoryForVerdict("c1", "test") {
                    delay(10_000)
                    emptyList()
                }
            } catch (e: CancellationException) {
                propagated = e
            }
        }
        job.cancelAndJoin()

        assertNotNull("expected the cancelled read to surface a throwable", propagated)
        assertTrue(
            "kotlinx threw ${propagated!!::class.java.name} and the helper failed open instead",
            propagated is CancellationException,
        )
    }

    // ── Fail open: ordinary read errors are unchanged ────────────────────────────

    @Test
    fun `an unreadable history still fails open to an empty list`() = runTest {
        val history = readHistoryForVerdict("c1", "test") { throw IOException("Room unavailable") }

        assertEquals(emptyList<DailyLog>(), history)
    }

    @Test
    fun `an IllegalStateException fails open — the cancellation clause must not over-match`() = runTest {
        // CancellationException extends IllegalStateException, so a `catch (e: IllegalStateException)`
        // ordering mistake would swallow real state errors into the abstain path (or vice versa).
        // Pin the direction that actually matters: a plain ISE is NOT a cancellation.
        val history = readHistoryForVerdict("c1", "test") { throw IllegalStateException("bad state") }

        assertEquals(emptyList<DailyLog>(), history)
    }

    // ── Happy path ───────────────────────────────────────────────────────────────

    @Test
    fun `a successful read is passed through untouched`() = runTest {
        val logs = listOf(log(limitExceeded = true), log(limitExceeded = false))

        assertEquals(logs, readHistoryForVerdict("c1", "test") { logs })
    }
}
