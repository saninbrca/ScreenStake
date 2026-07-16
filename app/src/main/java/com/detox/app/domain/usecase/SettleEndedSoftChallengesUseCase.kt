package com.detox.app.domain.usecase

import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.util.DateUtils
import timber.log.Timber
import javax.inject.Inject

/**
 * On-app-open backstop that finalises FIXED-END-DATE Soft Mode challenges whose end date passed
 * while the app was closed (or while the EMUI-throttled periodic [DailyEvaluationWorker] failed to
 * run). This is an ADDITIONAL safety net — it never replaces the worker, and it re-uses the worker's
 * exact end-of-challenge trigger ([DateUtils.hasReachedEnd]) and status decision so the two paths
 * can never diverge.
 *
 * Strictly SOFT-only and money-free:
 *  - `mode == SOFT` && `stripePaymentIntentId == null` — never touches Hard Mode capture/refund/
 *    settlement gates.
 *  - `groupChallengeId == null` — group shadow rows settle via their Cloud Function, not here.
 *  - Open-ended challenges ([DateUtils.isOpenEnded]) run indefinitely by design and are NEVER
 *    completed here.
 *
 * The FAILED-vs-COMPLETED choice mirrors the worker's already-logged short-circuit: FAILED iff
 * today's DailyLog recorded a limit breach, otherwise COMPLETED. (A Soft challenge that actually
 * exceeded its limit is already flipped to FAILED intra-day by OverlayManager and is therefore no
 * longer in the active list, so in practice this backstop resolves the win case.)
 */
class SettleEndedSoftChallengesUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val dailyLogRepository: DailyLogRepository,
) {
    suspend operator fun invoke() {
        val now = System.currentTimeMillis()
        val today = DateUtils.todayKey()
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

            // Same end-of-challenge trigger the worker uses. Naturally handles the 23:59-wrinkle:
            // whenever the app is opened after endDate has passed, this fires immediately.
            if (!DateUtils.hasReachedEnd(challenge.startDate, challenge.endDate, now)) continue

            val limitExceeded = dailyLogRepository.getLogForDate(challenge.id, today)
                .getOrNull()?.limitExceeded == true
            val finalStatus = if (limitExceeded) ChallengeStatus.FAILED else ChallengeStatus.COMPLETED

            challengeRepository.updateChallengeStatus(
                challenge.id,
                finalStatus,
                failReason = if (finalStatus == ChallengeStatus.FAILED) "limit_exceeded" else null
            ).onFailure { e ->
                Timber.e(e, "SettleEndedSoftChallenges: failed to finalise %s", challenge.id)
            }.onSuccess {
                Timber.i(
                    "SettleEndedSoftChallenges: %s reached end → %s (on-app-open backstop)",
                    challenge.id, finalStatus
                )
            }
        }
    }
}
