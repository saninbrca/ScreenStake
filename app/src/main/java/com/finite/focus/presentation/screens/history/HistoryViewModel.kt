package com.finite.focus.presentation.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finite.focus.data.local.db.DetoxDatabase
import com.finite.focus.data.local.db.entity.ChallengeEntity
import com.finite.focus.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject

data class HistoryStats(
    val bestStreak: Int,
    val totalConsciousOpens: Int,
    val savedTimeMinutes: Int,   // -1 = not calculable (SESSION_LIMIT)
    val percentageReduction: Int // 0–99, clamped
)

/**
 * One finished challenge, resolved for display. Everything the row renders is decided here — the
 * Composable formats, it does not derive.
 */
data class SoloChallengeHistory(
    val entity: ChallengeEntity,
    /**
     * When this challenge ended, from the single accessor ([effectiveEndDate]). Sort key AND the
     * date the row displays — the two were different fields before, which is what made the list
     * jump between dates it never showed.
     */
    val effectiveEndDate: Long,
    /**
     * The same instant, but only when it is trustworthy enough to put in front of the user; null
     * otherwise. See [displayableEndDate] — a null here means the row shows no date line at all
     * rather than a future or 1970 one.
     */
    val displayEndDate: Long?,
    /** CALENDAR days actually held: start → [effectiveEndDate]. */
    val durationDays: Int,
    /**
     * CALENDAR days the challenge was PLANNED to run, or null when that is not derivable
     * (open-ended, legacy day-offset endDate). Only ever used as the denominator of the
     * "after N of M days" line, which is suppressed when this is null.
     */
    val plannedDurationDays: Int?,
)

/**
 * Status filter for the history list.
 *
 * There are deliberately only three. `ended` (group challenge awaiting server settlement) and
 * `ended_unverified` (soft challenge restored after a reinstall, outcome unknowable) belong to
 * NEITHER outcome tab: calling them completed would celebrate a run nobody observed, and calling
 * them losses would accuse the user of a breach nobody recorded. They appear under [ALL] only, and
 * that is a decision rather than an oversight.
 */
enum class HistoryFilter { ALL, COMPLETED, NOT_COMPLETED }

/** A row in the flattened list: either a month separator or a challenge. */
sealed interface HistoryRow {
    /** Stable identity for `LazyColumn`'s `key`. */
    val key: String

    /**
     * Start-of-month instant for the section below it, or null for the trailing "no date" group.
     * Carried as a timestamp, not a formatted string, so the label is built with the DEVICE locale
     * at render time instead of being frozen into the ViewModel.
     */
    data class MonthHeader(val monthStartMs: Long?) : HistoryRow {
        override val key = "header-${monthStartMs ?: "undated"}"
    }

    data class Entry(val item: SoloChallengeHistory) : HistoryRow {
        override val key = "entry-${item.entity.id}"
    }
}

sealed interface HistoryUiState {
    /**
     * First load. Distinct from [NoHistory] purely so the "no finished challenges yet" copy stops
     * flashing on every open — the list is read off the disk asynchronously, and an empty list is
     * indistinguishable from "not loaded yet" without this.
     */
    data object Loading : HistoryUiState

    /** Nothing has ever finished — no filter chrome, just the empty message. */
    data object NoHistory : HistoryUiState

    /** [rows] is empty when the active filter matches nothing; the filter chrome still shows. */
    data class Content(val rows: List<HistoryRow>) : HistoryUiState
}

/**
 * **The single "when did this challenge end" accessor.** Everything that sorts, groups or displays
 * a finished challenge by its end reads this and nothing else — there is deliberately no second
 * definition to drift against.
 *
 * Resolution order:
 *  1. **[endedAtMs]** — the real recorded end (`ChallengeEntity.endedAt`), stamped at the terminal
 *     transition. Correct for every case, including the two the fallback can only approximate.
 *  2. Open-ended challenge ⇒ [lastLogDateMs], else [startMs]. The ~100-year sentinel
 *     ([DateUtils.isOpenEnded]) must NEVER become a sort key: abandoning one leaves the sentinel in
 *     place, pinning the entry to the top of a newest-first list forever.
 *  3. Otherwise [endMs] — the PLANNED end.
 *
 * Steps 2–3 are the pre-`endedAt` behaviour, kept as the fallback for rows the migration could not
 * backfill (finished before the column existed, no logs, no usable end date). They carry the known
 * inaccuracies that motivated the column and cannot be fixed from the data alone: a challenge
 * abandoned on day 2 of 30 falls through to its PLANNED end, 28 days in the future, and an
 * open-ended challenge with no logs falls back to its start. [displayableEndDate] is what stops
 * either of those reaching the user as a date.
 */
internal fun effectiveEndDate(
    startMs: Long,
    endMs: Long,
    lastLogDateMs: Long?,
    endedAtMs: Long? = null,
): Long = endedAtMs
    ?: if (DateUtils.isOpenEnded(startMs, endMs)) {
        lastLogDateMs ?: startMs
    } else {
        endMs
    }

/**
 * [effectiveEndDate], but only when it can honestly be shown as "ended on X"; null otherwise.
 *
 * Two shapes must never reach the user, and both come from the fallback arms above — a recorded
 * `endedAt` always passes:
 *  - a **future** date, from a challenge abandoned early whose PLANNED end has not arrived yet;
 *  - a **1970** date, from a legacy row whose `endDate` is a day offset (`7`) rather than millis.
 *
 * Callers render no date line at all when this is null. That is deliberate: a history row without
 * a date is merely incomplete, whereas a row claiming the user finished something next month is
 * wrong, and wrong is worse.
 */
internal fun displayableEndDate(
    effectiveEndMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): Long? = effectiveEndMs.takeIf {
    it > DateUtils.MIN_PLAUSIBLE_TIMESTAMP_MS && it <= nowMs
}

/**
 * The days a challenge was PLANNED to run, or null when the plan is not knowable.
 *
 * Null for an open-ended challenge (the ~100-year sentinel is not a plan anyone made) and for a
 * legacy day-offset `endDate`. Both would otherwise produce an absurd denominator in "after 2 of
 * 36500 days", so the line is dropped instead.
 *
 * Defers to [DateUtils.calendarDurationDays], the one day-count definition in the codebase.
 */
internal fun plannedDurationDays(startMs: Long, endMs: Long): Int? = when {
    DateUtils.isOpenEnded(startMs, endMs) -> null
    endMs <= DateUtils.MIN_PLAUSIBLE_TIMESTAMP_MS -> null
    else -> DateUtils.calendarDurationDays(startMs, endMs)
}

/** Midnight on the first day of the month containing [timestampMs] — the grouping key. */
internal fun monthStart(timestampMs: Long): Long = Calendar.getInstance().apply {
    timeInMillis = timestampMs
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * Flattens sorted entries into month sections.
 *
 * Runs AFTER filtering so a filter can never leave an empty month heading behind. Rows with no
 * displayable date cannot honestly claim a month, so they collect in a single trailing group
 * (`monthStartMs == null`) instead of being scattered into whichever month their fallback date
 * happens to land in.
 */
internal fun groupByMonth(entries: List<SoloChallengeHistory>): List<HistoryRow> {
    val rows = mutableListOf<HistoryRow>()
    var currentMonth: Long? = null
    var started = false
    // Dated entries first (already sorted newest-first), undated last.
    val (dated, undated) = entries.partition { it.displayEndDate != null }
    dated.forEach { entry ->
        val month = monthStart(entry.displayEndDate!!)
        if (!started || month != currentMonth) {
            rows += HistoryRow.MonthHeader(month)
            currentMonth = month
            started = true
        }
        rows += HistoryRow.Entry(entry)
    }
    if (undated.isNotEmpty()) {
        rows += HistoryRow.MonthHeader(null)
        undated.forEach { rows += HistoryRow.Entry(it) }
    }
    return rows
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val database: DetoxDatabase,
) : ViewModel() {

    private val _allEntries = MutableStateFlow<List<SoloChallengeHistory>?>(null) // null = loading

    private val _filter = MutableStateFlow(HistoryFilter.ALL)
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    val uiState: StateFlow<HistoryUiState> =
        combine(_allEntries, _filter) { all, filter ->
            when {
                all == null -> HistoryUiState.Loading
                all.isEmpty() -> HistoryUiState.NoHistory
                else -> HistoryUiState.Content(groupByMonth(all.filter(filter::matches)))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState.Loading)

    init {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    fun setFilter(filter: HistoryFilter) {
        _filter.value = filter
    }

    private suspend fun load() {
        val solos = database.challengeDao().getFinishedSoloChallenges()
        // ONE aggregate query for the whole list instead of one log query per row. Only the
        // pre-`endedAt` fallback needs this at all, and it needs a single number per challenge.
        val lastLogDates = database.dailyLogDao().getLastLogDatePerChallenge()
            .associate { it.challengeId to it.lastDate }

        val result = solos.map { entity ->
            val effectiveEnd = effectiveEndDate(
                startMs = entity.startDate,
                endMs = entity.endDate,
                lastLogDateMs = lastLogDates[entity.id],
                endedAtMs = entity.endedAt,
            )
            SoloChallengeHistory(
                entity = entity,
                effectiveEndDate = effectiveEnd,
                displayEndDate = displayableEndDate(effectiveEnd),
                // Real days held — start → when it ACTUALLY ended, so a 30-day challenge abandoned
                // on day 2 reads 2, not 30. Same single day-count definition as every other surface.
                durationDays = DateUtils.calendarDurationDays(entity.startDate, effectiveEnd),
                plannedDurationDays = plannedDurationDays(entity.startDate, entity.endDate),
            )
        }.sortedByDescending { it.effectiveEndDate } // newest-finished first; the date shown IS this

        _allEntries.value = result
        Timber.d("HistoryViewModel: loaded ${result.size} entries")
    }
}

/** Which rows a tab shows. See [HistoryFilter] for why the two indeterminate states match neither. */
private fun HistoryFilter.matches(item: SoloChallengeHistory): Boolean = when (this) {
    HistoryFilter.ALL -> true
    HistoryFilter.COMPLETED -> item.entity.status == "completed"
    // Every terminal loss, whatever the fail reason — the reason is shown ON the row, not filtered by.
    HistoryFilter.NOT_COMPLETED -> item.entity.status == "failed"
}
