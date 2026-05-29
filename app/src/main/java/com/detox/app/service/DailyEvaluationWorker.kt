package com.detox.app.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.GroupChallengeDao
import com.detox.app.data.remote.firebase.AnalyticsService
import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.ParticipantStatus
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.GroupChallengeRepository
import com.detox.app.domain.usecase.GetChallengeStreakUseCase
import com.detox.app.domain.repository.PaymentRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.util.DateUtils
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.floor

@HiltWorker
class DailyEvaluationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val challengeRepository: ChallengeRepository,
    private val dailyLogRepository: DailyLogRepository,
    private val usageStatsRepository: UsageStatsRepository,
    private val paymentRepository: PaymentRepository,
    private val analyticsService: AnalyticsService,
    private val cloudFunctionsService: CloudFunctionsService,
    private val firebaseAuthService: FirebaseAuthService,
    private val groupChallengeRepository: GroupChallengeRepository,
    private val challengeDao: ChallengeDao,
    private val groupChallengeDao: GroupChallengeDao,
    private val firestoreService: FirestoreService,
    private val firestore: FirebaseFirestore,
    private val getChallengeStreakUseCase: GetChallengeStreakUseCase,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("DailyEvaluationWorker: ▶ starting (runAttemptCount=$runAttemptCount)")

        // Clear budget session accumulator at midnight so day-N committed time doesn't bleed into day-N+1
        val budgetPrefs = applicationContext.getSharedPreferences(
            OverlayManager.BUDGET_SESSION_PREFS_NAME, Context.MODE_PRIVATE
        )
        budgetPrefs.edit()
            .putLong(OverlayManager.BUDGET_COMMITTED_MS_KEY, 0L)
            .putLong(OverlayManager.BUDGET_SESSION_END_TIME_KEY, 0L)
            .putLong(OverlayManager.BUDGET_SESSION_START_TIME_KEY, 0L)
            .remove(OverlayManager.BUDGET_SESSION_CHALLENGE_KEY)
            .remove(OverlayManager.BUDGET_SESSION_PACKAGE_KEY)
            .apply()

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
                            "limitType=${challenge.limitType}, " +
                            "groupChallengeId=${challenge.groupChallengeId})"
                )

                // ── Group Challenge shadow row: delegate to Cloud Functions ───────────
                if (challenge.groupChallengeId != null) {
                    evaluateGroupChallenge(challenge, challenge.groupChallengeId, today, now)
                    continue
                }

                // ── Apply pending limit reduction if due ──────────────────────────────
                if (challenge.pendingLimitValue != null &&
                    now >= (challenge.pendingLimitAppliesAt ?: Long.MAX_VALUE)) {
                    val newLimit = challenge.pendingLimitValue!!
                    val currentLimit = when (challenge.limitType) {
                        LimitType.SESSIONS    -> challenge.limitValueSessions ?: Int.MAX_VALUE
                        LimitType.TIME        -> challenge.limitValueMinutes
                        LimitType.TIME_BUDGET -> challenge.dailyBudgetMinutes ?: Int.MAX_VALUE
                        else -> Int.MAX_VALUE
                    }
                    if (newLimit < currentLimit) {
                        Timber.d(
                            "DailyEvaluationWorker: applying pending limit id=${challenge.id} " +
                            "newLimit=$newLimit limitType=${challenge.limitType}"
                        )
                        val userId = firebaseAuthService.currentUserId()
                        if (userId != null) {
                            val limitFieldKey = when (challenge.limitType) {
                                LimitType.SESSIONS    -> "limitValueSessions"
                                LimitType.TIME        -> "limitValueMinutes"
                                LimitType.TIME_BUDGET -> "dailyBudgetMinutes"
                                else -> null
                            }
                            if (limitFieldKey != null) {
                                firestore.collection("users").document(userId)
                                    .collection("challenges").document(challenge.id)
                                    .update(mapOf(
                                        limitFieldKey to newLimit,
                                        "pendingLimitValue" to null,
                                        "pendingLimitAppliesAt" to null
                                    )).await()
                            }
                        }
                        challengeDao.applyPendingLimit(
                            id = challenge.id,
                            newSessions = if (challenge.limitType == LimitType.SESSIONS) newLimit else null,
                            newMinutes  = if (challenge.limitType == LimitType.TIME) newLimit else null,
                            newBudget   = if (challenge.limitType == LimitType.TIME_BUDGET) newLimit else null
                        )
                    } else {
                        Timber.w(
                            "DailyEvaluationWorker: discarding pending limit that would not reduce " +
                            "id=${challenge.id} newLimit=$newLimit currentLimit=$currentLimit"
                        )
                        challengeDao.applyPendingLimit(challenge.id, null, null, null)
                    }
                }

                // Skip full evaluation if already evaluated today (e.g. OverlayManager wrote
                // the log intra-day after a Hard Mode capture), but STILL check whether the
                // challenge reached its end date so status is updated correctly.
                // NOTE: A placeholder row (overlayPausedMs only, all other fields zero) is
                // NOT considered a full evaluation — real evaluations have limitExceeded=true
                // OR actual usage data (totalMinutes/openCount/budgetUsedMinutes > 0).
                val existingLog = dailyLogRepository.getLogForDate(challenge.id, today)
                val existingRealLog = existingLog.getOrNull()
                    ?.takeIf {
                        it.limitExceeded ||
                                it.totalMinutes > 0 ||
                                it.openCount > 0 ||
                                it.budgetUsedMinutes > 0
                    }
                if (existingRealLog != null) {
                    Timber.d(
                        "DailyEvaluationWorker: '${challenge.appDisplayName}' already " +
                                "evaluated today — skipping payment logic"
                    )
                    val durationDays = ((challenge.endDate - challenge.startDate) /
                            DateUtils.MILLIS_PER_DAY).toInt()
                    if (now >= challenge.endDate || durationDays == 1) {
                        val log = existingRealLog
                        val finalStatus = if (log.limitExceeded) {
                            ChallengeStatus.FAILED
                        } else {
                            ChallengeStatus.COMPLETED
                        }
                        challengeRepository.updateChallengeStatus(challenge.id, finalStatus)
                        val mode = challenge.mode.name.lowercase()
                        NotificationHelper.createChannels(applicationContext)
                        if (finalStatus == ChallengeStatus.COMPLETED) {
                            analyticsService.logChallengeCompleted(
                                mode = mode,
                                durationDays = durationDays
                            )
                            if (challenge.mode == ChallengeMode.HARD && challenge.amountCents != null) {
                                val refundAmount = floor(challenge.amountCents * 0.80).toInt()
                                val feeCents = challenge.amountCents - refundAmount
                                NotificationHelper.sendHardModeCompleted(
                                    applicationContext, challenge.appDisplayName, refundAmount, feeCents
                                )
                            } else {
                                NotificationHelper.sendChallengeCompleted(
                                    applicationContext, challenge.appDisplayName
                                )
                            }
                        } else {
                            analyticsService.logChallengeFailed(mode)
                            NotificationHelper.sendChallengeFailed(
                                applicationContext, challenge.appDisplayName
                            )
                            if (challenge.mode == ChallengeMode.SOFT) {
                                val streak = getChallengeStreakUseCase(challenge)
                                TrackedAppEventBus.emitNavigateToSoftFailResult(challenge.id, streak)
                            }
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

                    val limitExceeded = budgetUsed >= totalBudget

                    var moneyLostCents = 0
                    if (challenge.mode == ChallengeMode.HARD &&
                        challenge.stripePaymentIntentId != null
                    ) {
                        val durationDays = ((challenge.endDate - challenge.startDate) /
                                DateUtils.MILLIS_PER_DAY).toInt()
                        if (limitExceeded) {
                            paymentRepository.capturePayment(challenge.stripePaymentIntentId)
                                .onSuccess {
                                    moneyLostCents = challenge.amountCents ?: 0
                                    setRedemptionInfo(challenge, now)
                                }
                                .onFailure { e ->
                                    Timber.e(e, "Failed to capture payment for ${challenge.id}")
                                }
                        } else if (now >= challenge.endDate || durationDays == 1) {
                            if (challenge.isRedemption && challenge.originalPaymentIntentId != null && challenge.refundAmountCents != null) {
                                Timber.d("Redemption TIME_BUDGET challenge ${challenge.id} completed → 60%% partial refund")
                                val userId = firebaseAuthService.currentUserId()
                                paymentRepository.cancelOrRefundPayment(
                                    paymentIntentId = challenge.originalPaymentIntentId,
                                    challengeId = challenge.id,
                                    userId = userId,
                                    amountCents = challenge.refundAmountCents,
                                    partialRefundCents = challenge.refundAmountCents
                                ).onFailure { e ->
                                    Timber.e(e, "Failed to partial-refund redemption payment for ${challenge.id}")
                                }
                            } else {
                                // Normal Hard Mode win → 80% refund (app keeps 20%)
                                val originalAmount = challenge.amountCents ?: 0
                                val refundAmount = floor(originalAmount * 0.80).toInt()
                                Timber.d("Challenge ${challenge.id} TIME_BUDGET completed → 80%% refund €${refundAmount / 100f}")
                                val userId = firebaseAuthService.currentUserId()
                                paymentRepository.cancelOrRefundPayment(
                                    paymentIntentId = challenge.stripePaymentIntentId,
                                    challengeId = challenge.id,
                                    userId = userId,
                                    amountCents = refundAmount
                                ).onFailure { e ->
                                    Timber.e(e, "Failed to cancel/refund payment for ${challenge.id}")
                                }
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
                        pointsEarned = 0,
                        limitExceeded = limitExceeded,
                        moneyLostCents = moneyLostCents
                    )
                    dailyLogRepository.insertDailyLog(dailyLog)
                    Timber.d(
                        "DailyEvaluationWorker: TIME_BUDGET DailyLog saved — " +
                                "budgetUsed=${budgetUsed}min, limitExceeded=$limitExceeded"
                    )

                    if (!limitExceeded) {
                        NotificationHelper.createChannels(applicationContext)
                        NotificationHelper.sendDayCongratulations(
                            applicationContext, challenge.appDisplayName
                        )
                    }

                    val durationDays = ((challenge.endDate - challenge.startDate) /
                            DateUtils.MILLIS_PER_DAY).toInt()
                    if (now >= challenge.endDate || durationDays == 1) {
                        val finalStatus = if (limitExceeded) {
                            ChallengeStatus.FAILED
                        } else {
                            ChallengeStatus.COMPLETED
                        }
                        challengeRepository.updateChallengeStatus(challenge.id, finalStatus)
                        val mode = challenge.mode.name.lowercase()
                        NotificationHelper.createChannels(applicationContext)
                        if (finalStatus == ChallengeStatus.COMPLETED) {
                            analyticsService.logChallengeCompleted(
                                mode = mode, durationDays = durationDays
                            )
                            if (challenge.isRedemption && challenge.refundAmountCents != null) {
                                NotificationHelper.sendRedemptionCompleted(
                                    applicationContext, challenge.appDisplayName, challenge.refundAmountCents
                                )
                            } else if (challenge.mode == ChallengeMode.HARD && challenge.amountCents != null) {
                                val refundAmount = floor(challenge.amountCents * 0.80).toInt()
                                val feeCents = challenge.amountCents - refundAmount
                                NotificationHelper.sendHardModeCompleted(
                                    applicationContext, challenge.appDisplayName, refundAmount, feeCents
                                )
                            } else {
                                NotificationHelper.sendChallengeCompleted(
                                    applicationContext, challenge.appDisplayName
                                )
                            }
                        } else {
                            analyticsService.logChallengeFailed(mode)
                            if (challenge.isRedemption) {
                                NotificationHelper.sendRedemptionFailed(applicationContext, challenge.appDisplayName)
                            } else {
                                NotificationHelper.sendChallengeFailed(
                                    applicationContext, challenge.appDisplayName
                                )
                            }
                            if (challenge.mode == ChallengeMode.SOFT) {
                                val streak = getChallengeStreakUseCase(challenge)
                                TrackedAppEventBus.emitNavigateToSoftFailResult(challenge.id, streak)
                            }
                        }
                        Timber.d(
                            "DailyEvaluationWorker: TIME_BUDGET '${challenge.appDisplayName}' " +
                                    "ended with status: $finalStatus"
                        )
                    }
                    continue
                }

                // ── TIME / SESSIONS: use UsageStats + overlay adjustment ───────────────
                // Sum usage across all tracked packages in this challenge (multi-app support)
                val todayUsage = challenge.appPackageNames.fold(
                    com.detox.app.domain.model.AppDailyUsage(0, 0)
                ) { acc, pkg ->
                    Timber.d(
                        "DailyEvaluationWorker: checking package=$pkg against challenge " +
                                "packages=${challenge.appPackageNames}"
                    )
                    val usage = usageStatsRepository.getTodayUsageForApp(pkg)
                    com.detox.app.domain.model.AppDailyUsage(
                        minutes = acc.minutes + usage.minutes,
                        opens = acc.opens + usage.opens
                    )
                }

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

                val limitExceeded = computeLimitExceeded(
                    limitType = challenge.limitType,
                    limitValueMinutes = challenge.limitValueMinutes,
                    limitValueSessions = challenge.limitValueSessions,
                    adjustedMinutes = adjustedMinutes,
                    opens = todayUsage.opens
                )
                Timber.d(
                    "DailyEvaluationWorker: limitExceeded=$limitExceeded for '${challenge.appDisplayName}'"
                )

                // ── Hard Mode: handle Stripe payment ──────────────────────────
                var moneyLostCents = 0
                if (challenge.mode == ChallengeMode.HARD &&
                    challenge.stripePaymentIntentId != null
                ) {
                    val durationDays = ((challenge.endDate - challenge.startDate) /
                            DateUtils.MILLIS_PER_DAY).toInt()

                    if (limitExceeded) {
                        // User broke their Hard Mode limit today → capture payment
                        Timber.d("Hard Mode limit exceeded for ${challenge.appDisplayName} — capturing payment")
                        paymentRepository.capturePayment(challenge.stripePaymentIntentId)
                            .onSuccess {
                                moneyLostCents = challenge.amountCents ?: 0
                                Timber.d("Payment captured: €${moneyLostCents / 100f}")
                                setRedemptionInfo(challenge, now)
                            }
                            .onFailure { e ->
                                Timber.e(e, "Failed to capture payment for ${challenge.id}")
                            }
                    } else if (now >= challenge.endDate || durationDays == 1) {
                        if (challenge.isRedemption && challenge.originalPaymentIntentId != null && challenge.refundAmountCents != null) {
                            // Redemption Challenge completed → 60% partial refund from original payment
                            Timber.d("Redemption challenge ${challenge.id} completed → partial refund €${challenge.refundAmountCents / 100f}")
                            val userId = firebaseAuthService.currentUserId()
                            paymentRepository.cancelOrRefundPayment(
                                paymentIntentId = challenge.originalPaymentIntentId,
                                challengeId = challenge.id,
                                userId = userId,
                                amountCents = challenge.refundAmountCents,
                                partialRefundCents = challenge.refundAmountCents
                            ).onFailure { e ->
                                Timber.e(e, "Failed to partial-refund redemption payment for ${challenge.id}")
                            }
                        } else {
                            // Normal Hard Mode win → 80% refund (app keeps 20%)
                            val originalAmount = challenge.amountCents ?: 0
                            val refundAmount = floor(originalAmount * 0.80).toInt()
                            Timber.d("Challenge ${challenge.id} completed → 80%% refund €${refundAmount / 100f} (fee €${(originalAmount - refundAmount) / 100f})")
                            val userId = firebaseAuthService.currentUserId()
                            paymentRepository.cancelOrRefundPayment(
                                paymentIntentId = challenge.stripePaymentIntentId,
                                challengeId = challenge.id,
                                userId = userId,
                                amountCents = refundAmount
                            ).onFailure { e ->
                                Timber.e(e, "Failed to cancel/refund payment for ${challenge.id}")
                            }
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
                    pointsEarned = 0,
                    limitExceeded = limitExceeded,
                    moneyLostCents = moneyLostCents
                )
                dailyLogRepository.insertDailyLog(dailyLog)
                Timber.d(
                    "DailyEvaluationWorker: DailyLog saved — id=${dailyLog.id}, " +
                            "limitExceeded=${dailyLog.limitExceeded}"
                )

                // ── Congratulations notification for a successful day ──────────
                if (!limitExceeded) {
                    NotificationHelper.createChannels(applicationContext)
                    NotificationHelper.sendDayCongratulations(
                        applicationContext,
                        challenge.appDisplayName
                    )
                } else {
                    Timber.d(
                        "DailyEvaluationWorker: limit exceeded for '${challenge.appDisplayName}'"
                    )
                }

                // ── Update challenge status if end date reached ─────────────────
                val durationDays = ((challenge.endDate - challenge.startDate) /
                        DateUtils.MILLIS_PER_DAY).toInt()
                if (now >= challenge.endDate || durationDays == 1) {
                    val finalStatus = if (limitExceeded) {
                        ChallengeStatus.FAILED
                    } else {
                        ChallengeStatus.COMPLETED
                    }
                    challengeRepository.updateChallengeStatus(challenge.id, finalStatus)
                    val mode = challenge.mode.name.lowercase()
                    if (finalStatus == ChallengeStatus.COMPLETED) {
                        analyticsService.logChallengeCompleted(
                            mode = mode,
                            durationDays = durationDays
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
                        if (challenge.isRedemption && challenge.refundAmountCents != null) {
                            NotificationHelper.sendRedemptionCompleted(
                                applicationContext, challenge.appDisplayName, challenge.refundAmountCents
                            )
                        } else if (challenge.mode == ChallengeMode.HARD && challenge.amountCents != null) {
                            val refundAmount = floor(challenge.amountCents * 0.80).toInt()
                            val feeCents = challenge.amountCents - refundAmount
                            NotificationHelper.sendHardModeCompleted(
                                applicationContext, challenge.appDisplayName, refundAmount, feeCents
                            )
                        } else {
                            NotificationHelper.sendChallengeCompleted(
                                applicationContext, challenge.appDisplayName
                            )
                        }
                    } else {
                        if (challenge.isRedemption) {
                            NotificationHelper.sendRedemptionFailed(applicationContext, challenge.appDisplayName)
                        } else {
                            NotificationHelper.sendChallengeFailed(
                                applicationContext, challenge.appDisplayName
                            )
                        }
                        if (challenge.mode == ChallengeMode.SOFT) {
                            val streak = getChallengeStreakUseCase(challenge)
                            TrackedAppEventBus.emitNavigateToSoftFailResult(challenge.id, streak)
                        }
                    }
                }

                Timber.d(
                    "DailyEvaluationWorker: ✓ '${challenge.appDisplayName}' done — " +
                            "exceeded=$limitExceeded, moneyLost=${moneyLostCents / 100f}€"
                )
            }

            // ── Auto-cancel expired WAITING group challenges ───────────────────
            val waitingGroups = groupChallengeDao.getByStatus(listOf("waiting"))
            val userId = firebaseAuthService.currentUserId()
            for (wg in waitingGroups) {
                val expiresAt = wg.authorizationExpiresAt
                if (expiresAt <= 0L) continue
                if (now >= expiresAt) {
                    Timber.d("DailyEvaluationWorker: group %s auth expired — calling expireGroupChallenge", wg.groupId)
                    cloudFunctionsService.expireGroupChallenge(wg.groupId)
                        .onSuccess {
                            groupChallengeDao.updateStatus(wg.groupId, "cancelled")
                            NotificationHelper.createChannels(applicationContext)
                            NotificationHelper.sendGroupChallengeExpired(
                                context = applicationContext,
                                appName = wg.appDisplayName
                            )
                            Timber.d("DailyEvaluationWorker: group %s expired and cancelled", wg.groupId)
                        }
                        .onFailure { e ->
                            Timber.e(e, "DailyEvaluationWorker: expireGroupChallenge failed for %s", wg.groupId)
                        }
                } else if ((expiresAt - now) <= DateUtils.MILLIS_PER_DAY && userId == wg.creatorUserId) {
                    // Day 4 warning — notify creator only
                    Timber.d("DailyEvaluationWorker: group %s expires in ≤1 day — sending warning to creator", wg.groupId)
                    NotificationHelper.createChannels(applicationContext)
                    NotificationHelper.sendGroupChallengeStartWarning(
                        context = applicationContext,
                        appName = wg.appDisplayName
                    )
                }
            }

            // ── Post daily summary notification ────────────────────────────────
            val onTrackCount = challenges.count { challenge ->
                dailyLogRepository.getLogForDate(challenge.id, today)
                    .getOrNull()?.limitExceeded == false
            }
            NotificationHelper.createChannels(applicationContext)
            NotificationHelper.sendDailyReport(
                context = applicationContext,
                onTrackCount = onTrackCount,
                totalCount = challenges.size
            )

            Timber.d(
                "DailyEvaluationWorker: ■ completed successfully — " +
                        "processed ${challenges.size} challenge(s)"
            )
            // Fallback: server-side check for users who lost permissions and then uninstalled
            cloudFunctionsService.checkPermissionViolations().onFailure { e ->
                Timber.w(e, "DailyEvaluationWorker: checkPermissionViolations failed (non-fatal)")
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "DailyEvaluationWorker failed")
            FirebaseCrashlytics.getInstance().recordException(e)
            Result.retry()
        }
    }

    private fun computeLimitExceeded(
        limitType: LimitType,
        limitValueMinutes: Int,
        limitValueSessions: Int?,
        adjustedMinutes: Int,
        opens: Int
    ): Boolean = when (limitType) {
        LimitType.TIME -> adjustedMinutes >= limitValueMinutes
        LimitType.TIME_BUDGET -> adjustedMinutes >= limitValueMinutes
        LimitType.SESSIONS -> {
            val maxSessions = limitValueSessions
            if (maxSessions == null) {
                Timber.w("limitValueSessions is null for SESSIONS limit — marking exceeded")
                true
            } else {
                opens >= maxSessions
            }
        }
        LimitType.TIME_WINDOW -> false
    }

    /**
     * Handles daily evaluation for a group challenge shadow row (a [Challenge] whose
     * [Challenge.groupChallengeId] is non-null).
     *
     * Group Challenges NEVER auto-fail. Limit exceeded = write DailyLog (stats only).
     * Participant status only changes via manual "Aufgeben" in Detail screen.
     *  - Limit exceeded today → write DailyLog with limitExceeded=true, moneyLostCents=0 (stats only).
     *  - End date reached    → call [CloudFunctionsService.completeGroupChallenge] (CF runs
     *    payout logic: refunds all active participants, distributes the pot from failed ones).
     */
    private suspend fun evaluateGroupChallenge(
        challenge: Challenge,
        groupId: String,
        today: Long,
        now: Long
    ) {
        val userId = firebaseAuthService.currentUserId()
        if (userId == null) {
            Timber.w("DailyEvaluationWorker: group %s — user not signed in, skipping", groupId)
            return
        }

        Timber.d(
            "DailyEvaluationWorker: group shadow evaluation — challengeId=%s groupId=%s",
            challenge.id, groupId
        )

        // If the limit was already logged today (limitExceeded=true in DailyLog = stats marker,
        // NOT a fail marker), skip re-evaluation. completeGroupChallenge is still called below
        // when endDate is reached.
        val existingLog = dailyLogRepository.getLogForDate(challenge.id, today)
        val alreadyLogged = existingLog.getOrNull()?.limitExceeded == true

        if (!alreadyLogged) {
            // Compute today's usage for all packages in this challenge
            val todayUsage = challenge.appPackageNames.fold(
                com.detox.app.domain.model.AppDailyUsage(0, 0)
            ) { acc, pkg ->
                val usage = usageStatsRepository.getTodayUsageForApp(pkg)
                com.detox.app.domain.model.AppDailyUsage(
                    minutes = acc.minutes + usage.minutes,
                    opens   = acc.opens   + usage.opens
                )
            }

            val overlayPausedMs       = existingLog.getOrNull()?.overlayPausedMs ?: 0L
            val overlayPausedMinutes  = (overlayPausedMs / 60_000L).toInt()
            val adjustedMinutes       = maxOf(0, todayUsage.minutes - overlayPausedMinutes)

            Timber.d(
                "DailyEvaluationWorker: group %s usage — %dmin (adjusted), %d opens",
                groupId, adjustedMinutes, todayUsage.opens
            )

            val limitExceeded = computeLimitExceeded(
                limitType          = challenge.limitType,
                limitValueMinutes  = challenge.limitValueMinutes,
                limitValueSessions = challenge.limitValueSessions,
                adjustedMinutes    = adjustedMinutes,
                opens              = todayUsage.opens
            )

            if (limitExceeded) {
                // Group Challenge NEVER auto-fails. Record limit_exceeded in DailyLog for
                // statistics only. Participant stays active. Stripe only captured on manual
                // "Aufgeben" in Detail screen.
                Timber.d(
                    "DailyEvaluationWorker: group %s limit reached today — " +
                            "recording in DailyLog only (no auto-fail, participant stays active)", groupId
                )
                dailyLogRepository.insertDailyLog(
                    DailyLog(
                        id              = UUID.randomUUID().toString(),
                        challengeId     = challenge.id,
                        date            = today,
                        totalMinutes    = adjustedMinutes,
                        openCount       = todayUsage.opens,
                        overlayPausedMs = overlayPausedMs,
                        pointsEarned    = 0,
                        limitExceeded   = true,
                        moneyLostCents  = 0
                    )
                )
                Timber.d("DailyEvaluationWorker: group limit_exceeded DailyLog written for %s (stats only, no fail, no Stripe)", challenge.id)
            }
        } else {
            Timber.d("DailyEvaluationWorker: group %s limit already logged today — skipping usage check", groupId)
        }

        // End-of-challenge: call completeGroupChallenge CF so it can run payout logic.
        // The CF guards itself with endDate, but we also check locally to avoid spurious calls.
        val endDate = challenge.endDate
        val expired = endDate > 0L && now >= endDate
        Timber.d("Group challenge check: endDate=%d now=%d expired=%b", endDate, now, expired)
        if (expired) {
            Timber.d(
                "DailyEvaluationWorker: group %s endDate reached — calling completeGroupChallenge",
                groupId
            )
            cloudFunctionsService.completeGroupChallenge(groupId)
                .onSuccess {
                    Timber.d("DailyEvaluationWorker: completeGroupChallenge succeeded for %s", groupId)

                    // Fetch updated group doc to get participant outcome + prize breakdown
                    val updatedGroup = groupChallengeRepository.fetchAndCacheById(groupId).getOrNull()

                    // Update local Room status based on participant's Firestore outcome
                    val myParticipant = updatedGroup?.participants?.find { it.userId == userId }
                    val finalStatus = when (myParticipant?.status) {
                        ParticipantStatus.FAILED -> ChallengeStatus.FAILED
                        else -> ChallengeStatus.COMPLETED
                    }
                    challengeRepository.updateChallengeStatus(challenge.id, finalStatus)
                    Timber.d(
                        "DailyEvaluationWorker: group %s Room status → %s (participant=%s)",
                        groupId, finalStatus, myParticipant?.status
                    )

                    val succeeded = finalStatus == ChallengeStatus.COMPLETED
                    NotificationHelper.createChannels(applicationContext)
                    if (succeeded) {
                        val prizePerWinner = updatedGroup?.perWinnerBonus ?: 0
                        val payoutIban = runCatching {
                            firestore.collection("users").document(userId).get().await()
                                .getString("payoutIban")
                        }.getOrNull()
                        val hasPendingIban = payoutIban.isNullOrBlank()
                        NotificationHelper.sendGroupChallengePayoutReceived(
                            context          = applicationContext,
                            stakeRefundCents = challenge.amountCents ?: 0,
                            prizeShareCents  = prizePerWinner,
                            hasPendingIban   = hasPendingIban
                        )
                    } else {
                        NotificationHelper.sendGroupChallengeCompleted(
                            context     = applicationContext,
                            appName     = challenge.appDisplayName,
                            succeeded   = false,
                            refundCents = 0
                        )
                    }
                }
                .onFailure { e ->
                    Timber.e(e, "DailyEvaluationWorker: completeGroupChallenge failed for %s", groupId)
                }
        }
    }

    private fun getStartOfDay(): Long = DateUtils.todayKey()

    private suspend fun setRedemptionInfo(challenge: Challenge, now: Long) {
        if (challenge.isRedemption) return  // No redemption of redemption

        val originalDays = ((challenge.endDate - challenge.startDate) / DateUtils.MILLIS_PER_DAY).toInt()
        val isEligible = originalDays <= 28 && challenge.stripePaymentIntentId != null

        if (!isEligible) {
            Timber.d("Redemption not eligible for ${challenge.id}: originalDays=$originalDays")
            return
        }

        val deadline = now + (3L * DateUtils.MILLIS_PER_DAY)
        val showAfter = now + DateUtils.MILLIS_PER_DAY
        val refundAmount = floor((challenge.amountCents ?: 0) * 0.60).toInt()
        val redemptionDays = originalDays * 2
        val redemptionLimit = when (challenge.limitType) {
            LimitType.SESSIONS -> floor((challenge.limitValueSessions ?: 1) / 2.0).toInt().coerceAtLeast(1)
            LimitType.TIME -> floor(challenge.limitValueMinutes / 2.0).toInt().coerceAtLeast(5)
            LimitType.TIME_BUDGET -> floor((challenge.dailyBudgetMinutes ?: 5) / 2.0).toInt().coerceAtLeast(5)
            LimitType.TIME_WINDOW -> challenge.limitValueMinutes
        }

        Timber.d("Setting redemption info for ${challenge.id}: eligible=$isEligible days=$redemptionDays limit=$redemptionLimit refund=$refundAmount")

        challengeDao.updateRedemptionInfo(
            id = challenge.id,
            eligible = 1,
            deadline = deadline,
            showAfter = showAfter,
            refundAmount = refundAmount,
            redemptionDays = redemptionDays,
            redemptionLimit = redemptionLimit
        )

        val userId = firebaseAuthService.currentUserId()
        if (userId != null) {
            firestoreService.updateChallengeRedemptionInfo(
                userId = userId,
                challengeId = challenge.id,
                eligible = true,
                deadline = deadline,
                showAfter = showAfter,
                refundAmount = refundAmount,
                redemptionDays = redemptionDays,
                redemptionLimit = redemptionLimit
            )
        }

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                "redemption_${challenge.id}",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RedemptionNotificationWorker>()
                    .setInitialDelay(24, TimeUnit.HOURS)
                    .setInputData(workDataOf(
                        RedemptionNotificationWorker.KEY_CHALLENGE_ID to challenge.id,
                        RedemptionNotificationWorker.KEY_APP_NAME to challenge.appDisplayName,
                        RedemptionNotificationWorker.KEY_REFUND_CENTS to refundAmount,
                        RedemptionNotificationWorker.KEY_ORIGINAL_AMOUNT to (challenge.amountCents ?: 0)
                    ))
                    .build()
            )

        Timber.d("RedemptionNotificationWorker scheduled 24h from now for ${challenge.id}")
    }
}
