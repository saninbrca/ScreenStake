package com.detox.app.presentation.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class HistoryStats(
    val bestStreak: Int,
    val totalConsciousOpens: Int,
    val savedTimeMinutes: Int,   // -1 = not calculable (SESSION_LIMIT)
    val percentageReduction: Int // 0–99, clamped
)

data class SoloChallengeHistory(
    val entity: ChallengeEntity,
    val stats: HistoryStats?,    // null for FAILED entries
    val durationDays: Int,
    /** Sort key only — never displayed. See [effectiveEndDate]. */
    val effectiveEndDate: Long
)

/** Status filter for the history list. */
enum class HistoryFilter { ALL, COMPLETED, FAILED }

/**
 * When a challenge actually finished, safe for open-ended ("Kein Enddatum") challenges. Fixed-end
 * challenges use their real [endMs]. Open-ended challenges carry a ~100-year sentinel end date
 * ([DateUtils.isOpenEnded]) that must NEVER be used as a sort key — abandoning one leaves the
 * sentinel in place, which would pin the entry to the top of a newest-first list forever. For
 * those we return the last tracked DailyLog date (same signal [openEndedSafeDurationDays] uses),
 * falling back to [startMs] when no log exists.
 */
internal fun effectiveEndDate(
    startMs: Long,
    endMs: Long,
    logs: List<DailyLogEntity>
): Long = if (DateUtils.isOpenEnded(startMs, endMs)) {
    logs.maxOfOrNull { it.date } ?: startMs
} else {
    endMs
}

/**
 * Duration in days for a finished solo challenge, safe for open-ended ("Kein Enddatum") challenges.
 * Fixed-end challenges use their real [startMs]→[endMs] span. Open-ended challenges carry a
 * ~100-year sentinel end date ([DateUtils.isOpenEnded]) that must NEVER be rendered as a day count —
 * for those we return the actual days survived, derived from the last tracked DailyLog date.
 */
internal fun openEndedSafeDurationDays(
    startMs: Long,
    endMs: Long,
    logs: List<DailyLogEntity>
): Int = if (DateUtils.isOpenEnded(startMs, endMs)) {
    val lastActive = logs.maxOfOrNull { it.date } ?: startMs
    (((lastActive - startMs) / DateUtils.MILLIS_PER_DAY).toInt() + 1).coerceAtLeast(1)
} else {
    ((endMs - startMs) / DateUtils.MILLIS_PER_DAY).toInt().coerceAtLeast(1)
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val database: DetoxDatabase,
) : ViewModel() {

    private val _allEntries = MutableStateFlow<List<SoloChallengeHistory>>(emptyList())

    private val _filter = MutableStateFlow(HistoryFilter.ALL)
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    /** True once at least one finished challenge exists, regardless of the active filter. */
    val hasAnyEntries: StateFlow<Boolean> = _allEntries.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val entries: StateFlow<List<SoloChallengeHistory>> =
        combine(_allEntries, _filter) { all, filter ->
            when (filter) {
                HistoryFilter.ALL -> all
                HistoryFilter.COMPLETED -> all.filter { it.entity.status == "completed" }
                HistoryFilter.FAILED -> all.filter { it.entity.status == "failed" }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    fun setFilter(filter: HistoryFilter) {
        _filter.value = filter
    }

    private suspend fun load() {
        val solos = database.challengeDao().getFinishedSoloChallenges()
        val result = solos.map { entity ->
            val logs = database.dailyLogDao().getLogsForChallengeOnce(entity.id)
            val durationDays = openEndedSafeDurationDays(entity.startDate, entity.endDate, logs)
            val stats = if (entity.status == "completed") computeStats(entity, logs, durationDays) else null
            SoloChallengeHistory(
                entity = entity,
                stats = stats,
                durationDays = durationDays,
                effectiveEndDate = effectiveEndDate(entity.startDate, entity.endDate, logs)
            )
        }.sortedByDescending { it.effectiveEndDate } // newest-finished first, sentinel-safe
        _allEntries.value = result
        Timber.d("HistoryViewModel: loaded ${result.size} entries")
    }

    private fun computeStats(
        entity: ChallengeEntity,
        logs: List<DailyLogEntity>,
        durationDays: Int
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
