package com.detox.app.domain.usecase

import com.detox.app.domain.model.LimitType
import timber.log.Timber
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
        Timber.d(
            "CalculatePoints ▶ limitType=$limitType, limitMinutes=$limitValueMinutes, " +
                    "limitSessions=$limitValueSessions, " +
                    "todayMinutes=$todayMinutes, todayOpens=$todayOpens"
        )

        val limitExceeded = when (limitType) {
            LimitType.TIME -> todayMinutes >= limitValueMinutes
            // TIME_BUDGET: todayMinutes = budget consumed, limitValueMinutes = total budget
            LimitType.TIME_BUDGET -> todayMinutes >= limitValueMinutes
            LimitType.SESSIONS -> {
                val maxSessions = limitValueSessions ?: run {
                    Timber.w("CalculatePoints: limitValueSessions is null for SESSIONS limit — marking exceeded")
                    return PointsResult(0, true, 0)
                }
                todayOpens >= maxSessions
            }
            // TIME_WINDOW: no usage limit — blocking is schedule-based via VPN / Accessibility.
            // Every day always counts as a success; flat base points, no bonus.
            LimitType.TIME_WINDOW -> false
        }

        if (limitExceeded) {
            Timber.d("CalculatePoints ◀ limit EXCEEDED → 0 pts")
            return PointsResult(points = 0, limitExceeded = true, bonusPoints = 0)
        }

        val basePoints = 10
        val remainingMinutes = when (limitType) {
            LimitType.TIME -> limitValueMinutes - todayMinutes
            // TIME_BUDGET: remaining = unconsumed budget
            LimitType.TIME_BUDGET -> limitValueMinutes - todayMinutes
            LimitType.SESSIONS -> {
                val maxSessionMinutes = limitValueMinutes * (limitValueSessions ?: 1)
                maxSessionMinutes - todayMinutes
            }
            // TIME_WINDOW: no remaining concept; no bonus points
            LimitType.TIME_WINDOW -> 0
        }
        val bonusPoints = maxOf(0, remainingMinutes / 5)
        val total = basePoints + bonusPoints

        Timber.d(
            "CalculatePoints ◀ within limit — remainingMinutes=$remainingMinutes, " +
                    "base=$basePoints, bonus=$bonusPoints, total=$total"
        )

        return PointsResult(
            points = total,
            limitExceeded = false,
            bonusPoints = bonusPoints
        )
    }
}
