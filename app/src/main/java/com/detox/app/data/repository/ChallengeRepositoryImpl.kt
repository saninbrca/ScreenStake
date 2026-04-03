package com.detox.app.data.repository

import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.detox.app.di.ApplicationScope
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
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

    private fun ChallengeEntity.toDomain(): Challenge = Challenge(
        id = id,
        appPackageName = appPackageName,
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
        dailyBudgetMinutes = dailyBudgetMinutes
    )

    private fun Challenge.toEntity(): ChallengeEntity = ChallengeEntity(
        id = id,
        appPackageName = appPackageName,
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
        dailyBudgetMinutes = dailyBudgetMinutes
    )
}
