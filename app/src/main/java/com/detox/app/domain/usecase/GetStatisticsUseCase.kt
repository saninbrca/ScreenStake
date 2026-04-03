package com.detox.app.domain.usecase

import com.detox.app.domain.model.AppStatistics
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.OverallStatistics
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.PointsRepository
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class GetStatisticsUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val dailyLogRepository: DailyLogRepository,
    private val pointsRepository: PointsRepository
) {
    suspend operator fun invoke(): Result<OverallStatistics> {
        return try {
            val challenges = challengeRepository.getAllChallenges().first()
            val totalPoints = pointsRepository.getTotalPointsBalance().first()
            val weekCutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)

            val perApp = challenges.map { challenge ->
                buildAppStatistics(challenge, weekCutoff)
            }

            val weeklyPoints = perApp.sumOf { appStats ->
                appStats.recentLogs
                    .filter { it.date >= weekCutoff }
                    .sumOf { it.pointsEarned }
            }

            val stats = OverallStatistics(
                totalPoints = totalPoints,
                weeklyPoints = weeklyPoints,
                challengesCompleted = challenges.count { it.status == ChallengeStatus.COMPLETED },
                challengesFailed = challenges.count { it.status == ChallengeStatus.FAILED },
                perApp = perApp
            )

            Timber.d("Statistics loaded: ${challenges.size} challenges, $totalPoints total pts")
            Result.success(stats)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load statistics")
            Result.failure(e)
        }
    }

    private suspend fun buildAppStatistics(
        challenge: Challenge,
        weekCutoff: Long
    ): AppStatistics {
        val allLogs = dailyLogRepository.getLogsForChallenge(challenge.id)
            .first()
            .sortedByDescending { it.date }          // newest first

        val daysSucceeded = allLogs.count { !it.limitExceeded }
        val daysExceeded = allLogs.count { it.limitExceeded }
        val totalPoints = allLogs.sumOf { it.pointsEarned }
        val recentLogs = allLogs.take(7)             // last 7 days for the dot row

        // Compute streaks on logs sorted oldest-first
        val oldestFirst = allLogs.reversed()
        val (currentStreak, bestStreak) = computeStreaks(oldestFirst)

        return AppStatistics(
            challenge = challenge,
            totalDaysTracked = allLogs.size,
            daysSucceeded = daysSucceeded,
            daysExceeded = daysExceeded,
            totalPointsEarned = totalPoints,
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            recentLogs = recentLogs
        )
    }

    /**
     * Returns (currentStreak, bestStreak) from a list of logs sorted oldest-first.
     * A "streak" is consecutive days where limitExceeded == false.
     */
    private fun computeStreaks(logsOldestFirst: List<DailyLog>): Pair<Int, Int> {
        if (logsOldestFirst.isEmpty()) return 0 to 0

        var best = 0
        var running = 0
        for (log in logsOldestFirst) {
            if (!log.limitExceeded) {
                running++
                if (running > best) best = running
            } else {
                running = 0
            }
        }
        // currentStreak = running streak ending at the latest log
        return running to best
    }
}
