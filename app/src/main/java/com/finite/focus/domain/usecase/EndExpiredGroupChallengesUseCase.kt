package com.finite.focus.domain.usecase

import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.util.DateUtils
import timber.log.Timber
import javax.inject.Inject

/**
 * Stops enforcing a GROUP challenge once its end date has passed, computed ENTIRELY ON-DEVICE.
 *
 * The incident this exists for: a group challenge that ended on 1 Aug kept showing the overlay and
 * locked the user out of the monitored app at 00:30 on 2 Aug — with no connectivity. Nothing in the
 * enforcement path looked at `endDate`; the only way out of `status = 'active'` was
 * `completeGroupChallenge` succeeding (network) or a Firestore sync reporting the group COMPLETED
 * (network). Offline, the block was permanent. Past end ⇒ no enforcement, period — and that verdict
 * must not need a server.
 *
 * MONEY-FREE BY CONSTRUCTION. This settles, captures, refunds and deletes NOTHING:
 *  - the only write is the Room `status` column, via
 *    [ChallengeRepository.endGroupChallengeLocally] (no Firestore, no Cloud Function, no Stripe);
 *  - the buy-in and the server's settlement obligation are exactly as they were;
 *  - settlement still fires from its own triggers — `MainActivity.checkExpiredGroupChallenges` and
 *    `PermissionCheckWorker.checkExpiredGroupChallenges` — which iterate the `group_challenges`
 *    table and its `GroupChallengeStatus`, NOT this shadow row. Ending enforcement here therefore
 *    cannot strand a payout, and [ChallengeStatus.ENDED] is overwritten with COMPLETED/FAILED by
 *    `finishLocalGroupChallenge` the moment settlement lands.
 *
 * Group rows ONLY (`groupChallengeId != null`). Solo challenges are untouched: Soft settles via
 * [SettleEndedSoftChallengesUseCase] and Hard via the capture-gated worker paths, both of which need
 * a real win/loss verdict this use case deliberately does not compute.
 */
class EndExpiredGroupChallengesUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository,
) {
    suspend operator fun invoke() {
        val challenges = challengeRepository.getActiveChallengesList().getOrElse { e ->
            Timber.w(e, "EndExpiredGroupChallenges: could not read active challenges — skipping")
            return
        }

        for (challenge in challenges) {
            if (challenge.groupChallengeId == null) continue
            // Defensive: a group challenge is always fixed-end, but the sentinel must never be
            // read as "long past due" if one ever appears.
            if (DateUtils.isOpenEnded(challenge.startDate, challenge.endDate)) continue
            if (!DateUtils.hasPassedEnd(challenge.endDate)) continue

            challengeRepository.endGroupChallengeLocally(challenge.id)
                .onSuccess {
                    Timber.i(
                        "EndExpiredGroupChallenges: %s (group %s) is past its end date — " +
                            "enforcement stopped locally, awaiting server settlement",
                        challenge.id, challenge.groupChallengeId
                    )
                }
                .onFailure { e ->
                    Timber.e(e, "EndExpiredGroupChallenges: could not end %s locally", challenge.id)
                }
        }
    }
}
