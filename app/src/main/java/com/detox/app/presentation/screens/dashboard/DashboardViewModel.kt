package com.detox.app.presentation.screens.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.DailyStats
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.usecase.GetChallengeStreakUseCase
import com.detox.app.domain.usecase.GetDailyStatsUseCase
import com.detox.app.domain.usecase.SyncUserDataUseCase
import com.detox.app.service.TrackedAppEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.detox.app.util.DateUtils
import timber.log.Timber
import javax.inject.Inject

data class SuccessDialogState(
    val challenge: Challenge,
    val allLogs: List<DailyLog>,
    val streak: Int
)

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Success(
        val activeChallenges: List<DailyStats>
    ) : DashboardUiState
    data object Empty : DashboardUiState
    data class Error(val message: String) : DashboardUiState
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val getDailyStatsUseCase: GetDailyStatsUseCase,
    private val syncUserDataUseCase: SyncUserDataUseCase,
    private val challengeRepository: ChallengeRepository,
    private val dailyLogRepository: DailyLogRepository,
    private val database: DetoxDatabase,
    private val getChallengeStreakUseCase: GetChallengeStreakUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /** Non-null while the success dialog should be shown (Soft or Hard Mode). Null = hidden. */
    private val _successDialogChallengeId = MutableStateFlow<String?>(null)
    val successDialogChallengeId: StateFlow<String?> = _successDialogChallengeId.asStateFlow()

    private val _successDialogState = MutableStateFlow<SuccessDialogState?>(null)
    val successDialogState: StateFlow<SuccessDialogState?> = _successDialogState.asStateFlow()

    /** Non-null while the Hard Mode failure overlay should be shown. Null = overlay hidden. */
    private val _failedHardChallenge = MutableStateFlow<Challenge?>(null)
    val failedHardChallenge: StateFlow<Challenge?> = _failedHardChallenge.asStateFlow()

    /** Failed Hard Mode challenges with an active redemption window. Empty = banner hidden. */
    private val _redemptionChallenges = MutableStateFlow<List<ChallengeEntity>>(emptyList())
    val redemptionChallenges: StateFlow<List<ChallengeEntity>> = _redemptionChallenges.asStateFlow()

    /** Set to true when the user dismisses the redemption banner for this session. */
    private var redemptionBannerDismissed = false

    // Kicked off immediately on ViewModel creation. loadStats() awaits this before
    // reading Room, ensuring re-login always sees up-to-date data. On subsequent
    // loadStats() calls (tab switches, etc.) join() returns instantly.
    private val syncJob: Job = viewModelScope.launch {
        Timber.d("Dashboard: starting Firestore sync")
        syncUserDataUseCase()
            .onSuccess { Timber.d("Dashboard: sync completed") }
            .onFailure { e -> Timber.w(e, "Dashboard: sync failed (offline?)") }
    }

    init {
        observeDailyLogChanges()
    }

    fun loadStats() {
        viewModelScope.launch {
            _uiState.value = DashboardUiState.Loading
            // Wait for the one-shot sync to finish before reading Room.
            // If it already completed this is a no-op.
            syncJob.join()
            refreshStats()
            // Check if there is a completed challenge (Hard or Soft) to show the success dialog
            if (_successDialogState.value == null) {
                val sp = appContext.getSharedPreferences("detox_win_popup", Context.MODE_PRIVATE)
                val completedHard = challengeRepository.getUnshownCompletedHardChallenge()
                    .getOrNull()
                val completedSoft = if (completedHard == null) {
                    challengeRepository.getUnshownCompletedSoftChallenge().getOrNull()
                } else null
                val completedChallenge = completedHard ?: completedSoft
                if (completedChallenge != null && !sp.getBoolean("win_shown_${completedChallenge.id}", false)) {
                    Timber.d("Dashboard: unseen completed challenge — ${completedChallenge.id} mode=${completedChallenge.mode}")
                    val logs = dailyLogRepository.getLogsForChallengeOnce(completedChallenge.id)
                    val streak = getChallengeStreakUseCase(completedChallenge)
                    _successDialogState.value = SuccessDialogState(completedChallenge, logs, streak)
                    _successDialogChallengeId.value = completedChallenge.id
                }
            }

            // Check if there is a Soft Mode challenge that failed while the app was closed
            challengeRepository.getUnshownFailedSoftChallenge()
                .onSuccess { challenge ->
                    if (challenge != null) {
                        Timber.d("Dashboard: unseen failed Soft Mode challenge found — ${challenge.id}")
                        challengeRepository.markCompletionShown(challenge.id)
                        val streak = getChallengeStreakUseCase(challenge)
                        TrackedAppEventBus.emitNavigateToSoftFailResult(challenge.id, streak)
                    }
                }
                .onFailure { e -> Timber.w(e, "Dashboard: failed to check failed Soft Mode challenge") }

            // Check if there is a Hard Mode challenge that failed since last app open
            challengeRepository.getUnshownFailedHardChallenge()
                .onSuccess { challenge ->
                    if (challenge != null) {
                        Timber.d("Dashboard: unseen failed Hard Mode challenge found — ${challenge.id}")
                        _failedHardChallenge.value = challenge
                    }
                }
                .onFailure { e -> Timber.w(e, "Dashboard: failed to check failed Hard Mode challenge") }

            if (!redemptionBannerDismissed) {
                val now = System.currentTimeMillis()
                val available = database.challengeDao().getChallengesWithRedemptionAvailable(now)
                _redemptionChallenges.value = available
                Timber.d("Dashboard: ${available.size} challenge(s) with redemption available")
            }
        }
    }

    /**
     * Observes today's DailyLog rows in Room.  Whenever a conscious open is persisted
     * (or any other intra-day write happens), Room emits a new list and we re-query stats
     * without showing a Loading spinner — the existing data stays visible while it updates.
     * [drop(1)] skips the initial emission because [loadStats] already handles the first load.
     */
    private fun observeDailyLogChanges() {
        val today = DateUtils.todayKey()
        viewModelScope.launch {
            dailyLogRepository.observeLogsForDate(today)
                .drop(1)
                .collect { logs ->
                    Timber.d("Dashboard: DailyLog changed (${logs.size} rows for today) — refreshing stats")
                    logs.forEach { log ->
                        Timber.d("Reading DailyLog for ${log.challengeId} date=$today: opens=${log.consciousOpens}")
                    }
                    refreshStats()
                }
        }
    }

    private suspend fun refreshStats() {
        getDailyStatsUseCase().fold(
            onSuccess = { stats ->
                Timber.d("Dashboard loaded: ${stats.size} challenges, first challenge opens=${stats.firstOrNull()?.todayOpens}")
                if (stats.isEmpty()) {
                    _uiState.value = DashboardUiState.Empty
                } else {
                    _uiState.value = DashboardUiState.Success(
                        activeChallenges = stats
                    )
                }
            },
            onFailure = { error ->
                _uiState.value = DashboardUiState.Error(
                    error.message ?: "Failed to load stats"
                )
            }
        )
    }

    fun dismissRedemptionBanner() {
        redemptionBannerDismissed = true
        _redemptionChallenges.value = emptyList()
    }

    /** Called when the user dismisses the success dialog (X button or "Zurück zum Dashboard"). */
    fun dismissSuccessDialog() {
        val challengeId = _successDialogChallengeId.value ?: return
        _successDialogState.value = null
        _successDialogChallengeId.value = null
        viewModelScope.launch {
            appContext.getSharedPreferences("detox_win_popup", Context.MODE_PRIVATE)
                .edit().putBoolean("win_shown_$challengeId", true).apply()
            challengeRepository.markCompletionShown(challengeId)
                .onFailure { e -> Timber.e(e, "Dashboard: failed to mark completionShown for $challengeId") }
        }
    }

    /** Called when the user taps CTA on the Hard Mode fail overlay. */
    fun dismissHardFailOverlay() {
        val challenge = _failedHardChallenge.value ?: return
        _failedHardChallenge.value = null
        viewModelScope.launch {
            challengeRepository.markCompletionShown(challenge.id)
                .onFailure { e -> Timber.e(e, "Dashboard: failed to mark completionShown for ${challenge.id}") }
        }
    }
}
