package com.detox.app.util

import java.util.Calendar

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
     * The end-of-challenge trigger shared by [com.detox.app.service.DailyEvaluationWorker] and the
     * on-app-open soft backstop ([com.detox.app.domain.usecase.SettleEndedSoftChallengesUseCase]),
     * so the two completion paths can never diverge. Mirrors the worker's inline condition exactly:
     * a challenge has reached its end when [now] is at/after its resolved [endMs], OR it is a
     * single-day challenge. Open-endedness is a SEPARATE guard ([isOpenEnded]) — callers that must
     * never complete open-ended challenges check that first.
     */
    fun hasReachedEnd(startMs: Long, endMs: Long, now: Long): Boolean {
        val durationDays = ((endMs - startMs) / MILLIS_PER_DAY).toInt()
        return now >= endMs || durationDays == 1
    }

    fun todayKey(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun nextMidnightTimestamp(): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

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
