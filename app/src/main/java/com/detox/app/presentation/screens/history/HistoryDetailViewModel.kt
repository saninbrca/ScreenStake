package com.detox.app.presentation.screens.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.DailyLogEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HistoryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: DetoxDatabase,
) : ViewModel() {

    private val challengeId: String = checkNotNull(savedStateHandle["challengeId"])

    sealed interface UiState {
        data object Loading : UiState
        data class Success(
            val entity: ChallengeEntity,
            val stats: HistoryStats?,
            val durationDays: Int,
        ) : UiState
        data object NotFound : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    private suspend fun load() {
        val entity = database.challengeDao().getChallengeById(challengeId) ?: run {
            Timber.w("HistoryDetailViewModel: challenge $challengeId not found")
            _uiState.value = UiState.NotFound
            return
        }
        val logs = database.dailyLogDao().getLogsForChallengeOnce(challengeId)
        val durationDays = openEndedSafeDurationDays(entity.startDate, entity.endDate, logs)
        val stats = if (entity.status == "completed") computeStats(entity, logs, durationDays) else null
        _uiState.value = UiState.Success(entity, stats, durationDays)
        Timber.d("HistoryDetailViewModel: loaded ${entity.appDisplayName}, stats=$stats")
    }

    private fun computeStats(
        entity: ChallengeEntity,
        logs: List<DailyLogEntity>,
        durationDays: Int,
    ): HistoryStats {
        val totalConsciousOpens = logs.sumOf { it.consciousOpens }

        val savedMinutes: Int = when (entity.limitType) {
            "time" -> {
                val budget = durationDays * entity.limitValueMinutes
                val used = logs.sumOf { it.totalMinutes }
                (budget - used).coerceAtLeast(0)
            }
            "time_budget" -> {
                val budgetPerDay = entity.dailyBudgetMinutes ?: 0
                val budget = durationDays * budgetPerDay
                val used = (logs.sumOf { it.budgetUsedMs } / 60_000).toInt()
                (budget - used).coerceAtLeast(0)
            }
            else -> -1
        }

        val percentage: Int = when (entity.limitType) {
            "sessions" -> {
                val limit = entity.limitValueSessions ?: 1
                val budget = durationDays * limit
                if (budget > 0)
                    ((1.0 - totalConsciousOpens.toDouble() / budget) * 100).toInt().coerceIn(0, 99)
                else 0
            }
            "time" -> {
                val budget = durationDays * entity.limitValueMinutes
                val used = logs.sumOf { it.totalMinutes }
                if (budget > 0)
                    ((1.0 - used.toDouble() / budget) * 100).toInt().coerceIn(0, 99)
                else 0
            }
            "time_budget" -> {
                val budgetPerDay = entity.dailyBudgetMinutes ?: 1
                val budget = durationDays * budgetPerDay
                val used = (logs.sumOf { it.budgetUsedMs } / 60_000).toInt()
                if (budget > 0)
                    ((1.0 - used.toDouble() / budget) * 100).toInt().coerceIn(0, 99)
                else 0
            }
            else -> 0
        }

        val sorted = logs.sortedBy { it.date }
        var bestStreak = 0
        var current = 0
        for (log in sorted) {
            if (!log.limitExceeded) {
                current++
                bestStreak = maxOf(bestStreak, current)
            } else {
                current = 0
            }
        }

        return HistoryStats(bestStreak, totalConsciousOpens, savedMinutes, percentage)
    }
}
