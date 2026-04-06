package com.detox.app.domain.repository

import com.detox.app.domain.model.GroupChallenge
import kotlinx.coroutines.flow.Flow

interface GroupChallengeRepository {
    fun getGroupChallenges(): Flow<List<GroupChallenge>>
    suspend fun getGroupChallengeById(groupId: String): GroupChallenge?
    suspend fun saveGroupChallenge(groupChallenge: GroupChallenge): Result<Unit>
    fun observeGroupChallenge(groupId: String): Flow<GroupChallenge?>
    suspend fun fetchGroupChallengeByCode(code: String): Result<GroupChallenge?>
    /**
     * Fetches a group challenge from Firestore by its [groupId] and caches the result in Room.
     * Called after creation to ensure Room holds the Firestore-authoritative document.
     */
    suspend fun fetchAndCacheById(groupId: String): Result<GroupChallenge?>
}
