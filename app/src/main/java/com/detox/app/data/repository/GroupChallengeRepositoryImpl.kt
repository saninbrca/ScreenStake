package com.detox.app.data.repository

import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.GroupChallengeDao
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.GroupChallengeEntity
import com.detox.app.data.remote.firebase.GroupChallengeFirestoreService
import com.detox.app.di.ApplicationScope
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.Participant
import com.detox.app.domain.model.ParticipantStatus
import com.detox.app.domain.model.Taunt
import com.detox.app.domain.repository.GroupChallengeRepository
import com.detox.app.service.TrackedAppEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupChallengeRepositoryImpl @Inject constructor(
    private val groupChallengeDao: GroupChallengeDao,
    private val challengeDao: ChallengeDao,
    private val firestoreService: GroupChallengeFirestoreService,
    @ApplicationScope private val appScope: CoroutineScope
) : GroupChallengeRepository {

    private var syncJob: Job? = null
    private val syncedStatuses = mutableMapOf<String, GroupChallengeStatus>()

    override fun startSyncingForUser(userId: String) {
        syncJob?.cancel()
        syncedStatuses.clear()
        syncJob = appScope.launch {
            firestoreService.observeUserGroupChallenges(userId)
                .catch { e -> Timber.e(e, "GroupChallengeRepo: global listener error uid=%s", userId) }
                .collect { challenges ->
                    challenges.forEach { gc ->
                        if (gc.status == syncedStatuses[gc.groupId]) return@forEach
                        syncedStatuses[gc.groupId] = gc.status
                        when (gc.status) {
                            GroupChallengeStatus.ACTIVE -> {
                                groupChallengeDao.upsert(gc.toEntity())
                                val myParticipant = gc.participants.find { it.userId == userId }
                                if (myParticipant?.status == ParticipantStatus.FAILED) {
                                    challengeDao.deleteById("group_${gc.groupId}")
                                    Timber.d(
                                        "GroupChallengeRepo: user %s FAILED group %s — deleted local challenge",
                                        userId, gc.groupId
                                    )
                                } else {
                                    syncGroupChallengeToLocalTracking(gc, userId)
                                    Timber.d(
                                        "Group challenge synced to Room: %s, app: %s",
                                        gc.groupId, gc.appPackageNames
                                    )
                                }
                            }
                            GroupChallengeStatus.COMPLETED, GroupChallengeStatus.CANCELLED -> {
                                groupChallengeDao.upsert(gc.toEntity())
                                val localId = "group_${gc.groupId}"
                                challengeDao.deleteById(localId)
                                Timber.d(
                                    "GroupChallengeRepo: upserted group entity + deleted local challenge %s (status=%s)",
                                    localId, gc.status
                                )
                            }
                            else -> Unit
                        }
                    }
                }
        }
        Timber.d("GroupChallengeRepo: global listener started for uid=%s", userId)
    }

    override fun stopSyncing() {
        syncJob?.cancel()
        syncJob = null
        syncedStatuses.clear()
        Timber.d("GroupChallengeRepo: global listener stopped")
    }

    override suspend fun getActiveGroupChallengeForApp(packageName: String): GroupChallenge? =
        groupChallengeDao.getActiveGroupChallengeForApp(packageName)?.toDomain()

    override fun getGroupChallenges(): Flow<List<GroupChallenge>> =
        groupChallengeDao.getAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getGroupChallengeById(groupId: String): GroupChallenge? =
        groupChallengeDao.getById(groupId)?.toDomain()

    override suspend fun saveGroupChallenge(groupChallenge: GroupChallenge): Result<Unit> {
        return try {
            groupChallengeDao.upsert(groupChallenge.toEntity())
            appScope.launch {
                firestoreService.saveGroupChallenge(groupChallenge)
            }
            Timber.d("GroupChallengeRepo: saved %s", groupChallenge.groupId)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeRepo: saveGroupChallenge failed")
            Result.failure(e)
        }
    }

    override fun observeGroupChallenge(groupId: String): Flow<GroupChallenge?> =
        firestoreService.observeGroupChallenge(groupId)
            .onEach { gc ->
                // Keep Room cache in sync with live Firestore data
                if (gc != null) {
                    try { groupChallengeDao.upsert(gc.toEntity()) }
                    catch (e: Exception) { Timber.e(e, "GroupChallengeRepo: cache upsert failed") }
                }
            }

    override suspend fun fetchGroupChallengeByCode(code: String): Result<GroupChallenge?> {
        return try {
            val gc = firestoreService.fetchGroupChallengeByCode(code)
            if (gc != null) groupChallengeDao.upsert(gc.toEntity())
            Result.success(gc)
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeRepo: fetchGroupChallengeByCode failed code=%s", code)
            Result.failure(e)
        }
    }

    override suspend fun fetchAndCacheById(groupId: String): Result<GroupChallenge?> {
        return try {
            val gc = firestoreService.fetchGroupChallengeById(groupId)
            if (gc != null) {
                groupChallengeDao.upsert(gc.toEntity())
                Timber.d("GroupChallengeRepo: fetchAndCacheById — cached %s from Firestore", groupId)
            } else {
                Timber.w("GroupChallengeRepo: fetchAndCacheById — document not found for %s", groupId)
            }
            Result.success(gc)
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeRepo: fetchAndCacheById failed groupId=%s", groupId)
            Result.failure(e)
        }
    }

    override suspend fun syncGroupChallengeToLocalTracking(
        groupChallenge: GroupChallenge,
        userId: String
    ): Result<Unit> {
        return try {
            val userParticipant = groupChallenge.participants.find { it.userId == userId }
            if (userParticipant == null) {
                Timber.w(
                    "GroupChallengeRepo: syncToTracking — user %s not in participants for %s",
                    userId, groupChallenge.groupId
                )
                return Result.success(Unit)
            }

            val localChallengeId = "group_${groupChallenge.groupId}"
            if (userParticipant.status == ParticipantStatus.FAILED) {
                challengeDao.deleteById(localChallengeId)
                Timber.d(
                    "GroupChallengeRepo: user %s already FAILED group %s — deleted local challenge",
                    userId, groupChallenge.groupId
                )
                return Result.success(Unit)
            }
            val entity = ChallengeEntity(
                id = localChallengeId,
                appPackageName = groupChallenge.appPackageNames.firstOrNull() ?: "",
                appDisplayName = groupChallenge.appDisplayName,
                mode = "hard",
                limitType = groupChallenge.limitType.name.lowercase(),
                limitValueMinutes = groupChallenge.limitValueMinutes,
                limitValueSessions = groupChallenge.limitValueSessions,
                startDate = if (groupChallenge.startDate > 0L) groupChallenge.startDate else System.currentTimeMillis(),
                endDate = groupChallenge.endDate,
                amountCents = groupChallenge.buyInCents,
                stripePaymentIntentId = userParticipant.paymentIntentId,
                customMotivation = null,
                status = "active",
                createdAt = System.currentTimeMillis(),
                appPackageNames = groupChallenge.appPackageNames.joinToString(","),
                sessionDurationMinutes = groupChallenge.sessionDurationMinutes,
                groupChallengeId = groupChallenge.groupId,
                blockedDomains = groupChallenge.blockedDomains.joinToString(",").ifEmpty { null }
            )
            challengeDao.insertChallenge(entity)
            Timber.d(
                "GroupChallengeRepo: synced group challenge %s → local challenge %s (paymentIntent=%s)",
                groupChallenge.groupId, localChallengeId, userParticipant.paymentIntentId
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeRepo: syncToTracking failed for %s", groupChallenge.groupId)
            Result.failure(e)
        }
    }

    override suspend fun refreshFromFirestore(userId: String): Result<Unit> {
        return try {
            val challenges = firestoreService.fetchUserGroupChallenges(userId)
            challenges.forEach { gc -> groupChallengeDao.upsert(gc.toEntity()) }

            challenges
                .filter { it.status == GroupChallengeStatus.ACTIVE }
                .forEach { gc ->
                    val myParticipant = gc.participants.find { it.userId == userId }
                    if (myParticipant?.status == ParticipantStatus.FAILED) {
                        challengeDao.deleteById("group_${gc.groupId}")
                        Timber.d(
                            "GroupChallengeRepo: refreshFromFirestore — user %s FAILED group %s, deleted local challenge",
                            userId, gc.groupId
                        )
                    } else {
                        syncGroupChallengeToLocalTracking(gc, userId)
                            .onFailure { e ->
                                Timber.e(e, "GroupChallengeRepo: refreshFromFirestore sync failed for %s", gc.groupId)
                            }
                    }
                }

            challenges
                .filter { it.status == GroupChallengeStatus.COMPLETED || it.status == GroupChallengeStatus.CANCELLED }
                .forEach { gc ->
                    val participant = gc.participants.find { it.userId == userId }
                    val succeeded = gc.status == GroupChallengeStatus.COMPLETED &&
                            participant?.status?.name?.uppercase() != "FAILED"
                    finishLocalGroupChallenge(gc.groupId, succeeded)
                        .onFailure { e ->
                            Timber.e(e, "GroupChallengeRepo: refreshFromFirestore finish failed for %s", gc.groupId)
                        }
                }

            Timber.d(
                "GroupChallengeRepo: refreshFromFirestore — synced %d challenge(s) for uid=%s",
                challenges.size, userId
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeRepo: refreshFromFirestore failed uid=%s", userId)
            Result.failure(e)
        }
    }

    override suspend fun finishLocalGroupChallenge(groupId: String, succeeded: Boolean): Result<Unit> {
        return try {
            val localChallengeId = "group_$groupId"
            val finalStatus = if (succeeded) "completed" else "failed"
            challengeDao.updateStatus(localChallengeId, finalStatus)
            Timber.d(
                "GroupChallengeRepo: finishLocalGroupChallenge %s → %s",
                groupId, finalStatus
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeRepo: finishLocalGroupChallenge failed for %s", groupId)
            Result.failure(e)
        }
    }

    override suspend fun updateParticipantStats(
        groupId: String,
        userId: String,
        opensToday: Int,
        timeUsedMinutes: Int
    ) {
        firestoreService.updateParticipantStats(groupId, userId, opensToday, timeUsedMinutes)
    }

    override suspend fun updateParticipantTimeUsed(groupId: String, userId: String, timeUsedMinutes: Int) {
        firestoreService.updateParticipantTimeUsed(groupId, userId, timeUsedMinutes)
    }

    override suspend fun incrementParticipantOpensToday(groupId: String, userId: String) {
        firestoreService.incrementParticipantOpensToday(groupId, userId)
    }

    override suspend fun markParticipantFailedLocally(groupId: String, userId: String) {
        val entity = groupChallengeDao.getById(groupId) ?: return
        val updatedJson = runCatching {
            val array = JSONArray(entity.participantsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("userId") == userId) {
                    obj.put("status", "failed")
                }
            }
            array.toString()
        }.getOrElse { entity.participantsJson }
        groupChallengeDao.updateParticipants(groupId, updatedJson)

        val packages = entity.appPackageNames
            .split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        TrackedAppEventBus.markPackagesFailedForUser(packages)

        Timber.d("User failed group $groupId — removing from active blocking")
    }

    // ── Taunts ─────────────────────────────────────────────────────────────────

    override suspend fun sendTaunt(
        groupId: String,
        fromUserId: String,
        fromDisplayName: String,
        toUserId: String,
        message: String,
    ): Result<Unit> = try {
        firestoreService.sendTaunt(groupId, fromUserId, fromDisplayName, toUserId, message)
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.e(e, "GroupChallengeRepo: sendTaunt failed group=%s", groupId)
        Result.failure(e)
    }

    override suspend fun countTauntsToday(groupId: String, fromUserId: String, toUserId: String): Int =
        firestoreService.countTauntsToday(groupId, fromUserId, toUserId)

    override fun observeUnshownTaunts(groupId: String, toUserId: String): Flow<List<Taunt>> =
        firestoreService.observeUnshownTaunts(groupId, toUserId)

    override suspend fun markTauntShown(groupId: String, tauntId: String): Result<Unit> = try {
        firestoreService.markTauntShown(groupId, tauntId)
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.e(e, "GroupChallengeRepo: markTauntShown failed tauntId=%s", tauntId)
        Result.failure(e)
    }

    // ── Entity ↔ Domain ─────────────────────────────────────────────────────────

    private fun GroupChallengeEntity.toDomain(): GroupChallenge {
        val packages = appPackageNames.split(",").map { it.trim() }.filter { it.isNotBlank() }

        val participants = runCatching {
            val array = JSONArray(participantsJson)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Participant(
                    userId = obj.optString("userId", ""),
                    displayName = obj.optString("displayName", ""),
                    paymentIntentId = obj.optString("paymentIntentId", ""),
                    amountCents = obj.optInt("amountCents", 0),
                    status = runCatching {
                        ParticipantStatus.valueOf(obj.optString("status", "active").uppercase())
                    }.getOrDefault(ParticipantStatus.ACTIVE),
                    opensToday = obj.optInt("opensToday", 0),
                    timeUsedMinutes = obj.optInt("timeUsedMinutes", 0),
                    joinedAt = obj.optLong("joinedAt", 0L)
                )
            }
        }.getOrDefault(emptyList())

        return GroupChallenge(
            groupId = groupId,
            code = code,
            creatorUserId = creatorUserId,
            appPackageNames = packages,
            appDisplayName = appDisplayName,
            limitType = runCatching { LimitType.valueOf(limitType.uppercase()) }
                .getOrDefault(LimitType.TIME),
            limitValueMinutes = limitValueMinutes,
            limitValueSessions = limitValueSessions,
            sessionDurationMinutes = sessionDurationMinutes,
            durationDays = durationDays,
            buyInCents = buyInCents,
            maxParticipants = maxParticipants,
            startDate = startDate,
            endDate = endDate,
            bonusEnabled = bonusEnabled != 0,
            status = runCatching { GroupChallengeStatus.valueOf(status.uppercase()) }
                .getOrDefault(GroupChallengeStatus.WAITING),
            participants = participants,
            blockedDomains = blockedDomains
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()
        )
    }

    private fun GroupChallenge.toEntity(): GroupChallengeEntity {
        val array = JSONArray()
        participants.forEach { p ->
            val obj = JSONObject()
            obj.put("userId", p.userId)
            obj.put("displayName", p.displayName)
            obj.put("paymentIntentId", p.paymentIntentId)
            obj.put("amountCents", p.amountCents)
            obj.put("status", p.status.name.lowercase())
            obj.put("opensToday", p.opensToday)
            obj.put("timeUsedMinutes", p.timeUsedMinutes)
            obj.put("joinedAt", p.joinedAt)
            array.put(obj)
        }
        return GroupChallengeEntity(
            groupId = groupId,
            code = code,
            creatorUserId = creatorUserId,
            appPackageNames = appPackageNames.joinToString(","),
            appDisplayName = appDisplayName,
            limitType = limitType.name.lowercase(),
            limitValueMinutes = limitValueMinutes,
            limitValueSessions = limitValueSessions,
            sessionDurationMinutes = sessionDurationMinutes,
            durationDays = durationDays,
            buyInCents = buyInCents,
            maxParticipants = maxParticipants,
            startDate = startDate,
            endDate = endDate,
            bonusEnabled = if (bonusEnabled) 1 else 0,
            status = status.name.lowercase(),
            participantsJson = array.toString(),
            blockedDomains = blockedDomains.joinToString(",").ifEmpty { null }
        )
    }
}
