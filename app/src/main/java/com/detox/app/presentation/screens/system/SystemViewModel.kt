package com.detox.app.presentation.screens.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.repository.AppConfig
import com.detox.app.data.repository.AppConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the blocking system screens (ForceUpdate / Maintenance). Exposes the live
 * [AppConfig] and a [retryMaintenance] action that re-reads the remote config.
 */
@HiltViewModel
class SystemViewModel @Inject constructor(
    private val appConfigRepository: AppConfigRepository
) : ViewModel() {

    val config: StateFlow<AppConfig> = appConfigRepository.config

    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    /**
     * Re-reads `config/app`. If maintenance is no longer active, invokes [onCleared] so the
     * caller can navigate the user into the app. If still in maintenance (or the read failed),
     * stays put.
     */
    fun retryMaintenance(onCleared: () -> Unit) {
        if (_retrying.value) return
        _retrying.value = true
        viewModelScope.launch {
            val latest = appConfigRepository.refresh()
            _retrying.value = false
            if (!latest.maintenanceMode) onCleared()
        }
    }
}
