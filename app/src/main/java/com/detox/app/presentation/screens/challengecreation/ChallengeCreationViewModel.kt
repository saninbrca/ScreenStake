package com.detox.app.presentation.screens.challengecreation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.BuildConfig
import com.detox.app.R
import com.detox.app.data.remote.firebase.AnalyticsService
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.model.BlockingType
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.PartialBlockSection
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.domain.usecase.CreateChallengeUseCase
import com.detox.app.domain.usecase.GetAddictiveAppsUseCase
import com.detox.app.domain.usecase.ProcessPaymentUseCase
import com.detox.app.service.UsageTrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/**
 * Maps a URL path prefix → human-readable feature name.
 * Used for PARTIAL_BLOCK: only the specific path is blocked, not the entire domain.
 * The parent domain (before the first '/') must NOT already be in [blockedDomains] for
 * the toggle to be shown — if the whole site is already blocked there is nothing to add.
 */
val FEATURE_BLOCK_MAP: Map<String, String> = mapOf(
    "instagram.com/reels"    to "Instagram Reels",
    "instagram.com/stories"  to "Instagram Stories",
    "youtube.com/shorts"     to "YouTube Shorts",
    "tiktok.com/foryou"      to "TikTok For You",
    "facebook.com/watch"     to "Facebook Watch",
    "twitter.com/i/timeline" to "Twitter Timeline",
)

/** App packages that support native in-app section blocking. */
val PARTIAL_SECTION_PACKAGES: Set<String> = PartialBlockSection.SUPPORTED_PACKAGES

const val NO_END_DATE_DAYS = 36500
const val TOTAL_STEPS = 7

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
    /** URL path prefixes selected for partial (feature-level) blocking, e.g. "instagram.com/reels". */
    val partialBlockDomains: Set<String> = emptySet(),
    /** Native in-app section IDs selected for blocking, e.g. "instagram_reels". */
    val partialBlockSections: Set<String> = emptySet(),
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
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class ChallengeCreationViewModel @Inject constructor(
    private val getAddictiveAppsUseCase: GetAddictiveAppsUseCase,
    private val createChallengeUseCase: CreateChallengeUseCase,
    private val processPaymentUseCase: ProcessPaymentUseCase,
    private val challengeRepository: ChallengeRepository,
    private val usageStatsRepository: UsageStatsRepository,
    private val firebaseAuthService: FirebaseAuthService,
    private val analyticsService: AnalyticsService,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ChallengeCreationState())
    val state: StateFlow<ChallengeCreationState> = _state.asStateFlow()

    private val _appListState = MutableStateFlow(AppListState())
    val appListState: StateFlow<AppListState> = _appListState.asStateFlow()

    private val _uiState = MutableStateFlow<ChallengeCreationUiState>(ChallengeCreationUiState.Idle)
    val uiState: StateFlow<ChallengeCreationUiState> = _uiState.asStateFlow()

    private var confirmedPaymentIntentId: String? = null

    init {
        loadApps()
    }

    // ── App loading ───────────────────────────────────────────────────────────

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

    private fun hardModeMinDays(): Int {
        if (!BuildConfig.DEBUG) return 14
        val prefs = context.getSharedPreferences("detox_settings", Context.MODE_PRIVATE)
        return if (prefs.getBoolean("debug_hard_mode_min_1", false)) 1 else 14
    }

    // ── Step 1: Mode ──────────────────────────────────────────────────────────

    fun selectMode(mode: ChallengeMode) {
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

    fun updateBlockAdultContent(enabled: Boolean) =
        _state.update { it.copy(blockAdultContent = enabled) }

    fun togglePartialBlockDomain(path: String) {
        _state.update { s ->
            val updated = if (s.partialBlockDomains.contains(path))
                s.partialBlockDomains - path
            else
                s.partialBlockDomains + path
            s.copy(partialBlockDomains = updated)
        }
    }

    fun togglePartialSection(sectionId: String) {
        _state.update { s ->
            val updated = if (sectionId in s.partialBlockSections)
                s.partialBlockSections - sectionId
            else
                s.partialBlockSections + sectionId
            s.copy(partialBlockSections = updated)
        }
    }

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
                durationDays = if (enabled) NO_END_DATE_DAYS else 7,
                durationError = null,
            )
        }
    }

    fun updateAmountEuros(euros: Int) = _state.update { it.copy(amountEuros = euros) }

    // ── Step 7: Confirm ───────────────────────────────────────────────────────

    fun updateMotivationText(text: String) = _state.update { it.copy(motivationText = text) }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun goBack() = _state.update { s -> s.copy(currentStep = (s.currentStep - 1).coerceAtLeast(1)) }
    fun goNext() = _state.update { s -> s.copy(currentStep = (s.currentStep + 1).coerceAtMost(TOTAL_STEPS)) }

    fun canGoNext(): Boolean {
        val s = _state.value
        val conflicts = _appListState.value.conflictingPackages
        return when (s.currentStep) {
            1 -> s.selectedMode != null
            2 -> (s.selectedApps.isNotEmpty() || s.partialBlockSections.isNotEmpty() ||
                        s.manualDomains.isNotEmpty() || s.blockAdultContent || s.partialBlockDomains.isNotEmpty()) &&
                        s.selectedApps.none { conflicts.containsKey(it) }
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

    private fun computePartialBlockDomains(): List<String> = _state.value.partialBlockDomains.toList()

    private fun computePartialBlockSections(): List<PartialBlockSection> =
        _state.value.partialBlockSections.mapNotNull { PartialBlockSection.fromId(it) }

    // ── Challenge creation ────────────────────────────────────────────────────

    fun createChallenge() {
        firebaseAuthService.logAuthState("ChallengeCreationViewModel.createChallenge")
        if (_state.value.selectedMode == ChallengeMode.HARD) initiateHardModePayment()
        else saveSoftModeChallenge()
    }

    private fun displayName(): String {
        val s = _state.value
        if (s.activeTab == 1) return s.manualDomains.firstOrNull() ?: "Website"
        val firstPkg = s.selectedApps.firstOrNull() ?: return "App"
        return _appListState.value.trackableApps
            .firstOrNull { it.packageName == firstPkg }?.appName ?: firstPkg
    }

    private fun resolveLimitPair(): Pair<Int, Int?> {
        val s = _state.value
        return when (s.limitType) {
            LimitType.TIME        -> s.limitValueMinutes to null
            LimitType.SESSIONS    -> s.sessionDurationMinutes to s.limitValueSessions
            LimitType.TIME_BUDGET -> 0 to null
            LimitType.TIME_WINDOW -> 0 to null
            null -> 0 to null
        }
    }

    private fun saveSoftModeChallenge() {
        val s = _state.value
        val isWebsiteTab = s.activeTab == 1
        _uiState.value = ChallengeCreationUiState.Loading
        viewModelScope.launch {
            val (limitMinutes, limitSessions) = resolveLimitPair()
            val sectionsOnly = !isWebsiteTab && s.selectedApps.isEmpty() && s.partialBlockSections.isNotEmpty()
            val appPackages = if (!isWebsiteTab) {
                if (sectionsOnly) computePartialBlockSections().map { it.appPackage }.distinct()
                else s.selectedApps.toList()
            } else emptyList()
            createChallengeUseCase(
                appPackageName = appPackages.firstOrNull(),
                appDisplayName = displayName(),
                limitType = s.limitType ?: LimitType.TIME,
                limitValueMinutes = limitMinutes,
                limitValueSessions = limitSessions,
                durationDays = if (s.noEndDate) NO_END_DATE_DAYS else s.durationDays,
                customMotivation = s.motivationText.ifBlank { null },
                mode = ChallengeMode.SOFT,
                appPackageNames = appPackages,
                blockedDomains = computeBlockedDomains(),
                partialBlockDomains = computePartialBlockDomains(),
                blockingType = if (!isWebsiteTab) BlockingType.APP else BlockingType.WEBSITE,
                blockAdultContent = s.blockAdultContent,
                scheduleStartTime = s.scheduleStart.takeIf { it.length == 5 },
                scheduleEndTime = s.scheduleEnd.takeIf { it.length == 5 },
                activeDays = s.activeDays.toList(),
                sessionDurationMinutes = s.sessionDurationMinutes,
                dailyBudgetMinutes = if (s.limitType == LimitType.TIME_BUDGET) s.dailyBudgetMinutes else null,
                partialBlockSections = computePartialBlockSections(),
                isPartialBlockOnly = sectionsOnly,
            ).fold(
                onSuccess = { result ->
                    analyticsService.logChallengeCreated(
                        mode = "soft",
                        limitType = (s.limitType ?: LimitType.TIME).name.lowercase(),
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

    fun onPaymentConfirmed() {
        val s = _state.value
        val isWebsiteTab = s.activeTab == 1
        val paymentIntentId = confirmedPaymentIntentId ?: return
        _uiState.value = ChallengeCreationUiState.Loading
        viewModelScope.launch {
            val (limitMinutes, limitSessions) = resolveLimitPair()
            val sectionsOnlyHard = !isWebsiteTab && s.selectedApps.isEmpty() && s.partialBlockSections.isNotEmpty()
            val appPackagesHard = if (!isWebsiteTab) {
                if (sectionsOnlyHard) computePartialBlockSections().map { it.appPackage }.distinct()
                else s.selectedApps.toList()
            } else emptyList()
            createChallengeUseCase(
                appPackageName = appPackagesHard.firstOrNull(),
                appDisplayName = displayName(),
                limitType = s.limitType ?: LimitType.TIME,
                limitValueMinutes = limitMinutes,
                limitValueSessions = limitSessions,
                durationDays = s.durationDays,
                customMotivation = s.motivationText.ifBlank { null },
                mode = ChallengeMode.HARD,
                amountCents = s.amountEuros * 100,
                stripePaymentIntentId = paymentIntentId,
                appPackageNames = appPackagesHard,
                blockedDomains = computeBlockedDomains(),
                partialBlockDomains = computePartialBlockDomains(),
                blockingType = if (!isWebsiteTab) BlockingType.APP else BlockingType.WEBSITE,
                blockAdultContent = s.blockAdultContent,
                scheduleStartTime = s.scheduleStart.takeIf { it.length == 5 },
                scheduleEndTime = s.scheduleEnd.takeIf { it.length == 5 },
                activeDays = s.activeDays.toList(),
                sessionDurationMinutes = s.sessionDurationMinutes,
                dailyBudgetMinutes = if (s.limitType == LimitType.TIME_BUDGET) s.dailyBudgetMinutes else null,
                partialBlockSections = computePartialBlockSections(),
                isPartialBlockOnly = sectionsOnlyHard,
            ).fold(
                onSuccess = { result ->
                    analyticsService.logChallengeCreated(
                        mode = "hard",
                        limitType = (s.limitType ?: LimitType.TIME).name.lowercase(),
                        durationDays = s.durationDays,
                    )
                    UsageTrackingService.start(context)
                    _uiState.value = ChallengeCreationUiState.Success(result.challengeId)
                },
                onFailure = { error ->
                    _uiState.value = ChallengeCreationUiState.Error(error.message ?: "Failed to save challenge")
                },
            )
        }
    }

    fun onPaymentCancelled() {
        confirmedPaymentIntentId = null
        _uiState.value = ChallengeCreationUiState.Idle
    }
}
