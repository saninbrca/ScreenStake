package com.detox.app.presentation.screens.challenges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.repository.ChallengeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ChallengesUiData(
    val active: List<Challenge>,
    val history: List<Challenge>
)

sealed interface ChallengesUiState {
    data object Loading : ChallengesUiState
    data class Success(val data: ChallengesUiData) : ChallengesUiState
}

@HiltViewModel
class ChallengesViewModel @Inject constructor(
    challengeRepository: ChallengeRepository
) : ViewModel() {

    val uiState: StateFlow<ChallengesUiState> = challengeRepository.getAllChallenges()
        .map { challenges ->
            ChallengesUiState.Success(
                ChallengesUiData(
                    active = challenges.filter { it.status == ChallengeStatus.ACTIVE },
                    history = challenges.filter { it.status != ChallengeStatus.ACTIVE }
                )
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChallengesUiState.Loading)
}
