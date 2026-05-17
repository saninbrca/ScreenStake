package com.detox.app.data.remote.firebase

import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.util.DateUtils
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.Participant
import com.detox.app.domain.model.ParticipantStatus
import com.detox.app.domain.model.Taunt
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.Calendar
import java.util.UUID
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
     * Real-time listener for all group challenges where [userId] is the creator
     * or a participant. Combines two Firestore snapshot listeners and deduplicates.
     */
    fun observeUserGroupChallenges(userId: String): Flow<List<GroupChallenge>> {
        val asCreator = callbackFlow {
            val reg = collection.whereEqualTo("creatorUserId", userId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Timber.e(error, "GroupChallengeFirestore: observeUserGroupChallenges(creator) error uid=%s", userId)
                        close(error)
                        return@addSnapshotListener
                    }
                    trySend(snapshot?.documents?.mapNotNull { it.toGroupChallenge() } ?: emptyList())
                }
            awaitClose { reg.remove() }
        }
        val asParticipant = callbackFlow {
            val reg = collection.whereArrayContains("participantUserIds", userId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Timber.e(error, "GroupChallengeFirestore: observeUserGroupChallenges(participant) error uid=%s", userId)
                        close(error)
                        return@addSnapshotListener
                    }
                    trySend(snapshot?.documents?.mapNotNull { it.toGroupChallenge() } ?: emptyList())
                }
            awaitClose { reg.remove() }
        }
        return combine(asCreator, asParticipant) { a, b ->
            (a + b).distinctBy { it.groupId }
        }
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

    /**
     * Updates a single participant's opensToday and timeUsedMinutes in Firestore by
     * reading the current participants array, patching the matching entry, and writing it back.
     */
    suspend fun updateParticipantStats(
        groupId: String,
        userId: String,
        opensToday: Int,
        timeUsedMinutes: Int
    ) {
        try {
            val docRef = collection.document(groupId)
            val snapshot = docRef.get().await()
            val rawParticipants = parseRawParticipants(snapshot.get("participants"))
            if (rawParticipants.isEmpty()) return
            val updated = rawParticipants.map { p ->
                if ((p["userId"] as? String) == userId) {
                    p.toMutableMap().apply {
                        put("opensToday", opensToday.toLong())
                        put("timeUsedMinutes", timeUsedMinutes.toLong())
                    }
                } else p
            }
            docRef.update("participants", updated).await()
            Timber.d("Leaderboard updated: userId=$userId opens=$opensToday time=$timeUsedMinutes")
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: updateParticipantStats failed groupId=%s uid=%s", groupId, userId)
        }
    }

    /**
     * Updates only timeUsedMinutes for a participant, leaving opensToday untouched.
     * Used by the 60-second leaderboard polling in UsageTrackingService.
     */
    suspend fun updateParticipantTimeUsed(groupId: String, userId: String, timeUsedMinutes: Int) {
        try {
            val docRef = collection.document(groupId)
            val snapshot = docRef.get().await()
            val rawParticipants = parseRawParticipants(snapshot.get("participants"))
            if (rawParticipants.isEmpty()) return
            val index = rawParticipants.indexOfFirst { (it["userId"] as? String) == userId }
            if (index < 0) return
            val updated = rawParticipants.toMutableList()
            updated[index] = updated[index].toMutableMap().apply {
                put("timeUsedMinutes", timeUsedMinutes.toLong())
            }
            docRef.update("participants", updated).await()
            Timber.d("Leaderboard time updated: groupId=$groupId userId=$userId time=$timeUsedMinutes")
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: updateParticipantTimeUsed failed groupId=%s uid=%s", groupId, userId)
        }
    }

    /**
     * Increments opensToday by 1 for the given participant by reading the full participants array,
     * patching the matching entry, and writing the entire array back. This avoids dot-notation
     * partial updates that cause Firestore snapshots to return incomplete participant objects.
     */
    suspend fun incrementParticipantOpensToday(groupId: String, userId: String) {
        try {
            val docRef = collection.document(groupId)
            val snapshot = docRef.get().await()
            val rawParticipants = parseRawParticipants(snapshot.get("participants"))
            if (rawParticipants.isEmpty()) return
            val index = rawParticipants.indexOfFirst { (it["userId"] as? String) == userId }
            if (index < 0) {
                Timber.w("GroupChallengeFirestore: incrementParticipantOpensToday — userId=$userId not found in group=$groupId")
                return
            }
            val updated = rawParticipants.toMutableList()
            val current = updated[index]
            val currentOpens = (current["opensToday"] as? Long)?.toInt() ?: 0
            updated[index] = current.toMutableMap().apply {
                put("opensToday", (currentOpens + 1).toLong())
            }
            docRef.update("participants", updated).await()
            Timber.d("Group opensToday incremented: $groupId user=$userId")
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: incrementParticipantOpensToday failed groupId=%s uid=%s", groupId, userId)
        }
    }

    // ── Taunts ──────────────────────────────────────────────────────────────────

    private fun tauntsRef(groupId: String) = collection.document(groupId).collection("taunts")

    suspend fun sendTaunt(
        groupId: String,
        fromUserId: String,
        fromDisplayName: String,
        toUserId: String,
        message: String,
    ) {
        val tauntId = UUID.randomUUID().toString()
        tauntsRef(groupId).document(tauntId)
            .set(
                mapOf(
                    "fromUserId" to fromUserId,
                    "fromDisplayName" to fromDisplayName,
                    "toUserId" to toUserId,
                    "message" to message,
                    "createdAt" to System.currentTimeMillis(),
                    "shown" to false,
                )
            )
            .await()
        Timber.d("Taunt sent: $tauntId from=$fromUserId to=$toUserId group=$groupId")
    }

    suspend fun countTauntsToday(groupId: String, fromUserId: String, toUserId: String): Int {
        return try {
            val todayMidnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val snapshot = tauntsRef(groupId)
                .whereEqualTo("fromUserId", fromUserId)
                .get().await()
            snapshot.documents.count { doc ->
                doc.getString("toUserId") == toUserId &&
                    (doc.getLong("createdAt") ?: 0L) >= todayMidnight
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to count taunts in group $groupId")
            0
        }
    }

    fun observeUnshownTaunts(groupId: String, toUserId: String): Flow<List<Taunt>> = callbackFlow {
        val reg = tauntsRef(groupId)
            .whereEqualTo("toUserId", toUserId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.e(error, "Taunt listener error group=$groupId")
                    return@addSnapshotListener
                }
                val taunts = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val shown = doc.getBoolean("shown") ?: false
                        if (shown) return@mapNotNull null
                        Taunt(
                            id = doc.id,
                            fromUserId = doc.getString("fromUserId") ?: "",
                            fromDisplayName = doc.getString("fromDisplayName") ?: "",
                            toUserId = doc.getString("toUserId") ?: "",
                            message = doc.getString("message") ?: "",
                            createdAt = doc.getLong("createdAt") ?: 0L,
                            shown = false,
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse taunt ${doc.id}")
                        null
                    }
                } ?: emptyList()
                trySend(taunts)
            }
        awaitClose { reg.remove() }
    }

    suspend fun markTauntShown(groupId: String, tauntId: String) {
        try {
            tauntsRef(groupId).document(tauntId).update("shown", true).await()
        } catch (e: Exception) {
            Timber.e(e, "Failed to mark taunt $tauntId shown")
        }
    }

    // ── Parsing helpers ─────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun parseRawParticipants(raw: Any?): List<Map<String, Any>> = when (raw) {
        is List<*> -> raw as List<Map<String, Any>>
        is Map<*, *> -> (raw as Map<*, Map<String, Any>>).values.toList()
        else -> emptyList()
    }.also { list ->
        Timber.d("Participants parsed: count=${list.size} type=${raw?.javaClass?.simpleName}")
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
        "participantUserIds" to participants.map { it.userId },
        "blockedDomains" to blockedDomains.joinToString(",").ifEmpty { null },
        "authorizationExpiresAt" to authorizationExpiresAt.takeIf { it > 0L }
    )

    @Suppress("UNCHECKED_CAST")
    internal fun DocumentSnapshot.toGroupChallenge(): GroupChallenge? {
        return try {
            val d = data ?: return null
            val packages = (d["appPackageNames"] as? String)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()

            Timber.d("Raw participants from Firestore: ${this.get("participants")}")
            val rawParticipants = parseRawParticipants(d["participants"])
            val participants = rawParticipants.map { p ->
                Participant(
                    userId = p["userId"] as? String ?: "",
                    displayName = run {
                        val raw = p["displayName"] as? String ?: ""
                        when {
                            raw.isBlank() -> "Player"
                            raw.contains('@') -> raw.substringBefore('@')
                            else -> raw
                        }
                    },
                    paymentIntentId = p["paymentIntentId"] as? String ?: "",
                    amountCents = (p["amountCents"] as? Long)?.toInt() ?: 0,
                    status = runCatching {
                        ParticipantStatus.valueOf(
                            (p["status"] as? String ?: "active").uppercase()
                        )
                    }.getOrDefault(ParticipantStatus.ACTIVE),
                    opensToday = (p["opensToday"] as? Long)?.toInt() ?: 0,
                    timeUsedMinutes = (p["timeUsedMinutes"] as? Long)?.toInt() ?: 0,
                    joinedAt = (p["joinedAt"] as? Long) ?: 0L,
                    payoutStatus = p["payoutStatus"] as? String ?: "",
                    finalPayout = (p["finalPayout"] as? Long)?.toInt() ?: 0,
                )
            }

            val createdAt = when (val raw = this.get("createdAt")) {
                is com.google.firebase.Timestamp -> raw.toDate().time
                is Long -> raw
                else -> System.currentTimeMillis()
            }
            val startDate = (d.get("startDate") as? Number)?.toLong() ?: createdAt
            val endDate = (d.get("endDate") as? Number)?.toLong()
                ?: (startDate + 7L * DateUtils.MILLIS_PER_DAY)

            val now = System.currentTimeMillis()
            val progress = if (endDate > startDate) (now - startDate).toFloat() / (endDate - startDate).toFloat() else 0f
            val remainingMs = endDate - now
            Timber.d("startDate=${java.util.Date(startDate)} endDate=${java.util.Date(endDate)} progress=$progress remaining=${java.util.concurrent.TimeUnit.MILLISECONDS.toDays(remainingMs)}days")

            GroupChallenge(
                groupId = d["groupId"] as? String ?: id,
                code = d["code"] as? String ?: "",
                creatorUserId = d["creatorUserId"] as? String ?: "",
                creatorDisplayName = d["creatorDisplayName"] as? String ?: "",
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
                startDate = startDate,
                endDate = endDate,
                bonusEnabled = d["bonusEnabled"] as? Boolean ?: false,
                status = runCatching {
                    GroupChallengeStatus.valueOf(
                        (d["status"] as? String ?: "waiting").uppercase()
                    )
                }.getOrDefault(GroupChallengeStatus.WAITING),
                participants = participants,
                perWinnerBonus = ((d["prizePerWinner"] as? Long)?.toInt()
                    ?: (d["perWinnerBonus"] as? Long)?.toInt()) ?: 0,
                blockedDomains = (d["blockedDomains"] as? String)
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?: emptyList(),
                authorizationExpiresAt = (d["authorizationExpiresAt"] as? Long) ?: 0L,
            )
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: failed to parse document %s", id)
            null
        }
    }
}
