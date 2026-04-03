package com.detox.app.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.LimitType
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

                // Skip full evaluation if already evaluated today (e.g. OverlayManager wrote
                // the log intra-day after a Hard Mode capture), but STILL check whether the
                // challenge reached its end date so status is updated correctly.
                // NOTE: A placeholder row (overlayPausedMs only, all other fields zero) is
                // NOT considered a full evaluation — real evaluations have limitExceeded=true
                // OR pointsEarned > 0.
                val existingLog = dailyLogRepository.getLogForDate(challenge.id, today)
                val existingRealLog = existingLog.getOrNull()
                    ?.takeIf { it.limitExceeded || it.pointsEarned > 0 }
                if (existingRealLog != null) {
                    Timber.d(
                        "DailyEvaluationWorker: '${challenge.appDisplayName}' already " +
                                "evaluated today — skipping point/payment logic"
                    )
                    if (now >= challenge.endDate) {
                        val log = existingRealLog
                        val finalStatus = if (log.limitExceeded) {
                            ChallengeStatus.FAILED
                        } else {
                            ChallengeStatus.COMPLETED
                        }
                        challengeRepository.updateChallengeStatus(challenge.id, finalStatus)
                        val durationDays = ((challenge.endDate - challenge.startDate) /
                                86_400_000L).toInt()
                        val mode = challenge.mode.name.lowercase()
                        NotificationHelper.createChannels(applicationContext)
                        if (finalStatus == ChallengeStatus.COMPLETED) {
                            analyticsService.logChallengeCompleted(
                                mode = mode,
                                durationDays = durationDays,
                                totalPoints = log.pointsEarned
                            )
                            NotificationHelper.sendChallengeCompleted(
                                applicationContext, challenge.appDisplayName
                            )
                        } else {
                            analyticsService.logChallengeFailed(mode)
                            NotificationHelper.sendChallengeFailed(
                                applicationContext, challenge.appDisplayName
                            )
                        }
                        Timber.d(
                            "DailyEvaluationWorker: '${challenge.appDisplayName}' end-of-challenge " +
                                    "status set to $finalStatus (log already existed)"
                        )
                    }
                    continue
                }

                // ── TIME_BUDGET: use explicit budget columns, not UsageStats ──────────
                if (challenge.limitType == LimitType.TIME_BUDGET) {
                    val totalBudget = challenge.dailyBudgetMinutes ?: 0
                    val budgetUsed = existingLog.getOrNull()?.budgetUsedMinutes ?: 0
                    val overlayPausedMs = existingLog.getOrNull()?.overlayPausedMs ?: 0L

                    Timber.d(
                        "DailyEvaluationWorker: TIME_BUDGET '${challenge.appDisplayName}' — " +
                                "budgetUsed=${budgetUsed}min, totalBudget=${totalBudget}min"
                    )

                    val pointsResult = calculatePointsUseCase(
                        limitType = LimitType.TIME_BUDGET,
                        limitValueMinutes = totalBudget,
                        limitValueSessions = null,
                        todayMinutes = budgetUsed,
                        todayOpens = 0
                    )

                    var moneyLostCents = 0
                    if (challenge.mode == ChallengeMode.HARD &&
                        challenge.stripePaymentIntentId != null
                    ) {
                        val durationDays = ((challenge.endDate - challenge.startDate) /
                                86_400_000L).toInt()
                        val isImmediateCapture = durationDays > 7
                        if (pointsResult.limitExceeded) {
                            paymentRepository.capturePayment(challenge.stripePaymentIntentId)
                                .onSuccess { moneyLostCents = challenge.amountCents ?: 0 }
                                .onFailure { e ->
                                    Timber.e(e, "Failed to capture payment for ${challenge.id}")
                                }
                        } else if (now >= challenge.endDate) {
                            paymentRepository.cancelOrRefundPayment(
                                paymentIntentId = challenge.stripePaymentIntentId,
                                wasImmediate = isImmediateCapture
                            ).onFailure { e ->
                                Timber.e(e, "Failed to cancel/refund payment for ${challenge.id}")
                            }
                        }
                    }

                    val dailyLog = DailyLog(
                        id = UUID.randomUUID().toString(),
                        challengeId = challenge.id,
                        date = today,
                        totalMinutes = budgetUsed,
                        openCount = 0,
                        overlayPausedMs = overlayPausedMs,
                        budgetUsedMinutes = budgetUsed,
                        budgetRemainingMinutes = maxOf(0, totalBudget - budgetUsed),
                        pointsEarned = pointsResult.points,
                        limitExceeded = pointsResult.limitExceeded,
                        moneyLostCents = moneyLostCents
                    )
                    dailyLogRepository.insertDailyLog(dailyLog)
                    Timber.d(
                        "DailyEvaluationWorker: TIME_BUDGET DailyLog saved — " +
                                "budgetUsed=${budgetUsed}min, pts=${pointsResult.points}, " +
                                "limitExceeded=${pointsResult.limitExceeded}"
                    )

                    if (pointsResult.points > 0) {
                        pointsRepository.addPointTransaction(
                            PointTransaction(
                                id = UUID.randomUUID().toString(),
                                type = "earned",
                                amount = 10,
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
                        NotificationHelper.createChannels(applicationContext)
                        NotificationHelper.sendDayCongratulations(
                            applicationContext, challenge.appDisplayName
                        )
                    }

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
                        NotificationHelper.createChannels(applicationContext)
                        if (finalStatus == ChallengeStatus.COMPLETED) {
                            analyticsService.logChallengeCompleted(
                                mode = mode, durationDays = durationDays,
                                totalPoints = pointsResult.points
                            )
                            NotificationHelper.sendChallengeCompleted(
                                applicationContext, challenge.appDisplayName
                            )
                        } else {
                            analyticsService.logChallengeFailed(mode)
                            NotificationHelper.sendChallengeFailed(
                                applicationContext, challenge.appDisplayName
                            )
                        }
                        Timber.d(
                            "DailyEvaluationWorker: TIME_BUDGET '${challenge.appDisplayName}' " +
                                    "ended with status: $finalStatus"
                        )
                    }
                    continue
                }

                // ── TIME / SESSIONS: use UsageStats + overlay adjustment ───────────────
                val todayUsage = usageStatsRepository.getTodayUsageForApp(challenge.appPackageName)

                // Subtract time when our overlay was covering the app so that overlay wait-time
                // doesn't count against the user's limit. Carry the value into the DailyLog.
                val overlayPausedMs = existingLog.getOrNull()?.overlayPausedMs ?: 0L
                val overlayPausedMinutes = (overlayPausedMs / 60_000L).toInt()
                val adjustedMinutes = maxOf(0, todayUsage.minutes - overlayPausedMinutes)

                Timber.d(
                    "DailyEvaluationWorker: usage for '${challenge.appDisplayName}': " +
                            "${todayUsage.minutes} raw min, -${overlayPausedMinutes} overlay min " +
                            "= $adjustedMinutes adjusted min, ${todayUsage.opens} opens"
                )

                Timber.d(
                    "DailyEvaluationWorker: CalculatePointsUseCase inputs — " +
                            "limitType=${challenge.limitType}, " +
                            "limitValueMinutes=${challenge.limitValueMinutes}, " +
                            "limitValueSessions=${challenge.limitValueSessions}, " +
                            "todayMinutes=$adjustedMinutes (adjusted), " +
                            "todayOpens=${todayUsage.opens}"
                )
                val pointsResult = calculatePointsUseCase(
                    limitType = challenge.limitType,
                    limitValueMinutes = challenge.limitValueMinutes,
                    limitValueSessions = challenge.limitValueSessions,
                    todayMinutes = adjustedMinutes,
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
                    totalMinutes = adjustedMinutes,
                    openCount = todayUsage.opens,
                    overlayPausedMs = overlayPausedMs,
                    // budgetUsedMinutes / budgetRemainingMinutes are 0 for TIME/SESSIONS
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
