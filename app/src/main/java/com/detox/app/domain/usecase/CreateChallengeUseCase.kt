package com.detox.app.domain.usecase

import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import java.util.UUID
import javax.inject.Inject

data class ChallengeCreationResult(
    val challengeId: String
)

class CreateChallengeUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository
) {
    suspend operator fun invoke(
        appPackageName: String,
        appDisplayName: String,
        limitType: LimitType,
        limitValueMinutes: Int,
        limitValueSessions: Int?,
        durationDays: Int,
        customMotivation: String?,
        mode: ChallengeMode = ChallengeMode.SOFT,
        amountCents: Int? = null,
        stripePaymentIntentId: String? = null
    ): Result<ChallengeCreationResult> {
        if (limitValueMinutes <= 0) {
            return Result.failure(IllegalArgumentException("Limit minutes must be greater than 0"))
        }
        if (durationDays !in 1..365) {
            return Result.failure(IllegalArgumentException("Duration must be between 1 and 365 days"))
        }
        if (limitType == LimitType.SESSIONS && (limitValueSessions == null || limitValueSessions <= 0)) {
            return Result.failure(IllegalArgumentException("Session limit must be greater than 0"))
        }
        if (mode == ChallengeMode.HARD && (amountCents == null || amountCents <= 0)) {
            return Result.failure(IllegalArgumentException("Hard Mode requires a positive amount"))
        }

        val existingChallenge = challengeRepository.getActiveChallengeForApp(appPackageName)
        if (existingChallenge.isSuccess && existingChallenge.getOrNull() != null) {
            return Result.failure(IllegalStateException("An active challenge already exists for this app"))
        }

        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val endDate = now + durationDays * 86_400_000L

        val challenge = Challenge(
            id = id,
            appPackageName = appPackageName,
            appDisplayName = appDisplayName,
            mode = mode,
            limitType = limitType,
            limitValueMinutes = limitValueMinutes,
            limitValueSessions = limitValueSessions,
            startDate = now,
            endDate = endDate,
            amountCents = if (mode == ChallengeMode.HARD) amountCents else null,
            stripePaymentIntentId = if (mode == ChallengeMode.HARD) stripePaymentIntentId else null,
            customMotivation = customMotivation,
            status = ChallengeStatus.ACTIVE,
            createdAt = now
        )

        return challengeRepository.createChallenge(challenge).map {
            ChallengeCreationResult(challengeId = id)
        }
    }
}
