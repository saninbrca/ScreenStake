package com.detox.app.presentation.screens.statistics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.domain.model.OverallStatistics
import com.detox.app.domain.usecase.GetStatisticsUseCase
import com.detox.app.util.ErrorMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface StatisticsUiState {
    data object Loading : StatisticsUiState
    data class Success(val data: OverallStatistics) : StatisticsUiState
    data class Error(val message: String) : StatisticsUiState
}

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val getStatisticsUseCase: GetStatisticsUseCase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<StatisticsUiState>(StatisticsUiState.Loading)
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = StatisticsUiState.Loading
            getStatisticsUseCase()
                .onSuccess { stats ->
                    _uiState.value = StatisticsUiState.Success(stats)
                }
                .onFailure { e ->
                    _uiState.value = StatisticsUiState.Error(ErrorMessages.from(context, e))
                }
        }
    }
}
