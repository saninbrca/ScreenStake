package com.detox.app.presentation.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.DailyStats
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.usecase.GetDailyStatsUseCase
import com.detox.app.domain.usecase.SyncUserDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.detox.app.util.DateUtils
import timber.log.Timber
import javax.inject.Inject

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
    private val getDailyStatsUseCase: GetDailyStatsUseCase,
    private val syncUserDataUseCase: SyncUserDataUseCase,
    private val challengeRepository: ChallengeRepository,
    private val dailyLogRepository: DailyLogRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /** Non-null while the Hard Mode success overlay should be shown. Null = overlay hidden. */
    private val _completedChallenge = MutableStateFlow<Challenge?>(null)
    val completedChallenge: StateFlow<Challenge?> = _completedChallenge.asStateFlow()

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
            // Check if there is a Hard Mode challenge completed since last app open
            challengeRepository.getUnshownCompletedHardChallenge()
                .onSuccess { challenge ->
                    if (challenge != null) {
                        Timber.d("Dashboard: unseen completed Hard Mode challenge found — ${challenge.id}")
                        _completedChallenge.value = challenge
                    }
                }
                .onFailure { e -> Timber.w(e, "Dashboard: failed to check completed Hard Mode challenge") }
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

    /** Called when the user taps "Start New Challenge" on the success overlay. */
    fun dismissCompletionOverlay() {
        val challenge = _completedChallenge.value ?: return
        _completedChallenge.value = null
        viewModelScope.launch {
            challengeRepository.markCompletionShown(challenge.id)
                .onFailure { e -> Timber.e(e, "Dashboard: failed to mark completionShown for ${challenge.id}") }
        }
    }
}
