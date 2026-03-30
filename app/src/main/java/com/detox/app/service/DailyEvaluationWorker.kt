package com.detox.app.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.PointTransaction
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.PointsRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.domain.usecase.CalculatePointsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.Calendar
import java.util.UUID

@HiltWorker
class DailyEvaluationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val challengeRepository: ChallengeRepository,
    private val dailyLogRepository: DailyLogRepository,
    private val pointsRepository: PointsRepository,
    private val usageStatsRepository: UsageStatsRepository,
    private val calculatePointsUseCase: CalculatePointsUseCase
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("DailyEvaluationWorker starting")

        return try {
            val challenges = challengeRepository.getActiveChallengesList().getOrThrow()
            val today = getStartOfDay()
            val now = System.currentTimeMillis()

            for (challenge in challenges) {
                // Check if already evaluated today
                val existingLog = dailyLogRepository.getLogForDate(challenge.id, today)
                if (existingLog.isSuccess && existingLog.getOrNull() != null) {
                    Timber.d("Already evaluated ${challenge.appDisplayName} for today")
                    continue
                }

                val todayUsage = usageStatsRepository.getTodayUsageForApp(challenge.appPackageName)

                val pointsResult = calculatePointsUseCase(
                    limitType = challenge.limitType,
                    limitValueMinutes = challenge.limitValueMinutes,
                    limitValueSessions = challenge.limitValueSessions,
                    todayMinutes = todayUsage.minutes,
                    todayOpens = todayUsage.opens
                )

                // Create daily log
                val dailyLog = DailyLog(
                    id = UUID.randomUUID().toString(),
                    challengeId = challenge.id,
                    date = today,
                    totalMinutes = todayUsage.minutes,
                    openCount = todayUsage.opens,
                    pointsEarned = pointsResult.points,
                    limitExceeded = pointsResult.limitExceeded,
                    moneyLostCents = 0
                )
                dailyLogRepository.insertDailyLog(dailyLog)

                // Create point transactions
                if (pointsResult.points > 0) {
                    val basePoints = 10
                    pointsRepository.addPointTransaction(
                        PointTransaction(
                            id = UUID.randomUUID().toString(),
                            type = "earned",
                            amount = basePoints,
                            reason = "daily_goal_met",
                            challengeId = challenge.id,
                            timestamp = now
                        )
                    )

                    if (pointsResult.bonusPoints > 0) {
                        pointsRepository.addPointTransaction(
                            PointTransaction(
                                id = UUID.randomUUID().toString(),
                                type = "earned",
                                amount = pointsResult.bonusPoints,
                                reason = "bonus_under_limit",
                                challengeId = challenge.id,
                                timestamp = now
                            )
                        )
                    }
                }

                // Check if challenge ended
                if (now >= challenge.endDate) {
                    val finalStatus = if (pointsResult.limitExceeded) {
                        ChallengeStatus.FAILED
                    } else {
                        ChallengeStatus.COMPLETED
                    }
                    challengeRepository.updateChallengeStatus(challenge.id, finalStatus)
                    Timber.d("Challenge ${challenge.appDisplayName} ended with status: $finalStatus")
                }

                Timber.d("Evaluated ${challenge.appDisplayName}: ${pointsResult.points} pts, exceeded=${pointsResult.limitExceeded}")
            }

            Timber.d("DailyEvaluationWorker completed successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "DailyEvaluationWorker failed")
            Result.retry()
        }
    }

    private fun getStartOfDay(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
