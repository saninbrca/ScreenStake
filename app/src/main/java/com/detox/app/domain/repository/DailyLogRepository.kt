package com.detox.app.domain.repository

import com.detox.app.domain.model.DailyLog
import kotlinx.coroutines.flow.Flow

interface DailyLogRepository {
    suspend fun insertDailyLog(log: DailyLog): Result<Unit>
    suspend fun getLogForDate(challengeId: String, date: Long): Result<DailyLog?>
    /** Live Flow for a specific challenge + date — emits whenever that row changes in Room. */
    fun observeLogForDate(challengeId: String, date: Long): Flow<DailyLog?>
    fun getLogsForChallenge(challengeId: String): Flow<List<DailyLog>>
    suspend fun getLogsForChallengeOnce(challengeId: String): List<DailyLog>
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

    /** Returns the remaining budget milliseconds for a TIME_BUDGET challenge. 0 if no row exists. */
    suspend fun getBudgetRemainingMs(challengeId: String, date: Long): Result<Long>
    /** Upserts budgetUsedMs / budgetRemainingMs. Creates a placeholder row if none exists. */
    suspend fun updateBudgetStateMs(challengeId: String, date: Long, usedMs: Long, remainingMs: Long): Result<Unit>

    /**
     * Counts consecutive completed days (before [beforeDate]) where [limitExceeded] is false.
     * Returns 0 if there are no completed days or on any error.
     */
    suspend fun getStreakForChallenge(challengeId: String, beforeDate: Long): Result<Int>
}
