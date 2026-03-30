package com.detox.app.domain.usecase

import com.detox.app.domain.model.LimitType
import javax.inject.Inject

data class PointsResult(
    val points: Int,
    val limitExceeded: Boolean,
    val bonusPoints: Int
)

class CalculatePointsUseCase @Inject constructor() {

    operator fun invoke(
        limitType: LimitType,
        limitValueMinutes: Int,
        limitValueSessions: Int?,
        todayMinutes: Int,
        todayOpens: Int
    ): PointsResult {
        val limitExceeded = when (limitType) {
            LimitType.TIME -> todayMinutes >= limitValueMinutes
            LimitType.SESSIONS -> {
                val maxSessions = limitValueSessions ?: return PointsResult(0, true, 0)
                todayOpens >= maxSessions
            }
        }

        if (limitExceeded) {
            return PointsResult(points = 0, limitExceeded = true, bonusPoints = 0)
        }

        val basePoints = 10
        val remainingMinutes = when (limitType) {
            LimitType.TIME -> limitValueMinutes - todayMinutes
            LimitType.SESSIONS -> {
                val maxSessionMinutes = limitValueMinutes * (limitValueSessions ?: 1)
                maxSessionMinutes - todayMinutes
            }
        }
        val bonusPoints = maxOf(0, remainingMinutes / 5)

        return PointsResult(
            points = basePoints + bonusPoints,
            limitExceeded = false,
            bonusPoints = bonusPoints
        )
    }
}
