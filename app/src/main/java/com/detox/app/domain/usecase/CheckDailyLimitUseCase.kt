package com.detox.app.domain.usecase

import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.UsageStatsRepository
import java.util.Calendar
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
    private val usageStatsRepository: UsageStatsRepository,
    private val dailyLogRepository: DailyLogRepository
) {
    suspend operator fun invoke(packageName: String): Result<DailyLimitStatus> {
        return try {
            val challenge = challengeRepository.getActiveChallengeForApp(packageName).getOrThrow()
                ?: return Result.failure(IllegalStateException("No active challenge for $packageName"))

            val todayUsage = usageStatsRepository.getTodayUsageForApp(packageName)
            val today = todayMidnightMs()

            // Subtract time when our own overlay was covering this app — that time should
            // not count against the user's limit since the app was in the background.
            val overlayPausedMs = dailyLogRepository
                .getOverlayPausedMs(challenge.id, today)
                .getOrElse { 0L }
            val overlayPausedMinutes = (overlayPausedMs / 60_000L).toInt()
            val adjustedMinutes = maxOf(0, todayUsage.minutes - overlayPausedMinutes)

            // For session-limit challenges use the Room-persisted conscious opens counter
            // so only deliberate "Yes, open it" taps count towards the limit.
            val todayOpens: Int
            val limitExceeded: Boolean
            val remainingOpens: Int?

            when (challenge.limitType) {
                LimitType.TIME -> {
                    todayOpens = todayUsage.opens
                    limitExceeded = adjustedMinutes >= challenge.limitValueMinutes
                    remainingOpens = null
                }
                LimitType.SESSIONS -> {
                    val consciousOpens = dailyLogRepository
                        .getConsciousOpens(challenge.id, today)
                        .getOrElse { todayUsage.opens }
                    todayOpens = consciousOpens
                    val maxSessions = challenge.limitValueSessions ?: 0
                    limitExceeded = consciousOpens >= maxSessions
                    remainingOpens = maxOf(0, maxSessions - consciousOpens)
                }
                LimitType.TIME_BUDGET -> {
                    // Budget state is tracked in-memory by OverlayManager; we just provide
                    // the challenge object. The overlay handles exhaustion detection itself.
                    todayOpens = todayUsage.opens
                    limitExceeded = false
                    remainingOpens = null
                }
            }

            val remainingMinutes = when (challenge.limitType) {
                LimitType.TIME -> maxOf(0, challenge.limitValueMinutes - adjustedMinutes)
                LimitType.SESSIONS -> {
                    val maxSessionMinutes = challenge.limitValueMinutes * (challenge.limitValueSessions ?: 1)
                    maxOf(0, maxSessionMinutes - adjustedMinutes)
                }
                LimitType.TIME_BUDGET -> challenge.dailyBudgetMinutes ?: 0
            }

            Result.success(
                DailyLimitStatus(
                    challenge = challenge,
                    todayMinutes = adjustedMinutes,
                    todayOpens = todayOpens,
                    limitExceeded = limitExceeded,
                    remainingMinutes = remainingMinutes,
                    remainingOpens = remainingOpens
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun todayMidnightMs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
