package com.detox.app.presentation.screens.challengecreation

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.BuildConfig
import com.detox.app.R
import com.detox.app.data.local.db.dao.PendingHardChallengeDao
import com.detox.app.data.local.db.entity.PendingHardChallengeEntity
import com.detox.app.data.remote.firebase.AnalyticsService
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.repository.AppConfig
import com.detox.app.data.repository.AppConfigRepository
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.model.BlockingType
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.domain.usecase.CreateChallengeUseCase
import com.detox.app.domain.usecase.GetAddictiveAppsUseCase
import com.detox.app.domain.usecase.ProcessPaymentUseCase
import com.detox.app.service.RootDetectionManager
import com.detox.app.service.UsageTrackingService
import com.detox.app.util.DateUtils
import com.detox.app.util.FeatureFlags
import com.detox.app.util.PermissionUtils
import androidx.lifecycle.SavedStateHandle
import io.sentry.Sentry
import com.google.firebase.auth.FirebaseAuth
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
import java.util.UUID
import javax.inject.Inject

val APP_DOMAIN_MAP: Map<String, List<String>> = mapOf(
    "com.instagram.android"      to listOf("instagram.com"),
    "com.zhiliaoapp.musically"   to listOf("tiktok.com"),
    "com.google.android.youtube" to listOf("youtube.com"),
    "com.twitter.android"        to listOf("twitter.com", "x.com"),
    "com.facebook.katana"        to listOf("facebook.com"),
    "com.snapchat.android"       to listOf("snapchat.com"),
    "com.reddit.frontpage"       to listOf("reddit.com"),
    "tv.twitch.android.app"      to listOf("twitch.tv"),
    "com.netflix.mediaclient"    to listOf("netflix.com"),
    "com.pinterest"              to listOf("pinterest.com"),
    "com.linkedin.android"       to listOf("linkedin.com"),
)

const val TOTAL_STEPS = 7

/**
 * Step-2 gate predicate: true when the ACTIVE tab has a real blocking source that will actually be
 * persisted at submit. Tab-aware because the two tabs persist different sources:
 *  - Apps tab (activeTab == 0): submits [ChallengeCreationState.selectedApps]; the Website-tab
 *    sources (domains/adult) are NOT the primary block, so they don't count here.
 *  - Website tab (activeTab == 1): submits manualDomains + blockAdultContent and DISCARDS
 *    selectedApps (see `saveSoftModeChallenge`). A leftover app selection must therefore NOT satisfy
 *    the gate — otherwise a "blocks nothing" challenge (no app, no domain, no adult) can be created.
 *
 * Extracted as a pure top-level function so it is unit-testable without constructing the full
 * (Hilt-injected, coroutine-driven) ViewModel; [ChallengeCreationViewModel.canGoNext] delegates to it.
 */
internal fun step2HasValidBlockingSource(
    state: ChallengeCreationState,
    conflictingPackages: Map<String, String>,
): Boolean = when (state.activeTab) {
    0 -> state.selectedApps.isNotEmpty() && state.selectedApps.none { conflictingPackages.containsKey(it) }
    else -> state.manualDomains.isNotEmpty() || state.blockAdultContent
}

/**
 * Ordered list of internal step ids (1..7) visible for the current wizard path. The internal ids
 * stay stable content keys (the screen's `when(step)` switch and [ChallengeCreationViewModel.canGoNext]
 * key on them); only membership changes per path:
 *  - APP (Apps tab): all steps; with [LimitType.TIME_WINDOW] the step-4 value picker is skipped
 *    (the window is configured on the schedule step).
 *  - Block-only (Website tab — custom domains and/or adult): the challenge is a 24/7 hard block,
 *    so both the minute-limit steps (3+4) AND the time-window step (5) are skipped.
 *
 * [ChallengeCreationViewModel.goNext]/[ChallengeCreationViewModel.goBack] walk this list, and the
 * displayed "Schritt X von Y" counter is the position in it — pure so it is unit-testable.
 */
internal fun visibleSteps(state: ChallengeCreationState): List<Int> = when {
    state.activeTab == 1 -> listOf(1, 2, 6, 7)
    state.limitType == LimitType.TIME_WINDOW -> listOf(1, 2, 3, 5, 6, 7)
    else -> listOf(1, 2, 3, 4, 5, 6, 7)
}

// ── App list sub-state ────────────────────────────────────────────────────────

data class AppListState(
    val isLoading: Boolean = true,
    val trackableApps: List<AppUsageInfo> = emptyList(),
    val nonTrackableApps: List<AppUsageInfo> = emptyList(),
    val conflictingPackages: Map<String, String> = emptyMap(),
    val error: String? = null,
    val noPermission: Boolean = false,
)

// ── Wizard form state ─────────────────────────────────────────────────────────

data class ChallengeCreationState(
    val currentStep: Int = 1,
    // Step 1
    val selectedMode: ChallengeMode? = null,
    // Step 2 — common
    val activeTab: Int = 0,   // 0 = Apps, 1 = Websites
    val searchQuery: String = "",
    // Step 2 — Apps tab
    val selectedApps: Set<String> = emptySet(),
    /** Per-package domain-blocking toggle, populated when an app with a known domain is selected. */
    val domainToggles: Map<String, Boolean> = emptyMap(),
    // Step 2 — Websites tab
    val manualDomains: List<String> = emptyList(),
    val manualDomainInput: String = "",
    val manualDomainError: String? = null,
    val blockAdultContent: Boolean = false,
    // Step 2 — adult-block exclusivity dialogs (no silent clearing in either direction)
    /** True while the "adult ON would remove your selected apps" dialog is shown. */
    val showAdultExclusiveDialog: Boolean = false,
    /** Package the user tapped on the Apps tab while adult-block is ON; non-null shows the mirrored dialog. */
    val pendingAdultAppPackage: String? = null,
    // Step 3
    val limitType: LimitType? = null,
    // Step 4
    val limitValueMinutes: Int = 60,
    val limitValueSessions: Int = 5,
    val sessionDurationMinutes: Int = 5,
    val dailyBudgetMinutes: Int = 10,
    val avgDailyMinutes: Int = 0,
    val limitMinutesError: String? = null,
    val limitSessionsError: String? = null,
    val sessionMinutesError: String? = null,
    val dailyBudgetError: String? = null,
    // Step 5
    val scheduleStart: String = "",
    val scheduleEnd: String = "",
    val activeDays: Set<String> = emptySet(),
    // Step 6
    val durationDays: Int = 7,
    val noEndDate: Boolean = false,
    val amountEuros: Int = 10,
    val durationError: String? = null,
    // Step 7
    val motivationText: String = "",
)

// ── UI state ──────────────────────────────────────────────────────────────────

sealed interface ChallengeCreationUiState {
    data object Idle : ChallengeCreationUiState
    data object Loading : ChallengeCreationUiState
    data class AwaitingPayment(val clientSecret: String, val pendingChallengeId: String) :
        ChallengeCreationUiState
    data class Success(val challengeId: String) : ChallengeCreationUiState
    data class Error(val message: String) : ChallengeCreationUiState
    data object RootedDeviceWarning : ChallengeCreationUiState

    /**
     * Pre-flight enforcement-permission gate result: at least one flagged permission is missing,
     * so the challenge was NOT created. The screen shows a dialog naming each missing permission
     * and routes the user to grant it (accessibility through the prominent-disclosure dialog).
     */
    data class MissingPermissions(
        val needsUsage: Boolean,
        val needsAccessibility: Boolean,
        val needsOverlay: Boolean,
    ) : ChallengeCreationUiState
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class ChallengeCreationViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val getAddictiveAppsUseCase: GetAddictiveAppsUseCase,
    private val createChallengeUseCase: CreateChallengeUseCase,
    private val processPaymentUseCase: ProcessPaymentUseCase,
    private val challengeRepository: ChallengeRepository,
    private val usageStatsRepository: UsageStatsRepository,
    private val firebaseAuthService: FirebaseAuthService,
    private val analyticsService: AnalyticsService,
    private val pendingHardChallengeDao: PendingHardChallengeDao,
    appConfigRepository: AppConfigRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ChallengeCreationState())
    val state: StateFlow<ChallengeCreationState> = _state.asStateFlow()

    /** Live remote feature flags (Hard Mode toggle, etc.). */
    val appConfig: StateFlow<AppConfig> = appConfigRepository.config

    private val _appListState = MutableStateFlow(AppListState())
    val appListState: StateFlow<AppListState> = _appListState.asStateFlow()

    private val _uiState = MutableStateFlow<ChallengeCreationUiState>(ChallengeCreationUiState.Idle)
    val uiState: StateFlow<ChallengeCreationUiState> = _uiState.asStateFlow()

    private var confirmedPaymentIntentId: String? = null

    /**
     * The single challenge id that was sent to createPaymentIntent (Stripe metadata.challengeId).
     * Threaded into CreateChallengeUseCase so the PaymentIntent and the persisted challenge doc
     * share ONE cid — otherwise the payment is orphaned and the server can't validate the win.
     */
    private var confirmedChallengeId: String? = null

    init {
        val prePackage = savedStateHandle.get<String>("prePackage") ?: ""
        if (prePackage.isNotBlank()) {
            val packages = prePackage.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            _state.update { it.copy(selectedApps = packages) }
        }
        loadApps()
    }

    // ── App loading ───────────────────────────────────────────────────────────

    fun loadApps() {
        // List source is PackageManager (launchable apps), so it populates regardless of
        // PACKAGE_USAGE_STATS — the user browses freely. Usage access is required to START a
        // challenge and is enforced in [createChallenge], never here.
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

    private fun hardModeMinDays(): Int {
        if (!BuildConfig.DEBUG) return 14
        val prefs = context.getSharedPreferences("detox_settings", Context.MODE_PRIVATE)
        return if (prefs.getBoolean("debug_hard_mode_min_1", false)) 1 else 14
    }

    // ── Step 1: Mode ──────────────────────────────────────────────────────────

    fun selectMode(mode: ChallengeMode) {
        // Money gate: the build-level floor (soft-only release) AND the remote hardModeEnabled
        // kill-switch. Active challenges are unaffected — this only blocks NEW Hard Mode creation.
        if (mode == ChallengeMode.HARD && !FeatureFlags.hardModeEnabled(appConfig.value.hardModeEnabled)) {
            Timber.w("Hard Mode creation blocked — money features gated off (build floor / hardModeEnabled)")
            return
        }
        _state.update { s ->
            val minDays = hardModeMinDays()
            val newDuration = if (mode == ChallengeMode.HARD) maxOf(minDays, s.durationDays) else s.durationDays
            s.copy(
                selectedMode = mode,
                noEndDate = if (mode == ChallengeMode.HARD) false else s.noEndDate,
                durationDays = newDuration,
                currentStep = 2,
            )
        }
    }

    // ── Step 2: Tab selection ─────────────────────────────────────────────────

    fun updateActiveTab(tab: Int) = _state.update { it.copy(activeTab = tab, manualDomainError = null) }

    // ── Step 2: App selection ─────────────────────────────────────────────────

    fun updateSearchQuery(query: String) = _state.update { it.copy(searchQuery = query) }

    fun toggleApp(packageName: String) {
        // Adult-block is exclusive: adding an app while it is ON needs an explicit choice
        // (mirrored dialog — "Ja, passt" disables adult and adds the app). Removing is
        // unreachable then (adult ON implies no selected apps), but stays allowed defensively.
        val current = _state.value
        if (current.blockAdultContent && !current.selectedApps.contains(packageName)) {
            _state.update { it.copy(pendingAdultAppPackage = packageName) }
            return
        }
        applyToggleApp(packageName)
    }

    /** Mirrored exclusivity dialog: user chose the app over adult-block. */
    fun confirmAppOverAdult() {
        val pkg = _state.value.pendingAdultAppPackage ?: return
        _state.update { it.copy(blockAdultContent = false, pendingAdultAppPackage = null) }
        applyToggleApp(pkg)
    }

    /** Mirrored exclusivity dialog: keep adult-block, don't add the app. */
    fun dismissAppOverAdultDialog() = _state.update { it.copy(pendingAdultAppPackage = null) }

    private fun applyToggleApp(packageName: String) {
        _state.update { s ->
            val newSelected = if (s.selectedApps.contains(packageName))
                s.selectedApps - packageName else s.selectedApps + packageName
            val newToggles = s.domainToggles.toMutableMap()
            if (newSelected.contains(packageName)) {
                if (APP_DOMAIN_MAP.containsKey(packageName)) newToggles[packageName] = true
            } else {
                newToggles.remove(packageName)
            }
            s.copy(selectedApps = newSelected, domainToggles = newToggles)
        }
        val firstPkg = _state.value.selectedApps.firstOrNull() ?: return
        if (_state.value.avgDailyMinutes == 0) {
            viewModelScope.launch {
                val stats = usageStatsRepository.getAppUsageStats(14)
                val avg = stats.firstOrNull { it.packageName == firstPkg }?.avgDailyMinutes?.toInt() ?: 0
                _state.update { it.copy(avgDailyMinutes = avg) }
            }
        }
    }

    fun toggleDomain(packageName: String) {
        _state.update { s ->
            val current = s.domainToggles[packageName] ?: true
            s.copy(domainToggles = s.domainToggles + (packageName to !current))
        }
    }

    // ── Step 2: Manual domain entry (Websites tab) ────────────────────────────

    fun updateManualDomainInput(input: String) =
        _state.update { it.copy(manualDomainInput = input, manualDomainError = null) }

    fun addManualDomain() {
        var input = _state.value.manualDomainInput.trim().lowercase()
        input = input
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore("/")
            .trim()

        if (input.isBlank() || !input.contains(".") || input.contains(" ")) {
            _state.update { it.copy(manualDomainError = "Please enter a valid website (e.g. instagram.com)") }
            return
        }

        _state.update { s ->
            if (!s.manualDomains.contains(input)) {
                s.copy(manualDomains = s.manualDomains + input, manualDomainInput = "", manualDomainError = null)
            } else {
                s.copy(manualDomainInput = "", manualDomainError = null)
            }
        }
    }

    fun removeManualDomain(domain: String) =
        _state.update { it.copy(manualDomains = it.manualDomains - domain) }

    fun updateBlockAdultContent(enabled: Boolean) {
        // Adult-block is exclusive: enabling it while apps are selected needs an explicit
        // choice (dialog) — never silently clear the selection. Disabling is always free.
        if (enabled && _state.value.selectedApps.isNotEmpty()) {
            _state.update { it.copy(showAdultExclusiveDialog = true) }
            return
        }
        _state.update { it.copy(blockAdultContent = enabled) }
    }

    /** Exclusivity dialog: user confirmed — clear the selected apps, enable adult-block. */
    fun confirmAdultExclusive() = _state.update {
        it.copy(
            selectedApps = emptySet(),
            domainToggles = emptyMap(),
            blockAdultContent = true,
            showAdultExclusiveDialog = false,
        )
    }

    /** Exclusivity dialog: user declined — keep the apps, adult-block stays OFF. */
    fun dismissAdultExclusiveDialog() = _state.update { it.copy(showAdultExclusiveDialog = false) }

    // ── Step 3: Limit type ────────────────────────────────────────────────────

    fun selectLimitType(type: LimitType) = _state.update { it.copy(limitType = type) }

    // ── Step 4: Limit values ──────────────────────────────────────────────────

    fun updateLimitMinutes(minutes: Int) {
        _state.update { s ->
            s.copy(
                limitValueMinutes = minutes,
                limitMinutesError = if (minutes < 5) context.getString(R.string.challenge_setup_error_min_minutes) else null,
            )
        }
    }

    fun updateLimitSessions(sessions: Int) {
        _state.update { s ->
            s.copy(
                limitValueSessions = sessions,
                limitSessionsError = if (sessions < 1)
                    context.getString(R.string.challenge_setup_error_min_sessions) else null,
            )
        }
    }

    fun updateSessionDurationMinutes(minutes: Int) {
        _state.update { s ->
            s.copy(
                sessionDurationMinutes = minutes,
                sessionMinutesError = if (minutes < 1)
                    context.getString(R.string.challenge_setup_error_min_session_mins) else null,
            )
        }
    }

    fun updateDailyBudgetMinutes(minutes: Int) {
        _state.update { s ->
            s.copy(
                dailyBudgetMinutes = minutes,
                dailyBudgetError = if (minutes < 5) context.getString(R.string.challenge_setup_error_min_budget) else null,
            )
        }
    }

    // ── Step 5: Schedule ──────────────────────────────────────────────────────

    fun updateScheduleStart(time: String) = _state.update { it.copy(scheduleStart = time) }
    fun updateScheduleEnd(time: String) = _state.update { it.copy(scheduleEnd = time) }
    fun toggleActiveDay(day: String) = _state.update { s ->
        s.copy(activeDays = if (s.activeDays.contains(day)) s.activeDays - day else s.activeDays + day)
    }
    fun clearSchedule() = _state.update { it.copy(scheduleStart = "", scheduleEnd = "", activeDays = emptySet()) }

    // ── Step 6: Duration ──────────────────────────────────────────────────────

    fun updateDurationDays(days: Int) {
        _state.update { s ->
            s.copy(
                durationDays = days,
                durationError = if (s.selectedMode == ChallengeMode.HARD && days < hardModeMinDays())
                    context.getString(R.string.challenge_setup_error_hard_mode_min_days) else null,
            )
        }
    }

    fun updateNoEndDate(enabled: Boolean) {
        _state.update { s ->
            s.copy(
                noEndDate = enabled,
                durationDays = if (enabled) DateUtils.NO_END_DATE_DAYS else 7,
                durationError = null,
            )
        }
    }

    fun updateAmountEuros(euros: Int) = _state.update { it.copy(amountEuros = euros) }

    // ── Step 7: Confirm ───────────────────────────────────────────────────────

    fun updateMotivationText(text: String) = _state.update { it.copy(motivationText = text) }

    // ── Navigation ────────────────────────────────────────────────────────────

    // Navigation walks [visibleSteps] (nearest visible neighbour), so per-path skips — TIME_WINDOW's
    // missing step 4, the block-only paths' missing 3/4/5 — need no special cases here. Internal
    // indices stay 1..7 as content identifiers; the screen derives the displayed counter from the
    // same list.
    fun goBack() = _state.update { s ->
        s.copy(currentStep = visibleSteps(s).lastOrNull { it < s.currentStep } ?: 1)
    }
    fun goNext() = _state.update { s ->
        s.copy(currentStep = visibleSteps(s).firstOrNull { it > s.currentStep } ?: s.currentStep)
    }

    fun canGoNext(): Boolean {
        val s = _state.value
        val conflicts = _appListState.value.conflictingPackages
        return when (s.currentStep) {
            1 -> s.selectedMode != null
            2 -> step2HasValidBlockingSource(s, conflicts)
            3 -> s.limitType != null
            4 -> when (s.limitType) {
                LimitType.TIME        -> s.limitMinutesError == null
                LimitType.SESSIONS    -> s.limitSessionsError == null && s.sessionMinutesError == null
                LimitType.TIME_BUDGET -> s.dailyBudgetError == null
                LimitType.TIME_WINDOW -> true
                null -> false
            }
            5 -> if (s.limitType == LimitType.TIME_WINDOW)
                s.scheduleStart.length == 5 && s.scheduleEnd.length == 5
            else true
            6 -> s.durationError == null
            7 -> true
            else -> false
        }
    }

    // ── Domain computation ────────────────────────────────────────────────────

    private fun computeBlockedDomains(): List<String> {
        val s = _state.value
        return if (s.activeTab == 0) {
            (s.domainToggles.entries.filter { it.value }
                .flatMap { APP_DOMAIN_MAP[it.key] ?: emptyList() } + s.manualDomains)
                .distinct()
        } else {
            s.manualDomains
        }
    }

    // ── Challenge creation ────────────────────────────────────────────────────

    fun createChallenge() {
        firebaseAuthService.logAuthState("ChallengeCreationViewModel.createChallenge")
        // Pre-flight permission gate (Soft AND Hard): never create a challenge (and for Hard Mode
        // never authorize a Stripe payment) while an enforcement permission is off — the challenge
        // would silently block nothing. Aborts BEFORE the root check, save, or payment. Overlay is
        // required when something consumes it: app blocking, custom-domain blocking, or adult
        // blocking (the about:blank redirect needs SYSTEM_ALERT_WINDOW as its background-activity-
        // launch exemption, and the explanation overlay needs it to draw). The screen routes each
        // missing permission to its grant flow — accessibility through the prominent-disclosure dialog.
        val blocksApps = _state.value.activeTab == 0 && _state.value.selectedApps.isNotEmpty()
        val missing = ChallengeCreationUiState.MissingPermissions(
            needsUsage = !usageStatsRepository.hasUsageStatsPermission(),
            needsAccessibility = !PermissionUtils.isAccessibilityServiceEnabled(context),
            needsOverlay = (blocksApps || computeBlockedDomains().isNotEmpty() ||
                _state.value.blockAdultContent) &&
                !Settings.canDrawOverlays(context),
        )
        if (missing.needsUsage || missing.needsAccessibility || missing.needsOverlay) {
            Timber.w(
                "createChallenge blocked — missing permissions: usage=%s accessibility=%s overlay=%s",
                missing.needsUsage, missing.needsAccessibility, missing.needsOverlay,
            )
            _uiState.value = missing
            return
        }
        if (_state.value.selectedMode == ChallengeMode.HARD) {
            // Defense-in-depth: never authorize a Stripe payment for Hard Mode when money features
            // are gated off (build floor / remote kill-switch). Step 1 already blocks selecting HARD,
            // so in a gated build selectedMode can't be HARD here — this guarantees no PaymentSheet.
            if (!FeatureFlags.hardModeEnabled(appConfig.value.hardModeEnabled)) {
                Timber.w("createChallenge: Hard Mode blocked — money features gated off; aborting before payment")
                _uiState.value = ChallengeCreationUiState.Idle
                return
            }
            var rootWarningShown = false
            RootDetectionManager.checkAndWarn(context) {
                logRootedDeviceToFirestore()
                _uiState.value = ChallengeCreationUiState.RootedDeviceWarning
                rootWarningShown = true
            }
            if (!rootWarningShown) initiateHardModePayment()
        } else {
            saveSoftModeChallenge()
        }
    }

    fun acknowledgeRootWarningAndProceed() {
        _uiState.value = ChallengeCreationUiState.Idle
        initiateHardModePayment()
    }

    fun dismissRootWarning() {
        _uiState.value = ChallengeCreationUiState.Idle
    }

    /** Closes the missing-permissions dialog without creating anything. */
    fun dismissPermissionDialog() {
        _uiState.value = ChallengeCreationUiState.Idle
    }

    /**
     * Stores the user's explicit FAGG § 18 withdrawal-rights waiver consent on the
     * challenge document. Fire-and-forget merge write — mirrors the challenge doc
     * at users/{uid}/challenges/{challengeId} written by FirestoreService.
     */
    private fun logWithdrawalWaiver(challengeId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("challenges").document(challengeId)
            .set(
                mapOf(
                    "withdrawalWaiverAccepted" to true,
                    "withdrawalWaiverTimestamp" to System.currentTimeMillis(),
                ),
                SetOptions.merge()
            )
    }

    private fun logRootedDeviceToFirestore() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("deviceInfo").document("security")
            .set(
                mapOf("isRooted" to true, "detectedAt" to System.currentTimeMillis()),
                SetOptions.merge()
            )
    }

    private fun displayName(): String {
        val s = _state.value
        // Website tab: first custom domain, or the dedicated adult-only name (the Step-2 gate
        // guarantees adult is ON when no domain was added).
        if (s.activeTab == 1) return s.manualDomains.firstOrNull()
            ?: context.getString(R.string.adult_block_display_name)
        if (s.selectedApps.isEmpty()) return "App"
        val apps = _appListState.value.trackableApps
        return s.selectedApps.joinToString(", ") { pkg ->
            apps.firstOrNull { it.packageName == pkg }?.appName ?: pkg
        }
    }

    private fun resolveLimitPair(s: ChallengeCreationState): Pair<Int, Int?> = when (s.limitType) {
        LimitType.TIME        -> s.limitValueMinutes to null
        LimitType.SESSIONS    -> s.sessionDurationMinutes to s.limitValueSessions
        LimitType.TIME_BUDGET -> 0 to null
        LimitType.TIME_WINDOW -> 0 to null
        null -> 0 to null
    }

    /** Limit/schedule/adult fields as actually persisted for the current path. */
    private data class SubmissionFields(
        val limitType: LimitType,
        val limitValueMinutes: Int,
        val limitValueSessions: Int?,
        val blockAdultContent: Boolean,
        val scheduleStartTime: String?,
        val scheduleEndTime: String?,
        val activeDays: List<String>,
        val dailyBudgetMinutes: Int?,
    )

    /**
     * Derives the persisted limit/schedule/adult fields from the wizard state — the single source
     * for BOTH the Soft Mode save and the Hard Mode pending record, so the two paths can't drift.
     *
     * Block-only paths (Website tab) skip the limit and schedule steps entirely, so any leftover
     * limit/schedule state from an earlier path switch is dropped here: the canonical "always
     * blocked, no minute limit" shape is TIME_WINDOW with 0 minutes and no window (validated as-is
     * by CreateChallengeUseCase; enforcement hard-blocks matched domains unconditionally).
     * The APP path in turn NEVER carries the adult flag — adult-block is exclusive, and this is the
     * submit-side guarantee behind the Step-2 dialogs.
     */
    private fun submissionFields(s: ChallengeCreationState): SubmissionFields {
        if (s.activeTab == 1) {
            return SubmissionFields(
                limitType = LimitType.TIME_WINDOW,
                limitValueMinutes = 0,
                limitValueSessions = null,
                blockAdultContent = s.blockAdultContent,
                scheduleStartTime = null,
                scheduleEndTime = null,
                activeDays = emptyList(),
                dailyBudgetMinutes = null,
            )
        }
        val (limitMinutes, limitSessions) = resolveLimitPair(s)
        return SubmissionFields(
            limitType = s.limitType ?: LimitType.TIME,
            limitValueMinutes = limitMinutes,
            limitValueSessions = limitSessions,
            blockAdultContent = false,
            scheduleStartTime = s.scheduleStart.takeIf { it.length == 5 },
            scheduleEndTime = s.scheduleEnd.takeIf { it.length == 5 },
            activeDays = s.activeDays.toList(),
            dailyBudgetMinutes = if (s.limitType == LimitType.TIME_BUDGET) s.dailyBudgetMinutes else null,
        )
    }

    private fun saveSoftModeChallenge() {
        val s = _state.value
        val isWebsiteTab = s.activeTab == 1
        _uiState.value = ChallengeCreationUiState.Loading
        viewModelScope.launch {
            val fields = submissionFields(s)
            val appPackages = if (!isWebsiteTab) s.selectedApps.toList() else emptyList()
            createChallengeUseCase(
                appPackageName = appPackages.firstOrNull(),
                appDisplayName = displayName(),
                limitType = fields.limitType,
                limitValueMinutes = fields.limitValueMinutes,
                limitValueSessions = fields.limitValueSessions,
                durationDays = if (s.noEndDate) DateUtils.NO_END_DATE_DAYS else s.durationDays,
                customMotivation = s.motivationText.ifBlank { null },
                mode = ChallengeMode.SOFT,
                appPackageNames = appPackages,
                blockedDomains = computeBlockedDomains(),
                partialBlockDomains = emptyList(),
                blockingType = if (!isWebsiteTab) BlockingType.APP else BlockingType.WEBSITE,
                blockAdultContent = fields.blockAdultContent,
                scheduleStartTime = fields.scheduleStartTime,
                scheduleEndTime = fields.scheduleEndTime,
                activeDays = fields.activeDays,
                sessionDurationMinutes = s.sessionDurationMinutes,
                dailyBudgetMinutes = fields.dailyBudgetMinutes,
                partialBlockSections = emptyList(),
                isPartialBlockOnly = false,
            ).fold(
                onSuccess = { result ->
                    analyticsService.logChallengeCreated(
                        mode = "soft",
                        limitType = fields.limitType.name.lowercase(),
                        durationDays = s.durationDays,
                    )
                    UsageTrackingService.start(context)
                    _uiState.value = ChallengeCreationUiState.Success(result.challengeId)
                },
                onFailure = { error ->
                    _uiState.value = ChallengeCreationUiState.Error(error.message ?: "Failed to create challenge")
                },
            )
        }
    }

    private fun initiateHardModePayment() {
        val s = _state.value
        _uiState.value = ChallengeCreationUiState.Loading
        viewModelScope.launch {
            val tempId = UUID.randomUUID().toString()
            processPaymentUseCase(
                amountCents = s.amountEuros * 100,
                durationDays = s.durationDays,
                challengeId = tempId,
            ).fold(
                onSuccess = { paymentData ->
                    confirmedPaymentIntentId = paymentData.paymentIntentId
                    // Persist the SAME id the PI was created with (Stripe metadata.challengeId).
                    confirmedChallengeId = tempId
                    // MONEY-CRITICAL (Part A): durably record the FULL creation payload BEFORE the
                    // PaymentSheet shows, so a ViewModel/Activity/process recreation during the Stripe
                    // round trip can no longer lose the challenge after the stake is captured. The
                    // confirm + startup-recovery paths rebuild from this record, not the volatile
                    // in-memory fields / _state. Failure to record is non-fatal (the in-memory fast
                    // path still works) but logged loudly — without it we'd be back to the silent loss.
                    runCatching {
                        pendingHardChallengeDao.upsert(
                            buildPendingRecord(s, challengeId = tempId, paymentIntentId = paymentData.paymentIntentId)
                        )
                    }.onFailure { e ->
                        Timber.e(e, "initiateHardModePayment: failed to persist PendingHardChallenge for $tempId")
                        Sentry.captureException(e)
                    }
                    _uiState.value = ChallengeCreationUiState.AwaitingPayment(
                        clientSecret = paymentData.clientSecret,
                        pendingChallengeId = tempId,
                    )
                },
                onFailure = { error ->
                    _uiState.value = ChallengeCreationUiState.Error(error.message ?: "Payment setup failed")
                },
            )
        }
    }

    /**
     * Snapshots the current Hard Mode form state into a durable [PendingHardChallengeEntity]. Captures
     * deviceId (ANDROID_ID) + rooted status here (at PI-create time) so the anti-cheat metadata is part
     * of the recoverable payload and the confirm path never has to re-read volatile state.
     */
    private fun buildPendingRecord(
        s: ChallengeCreationState,
        challengeId: String,
        paymentIntentId: String,
    ): PendingHardChallengeEntity {
        val isWebsiteTab = s.activeTab == 1
        // Same field derivation as the Soft Mode save (block-only paths force the
        // "always blocked" shape; APP path never carries the adult flag).
        val fields = submissionFields(s)
        val appPackagesHard = if (!isWebsiteTab) s.selectedApps.toList() else emptyList()
        @Suppress("HardwareIds")
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )
        val deviceRooted = RootDetectionManager.isDeviceRooted(context)
        return PendingHardChallengeEntity(
            challengeId = challengeId,
            paymentIntentId = paymentIntentId,
            paymentIntentCreatedAt = System.currentTimeMillis(),
            isImmediateCapture = if (s.durationDays > 7) 1 else 0,
            appDisplayName = displayName(),
            appPackageNames = appPackagesHard.joinToString(","),
            limitType = fields.limitType.name,
            limitValueMinutes = fields.limitValueMinutes,
            limitValueSessions = fields.limitValueSessions,
            durationDays = s.durationDays,
            amountCents = s.amountEuros * 100,
            customMotivation = s.motivationText.ifBlank { null },
            blockedDomains = computeBlockedDomains().joinToString(","),
            partialBlockDomains = "",
            blockingType = (if (!isWebsiteTab) BlockingType.APP else BlockingType.WEBSITE).name,
            blockAdultContent = if (fields.blockAdultContent) 1 else 0,
            scheduleStartTime = fields.scheduleStartTime,
            scheduleEndTime = fields.scheduleEndTime,
            activeDays = fields.activeDays.joinToString(","),
            sessionDurationMinutes = s.sessionDurationMinutes,
            dailyBudgetMinutes = fields.dailyBudgetMinutes,
            partialBlockSections = "",
            isPartialBlockOnly = 0,
            deviceId = androidId,
            isRooted = if (deviceRooted) 1 else 0,
        )
    }

    fun onPaymentConfirmed() {
        // A redundant result callback after the challenge is already created must never downgrade a
        // shown Success into an error (the record is gone by then).
        if (_uiState.value is ChallengeCreationUiState.Success) return
        viewModelScope.launch {
            // Source the payload from the DURABLE record, NOT the in-memory fields / _state — those
            // are lost if the ViewModel/Activity/process was recreated while the Stripe activity was
            // foreground. Prefer the exact cid; fall back to the latest pending record.
            val pending = (confirmedChallengeId?.let { pendingHardChallengeDao.getByChallengeId(it) })
                ?: pendingHardChallengeDao.getLatest()

            if (pending == null) {
                // MONEY-CRITICAL (Part C): never return silently. If a PI was confirmed but we have
                // no recoverable payload, the stake may be captured with no way to rebuild — surface
                // it loudly (Sentry + error UI). The startup recovery net cannot help without a record.
                val pi = confirmedPaymentIntentId
                Timber.e(
                    "onPaymentConfirmed: NO PendingHardChallenge record (confirmedPI=%s) — cannot rebuild challenge after payment",
                    pi,
                )
                Sentry.captureMessage(
                    "onPaymentConfirmed: payment confirmed but no PendingHardChallenge record" +
                        (pi?.let { " (PI=$it)" } ?: " (PI unknown)")
                )
                _uiState.value = ChallengeCreationUiState.Error(
                    context.getString(R.string.challenge_create_sync_failed)
                )
                return@launch
            }

            _uiState.value = ChallengeCreationUiState.Loading
            // Rebuild via CreateChallengeUseCase (keeps validation + date logic) from the record. The
            // unified challengeId + existing PI mean this writes the doc under the SAME cid as the
            // PaymentIntent — idempotent, and it NEVER creates a new PaymentIntent.
            createChallengeUseCase(
                appPackageName = pending.appPackageNames.toCsvList().firstOrNull(),
                appDisplayName = pending.appDisplayName,
                limitType = LimitType.valueOf(pending.limitType.uppercase()),
                limitValueMinutes = pending.limitValueMinutes,
                limitValueSessions = pending.limitValueSessions,
                durationDays = pending.durationDays,
                customMotivation = pending.customMotivation,
                mode = ChallengeMode.HARD,
                amountCents = pending.amountCents,
                stripePaymentIntentId = pending.paymentIntentId,
                appPackageNames = pending.appPackageNames.toCsvList(),
                blockedDomains = pending.blockedDomains.toCsvList(),
                partialBlockDomains = pending.partialBlockDomains.toCsvList(),
                blockingType = runCatching { BlockingType.valueOf(pending.blockingType.uppercase()) }
                    .getOrDefault(BlockingType.APP),
                blockAdultContent = pending.blockAdultContent != 0,
                scheduleStartTime = pending.scheduleStartTime,
                scheduleEndTime = pending.scheduleEndTime,
                activeDays = pending.activeDays.toCsvList(),
                sessionDurationMinutes = pending.sessionDurationMinutes,
                dailyBudgetMinutes = pending.dailyBudgetMinutes,
                partialBlockSections = emptyList(),
                isPartialBlockOnly = pending.isPartialBlockOnly != 0,
                deviceId = pending.deviceId,
                isRooted = pending.isRooted?.let { it != 0 },
                // Unify the id: persist under the same cid the PaymentIntent was created with.
                challengeId = pending.challengeId,
            ).fold(
                onSuccess = { result ->
                    // Doc confirmed written → the durable record has done its job; remove it so the
                    // startup recovery net doesn't re-process it.
                    runCatching { pendingHardChallengeDao.deleteByChallengeId(pending.challengeId) }
                        .onFailure { e -> Timber.w(e, "onPaymentConfirmed: failed to clear pending record ${pending.challengeId}") }
                    // Legal: persist the FAGG § 18 withdrawal-rights waiver consent alongside the
                    // challenge doc (merge update of two fields onto the now-existing doc).
                    logWithdrawalWaiver(result.challengeId)
                    analyticsService.logChallengeCreated(
                        mode = "hard",
                        limitType = pending.limitType.lowercase(),
                        durationDays = pending.durationDays,
                    )
                    UsageTrackingService.start(context)
                    confirmedPaymentIntentId = null
                    confirmedChallengeId = null
                    _uiState.value = ChallengeCreationUiState.Success(result.challengeId)
                },
                onFailure = { error ->
                    // Payment already succeeded; the realistic failure here is the awaited Hard Mode
                    // Firestore mirror create. KEEP the durable record so the startup recovery net
                    // retries (idempotent, no new PI). Surface a clear message; never fake success.
                    Timber.e(error, "onPaymentConfirmed: Hard Mode persistence failed — record kept for recovery (${pending.challengeId})")
                    _uiState.value = ChallengeCreationUiState.Error(
                        context.getString(R.string.challenge_create_sync_failed)
                    )
                },
            )
        }
    }

    fun onPaymentCancelled() {
        // The PaymentSheet was cancelled / failed → no capture. Discard the durable record so the
        // recovery net never re-creates a challenge for an un-charged attempt. Fall back to the latest
        // record when the in-memory cid was lost on recreation (symmetric with onPaymentConfirmed).
        val cid = confirmedChallengeId
        confirmedPaymentIntentId = null
        confirmedChallengeId = null
        viewModelScope.launch {
            runCatching {
                val record = (cid?.let { pendingHardChallengeDao.getByChallengeId(it) })
                    ?: pendingHardChallengeDao.getLatest()
                record?.let { pendingHardChallengeDao.deleteByChallengeId(it.challengeId) }
            }.onFailure { e -> Timber.w(e, "onPaymentCancelled: failed to clear pending record") }
        }
        _uiState.value = ChallengeCreationUiState.Idle
    }

    private fun String.toCsvList(): List<String> =
        split(",").map { it.trim() }.filter { it.isNotBlank() }
}
