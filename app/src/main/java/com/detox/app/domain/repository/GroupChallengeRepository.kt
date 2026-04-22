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
    /**
     * Writes (or updates) a local [ChallengeEntity] so the AccessibilityService and
     * OverlayManager can track this group challenge identically to a solo Hard Mode challenge.
     *
     * Should be called when the challenge transitions to ACTIVE.  The local challenge id is
     * "group_${groupChallenge.groupId}" so it can later be looked up or removed.
     *
     * @param userId Firebase UID of the current user — used to find their paymentIntentId.
     */
    suspend fun syncGroupChallengeToLocalTracking(groupChallenge: GroupChallenge, userId: String): Result<Unit>
    /**
     * Removes the local shadow [ChallengeEntity] for a group challenge (marks it COMPLETED or FAILED).
     * Called when the group challenge ends.
     */
    suspend fun finishLocalGroupChallenge(groupId: String, succeeded: Boolean): Result<Unit>

    /**
     * Fetches all group challenges for [userId] from Firestore, upserts them into Room,
     * and syncs [ChallengeEntity] rows for ACTIVE and finished challenges.
     * Call this on FriendsHub open to keep the local cache fresh.
     */
    suspend fun refreshFromFirestore(userId: String): Result<Unit>

    /**
     * Returns the first active group challenge that includes [packageName], or null if none.
     * Used by use cases to prevent duplicate challenges for the same app.
     */
    suspend fun getActiveGroupChallengeForApp(packageName: String): GroupChallenge?

    /**
     * Starts a long-lived Firestore snapshot listener for all of [userId]'s group challenges.
     * Automatically syncs ACTIVE challenges to Room (so AccessibilityService can block apps)
     * and deletes Room entries when challenges complete or are cancelled.
     * Should be called when the user logs in.
     */
    fun startSyncingForUser(userId: String)

    /**
     * Cancels the background Firestore listener started by [startSyncingForUser].
     * Should be called when the user logs out.
     */
    fun stopSyncing()

    /**
     * Updates [userId]'s opensToday and timeUsedMinutes in the Firestore document
     * for real-time leaderboard display. Fire-and-forget — failures are logged only.
     */
    suspend fun updateParticipantStats(groupId: String, userId: String, opensToday: Int, timeUsedMinutes: Int)
}
