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
import java.util.Calendar
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

    private val challengeId: String = savedStateHandle.get<String>("challengeId") ?: ""

    private val _uiState = MutableStateFlow<ActiveChallengeUiState>(ActiveChallengeUiState.Loading)
    val uiState: StateFlow<ActiveChallengeUiState> = _uiState.asStateFlow()

    private val _abandonState = MutableStateFlow(false)
    val abandonSuccess: StateFlow<Boolean> = _abandonState.asStateFlow()

    init {
        loadChallenge()
    }

    private fun loadChallenge() {
        viewModelScope.launch {
            challengeRepository.getChallengeById(challengeId)
                .fold(
                    onSuccess = { challenge ->
                        if (challenge == null) {
                            _uiState.value = ActiveChallengeUiState.Error("Challenge not found")
                            return@launch
                        }
                        val today = todayMidnightMs()
                        val todayLog = dailyLogRepository.getLogForDate(challengeId, today).getOrNull()
                        Timber.d("DailyLog for $challengeId date=$today: $todayLog")
                        val opensToday = todayLog?.consciousOpens ?: 0
                        val timeToday = todayLog?.totalMinutes ?: 0
                        val budgetUsed = todayLog?.budgetUsedMinutes ?: 0

                        val progress = when (challenge.limitType) {
                            LimitType.SESSIONS -> {
                                val max = challenge.limitValueSessions ?: 1
                                if (max > 0) opensToday.toFloat() / max else 0f
                            }
                            LimitType.TIME_BUDGET -> {
                                val budget = challenge.dailyBudgetMinutes ?: 1
                                if (budget > 0) budgetUsed.toFloat() / budget else 0f
                            }
                            else -> {
                                if (challenge.limitValueMinutes > 0) timeToday.toFloat() / challenge.limitValueMinutes else 0f
                            }
                        }
                        Timber.d("Dashboard card: opens=$opensToday time=$timeToday progress=$progress")

                        val status = DailyLimitStatus(
                            challenge = challenge,
                            todayMinutes = if (challenge.limitType == LimitType.TIME_BUDGET) budgetUsed else timeToday,
                            todayOpens = opensToday,
                            limitExceeded = todayLog?.limitExceeded ?: false,
                            remainingMinutes = when (challenge.limitType) {
                                LimitType.TIME -> maxOf(0, challenge.limitValueMinutes - timeToday)
                                LimitType.SESSIONS -> maxOf(0, (challenge.limitValueSessions ?: 0) * challenge.limitValueMinutes - timeToday)
                                LimitType.TIME_BUDGET -> todayLog?.budgetRemainingMinutes ?: (challenge.dailyBudgetMinutes ?: 0)
                                LimitType.TIME_WINDOW -> 0
                            },
                            remainingOpens = if (challenge.limitType == LimitType.SESSIONS)
                                maxOf(0, (challenge.limitValueSessions ?: 0) - opensToday) else null
                        )
                        val streak = dailyLogRepository
                            .getStreakForChallenge(challengeId, today)
                            .getOrElse { 0 }
                        _uiState.value = ActiveChallengeUiState.Success(challenge, status, streak)
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

    private fun todayMidnightMs(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

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
