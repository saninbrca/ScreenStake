package com.detox.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.domain.model.ThresholdFlags
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyLog(log: DailyLogEntity)

    @Query("SELECT * FROM daily_logs WHERE challengeId = :challengeId ORDER BY date DESC")
    fun getLogsForChallenge(challengeId: String): Flow<List<DailyLogEntity>>

    @Query("SELECT * FROM daily_logs WHERE date = :date")
    fun observeLogsForDate(date: Long): Flow<List<DailyLogEntity>>

    @Query("SELECT * FROM daily_logs WHERE challengeId = :challengeId AND date = :date LIMIT 1")
    suspend fun getLogForDate(challengeId: String, date: Long): DailyLogEntity?

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

    // ── Threshold notification flags ───────────────────────────────────────────

    /**
     * Returns which threshold notifications (50 / 75 / 90 %) have already fired today,
     * or null if no log row exists yet for this challenge+date.
     * Room maps the three selected columns directly to [ThresholdFlags].
     */
    @Query("SELECT notified50, notified75, notified90 FROM daily_logs WHERE challengeId = :challengeId AND date = :date LIMIT 1")
    suspend fun getThresholdFlags(challengeId: String, date: Long): ThresholdFlags?

    @Query("UPDATE daily_logs SET notified50 = 1 WHERE challengeId = :challengeId AND date = :date")
    suspend fun markNotified50(challengeId: String, date: Long)

    @Query("UPDATE daily_logs SET notified75 = 1 WHERE challengeId = :challengeId AND date = :date")
    suspend fun markNotified75(challengeId: String, date: Long)

    @Query("UPDATE daily_logs SET notified90 = 1 WHERE challengeId = :challengeId AND date = :date")
    suspend fun markNotified90(challengeId: String, date: Long)

    /** INSERT OR IGNORE: creates a skeleton row so UPDATE-based flag setters have a target. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(log: DailyLogEntity)

    /**
     * Returns the [limitExceeded] flag for the most recent [limit] completed days before
     * [beforeDate], ordered newest-first.  Used to calculate the current streak:
     * the caller counts how many consecutive leading `false` values there are.
     */
    @Query("SELECT limitExceeded FROM daily_logs WHERE challengeId = :challengeId AND date < :beforeDate ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentLimitExceededFlags(challengeId: String, beforeDate: Long, limit: Int = 60): List<Boolean>

    @Query("SELECT * FROM daily_logs ORDER BY date DESC LIMIT 90")
    suspend fun getAllLogsOrderedByDateDesc(): List<DailyLogEntity>
}
