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
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mutable per-participant leaderboard stats, stored in the CLIENT-WRITABLE
 * `groupChallenges/{groupId}/participants/{uid}` sub-collection (each user may write
 * only their own doc; rules whitelist exactly these fields — no money field can ever
 * live here). The parent doc's `participants` array keeps identity/money/status and
 * is Cloud-Function-only. Fields are nullable because the two write paths (opens vs
 * time) merge independently and a doc may carry only one of them at first.
 */
data class ParticipantStats(
    val opensToday: Int? = null,
    val timeUsedMinutes: Int? = null,
    /** [DateUtils.todayKey] of the day the stats refer to — enables a later reader-side daily reset. */
    val dateKey: Long? = null,
)

@Singleton
class GroupChallengeFirestoreService @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val collection get() = firestore.collection("groupChallenges")

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
                ?.let { it.withStats(fetchParticipantStats(groupId)) }
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: fetchGroupChallengeById failed groupId=%s", groupId)
            null
        }
    }

    /**
     * Real-time listener for a single group challenge, with per-participant stats from the
     * `participants` sub-collection merged into [GroupChallenge.participants] — downstream
     * readers keep consuming `Participant.opensToday`/`timeUsedMinutes` unchanged.
     */
    fun observeGroupChallenge(groupId: String): Flow<GroupChallenge?> =
        combine(observeGroupChallengeDoc(groupId), observeParticipantStats(groupId)) { gc, stats ->
            gc?.withStats(stats)
        }

    /** Raw parent-doc listener (array stats only — pre-merge). */
    private fun observeGroupChallengeDoc(groupId: String): Flow<GroupChallenge?> = callbackFlow {
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

    // ── Per-participant stats sub-collection ────────────────────────────────────

    private fun statsRef(groupId: String) = collection.document(groupId).collection("participants")

    /**
     * Live map of userId → [ParticipantStats] for a group. Errors emit an empty map
     * (readers fall back to the parent array's frozen values) instead of closing the
     * flow, so the parent-doc listener stays alive.
     */
    fun observeParticipantStats(groupId: String): Flow<Map<String, ParticipantStats>> = callbackFlow {
        val registration = statsRef(groupId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.e(error, "GroupChallengeFirestore: observeParticipantStats error %s", groupId)
                    trySend(emptyMap())
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.associate { it.id to it.toParticipantStats() } ?: emptyMap())
            }
        awaitClose { registration.remove() }
    }

    /** One-shot stats fetch — same fallback contract as [observeParticipantStats]. */
    suspend fun fetchParticipantStats(groupId: String): Map<String, ParticipantStats> = try {
        statsRef(groupId).get().await()
            .documents.associate { it.id to it.toParticipantStats() }
    } catch (e: Exception) {
        Timber.e(e, "GroupChallengeFirestore: fetchParticipantStats failed %s", groupId)
        emptyMap()
    }

    /**
     * ABSOLUTE write of the caller's own conscious-opens count for today. Merge-writes
     * only the user's own stat doc — never the parent doc, never another participant.
     * The value is today's count (from the overlay's per-day counter / Room DailyLog),
     * stamped with [DateUtils.todayKey] so a reader-side daily reset can slot in later.
     */
    suspend fun setParticipantOpensToday(groupId: String, userId: String, opensToday: Int) {
        try {
            statsRef(groupId).document(userId)
                .set(
                    mapOf(
                        "opensToday" to opensToday,
                        "dateKey" to DateUtils.todayKey(),
                        "updatedAt" to System.currentTimeMillis(),
                    ),
                    SetOptions.merge()
                )
                .await()
            Timber.d("Group opensToday set: %s user=%s opens=%d", groupId, userId, opensToday)
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: setParticipantOpensToday failed groupId=%s uid=%s", groupId, userId)
        }
    }

    /** ABSOLUTE write of the caller's own time-used minutes — same contract as [setParticipantOpensToday]. */
    suspend fun setParticipantTimeUsed(groupId: String, userId: String, timeUsedMinutes: Int) {
        try {
            statsRef(groupId).document(userId)
                .set(
                    mapOf(
                        "timeUsedMinutes" to timeUsedMinutes,
                        "dateKey" to DateUtils.todayKey(),
                        "updatedAt" to System.currentTimeMillis(),
                    ),
                    SetOptions.merge()
                )
                .await()
            Timber.d("Leaderboard time set: groupId=%s userId=%s time=%d", groupId, userId, timeUsedMinutes)
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: setParticipantTimeUsed failed groupId=%s uid=%s", groupId, userId)
        }
    }

    private fun DocumentSnapshot.toParticipantStats(): ParticipantStats = ParticipantStats(
        opensToday = getLong("opensToday")?.toInt(),
        timeUsedMinutes = getLong("timeUsedMinutes")?.toInt(),
        dateKey = getLong("dateKey"),
    )

    /**
     * Overlays sub-collection stats onto the parent array's participants. A stat-doc value
     * overrides the array value when present; the array value (frozen at its last legacy
     * write, or the CF's initial 0) is the fallback — this is the whole transition story
     * for in-flight and completed challenges.
     */
    private fun GroupChallenge.withStats(stats: Map<String, ParticipantStats>): GroupChallenge {
        if (stats.isEmpty()) return this
        return copy(
            participants = participants.map { p ->
                val s = stats[p.userId] ?: return@map p
                p.copy(
                    opensToday = s.opensToday ?: p.opensToday,
                    timeUsedMinutes = s.timeUsedMinutes ?: p.timeUsedMinutes,
                )
            }
        )
    }

    /**
     * Real-time listener for all group challenges where [userId] is the creator
     * or a participant. Combines two Firestore snapshot listeners and deduplicates,
     * then overlays live per-participant stats from the sub-collection.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
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
        val raw = combine(asCreator, asParticipant) { a, b ->
            (a + b).distinctBy { it.groupId }
        }
        // Overlay live sub-collection stats for ACTIVE/COMPLETED groups so the Room mirror
        // (computeGroupRank, dashboard card, FriendsHub sort) stays fresh — stat writes no
        // longer touch the parent doc, so the parent listeners alone would go stale.
        // flatMapLatest restarts the stat listeners on parent emissions, which are rare
        // after this move (status changes, joins). WAITING groups have no stats yet.
        return raw.flatMapLatest { challenges ->
            val statTargets = challenges.filter {
                it.status == GroupChallengeStatus.ACTIVE || it.status == GroupChallengeStatus.COMPLETED
            }
            if (statTargets.isEmpty()) {
                flowOf(challenges)
            } else {
                combine(
                    statTargets.map { gc ->
                        observeParticipantStats(gc.groupId).map { stats -> gc.groupId to stats }
                    }
                ) { pairs ->
                    val statsByGroup = pairs.toMap()
                    challenges.map { gc -> statsByGroup[gc.groupId]?.let { gc.withStats(it) } ?: gc }
                }
            }
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
                .map { gc ->
                    // One-shot stats overlay for the statuses whose stats are rendered.
                    if (gc.status == GroupChallengeStatus.ACTIVE || gc.status == GroupChallengeStatus.COMPLETED) {
                        gc.withStats(fetchParticipantStats(gc.groupId))
                    } else gc
                }
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: fetchUserGroupChallenges failed uid=%s", userId)
            emptyList()
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
                    payoutOwedCents = (p["payoutOwedCents"] as? Long)?.toInt() ?: 0,
                )
            }

            val createdAt = when (val raw = this.get("createdAt")) {
                is com.google.firebase.Timestamp -> raw.toDate().time
                is Long -> raw
                else -> System.currentTimeMillis()
            }
            val startDate = (d.get("startDate") as? Number)?.toLong() ?: createdAt
            val durationDays = (d["durationDays"] as? Long)?.toInt() ?: 7
            // Invariant #18: end-of-day, never startDate + N×86400000. Only reachable for a
            // WAITING doc (startGroupChallenge always stamps endDate on activation), but the
            // raw-millis form must not survive anywhere.
            val endDate = (d.get("endDate") as? Number)?.toLong()
                ?: DateUtils.endOfDayMillis(startDate, durationDays)

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
                durationDays = durationDays,
                buyInCents = (d["buyInCents"] as? Long)?.toInt() ?: 500,
                // Deliberate fail-safe fallback, NOT the creation default (that is
                // GroupParticipantLimits.DEFAULT = 20, chosen by the creator on step 4). It only
                // applies to a doc written before the field existed; 5 matches the identical
                // `?? 5` in joinGroupChallenge/confirmGroupJoin so client and server agree, and it
                // errs SMALL — an undersized group, never an oversold one. Do not raise it.
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
                blockAdultContent = d["blockAdultContent"] as? Boolean ?: false,
                authorizationExpiresAt = (d["authorizationExpiresAt"] as? Long) ?: 0L,
            )
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeFirestore: failed to parse document %s", id)
            null
        }
    }
}
