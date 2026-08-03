package com.detox.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.detox.app.data.local.db.entity.ChallengeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChallengeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChallenge(challenge: ChallengeEntity)

    /** Updates an existing challenge row without deleting it first — no FK CASCADE on DailyLogs. */
    @Update
    suspend fun updateChallenge(challenge: ChallengeEntity)

    @Query("SELECT * FROM challenges WHERE id = :id")
    suspend fun getChallengeById(id: String): ChallengeEntity?

    @Query("SELECT * FROM challenges WHERE status = 'active' ORDER BY createdAt DESC")
    fun getActiveChallenges(): Flow<List<ChallengeEntity>>

    @Query("SELECT * FROM challenges WHERE status = 'active'")
    suspend fun getActiveChallengesList(): List<ChallengeEntity>

    @Query("""
        SELECT * FROM challenges WHERE status = 'active' AND (
            appPackageName = :packageName OR
            appPackageNames = :packageName OR
            appPackageNames LIKE :packageName || ',%' OR
            appPackageNames LIKE '%,' || :packageName OR
            appPackageNames LIKE '%,' || :packageName || ',%'
        ) LIMIT 1
    """)
    suspend fun getActiveChallengeForApp(packageName: String): ChallengeEntity?

    @Query("UPDATE challenges SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /**
     * Writes the loss-cause column ONLY (never status), so the "sync writes status via updateStatus()
     * only" invariant is preserved. Used at local loss time and by the sync active→terminal reconcile
     * for server-detected losses.
     */
    @Query("UPDATE challenges SET failReason = :reason WHERE id = :id")
    suspend fun updateFailReason(id: String, reason: String?)

    @Query("SELECT * FROM challenges ORDER BY createdAt DESC")
    fun getAllChallenges(): Flow<List<ChallengeEntity>>

    /** Marks the congratulations overlay as shown so it does not appear again. */
    @Query("UPDATE challenges SET completionShown = 1 WHERE id = :id")
    suspend fun markCompletionShown(id: String)

    /**
     * Returns the first SOLO Hard Mode challenge that completed successfully but whose overlay has
     * not yet been shown.
     *
     * The `groupChallengeId IS NULL` half is load-bearing: a group shadow row carries
     * `mode = 'hard'`, and since group rows now PERSIST as terminal instead of being deleted (so
     * they show up in History), a mode-only filter would pop the SOLO Hard Mode win dialog — with
     * solo refund copy — for a group settlement. The group outcome has its own surfaces
     * (`NotificationHelper.sendGroupChallengePayoutReceived` + the group detail screen).
     */
    @Query("SELECT * FROM challenges WHERE status = 'completed' AND mode = 'hard' AND completionShown = 0 AND (groupChallengeId IS NULL OR groupChallengeId = '') LIMIT 1")
    suspend fun getUnshownCompletedHardChallenge(): ChallengeEntity?

    /** Returns the first Soft Mode challenge that completed successfully but whose overlay has not yet been shown. */
    @Query("SELECT * FROM challenges WHERE status = 'completed' AND mode = 'soft' AND completionShown = 0 LIMIT 1")
    suspend fun getUnshownCompletedSoftChallenge(): ChallengeEntity?

    /** Returns the first Soft Mode challenge that failed but whose result screen has not yet been shown. */
    @Query("SELECT * FROM challenges WHERE status = 'failed' AND mode = 'soft' AND completionShown = 0 LIMIT 1")
    suspend fun getUnshownFailedSoftChallenge(): ChallengeEntity?

    /**
     * Returns the first SOLO Hard Mode challenge that failed but whose overlay has not yet been
     * shown. `groupChallengeId IS NULL` for the same reason as
     * [getUnshownCompletedHardChallenge] — a persisted group shadow row must not raise the solo
     * loss dialog.
     */
    @Query("SELECT * FROM challenges WHERE status = 'failed' AND mode = 'hard' AND completionShown = 0 AND (groupChallengeId IS NULL OR groupChallengeId = '') LIMIT 1")
    suspend fun getUnshownFailedHardChallenge(): ChallengeEntity?

    @Query("SELECT COUNT(*) FROM challenges WHERE status = 'completed'")
    suspend fun getCompletedCount(): Int

    @Query("SELECT * FROM challenges")
    suspend fun getAllChallengesList(): List<ChallengeEntity>

    /**
     * ⚠ NEVER call this for a group shadow row (`id = "group_<groupId>"`) — invariant #30. The
     * History screen reads finished challenges from this table, so deleting the row erases the
     * challenge from the user's record entirely; that was the "settled group challenge vanished"
     * bug. Mark it terminal instead (`finishLocalGroupChallenge` / `endGroupChallengeLocally`) —
     * enforcement stops the same way, since only `status = 'active'` enforces.
     *
     * Currently unreferenced, deliberately kept for solo/cleanup use.
     */
    @Query("DELETE FROM challenges WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM challenges WHERE status IN ('completed', 'failed', 'ended') ORDER BY endDate DESC LIMIT 3")
    suspend fun getRecentFinishedChallenges(): List<ChallengeEntity>

    /**
     * Everything the History screen shows. `'ended'` is included so a group challenge whose end
     * date passed on-device is RECORDED and visible while its server settlement is still pending
     * (see [com.detox.app.domain.model.ChallengeStatus.ENDED]) — the alternative, dropping it until
     * settlement lands, is exactly the "challenge vanished" bug.
     */
    @Query("SELECT * FROM challenges WHERE status IN ('completed', 'failed', 'ended') ORDER BY endDate DESC")
    suspend fun getFinishedSoloChallenges(): List<ChallengeEntity>

    @Query("UPDATE challenges SET endDate = :endDate WHERE id = :id")
    suspend fun updateEndDate(id: String, endDate: Long)

    @Query("UPDATE challenges SET redemptionEligible = :eligible, redemptionDeadline = :deadline, redemptionShowAfter = :showAfter, redemptionRefundAmount = :refundAmount, redemptionDays = :redemptionDays, redemptionLimit = :redemptionLimit WHERE id = :id")
    suspend fun updateRedemptionInfo(id: String, eligible: Int, deadline: Long, showAfter: Long, refundAmount: Int, redemptionDays: Int, redemptionLimit: Int)

    @Query("UPDATE challenges SET redemptionChallengeId = :redemptionChallengeId WHERE id = :id")
    suspend fun updateRedemptionChallengeId(id: String, redemptionChallengeId: String)

    @Query("UPDATE challenges SET pending_limit_value = :value, pending_limit_applies_at = :appliesAt WHERE id = :id")
    suspend fun updatePendingLimit(id: String, value: Int, appliesAt: Long)

    /** Applies the pending limit to the appropriate field and clears the pending columns. Pass only the field that matches the challenge's limitType; leave others null. */
    @Query(
        "UPDATE challenges SET " +
        "pending_limit_value = NULL, pending_limit_applies_at = NULL, " +
        "limitValueSessions = COALESCE(:newSessions, limitValueSessions), " +
        "limitValueMinutes = COALESCE(:newMinutes, limitValueMinutes), " +
        "dailyBudgetMinutes = COALESCE(:newBudget, dailyBudgetMinutes) " +
        "WHERE id = :id"
    )
    suspend fun applyPendingLimit(id: String, newSessions: Int?, newMinutes: Int?, newBudget: Int?)

    /** Returns failed Hard Mode Solo challenges with an active (not expired) redemption window that hasn't been used yet. */
    @Query("""
        SELECT * FROM challenges
        WHERE status = 'failed'
          AND mode = 'hard'
          AND (groupChallengeId IS NULL OR groupChallengeId = '')
          AND isRedemption = 0
          AND redemptionEligible = 1
          AND redemptionChallengeId IS NULL
          AND redemptionShowAfter <= :now
          AND redemptionDeadline > :now
        ORDER BY endDate DESC
    """)
    suspend fun getChallengesWithRedemptionAvailable(now: Long): List<ChallengeEntity>
}
