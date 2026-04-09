package com.detox.app.data.remote.firebase

import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.Participant
import com.detox.app.domain.model.ParticipantStatus
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupChallengeFirestoreService @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val collection get() = firestore.collection("groupChallenges")

    /** Writes (creates or overwrites) a group challenge document. */
    suspend fun saveGroupChallenge(groupChallenge: GroupChallenge) {
        try {
            collection.document(groupChallenge.groupId)
                .set(groupChallenge.toMap())
                .await()
            Timber.d("Firestore write result: ${groupChallenge.groupId}")
            Timber.d("GroupChallengeFirestore: saved %s", groupChallenge.groupId)
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: failed to save %s", groupChallenge.groupId)
        }
    }

    /** Looks up a group challenge by its 6-char invite code. */
    suspend fun fetchGroupChallengeByCode(code: String): GroupChallenge? {
        return try {
            val snapshot = collection
                .whereEqualTo("code", code.uppercase())
                .limit(1)
                .get()
                .await()
            snapshot.documents.firstOrNull()?.toGroupChallenge()
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: fetchByCode failed for code=%s", code)
            null
        }
    }

    /**
     * Fetches a single group challenge document by its [groupId] directly from the server,
     * bypassing the local Firestore cache. Used immediately after creation so we confirm
     * the document is server-committed before the detail screen is shown.
     */
    suspend fun fetchGroupChallengeById(groupId: String): GroupChallenge? {
        return try {
            val snapshot = collection.document(groupId).get(Source.SERVER).await()
            Timber.d(
                "GroupChallengeFirestore: fetchGroupChallengeById groupId=%s exists=%b",
                groupId, snapshot.exists()
            )
            snapshot.takeIf { it.exists() }?.toGroupChallenge()
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: fetchGroupChallengeById failed groupId=%s", groupId)
            null
        }
    }

    /** Real-time Firestore snapshot listener for a single group challenge. */
    fun observeGroupChallenge(groupId: String): Flow<GroupChallenge?> = callbackFlow {
        val registration = collection.document(groupId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.e(error, "GroupChallengeFirestore: observeGroupChallenge error %s", groupId)
                    close(error)
                    return@addSnapshotListener
                }
                val gc = snapshot?.takeIf { it.exists() }?.toGroupChallenge()
                Timber.d(
                    "GroupChallengeFirestore: snapshot for %s exists=%b status=%s participants=%d",
                    groupId, snapshot?.exists(), gc?.status, gc?.participants?.size
                )
                trySend(gc)
            }
        awaitClose { registration.remove() }
    }

    /**
     * Fetches all group challenges where the given user is either the creator
     * or a participant (denormalised participantUserIds array).
     */
    suspend fun fetchUserGroupChallenges(userId: String): List<GroupChallenge> {
        return try {
            val asCreator = collection
                .whereEqualTo("creatorUserId", userId)
                .get().await()
                .documents.mapNotNull { it.toGroupChallenge() }

            val asParticipant = collection
                .whereArrayContains("participantUserIds", userId)
                .get().await()
                .documents.mapNotNull { it.toGroupChallenge() }

            (asCreator + asParticipant)
                .distinctBy { it.groupId }
                .sortedByDescending { it.startDate }
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: fetchUserGroupChallenges failed uid=%s", userId)
            emptyList()
        }
    }

    // ── Mapping helpers ─────────────────────────────────────────────────────────

    private fun GroupChallenge.toMap(): Map<String, Any?> = mapOf(
        "groupId" to groupId,
        "code" to code,
        "creatorUserId" to creatorUserId,
        "appPackageNames" to appPackageNames.joinToString(","),
        "appDisplayName" to appDisplayName,
        "limitType" to limitType.name.lowercase(),
        "limitValueMinutes" to limitValueMinutes,
        "limitValueSessions" to limitValueSessions,
        "sessionDurationMinutes" to sessionDurationMinutes,
        "durationDays" to durationDays,
        "buyInCents" to buyInCents,
        "maxParticipants" to maxParticipants,
        "startDate" to startDate,
        "endDate" to endDate,
        "bonusEnabled" to bonusEnabled,
        "status" to status.name.lowercase(),
        "participants" to participants.map { p ->
            mapOf(
                "userId" to p.userId,
                "displayName" to p.displayName,
                "paymentIntentId" to p.paymentIntentId,
                "amountCents" to p.amountCents,
                "status" to p.status.name.lowercase(),
                "opensToday" to p.opensToday,
                "timeUsedMinutes" to p.timeUsedMinutes,
                "joinedAt" to p.joinedAt
            )
        },
        // Denormalised list for Firestore array-contains queries
        "participantUserIds" to participants.map { it.userId }
    )

    @Suppress("UNCHECKED_CAST")
    internal fun DocumentSnapshot.toGroupChallenge(): GroupChallenge? {
        return try {
            val d = data ?: return null
            val packages = (d["appPackageNames"] as? String)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()

            val rawParticipants = d["participants"] as? List<Map<String, Any>> ?: emptyList()
            val participants = rawParticipants.map { p ->
                Participant(
                    userId = p["userId"] as? String ?: "",
                    displayName = p["displayName"] as? String ?: "",
                    paymentIntentId = p["paymentIntentId"] as? String ?: "",
                    amountCents = (p["amountCents"] as? Long)?.toInt() ?: 0,
                    status = runCatching {
                        ParticipantStatus.valueOf(
                            (p["status"] as? String ?: "active").uppercase()
                        )
                    }.getOrDefault(ParticipantStatus.ACTIVE),
                    opensToday = (p["opensToday"] as? Long)?.toInt() ?: 0,
                    timeUsedMinutes = (p["timeUsedMinutes"] as? Long)?.toInt() ?: 0,
                    joinedAt = (p["joinedAt"] as? Long) ?: 0L
                )
            }

            GroupChallenge(
                groupId = d["groupId"] as? String ?: id,
                code = d["code"] as? String ?: "",
                creatorUserId = d["creatorUserId"] as? String ?: "",
                appPackageNames = packages,
                appDisplayName = d["appDisplayName"] as? String ?: "",
                limitType = runCatching {
                    LimitType.valueOf((d["limitType"] as? String ?: "time").uppercase())
                }.getOrDefault(LimitType.TIME),
                limitValueMinutes = (d["limitValueMinutes"] as? Long)?.toInt() ?: 60,
                limitValueSessions = (d["limitValueSessions"] as? Long)?.toInt(),
                sessionDurationMinutes = (d["sessionDurationMinutes"] as? Long)?.toInt() ?: 5,
                durationDays = (d["durationDays"] as? Long)?.toInt() ?: 7,
                buyInCents = (d["buyInCents"] as? Long)?.toInt() ?: 500,
                maxParticipants = (d["maxParticipants"] as? Long)?.toInt() ?: 5,
                startDate = (d["startDate"] as? Long) ?: 0L,
                endDate = (d["endDate"] as? Long) ?: 0L,
                bonusEnabled = d["bonusEnabled"] as? Boolean ?: false,
                status = runCatching {
                    GroupChallengeStatus.valueOf(
                        (d["status"] as? String ?: "waiting").uppercase()
                    )
                }.getOrDefault(GroupChallengeStatus.WAITING),
                participants = participants
            )
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: failed to parse document %s", id)
            null
        }
    }
}
