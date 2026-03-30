package com.detox.app.data.repository

import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChallengeRepositoryImpl @Inject constructor(
    private val challengeDao: ChallengeDao
) : ChallengeRepository {

    override suspend fun createChallenge(challenge: Challenge): Result<Unit> {
        return try {
            challengeDao.insertChallenge(challenge.toEntity())
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

    override suspend fun updateChallengeStatus(id: String, status: ChallengeStatus): Result<Unit> {
        return try {
            challengeDao.updateStatus(id, status.name.lowercase())
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
        emergencyCode = emergencyCode,
        customMotivation = customMotivation,
        status = ChallengeStatus.valueOf(status.uppercase()),
        createdAt = createdAt
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
        emergencyCode = emergencyCode,
        customMotivation = customMotivation,
        status = status.name.lowercase(),
        createdAt = createdAt
    )
}
