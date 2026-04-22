package com.detox.app.presentation.screens.activechallenge

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.AnalyticsService
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.usecase.CheckDailyLimitUseCase
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
    private val checkDailyLimitUseCase: CheckDailyLimitUseCase,
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
                        // Also load today's limit status for live progress display
                        val status = checkDailyLimitUseCase(challenge.appPackageName ?: "")
                            .getOrNull()
                        val streak = dailyLogRepository
                            .getStreakForChallenge(challengeId, todayMidnightMs())
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
