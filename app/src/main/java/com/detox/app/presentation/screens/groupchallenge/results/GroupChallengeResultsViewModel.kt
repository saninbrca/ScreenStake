package com.detox.app.presentation.screens.groupchallenge.results

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.model.Participant
import com.detox.app.domain.model.ParticipantStatus
import com.detox.app.domain.repository.GroupChallengeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class GroupChallengeResultsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupChallengeRepository: GroupChallengeRepository,
    private val firebaseAuthService: FirebaseAuthService,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val groupId: String = checkNotNull(savedStateHandle["groupId"])

    data class ResultsUiState(
        val sortedWinners: List<Participant> = emptyList(),
        val failedParticipants: List<Participant> = emptyList(),
        val currentUserParticipant: Participant? = null,
        val isLoading: Boolean = true,
    )

    private val _uiState = MutableStateFlow(ResultsUiState())
    val uiState: StateFlow<ResultsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val userId = firebaseAuthService.currentUserId() ?: ""
            runCatching {
                groupChallengeRepository.observeGroupChallenge(groupId)
                    .first { it != null }
            }.onSuccess { challenge ->
                if (challenge == null) return@onSuccess
                val all = challenge.participants
                val winners = all
                    .filter { it.status != ParticipantStatus.FAILED }
                    .sortedWith(compareBy({ it.opensToday }, { it.timeUsedMinutes }))
                val failed = all.filter { it.status == ParticipantStatus.FAILED }
                _uiState.update {
                    it.copy(
                        sortedWinners = winners,
                        failedParticipants = failed,
                        currentUserParticipant = all.firstOrNull { p -> p.userId == userId },
                        isLoading = false,
                    )
                }
                Timber.d("ResultsVM: loaded %d winners, %d failed for group %s", winners.size, failed.size, groupId)
            }.onFailure { e ->
                Timber.e(e, "ResultsVM: failed to load challenge %s", groupId)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
