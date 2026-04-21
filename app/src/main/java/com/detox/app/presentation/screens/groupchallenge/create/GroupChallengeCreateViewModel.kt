package com.detox.app.presentation.screens.groupchallenge.create

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.domain.usecase.CreateGroupChallengeUseCase
import com.detox.app.domain.usecase.GetAddictiveAppsUseCase
import com.detox.app.presentation.screens.challengecreation.APP_DOMAIN_MAP
import com.detox.app.presentation.screens.challengecreation.AppListState
import com.detox.app.service.GroupStartReminderWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

const val GROUP_WIZARD_TOTAL_STEPS = 6
private const val MAX_PARTICIPANTS = 20

data class GroupCreateFormState(
    val currentStep: Int = 1,
    // Step 1 — app selection
    val packageNames: List<String> = emptyList(),
    val displayName: String = "",
    val searchQuery: String = "",
    val domainToggles: Map<String, Boolean> = emptyMap(),
    // Step 2 — limit type
    val limitType: LimitType = LimitType.TIME,
    // Step 3 — limit value + duration
    val limitValueMinutes: Int = 60,
    val limitValueSessions: Int = 5,
    val sessionMinutes: Int = 5,
    val dailyBudgetMinutes: Int = 60,
    val durationDays: Int = 7,
    val durationError: String? = null,
    // Step 4 — buy-in
    val buyInEuros: Int = 10,
    // Step 5 — start date + bonus
    val startDateEnabled: Boolean = false,
    val startDateMs: Long = 0L,
    val bonusEnabled: Boolean = false,
    // Step 6 — review (populated after creation)
    val generatedCode: String = "",
    // Errors
    val packageNamesError: String? = null,
    val startDateError: String? = null,
    val genericError: String? = null
)

sealed interface GroupCreateUiState {
    data object Idle : GroupCreateUiState
    data object Loading : GroupCreateUiState
    data class AwaitingPayment(
        val groupId: String,
        val code: String,
        val clientSecret: String,
    ) : GroupCreateUiState
    data class Created(val groupId: String, val code: String) : GroupCreateUiState
    data class Error(val message: String) : GroupCreateUiState
}

@HiltViewModel
class GroupChallengeCreateViewModel @Inject constructor(
    private val createGroupChallengeUseCase: CreateGroupChallengeUseCase,
    private val firebaseAuthService: FirebaseAuthService,
    private val getAddictiveAppsUseCase: GetAddictiveAppsUseCase,
    private val usageStatsRepository: UsageStatsRepository,
    private val challengeRepository: ChallengeRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _formState = MutableStateFlow(GroupCreateFormState())
    val formState: StateFlow<GroupCreateFormState> = _formState.asStateFlow()

    private val _uiState = MutableStateFlow<GroupCreateUiState>(GroupCreateUiState.Idle)
    val uiState: StateFlow<GroupCreateUiState> = _uiState.asStateFlow()

    private val _appListState = MutableStateFlow(AppListState())
    val appListState: StateFlow<AppListState> = _appListState.asStateFlow()

    init {
        loadApps()
    }

    // ── App loading ─────────────────────────────────────────────────────────────

    fun loadApps() {
        if (!usageStatsRepository.hasUsageStatsPermission()) {
            _appListState.value = AppListState(isLoading = false, noPermission = true)
            return
        }
        viewModelScope.launch {
            _appListState.update { it.copy(isLoading = true, error = null, noPermission = false) }
            val conflicts = mutableMapOf<String, String>()
            challengeRepository.getActiveChallengesList().getOrNull()?.forEach { challenge ->
                challenge.appPackageNames.forEach { pkg -> conflicts[pkg] = challenge.appDisplayName }
            }
            getAddictiveAppsUseCase().fold(
                onSuccess = { result ->
                    _appListState.value = AppListState(
                        isLoading = false,
                        trackableApps = result.trackableApps,
                        nonTrackableApps = result.nonTrackableApps,
                        conflictingPackages = conflicts,
                    )
                },
                onFailure = { error ->
                    _appListState.value = AppListState(isLoading = false, error = error.message ?: "Unknown error")
                },
            )
        }
    }

    // ── Step 1 — app selection ──────────────────────────────────────────────────

    fun toggleApp(packageName: String) {
        val current = _formState.value.packageNames.toMutableList()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        val apps = _appListState.value.trackableApps
        val primaryName = apps.firstOrNull { it.packageName == current.firstOrNull() }?.appName ?: ""
        val newDomainToggles = current
            .filter { APP_DOMAIN_MAP.containsKey(it) }
            .associateWith { _formState.value.domainToggles[it] ?: true }
        _formState.update {
            it.copy(
                packageNames = current,
                displayName = primaryName,
                domainToggles = newDomainToggles,
                packageNamesError = null,
            )
        }
    }

    fun toggleDomain(packageName: String) {
        _formState.update {
            val updated = it.domainToggles.toMutableMap()
            updated[packageName] = !(updated[packageName] ?: true)
            it.copy(domainToggles = updated)
        }
    }

    fun updateSearchQuery(q: String) = _formState.update { it.copy(searchQuery = q) }

    // ── Step 2 — limit type ─────────────────────────────────────────────────────

    fun setLimitType(type: LimitType) = _formState.update { it.copy(limitType = type) }

    // ── Step 3 — limit value + duration ────────────────────────────────────────

    fun setLimitValueMinutes(v: Int) = _formState.update { it.copy(limitValueMinutes = v.coerceIn(5, 600)) }
    fun setLimitValueSessions(v: Int) = _formState.update { it.copy(limitValueSessions = v.coerceIn(1, 50)) }
    fun setSessionMinutes(v: Int) = _formState.update { it.copy(sessionMinutes = v.coerceIn(5, 120)) }
    fun setDailyBudgetMinutes(v: Int) = _formState.update { it.copy(dailyBudgetMinutes = v.coerceIn(5, 600)) }

    fun setDurationDays(v: Int) {
        val clamped = v.coerceIn(1, 365)
        val error = if (clamped < 3) "Group challenges require minimum 3 days" else null
        _formState.update { it.copy(durationDays = clamped, durationError = error) }
    }

    // ── Step 4 — buy-in ─────────────────────────────────────────────────────────

    fun setBuyInEuros(v: Int) {
        val clamped = v.coerceIn(10, 50)
        _formState.update { it.copy(buyInEuros = clamped) }
    }

    // ── Step 5 — start date + bonus ─────────────────────────────────────────────

    fun setStartDateEnabled(v: Boolean) = _formState.update {
        it.copy(startDateEnabled = v, startDateError = if (!v) null else it.startDateError)
    }

    fun setStartDate(ms: Long) {
        _formState.update { it.copy(startDateMs = ms, startDateError = null) }
    }

    fun setBonusEnabled(v: Boolean) = _formState.update { it.copy(bonusEnabled = v) }

    // ── Navigation ──────────────────────────────────────────────────────────────

    fun goNext() {
        val step = _formState.value.currentStep
        if (step < GROUP_WIZARD_TOTAL_STEPS && validateCurrentStep()) {
            _formState.update { it.copy(currentStep = it.currentStep + 1) }
        }
    }

    fun goBack() {
        val step = _formState.value.currentStep
        if (step > 1) {
            _formState.update { it.copy(currentStep = it.currentStep - 1) }
        }
    }

    fun canGoNext(): Boolean {
        val s = _formState.value
        return when (s.currentStep) {
            1 -> s.packageNames.isNotEmpty()
            2 -> true
            3 -> s.durationError == null && s.durationDays >= 3
            4 -> s.buyInEuros >= 10
            5 -> !s.startDateEnabled || (s.startDateMs > 0L && s.startDateError == null)
            else -> true
        }
    }

    private fun validateCurrentStep(): Boolean {
        val s = _formState.value
        return when (s.currentStep) {
            1 -> {
                if (s.packageNames.isEmpty()) {
                    _formState.update { it.copy(packageNamesError = "Select at least one app") }
                    false
                } else true
            }
            3 -> {
                if (s.durationDays < 3) {
                    _formState.update { it.copy(durationError = "Group challenges require minimum 3 days") }
                    false
                } else true
            }
            4 -> true
            5 -> {
                if (s.startDateEnabled && s.startDateMs == 0L) {
                    _formState.update { it.copy(startDateError = "Pick a valid start date") }
                    false
                } else true
            }
            else -> true
        }
    }

    // ── Create ──────────────────────────────────────────────────────────────────

    fun createChallenge() {
        val s = _formState.value
        val userId = firebaseAuthService.currentUserId() ?: run {
            _uiState.value = GroupCreateUiState.Error("Not signed in.")
            return
        }
        val creatorName = firebaseAuthService.currentUser()?.displayName
            ?: firebaseAuthService.currentUser()?.email
            ?: "Unknown"

        _uiState.value = GroupCreateUiState.Loading
        viewModelScope.launch {
            val result = createGroupChallengeUseCase(
                creatorUserId = userId,
                creatorDisplayName = creatorName,
                appPackageNames = s.packageNames,
                appDisplayName = s.displayName,
                limitType = s.limitType,
                limitValueMinutes = when (s.limitType) {
                    LimitType.TIME -> s.limitValueMinutes
                    LimitType.TIME_BUDGET -> s.dailyBudgetMinutes
                    else -> s.limitValueMinutes
                },
                limitValueSessions = if (s.limitType == LimitType.SESSIONS) s.limitValueSessions else null,
                sessionDurationMinutes = s.sessionMinutes,
                durationDays = s.durationDays,
                buyInCents = s.buyInEuros * 100,
                maxParticipants = MAX_PARTICIPANTS,
                startDateMs = if (s.startDateEnabled) s.startDateMs else 0L,
                bonusEnabled = s.bonusEnabled,
            )
            result.fold(
                onSuccess = { data ->
                    Timber.d("GroupChallengeCreateVM: created groupId=%s code=%s", data.groupId, data.code)
                    _formState.update { it.copy(generatedCode = data.code) }
                    _uiState.value = GroupCreateUiState.AwaitingPayment(
                        groupId = data.groupId,
                        code = data.code,
                        clientSecret = data.clientSecret,
                    )
                },
                onFailure = { e ->
                    Timber.e(e, "GroupChallengeCreateVM: create failed")
                    _uiState.value = GroupCreateUiState.Error(e.message ?: "Failed to create group challenge.")
                },
            )
        }
    }

    fun onPaymentSuccess() {
        val state = _uiState.value as? GroupCreateUiState.AwaitingPayment ?: return
        if (!_formState.value.startDateEnabled) {
            scheduleStartReminder(state.groupId)
        }
        _uiState.value = GroupCreateUiState.Created(groupId = state.groupId, code = state.code)
    }

    private fun scheduleStartReminder(groupId: String) {
        val request = OneTimeWorkRequestBuilder<GroupStartReminderWorker>()
            .setInitialDelay(24, TimeUnit.HOURS)
            .setInputData(workDataOf(GroupStartReminderWorker.KEY_GROUP_ID to groupId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "group_start_reminder_$groupId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun onPaymentCancelled() {
        _uiState.value = GroupCreateUiState.Error("Payment was cancelled.")
    }

    fun clearError() {
        _formState.update { it.copy(genericError = null) }
        _uiState.value = GroupCreateUiState.Idle
    }
}
