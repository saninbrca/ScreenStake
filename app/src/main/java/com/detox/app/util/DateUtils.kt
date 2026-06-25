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
