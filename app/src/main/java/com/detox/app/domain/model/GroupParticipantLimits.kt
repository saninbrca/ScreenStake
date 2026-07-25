package com.detox.app.domain.model

import kotlin.math.floor

/**
 * Single source of truth for the Group Challenge participant cap.
 *
 * The cap is chosen by the creator on step 4 of the create wizard and stored as
 * `maxParticipants` on the group document. It is enforced SERVER-SIDE in
 * `joinGroupChallenge` and `confirmGroupJoin` (and validated on create in
 * `createGroupChallenge`) — the values here drive the input UI only.
 *
 * [HARD_MIN] is a real floor, not a style choice: `startGroupChallenge` cancels every
 * PaymentIntent and refuses to start a group with fewer than 2 participants, and the
 * detail screen's start button is gated on the same number. A cap of 1 would produce a
 * group that can never start.
 */
object GroupParticipantLimits {
    /** Absolute floor — below this a group can never start. Defensive clamp only. */
    const val HARD_MIN = 2

    /** Lowest value offered in the picker. */
    const val PICKER_MIN = 3

    /** Highest cap a creator can choose. Mirrored by the server-side create validation. */
    const val MAX = 20

    /** Pre-selected value — an untouched wizard behaves exactly as it did when the cap was fixed. */
    const val DEFAULT = MAX

    /** The values offered by the step-4 picker. */
    val PICKER_VALUES: List<Int> = (PICKER_MIN..MAX).toList()
}

/**
 * The most a single participant could ever receive from a group of [maxParticipants]
 * with a [stakeCents] buy-in: they finish clean and every other player fails.
 *
 * Mirrors `completeGroupChallenge` (functions/src/index.ts) EXACTLY — change both together:
 *  - own stake back at 80%       → `Math.floor(participantStake * 0.80)`
 *  - losers' pot minus a 10% fee → `failedPot - Math.floor(failedPot * 0.10)`
 *  - split across winners        → sole winner takes the whole distributable pot
 *
 * This is a ceiling, not an expectation: it needs a full lobby and every other participant
 * failing. If nobody fails, `nobodyFailed` settlement returns 100% of every stake instead.
 */
fun maxPossibleWinCents(stakeCents: Int, maxParticipants: Int): Int {
    val others = (maxParticipants - 1).coerceAtLeast(0)
    val failedPot = others * stakeCents
    val appFee = floor(failedPot * 0.10).toInt()
    val stakeRefund = floor(stakeCents * 0.80).toInt()
    return stakeRefund + (failedPot - appFee)
}
