package com.detox.app.presentation.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.domain.model.DailyStats
import com.detox.app.domain.repository.PointsRepository
import com.detox.app.domain.usecase.GetDailyStatsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val pointsRepository: PointsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            pointsRepository.getTotalPointsBalance().collect { points ->
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
            getDailyStatsUseCase().fold(
                onSuccess = { stats ->
                    if (stats.isEmpty()) {
                        _uiState.value = DashboardUiState.Empty
                    } else {
                        _uiState.value = DashboardUiState.Success(
                            activeChallenges = stats,
                            totalPoints = (_uiState.value as? DashboardUiState.Success)?.totalPoints ?: 0
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
