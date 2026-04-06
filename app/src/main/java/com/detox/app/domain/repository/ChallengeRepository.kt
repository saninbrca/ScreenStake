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
    /** Returns all challenges (active + completed + failed) ordered by createdAt DESC. */
    fun getAllChallenges(): Flow<List<Challenge>>
    /** Marks the congratulations overlay as shown so it won't appear again. */
    suspend fun markCompletionShown(id: String): Result<Unit>
    /** Returns the first completed Hard Mode challenge whose overlay has not yet been shown, or null. */
    suspend fun getUnshownCompletedHardChallenge(): Result<Challenge?>
}
