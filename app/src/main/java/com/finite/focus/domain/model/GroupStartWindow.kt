package com.finite.focus.domain.model

import com.finite.focus.util.DateUtils
import java.util.Calendar
import java.util.TimeZone

/**
 * Bounds for the OPTIONAL scheduled start date of a Group Challenge (create wizard, step 5).
 *
 * Why a bound has to exist: every participant authorizes their buy-in when they join, and
 * `createGroupChallenge` (functions/src/index.ts) stamps
 * `authorizationExpiresAt = Date.now() + 5 * MILLIS_PER_DAY` on the group document.
 * `startGroupChallenge` CAPTURES those authorizations, and its preflight refuses to start unless
 * every PaymentIntent is still `requires_capture`. So a start date past that window produces a
 * challenge that is GUARANTEED never to start: everyone joins, everyone pays, the group sits in
 * WAITING until `expireGroupChallenge` cancels every hold, and the whole thing silently refunds.
 * No money is lost — but nobody gets the challenge they paid for either. The picker must never
 * offer such a date.
 *
 * [WINDOW_DAYS] MIRRORS THE CLOUD FUNCTION. The window is not remotely configurable (it is a
 * literal in `createGroupChallenge`), so this is the second copy, not a second source of truth —
 * change both together.
 */
object GroupStartWindow {

    /** Mirrors `createGroupChallenge`: `authorizationExpiresAt = createdAt + WINDOW_DAYS`. */
    const val WINDOW_DAYS = 5L

    /**
     * Worst-case lag between a scheduled start falling due and the start actually firing.
     *
     * `GroupChallengeAutoStartWorker` is 24h-periodic, so a date that comes due just after a run
     * waits a full day for the next one. Subtracted from the window so that even the LATEST
     * selectable date still has time to fire while the holds are alive. The hourly server-side
     * sweep shortens this in practice, but it ships behind a disabled flag, so the client worker's
     * cadence stays the honest worst case.
     */
    const val AUTO_START_MAX_LATENCY_MS = DateUtils.MILLIS_PER_DAY

    /** UTC midnight of the day containing [ms] — the space Compose's DatePicker works in. */
    fun utcDayStart(ms: Long): Long = ms - (ms % DateUtils.MILLIS_PER_DAY)

    /**
     * The last instant a scheduled start may be set to and still be captured before the card
     * holds lapse. [createdAtMs] is "now" at wizard time; the real `createdAt` is stamped a few
     * seconds later by the CF, which makes this bound very slightly conservative — the safe
     * direction.
     */
    fun latestStartMillis(createdAtMs: Long): Long =
        createdAtMs + WINDOW_DAYS * DateUtils.MILLIS_PER_DAY - AUTO_START_MAX_LATENCY_MS

    /** Today (UTC) — a start date in the past would simply fire on the next sweep. */
    fun earliestSelectableUtcDay(nowMs: Long): Long = utcDayStart(nowMs)

    /** The latest whole UTC day whose midnight still falls inside [latestStartMillis]. */
    fun latestSelectableUtcDay(nowMs: Long): Long = utcDayStart(latestStartMillis(nowMs))

    /**
     * The single predicate behind both the picker's greying-out and the ViewModel's backstop, so
     * the two can never disagree. [utcDayMs] is a UTC-midnight value as produced by
     * `DatePickerState.selectedDateMillis`.
     */
    fun isSelectable(utcDayMs: Long, nowMs: Long): Boolean =
        utcDayMs in earliestSelectableUtcDay(nowMs)..latestSelectableUtcDay(nowMs)

    /** Years the picker may offer — a 4-day span touches two only across a New Year boundary. */
    fun selectableYears(nowMs: Long): IntRange {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = earliestSelectableUtcDay(nowMs)
        val first = cal.get(Calendar.YEAR)
        cal.timeInMillis = latestSelectableUtcDay(nowMs)
        return first..cal.get(Calendar.YEAR)
    }
}
