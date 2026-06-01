package com.detox.app.presentation.screens.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.repository.AppConfigRepository
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.model.ParticipantStatus
import com.detox.app.domain.repository.GroupChallengeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class FriendsHubUiData(
    val active: List<GroupChallenge>,
    val waiting: List<GroupChallenge>,
    val currentUserId: String?
)

sealed interface FriendsHubUiState {
    data object Loading : FriendsHubUiState
    data class Success(val data: FriendsHubUiData) : FriendsHubUiState
}

@HiltViewModel
class FriendsHubViewModel @Inject constructor(
    private val groupChallengeRepository: GroupChallengeRepository,
    private val firebaseAuthService: FirebaseAuthService,
    private val cloudFunctionsService: CloudFunctionsService,
    appConfigRepository: AppConfigRepository,
) : ViewModel() {

    private val userId get() = firebaseAuthService.currentUserId()

    /** Remote kill-switch for NEW Group Challenge creation. Active challenges are unaffected. */
    val groupChallengeEnabled: StateFlow<Boolean> =
        appConfigRepository.config
            .map { it.groupChallengeEnabled }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = appConfigRepository.config.value.groupChallengeEnabled
            )

    // Per-groupId Firestore snapshot observers for WAITING challenges.
    // Cancelled when the challenge is no longer WAITING.
    private val activeObservers = mutableMapOf<String, Job>()
    private val lastSyncedStatuses = mutableMapOf<String, GroupChallengeStatus>()

    val uiState: StateFlow<FriendsHubUiState> =
        groupChallengeRepository.getGroupChallenges()
            .map { challenges ->
                val uid = userId
                val filtered = challenges.filter { gc ->
                    val myParticipant = gc.participants.find { it.userId == uid }
                    myParticipant != null &&
                    myParticipant.status == ParticipantStatus.ACTIVE &&
                    (gc.status == GroupChallengeStatus.WAITING || gc.status == GroupChallengeStatus.ACTIVE)
                }

                val active = filtered.filter { it.status == GroupChallengeStatus.ACTIVE }
                val waiting = filtered.filter { it.status == GroupChallengeStatus.WAITING }

                Timber.d("Friends filter: input=${challenges.size} output=${filtered.size}")
                FriendsHubUiState.Success(
                    FriendsHubUiData(
                        active = active,
                        waiting = waiting,
                        currentUserId = uid
                    )
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FriendsHubUiState.Loading
            )

    init {
        val uid = userId
        if (uid != null) {
            // One-shot Firestore refresh to keep Room cache current
            viewModelScope.launch {
                groupChallengeRepository.refreshFromFirestore(uid)
                    .onFailure { e -> Timber.e(e, "FriendsHubVM: refreshFromFirestore failed") }
            }

            // Watch WAITING challenges and set up individual Firestore observers so we
            // catch the WAITING → ACTIVE (or CANCELLED) transition even when the detail
            // screen is not open.
            viewModelScope.launch {
                groupChallengeRepository.getGroupChallenges().collect { challenges ->
                    val waitingIds = challenges
                        .filter { gc ->
                            gc.status == GroupChallengeStatus.WAITING &&
                            gc.participants.any { it.userId == uid }
                        }
                        .map { it.groupId }
                        .toSet()

                    // Stop observers for challenges that are no longer WAITING
                    val stale = activeObservers.keys.filter { it !in waitingIds }
                    stale.forEach { id ->
                        activeObservers.remove(id)?.cancel()
                        lastSyncedStatuses.remove(id)
                    }

                    // Start observers for newly-seen WAITING challenges
                    waitingIds.filter { it !in activeObservers }.forEach { groupId ->
                        activeObservers[groupId] = viewModelScope.launch {
                            groupChallengeRepository.observeGroupChallenge(groupId).collect { gc ->
                                if (gc != null) {
                                    val prev = lastSyncedStatuses[groupId]
                                    if (prev != gc.status) {
                                        lastSyncedStatuses[groupId] = gc.status
                                        if (gc.status != GroupChallengeStatus.WAITING) {
                                            syncGroupChallengeStatus(gc, uid)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun syncGroupChallengeStatus(gc: GroupChallenge, userId: String) {
        viewModelScope.launch {
            when (gc.status) {
                GroupChallengeStatus.ACTIVE -> {
                    Timber.d("FriendsHubVM: group %s → ACTIVE, syncing to Room", gc.groupId)
                    groupChallengeRepository.syncGroupChallengeToLocalTracking(gc, userId)
                        .onSuccess {
                            Timber.d(
                                "Group challenge %s started → synced to Room as ChallengeEntity",
                                gc.groupId
                            )
                        }
                        .onFailure { e ->
                            Timber.e(e, "FriendsHubVM: syncToLocalTracking failed for %s", gc.groupId)
                        }
                }
                GroupChallengeStatus.COMPLETED -> {
                    val participant = gc.participants.find { it.userId == userId }
                    val succeeded = participant?.status?.name?.uppercase() != "FAILED"
                    groupChallengeRepository.finishLocalGroupChallenge(gc.groupId, succeeded)
                        .onFailure { e ->
                            Timber.e(e, "FriendsHubVM: finishLocalGroupChallenge failed for %s", gc.groupId)
                        }
                }
                GroupChallengeStatus.CANCELLED -> {
                    groupChallengeRepository.finishLocalGroupChallenge(gc.groupId, succeeded = false)
                        .onFailure { e ->
                            Timber.e(e, "FriendsHubVM: finishLocalGroupChallenge (cancelled) failed for %s", gc.groupId)
                        }
                }
                GroupChallengeStatus.WAITING -> Unit
            }
        }
    }

    fun forceStartChallenge(groupId: String) {
        viewModelScope.launch {
            Timber.d("FriendsHubVM: force-starting group %s", groupId)
            cloudFunctionsService.startGroupChallenge(groupId)
                .onSuccess { Timber.d("FriendsHubVM: force-started group %s", groupId) }
                .onFailure { e -> Timber.e(e, "FriendsHubVM: forceStart failed for %s", groupId) }
        }
    }
}
