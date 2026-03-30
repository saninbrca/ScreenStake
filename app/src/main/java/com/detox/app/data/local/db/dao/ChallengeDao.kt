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

    @Query("SELECT * FROM challenges WHERE appPackageName = :packageName AND status = 'active' LIMIT 1")
    suspend fun getActiveChallengeForApp(packageName: String): ChallengeEntity?

    @Query("UPDATE challenges SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("SELECT * FROM challenges ORDER BY createdAt DESC")
    fun getAllChallenges(): Flow<List<ChallengeEntity>>
}
