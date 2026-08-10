package com.finite.focus.domain.model

import com.finite.focus.util.DateUtils
import kotlin.math.roundToInt

/**
 * The ONE client-side mirror of Stripe's `capture_method` branch, so money copy can never drift
 * from the moment the stake actually leaves the card.
 *
 * Server truth (`createPaymentIntent`, `functions/src/index.ts`):
 * ```
 * const isImmediateCapture = isGroupChallenge ? false : durationDays > 7;
 * capture_method: isImmediateCapture ? "automatic" : "manual"
 * ```
 *
 * COPY ONLY. Nothing here decides, gates or triggers a capture/refund — money authority stays
 * server-side (invariant #2), and every loss path keeps its capture gate (#6). If the server
 * branch ever moves, move it HERE too: every screen that talks about the stake reads this object.
 */
object StakeCapture {

    /** A SOLO Hard Mode challenge longer than this many days is auto-captured at creation. */
    const val IMMEDIATE_CAPTURE_ABOVE_DAYS = 7

    /**
     * True when Stripe takes the stake at creation time (`capture_method: "automatic"`), i.e. the
     * "charge now, refund 80% on success" model. False = a hold that is only captured on a breach.
     *
     * Group buy-ins are ALWAYS manual capture (5-day authorization window), whatever the duration.
     */
    fun isImmediateCapture(durationDays: Int, isGroupChallenge: Boolean = false): Boolean =
        if (isGroupChallenge) false else durationDays > IMMEDIATE_CAPTURE_ABOVE_DAYS

    /**
     * True when the stake behind an ALREADY RUNNING challenge has left the card.
     *  - Solo Hard Mode: only the auto-capture band above. A ≤7-day stake is still just a hold,
     *    captured on the loss path (`DailyEvaluationWorker` / `PermissionCheckWorker` / abandon).
     *  - Group: always. `startGroupChallenge` captures EVERY participant's manual-capture hold
     *    before the challenge can go ACTIVE, and the local mirror row only exists from then on.
     */
    fun isStakeChargedWhileActive(durationDays: Int, isGroupChallenge: Boolean): Boolean =
        isGroupChallenge || isImmediateCapture(durationDays)

    /** [isStakeChargedWhileActive] for a stored challenge, whose duration lives in its date span. */
    fun isStakeChargedWhileActive(challenge: Challenge): Boolean =
        isStakeChargedWhileActive(
            durationDays = durationDaysOf(challenge.startDate, challenge.endDate),
            isGroupChallenge = challenge.groupChallengeId != null,
        )

    /**
     * Recovers the `durationDays` that was sent to `createPaymentIntent` from a stored challenge —
     * the exact inverse of [DateUtils.endOfDayMillis] (endDate = 23:59:59.999 of the day
     * `startDate + durationDays - 1`). Day keys, not raw millis: a DST shift inside the span must
     * never move the 7/8-day copy boundary.
     */
    fun durationDaysOf(startDate: Long, endDate: Long): Int {
        if (startDate <= 0L || endDate <= 0L || endDate < startDate) return 0
        val spanDays = (DateUtils.dayKey(endDate) - DateUtils.dayKey(startDate)).toDouble() /
            DateUtils.MILLIS_PER_DAY
        return spanDays.roundToInt() + 1
    }
}
