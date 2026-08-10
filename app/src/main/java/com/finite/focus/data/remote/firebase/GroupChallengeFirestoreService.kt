package com.finite.focus.data.remote.firebase

import com.finite.focus.domain.model.GroupChallenge
import com.finite.focus.domain.model.GroupChallengeStatus
import com.finite.focus.util.DateUtils
import com.finite.focus.domain.model.LimitType
import com.finite.focus.domain.model.Participant
import com.finite.focus.domain.model.ParticipantStatus
import com.finite.focus.domain.model.Taunt
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    // ── Daily slot: the value FOR the day named by the matching date key ──────────
    val opensToday: Int? = null,
    val timeUsedMinutes: Int? = null,
    /** True when the day named by [exceededDateKey] blew the challenge's limit. */
    val exceededToday: Boolean = false,

    /**
     * Legacy SHARED day stamp, written before the per-path split. Still written by every
     * path so an older build reading this doc keeps its daily reset, and used as the
     * fallback for a doc that predates the split keys below.
     */
    val dateKey: Long? = null,

    // ── Per-path day stamps ──────────────────────────────────────────────────────
    // Three INDEPENDENT writers touch this doc: the opens path (OverlayManager, on a
    // conscious "Ja, öffnen"), the time path (UsageTrackingService, on its polling
    // loops) and the exceeded path (DailyEvaluationWorker, once on a violation day).
    // They must NOT share one stamp: whichever wrote first would roll the shared key to
    // today, and the others would then see "already today" and skip folding THEIR
    // still-pending previous-day value into the cumulative total — silently dropping a
    // day. Each path owns its own {daily value, cumulative total, date key} triple and
    // rolls only that triple.
    val opensDateKey: Long? = null,
    val timeDateKey: Long? = null,
    val exceededDateKey: Long? = null,

    // ── Cumulative slot: the sum over all days STRICTLY BEFORE the matching key ───
    // This exclusion is the load-bearing invariant. It means the last day of a
    // challenge needs no end-of-challenge flush: its value is still sitting in the
    // daily slot, and `total + daily` recovers it forever. That is what keeps
    // completeGroupChallenge (and therefore all settlement/payout code) out of this
    // feature entirely.
    val totalOpens: Int = 0,
    val totalMinutes: Int = 0,
    val exceededDays: Int = 0,
) {
    private fun isToday(key: Long?): Boolean =
        key != null && key == DateUtils.todayKey()

    /**
     * Whether each daily slot refers to the CURRENT day. Stat writes are lazy — no write
     * happens on a day the user never opens the tracked app — so a doc routinely survives
     * a midnight rollover still carrying yesterday's numbers. Anything asking "what is
     * today's value" MUST gate on these; see [GroupChallengeFirestoreService.withStats].
     *
     * A missing key is NOT today: an unattributable value belongs to no particular day.
     */
    val opensIsForToday: Boolean get() = isToday(opensDateKey ?: dateKey)
    val timeIsForToday: Boolean get() = isToday(timeDateKey ?: dateKey)

    /**
     * Whole-challenge totals. Deliberately NOT gated on the date key: under the
     * cumulative-excludes-the-current-day invariant, `total + daily` is the true all-time
     * sum whether or not the daily slot happens to be today's. Gating these would silently
     * drop the final day of every finished challenge from the results ranking.
     */
    val totalOpensAllDays: Int get() = totalOpens + (opensToday ?: 0)
    val totalMinutesAllDays: Int get() = totalMinutes + (timeUsedMinutes ?: 0)
    val exceededDaysAllDays: Int get() = exceededDays + (if (exceededToday) 1 else 0)
}

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
     * Serialises stat writes per group WITHIN this process. The rollover below is a
     * read-modify-write, so two writes racing on the same triple could both observe the
     * stale date key and both fold the same daily value into the cumulative total. The
     * three writer paths live in one app process, so a local mutex closes that window
     * without putting a Firestore transaction on the overlay's "Ja, öffnen" tap path.
     *
     * This is insurance against DOUBLE-COUNTING only. It is NOT what makes the three
     * paths independent — that is the per-path date keys on [ParticipantStats], which fix
     * a sequential bug a mutex cannot touch.
     */
    private val statsWriteMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Read-modify-merge of the caller's OWN stat doc. [build] receives the stored doc plus
     * today's key and returns the fields to merge; it is called only when the current doc
     * was read successfully.
     *
     * On a read failure the write is SKIPPED rather than attempted blind. A blind write
     * would have to guess the cumulative total, and guessing 0 would overwrite a real
     * total with zero — losing the whole challenge's history for one transient error.
     * Dropping a single stat update instead is cheap: the opens path rewrites on the next
     * conscious open and the time path within 60 s. A non-existent doc is NOT a failure —
     * it reads back as an all-defaults [ParticipantStats], which is exactly right for a
     * first write.
     */
    private suspend fun writeOwnStats(
        groupId: String,
        userId: String,
        label: String,
        build: (stored: ParticipantStats, today: Long) -> Map<String, Any>,
    ) {
        val mutex = statsWriteMutexes.getOrPut(groupId) { Mutex() }
        try {
            mutex.withLock {
                val ref = statsRef(groupId).document(userId)
                val stored = ref.get().await().toParticipantStats()
                val fields = build(stored, DateUtils.todayKey()) +
                    ("updatedAt" to System.currentTimeMillis())
                ref.set(fields, SetOptions.merge()).await()
                Timber.d(
                    "GroupChallengeFirestore: %s stats written groupId=%s uid=%s fields=%s",
                    label, groupId, userId, fields.keys
                )
            }
        } catch (e: Exception) {
            Timber.e(
                e, "GroupChallengeFirestore: %s stat write failed (skipped, no cumulative touched) groupId=%s uid=%s",
                label, groupId, userId
            )
        }
    }

    /**
     * ABSOLUTE write of the caller's own conscious-opens count for today, rolling the
     * previous day's count into [ParticipantStats.totalOpens] first if the stored opens
     * stamp is not today's. Merge-writes only the user's own stat doc — never the parent
     * doc, never another participant.
     */
    suspend fun setParticipantOpensToday(groupId: String, userId: String, opensToday: Int) =
        writeOwnStats(groupId, userId, "opens") { stored, today ->
            buildMap {
                put("opensToday", opensToday)
                put("opensDateKey", today)
                put("dateKey", today)
                // Fold the stored day's count into the total as we leave that day. Runs
                // on the FIRST write of a new day only; the daily slot then belongs to
                // today and stays excluded from the total (see ParticipantStats).
                if (!stored.opensIsForToday) {
                    put("totalOpens", stored.totalOpens + (stored.opensToday ?: 0))
                }
            }
        }

    /** ABSOLUTE write of the caller's own time-used minutes — same contract as [setParticipantOpensToday]. */
    suspend fun setParticipantTimeUsed(groupId: String, userId: String, timeUsedMinutes: Int) =
        writeOwnStats(groupId, userId, "time") { stored, today ->
            buildMap {
                put("timeUsedMinutes", timeUsedMinutes)
                put("timeDateKey", today)
                put("dateKey", today)
                if (!stored.timeIsForToday) {
                    put("totalMinutes", stored.totalMinutes + (stored.timeUsedMinutes ?: 0))
                }
            }
        }

    /**
     * Records that TODAY blew the challenge's limit, rolling a previous flagged day into
     * [ParticipantStats.exceededDays] first. Idempotent: called once per violation day by
     * `DailyEvaluationWorker`, and a repeat call on the same day re-writes the same flag
     * without folding again (the stamp already matches today).
     *
     * A clean day writes NOTHING — which is correct, because the stored flag describes the
     * day named by `exceededDateKey`, however long ago that was. The fold on the next
     * violation day still credits exactly one day.
     */
    suspend fun setParticipantExceededToday(groupId: String, userId: String) =
        writeOwnStats(groupId, userId, "exceeded") { stored, today ->
            buildMap {
                put("exceededToday", true)
                put("exceededDateKey", today)
                if (stored.exceededDateKey != today) {
                    put("exceededDays", stored.exceededDays + (if (stored.exceededToday) 1 else 0))
                }
            }
        }

    private fun DocumentSnapshot.toParticipantStats(): ParticipantStats = ParticipantStats(
        opensToday = getLong("opensToday")?.toInt(),
        timeUsedMinutes = getLong("timeUsedMinutes")?.toInt(),
        exceededToday = getBoolean("exceededToday") ?: false,
        dateKey = getLong("dateKey"),
        opensDateKey = getLong("opensDateKey"),
        timeDateKey = getLong("timeDateKey"),
        exceededDateKey = getLong("exceededDateKey"),
        totalOpens = getLong("totalOpens")?.toInt() ?: 0,
        totalMinutes = getLong("totalMinutes")?.toInt() ?: 0,
        exceededDays = getLong("exceededDays")?.toInt() ?: 0,
    )

    /**
     * Overlays sub-collection stats onto the parent array's participants. A stat-doc value
     * overrides the array value when present; the array value (frozen at its last legacy
     * write, or the CF's initial 0) is the fallback — this is the whole transition story
     * for in-flight and completed challenges.
     *
     * ── DAILY RESET (the ONE place it happens) ────────────────────────────────────────
     * `opensToday`/`timeUsedMinutes` are DAILY values. The write side stamps every write
     * with [DateUtils.todayKey], but writes are lazy: a user who stays clean all day
     * produces NO write at all (the TIME path skips `totalMs == 0`, the SESSIONS path
     * only writes on a conscious open), so a stat doc routinely carries yesterday's
     * numbers into today. A doc that is not [ParticipantStats.isForToday] therefore
     * reads as 0 — never as its stale value.
     *
     * This is deliberately the SINGLE gate for the whole app. Every consumer of
     * `Participant.opensToday`/`timeUsedMinutes` — the detail leaderboard, the results
     * podium, `OverlayManager.computeGroupRank`, `GetDailyStatsUseCase`, `FriendsHubScreen`,
     * and (via the Room mirror → `TrackedAppEventBus`) the accessibility service's
     * session-limit gate — reads through here. Do NOT re-implement the reset at a call
     * site: the Room mirror re-serialises these values WITHOUT `dateKey`
     * (`GroupChallengeRepositoryImpl.toEntity`), so a downstream reader cannot detect
     * staleness even in principle. It has to be zeroed here, before it is cached.
     *
     * The stale-gate is why this is not merely cosmetic: `UsageTrackingService` feeds the
     * Room value into `TrackedAppEventBus.groupSessionInfos`, which
     * `AppDetectionAccessibilityService` consults to decide whether the session limit is
     * already reached. Without the reset, a SESSIONS participant who hit their limit
     * yesterday stayed blocked from 00:01 on a clean day — and re-poisoned
     * `OverlayManager.consciousOpensToday` right after the midnight reset had cleared it.
     */
    private fun GroupChallenge.withStats(stats: Map<String, ParticipantStats>): GroupChallenge {
        // Empty map = the stats listener errored (its documented fallback). Keep the
        // parent array's values rather than blanking a live leaderboard on a transient
        // read failure.
        if (stats.isEmpty()) return this
        return copy(
            participants = participants.map { p ->
                val s = stats[p.userId] ?: return@map p
                p.copy(
                    // Daily values — each gated on ITS OWN day stamp, so a fresh time
                    // write cannot make a stale opens value look current (or vice versa).
                    opensToday = if (s.opensIsForToday) s.opensToday ?: p.opensToday else 0,
                    timeUsedMinutes =
                        if (s.timeIsForToday) s.timeUsedMinutes ?: p.timeUsedMinutes else 0,
                    // Whole-challenge totals — ungated by design (see ParticipantStats).
                    totalOpens = s.totalOpensAllDays,
                    totalMinutes = s.totalMinutesAllDays,
                    exceededDays = s.exceededDaysAllDays,
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
