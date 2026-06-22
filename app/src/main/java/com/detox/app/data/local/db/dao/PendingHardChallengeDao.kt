package com.detox.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.detox.app.data.local.db.entity.PendingHardChallengeEntity

@Dao
interface PendingHardChallengeDao {

    /** Upsert — re-tapping create for the same cid overwrites the stale payload. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: PendingHardChallengeEntity)

    @Query("SELECT * FROM pending_hard_challenges WHERE challengeId = :challengeId LIMIT 1")
    suspend fun getByChallengeId(challengeId: String): PendingHardChallengeEntity?

    /** Most recently created pending record — used when the in-memory cid was lost on recreation. */
    @Query("SELECT * FROM pending_hard_challenges ORDER BY paymentIntentCreatedAt DESC LIMIT 1")
    suspend fun getLatest(): PendingHardChallengeEntity?

    /** All pending records — scanned by the startup recovery net. */
    @Query("SELECT * FROM pending_hard_challenges")
    suspend fun getAll(): List<PendingHardChallengeEntity>

    @Query("DELETE FROM pending_hard_challenges WHERE challengeId = :challengeId")
    suspend fun deleteByChallengeId(challengeId: String)
}
