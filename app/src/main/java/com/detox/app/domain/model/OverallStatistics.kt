package com.detox.app.domain.model

/**
 * Aggregated statistics across all challenges (active + historical).
 *
 * @param totalPoints          lifetime points balance (from PointsRepository)
 * @param weeklyPoints         points earned in the last 7 days
 * @param challengesCompleted  challenges with status = COMPLETED
 * @param challengesFailed     challenges with status = FAILED
 * @param perApp               per-challenge breakdown, ordered by challenge start date DESC
 */
data class OverallStatistics(
    val totalPoints: Int,
    val weeklyPoints: Int,
    val challengesCompleted: Int,
    val challengesFailed: Int,
    val perApp: List<AppStatistics>
)
