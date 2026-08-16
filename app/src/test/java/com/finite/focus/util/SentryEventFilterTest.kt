package com.finite.focus.util

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Guards [SentryEventFilter.isCancellation] — the `beforeSend` rule that keeps coroutine
 * cancellation out of Sentry.
 *
 * The two failure directions are not symmetric:
 *  - Too lax ⇒ the 1.0.1 noise returns (a cancelled startup job reported as a production issue).
 *    Covered by the two cases that cancel a *real* coroutine rather than constructing an
 *    exception by hand — the point is that whatever kotlinx actually throws is matched, not
 *    whatever we assumed it throws.
 *  - Too eager ⇒ a genuine crash is silently dropped and we never learn about it. Covered by
 *    [genuineException_isReported] and [causedByCancellation_isStillReported].
 */
class SentryEventFilterTest {

    // ── Too lax: the real kotlinx types must be matched ──────────────────────────

    @Test
    fun cancelledJob_isFiltered() = runBlocking {
        // Exactly the production shape: a job launched at startup, cancelled when its scope goes
        // away, with a broad catch reporting whatever came out. kotlinx throws its internal
        // JobCancellationException here — never a bare CancellationException — which is why the
        // filter has to match subclasses.
        var caught: Throwable? = null
        // UNDISPATCHED so the body is already parked in delay() before we cancel, and
        // cancelAndJoin() so the catch has definitely run by the time we assert — no sleeps.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                delay(10_000)
            } catch (e: Exception) {
                caught = e
            }
        }
        job.cancelAndJoin()

        assertNotNull("expected the cancelled job to surface a throwable", caught)
        assertTrue(
            "kotlinx threw ${caught!!::class.java.name} and the filter missed it",
            SentryEventFilter.isCancellation(caught),
        )
    }

    @Test
    fun timeoutCancellation_isFiltered() = runBlocking {
        val caught: Throwable = try {
            withTimeout(1) { delay(10_000) }
            error("withTimeout should have thrown")
        } catch (e: Exception) {
            e
        }

        assertTrue(caught is TimeoutCancellationException)
        assertTrue(SentryEventFilter.isCancellation(caught))
    }

    @Test
    fun plainJavaCancellationException_isFiltered() {
        // kotlinx.coroutines.CancellationException is a typealias for this on the JVM, so both
        // spellings — and anything a Future throws — are covered by the one check.
        assertTrue(SentryEventFilter.isCancellation(java.util.concurrent.CancellationException()))
    }

    // ── Too eager: real failures must still reach Sentry ─────────────────────────

    @Test
    fun genuineException_isReported() {
        assertFalse(SentryEventFilter.isCancellation(IOException("network down")))
        assertFalse(SentryEventFilter.isCancellation(IllegalStateException("bad state")))
        assertFalse(SentryEventFilter.isCancellation(RuntimeException("Sentry test crash")))
    }

    @Test
    fun causedByCancellation_isStillReported() {
        // Documents the deliberate scope decision: only the reported throwable is inspected, never
        // the cause chain. A real failure that merely carries a cancellation underneath it is a
        // real failure and must not be swallowed.
        val wrapped = IllegalStateException("settlement failed", java.util.concurrent.CancellationException())
        assertFalse(SentryEventFilter.isCancellation(wrapped))
    }

    @Test
    fun nullThrowable_isReported() {
        // Sentry.captureMessage() produces an event with no throwable — it must pass through.
        assertFalse(SentryEventFilter.isCancellation(null))
    }
}
