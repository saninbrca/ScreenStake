package com.detox.app.presentation.screens.softfail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject

/** Identity + cause + calendar days survived for the Soft Mode fail result screen. */
data class SoftFailResultUiState(
    val appDisplayName: String? = null,
    val failReason: String? = null,
    /** Full CALENDAR days survived before the fail. Null until the lookup resolves. */
    val daysSurvived: Int? = null,
)

/**
 * Loads the failed challenge's display name, [Challenge.failReason] and the CALENDAR days survived
 * from Room so [SoftFailResultScreen] can show WHICH challenge failed, WHY, and for how long the
 * user actually held out. The `challengeId` is read from the navigation route arg via
 * [SavedStateHandle] (route: `soft_fail_result/{challengeId}/{streak}`).
 *
 * Days survived is calendar-derived, never a log-row count (zero-usage days often have no DailyLog
 * row on EMUI, so `logs.size`-style figures undercount):
 *  - Limit breach: anchored on the breach day — the newest DailyLog with `limitExceeded=true`
 *    (written on every breach path: overlay intra-day, worker at 23:59, on-open backstop).
 *  - No breach log (abandon / permission loss / server fail): days from start until now, clamped
 *    to the challenge's resolved end date; open-ended sentinel guarded via [DateUtils.isOpenEnded].
 */
@HiltViewModel
class SoftFailResultViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val challengeRepository: ChallengeRepository,
    private val dailyLogRepository: DailyLogRepository,
) : ViewModel() {

    private val challengeId: String? = savedStateHandle["challengeId"]

    private val _uiState = MutableStateFlow(SoftFailResultUiState())
    val uiState: StateFlow<SoftFailResultUiState> = _uiState.asStateFlow()

    init {
        val id = challengeId
        if (id != null) {
            viewModelScope.launch {
                challengeRepository.getChallengeById(id)
                    .onSuccess { challenge ->
                        if (challenge != null) {
                            _uiState.value = SoftFailResultUiState(
                                appDisplayName = challenge.appDisplayName,
                                failReason = challenge.failReason,
                                daysSurvived = computeDaysSurvived(challenge),
                            )
                        }
                    }
                    .onFailure { e -> Timber.w(e, "SoftFailResult: failed to load challenge $id") }
            }
        }
    }

    private suspend fun computeDaysSurvived(challenge: Challenge): Int {
        val startKey = dayKeyOf(challenge.startDate)
        val breachDay = dailyLogRepository.getLogsForChallengeOnce(challenge.id)
            .filter { it.limitExceeded }
            .maxOfOrNull { it.date }

        val resolvedEnd = if (challenge.endDate > 1_700_000_000_000L) challenge.endDate
        else challenge.startDate + (challenge.endDate * DateUtils.MILLIS_PER_DAY)

        val failMoment = when {
            breachDay != null -> breachDay
            DateUtils.isOpenEnded(challenge.startDate, resolvedEnd) -> System.currentTimeMillis()
            else -> minOf(System.currentTimeMillis(), resolvedEnd)
        }
        return ((failMoment - startKey) / DateUtils.MILLIS_PER_DAY).toInt().coerceAtLeast(0)
    }

    /** Midnight (local) of the day containing [timestampMs] — same day-key scheme as DailyLog.date. */
    private fun dayKeyOf(timestampMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timestampMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
