package com.detox.app.data.repository

import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.detox.app.di.ApplicationScope
import com.detox.app.domain.model.BlockingType
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.PartialBlockSection
import com.detox.app.domain.repository.ChallengeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChallengeRepositoryImpl @Inject constructor(
    private val challengeDao: ChallengeDao,
    private val firestoreService: FirestoreService,
    private val firebaseAuthService: FirebaseAuthService,
    @ApplicationScope private val appScope: CoroutineScope
) : ChallengeRepository {

    override suspend fun createChallenge(challenge: Challenge): Result<Unit> {
        return try {
            challengeDao.insertChallenge(challenge.toEntity())
            // Fire-and-forget Firestore sync
            appScope.launch {
                firebaseAuthService.logAuthState("ChallengeRepo.createChallenge")
                val uid = firebaseAuthService.currentUserId()
                if (uid == null) {
                    Timber.w("createChallenge: skipping Firestore sync — user not signed in")
                } else {
                    Timber.d("createChallenge: syncing challenge %s for uid=%s", challenge.id, uid)
                    firestoreService.saveChallenge(uid, challenge)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getChallengeById(id: String): Result<Challenge?> {
        return try {
            Result.success(challengeDao.getChallengeById(id)?.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getActiveChallenges(): Flow<List<Challenge>> {
        return challengeDao.getActiveChallenges().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getActiveChallengesList(): Result<List<Challenge>> {
        return try {
            Result.success(challengeDao.getActiveChallengesList().map { it.toDomain() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getActiveChallengeForApp(packageName: String): Result<Challenge?> {
        return try {
            Result.success(challengeDao.getActiveChallengeForApp(packageName)?.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getAllChallenges(): Flow<List<Challenge>> {
        return challengeDao.getAllChallenges().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun markCompletionShown(id: String): Result<Unit> {
        return try {
            challengeDao.markCompletionShown(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updatePendingLimit(
        challengeId: String, pendingValue: Int, appliesAt: Long
    ): Result<Unit> = runCatching {
        val uid = firebaseAuthService.currentUserId() ?: error("Nicht authentifiziert")
        // Firestore FIRST (source of truth — survives reinstall)
        firestoreService.updateChallengePendingLimit(uid, challengeId, pendingValue, appliesAt)
        // Then Room
        challengeDao.updatePendingLimit(challengeId, pendingValue, appliesAt)
        Timber.d("updatePendingLimit: challengeId=$challengeId pendingValue=$pendingValue")
    }

    override suspend fun getUnshownCompletedHardChallenge(): Result<Challenge?> {
        return try {
            Result.success(challengeDao.getUnshownCompletedHardChallenge()?.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateChallengeStatus(id: String, status: ChallengeStatus): Result<Unit> {
        return try {
            val statusStr = status.name.lowercase()
            challengeDao.updateStatus(id, statusStr)
            // Fire-and-forget Firestore sync
            appScope.launch {
                firebaseAuthService.logAuthState("ChallengeRepo.updateChallengeStatus")
                val uid = firebaseAuthService.currentUserId()
                if (uid == null) {
                    Timber.w("updateChallengeStatus: skipping Firestore sync — user not signed in")
                } else {
                    Timber.d("updateChallengeStatus: challenge=%s status=%s uid=%s", id, statusStr, uid)
                    firestoreService.updateChallengeStatus(uid, id, statusStr)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun ChallengeEntity.toDomain(): Challenge {
        val type = runCatching { BlockingType.valueOf(blockingType.uppercase()) }
            .getOrDefault(BlockingType.APP)
        val packageNames = appPackageNames
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: if (appPackageName.isNotBlank()) listOf(appPackageName) else emptyList()
        val domains = blockedDomains
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val partialDomains = partialBlockDomains
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return Challenge(
            id = id,
            appPackageName = packageNames.firstOrNull(),
            appPackageNames = packageNames,
            appDisplayName = appDisplayName,
            mode = ChallengeMode.valueOf(mode.uppercase()),
            limitType = LimitType.valueOf(limitType.uppercase()),
            limitValueMinutes = limitValueMinutes,
            limitValueSessions = limitValueSessions,
            startDate = startDate,
            endDate = endDate,
            amountCents = amountCents,
            stripePaymentIntentId = stripePaymentIntentId,
            customMotivation = customMotivation,
            status = ChallengeStatus.valueOf(status.uppercase()),
            createdAt = createdAt,
            dailyBudgetMinutes = dailyBudgetMinutes,
            blockedDomains = domains,
            partialBlockDomains = partialDomains,
            blockingType = type,
            blockAdultContent = blockAdultContent != 0,
            scheduleStartTime = scheduleStartTime,
            scheduleEndTime = scheduleEndTime,
            activeDays = activeDays
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList(),
            completionShown = completionShown != 0,
            sessionDurationMinutes = sessionDurationMinutes,
            groupChallengeId = groupChallengeId,
            partialBlockSections = partialBlockSections
                .split(",")
                .filter { it.isNotBlank() }
                .mapNotNull { PartialBlockSection.fromId(it.trim()) },
            isPartialBlockOnly = isPartialBlockOnly != 0,
            redemptionEligible = redemptionEligible != 0,
            redemptionDeadline = redemptionDeadline,
            redemptionShowAfter = redemptionShowAfter,
            redemptionChallengeId = redemptionChallengeId,
            redemptionRefundAmount = redemptionRefundAmount,
            redemptionDays = redemptionDays,
            redemptionLimit = redemptionLimit,
            isRedemption = isRedemption != 0,
            originalChallengeId = originalChallengeId,
            originalPaymentIntentId = originalPaymentIntentId,
            refundAmountCents = refundAmountCents,
            pendingLimitValue = pendingLimitValue,
            pendingLimitAppliesAt = pendingLimitAppliesAt,
        )
    }

    private fun Challenge.toEntity(): ChallengeEntity = ChallengeEntity(
        id = id,
        appPackageName = appPackageNames.firstOrNull() ?: "",
        appDisplayName = appDisplayName,
        mode = mode.name.lowercase(),
        limitType = limitType.name.lowercase(),
        limitValueMinutes = limitValueMinutes,
        limitValueSessions = limitValueSessions,
        startDate = startDate,
        endDate = endDate,
        amountCents = amountCents,
        stripePaymentIntentId = stripePaymentIntentId,
        customMotivation = customMotivation,
        status = status.name.lowercase(),
        createdAt = createdAt,
        dailyBudgetMinutes = dailyBudgetMinutes,
        appPackageNames = appPackageNames.joinToString(",").ifEmpty { null },
        blockedDomains = blockedDomains.joinToString(",").ifEmpty { null },
        partialBlockDomains = partialBlockDomains.joinToString(",").ifEmpty { null },
        blockingType = blockingType.name.lowercase(),
        blockAdultContent = if (blockAdultContent) 1 else 0,
        scheduleStartTime = scheduleStartTime,
        scheduleEndTime = scheduleEndTime,
        activeDays = activeDays.joinToString(",").ifEmpty { null },
        completionShown = if (completionShown) 1 else 0,
        sessionDurationMinutes = sessionDurationMinutes,
        groupChallengeId = groupChallengeId,
        partialBlockSections = partialBlockSections.joinToString(",") { it.id },
        isPartialBlockOnly = if (isPartialBlockOnly) 1 else 0,
        redemptionEligible = if (redemptionEligible) 1 else 0,
        redemptionDeadline = redemptionDeadline,
        redemptionShowAfter = redemptionShowAfter,
        redemptionChallengeId = redemptionChallengeId,
        redemptionRefundAmount = redemptionRefundAmount,
        redemptionDays = redemptionDays,
        redemptionLimit = redemptionLimit,
        isRedemption = if (isRedemption) 1 else 0,
        originalChallengeId = originalChallengeId,
        originalPaymentIntentId = originalPaymentIntentId,
        refundAmountCents = refundAmountCents,
        pendingLimitValue = pendingLimitValue,
        pendingLimitAppliesAt = pendingLimitAppliesAt,
    )
}
