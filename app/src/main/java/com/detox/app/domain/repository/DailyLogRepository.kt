package com.detox.app.domain.repository

import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.ThresholdFlags
import kotlinx.coroutines.flow.Flow

interface DailyLogRepository {
    suspend fun insertDailyLog(log: DailyLog): Result<Unit>
    suspend fun getLogForDate(challengeId: String, date: Long): Result<DailyLog?>
    fun getLogsForChallenge(challengeId: String): Flow<List<DailyLog>>
    fun observeLogsForDate(date: Long): Flow<List<DailyLog>>
    suspend fun getConsciousOpens(challengeId: String, date: Long): Result<Int>
    suspend fun upsertConsciousOpens(challengeId: String, date: Long, count: Int): Result<Unit>
    suspend fun getOverlayPausedMs(challengeId: String, date: Long): Result<Long>
    suspend fun addOverlayPausedMs(challengeId: String, date: Long, additionalMs: Long): Result<Unit>
    /** Returns the remaining budget minutes for a TIME_BUDGET challenge. 0 if no row exists yet. */
    suspend fun getBudgetRemainingMinutes(challengeId: String, date: Long): Result<Int>
    /**
     * Upserts the budget state for a TIME_BUDGET challenge.
     * If no log row exists for today, creates a placeholder with the budget values.
     * If one exists, runs an UPDATE to overwrite budgetUsedMinutes / budgetRemainingMinutes.
     */
    suspend fun updateBudgetState(challengeId: String, date: Long, used: Int, remaining: Int): Result<Unit>

    // ── Threshold notification flags ───────────────────────────────────────────

    /**
     * Returns which of the 50 / 75 / 90 % threshold notifications have already been
     * fired for this challenge today.  Returns all-false if no row exists yet.
     */
    suspend fun getThresholdFlags(challengeId: String, date: Long): Result<ThresholdFlags>

    /**
     * Marks the given [threshold] (50, 75 or 90) as notified for today.
     * If no log row exists yet it creates a minimal placeholder row first so the
     * subsequent SQL UPDATE has a target.
     */
    suspend fun markThresholdNotified(challengeId: String, date: Long, threshold: Int): Result<Unit>

    /**
     * Counts consecutive completed days (before [beforeDate]) where [limitExceeded] is false.
     * Returns 0 if there are no completed days or on any error.
     */
    suspend fun getStreakForChallenge(challengeId: String, beforeDate: Long): Result<Int>
}
