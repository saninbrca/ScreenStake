package com.detox.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.detox.app.data.local.db.entity.DailyLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyLog(log: DailyLogEntity)

    @Query("SELECT * FROM daily_logs WHERE challengeId = :challengeId ORDER BY date DESC")
    fun getLogsForChallenge(challengeId: String): Flow<List<DailyLogEntity>>

    @Query("SELECT * FROM daily_logs WHERE challengeId = :challengeId ORDER BY date DESC")
    suspend fun getLogsForChallengeOnce(challengeId: String): List<DailyLogEntity>

    @Query("SELECT * FROM daily_logs WHERE date = :date")
    fun observeLogsForDate(date: Long): Flow<List<DailyLogEntity>>

    @Query("SELECT * FROM daily_logs WHERE challengeId = :challengeId AND date = :date LIMIT 1")
    suspend fun getLogForDate(challengeId: String, date: Long): DailyLogEntity?

    /** Live Flow — emits a new value whenever this row changes in Room. Used by Detail screens. */
    @Query("SELECT * FROM daily_logs WHERE challengeId = :challengeId AND date = :date LIMIT 1")
    fun observeLogForDate(challengeId: String, date: Long): Flow<DailyLogEntity?>

    @Query("SELECT COALESCE(consciousOpens, 0) FROM daily_logs WHERE challengeId = :challengeId AND date = :date LIMIT 1")
    suspend fun getConsciousOpens(challengeId: String, date: Long): Int

    @Query("SELECT COALESCE(overlayPausedMs, 0) FROM daily_logs WHERE challengeId = :challengeId AND date = :date LIMIT 1")
    suspend fun getOverlayPausedMs(challengeId: String, date: Long): Long

    @Query("UPDATE daily_logs SET overlayPausedMs = overlayPausedMs + :additionalMs WHERE challengeId = :challengeId AND date = :date")
    suspend fun addOverlayPausedMs(challengeId: String, date: Long, additionalMs: Long)

    @Query("SELECT COALESCE(budgetRemainingMinutes, 0) FROM daily_logs WHERE challengeId = :challengeId AND date = :date LIMIT 1")
    suspend fun getBudgetRemainingMinutes(challengeId: String, date: Long): Int

    @Query("UPDATE daily_logs SET budgetUsedMinutes = :used, budgetRemainingMinutes = :remaining WHERE challengeId = :challengeId AND date = :date")
    suspend fun updateBudgetState(challengeId: String, date: Long, used: Int, remaining: Int)

    @Query("SELECT COALESCE(budgetRemainingMs, 0) FROM daily_logs WHERE challengeId = :challengeId AND date = :date LIMIT 1")
    suspend fun getBudgetRemainingMs(challengeId: String, date: Long): Long

    @Query("UPDATE daily_logs SET budgetUsedMs = :usedMs, budgetRemainingMs = :remainingMs WHERE challengeId = :challengeId AND date = :date")
    suspend fun updateBudgetStateMs(challengeId: String, date: Long, usedMs: Long, remainingMs: Long)

    /** INSERT OR IGNORE: creates a skeleton row so placeholder-dependent writes have a target. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(log: DailyLogEntity)

    /** INSERT OR REPLACE — creates the row if absent, overwrites if present. Use for debug writes and any path that can't guarantee the row exists. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: DailyLogEntity)

    /**
     * Returns the [limitExceeded] flag for the most recent [limit] completed days before
     * [beforeDate], ordered newest-first.  Used to calculate the current streak:
     * the caller counts how many consecutive leading `false` values there are.
     */
    @Query("SELECT limitExceeded FROM daily_logs WHERE challengeId = :challengeId AND date < :beforeDate ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentLimitExceededFlags(challengeId: String, beforeDate: Long, limit: Int = 60): List<Boolean>

    @Query("SELECT * FROM daily_logs ORDER BY date DESC LIMIT 90")
    suspend fun getAllLogsOrderedByDateDesc(): List<DailyLogEntity>

    @Query("SELECT * FROM daily_logs WHERE date = :date")
    suspend fun getAllForDate(date: Long): List<DailyLogEntity>

    @Query("DELETE FROM daily_logs WHERE date = :date")
    suspend fun deleteAllForDate(date: Long)

    @Query("UPDATE daily_logs SET consciousOpens = :count WHERE challengeId = :challengeId AND date = :date")
    suspend fun updateConsciousOpens(challengeId: String, date: Long, count: Int)
}
