package com.detox.app.util

import java.util.Calendar

object DateUtils {
    const val MILLIS_PER_DAY = 86_400_000L

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
