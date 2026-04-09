package com.detox.app.presentation.screens.groupchallenge.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.repository.GroupChallengeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface GroupDetailUiState {
    data object Loading : GroupDetailUiState
    data class Success(val groupChallenge: GroupChallenge) : GroupDetailUiState
    data class Error(val message: String) : GroupDetailUiState
}

@HiltViewModel
class GroupChallengeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupChallengeRepository: GroupChallengeRepository,
    private val firebaseAuthService: FirebaseAuthService
) : ViewModel() {

    private val groupId: String = requireNotNull(savedStateHandle["groupId"]) {
        "groupId nav arg is required for GroupChallengeDetailViewModel"
    }

    private val _uiState = MutableStateFlow<GroupDetailUiState>(GroupDetailUiState.Loading)

    /**
     * Live Firestore snapshot — updates in real time as participants join, fail, or succeed.
     * Room cache is kept in sync automatically by [GroupChallengeRepositoryImpl.observeGroupChallenge].
     *
     * If the first emission is null (document not yet committed on the server), the VM
     * waits 1 second and performs a one-shot server fetch before showing "not found".
     */
    val uiState: StateFlow<GroupDetailUiState> = _uiState.asStateFlow()

    /** True after the first null emission has already triggered a one-shot retry. */
    private var retryScheduled = false
    /** Tracks the last observed status so we only sync once per transition. */
    private var lastSyncedStatus: GroupChallengeStatus? = null

    init {
        Timber.d("GroupDetailVM: start observing groupId=%s", groupId)
        viewModelScope.launch {
            groupChallengeRepository.observeGroupChallenge(groupId)
                .collect { gc ->
                    if (gc != null) {
                        // Cross-check: verify the groupId passed via nav matches Firestore.
                        if (gc.groupId != groupId) {
                            Timber.w(
                                "GroupDetailVM: groupId mismatch — nav arg=%s  document=%s",
                                groupId, gc.groupId
                            )
                        }
                        Timber.d(
                            "GroupDetailVM: snapshot update for %s status=%s participants=%d",
                            gc.groupId, gc.status, gc.participants.size
                        )
                        retryScheduled = false
                        _uiState.value = GroupDetailUiState.Success(gc)

                        // Sync to local Room when status transitions (once per transition)
                        if (gc.status != lastSyncedStatus) {
                            lastSyncedStatus = gc.status
                            syncToLocalTracking(gc)
                        }
                    } else {
                        // Document not yet visible to the listener. Schedule a one-shot
                        // server fetch after 1 s rather than showing "not found" immediately.
                        if (!retryScheduled) {
                            retryScheduled = true
                            Timber.d(
                                "GroupDetailVM: snapshot returned null for %s — " +
                                        "showing loading and retrying in 1 s", groupId
                            )
                            _uiState.value = GroupDetailUiState.Loading
                            viewModelScope.launch {
                                delay(1_000)
                                // Only act if the snapshot listener hasn't already resolved.
                                if (_uiState.value is GroupDetailUiState.Loading) {
                                    val fetched = groupChallengeRepository
                                        .fetchAndCacheById(groupId)
                                        .getOrNull()
                                    if (fetched != null) {
                                        Timber.d(
                                            "GroupDetailVM: retry fetch succeeded for %s", groupId
                                        )
                                        _uiState.value = GroupDetailUiState.Success(fetched)
                                    } else {
                                        Timber.w(
                                            "GroupDetailVM: retry fetch also returned null for %s " +
                                                    "— showing error", groupId
                                        )
                                        _uiState.value =
                                            GroupDetailUiState.Error("Challenge not found.")
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun syncToLocalTracking(gc: GroupChallenge) {
        val userId = firebaseAuthService.currentUserId() ?: return
        viewModelScope.launch {
            when (gc.status) {
                GroupChallengeStatus.ACTIVE -> {
                    Timber.d("GroupDetailVM: status → ACTIVE for %s — syncing to local Room", groupId)
                    groupChallengeRepository.syncGroupChallengeToLocalTracking(gc, userId)
                        .onSuccess { Timber.d("GroupDetailVM: local challenge created for group %s", groupId) }
                        .onFailure { e -> Timber.e(e, "GroupDetailVM: syncToLocalTracking failed") }
                }
                GroupChallengeStatus.COMPLETED -> {
                    Timber.d("GroupDetailVM: status → COMPLETED for %s — finalising local challenge", groupId)
                    // Check if current user succeeded or failed
                    val participant = gc.participants.find { it.userId == userId }
                    val succeeded = participant?.status?.name?.uppercase() != "FAILED"
                    groupChallengeRepository.finishLocalGroupChallenge(groupId, succeeded)
                        .onFailure { e -> Timber.e(e, "GroupDetailVM: finishLocalGroupChallenge failed") }
                }
                GroupChallengeStatus.CANCELLED -> {
                    Timber.d("GroupDetailVM: status → CANCELLED for %s — finalising local challenge as failed", groupId)
                    groupChallengeRepository.finishLocalGroupChallenge(groupId, succeeded = false)
                        .onFailure { e -> Timber.e(e, "GroupDetailVM: finishLocalGroupChallenge (cancelled) failed") }
                }
                GroupChallengeStatus.WAITING -> Unit // nothing to sync yet
            }
        }
    }
}
