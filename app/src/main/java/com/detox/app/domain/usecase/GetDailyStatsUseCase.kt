package com.detox.app.domain.usecase

import com.detox.app.domain.model.DailyStats
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import javax.inject.Inject

class GetDailyStatsUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val usageStatsRepository: UsageStatsRepository,
    private val calculatePointsUseCase: CalculatePointsUseCase
) {
    suspend operator fun invoke(): Result<List<DailyStats>> {
        return try {
            val challenges = challengeRepository.getActiveChallengesList().getOrThrow()
            val now = System.currentTimeMillis()

            val stats = challenges.map { challenge ->
                val todayUsage = usageStatsRepository.getTodayUsageForApp(challenge.appPackageName)

                val pointsResult = calculatePointsUseCase(
                    limitType = challenge.limitType,
                    limitValueMinutes = challenge.limitValueMinutes,
                    limitValueSessions = challenge.limitValueSessions,
                    todayMinutes = todayUsage.minutes,
                    todayOpens = todayUsage.opens
                )

                val daysRemaining = maxOf(0, ((challenge.endDate - now) / 86_400_000L).toInt())

                DailyStats(
                    challengeId = challenge.id,
                    appDisplayName = challenge.appDisplayName,
                    appPackageName = challenge.appPackageName,
                    limitType = challenge.limitType,
                    limitValueMinutes = challenge.limitValueMinutes,
                    limitValueSessions = challenge.limitValueSessions,
                    todayMinutes = todayUsage.minutes,
                    todayOpens = todayUsage.opens,
                    pointsEarnedToday = pointsResult.points,
                    limitExceeded = pointsResult.limitExceeded,
                    customMotivation = challenge.customMotivation,
                    daysRemaining = daysRemaining
                )
            }

            Result.success(stats)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
