package com.detox.app.domain.usecase

import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import javax.inject.Inject

data class DailyLimitStatus(
    val challenge: Challenge,
    val todayMinutes: Int,
    val todayOpens: Int,
    val limitExceeded: Boolean,
    val remainingMinutes: Int,
    val remainingOpens: Int?
)

class CheckDailyLimitUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val usageStatsRepository: UsageStatsRepository
) {
    suspend operator fun invoke(packageName: String): Result<DailyLimitStatus> {
        return try {
            val challenge = challengeRepository.getActiveChallengeForApp(packageName).getOrThrow()
                ?: return Result.failure(IllegalStateException("No active challenge for $packageName"))

            val todayUsage = usageStatsRepository.getTodayUsageForApp(packageName)

            val limitExceeded = when (challenge.limitType) {
                LimitType.TIME -> todayUsage.minutes >= challenge.limitValueMinutes
                LimitType.SESSIONS -> {
                    val maxSessions = challenge.limitValueSessions ?: 0
                    todayUsage.opens >= maxSessions
                }
            }

            val remainingMinutes = when (challenge.limitType) {
                LimitType.TIME -> maxOf(0, challenge.limitValueMinutes - todayUsage.minutes)
                LimitType.SESSIONS -> {
                    val maxSessionMinutes = challenge.limitValueMinutes * (challenge.limitValueSessions ?: 1)
                    maxOf(0, maxSessionMinutes - todayUsage.minutes)
                }
            }

            val remainingOpens = when (challenge.limitType) {
                LimitType.TIME -> null
                LimitType.SESSIONS -> maxOf(0, (challenge.limitValueSessions ?: 0) - todayUsage.opens)
            }

            Result.success(
                DailyLimitStatus(
                    challenge = challenge,
                    todayMinutes = todayUsage.minutes,
                    todayOpens = todayUsage.opens,
                    limitExceeded = limitExceeded,
                    remainingMinutes = remainingMinutes,
                    remainingOpens = remainingOpens
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
