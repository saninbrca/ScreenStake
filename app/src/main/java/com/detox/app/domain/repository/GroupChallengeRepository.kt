package com.detox.app.domain.repository

import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.Taunt
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

    /**
     * Updates only timeUsedMinutes for [userId] in the Firestore leaderboard.
     * Does NOT touch opensToday. Used by background polling in UsageTrackingService.
     */
    suspend fun updateParticipantTimeUsed(groupId: String, userId: String, timeUsedMinutes: Int)

    /**
     * Atomically increments [userId]'s opensToday by 1 in the Firestore document.
     * Must only be called when the user consciously taps "Ja, öffnen" in the overlay.
     */
    suspend fun incrementParticipantOpensToday(groupId: String, userId: String)

    /**
     * Updates the local [GroupChallengeEntity] to mark [userId]'s participant status as "failed",
     * then adds the challenge's app packages to [TrackedAppEventBus.failedPackagesToday] so the
     * AccessibilityService stops showing the overlay for those apps immediately.
     */
    suspend fun markParticipantFailedLocally(groupId: String, userId: String)

    // ── Taunts ─────────────────────────────────────────────────────────────────

    suspend fun sendTaunt(
        groupId: String,
        fromUserId: String,
        fromDisplayName: String,
        toUserId: String,
        message: String,
    ): Result<Unit>

    suspend fun countTauntsToday(groupId: String, fromUserId: String, toUserId: String): Int

    fun observeUnshownTaunts(groupId: String, toUserId: String): Flow<List<Taunt>>

    suspend fun markTauntShown(groupId: String, tauntId: String): Result<Unit>
}
