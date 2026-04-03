package com.detox.app.domain.model

/**
 * Per-challenge statistics computed from the full daily log history.
 *
 * @param challenge        the challenge this data belongs to
 * @param totalDaysTracked total number of days that have a daily log entry
 * @param daysSucceeded    days where the limit was NOT exceeded
 * @param daysExceeded     days where the limit WAS exceeded
 * @param totalPointsEarned sum of all pointsEarned across all daily logs
 * @param currentStreak    consecutive days of success ending today (or the latest log date)
 * @param bestStreak       longest consecutive success streak ever recorded
 * @param recentLogs       last 7 daily logs, newest first — used for the 7-day dot row in the UI
 */
data class AppStatistics(
    val challenge: Challenge,
    val totalDaysTracked: Int,
    val daysSucceeded: Int,
    val daysExceeded: Int,
    val totalPointsEarned: Int,
    val currentStreak: Int,
    val bestStreak: Int,
    val recentLogs: List<DailyLog>
) {
    /** Success rate as a float 0..1, safe against zero division. */
    val successRate: Float
        get() = if (totalDaysTracked == 0) 0f
                else daysSucceeded.toFloat() / totalDaysTracked.toFloat()
}
