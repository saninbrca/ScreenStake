package com.detox.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.detox.app.data.local.db.entity.ChallengeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChallengeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChallenge(challenge: ChallengeEntity)

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

    @Query("SELECT * FROM challenges ORDER BY createdAt DESC")
    fun getAllChallenges(): Flow<List<ChallengeEntity>>

    /** Marks the congratulations overlay as shown so it does not appear again. */
    @Query("UPDATE challenges SET completionShown = 1 WHERE id = :id")
    suspend fun markCompletionShown(id: String)

    /** Returns the first Hard Mode challenge that completed successfully but whose overlay has not yet been shown. */
    @Query("SELECT * FROM challenges WHERE status = 'completed' AND mode = 'hard' AND completionShown = 0 LIMIT 1")
    suspend fun getUnshownCompletedHardChallenge(): ChallengeEntity?
}
