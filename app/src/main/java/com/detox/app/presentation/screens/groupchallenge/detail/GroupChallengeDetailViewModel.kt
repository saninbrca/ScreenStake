package com.detox.app.presentation.screens.groupchallenge.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.Participant
import com.detox.app.domain.model.ParticipantStatus
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.GroupChallengeRepository
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.detox.app.util.DateUtils
import com.detox.app.util.ErrorMessages
import com.detox.app.R
import timber.log.Timber
import javax.inject.Inject

sealed interface GroupDetailUiState {
    data object Loading : GroupDetailUiState
    data class Success(
        val groupChallenge: GroupChallenge,
        val myStreak: Int = 0,
        /** Own opens today — read from Room DailyLog (source of truth), NOT Firestore participants array. */
        val myOpensToday: Int = 0,
        /** Own time used today in minutes — read from Room DailyLog (source of truth). */
        val myTimeUsedMinutes: Int = 0,
    ) : GroupDetailUiState
    data class Error(val message: String) : GroupDetailUiState
}

sealed interface StartChallengeState {
    data object Idle : StartChallengeState
    data object Loading : StartChallengeState
    data object Success : StartChallengeState
    data class Error(val message: String) : StartChallengeState
    data class PaymentNotReady(val displayName: String) : StartChallengeState
}

sealed interface QuitState {
    data object Idle : QuitState
    data object Loading : QuitState
    data class Success(val amountCents: Int) : QuitState
    data class Error(val message: String) : QuitState
}

sealed interface LeaveState {
    data object Idle : LeaveState
    data object Loading : LeaveState
    data class Success(val amountCents: Int) : LeaveState
    data class Error(val message: String) : LeaveState
}

sealed interface DeleteState {
    data object Idle : DeleteState
    data object Loading : DeleteState
    data object Success : DeleteState
    data class Error(val message: String) : DeleteState
}

data class WinDialogInfo(
    val bonusCents: Int,
    val groupId: String,
    val hasIban: Boolean,
)

@HiltViewModel
class GroupChallengeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupChallengeRepository: GroupChallengeRepository,
    private val firebaseAuthService: FirebaseAuthService,
    private val cloudFunctionsService: CloudFunctionsService,
    private val dailyLogRepository: DailyLogRepository,
    private val firestore: FirebaseFirestore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val groupId: String = requireNotNull(savedStateHandle["groupId"]) {
        "groupId nav arg is required for GroupChallengeDetailViewModel"
    }

    val currentUserId: String? get() = firebaseAuthService.currentUserId()

    private val _uiState = MutableStateFlow<GroupDetailUiState>(GroupDetailUiState.Loading)

    private val _startState = MutableStateFlow<StartChallengeState>(StartChallengeState.Idle)
    val startState: StateFlow<StartChallengeState> = _startState.asStateFlow()

    private val _quitState = MutableStateFlow<QuitState>(QuitState.Idle)
    val quitState: StateFlow<QuitState> = _quitState.asStateFlow()

    private val _leaveState = MutableStateFlow<LeaveState>(LeaveState.Idle)
    val leaveState: StateFlow<LeaveState> = _leaveState.asStateFlow()

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    /** Emits a snackbar message string — either a success confirmation or an error/rate-limit message. */
    private val _nudgeEvent = MutableStateFlow<String?>(null)
    val nudgeEvent: StateFlow<String?> = _nudgeEvent.asStateFlow()

    private val _winDialogInfo = MutableStateFlow<WinDialogInfo?>(null)
    val winDialogInfo: StateFlow<WinDialogInfo?> = _winDialogInfo.asStateFlow()

    private val prefs = context.getSharedPreferences("detox_win_popup", android.content.Context.MODE_PRIVATE)
    private val winPopupKey get() = "win_popup_shown_$groupId"

    private val podiumPrefs = context.getSharedPreferences("detox_podium", android.content.Context.MODE_PRIVATE)
    val shouldShowPodium: Boolean get() = !podiumPrefs.getBoolean("podium_shown_$groupId", false)
    fun markPodiumShown() { podiumPrefs.edit().putBoolean("podium_shown_$groupId", true).apply() }

    /** In-memory taunt count per target userId for the current session. */
    private val tauntCountsToday = mutableMapOf<String, Int>()

    private val tauntMessages = listOf(
        "👀 %s schaut zu!",
        "😂 %s hat dich erwischt!",
        "💪 %s sagt: Bleib stark!",
        "🐔 %s nennt dich einen Feigling!",
        "🔥 %s: Du verlierst deinen Streak!",
        "😈 %s lacht über dich!",
    )

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
                        Timber.d("Participants count: %d", gc.participants.size)
                        retryScheduled = false
                        val localChallengeId = "group_${gc.groupId}"
                        // ALWAYS use DateUtils.todayKey() — never inline 86400000 calculation.
                        val todayKey = DateUtils.todayKey()
                        val streak = dailyLogRepository
                            .getStreakForChallenge(localChallengeId, todayKey)
                            .getOrElse { 0 }

                        // Own progress — read from Room DailyLog (source of truth).
                        // Firestore participants array is the fallback if no Room record exists yet.
                        val myId = firebaseAuthService.currentUserId()
                        val dailyLog = dailyLogRepository
                            .getLogForDate(localChallengeId, todayKey)
                            .getOrNull()
                        val firestoreParticipant = gc.participants.find { it.userId == myId }
                        val myOpensToday = dailyLog?.consciousOpens
                            ?: firestoreParticipant?.opensToday
                            ?: 0
                        val myTimeUsedMinutes = when (gc.limitType) {
                            LimitType.TIME_BUDGET ->
                                ((dailyLog?.budgetUsedMs ?: 0L) / 60_000L).toInt()
                                    .takeIf { it > 0 }
                                    ?: (firestoreParticipant?.timeUsedMinutes ?: 0)
                            else -> dailyLog?.totalMinutes
                                ?: firestoreParticipant?.timeUsedMinutes
                                ?: 0
                        }
                        Timber.d(
                            "GroupDetailVM: myOpensToday=$myOpensToday myTimeUsedMinutes=$myTimeUsedMinutes " +
                            "source=${if (dailyLog != null) "Room" else "Firestore"}"
                        )
                        _uiState.value = GroupDetailUiState.Success(
                            gc,
                            myStreak = streak,
                            myOpensToday = myOpensToday,
                            myTimeUsedMinutes = myTimeUsedMinutes,
                        )

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
                                            GroupDetailUiState.Error(context.getString(R.string.error_challenge_not_found))
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }

    fun startChallenge() {
        if (_startState.value is StartChallengeState.Loading) return
        _startState.value = StartChallengeState.Loading
        viewModelScope.launch {
            cloudFunctionsService.startGroupChallenge(groupId)
                .onSuccess {
                    Timber.d("GroupDetailVM: startGroupChallenge succeeded for %s", groupId)
                    // Immediately sync to Room without waiting for the Firestore snapshot update
                    val currentGc = (_uiState.value as? GroupDetailUiState.Success)?.groupChallenge
                    val userId = firebaseAuthService.currentUserId()
                    if (currentGc != null && userId != null) {
                        val startDate = System.currentTimeMillis()
                        val endDate = startDate + currentGc.durationDays.toLong() * DateUtils.MILLIS_PER_DAY
                        val activeGc = currentGc.copy(
                            status = GroupChallengeStatus.ACTIVE,
                            startDate = startDate,
                            endDate = endDate
                        )
                        groupChallengeRepository.syncGroupChallengeToLocalTracking(activeGc, userId)
                            .onSuccess { Timber.d("GroupDetailVM: immediate Room sync done for group %s", groupId) }
                            .onFailure { e -> Timber.e(e, "GroupDetailVM: immediate Room sync failed for group %s", groupId) }
                    }
                    _startState.value = StartChallengeState.Success
                }
                .onFailure { e ->
                    Timber.e(e, "GroupDetailVM: startGroupChallenge failed for %s", groupId)
                    if (e.message?.contains("payment_not_ready") == true) {
                        val gc = (_uiState.value as? GroupDetailUiState.Success)?.groupChallenge
                        val displayName = gc?.appDisplayName ?: ""
                        _startState.value = StartChallengeState.PaymentNotReady(displayName)
                    } else {
                        _startState.value = StartChallengeState.Error(ErrorMessages.from(context, e))
                    }
                }
        }
    }

    fun clearStartError() { _startState.value = StartChallengeState.Idle }

    fun resetStartState() { _startState.value = StartChallengeState.Idle }

    fun daysRemaining(gc: GroupChallenge): Long =
        (gc.authorizationExpiresAt - System.currentTimeMillis()) / DateUtils.MILLIS_PER_DAY

    fun quitChallenge() {
        if (_quitState.value is QuitState.Loading) return
        val userId = firebaseAuthService.currentUserId() ?: return
        val amountCents = (_uiState.value as? GroupDetailUiState.Success)
            ?.groupChallenge?.buyInCents ?: 0
        _quitState.value = QuitState.Loading
        viewModelScope.launch {
            cloudFunctionsService.failGroupParticipant(groupId, userId)
                .onSuccess {
                    Timber.d("GroupDetailVM: quitChallenge succeeded for group %s", groupId)
                    _quitState.value = QuitState.Success(amountCents)
                }
                .onFailure { e ->
                    Timber.e(e, "GroupDetailVM: quitChallenge failed for group %s", groupId)
                    // Capture gate (failParticipant CF): the stake could not be collected, so
                    // the server deliberately left the participant ACTIVE. Say that plainly —
                    // a generic error would leave the user unsure whether they are still in.
                    val code = e.message.orEmpty()
                    _quitState.value = if (
                        code.contains("capture_failed") || code.contains("capture_not_possible")
                    ) {
                        QuitState.Error(context.getString(R.string.group_quit_capture_failed))
                    } else {
                        QuitState.Error(ErrorMessages.from(context, e))
                    }
                }
        }
    }

    fun clearQuitState() { _quitState.value = QuitState.Idle }

    fun leaveChallenge() {
        if (_leaveState.value is LeaveState.Loading) return
        _leaveState.value = LeaveState.Loading
        viewModelScope.launch {
            cloudFunctionsService.leaveGroupChallenge(groupId)
                .onSuccess { result ->
                    Timber.d("GroupDetailVM: leaveGroupChallenge succeeded for group %s", groupId)
                    _leaveState.value = LeaveState.Success(result.amountCents)
                }
                .onFailure { e ->
                    Timber.e(e, "GroupDetailVM: leaveGroupChallenge failed for group %s", groupId)
                    _leaveState.value = LeaveState.Error(ErrorMessages.from(context, e))
                }
        }
    }

    fun clearLeaveState() { _leaveState.value = LeaveState.Idle }

    fun deleteChallenge() {
        if (_deleteState.value is DeleteState.Loading) return
        _deleteState.value = DeleteState.Loading
        viewModelScope.launch {
            cloudFunctionsService.deleteGroupChallenge(groupId)
                .onSuccess {
                    Timber.d("GroupDetailVM: deleteGroupChallenge succeeded for group %s", groupId)
                    _deleteState.value = DeleteState.Success
                }
                .onFailure { e ->
                    Timber.e(e, "GroupDetailVM: deleteGroupChallenge failed for group %s", groupId)
                    _deleteState.value = DeleteState.Error(ErrorMessages.from(context, e))
                }
        }
    }

    fun clearDeleteState() { _deleteState.value = DeleteState.Idle }

    fun nudgeParticipant(targetUserId: String) {
        val fromUserId = currentUserId ?: return
        val fromDisplayName = firebaseAuthService.currentUser()?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: fromUserId.substringBefore('@')

        viewModelScope.launch {
            val cachedCount = tauntCountsToday.getOrElse(targetUserId) {
                groupChallengeRepository.countTauntsToday(groupId, fromUserId, targetUserId)
                    .also { tauntCountsToday[targetUserId] = it }
            }

            if (cachedCount >= 3) {
                _nudgeEvent.value = "Du hast heute schon 3x genervt 😄"
                return@launch
            }

            val message = tauntMessages.random().format("@$fromDisplayName")
            groupChallengeRepository.sendTaunt(groupId, fromUserId, fromDisplayName, targetUserId, message)
                .onSuccess {
                    tauntCountsToday[targetUserId] = cachedCount + 1
                    _nudgeEvent.value = "Taunt gesendet! 👀"
                    Timber.d("GroupDetailVM: taunt sent to $targetUserId in group $groupId")
                }
                .onFailure { e ->
                    Timber.e(e, "GroupDetailVM: sendTaunt failed to $targetUserId")
                    _nudgeEvent.value = context.getString(R.string.group_nudge_failed)
                }
        }
    }

    fun clearNudgeEvent() { _nudgeEvent.value = null }

    private fun triggerWinDialog(gc: GroupChallenge, participant: Participant?) {
        viewModelScope.launch {
            val uid = firebaseAuthService.currentUserId() ?: return@launch
            val hasIban = runCatching {
                val doc = firestore.collection("users").document(uid).get().await()
                !doc.getString("payoutIban").isNullOrBlank()
            }.getOrDefault(false)

            _winDialogInfo.value = WinDialogInfo(
                bonusCents = gc.perWinnerBonus,
                groupId = gc.groupId,
                hasIban = hasIban,
            )

            if (hasIban && gc.perWinnerBonus > 0 && participant != null) {
                writePayoutRequest(gc, participant, uid)
            }
        }
    }

    private suspend fun writePayoutRequest(gc: GroupChallenge, participant: Participant, uid: String) {
        runCatching {
            val userDoc = firestore.collection("users").document(uid).get().await()
            val iban = userDoc.getString("payoutIban") ?: return
            val payoutName = userDoc.getString("payoutName") ?: ""
            val displayName = firebaseAuthService.currentUser()?.displayName ?: ""
            firestore.collection("payoutRequests").add(
                mapOf(
                    "userId" to uid,
                    "displayName" to displayName,
                    "iban" to iban,
                    "payoutName" to payoutName,
                    "amountCents" to gc.perWinnerBonus,
                    "groupId" to gc.groupId,
                    "status" to "pending",
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                )
            ).await()
            Timber.d("GroupDetailVM: payoutRequest written for uid=%s groupId=%s amount=%d", uid, gc.groupId, gc.perWinnerBonus)
        }.onFailure { e ->
            Timber.e(e, "GroupDetailVM: writePayoutRequest failed for groupId=%s", gc.groupId)
        }
    }

    fun dismissWinDialog() { _winDialogInfo.value = null }

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
                    val participant = gc.participants.find { it.userId == userId }
                    val succeeded = participant?.status?.name?.uppercase() != "FAILED"
                    val alreadyCompleted = groupChallengeRepository.isLocalChallengeCompleted(groupId)
                    if (!alreadyCompleted) {
                        groupChallengeRepository.finishLocalGroupChallenge(groupId, succeeded)
                            .onFailure { e -> Timber.e(e, "GroupDetailVM: finishLocalGroupChallenge failed") }
                    }
                    if (succeeded && !prefs.getBoolean(winPopupKey, false)) {
                        prefs.edit().putBoolean(winPopupKey, true).apply()
                        triggerWinDialog(gc, participant)
                    }
                }
                GroupChallengeStatus.CANCELLED -> {
                    Timber.d("GroupDetailVM: status → CANCELLED for %s — finalising local challenge as failed", groupId)
                    val alreadyCompleted = groupChallengeRepository.isLocalChallengeCompleted(groupId)
                    if (!alreadyCompleted) {
                        groupChallengeRepository.finishLocalGroupChallenge(groupId, succeeded = false)
                            .onFailure { e -> Timber.e(e, "GroupDetailVM: finishLocalGroupChallenge (cancelled) failed") }
                    }
                }
                GroupChallengeStatus.WAITING -> Unit // nothing to sync yet
            }
        }
    }
}
