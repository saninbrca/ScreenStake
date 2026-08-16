package com.finite.focus.domain.usecase

import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.domain.repository.DailyLogRepository
import com.finite.focus.util.DateUtils
import com.finite.focus.util.InstallInfoProvider
import com.finite.focus.util.isUnobservedSoftChallenge
import com.finite.focus.util.readHistoryForVerdict
import timber.log.Timber
import javax.inject.Inject

/**
 * Backstop that finalises FIXED-END-DATE Soft Mode challenges whose end date passed while the app
 * was closed (or while the EMUI-throttled periodic [DailyEvaluationWorker] failed to run). This is
 * an ADDITIONAL safety net — it never replaces the worker, and it re-uses the worker's status
 * decision so the two paths can never diverge.
 *
 * Runs under two different [Trigger] grades, which differ ONLY in the end-date predicate; see the
 * enum. Everything below the predicate — guards, verdict, write — is identical, deliberately: a
 * second parallel implementation is exactly how the two paths would drift apart.
 *
 * Strictly SOFT-only and money-free:
 *  - `mode == SOFT` && `stripePaymentIntentId == null` — never touches Hard Mode capture/refund/
 *    settlement gates.
 *  - `groupChallengeId == null` — group shadow rows settle via their Cloud Function, not here.
 *  - Open-ended challenges ([DateUtils.isOpenEnded]) run indefinitely by design and are NEVER
 *    completed here.
 *
 * The FAILED-vs-COMPLETED choice mirrors the worker's whole-challenge verdict, which in turn
 * mirrors the server's reconciliation rule: FAILED iff ANY DailyLog in the challenge's history
 * recorded a limit breach, otherwise COMPLETED. Reading only TODAY's row (the previous behaviour)
 * let a challenge that broke its limit on an earlier day settle as a win.
 *
 * ## Third outcome: ENDED_UNVERIFIED
 *
 * "Otherwise COMPLETED" is only honest while the history is trustworthy, and after an
 * uninstall + reinstall it is not: Room is destroyed, no server pass settles Soft challenges (solo
 * reconciliation queries `mode == "hard"`), so the still-`active` Firestore doc is re-pulled onto an
 * empty log set and `any { limitExceeded }` fail-opens to a fabricated win.
 *
 * The verdict is therefore three-way, and the ORDER is the whole design:
 *
 *  1. **A breach log wins outright.** Positive proof is conclusive regardless of how much of the
 *     history is missing, so a surviving `limitExceeded` row from a previous install still settles
 *     FAILED through the ordinary "limit_exceeded" path. This check runs FIRST precisely so
 *     provenance can never mask a real loss.
 *  2. **No breach + never observed by this install** ⇒ [ChallengeStatus.ENDED_UNVERIFIED]
 *     (`isUnobservedSoftChallenge`). Only a would-be COMPLETED is ever downgraded.
 *  3. **No breach + observed live** ⇒ COMPLETED, exactly as before.
 *
 * The asymmetry in 1 vs 2 is deliberate: absence of evidence is not evidence of absence (a clean day
 * writes no DailyLog at all on EMUI, so "no rows" is equally consistent with a perfect run and with
 * a destroyed history), whereas presence of evidence IS evidence. That is also why the trigger for 2
 * is install provenance and never log emptiness — keying on emptiness would deny the neutral outcome
 * to nobody and the win to exactly the users who behaved best.
 *
 * ## Not a verdict at all: abstaining
 *
 * The three outcomes above all assume the history was actually read. When the read is CANCELLED —
 * the ViewModel is cleared, the service is torn down, the process is going away mid-read — no read
 * happened, so there is no evidence of any kind and the honest answer is "not now". The history
 * read ([com.finite.focus.util.readHistoryForVerdict]) rethrows the cancellation, this function
 * unwinds, and every challenge still `active` stays `active` for the next pass.
 *
 * That abstain sits deliberately ABOVE the ladder, never inside it: it is the absence of a verdict,
 * not a fourth one, so FAILED > ENDED_UNVERIFIED > COMPLETED is untouched. In particular a
 * cancelled read must NOT settle ENDED_UNVERIFIED — that outcome answers a different question
 * (could this install ever have observed the window?) and `isUnobservedSoftChallenge` says "no"
 * for a perfectly healthy live install, so using it here would fabricate a verdict just as surely
 * as COMPLETED did. An ordinary unreadable history is unchanged and still fails open to "clean".
 *
 * Placement note: this runs AFTER the whole sync (the Dashboard awaits `syncJob.join()` first), so
 * step 2's restored nested `dailyLogs` are already in Room when rule 1 is evaluated. Deciding this
 * at sync-insert time instead would decide it before the evidence arrived.
 */
class SettleEndedSoftChallengesUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val dailyLogRepository: DailyLogRepository,
    private val installInfoProvider: InstallInfoProvider,
) {
    /**
     * Which end-date predicate fires the settlement. The distinction is load-bearing and is the
     * whole reason this parameter exists — see [DateUtils.hasReachedEnd] vs [DateUtils.hasPassedEnd].
     */
    enum class Trigger {
        /**
         * [DateUtils.hasReachedEnd] (`>=`) — fires ON the final day, matching the 23:59
         * [DailyEvaluationWorker] run whose own usage IS part of the challenge. Correct for a
         * once-a-day-at-23:59 caller and for the Dashboard backstop that stands in for it.
         */
        SETTLEMENT,

        /**
         * [DateUtils.hasPassedEnd] (`>`) — fires only once the final day is FULLY over. Required for
         * anything on the enforcement path, which is reached at arbitrary times: `>=` invoked at
         * 00:05 on the last day would free the app a full day early AND settle on a history that is
         * still being written. Same predicate the group enforcement-stop uses.
         */
        ENFORCEMENT_STOP,
    }

    suspend operator fun invoke(trigger: Trigger = Trigger.SETTLEMENT) {
        val now = System.currentTimeMillis()
        val challenges = challengeRepository.getActiveChallengesList().getOrElse { e ->
            Timber.w(e, "SettleEndedSoftChallenges: could not read active challenges — skipping")
            return
        }

        for (challenge in challenges) {
            // ── Soft-only, money-free guards ─────────────────────────────────────────
            if (challenge.mode != ChallengeMode.SOFT) continue
            if (challenge.stripePaymentIntentId != null) continue
            if (challenge.groupChallengeId != null) continue

            // Open-ended challenges are streak-based and run forever — never auto-complete.
            if (DateUtils.isOpenEnded(challenge.startDate, challenge.endDate)) continue

            val ended = when (trigger) {
                // Same end-of-challenge trigger the worker uses. Naturally handles the
                // 23:59-wrinkle: whenever the app is opened after endDate has passed, this fires
                // immediately.
                Trigger.SETTLEMENT ->
                    DateUtils.hasReachedEnd(challenge.startDate, challenge.endDate, now)
                Trigger.ENFORCEMENT_STOP ->
                    DateUtils.hasPassedEnd(challenge.endDate, now)
            }
            if (!ended) continue

            // Whole-challenge verdict, matching DailyEvaluationWorker.challengeViolated and the
            // server's `logsSnap.docs.some(d => d.limitExceeded === true)`. Fail-open on a read
            // error — an unreadable history must never manufacture a loss.
            //
            // ── ABSTAIN POINT ────────────────────────────────────────────────────────
            // This line, not the ladder below, is where a cancelled read leaves.
            // [readHistoryForVerdict] rethrows a cancellation rather than handing back an empty
            // history, so nothing after it runs — no verdict is computed and no
            // updateChallengeStatus is reached, for this challenge or any still in the loop.
            // Every one of them keeps `status = 'active'` and the next pass (23:59 worker, next
            // Dashboard open, next enforcement stop) decides on a history it could actually read.
            //
            // Unwinding out of `invoke()` IS the abstain, and is preferred to `continue`: the
            // scope is already gone, so continuing would only re-throw on the next challenge's
            // read. Callers launch this in a coroutine, where a cancellation is ordinary
            // completion — see the class doc.
            val violated = readHistoryForVerdict(
                challengeId = challenge.id,
                caller = "SettleEndedSoftChallenges",
            ) { dailyLogRepository.getLogsForChallengeOnce(challenge.id) }
                .any { it.limitExceeded }

            // ── Verdict, in strict precedence order ──────────────────────────────────
            // 1. A breach log is POSITIVE PROOF and outranks everything below. One
            //    `limitExceeded` row is conclusive no matter how incomplete the rest of the
            //    history is, so this branch is reached even for a challenge this install never
            //    observed — a surviving breach log from the previous install still loses the
            //    challenge, through the normal FAILED/"limit_exceeded" path.
            // 2. No breach, but this install could never have observed the challenge's window
            //    ⇒ ENDED_UNVERIFIED. Absence of a breach log is NOT proof of a clean run: a
            //    reinstall destroys Room, and a clean day writes no row anyway, so the two are
            //    indistinguishable. Only a would-be COMPLETED is ever downgraded here — the loss
            //    verdict above is never weakened.
            // 3. Otherwise the challenge was observed live and settles normally as COMPLETED.
            val finalStatus = when {
                violated -> ChallengeStatus.FAILED
                isUnobservedSoftChallenge(
                    mode = challenge.mode,
                    stripePaymentIntentId = challenge.stripePaymentIntentId,
                    groupChallengeId = challenge.groupChallengeId,
                    startDate = challenge.startDate,
                    endDate = challenge.endDate,
                    installedAt = installInfoProvider.installedAt,
                ) -> ChallengeStatus.ENDED_UNVERIFIED
                else -> ChallengeStatus.COMPLETED
            }

            challengeRepository.updateChallengeStatus(
                challenge.id,
                finalStatus,
                failReason = if (finalStatus == ChallengeStatus.FAILED) "limit_exceeded" else null
            ).onFailure { e ->
                Timber.e(e, "SettleEndedSoftChallenges: failed to finalise %s", challenge.id)
            }.onSuccess {
                Timber.i(
                    "SettleEndedSoftChallenges: %s reached end → %s (trigger=%s)",
                    challenge.id, finalStatus, trigger
                )
            }
        }
    }
}
