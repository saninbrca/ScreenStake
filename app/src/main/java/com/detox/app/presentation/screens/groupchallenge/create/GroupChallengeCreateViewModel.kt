package com.detox.app.presentation.screens.groupchallenge.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.domain.usecase.CreateGroupChallengePaymentData
import com.detox.app.domain.usecase.CreateGroupChallengeUseCase
import com.detox.app.domain.usecase.GetAddictiveAppsUseCase
import com.detox.app.presentation.screens.challengecreation.APP_DOMAIN_MAP
import com.detox.app.presentation.screens.challengecreation.AppListState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

const val GROUP_WIZARD_TOTAL_STEPS = 6
private const val MAX_PARTICIPANTS = 20

data class GroupCreateFormState(
    val currentStep: Int = 1,
    // Step 1 — app/website selection
    val activeTab: Int = 0,   // 0 = Apps, 1 = Websites
    val packageNames: List<String> = emptyList(),
    val displayName: String = "",
    val searchQuery: String = "",
    val domainToggles: Map<String, Boolean> = emptyMap(),
    val manualDomainInput: String = "",
    val manualDomains: List<String> = emptyList(),
    val manualDomainError: String? = null,
    val blockAdultContent: Boolean = false,
    // Step 2 — limit type
    val limitType: LimitType? = null,
    // Step 3 — limit value + duration
    val limitValueMinutes: Int = 60,
    val limitValueSessions: Int = 5,
    val sessionMinutes: Int = 5,
    val dailyBudgetMinutes: Int = 10,
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
    /** PaymentSheet is open — only clientSecret is needed by the Screen. */
    data class AwaitingPayment(val clientSecret: String) : GroupCreateUiState
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
) : ViewModel() {

    private val _formState = MutableStateFlow(GroupCreateFormState())
    val formState: StateFlow<GroupCreateFormState> = _formState.asStateFlow()

    private val _uiState = MutableStateFlow<GroupCreateUiState>(GroupCreateUiState.Idle)
    val uiState: StateFlow<GroupCreateUiState> = _uiState.asStateFlow()

    private val _appListState = MutableStateFlow(AppListState())
    val appListState: StateFlow<AppListState> = _appListState.asStateFlow()

    /** Holds PaymentIntent data between initiatePayment (step 1) and createChallenge (step 2). */
    private var pendingPaymentData: CreateGroupChallengePaymentData? = null

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

    // ── Step 1 — tab + app/website selection ───────────────────────────────────

    fun updateActiveTab(tab: Int) = _formState.update { it.copy(activeTab = tab, manualDomainError = null) }

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

    fun updateManualDomainInput(v: String) = _formState.update { it.copy(manualDomainInput = v, manualDomainError = null) }

    fun addManualDomain() {
        var input = _formState.value.manualDomainInput.trim().lowercase()
        input = input
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore("/")
            .trim()
        if (input.isBlank() || !input.contains(".") || input.contains(" ")) {
            _formState.update { it.copy(manualDomainError = "Please enter a valid website (e.g. instagram.com)") }
            return
        }
        _formState.update { s ->
            if (!s.manualDomains.contains(input)) {
                s.copy(manualDomains = s.manualDomains + input, manualDomainInput = "", manualDomainError = null)
            } else {
                s.copy(manualDomainInput = "", manualDomainError = null)
            }
        }
    }

    fun removeManualDomain(domain: String) =
        _formState.update { it.copy(manualDomains = it.manualDomains - domain) }

    fun updateBlockAdultContent(enabled: Boolean) =
        _formState.update { it.copy(blockAdultContent = enabled) }

    fun computeBlockedDomains(): List<String> {
        val s = _formState.value
        val fromToggles = s.domainToggles
            .filter { it.value }
            .flatMap { APP_DOMAIN_MAP[it.key] ?: emptyList() }
        return (fromToggles + s.manualDomains).distinct()
    }

    // ── Step 2 — limit type ─────────────────────────────────────────────────────

    fun setLimitType(type: LimitType) = _formState.update { it.copy(limitType = type) }

    // ── Step 3 — limit value + duration ────────────────────────────────────────

    fun setLimitValueMinutes(v: Int) = _formState.update { it.copy(limitValueMinutes = v.coerceIn(5, 600)) }
    fun setLimitValueSessions(v: Int) = _formState.update { it.copy(limitValueSessions = v.coerceIn(1, 50)) }
    fun setSessionMinutes(v: Int) = _formState.update { it.copy(sessionMinutes = v.coerceIn(1, 120)) }
    fun setDailyBudgetMinutes(v: Int) = _formState.update { it.copy(dailyBudgetMinutes = v.coerceIn(1, 480)) }

    fun setDurationDays(v: Int) {
        val clamped = v.coerceIn(1, 365)
        val error = if (clamped < 3) "Group challenges require minimum 3 days" else null
        _formState.update { it.copy(durationDays = clamped, durationError = error) }
    }

    // ── Step 4 — buy-in ─────────────────────────────────────────────────────────

    fun setBuyInEuros(v: Int) {
        val clamped = v.coerceIn(10, 500)
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
            1 -> s.packageNames.isNotEmpty() || s.manualDomains.isNotEmpty() || s.blockAdultContent
            2 -> s.limitType != null
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
                val hasSelection = s.packageNames.isNotEmpty() || s.manualDomains.isNotEmpty() || s.blockAdultContent
                if (!hasSelection) {
                    _formState.update { it.copy(packageNamesError = "Select at least one app or website") }
                }
                hasSelection
            }
            2 -> s.limitType != null
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

    /**
     * Step 1: validates the form and creates a Stripe PaymentIntent via CF.
     * Sets [GroupCreateUiState.AwaitingPayment] to trigger PaymentSheet in the Screen.
     * Does NOT write to Firestore.
     */
    fun createChallenge() {
        val s = _formState.value
        _uiState.value = GroupCreateUiState.Loading
        viewModelScope.launch {
            val limitType = s.limitType ?: LimitType.TIME
            val result = createGroupChallengeUseCase.initiatePayment(
                appPackageNames = s.packageNames,
                buyInCents = s.buyInEuros * 100,
                durationDays = s.durationDays,
                limitType = limitType,
                limitValueMinutes = when (limitType) {
                    LimitType.TIME -> s.limitValueMinutes
                    LimitType.TIME_BUDGET -> s.dailyBudgetMinutes
                    else -> s.limitValueMinutes
                },
                limitValueSessions = if (limitType == LimitType.SESSIONS) s.limitValueSessions else null,
            )
            result.fold(
                onSuccess = { paymentData ->
                    Timber.d("GroupChallengeCreateVM: payment intent created groupId=%s", paymentData.groupId)
                    pendingPaymentData = paymentData
                    _uiState.value = GroupCreateUiState.AwaitingPayment(clientSecret = paymentData.clientSecret)
                },
                onFailure = { e ->
                    Timber.e(e, "GroupChallengeCreateVM: initiatePayment failed")
                    _uiState.value = GroupCreateUiState.Error(e.message ?: "Failed to prepare payment.")
                },
            )
        }
    }

    /**
     * Step 2: called only from [PaymentSheetResult.Completed].
     * Creates the Firestore document with the pre-authorized paymentIntentId.
     */
    fun onPaymentSuccess() {
        val pd = pendingPaymentData ?: run {
            Timber.w("GroupChallengeCreateVM: onPaymentSuccess called but no pendingPaymentData")
            return
        }
        val s = _formState.value
        val userId = firebaseAuthService.currentUserId() ?: run {
            _uiState.value = GroupCreateUiState.Error("Not signed in.")
            return
        }
        val creatorName = firebaseAuthService.currentUser()?.let { user ->
            user.displayName?.takeIf { it.isNotBlank() }
                ?: user.email?.substringBefore('@')
                ?: "Unknown"
        } ?: "Unknown"

        _uiState.value = GroupCreateUiState.Loading
        viewModelScope.launch {
            val limitType = s.limitType ?: LimitType.TIME
            val result = createGroupChallengeUseCase(
                creatorUserId = userId,
                creatorDisplayName = creatorName,
                appPackageNames = s.packageNames,
                appDisplayName = s.displayName,
                limitType = limitType,
                limitValueMinutes = when (limitType) {
                    LimitType.TIME -> s.limitValueMinutes
                    LimitType.TIME_BUDGET -> s.dailyBudgetMinutes
                    else -> s.limitValueMinutes
                },
                limitValueSessions = if (limitType == LimitType.SESSIONS) s.limitValueSessions else null,
                sessionDurationMinutes = s.sessionMinutes,
                durationDays = s.durationDays,
                buyInCents = s.buyInEuros * 100,
                maxParticipants = MAX_PARTICIPANTS,
                startDateMs = if (s.startDateEnabled) s.startDateMs else 0L,
                bonusEnabled = s.bonusEnabled,
                blockedDomains = computeBlockedDomains(),
                groupId = pd.groupId,
                code = pd.code,
                paymentIntentId = pd.paymentIntentId,
            )
            result.fold(
                onSuccess = { data ->
                    Timber.d("GroupChallengeCreateVM: challenge created groupId=%s code=%s", pd.groupId, data.code)
                    pendingPaymentData = null
                    _formState.update { it.copy(generatedCode = data.code) }
                    _uiState.value = GroupCreateUiState.Created(groupId = pd.groupId, code = data.code)
                },
                onFailure = { e ->
                    Timber.e(e, "GroupChallengeCreateVM: createChallenge CF failed groupId=%s", pd.groupId)
                    _uiState.value = GroupCreateUiState.Error(
                        e.message ?: "Payment received but challenge creation failed. Please contact support."
                    )
                },
            )
        }
    }

    /** Called from [PaymentSheetResult.Canceled] — no Firestore write, user stays on screen. */
    fun onPaymentCancelled() {
        pendingPaymentData = null
        _uiState.value = GroupCreateUiState.Idle
    }

    /** Called from [PaymentSheetResult.Failed] — no Firestore write, user stays on screen. */
    fun onPaymentFailed() {
        pendingPaymentData = null
        _uiState.value = GroupCreateUiState.Idle
    }

    fun clearError() {
        _formState.update { it.copy(genericError = null) }
        _uiState.value = GroupCreateUiState.Idle
    }
}
