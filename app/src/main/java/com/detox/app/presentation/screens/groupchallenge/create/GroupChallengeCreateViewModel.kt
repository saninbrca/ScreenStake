package com.detox.app.presentation.screens.groupchallenge.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.usecase.CreateGroupChallengeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject

data class GroupCreateFormState(
    // Step 1 — settings
    val packageNames: List<String> = emptyList(),
    val displayName: String = "",
    val limitType: LimitType = LimitType.TIME,
    val limitValueMinutes: Int = 60,
    val limitValueSessions: Int = 5,
    val durationDays: Int = 7,
    val buyInEuros: Int = 5,           // 1–50
    val maxParticipants: Int = 5,      // 2–20
    val startDateMs: Long = 0L,
    val bonusEnabled: Boolean = false,
    val showBonusTooltip: Boolean = false,
    // Step 2 — review
    val generatedCode: String = "",
    // Errors
    val packageNamesError: String? = null,
    val startDateError: String? = null,
    val genericError: String? = null
)

sealed interface GroupCreateUiState {
    data object Idle : GroupCreateUiState
    data object Loading : GroupCreateUiState
    data class Created(val groupId: String, val code: String) : GroupCreateUiState
    data class Error(val message: String) : GroupCreateUiState
}

@HiltViewModel
class GroupChallengeCreateViewModel @Inject constructor(
    private val createGroupChallengeUseCase: CreateGroupChallengeUseCase,
    private val firebaseAuthService: FirebaseAuthService
) : ViewModel() {

    private val _formState = MutableStateFlow(GroupCreateFormState())
    val formState: StateFlow<GroupCreateFormState> = _formState.asStateFlow()

    private val _uiState = MutableStateFlow<GroupCreateUiState>(GroupCreateUiState.Idle)
    val uiState: StateFlow<GroupCreateUiState> = _uiState.asStateFlow()

    // ── Step 1 setters ──────────────────────────────────────────────────────────

    fun setSelectedPackages(packageNamesRaw: String, displayName: String) {
        val packages = packageNamesRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        _formState.update { it.copy(packageNames = packages, displayName = displayName, packageNamesError = null) }
    }

    fun setLimitType(type: LimitType) = _formState.update { it.copy(limitType = type) }
    fun setLimitValueMinutes(v: Int) = _formState.update { it.copy(limitValueMinutes = v.coerceIn(1, 1440)) }
    fun setLimitValueSessions(v: Int) = _formState.update { it.copy(limitValueSessions = v.coerceIn(1, 100)) }
    fun setDurationDays(v: Int) = _formState.update { it.copy(durationDays = v.coerceIn(1, 30)) }
    fun setBuyInEuros(v: Int) = _formState.update { it.copy(buyInEuros = v.coerceIn(1, 50)) }
    fun setMaxParticipants(v: Int) = _formState.update { it.copy(maxParticipants = v.coerceIn(2, 20)) }
    fun setBonusEnabled(v: Boolean) = _formState.update { it.copy(bonusEnabled = v) }
    fun setShowBonusTooltip(v: Boolean) = _formState.update { it.copy(showBonusTooltip = v) }

    fun setStartDate(ms: Long) {
        val tomorrowMidnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val error = if (ms < tomorrowMidnight) "Start date must be tomorrow or later" else null
        _formState.update { it.copy(startDateMs = ms, startDateError = error) }
    }

    fun clearError() {
        _formState.update { it.copy(genericError = null) }
        _uiState.value = GroupCreateUiState.Idle
    }

    // ── Step 1 → Step 2 validation ──────────────────────────────────────────────

    /** Returns true if Step 1 form is valid and the ViewModel can proceed. */
    fun validateStep1(): Boolean {
        val s = _formState.value
        var valid = true
        if (s.packageNames.isEmpty()) {
            _formState.update { it.copy(packageNamesError = "Select at least one app") }
            valid = false
        }
        if (s.startDateMs == 0L || s.startDateError != null) {
            _formState.update { it.copy(startDateError = "Pick a valid start date (≥ 24 h from now)") }
            valid = false
        }
        return valid
    }

    // ── Create ──────────────────────────────────────────────────────────────────

    fun createChallenge() {
        val s = _formState.value
        val userId = firebaseAuthService.currentUserId() ?: run {
            _uiState.value = GroupCreateUiState.Error("Not signed in.")
            return
        }
        val displayName = firebaseAuthService.currentUser()?.displayName ?: firebaseAuthService.currentUser()?.email ?: "Unknown"

        _uiState.value = GroupCreateUiState.Loading
        viewModelScope.launch {
            val result = createGroupChallengeUseCase(
                creatorUserId = userId,
                creatorDisplayName = displayName,
                appPackageNames = s.packageNames,
                appDisplayName = s.displayName,
                limitType = s.limitType,
                limitValueMinutes = s.limitValueMinutes,
                limitValueSessions = if (s.limitType == LimitType.SESSIONS) s.limitValueSessions else null,
                durationDays = s.durationDays,
                buyInCents = s.buyInEuros * 100,
                maxParticipants = s.maxParticipants,
                startDateMs = s.startDateMs,
                bonusEnabled = s.bonusEnabled
            )
            result.fold(
                onSuccess = { (groupId, code) ->
                    Timber.d("GroupChallengeCreateVM: created groupId=%s code=%s", groupId, code)
                    _formState.update { it.copy(generatedCode = code) }
                    _uiState.value = GroupCreateUiState.Created(groupId = groupId, code = code)
                },
                onFailure = { e ->
                    Timber.e(e, "GroupChallengeCreateVM: create failed")
                    _uiState.value = GroupCreateUiState.Error(
                        e.message ?: "Failed to create group challenge."
                    )
                }
            )
        }
    }
}
