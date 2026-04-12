package com.detox.app.domain.usecase

import com.detox.app.domain.model.AppStatistics
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.OverallStatistics
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.model.ChallengeStatus
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class GetStatisticsUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val dailyLogRepository: DailyLogRepository
) {
    suspend operator fun invoke(): Result<OverallStatistics> {
        return try {
            val challenges = challengeRepository.getAllChallenges().first()

            val perApp = challenges.map { challenge ->
                buildAppStatistics(challenge)
            }

            val stats = OverallStatistics(
                challengesCompleted = challenges.count { it.status == ChallengeStatus.COMPLETED },
                challengesFailed = challenges.count { it.status == ChallengeStatus.FAILED },
                perApp = perApp
            )

            Timber.d("Statistics loaded: ${challenges.size} challenges")
            Result.success(stats)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load statistics")
            Result.failure(e)
        }
    }

    private suspend fun buildAppStatistics(challenge: Challenge): AppStatistics {
        val allLogs = dailyLogRepository.getLogsForChallenge(challenge.id)
            .first()
            .sortedByDescending { it.date }          // newest first

        val daysSucceeded = allLogs.count { !it.limitExceeded }
        val daysExceeded = allLogs.count { it.limitExceeded }
        val recentLogs = allLogs.take(7)             // last 7 days for the dot row

        // Compute streaks on logs sorted oldest-first
        val oldestFirst = allLogs.reversed()
        val (currentStreak, bestStreak) = computeStreaks(oldestFirst)

        return AppStatistics(
            challenge = challenge,
            totalDaysTracked = allLogs.size,
            daysSucceeded = daysSucceeded,
            daysExceeded = daysExceeded,
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
