package com.finite.focus.domain.usecase

import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.domain.repository.DailyLogRepository
import com.finite.focus.util.DateUtils
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
 */
class SettleEndedSoftChallengesUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val dailyLogRepository: DailyLogRepository,
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
            val violated = runCatching { dailyLogRepository.getLogsForChallengeOnce(challenge.id) }
                .getOrElse { e ->
                    Timber.e(e, "SettleEndedSoftChallenges: log history unreadable for %s — treating as clean", challenge.id)
                    emptyList()
                }
                .any { it.limitExceeded }
            val finalStatus = if (violated) ChallengeStatus.FAILED else ChallengeStatus.COMPLETED

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
