package com.detox.app.presentation.screens.groupchallenge.create

import android.content.Context
import android.provider.Settings
import com.detox.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.repository.AppConfig
import com.detox.app.data.repository.AppConfigRepository
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.domain.usecase.CreateGroupChallengePaymentData
import com.detox.app.domain.usecase.CreateGroupChallengeUseCase
import com.detox.app.domain.usecase.GetAddictiveAppsUseCase
import com.detox.app.presentation.screens.challengecreation.APP_DOMAIN_MAP
import com.detox.app.presentation.screens.challengecreation.AppListState
import com.detox.app.util.ErrorMessages
import com.detox.app.util.PermissionUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

const val GROUP_WIZARD_TOTAL_STEPS = 6
/** Group cap — also drives the review-step "max players" row and the estimated-pot maths. */
const val GROUP_MAX_PARTICIPANTS = 20

/**
 * Ordered list of visible step ids for the current wizard path (mirrors Solo/Hard's [visibleSteps]).
 * Internal ids stay stable (the screen's `when(step)` keys on them); only membership changes:
 *  - Apps tab (activeTab == 0): all steps.
 *  - Block-only (Websites tab — custom domains and/or adult): a 24/7 hard block, so the limit-type
 *    step (2) is skipped. Step 3 stays for the duration picker but renders duration-only.
 *
 * [GroupChallengeCreateViewModel.goNext]/[goBack] walk this list and the header counter is the
 * position in it — pure so it is unit-testable.
 */
internal fun visibleGroupSteps(state: GroupCreateFormState): List<Int> =
    if (state.activeTab == 1) listOf(1, 3, 4, 5, 6) else listOf(1, 2, 3, 4, 5, 6)

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
    // Step 1 — adult-block exclusivity dialogs (mirror Solo/Hard; no silent clearing either direction)
    /** True while the "adult ON would remove your selected apps" dialog is shown. */
    val showAdultExclusiveDialog: Boolean = false,
    /** Package tapped on the Apps tab while adult-block is ON; non-null shows the mirrored dialog. */
    val pendingAdultAppPackage: String? = null,
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

    /**
     * Pre-flight enforcement-permission gate result (mirrors Solo/Hard): at least one flagged
     * permission is missing, so no PaymentIntent was created. The screen shows a dialog naming each
     * missing permission and routes the user to grant it.
     */
    data class MissingPermissions(
        val needsUsage: Boolean,
        val needsAccessibility: Boolean,
        val needsOverlay: Boolean,
    ) : GroupCreateUiState
}

@HiltViewModel
class GroupChallengeCreateViewModel @Inject constructor(
    private val createGroupChallengeUseCase: CreateGroupChallengeUseCase,
    private val firebaseAuthService: FirebaseAuthService,
    private val getAddictiveAppsUseCase: GetAddictiveAppsUseCase,
    private val usageStatsRepository: UsageStatsRepository,
    private val challengeRepository: ChallengeRepository,
    appConfigRepository: AppConfigRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Live remote config — exposes the remote group buy-in range. */
    val appConfig: StateFlow<AppConfig> = appConfigRepository.config

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
                    _appListState.value = AppListState(isLoading = false, error = ErrorMessages.from(context, error))
                },
            )
        }
    }

    // ── Step 1 — tab + app/website selection ───────────────────────────────────

    fun updateActiveTab(tab: Int) = _formState.update { it.copy(activeTab = tab, manualDomainError = null) }

    fun toggleApp(packageName: String) {
        // Adult-block is exclusive with apps (mirror Solo/Hard): adding an app while adult is ON needs
        // an explicit choice via the mirrored dialog. Removing is allowed defensively (unreachable while
        // adult is ON, since adult implies no selected apps).
        val current = _formState.value
        if (current.blockAdultContent && !current.packageNames.contains(packageName)) {
            _formState.update { it.copy(pendingAdultAppPackage = packageName) }
            return
        }
        applyToggleApp(packageName)
    }

    private fun applyToggleApp(packageName: String) {
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

    /** Mirrored exclusivity dialog: user chose the app over adult-block. */
    fun confirmAppOverAdult() {
        val pkg = _formState.value.pendingAdultAppPackage ?: return
        _formState.update { it.copy(blockAdultContent = false, pendingAdultAppPackage = null) }
        applyToggleApp(pkg)
    }

    /** Mirrored exclusivity dialog: keep adult-block, don't add the app. */
    fun dismissAppOverAdultDialog() = _formState.update { it.copy(pendingAdultAppPackage = null) }

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
            _formState.update { it.copy(manualDomainError = context.getString(R.string.error_enter_valid_website)) }
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

    fun updateBlockAdultContent(enabled: Boolean) {
        // Adult-block is exclusive with apps (mirror Solo/Hard): enabling it while apps are selected needs
        // an explicit choice — never silently clear the selection. Disabling is always free.
        if (enabled && _formState.value.packageNames.isNotEmpty()) {
            _formState.update { it.copy(showAdultExclusiveDialog = true) }
            return
        }
        _formState.update { it.copy(blockAdultContent = enabled) }
    }

    /** Exclusivity dialog: user confirmed — clear the selected apps, enable adult-block. */
    fun confirmAdultExclusive() = _formState.update {
        it.copy(
            packageNames = emptyList(),
            domainToggles = emptyMap(),
            displayName = "",
            blockAdultContent = true,
            showAdultExclusiveDialog = false,
        )
    }

    /** Exclusivity dialog: user declined — keep the apps, adult-block stays OFF. */
    fun dismissAdultExclusiveDialog() = _formState.update { it.copy(showAdultExclusiveDialog = false) }

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

    // Navigation walks [visibleGroupSteps] (nearest visible neighbour), so the block-only path's
    // skipped step 2 needs no special case. Internal indices stay 1..6 as content identifiers; the
    // screen derives the displayed counter from the same list.
    fun goNext() {
        val s = _formState.value
        if (!validateCurrentStep()) return
        val next = visibleGroupSteps(s).firstOrNull { it > s.currentStep } ?: return
        _formState.update { it.copy(currentStep = next) }
    }

    fun goBack() {
        val s = _formState.value
        val prev = visibleGroupSteps(s).lastOrNull { it < s.currentStep } ?: return
        _formState.update { it.copy(currentStep = prev) }
    }

    fun canGoNext(): Boolean {
        val s = _formState.value
        return when (s.currentStep) {
            // Tab-aware source gate (mirrors Solo's step2HasValidBlockingSource): Apps tab needs an app;
            // Websites tab needs a domain or adult-block.
            1 -> if (s.activeTab == 0) s.packageNames.isNotEmpty()
                 else s.manualDomains.isNotEmpty() || s.blockAdultContent
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
    // ── Submission derivation ────────────────────────────────────────────────────

    /** The block/limit fields as actually persisted for the current path (single source for BOTH the
     *  payment-init and the doc-create step so they can't drift — mirrors Solo's submissionFields). */
    private data class GroupSubmission(
        val appPackageNames: List<String>,
        val limitType: LimitType,
        val limitValueMinutes: Int,
        val limitValueSessions: Int?,
        val blockedDomains: List<String>,
        val blockAdultContent: Boolean,
    )

    /**
     * Block-only paths (Websites tab) skip the limit-type step, so any chosen limit is dropped here:
     * the canonical "always blocked, no minute limit" shape is TIME_WINDOW with 0 minutes and no
     * sessions, plus the adult flag. The APP path in turn NEVER carries the adult flag — adult-block
     * is exclusive with apps, and this is the submit-side guarantee behind the Step-1 dialogs. The APP
     * path also discards nothing on the Websites side (single primary source).
     */
    private fun submission(s: GroupCreateFormState): GroupSubmission {
        if (s.activeTab == 1) {
            return GroupSubmission(
                appPackageNames = emptyList(),
                limitType = LimitType.TIME_WINDOW,
                limitValueMinutes = 0,
                limitValueSessions = null,
                blockedDomains = computeBlockedDomains(),
                blockAdultContent = s.blockAdultContent,
            )
        }
        val limitType = s.limitType ?: LimitType.TIME
        return GroupSubmission(
            appPackageNames = s.packageNames,
            limitType = limitType,
            limitValueMinutes = when (limitType) {
                LimitType.TIME -> s.limitValueMinutes
                LimitType.TIME_BUDGET -> s.dailyBudgetMinutes
                else -> s.limitValueMinutes
            },
            limitValueSessions = if (limitType == LimitType.SESSIONS) s.limitValueSessions else null,
            blockedDomains = computeBlockedDomains(),
            blockAdultContent = false,
        )
    }

    /** Display name for the persisted challenge — block-only uses the first domain or the adult label. */
    private fun submissionDisplayName(s: GroupCreateFormState): String =
        if (s.activeTab == 1) s.manualDomains.firstOrNull()
            ?: context.getString(R.string.adult_block_display_name)
        else s.displayName

    fun createChallenge() {
        val s = _formState.value
        val sub = submission(s)
        // Pre-flight permission gate (mirrors Solo/Hard's createChallenge): never authorize a buy-in
        // payment while an enforcement permission is off — the challenge would silently block nothing.
        // Aborts BEFORE any PaymentIntent is created. Overlay is required when something consumes it:
        // app blocking, custom-domain blocking, or adult blocking.
        val blocksApps = sub.appPackageNames.isNotEmpty()
        val missing = GroupCreateUiState.MissingPermissions(
            needsUsage = !usageStatsRepository.hasUsageStatsPermission(),
            needsAccessibility = !PermissionUtils.isAccessibilityServiceEnabled(context),
            needsOverlay = (blocksApps || sub.blockedDomains.isNotEmpty() || sub.blockAdultContent) &&
                !Settings.canDrawOverlays(context),
        )
        if (missing.needsUsage || missing.needsAccessibility || missing.needsOverlay) {
            Timber.w(
                "GroupChallengeCreateVM: createChallenge blocked — missing permissions: usage=%s accessibility=%s overlay=%s",
                missing.needsUsage, missing.needsAccessibility, missing.needsOverlay,
            )
            _uiState.value = missing
            return
        }
        // Duplicate adult-block gate (mirrors Solo): the 133k adult list is one global flag, so a second
        // adult-only challenge blocks nothing new. Only ACTIVE challenges count; ANY active adult-block
        // matches (solo or group — group adult-blocks surface as local mirror challenges). Fail-open.
        val adultOnly = sub.blockAdultContent && !blocksApps && sub.blockedDomains.isEmpty()
        if (adultOnly) {
            _uiState.value = GroupCreateUiState.Loading
            viewModelScope.launch {
                val hasActiveAdultBlock = challengeRepository.getActiveChallengesList()
                    .getOrElse { emptyList() }
                    .any { it.blockAdultContent }
                if (hasActiveAdultBlock) {
                    Timber.w("GroupChallengeCreateVM: blocked — active adult-block challenge already exists")
                    _uiState.value = GroupCreateUiState.Error(
                        context.getString(R.string.challenge_error_duplicate_adult_block)
                    )
                } else {
                    initiatePaymentFlow(s, sub)
                }
            }
            return
        }
        initiatePaymentFlow(s, sub)
    }

    /** Closes the missing-permissions dialog without creating anything. */
    fun dismissPermissionDialog() {
        _uiState.value = GroupCreateUiState.Idle
    }

    private fun initiatePaymentFlow(s: GroupCreateFormState, sub: GroupSubmission) {
        _uiState.value = GroupCreateUiState.Loading
        viewModelScope.launch {
            val result = createGroupChallengeUseCase.initiatePayment(
                appPackageNames = sub.appPackageNames,
                buyInCents = s.buyInEuros * 100,
                durationDays = s.durationDays,
                limitType = sub.limitType,
                limitValueMinutes = sub.limitValueMinutes,
                limitValueSessions = sub.limitValueSessions,
                blockedDomains = sub.blockedDomains,
                blockAdultContent = sub.blockAdultContent,
            )
            result.fold(
                onSuccess = { paymentData ->
                    Timber.d("GroupChallengeCreateVM: payment intent created groupId=%s", paymentData.groupId)
                    pendingPaymentData = paymentData
                    _uiState.value = GroupCreateUiState.AwaitingPayment(clientSecret = paymentData.clientSecret)
                },
                onFailure = { e ->
                    Timber.e(e, "GroupChallengeCreateVM: initiatePayment failed")
                    _uiState.value = GroupCreateUiState.Error(ErrorMessages.from(context, e, R.string.error_payment))
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
            _uiState.value = GroupCreateUiState.Error(context.getString(R.string.error_not_signed_in))
            return
        }
        val creatorName = firebaseAuthService.currentUser()?.let { user ->
            user.displayName?.takeIf { it.isNotBlank() }
                ?: user.email?.substringBefore('@')
                ?: context.getString(R.string.display_name_fallback)
        } ?: context.getString(R.string.display_name_fallback)

        val sub = submission(s)
        _uiState.value = GroupCreateUiState.Loading
        viewModelScope.launch {
            val result = createGroupChallengeUseCase(
                creatorUserId = userId,
                creatorDisplayName = creatorName,
                appPackageNames = sub.appPackageNames,
                appDisplayName = submissionDisplayName(s),
                limitType = sub.limitType,
                limitValueMinutes = sub.limitValueMinutes,
                limitValueSessions = sub.limitValueSessions,
                sessionDurationMinutes = s.sessionMinutes,
                durationDays = s.durationDays,
                buyInCents = s.buyInEuros * 100,
                maxParticipants = GROUP_MAX_PARTICIPANTS,
                startDateMs = if (s.startDateEnabled) s.startDateMs else 0L,
                bonusEnabled = s.bonusEnabled,
                blockedDomains = sub.blockedDomains,
                blockAdultContent = sub.blockAdultContent,
                groupId = pd.groupId,
                code = pd.code,
                paymentIntentId = pd.paymentIntentId,
            )
            result.fold(
                onSuccess = { data ->
                    Timber.d("GroupChallengeCreateVM: challenge created groupId=%s code=%s", pd.groupId, data.code)
                    // Legal: persist the FAGG § 18 withdrawal-rights waiver consent on
                    // the group challenge doc (the checkbox gated this payment).
                    logWithdrawalWaiver(pd.groupId)
                    pendingPaymentData = null
                    _formState.update { it.copy(generatedCode = data.code) }
                    _uiState.value = GroupCreateUiState.Created(groupId = pd.groupId, code = data.code)
                },
                onFailure = { e ->
                    Timber.e(e, "GroupChallengeCreateVM: createChallenge CF failed groupId=%s", pd.groupId)
                    _uiState.value = GroupCreateUiState.Error(
                        ErrorMessages.from(context, e, R.string.error_group_creation_failed_after_payment)
                    )
                },
            )
        }
    }

    /**
     * Stores the user's explicit FAGG § 18 withdrawal-rights waiver consent on the
     * group challenge document. Fire-and-forget merge write to groupChallenges/{groupId}.
     */
    private fun logWithdrawalWaiver(groupId: String) {
        FirebaseFirestore.getInstance()
            .collection("groupChallenges").document(groupId)
            .set(
                mapOf(
                    "withdrawalWaiverAccepted" to true,
                    "withdrawalWaiverTimestamp" to System.currentTimeMillis(),
                ),
                SetOptions.merge()
            )
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
