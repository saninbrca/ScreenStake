package com.detox.app.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.PointTransaction
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.PaymentRepository
import com.detox.app.domain.repository.PointsRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.data.remote.firebase.AnalyticsService
import com.detox.app.domain.usecase.CalculatePointsUseCase
import com.google.firebase.crashlytics.FirebaseCrashlytics
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
    private val paymentRepository: PaymentRepository,
    private val calculatePointsUseCase: CalculatePointsUseCase,
    private val analyticsService: AnalyticsService
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("DailyEvaluationWorker: ▶ starting (runAttemptCount=$runAttemptCount)")

        return try {
            val challenges = challengeRepository.getActiveChallengesList().getOrThrow()
            val today = getStartOfDay()
            val now = System.currentTimeMillis()
            Timber.d(
                "DailyEvaluationWorker: found ${challenges.size} active challenge(s), " +
                        "today=$today"
            )

            for (challenge in challenges) {
                Timber.d(
                    "DailyEvaluationWorker: evaluating '${challenge.appDisplayName}' " +
                            "(id=${challenge.id}, mode=${challenge.mode}, " +
                            "limitType=${challenge.limitType})"
                )

                // Skip if already evaluated today
                val existingLog = dailyLogRepository.getLogForDate(challenge.id, today)
                if (existingLog.isSuccess && existingLog.getOrNull() != null) {
                    Timber.d(
                        "DailyEvaluationWorker: '${challenge.appDisplayName}' already " +
                                "evaluated today — skipping"
                    )
                    continue
                }

                val todayUsage = usageStatsRepository.getTodayUsageForApp(challenge.appPackageName)
                Timber.d(
                    "DailyEvaluationWorker: usage for '${challenge.appDisplayName}': " +
                            "${todayUsage.minutes} min, ${todayUsage.opens} opens"
                )

                Timber.d(
                    "DailyEvaluationWorker: CalculatePointsUseCase inputs — " +
                            "limitType=${challenge.limitType}, " +
                            "limitValueMinutes=${challenge.limitValueMinutes}, " +
                            "limitValueSessions=${challenge.limitValueSessions}, " +
                            "todayMinutes=${todayUsage.minutes}, " +
                            "todayOpens=${todayUsage.opens}"
                )
                val pointsResult = calculatePointsUseCase(
                    limitType = challenge.limitType,
                    limitValueMinutes = challenge.limitValueMinutes,
                    limitValueSessions = challenge.limitValueSessions,
                    todayMinutes = todayUsage.minutes,
                    todayOpens = todayUsage.opens
                )
                Timber.d(
                    "DailyEvaluationWorker: CalculatePointsUseCase result — " +
                            "points=${pointsResult.points}, bonus=${pointsResult.bonusPoints}, " +
                            "limitExceeded=${pointsResult.limitExceeded}"
                )

                // ── Hard Mode: handle Stripe payment ──────────────────────────
                var moneyLostCents = 0
                if (challenge.mode == ChallengeMode.HARD &&
                    challenge.stripePaymentIntentId != null
                ) {
                    val durationDays = ((challenge.endDate - challenge.startDate) /
                            86_400_000L).toInt()
                    val isImmediateCapture = durationDays > 7

                    if (pointsResult.limitExceeded) {
                        // User broke their Hard Mode limit today → capture payment
                        Timber.d("Hard Mode limit exceeded for ${challenge.appDisplayName} — capturing payment")
                        paymentRepository.capturePayment(challenge.stripePaymentIntentId)
                            .onSuccess {
                                moneyLostCents = challenge.amountCents ?: 0
                                Timber.d("Payment captured: €${moneyLostCents / 100f}")
                            }
                            .onFailure { e ->
                                Timber.e(e, "Failed to capture payment for ${challenge.id}")
                            }
                    } else if (now >= challenge.endDate) {
                        // Challenge completed successfully → cancel pre-auth or issue refund
                        Timber.d("Hard Mode challenge ${challenge.appDisplayName} completed — refunding/cancelling")
                        paymentRepository.cancelOrRefundPayment(
                            paymentIntentId = challenge.stripePaymentIntentId,
                            wasImmediate = isImmediateCapture
                        ).onFailure { e ->
                            Timber.e(e, "Failed to cancel/refund payment for ${challenge.id}")
                        }
                    }
                }

                // ── Create daily log ───────────────────────────────────────────
                val dailyLog = DailyLog(
                    id = UUID.randomUUID().toString(),
                    challengeId = challenge.id,
                    date = today,
                    totalMinutes = todayUsage.minutes,
                    openCount = todayUsage.opens,
                    pointsEarned = pointsResult.points,
                    limitExceeded = pointsResult.limitExceeded,
                    moneyLostCents = moneyLostCents
                )
                dailyLogRepository.insertDailyLog(dailyLog)
                Timber.d(
                    "DailyEvaluationWorker: DailyLog saved — id=${dailyLog.id}, " +
                            "pointsEarned=${dailyLog.pointsEarned}, " +
                            "limitExceeded=${dailyLog.limitExceeded}"
                )

                // ── Award points ───────────────────────────────────────────────
                if (pointsResult.points > 0) {
                    val baseTransaction = PointTransaction(
                        id = UUID.randomUUID().toString(),
                        type = "earned",
                        amount = 10,
                        reason = "daily_goal_met",
                        challengeId = challenge.id,
                        timestamp = now
                    )
                    pointsRepository.addPointTransaction(baseTransaction)
                    Timber.d(
                        "DailyEvaluationWorker: wrote base transaction — " +
                                "+10 pts (daily_goal_met) for '${challenge.appDisplayName}'"
                    )

                    if (pointsResult.bonusPoints > 0) {
                        val bonusTransaction = PointTransaction(
                            id = UUID.randomUUID().toString(),
                            type = "earned",
                            amount = pointsResult.bonusPoints,
                            reason = "bonus_under_limit",
                            challengeId = challenge.id,
                            timestamp = now
                        )
                        pointsRepository.addPointTransaction(bonusTransaction)
                        Timber.d(
                            "DailyEvaluationWorker: wrote bonus transaction — " +
                                    "+${pointsResult.bonusPoints} pts (bonus_under_limit) " +
                                    "for '${challenge.appDisplayName}'"
                        )
                    }
                    // ── Congratulations notification for a successful day ───────
                    NotificationHelper.createChannels(applicationContext)
                    NotificationHelper.sendDayCongratulations(
                        applicationContext,
                        challenge.appDisplayName
                    )
                } else {
                    Timber.d(
                        "DailyEvaluationWorker: no points awarded for '${challenge.appDisplayName}' " +
                                "(limitExceeded=${pointsResult.limitExceeded})"
                    )
                }

                // ── Update challenge status if end date reached ─────────────────
                if (now >= challenge.endDate) {
                    val finalStatus = if (pointsResult.limitExceeded) {
                        ChallengeStatus.FAILED
                    } else {
                        ChallengeStatus.COMPLETED
                    }
                    challengeRepository.updateChallengeStatus(challenge.id, finalStatus)
                    val durationDays = ((challenge.endDate - challenge.startDate) /
                            86_400_000L).toInt()
                    val mode = challenge.mode.name.lowercase()
                    if (finalStatus == ChallengeStatus.COMPLETED) {
                        analyticsService.logChallengeCompleted(
                            mode = mode,
                            durationDays = durationDays,
                            totalPoints = pointsResult.points
                        )
                    } else {
                        analyticsService.logChallengeFailed(mode)
                    }
                    Timber.d(
                        "Challenge ${challenge.appDisplayName} ended with status: $finalStatus"
                    )
                    // Milestone notification
                    NotificationHelper.createChannels(applicationContext)
                    if (finalStatus == ChallengeStatus.COMPLETED) {
                        NotificationHelper.sendChallengeCompleted(
                            applicationContext, challenge.appDisplayName
                        )
                    } else {
                        NotificationHelper.sendChallengeFailed(
                            applicationContext, challenge.appDisplayName
                        )
                    }
                }

                Timber.d(
                    "DailyEvaluationWorker: ✓ '${challenge.appDisplayName}' done — " +
                            "pts=${pointsResult.points}, exceeded=${pointsResult.limitExceeded}, " +
                            "moneyLost=${moneyLostCents / 100f}€"
                )
            }

            // ── Post daily summary notification ────────────────────────────────
            val totalPointsEarned = challenges.sumOf { challenge ->
                // Re-read today's log to sum points (already written above)
                dailyLogRepository.getLogForDate(challenge.id, today)
                    .getOrNull()?.pointsEarned ?: 0
            }
            val onTrackCount = challenges.count { challenge ->
                dailyLogRepository.getLogForDate(challenge.id, today)
                    .getOrNull()?.limitExceeded == false
            }
            NotificationHelper.createChannels(applicationContext)
            NotificationHelper.sendDailyReport(
                context = applicationContext,
                pointsEarned = totalPointsEarned,
                onTrackCount = onTrackCount,
                totalCount = challenges.size
            )

            Timber.d(
                "DailyEvaluationWorker: ■ completed successfully — " +
                        "processed ${challenges.size} challenge(s)"
            )
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "DailyEvaluationWorker failed")
            FirebaseCrashlytics.getInstance().recordException(e)
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
