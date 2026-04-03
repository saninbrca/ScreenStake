package com.detox.app.presentation.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.domain.model.DailyStats
import com.detox.app.domain.repository.PointsRepository
import com.detox.app.domain.usecase.GetDailyStatsUseCase
import com.detox.app.domain.usecase.SyncUserDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Success(
        val activeChallenges: List<DailyStats>,
        val totalPoints: Int
    ) : DashboardUiState
    data object Empty : DashboardUiState
    data class Error(val message: String) : DashboardUiState
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getDailyStatsUseCase: GetDailyStatsUseCase,
    private val pointsRepository: PointsRepository,
    private val syncUserDataUseCase: SyncUserDataUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    // Always-current points cache. Using SharingStarted.Eagerly so .value is
    // populated immediately — this fixes the race where loadStats() fires while
    // the state is Loading and reads 0 from the (Loading) UI state.
    private val _totalPoints: StateFlow<Int> = pointsRepository.getTotalPointsBalance()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

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
        // Keep the Success state in sync whenever points change in the DB
        // (e.g. after DailyEvaluationWorker runs or a points shop purchase).
        viewModelScope.launch {
            _totalPoints.collect { points ->
                val currentState = _uiState.value
                if (currentState is DashboardUiState.Success) {
                    _uiState.value = currentState.copy(totalPoints = points)
                }
            }
        }
    }

    fun loadStats() {
        viewModelScope.launch {
            _uiState.value = DashboardUiState.Loading
            // Wait for the one-shot sync to finish before reading Room.
            // If it already completed this is a no-op.
            syncJob.join()
            getDailyStatsUseCase().fold(
                onSuccess = { stats ->
                    if (stats.isEmpty()) {
                        _uiState.value = DashboardUiState.Empty
                    } else {
                        // _totalPoints.value is always current because the
                        // StateFlow uses SharingStarted.Eagerly — no race condition.
                        _uiState.value = DashboardUiState.Success(
                            activeChallenges = stats,
                            totalPoints = _totalPoints.value
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
    }
}
