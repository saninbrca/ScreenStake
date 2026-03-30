package com.detox.app.presentation.screens.challengesetup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.usecase.CreateChallengeUseCase
import com.detox.app.service.UsageTrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChallengeSetupFormState(
    val packageName: String = "",
    val displayName: String = "",
    val limitType: LimitType = LimitType.TIME,
    val limitMinutes: Int = 60,
    val limitSessions: Int = 5,
    val sessionMinutes: Int = 5,
    val durationDays: Int = 7,
    val motivationText: String = ""
)

sealed interface ChallengeSetupUiState {
    data object Idle : ChallengeSetupUiState
    data object Loading : ChallengeSetupUiState
    data class Success(val challengeId: String) : ChallengeSetupUiState
    data class Error(val message: String) : ChallengeSetupUiState
}

@HiltViewModel
class ChallengeSetupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val createChallengeUseCase: CreateChallengeUseCase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _formState = MutableStateFlow(
        ChallengeSetupFormState(
            packageName = savedStateHandle.get<String>("packageName") ?: "",
            displayName = savedStateHandle.get<String>("displayName") ?: ""
        )
    )
    val formState: StateFlow<ChallengeSetupFormState> = _formState.asStateFlow()

    private val _uiState = MutableStateFlow<ChallengeSetupUiState>(ChallengeSetupUiState.Idle)
    val uiState: StateFlow<ChallengeSetupUiState> = _uiState.asStateFlow()

    fun updateLimitType(limitType: LimitType) {
        _formState.update { it.copy(limitType = limitType) }
    }

    fun updateLimitMinutes(minutes: Int) {
        _formState.update { it.copy(limitMinutes = minutes) }
    }

    fun updateLimitSessions(sessions: Int) {
        _formState.update { it.copy(limitSessions = sessions) }
    }

    fun updateSessionMinutes(minutes: Int) {
        _formState.update { it.copy(sessionMinutes = minutes) }
    }

    fun updateDurationDays(days: Int) {
        _formState.update { it.copy(durationDays = days) }
    }

    fun updateMotivationText(text: String) {
        _formState.update { it.copy(motivationText = text) }
    }

    fun createChallenge() {
        val form = _formState.value
        _uiState.value = ChallengeSetupUiState.Loading

        viewModelScope.launch {
            val limitMinutes = when (form.limitType) {
                LimitType.TIME -> form.limitMinutes
                LimitType.SESSIONS -> form.sessionMinutes
            }
            val limitSessions = when (form.limitType) {
                LimitType.TIME -> null
                LimitType.SESSIONS -> form.limitSessions
            }

            createChallengeUseCase(
                appPackageName = form.packageName,
                appDisplayName = form.displayName,
                limitType = form.limitType,
                limitValueMinutes = limitMinutes,
                limitValueSessions = limitSessions,
                durationDays = form.durationDays,
                customMotivation = form.motivationText.ifBlank { null }
            ).fold(
                onSuccess = { challengeId ->
                    UsageTrackingService.start(context)
                    _uiState.value = ChallengeSetupUiState.Success(challengeId)
                },
                onFailure = { error ->
                    _uiState.value = ChallengeSetupUiState.Error(
                        error.message ?: "Failed to create challenge"
                    )
                }
            )
        }
    }
}
