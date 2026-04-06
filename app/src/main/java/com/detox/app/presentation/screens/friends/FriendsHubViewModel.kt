package com.detox.app.presentation.screens.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.repository.GroupChallengeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class FriendsHubUiData(
    val active: List<GroupChallenge>,
    val waiting: List<GroupChallenge>,
    val history: List<GroupChallenge>
)

sealed interface FriendsHubUiState {
    data object Loading : FriendsHubUiState
    data class Success(val data: FriendsHubUiData) : FriendsHubUiState
}

@HiltViewModel
class FriendsHubViewModel @Inject constructor(
    groupChallengeRepository: GroupChallengeRepository
) : ViewModel() {

    val uiState: StateFlow<FriendsHubUiState> =
        groupChallengeRepository.getGroupChallenges()
            .map { challenges ->
                FriendsHubUiState.Success(
                    FriendsHubUiData(
                        active = challenges.filter { it.status == GroupChallengeStatus.ACTIVE },
                        waiting = challenges.filter { it.status == GroupChallengeStatus.WAITING },
                        history = challenges.filter {
                            it.status == GroupChallengeStatus.COMPLETED ||
                                it.status == GroupChallengeStatus.CANCELLED
                        }
                    )
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FriendsHubUiState.Loading
            )
}
