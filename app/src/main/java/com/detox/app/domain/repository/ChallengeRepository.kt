package com.detox.app.domain.repository

import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeStatus
import kotlinx.coroutines.flow.Flow

interface ChallengeRepository {
    suspend fun createChallenge(challenge: Challenge): Result<Unit>
    suspend fun getChallengeById(id: String): Result<Challenge?>
    fun getActiveChallenges(): Flow<List<Challenge>>
    suspend fun getActiveChallengesList(): Result<List<Challenge>>
    suspend fun getActiveChallengeForApp(packageName: String): Result<Challenge?>
    suspend fun updateChallengeStatus(id: String, status: ChallengeStatus): Result<Unit>
}
