package com.detox.app.domain.usecase

import com.detox.app.domain.model.Challenge
import com.detox.app.domain.repository.DailyLogRepository
import java.util.Calendar
import javax.inject.Inject

/**
 * Returns the current streak for a challenge:
 *  - Soft Mode "no end date" (endDate <= 0): days since startDate.
 *  - Otherwise: consecutive successful days from DailyLog (limitExceeded == false).
 */
class GetChallengeStreakUseCase @Inject constructor(
    private val dailyLogRepository: DailyLogRepository,
) {
    suspend operator fun invoke(challenge: Challenge, nowMs: Long = System.currentTimeMillis()): Int {
        return if (challenge.endDate <= 0L) {
            ((nowMs - challenge.startDate) / DAY_MS).toInt().coerceAtLeast(0)
        } else {
            dailyLogRepository
                .getStreakForChallenge(challenge.id, todayMidnightMs(nowMs))
                .getOrElse { 0 }
        }
    }

    private fun todayMidnightMs(nowMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object {
        private const val DAY_MS: Long = 24L * 60 * 60 * 1000
    }
}
