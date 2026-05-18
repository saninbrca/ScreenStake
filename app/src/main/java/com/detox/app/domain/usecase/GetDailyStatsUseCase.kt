package com.detox.app.domain.usecase

import android.content.Context
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.DailyStats
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.ParticipantStatus
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.GroupChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.service.OverlayManager
import com.detox.app.util.DateUtils
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

class GetDailyStatsUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
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

            val today = DateUtils.todayKey()

            val currentUid = firebaseAuth.currentUser?.uid
            val stats = challenges.mapNotNull { challenge ->
                Timber.d("Challenge endDate raw: id=${challenge.id} startDate=${challenge.startDate} endDate=${challenge.endDate}")
                Timber.d("endDate type: ${if (challenge.endDate > 1700000000000L) "timestamp" else "days"} value=${challenge.endDate}")
                val soloEndDateMs = when {
                    challenge.endDate <= 0L -> 0L
                    challenge.endDate > 1700000000000L -> challenge.endDate // already a Unix timestamp (ms)
                    else -> challenge.startDate + (challenge.endDate * DateUtils.MILLIS_PER_DAY)
                }
                // Group Challenge DailyLogs are stored with challengeId = "group_${groupId}",
                // not the local ChallengeEntity UUID — use the correct key.
                val dailyLogChallengeId = if (challenge.groupChallengeId != null) {
                    "group_${challenge.groupChallengeId}"
                } else {
                    challenge.id
                }
                val todayLog = dailyLogRepository.getLogForDate(dailyLogChallengeId, today).getOrNull()
                Timber.d("DailyLog for $dailyLogChallengeId date=$today: $todayLog")

                // Resolve group metadata once for this challenge (null if solo).
                val groupChallenge = challenge.groupChallengeId?.let {
                    groupChallengeRepository.getGroupChallengeById(it)
                }

                val effectiveEndDateMs = if (groupChallenge != null && groupChallenge.endDate > 0L)
                    groupChallenge.endDate
                else soloEndDateMs
                val daysRemaining = when {
                    effectiveEndDateMs <= 0L -> Int.MAX_VALUE
                    effectiveEndDateMs > now -> ((effectiveEndDateMs - now) / DateUtils.MILLIS_PER_DAY).toInt().coerceAtLeast(0)
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

                // TIME_BUDGET: prefer live prefs during an active session; fall back to Room (written every 10s).
                if (challenge.limitType == LimitType.TIME_BUDGET) {
                    val totalBudgetMs = (challenge.dailyBudgetMinutes ?: 0) * 60_000L

                    val budgetPrefs = context.getSharedPreferences(
                        OverlayManager.BUDGET_SESSION_PREFS_NAME, Context.MODE_PRIVATE
                    )
                    val sessionEndTime = budgetPrefs.getLong(OverlayManager.BUDGET_SESSION_END_TIME_KEY, 0L)
                    val sessionChallengeId = budgetPrefs.getString(OverlayManager.BUDGET_SESSION_CHALLENGE_KEY, null)

                    val displayUsedMs: Long = if (sessionEndTime > 0L && sessionChallengeId == challenge.id) {
                        // Active session: committedMs + live elapsed (sub-10s precision for dashboard)
                        val committedMsKey = if (challenge.groupChallengeId != null) {
                            "${OverlayManager.BUDGET_COMMITTED_MS_KEY}_group_${challenge.groupChallengeId}"
                        } else {
                            "${OverlayManager.BUDGET_COMMITTED_MS_KEY}_${challenge.id}"
                        }
                        val committedMs = budgetPrefs.getLong(committedMsKey, 0L)
                        val sessionStartTime = budgetPrefs.getLong(OverlayManager.BUDGET_SESSION_START_TIME_KEY, 0L)
                        val liveElapsedMs = (now - sessionStartTime).coerceAtLeast(0L)
                        committedMs + liveElapsedMs
                    } else {
                        // No active session: read from Room (written every 10s by UsageTrackingService)
                        todayLog?.budgetUsedMs ?: 0L
                    }

                    val displayRemainingMs = (totalBudgetMs - displayUsedMs).coerceAtLeast(0L)
                    if (challenge.groupChallengeId != null) {
                        Timber.d("Group budget from Room: key=$dailyLogChallengeId usedMs=$displayUsedMs remainingMs=$displayRemainingMs")
                    }
                    val budgetUsed = (displayUsedMs / 60_000L).toInt()
                    val budgetRemaining = (displayRemainingMs / 60_000L).toInt()
                    val totalBudget = (totalBudgetMs / 60_000L).toInt()
                    val moneyLostCents = todayLog?.moneyLostCents ?: 0
                    val limitExceeded = todayLog?.limitExceeded ?: (displayRemainingMs <= 0L)

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
                        partialBlockDomains = challenge.partialBlockDomains,
                        blockAdultContent = challenge.blockAdultContent,
                        mode = challenge.mode,
                        isGroup = isGroup,
                        participantCount = participantCount,
                        maxParticipants = maxParticipants,
                        userRank = userRank,
                        appPackageNames = challenge.appPackageNames,
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
                    partialBlockDomains = challenge.partialBlockDomains,
                    blockAdultContent = challenge.blockAdultContent,
                    mode = challenge.mode,
                    isGroup = isGroup,
                    participantCount = participantCount,
                    maxParticipants = maxParticipants,
                    userRank = userRank,
                    appPackageNames = challenge.appPackageNames,
                )
            }

            Timber.d("Dashboard group challenges filtered: ${stats.size} active for user $currentUid")
            Result.success(stats)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
