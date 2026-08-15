package com.finite.focus.presentation.screens.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.finite.focus.R
import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.DailyLog
import com.finite.focus.domain.model.LimitType
import com.finite.focus.util.DateUtils
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The wording and the few derived figures shared by all four result surfaces
 * ([ChallengeSuccessDialog] win, [ChallengeFailedDialog] Hard loss,
 * [com.finite.focus.presentation.screens.softfail.SoftFailResultScreen] Soft loss,
 * [ChallengeUnverifiedDialog] neutral).
 *
 * DISPLAY ONLY. Nothing here reads or writes a verdict, money, or enforcement state — the surfaces
 * are handed an already-settled challenge and merely describe it.
 *
 * Two rules the old copy broke and that everything below exists to keep:
 *
 *  1. **Say one true thing per figure.** The win card used to show a per-day average AND a conscious-
 *     opens total — the same fact twice, and the average rendered as "Ø 0,0" whenever no usage rows
 *     existed, which reads as broken data rather than as a clean run. The replacement ([winMetricLine])
 *     is derived from the challenge's OWN limit fields, which always exist, so it cannot degrade into
 *     a zero.
 *  2. **Day counts are CALENDAR days** ([daysHeldCalendar], [calendarDurationDays]), never a raw
 *     start→end span and never a DailyLog row count. Rows are only
 *     written when the app was used or the nightly worker ran, so on EMUI a perfectly clean day often
 *     has no row at all and a row count silently undercounts the user's actual streak.
 */

/** Resolves `endDate` to a timestamp, handling both timestamp and legacy day-offset formats. */
internal val Challenge.resolvedEndDate: Long
    get() = if (endDate > 1_700_000_000_000L) endDate
    else startDate + (endDate * DateUtils.MILLIS_PER_DAY)

/** True when this challenge uses the open-ended (~100-year) sentinel end date. */
internal val Challenge.isOpenEndedChallenge: Boolean
    get() = DateUtils.isOpenEnded(startDate, resolvedEndDate)

/**
 * The challenge's length in CALENDAR days, counting the start day (a challenge that starts today and
 * ends tomorrow night is 2 days) — the figure EVERY result surface shows, win and loss alike.
 *
 * Thin wrapper over [DateUtils.calendarDurationDays], which owns the definition; this only resolves
 * the two Challenge-specific end-date quirks first:
 *  - legacy day-offset endDates ([resolvedEndDate]), and
 *  - open-ended challenges, whose ~100-year sentinel end date must never be rendered as a day count
 *    (it surfaced as a huge "N Tage"). Those clamp to days-elapsed-so-far. Open-ended challenges are
 *    not completed by the worker/backstop, so reaching a result surface with one is defensive.
 *
 * There is deliberately no raw-span variant beside it: the span truncates (a 7-day challenge created
 * at 10:00 spans 6.58 days and read as "6 Tage"), which understated the win subtitle and, on the
 * loss line's held-vs-total ratio, told a user who broke the limit on the final day "6 of 6 days
 * held" — a win, phrased as a loss.
 */
internal val Challenge.calendarDurationDays: Int
    get() {
        val effectiveEnd = if (isOpenEndedChallenge) System.currentTimeMillis() else resolvedEndDate
        return DateUtils.calendarDurationDays(startDate, effectiveEnd)
    }

/** The daily limit the user set, in the unit of their [LimitType]; null when the type has no number. */
internal val Challenge.dailyLimitValue: Int?
    get() = when (limitType) {
        LimitType.TIME -> limitValueMinutes.takeIf { it > 0 }
        LimitType.SESSIONS -> limitValueSessions?.takeIf { it > 0 }
        LimitType.TIME_BUDGET -> (dailyBudgetMinutes ?: limitValueMinutes).takeIf { it > 0 }
        LimitType.TIME_WINDOW -> null
    }

/**
 * The first day whose DailyLog breached the daily limit, with the numbers behind it — the concrete
 * "here is what happened" a loss surface leads with.
 *
 * FIRST breach, not the last: a challenge is lost at the moment it first goes over, and Soft Mode
 * (which settles at the end rather than on the breach) can accumulate several breached days.
 */
data class BreachDetail(
    /** 1-based day of the challenge on which the breach happened. */
    val dayNumber: Int,
    /** What the user actually spent that day, in the limit's own unit. */
    val actualValue: Int,
    /** The daily limit that was exceeded, same unit. */
    val limitValue: Int,
    val limitType: LimitType,
)

/**
 * Derives the [BreachDetail] for [challenge] from its [logs], or null when no clean, self-consistent
 * breach can be told — in which case the surface falls back to [failReasonStringRes].
 *
 * Null (fall back) whenever any of these hold, because a half-derived number is worse than an honest
 * generic sentence:
 *  - the loss was not a limit breach ([Challenge.failReason] says abandon / permission / usage / …),
 *  - the limit type carries no daily number (TIME_WINDOW / block-only),
 *  - no log is flagged `limitExceeded`, or the flagged log's usage does not actually exceed the limit
 *    (stale row from a limit that was later changed, or a breach recorded without usage figures).
 */
internal fun firstBreachDetail(challenge: Challenge, logs: List<DailyLog>): BreachDetail? {
    // Only a limit breach may be narrated as one. "abandon"/"permission_violation"/"usage_violation"
    // have their own true sentence, and a Soft challenge can carry a breached day AND end by
    // abandon — blaming the wrong cause would be a lie the user can spot. A null reason (legacy
    // rows) is allowed through: there the breach log is the only evidence we have.
    if (challenge.failReason != null && challenge.failReason != "limit_exceeded") return null

    val breach = logs.filter { it.limitExceeded }.minByOrNull { it.date } ?: return null
    val limit = challenge.dailyLimitValue ?: return null

    val actual = when (challenge.limitType) {
        LimitType.TIME -> breach.totalMinutes
        LimitType.SESSIONS -> breach.consciousOpens
        // Round the consumed budget UP: 30.2 min against a 30 min budget is over, not "30 of 30".
        LimitType.TIME_BUDGET -> ceil(breach.budgetUsedMs / 60_000.0).toInt()
        LimitType.TIME_WINDOW -> return null
    }
    if (actual <= limit) return null

    return BreachDetail(
        dayNumber = dayNumberOf(challenge.startDate, breach.date),
        actualValue = actual,
        limitValue = limit,
        limitType = challenge.limitType,
    )
}

/** 1-based challenge day containing [timestampMs]. Rounded so a DST shift can't drop a day. */
private fun dayNumberOf(startMs: Long, timestampMs: Long): Int {
    val spanMs = DateUtils.dayKey(timestampMs) - DateUtils.dayKey(startMs)
    val elapsed = (spanMs.toDouble() / DateUtils.MILLIS_PER_DAY).roundToLong()
    return (elapsed + 1).coerceAtLeast(1L).toInt()
}

/**
 * Full CALENDAR days held before the challenge ended — never `logs.size` (see the file header).
 *
 * Anchored on the breach day when one exists (the newest, so a Soft challenge that limped through
 * several breached days is credited to its last one), otherwise on now, clamped to the challenge's
 * own end so a result opened days later cannot keep counting up.
 */
internal fun daysHeldCalendar(
    challenge: Challenge,
    logs: List<DailyLog>,
    nowMs: Long = System.currentTimeMillis(),
): Int {
    val startKey = DateUtils.dayKey(challenge.startDate)
    val breachDay = logs.filter { it.limitExceeded }.maxOfOrNull { it.date }

    val failMoment = when {
        breachDay != null -> breachDay
        challenge.isOpenEndedChallenge -> nowMs
        else -> minOf(nowMs, challenge.resolvedEndDate)
    }
    return ((failMoment - startKey) / DateUtils.MILLIS_PER_DAY).toInt().coerceAtLeast(0)
}

/**
 * The ONE metric line on a win: what the user promised themselves, stated as kept.
 *
 * Read from the challenge's own limit fields rather than aggregated usage, so it is exactly as
 * reliable as the verdict itself — a won challenge means the limit held every day, and that
 * sentence stays true whether or not a single usage row survived.
 */
@Composable
internal fun winMetricLine(challenge: Challenge): String = when (challenge.limitType) {
    LimitType.SESSIONS -> challenge.dailyLimitValue
        ?.let { stringResource(R.string.result_metric_sessions, it) }
        ?: stringResource(R.string.result_metric_generic)

    LimitType.TIME -> challenge.dailyLimitValue
        ?.let { stringResource(R.string.result_metric_time, durationLabel(it)) }
        ?: stringResource(R.string.result_metric_generic)

    LimitType.TIME_BUDGET -> challenge.dailyLimitValue
        ?.let { stringResource(R.string.result_metric_budget, it) }
        ?: stringResource(R.string.result_metric_generic)

    // TIME_WINDOW doubles as the 24/7 block sentinel on the website path (see ScheduleSummary):
    // a window only exists when a start time was actually configured.
    LimitType.TIME_WINDOW -> if (challenge.scheduleStartTime != null) {
        stringResource(R.string.result_metric_window)
    } else {
        stringResource(R.string.result_metric_block)
    }
}

/** The same limit, stated as the goal it was — what a loss surface shows instead of a win claim. */
@Composable
internal fun goalLine(challenge: Challenge): String = when (challenge.limitType) {
    LimitType.SESSIONS -> challenge.dailyLimitValue
        ?.let { stringResource(R.string.result_goal_sessions, it) }
        ?: stringResource(R.string.result_goal_generic)

    LimitType.TIME -> challenge.dailyLimitValue
        ?.let { stringResource(R.string.result_goal_time, durationLabel(it)) }
        ?: stringResource(R.string.result_goal_generic)

    LimitType.TIME_BUDGET -> challenge.dailyLimitValue
        ?.let { stringResource(R.string.result_goal_budget, it) }
        ?: stringResource(R.string.result_goal_generic)

    LimitType.TIME_WINDOW -> if (challenge.scheduleStartTime != null) {
        stringResource(R.string.result_goal_window)
    } else {
        stringResource(R.string.result_goal_block)
    }
}

/**
 * WHY the challenge was lost: the concrete first breach when one is cleanly derivable
 * ([firstBreachDetail]), otherwise the honest generic cause from [failReasonStringRes].
 */
@Composable
internal fun lossReasonLine(challenge: Challenge, logs: List<DailyLog>): String {
    val breach = firstBreachDetail(challenge, logs)
        ?: return stringResource(failReasonStringRes(challenge.failReason))

    return when (breach.limitType) {
        LimitType.TIME -> stringResource(
            R.string.result_breach_time, breach.dayNumber, breach.actualValue, breach.limitValue
        )
        LimitType.SESSIONS -> stringResource(
            R.string.result_breach_sessions, breach.dayNumber, breach.actualValue, breach.limitValue
        )
        LimitType.TIME_BUDGET -> stringResource(
            R.string.result_breach_budget, breach.dayNumber, breach.actualValue, breach.limitValue
        )
        // firstBreachDetail never returns TIME_WINDOW — it has no daily number to compare.
        LimitType.TIME_WINDOW -> stringResource(failReasonStringRes(challenge.failReason))
    }
}

/** "45 Min." / "1 Std." / "1 Std. 30 Min." — minutes rendered the way a person reads them. */
@Composable
internal fun durationLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.result_duration_minutes, rest)
        rest == 0 -> stringResource(R.string.result_duration_hours, hours)
        else -> stringResource(R.string.result_duration_hours_minutes, hours, rest)
    }
}
