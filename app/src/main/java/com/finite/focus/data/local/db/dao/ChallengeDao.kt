package com.finite.focus.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.finite.focus.data.local.db.entity.ChallengeEntity
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
     * EVERY terminal challenge whose result surface has not been shown yet, oldest first — the
     * backing query for the Dashboard's result QUEUE.
     *
     * Replaces five separate `LIMIT 1` queries (completed-hard / completed-soft / failed-hard /
     * failed-soft / ended-unverified). Those made the Dashboard structurally incapable of showing
     * more than one result per visit: it read one row, and the only way to reach the next was a tab
     * round-trip that recreated the ViewModel. When several challenges settled at once — the normal
     * case after a reinstall or a permission-deadline sweep — the rest silently queued up behind
     * navigation. The caller now takes the whole list and drains it on dismiss.
     *
     * `ORDER BY endDate, id` makes the drain order deterministic and chronological. The old queries
     * had no ORDER BY at all, so the single row SQLite happened to return was arbitrary; `id` is the
     * tiebreaker for a mass settlement where several rows share an `endDate`.
     *
     * `groupChallengeId IS NULL OR = ''` excludes group shadow rows, and is load-bearing: a shadow
     * row carries `mode = 'hard'`, and since group rows now PERSIST as terminal instead of being
     * deleted (so they appear in History), including them would pop the SOLO Hard Mode win dialog —
     * with solo refund copy — for a group settlement. The group outcome has its own surfaces
     * (`NotificationHelper.sendGroupChallengePayoutReceived` + the group detail screen). Soft rows
     * never carry a group id, so this is exactly the old per-query behaviour, unified.
     *
     * `'ended'` is deliberately NOT included: that is the group-local "awaiting settlement" state,
     * which has no result surface at all. Only `completed`, `failed` and `ended_unverified` do.
     */
    @Query("""
        SELECT * FROM challenges
        WHERE completionShown = 0
          AND status IN ('completed', 'failed', 'ended_unverified')
          AND (groupChallengeId IS NULL OR groupChallengeId = '')
        ORDER BY endDate ASC, id ASC
    """)
    suspend fun getUnshownTerminalChallenges(): List<ChallengeEntity>

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

    @Query("SELECT * FROM challenges WHERE status IN ('completed', 'failed', 'ended', 'ended_unverified') ORDER BY endDate DESC LIMIT 3")
    suspend fun getRecentFinishedChallenges(): List<ChallengeEntity>

    /**
     * Everything the History screen shows. `'ended'` is included so a group challenge whose end
     * date passed on-device is RECORDED and visible while its server settlement is still pending
     * (see [com.finite.focus.domain.model.ChallengeStatus.ENDED]) — the alternative, dropping it until
     * settlement lands, is exactly the "challenge vanished" bug.
     * `'ended_unverified'` is included for the same reason: the challenge really happened and must
     * stay in the user's record, even though its outcome could not be established.
     */
    @Query("SELECT * FROM challenges WHERE status IN ('completed', 'failed', 'ended', 'ended_unverified') ORDER BY endDate DESC")
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
