package com.detox.app.domain.model

/**
 * Aggregated statistics across all challenges (active + historical).
 *
 * @param challengesCompleted  challenges with status = COMPLETED
 * @param challengesFailed     challenges with status = FAILED
 * @param perApp               per-challenge breakdown, ordered by challenge start date DESC
 */
data class OverallStatistics(
    val challengesCompleted: Int,
    val challengesFailed: Int,
    val perApp: List<AppStatistics>
)
