package com.finite.focus.presentation.screens.softfail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.DailyLog
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.domain.repository.DailyLogRepository
import com.finite.focus.presentation.screens.dashboard.daysHeldCalendar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Identity + cause + calendar days survived for the Soft Mode fail result screen. */
data class SoftFailResultUiState(
    /** The failed challenge, so the screen can state its limit type in the user's own terms. */
    val challenge: Challenge? = null,
    /** Its DailyLogs, used ONLY to name the first breached day — never to count days survived. */
    val logs: List<DailyLog> = emptyList(),
    val appDisplayName: String? = null,
    val failReason: String? = null,
    /** Full CALENDAR days survived before the fail. Null until the lookup resolves. */
    val daysSurvived: Int? = null,
)

/**
 * Loads the failed challenge, its [DailyLog]s, [Challenge.failReason] and the CALENDAR days survived
 * from Room so [SoftFailResultScreen] can show WHICH challenge failed, WHY (down to the first
 * breached day where the logs support it), and for how long the user actually held out. The
 * `challengeId` is read from the navigation route arg via [SavedStateHandle]
 * (route: `soft_fail_result/{challengeId}/{streak}`).
 *
 * Days survived is calendar-derived, never a log-row count (zero-usage days often have no DailyLog
 * row on EMUI, so `logs.size`-style figures undercount) — the derivation itself lives in
 * `daysHeldCalendar`, shared with the Hard loss dialog so the two can't drift.
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
                            val logs = dailyLogRepository.getLogsForChallengeOnce(challenge.id)
                            _uiState.value = SoftFailResultUiState(
                                challenge = challenge,
                                logs = logs,
                                appDisplayName = challenge.appDisplayName,
                                failReason = challenge.failReason,
                                daysSurvived = daysHeldCalendar(challenge, logs),
                            )
                        }
                    }
                    .onFailure { e -> Timber.w(e, "SoftFailResult: failed to load challenge $id") }
            }
        }
    }
}
