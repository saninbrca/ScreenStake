package com.detox.app.domain.usecase

import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.DailyStats
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.ParticipantStatus
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.GroupChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.google.firebase.auth.FirebaseAuth
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject

class GetDailyStatsUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val usageStatsRepository: UsageStatsRepository,
    private val dailyLogRepository: DailyLogRepository,
    private val groupChallengeRepository: GroupChallengeRepository,
    private val firebaseAuth: FirebaseAuth,
) {
    suspend operator fun invoke(): Result<List<DailyStats>> {
        return try {
            val challenges = challengeRepository.getActiveChallengesList().getOrThrow()
            val now = System.currentTimeMillis()

            // Start of today — used to look up today's DailyLog
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val currentUid = firebaseAuth.currentUser?.uid
            val stats = challenges.mapNotNull { challenge ->
                val soloEndDateMs = if (challenge.endDate > 0L)
                    challenge.startDate + (challenge.endDate * 86_400_000L) else 0L
                val todayLog = dailyLogRepository.getLogForDate(challenge.id, today).getOrNull()
                Timber.d("DailyLog for ${challenge.id} date=$today: $todayLog")

                // Resolve group metadata once for this challenge (null if solo).
                val groupChallenge = challenge.groupChallengeId?.let {
                    groupChallengeRepository.getGroupChallengeById(it)
                }

                val effectiveEndDateMs = if (groupChallenge != null && groupChallenge.endDate > 0L)
                    groupChallenge.endDate
                else soloEndDateMs
                val daysRemaining = when {
                    effectiveEndDateMs <= 0L -> Int.MAX_VALUE
                    effectiveEndDateMs > now -> ((effectiveEndDateMs - now) / 86_400_000L).toInt().coerceAtLeast(0)
                    else -> 0
                }
                Timber.d("Challenge ${challenge.id} endDate=$effectiveEndDateMs remaining=$daysRemaining days")

                // Skip group challenges where the current user already failed
                if (groupChallenge != null && currentUid != null) {
                    val myParticipant = groupChallenge.participants.find { it.userId == currentUid }
                    if (myParticipant?.status == ParticipantStatus.FAILED) {
                        return@mapNotNull null
                    }
                }

                val isGroup = groupChallenge != null
                val participantCount = groupChallenge?.participants?.size ?: 0
                val maxParticipants = groupChallenge?.maxParticipants ?: 0
                val userRank: Int? = if (isGroup && groupChallenge != null && currentUid != null) {
                    val sorted = groupChallenge.participants.sortedBy { p ->
                        if (challenge.limitType == LimitType.SESSIONS) p.opensToday
                        else p.timeUsedMinutes
                    }
                    sorted.indexOfFirst { it.userId == currentUid }.takeIf { it >= 0 }?.plus(1)
                } else null

                // TIME_BUDGET: use budget columns from Room, not raw UsageStats.
                if (challenge.limitType == LimitType.TIME_BUDGET) {
                    val totalBudget = challenge.dailyBudgetMinutes ?: 0
                    val hasRealBudgetActivity = todayLog != null &&
                            (todayLog.budgetUsedMinutes > 0 || todayLog.budgetRemainingMinutes > 0)
                    val budgetUsed = todayLog?.budgetUsedMinutes ?: 0
                    val budgetRemaining = if (hasRealBudgetActivity) {
                        todayLog!!.budgetRemainingMinutes
                    } else {
                        totalBudget  // no deductions yet → full budget available
                    }
                    val moneyLostCents = todayLog?.moneyLostCents ?: 0
                    val limitExceeded = todayLog?.limitExceeded ?: (budgetUsed >= totalBudget)

                    return@mapNotNull DailyStats(
                        challengeId = challenge.id,
                        appDisplayName = challenge.appDisplayName,
                        appPackageName = challenge.appPackageName,
                        limitType = LimitType.TIME_BUDGET,
                        limitValueMinutes = totalBudget,
                        limitValueSessions = null,
                        todayMinutes = budgetUsed,
                        todayOpens = 0,
                        limitExceeded = limitExceeded,
                        customMotivation = challenge.customMotivation,
                        daysRemaining = daysRemaining,
                        moneyLostCents = moneyLostCents,
                        dailyBudgetMinutes = totalBudget,
                        budgetRemainingMinutes = budgetRemaining,
                        blockedDomains = challenge.blockedDomains,
                        blockAdultContent = challenge.blockAdultContent,
                        mode = challenge.mode,
                        isGroup = isGroup,
                        participantCount = participantCount,
                        maxParticipants = maxParticipants,
                        userRank = userRank,
                    )
                }

                // TIME / SESSIONS: existing UsageStats-based flow.
                val todayUsage = usageStatsRepository.getTodayUsageForApp(challenge.appPackageName ?: "")

                // Subtract overlay-visible time so it doesn't count against the user's limit.
                val overlayPausedMs = dailyLogRepository
                    .getOverlayPausedMs(challenge.id, today)
                    .getOrElse { 0L }
                val overlayPausedMinutes = (overlayPausedMs / 60_000L).toInt()
                val adjustedMinutes = maxOf(0, todayUsage.minutes - overlayPausedMinutes)

                // For session-limit challenges, use conscious opens from:
                //   - Group challenges: Firestore-synced participant.opensToday (ground truth)
                //   - Solo challenges: Room-persisted consciousOpens from DailyLog
                val todayOpens: Int = when {
                    challenge.limitType == LimitType.SESSIONS && isGroup && currentUid != null ->
                        groupChallenge?.participants?.find { it.userId == currentUid }?.opensToday ?: 0
                    challenge.limitType == LimitType.SESSIONS ->
                        dailyLogRepository.getConsciousOpens(challenge.id, today).getOrElse { 0 }
                    else -> todayUsage.opens
                }

                val limitExceeded = when (challenge.limitType) {
                    LimitType.TIME -> adjustedMinutes >= challenge.limitValueMinutes
                    LimitType.SESSIONS -> {
                        val max = challenge.limitValueSessions
                        if (max != null) todayOpens >= max else false
                    }
                    LimitType.TIME_WINDOW -> false
                    else -> false
                }

                val moneyLostCents = todayLog?.moneyLostCents ?: 0

                val progressLog = when (challenge.limitType) {
                    LimitType.SESSIONS -> {
                        val max = challenge.limitValueSessions ?: 1
                        if (max > 0) todayOpens.toFloat() / max else 0f
                    }
                    else -> if (challenge.limitValueMinutes > 0) adjustedMinutes.toFloat() / challenge.limitValueMinutes else 0f
                }
                Timber.d("Dashboard: challengeId=${challenge.id} opensToday=$todayOpens timeToday=$adjustedMinutes progress=$progressLog")

                DailyStats(
                    challengeId = challenge.id,
                    appDisplayName = challenge.appDisplayName,
                    appPackageName = challenge.appPackageName,
                    limitType = challenge.limitType,
                    limitValueMinutes = challenge.limitValueMinutes,
                    limitValueSessions = challenge.limitValueSessions,
                    todayMinutes = adjustedMinutes,
                    todayOpens = todayOpens,
                    limitExceeded = limitExceeded,
                    customMotivation = challenge.customMotivation,
                    daysRemaining = daysRemaining,
                    moneyLostCents = moneyLostCents,
                    blockedDomains = challenge.blockedDomains,
                    blockAdultContent = challenge.blockAdultContent,
                    mode = challenge.mode,
                    isGroup = isGroup,
                    participantCount = participantCount,
                    maxParticipants = maxParticipants,
                    userRank = userRank,
                )
            }

            Timber.d("Dashboard group challenges filtered: ${stats.size} active for user $currentUid")
            Result.success(stats)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
