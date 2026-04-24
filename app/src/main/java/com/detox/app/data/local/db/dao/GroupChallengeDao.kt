package com.detox.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.detox.app.data.local.db.entity.GroupChallengeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupChallengeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GroupChallengeEntity)

    @Query("SELECT * FROM group_challenges ORDER BY startDate DESC")
    fun getAll(): Flow<List<GroupChallengeEntity>>

    @Query("SELECT * FROM group_challenges WHERE groupId = :groupId LIMIT 1")
    suspend fun getById(groupId: String): GroupChallengeEntity?

    @Query("UPDATE group_challenges SET status = :status WHERE groupId = :groupId")
    suspend fun updateStatus(groupId: String, status: String)

    @Query("UPDATE group_challenges SET participantsJson = :json WHERE groupId = :groupId")
    suspend fun updateParticipants(groupId: String, json: String)

    @Query("""
        SELECT * FROM group_challenges WHERE status = 'active' AND (
            appPackageNames = :packageName OR
            appPackageNames LIKE :packageName || ',%' OR
            appPackageNames LIKE '%,' || :packageName OR
            appPackageNames LIKE '%,' || :packageName || ',%'
        ) LIMIT 1
    """)
    suspend fun getActiveGroupChallengeForApp(packageName: String): GroupChallengeEntity?

    @Query("SELECT * FROM group_challenges WHERE status IN (:statuses) ORDER BY endDate DESC")
    suspend fun getByStatus(statuses: List<String>): List<GroupChallengeEntity>

    @Query("SELECT * FROM group_challenges ORDER BY endDate DESC")
    suspend fun getAllList(): List<GroupChallengeEntity>
}
