package com.detox.app.presentation.screens.activechallenge

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.AnalyticsService
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.usecase.DailyLimitStatus
import com.detox.app.domain.usecase.GetChallengeStreakUseCase
import com.detox.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface ActiveChallengeUiState {
    data object Loading : ActiveChallengeUiState
    data class Success(
        val challenge: Challenge,
        val status: DailyLimitStatus?,
        val streak: Int = 0,
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
) : ViewModel() {

    private val challengeId: String = savedStateHandle.get<String>("challengeId") ?: ""

    private val _uiState = MutableStateFlow<ActiveChallengeUiState>(ActiveChallengeUiState.Loading)
    val uiState: StateFlow<ActiveChallengeUiState> = _uiState.asStateFlow()

    private val _abandonState = MutableStateFlow(false)
    val abandonSuccess: StateFlow<Boolean> = _abandonState.asStateFlow()

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
                            _uiState.value = ActiveChallengeUiState.Error("Challenge not found")
                            return@launch
                        }

                        val streak = getChallengeStreakUseCase(challenge)

                        // ALWAYS use DateUtils.todayKey() — never inline 86400000 calculation.
                        val todayKey = DateUtils.todayKey()
                        Timber.d("DetailScreen: challengeId=$challengeId limitType=${challenge.limitType}")

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
                                _uiState.value = ActiveChallengeUiState.Success(challenge, status, streak)
                            }
                    },
                    onFailure = { e ->
                        Timber.e(e, "Failed to load challenge $challengeId")
                        _uiState.value = ActiveChallengeUiState.Error(
                            e.message ?: "Failed to load challenge"
                        )
                    }
                )
        }
    }

    fun refresh() = loadChallenge()

    fun abandonChallenge() {
        viewModelScope.launch {
            val mode = (uiState.value as? ActiveChallengeUiState.Success)
                ?.challenge?.mode?.name?.lowercase() ?: "unknown"
            challengeRepository.updateChallengeStatus(challengeId, ChallengeStatus.FAILED)
                .onSuccess {
                    Timber.d("Challenge $challengeId abandoned")
                    analyticsService.logChallengeAbandoned(mode)
                    _abandonState.value = true
                }
                .onFailure { e ->
                    Timber.e(e, "Failed to abandon challenge $challengeId")
                }
        }
    }
}
