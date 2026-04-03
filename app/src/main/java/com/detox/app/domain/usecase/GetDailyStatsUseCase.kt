package com.detox.app.domain.usecase

import com.detox.app.domain.model.DailyStats
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.UsageStatsRepository
import java.util.Calendar
import javax.inject.Inject

class GetDailyStatsUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val usageStatsRepository: UsageStatsRepository,
    private val calculatePointsUseCase: CalculatePointsUseCase,
    private val dailyLogRepository: DailyLogRepository
) {
    suspend operator fun invoke(): Result<List<DailyStats>> {
        return try {
            val challenges = challengeRepository.getActiveChallengesList().getOrThrow()
            val now = System.currentTimeMillis()

            // Start of today — used to look up today's DailyLog
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val stats = challenges.map { challenge ->
                val todayUsage = usageStatsRepository.getTodayUsageForApp(challenge.appPackageName)

                // Subtract overlay-visible time so it doesn't count against the user's limit.
                val overlayPausedMs = dailyLogRepository
                    .getOverlayPausedMs(challenge.id, today)
                    .getOrElse { 0L }
                val overlayPausedMinutes = (overlayPausedMs / 60_000L).toInt()
                val adjustedMinutes = maxOf(0, todayUsage.minutes - overlayPausedMinutes)

                // For session-limit challenges, use the Room-persisted conscious opens so the
                // progress bar reflects only deliberate "Yes, open it" taps.
                val todayOpens: Int = when (challenge.limitType) {
                    LimitType.SESSIONS -> dailyLogRepository
                        .getConsciousOpens(challenge.id, today)
                        .getOrElse { todayUsage.opens }
                    LimitType.TIME -> todayUsage.opens
                }

                val pointsResult = calculatePointsUseCase(
                    limitType = challenge.limitType,
                    limitValueMinutes = challenge.limitValueMinutes,
                    limitValueSessions = challenge.limitValueSessions,
                    todayMinutes = adjustedMinutes,
                    todayOpens = todayOpens
                )

                // Read today's DailyLog to surface any intra-day Hard Mode payment capture
                val todayLog = dailyLogRepository.getLogForDate(challenge.id, today).getOrNull()
                val moneyLostCents = todayLog?.moneyLostCents ?: 0

                val daysRemaining = maxOf(0, ((challenge.endDate - now) / 86_400_000L).toInt())

                DailyStats(
                    challengeId = challenge.id,
                    appDisplayName = challenge.appDisplayName,
                    appPackageName = challenge.appPackageName,
                    limitType = challenge.limitType,
                    limitValueMinutes = challenge.limitValueMinutes,
                    limitValueSessions = challenge.limitValueSessions,
                    todayMinutes = adjustedMinutes,
                    todayOpens = todayOpens,
                    pointsEarnedToday = pointsResult.points,
                    limitExceeded = pointsResult.limitExceeded,
                    customMotivation = challenge.customMotivation,
                    daysRemaining = daysRemaining,
                    moneyLostCents = moneyLostCents
                )
            }

            Result.success(stats)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
