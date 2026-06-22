package com.detox.app.presentation.screens.softfail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.domain.repository.ChallengeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Identity + cause for the Soft Mode fail result screen. Null until the lookup resolves. */
data class SoftFailResultUiState(
    val appDisplayName: String? = null,
    val failReason: String? = null,
)

/**
 * Loads the failed challenge's display name + [com.detox.app.domain.model.Challenge.failReason] from
 * Room so [SoftFailResultScreen] can show WHICH challenge failed and WHY. The `challengeId` is read
 * from the navigation route arg via [SavedStateHandle] (route: `soft_fail_result/{challengeId}/{streak}`),
 * so no extra plumbing through [com.detox.app.presentation.navigation.MainScreen] is needed.
 */
@HiltViewModel
class SoftFailResultViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val challengeRepository: ChallengeRepository,
) : ViewModel() {

    private val challengeId: String? = savedStateHandle["challengeId"]

    private val _uiState = MutableStateFlow(SoftFailResultUiState())
    val uiState: StateFlow<SoftFailResultUiState> = _uiState.asStateFlow()

    init {
        val id = challengeId
        if (id != null) {
            viewModelScope.launch {
                challengeRepository.getChallengeById(id)
                    .onSuccess { challenge ->
                        if (challenge != null) {
                            _uiState.value = SoftFailResultUiState(
                                appDisplayName = challenge.appDisplayName,
                                failReason = challenge.failReason,
                            )
                        }
                    }
                    .onFailure { e -> Timber.w(e, "SoftFailResult: failed to load challenge $id") }
            }
        }
    }
}
