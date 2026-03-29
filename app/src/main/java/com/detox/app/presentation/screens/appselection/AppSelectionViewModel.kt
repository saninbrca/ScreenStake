package com.detox.app.presentation.screens.appselection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.domain.usecase.GetAddictiveAppsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AppSelectionUiState {
    data object Loading : AppSelectionUiState
    data class Success(
        val trackableApps: List<AppUsageInfo>,
        val nonTrackableApps: List<AppUsageInfo>
    ) : AppSelectionUiState
    data class Error(val message: String) : AppSelectionUiState
    data object NoPermission : AppSelectionUiState
}

@HiltViewModel
class AppSelectionViewModel @Inject constructor(
    private val getAddictiveAppsUseCase: GetAddictiveAppsUseCase,
    private val usageStatsRepository: UsageStatsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AppSelectionUiState>(AppSelectionUiState.Loading)
    val uiState: StateFlow<AppSelectionUiState> = _uiState.asStateFlow()

    fun loadApps() {
        if (!usageStatsRepository.hasUsageStatsPermission()) {
            _uiState.value = AppSelectionUiState.NoPermission
            return
        }

        viewModelScope.launch {
            _uiState.value = AppSelectionUiState.Loading
            getAddictiveAppsUseCase().fold(
                onSuccess = { result ->
                    _uiState.value = AppSelectionUiState.Success(
                        trackableApps = result.trackableApps,
                        nonTrackableApps = result.nonTrackableApps
                    )
                },
                onFailure = { error ->
                    _uiState.value = AppSelectionUiState.Error(
                        error.message ?: "Unknown error"
                    )
                }
            )
        }
    }
}
