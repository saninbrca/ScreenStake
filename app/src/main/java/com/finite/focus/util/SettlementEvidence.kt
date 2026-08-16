package com.finite.focus.util

import com.finite.focus.domain.model.DailyLog
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Reads the `DailyLog` history a settlement verdict is derived from, keeping the **two** ways that
 * read can fail strictly apart.
 *
 * Shared by the only two callers that turn a log history into a challenge status —
 * [com.finite.focus.domain.usecase.SettleEndedSoftChallengesUseCase] and
 * `DailyEvaluationWorker.challengeViolated` — for the same reason the verdict rule itself is
 * shared: a second parallel implementation is exactly how the two paths drift apart. Anything that
 * merely *displays* history (statistics, the History screen) is deliberately NOT a caller; only a
 * read whose result decides a status belongs here.
 *
 * ## The distinction this exists to make explicit
 *
 * Both failures used to arrive as `runCatching { … }.getOrElse { emptyList() }`, and an empty
 * history reads as "no breach ever recorded" ⇒ `violated = false` ⇒ the challenge settles as a win.
 * They are not the same event:
 *
 *  - **Unreadable history** (Room error, corrupt row, decryption failure). A read happened and
 *    could not be answered. Fail-open is CORRECT and unchanged: return an empty history so the
 *    caller reaches "no breach". This mirrors the server, which refunds when it finds zero logs and
 *    only flags `reconciliationLowEvidence` for human review. Never capture on data we could not
 *    read.
 *  - **Cancelled read** (the scope went away: ViewModel cleared, `CoroutineWorker` stopped, service
 *    torn down, process going down). **No read happened at all.** There is no evidence — not even
 *    the negative kind — so there is nothing to fail open *on*. Returning an empty history here
 *    manufactures a clean run out of a coroutine that simply died mid-question, which is how a
 *    genuinely breached Soft challenge could settle COMPLETED.
 *
 * So a cancellation is rethrown, and the caller **abstains**: it settles nothing this pass and
 * leaves the challenge `ACTIVE` for the next one. Both callers already run repeatedly (23:59
 * worker, every Dashboard open, every enforcement stop), so "decide later" costs a cycle and
 * nothing else — whereas deciding now on absent evidence is unrecoverable once the status is
 * terminal.
 *
 * Abstaining is deliberately **not** a fourth verdict. It sits above the
 * FAILED > ENDED_UNVERIFIED > COMPLETED ladder rather than inside it, so the precedence between
 * those three is untouched — in particular a cancelled read can never produce
 * [com.finite.focus.domain.model.ChallengeStatus.ENDED_UNVERIFIED], which answers a different
 * question (this install could never have observed the window) and would be just as fabricated
 * here.
 *
 * ## Clause order is load-bearing
 *
 * `kotlinx.coroutines.CancellationException` is a `typealias` for
 * `java.util.concurrent.CancellationException`, which extends `IllegalStateException` and therefore
 * `Exception`. The cancellation clause MUST stay first — reversed, the fail-open swallows it again
 * and this file does nothing. That inheritance is also why the original `runCatching` /
 * `catch (e: Exception)` swallowed cancellation without anyone writing a line of code to that
 * effect.
 *
 * Note this is about evidence, not noise: [SentryEventFilter] keeps the same exception type out of
 * Sentry. Filtering the report and honouring the control flow are separate jobs.
 *
 * @param challengeId the challenge whose history is being read — logging only.
 * @param caller short tag identifying the settlement path, for log correlation.
 * @param read the actual history read; must be the caller's real read, not a pre-fetched list.
 * @return the history, or an empty list when the read failed for an ordinary (non-cancellation)
 *         reason.
 * @throws CancellationException if the read was cancelled — the caller must let this propagate.
 */
suspend fun readHistoryForVerdict(
    challengeId: String,
    caller: String,
    read: suspend () -> List<DailyLog>,
): List<DailyLog> = try {
    read()
} catch (e: CancellationException) {
    // ABSTAIN. Rethrown, never converted: the caller must not reach its status write.
    Timber.w(
        "%s: log history read CANCELLED for %s — abstaining, challenge stays ACTIVE for the next pass",
        caller, challengeId
    )
    throw e
} catch (e: Exception) {
    // Fail-open, unchanged: an unreadable history must never manufacture a loss.
    Timber.e(e, "%s: log history unreadable for %s — treating as clean", caller, challengeId)
    emptyList()
}
