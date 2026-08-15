package com.finite.focus.util

import java.util.Calendar
import kotlin.math.roundToLong

object DateUtils {
    const val MILLIS_PER_DAY = 86_400_000L

    /**
     * Sentinel duration (≈100 years) representing an open-ended Soft Mode challenge ("Kein
     * Enddatum"). It drives a far-future [endOfDayMillis] so the challenge never reaches its
     * endDate (DailyEvaluationWorker therefore never completes it). Single source of truth —
     * referenced by both the creation ViewModel and CreateChallengeUseCase's duration validation,
     * so the validator can recognise it instead of rejecting it as out-of-range.
     */
    const val NO_END_DATE_DAYS = 36500

    /**
     * True when a challenge's resolved end-date [endMs] is the open-ended sentinel (created from
     * [NO_END_DATE_DAYS]). Real challenges are capped at 1..365 days (CreateChallengeUseCase), so only
     * the ~36500-day sentinel can reach this bound — a genuine long challenge can never be
     * misclassified. Span-based (not exact-millis ==) to stay robust against timezone/DST drift between
     * creation and display. Display-only: never affects completion math or money logic.
     */
    fun isOpenEnded(startMs: Long, endMs: Long): Boolean =
        startMs > 0L && endMs > 0L && (endMs - startMs) / MILLIS_PER_DAY >= NO_END_DATE_DAYS - 1

    /**
     * The end-of-challenge trigger shared by [com.finite.focus.service.DailyEvaluationWorker] and the
     * on-app-open soft backstop ([com.finite.focus.domain.usecase.SettleEndedSoftChallengesUseCase]),
     * so the two completion paths can never diverge. Open-endedness is a SEPARATE guard
     * ([isOpenEnded]) — callers that must never complete open-ended challenges check that first.
     *
     * Compares CALENDAR DAYS, not milliseconds. [endOfDayMillis] resolves [endMs] to 23:59:59.999
     * of the final day, while the worker fires at ~23:59:00 — so a raw `now >= endMs` was still
     * false on the final day and settlement slipped to the NEXT run, 23:59 of day N+1, whose usage
     * is not part of the challenge at all (and is therefore essentially always clean). Comparing
     * day keys makes the final day's own run settle the challenge.
     *
     * The former `|| durationDays == 1` escape hatch is gone: it made a 2-day challenge
     * (span ≈ 1.x days → 1) report "ended" on its very first evaluation. Day-key comparison covers
     * the genuine 1-day case correctly — start and end fall on the same day key.
     */
    fun hasReachedEnd(startMs: Long, endMs: Long, now: Long): Boolean =
        dayKey(now) >= dayKey(endMs)

    /**
     * True once the calendar day of [nowMs] is STRICTLY AFTER the day [endMs] falls on — i.e. the
     * challenge's last day is fully over. Offline-safe: pure local clock, no network, no server
     * round-trip. Used by [com.finite.focus.domain.usecase.EndExpiredGroupChallengesUseCase] and by
     * OverlayManager's enforcement funnel to stop blocking a group challenge that has run out.
     *
     * Strictly `>`, unlike [hasReachedEnd]'s `>=`, and the difference is load-bearing: settlement
     * fires ON the final day (the 23:59 worker run settles the day's own usage), while ENFORCEMENT
     * must cover that whole final day. A `>=` here would free the app at 00:00 of the last day.
     *
     * Day-key based for the same reason as [hasReachedEnd]: `endMs` is 23:59:59.999 local
     * ([endOfDayMillis], invariant #18) and a raw millis compare drifts across DST/timezone
     * changes. Money-free — this predicate never settles, captures, refunds or deletes anything.
     */
    fun hasPassedEnd(endMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        endMs > 0L && dayKey(nowMs) > dayKey(endMs)

    /** Midnight (00:00:00.000, local) of the calendar day containing [timestampMs]. */
    fun dayKey(timestampMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timestampMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun todayKey(): Long = dayKey(System.currentTimeMillis())

    fun nextMidnightTimestamp(): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * A challenge's length in CALENDAR days, counting the start day — the ONLY day-count that may be
     * shown to a user, and the exact inverse of [endOfDayMillis]: a challenge created for 7 days
     * reads back as 7.
     *
     * The single definition. Every user-facing "X Tage" routes here (result dialogs, History,
     * Profile payouts) precisely because the obvious raw-span form truncates: a 7-day challenge
     * created at 10:00 ends on day 7 at 23:59, a span of 6.58 days, which naive `(end - start) /
     * MILLIS_PER_DAY` renders as "6 Tage".
     *
     * DISPLAY ONLY. Settlement timing is decided by [hasReachedEnd] / [hasPassedEnd] and capture
     * mode by `StakeCapture.durationDaysOf` — none of them read this, and none of them may.
     *
     * Day keys, not raw millis, and the elapsed span is ROUNDED for the same reason day keys are
     * used at all: a DST shift inside the challenge makes the span 23 h or 25 h short/long of a
     * whole number of days, and truncating that drops a day off a spring-forward challenge.
     */
    fun calendarDurationDays(startMs: Long, endMs: Long): Int {
        val spanMs = (dayKey(endMs) - dayKey(startMs)).toDouble()
        val elapsedDays = (spanMs / MILLIS_PER_DAY).roundToLong()
        return (elapsedDays + 1).coerceAtLeast(1L).toInt()
    }

    /**
     * Returns 23:59:59.999 of the day that is [durationDays] days after [startMs].
     * Note: durationDays - 1 because startMs already counts as day 1.
     */
    fun endOfDayMillis(startMs: Long, durationDays: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = startMs
            add(Calendar.DAY_OF_YEAR, durationDays - 1)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return calendar.timeInMillis
    }

    fun addBusinessDays(timestampMs: Long, days: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestampMs
        var remaining = days
        while (remaining > 0) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                remaining--
            }
        }
        return cal.timeInMillis
    }
}
