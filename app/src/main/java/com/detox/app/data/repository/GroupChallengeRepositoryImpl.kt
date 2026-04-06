package com.detox.app.data.repository

import com.detox.app.data.local.db.dao.GroupChallengeDao
import com.detox.app.data.local.db.entity.GroupChallengeEntity
import com.detox.app.data.remote.firebase.GroupChallengeFirestoreService
import com.detox.app.di.ApplicationScope
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.Participant
import com.detox.app.domain.model.ParticipantStatus
import com.detox.app.domain.repository.GroupChallengeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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
    private val firestoreService: GroupChallengeFirestoreService,
    @ApplicationScope private val appScope: CoroutineScope
) : GroupChallengeRepository {

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
                    timeUsedMinutes = obj.optInt("timeUsedMinutes", 0)
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
            durationDays = durationDays,
            buyInCents = buyInCents,
            maxParticipants = maxParticipants,
            startDate = startDate,
            endDate = endDate,
            bonusEnabled = bonusEnabled != 0,
            status = runCatching { GroupChallengeStatus.valueOf(status.uppercase()) }
                .getOrDefault(GroupChallengeStatus.WAITING),
            participants = participants
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
            durationDays = durationDays,
            buyInCents = buyInCents,
            maxParticipants = maxParticipants,
            startDate = startDate,
            endDate = endDate,
            bonusEnabled = if (bonusEnabled) 1 else 0,
            status = status.name.lowercase(),
            participantsJson = array.toString()
        )
    }
}
