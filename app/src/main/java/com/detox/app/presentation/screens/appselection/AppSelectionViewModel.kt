package com.detox.app.presentation.screens.appselection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.domain.usecase.GetAddictiveAppsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AppSelectionUiState {
    data object Loading : AppSelectionUiState
    data class Success(
        val trackableApps: List<AppUsageInfo>,
        val nonTrackableApps: List<AppUsageInfo>,
        /** packageName → challenge display name for packages already in an active challenge. */
        val conflictingPackages: Map<String, String> = emptyMap()
    ) : AppSelectionUiState
    data class Error(val message: String) : AppSelectionUiState
    data object NoPermission : AppSelectionUiState
}

@HiltViewModel
class AppSelectionViewModel @Inject constructor(
    private val getAddictiveAppsUseCase: GetAddictiveAppsUseCase,
    private val usageStatsRepository: UsageStatsRepository,
    private val challengeRepository: ChallengeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AppSelectionUiState>(AppSelectionUiState.Loading)
    val uiState: StateFlow<AppSelectionUiState> = _uiState.asStateFlow()

    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages: StateFlow<Set<String>> = _selectedPackages.asStateFlow()

    fun loadApps() {
        // The list comes from PackageManager (launchable apps), so it populates without
        // PACKAGE_USAGE_STATS. Usage access only enriches sorting; it is enforced at challenge
        // creation, not here — the user can browse the full app list either way.
        viewModelScope.launch {
            _uiState.value = AppSelectionUiState.Loading

            // Build conflict map: packageName → challenge display name
            val conflicts = mutableMapOf<String, String>()
            challengeRepository.getActiveChallengesList().getOrNull()?.forEach { challenge ->
                challenge.appPackageNames.forEach { pkg ->
                    conflicts[pkg] = challenge.appDisplayName
                }
            }

            getAddictiveAppsUseCase().fold(
                onSuccess = { result ->
                    _uiState.value = AppSelectionUiState.Success(
                        trackableApps = result.trackableApps,
                        nonTrackableApps = result.nonTrackableApps,
                        conflictingPackages = conflicts
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

    fun toggleSelection(packageName: String) {
        _selectedPackages.update { current ->
            if (current.contains(packageName)) current - packageName else current + packageName
        }
    }

}
