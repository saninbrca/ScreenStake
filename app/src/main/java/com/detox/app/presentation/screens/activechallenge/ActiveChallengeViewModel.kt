package com.detox.app.presentation.screens.activechallenge

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.AnalyticsService
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.PaymentRepository
import com.detox.app.domain.usecase.DailyLimitStatus
import com.detox.app.domain.usecase.GetChallengeStreakUseCase
import com.detox.app.util.DateUtils
import com.detox.app.R
import com.detox.app.util.ErrorMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface ReduceLimitState {
    data object Idle : ReduceLimitState
    data object Loading : ReduceLimitState
    data object Success : ReduceLimitState
    data class Error(val message: String) : ReduceLimitState
}

/**
 * Progress of an abandon that must first capture the Stripe stake (solo Hard Mode). [Idle] for the
 * no-capture cases (Soft Mode / group / no pre-auth). [Loading] while the capture CF is in flight;
 * [Error] when capture (or the post-capture status write) fails — the challenge stays ACTIVE.
 */
sealed interface AbandonState {
    data object Idle : AbandonState
    data object Loading : AbandonState
    data class Error(val message: String) : AbandonState
}

sealed interface ActiveChallengeUiState {
    data object Loading : ActiveChallengeUiState
    data class Success(
        val challenge: Challenge,
        val status: DailyLimitStatus?,
        val streak: Int = 0,
        val bestStreak: Int = 0,
        val successRatePct: Int = 0,
    ) : ActiveChallengeUiState
    data class Error(val message: String) : ActiveChallengeUiState
}

@HiltViewModel
class ActiveChallengeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val challengeRepository: ChallengeRepository,
    private val analyticsService: AnalyticsService,
    private val dailyLogRepository: DailyLogRepository,
    private val getChallengeStreakUseCase: GetChallengeStreakUseCase,
    private val paymentRepository: PaymentRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val challengeId: String = savedStateHandle.get<String>("challengeId") ?: ""

    private val _uiState = MutableStateFlow<ActiveChallengeUiState>(ActiveChallengeUiState.Loading)
    val uiState: StateFlow<ActiveChallengeUiState> = _uiState.asStateFlow()

    private val _abandonState = MutableStateFlow(false)
    val abandonSuccess: StateFlow<Boolean> = _abandonState.asStateFlow()

    private val _abandonStatus = MutableStateFlow<AbandonState>(AbandonState.Idle)
    val abandonStatus: StateFlow<AbandonState> = _abandonStatus.asStateFlow()

    private val _reduceLimitState = MutableStateFlow<ReduceLimitState>(ReduceLimitState.Idle)
    val reduceLimitState: StateFlow<ReduceLimitState> = _reduceLimitState.asStateFlow()

    /** Tracks the active load+observe coroutine so refresh() can cancel and restart it cleanly. */
    private var loadJob: Job? = null

    init {
        loadChallenge()
    }

    private fun loadChallenge() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            challengeRepository.getChallengeById(challengeId)
                .fold(
                    onSuccess = { challenge ->
                        if (challenge == null) {
                            _uiState.value = ActiveChallengeUiState.Error(context.getString(R.string.error_challenge_not_found))
                            return@launch
                        }

                        val streak = getChallengeStreakUseCase(challenge)

                        // ALWAYS use DateUtils.todayKey() — never inline 86400000 calculation.
                        val todayKey = DateUtils.todayKey()
                        Timber.d("DetailScreen: challengeId=$challengeId limitType=${challenge.limitType}")

                        // Compute best streak + success rate from full log history (once per screen open).
                        val allLogs = dailyLogRepository.getLogsForChallenge(challengeId).first()
                        val bestStreak = computeBestStreak(allLogs)
                        val successRatePct = computeSuccessRate(allLogs)

                        // Observe Room DailyLog via Flow — auto-refreshes whenever UsageTrackingService
                        // writes (every 10 s) or on every conscious open. Never reads stale state.
                        dailyLogRepository.observeLogForDate(challengeId, todayKey)
                            .collect { dailyLog ->
                                Timber.d("DetailScreen: dailyLog from Room = $dailyLog")

                                // SOURCE OF TRUTH per field:
                                // SESSION_LIMIT  → consciousOpens (written atomically on each "Ja, öffnen" tap)
                                // TIME_LIMIT     → totalMinutes   (written by UsageTrackingService every 10 s)
                                // TIME_BUDGET    → budgetUsedMs   (ms source of truth, written every 10 s)
                                val opensToday     = dailyLog?.consciousOpens ?: 0
                                val totalMinutes   = dailyLog?.totalMinutes   ?: 0
                                val budgetUsedMs   = dailyLog?.budgetUsedMs   ?: 0L
                                val budgetRemainingMs = dailyLog?.budgetRemainingMs ?: 0L

                                // todayMinutes fed into DailyLimitStatus (Screen reads s.todayMinutes)
                                val todayMinutesForStatus = when (challenge.limitType) {
                                    LimitType.TIME_BUDGET -> (budgetUsedMs / 60_000L).toInt()
                                    else -> totalMinutes
                                }

                                val remainingMinutes = when (challenge.limitType) {
                                    LimitType.TIME -> maxOf(0, challenge.limitValueMinutes - totalMinutes)
                                    LimitType.SESSIONS -> maxOf(
                                        0,
                                        (challenge.limitValueSessions ?: 0) * challenge.limitValueMinutes - totalMinutes
                                    )
                                    LimitType.TIME_BUDGET -> (budgetRemainingMs / 60_000L).toInt()
                                    LimitType.TIME_WINDOW -> 0
                                }

                                val remainingOpens = if (challenge.limitType == LimitType.SESSIONS)
                                    maxOf(0, (challenge.limitValueSessions ?: 0) - opensToday) else null

                                val progress = when (challenge.limitType) {
                                    LimitType.SESSIONS -> {
                                        val max = challenge.limitValueSessions ?: 1
                                        if (max > 0) opensToday.toFloat() / max else 0f
                                    }
                                    LimitType.TIME_BUDGET -> {
                                        val budgetMs = (challenge.dailyBudgetMinutes ?: 1) * 60_000L
                                        if (budgetMs > 0) budgetUsedMs.toFloat() / budgetMs else 0f
                                    }
                                    else -> {
                                        if (challenge.limitValueMinutes > 0)
                                            totalMinutes.toFloat() / challenge.limitValueMinutes else 0f
                                    }
                                }.coerceIn(0f, 1f)

                                Timber.d(
                                    "DetailScreen: progress=$progress opensToday=$opensToday " +
                                    "totalMinutes=$totalMinutes budgetUsedMs=$budgetUsedMs"
                                )

                                val status = DailyLimitStatus(
                                    challenge = challenge,
                                    todayMinutes = todayMinutesForStatus,
                                    todayOpens = opensToday,
                                    limitExceeded = dailyLog?.limitExceeded ?: false,
                                    remainingMinutes = remainingMinutes,
                                    remainingOpens = remainingOpens
                                )
                                _uiState.value = ActiveChallengeUiState.Success(
                                    challenge, status, streak, bestStreak, successRatePct
                                )
                            }
                    },
                    onFailure = { e ->
                        Timber.e(e, "Failed to load challenge $challengeId")
                        _uiState.value = ActiveChallengeUiState.Error(
                            ErrorMessages.from(context, e)
                        )
                    }
                )
        }
    }

    fun refresh() = loadChallenge()

    private fun computeBestStreak(logs: List<DailyLog>): Int {
        if (logs.isEmpty()) return 0
        val sorted = logs.sortedBy { it.date }
        var best = 0
        var current = 0
        for (log in sorted) {
            if (!log.limitExceeded) { current++; if (current > best) best = current }
            else current = 0
        }
        return best
    }

    private fun computeSuccessRate(logs: List<DailyLog>): Int {
        if (logs.isEmpty()) return 0
        return logs.count { !it.limitExceeded } * 100 / logs.size
    }

    fun reducePendingLimit(newValue: Int) {
        viewModelScope.launch {
            _reduceLimitState.value = ReduceLimitState.Loading
            val nextMidnight = DateUtils.nextMidnightTimestamp()
            challengeRepository.updatePendingLimit(challengeId, newValue, nextMidnight)
                .onSuccess {
                    Timber.d("Pending limit set: challengeId=$challengeId newValue=$newValue appliesAt=$nextMidnight")
                    _reduceLimitState.value = ReduceLimitState.Success
                    loadChallenge()
                }
                .onFailure { e ->
                    Timber.e(e, "Failed to set pending limit for $challengeId")
                    _reduceLimitState.value = ReduceLimitState.Error(ErrorMessages.from(context, e))
                }
        }
    }

    fun resetReduceLimitState() {
        _reduceLimitState.value = ReduceLimitState.Idle
    }

    fun abandonChallenge() {
        viewModelScope.launch {
            val challenge = (uiState.value as? ActiveChallengeUiState.Success)?.challenge
            val mode = challenge?.mode?.name?.lowercase() ?: "unknown"

            // Abandoning is a LOSS. For a solo Hard Mode challenge with a live pre-auth the stake MUST
            // be captured before we mark FAILED — the confirm dialog already told the user "€X wird
            // eingezogen". Group challenges (groupChallengeId != null) are intentionally EXCLUDED: their
            // stake is settled by the completeGroupChallenge prize-pool flow, never direct-captured here.
            val paymentIntentId = challenge?.stripePaymentIntentId
            val needsCapture = challenge != null &&
                challenge.mode == ChallengeMode.HARD &&
                challenge.groupChallengeId == null &&
                paymentIntentId != null

            if (needsCapture) {
                _abandonStatus.value = AbandonState.Loading
                // Idempotent CF: ANY success means the money is gone (fresh capture OR already captured).
                paymentRepository.capturePayment(paymentIntentId!!)
                    .onSuccess {
                        // CRITICAL ORDER: FAILED + navigation happen ONLY after a confirmed capture.
                        markFailedAndFinish(mode)
                    }
                    .onFailure { e ->
                        // Real failure (network / Stripe / non-capturable PI). Leave the challenge ACTIVE
                        // and surface the error — NEVER mark FAILED without the stake being captured.
                        Timber.e(e, "Abandon: stake capture failed for $challengeId — staying ACTIVE")
                        _abandonStatus.value = AbandonState.Error(ErrorMessages.from(context, e, R.string.error_payment))
                    }
            } else {
                // Soft Mode, group challenge, or no pre-auth → no money to capture; behave as before.
                markFailedAndFinish(mode)
            }
        }
    }

    /**
     * Flips the challenge to FAILED and signals navigation back to the Dashboard, where the unified RED
     * loss dialog surfaces via getUnshownFailedHardChallenge(). For Hard Mode this runs ONLY after the
     * stake is confirmed captured. If the status write itself fails after a Hard Mode capture, the money
     * is already gone but the row stays ACTIVE — surfaced as an error; a retry/worker cycle reconciles
     * it (capturePayment is idempotent, so re-running abandon is safe).
     */
    private suspend fun markFailedAndFinish(mode: String) {
        challengeRepository.updateChallengeStatus(challengeId, ChallengeStatus.FAILED, "abandon")
            .onSuccess {
                Timber.d("Challenge $challengeId abandoned")
                analyticsService.logChallengeAbandoned(mode)
                _abandonStatus.value = AbandonState.Idle
                _abandonState.value = true
            }
            .onFailure { e ->
                Timber.e(e, "Failed to mark FAILED on abandon for $challengeId")
                _abandonStatus.value = AbandonState.Error(ErrorMessages.from(context, e))
            }
    }

    fun resetAbandonStatus() {
        _abandonStatus.value = AbandonState.Idle
    }
}
