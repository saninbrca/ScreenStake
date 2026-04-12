package com.detox.app.presentation.screens.challengesetup

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.R
import com.detox.app.data.remote.firebase.AnalyticsService
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.model.BlockingType
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.domain.usecase.CreateChallengeUseCase
import com.detox.app.domain.usecase.ProcessPaymentUseCase
import com.detox.app.service.UsageTrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Maps common app package names to their associated web domain. */
val APP_DOMAIN_MAP = mapOf(
    "com.instagram.android" to "instagram.com",
    "com.zhiliaoapp.musically" to "tiktok.com",
    "com.twitter.android" to "twitter.com",
    "com.reddit.frontpage" to "reddit.com",
    "com.facebook.katana" to "facebook.com",
    "com.snapchat.android" to "snapchat.com",
    "com.google.android.youtube" to "youtube.com",
    "com.netflix.mediaclient" to "netflix.com",
    "tv.twitch.android.app" to "twitch.tv",
    "com.threads.android" to "threads.net",
    "com.discord" to "discord.com",
    "com.pinterest" to "pinterest.com",
    "com.linkedin.android" to "linkedin.com",
)

data class ChallengeSetupFormState(
    val blockingType: BlockingType = BlockingType.APP,
    // APP mode
    val packageNames: List<String> = emptyList(),
    val displayName: String = "",
    /** Per-package domain blocking toggle: packageName → enabled. Default true for detected domains. */
    val domainToggles: Map<String, Boolean> = emptyMap(),
    // WEBSITE mode — pre-filled from BlockWebsiteScreen
    val blockedDomains: List<String> = emptyList(),
    val blockAdultContent: Boolean = false,
    // Common
    val customDomainInput: String = "",
    val customDomains: List<String> = emptyList(),
    // Schedule
    val scheduleStartTime: String = "",
    val scheduleEndTime: String = "",
    val activeDays: Set<String> = emptySet(),
    // Limits
    val limitType: LimitType = LimitType.TIME,
    val limitMinutes: Int = 60,
    val limitSessions: Int = 5,
    val sessionMinutes: Int = 5,
    val dailyBudgetMinutes: Int = 39,
    val durationDays: Int = 7,
    val motivationText: String = "",
    val mode: ChallengeMode = ChallengeMode.SOFT,
    val amountEuros: Int = 10,
    val avgDailyMinutes: Int = 0,
    val limitMinutesError: String? = null,
    val limitSessionsError: String? = null,
    val sessionMinutesError: String? = null,
    val dailyBudgetMinutesError: String? = null,
)

sealed interface ChallengeSetupUiState {
    data object Idle : ChallengeSetupUiState
    data object Loading : ChallengeSetupUiState
    data class AwaitingPayment(val clientSecret: String, val pendingChallengeId: String) :
        ChallengeSetupUiState
    data class Success(val challengeId: String) : ChallengeSetupUiState
    data class Error(val message: String) : ChallengeSetupUiState
}

@HiltViewModel
class ChallengeSetupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val createChallengeUseCase: CreateChallengeUseCase,
    private val processPaymentUseCase: ProcessPaymentUseCase,
    private val challengeRepository: ChallengeRepository,
    private val usageStatsRepository: UsageStatsRepository,
    private val firebaseAuthService: FirebaseAuthService,
    private val analyticsService: AnalyticsService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _formState: MutableStateFlow<ChallengeSetupFormState>
    val formState: StateFlow<ChallengeSetupFormState> get() = _formState.asStateFlow()

    private val _uiState = MutableStateFlow<ChallengeSetupUiState>(ChallengeSetupUiState.Idle)
    val uiState: StateFlow<ChallengeSetupUiState> = _uiState.asStateFlow()

    private var confirmedPaymentIntentId: String? = null
    private var isImmediateCapture: Boolean = false

    init {
        val typeStr = savedStateHandle.get<String>("blockingType") ?: "APP"
        val type = runCatching { BlockingType.valueOf(typeStr.uppercase()) }
            .getOrDefault(BlockingType.APP)

        val initialState = when (type) {
            BlockingType.APP -> {
                val packageNamesStr = savedStateHandle.get<String>("packageNames") ?: ""
                val packages = packageNamesStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val displayName = savedStateHandle.get<String>("displayName") ?: ""
                // Build domain toggles: one entry per package that has a known domain
                val toggles = packages
                    .filter { APP_DOMAIN_MAP.containsKey(it) }
                    .associate { it to true }
                ChallengeSetupFormState(
                    blockingType = BlockingType.APP,
                    packageNames = packages,
                    displayName = displayName,
                    domainToggles = toggles,
                )
            }
            BlockingType.WEBSITE -> {
                val domainsStr = savedStateHandle.get<String>("blockedDomains") ?: ""
                val domains = domainsStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val blockAdult = savedStateHandle.get<String>("blockAdultContent") == "true"
                ChallengeSetupFormState(
                    blockingType = BlockingType.WEBSITE,
                    blockedDomains = domains,
                    blockAdultContent = blockAdult,
                )
            }
        }
        _formState = MutableStateFlow(initialState)

        // For APP mode: load 14-day avg usage for the first package (limit validation)
        if (type == BlockingType.APP) {
            val packages = initialState.packageNames
            if (packages.isNotEmpty()) {
                viewModelScope.launch {
                    val existing = challengeRepository.getActiveChallengeForApp(packages.first())
                    if (existing.getOrNull() != null) {
                        _uiState.value = ChallengeSetupUiState.Error(
                            "You're already tracking ${initialState.displayName}. Abandon the existing challenge first."
                        )
                    }
                }
                viewModelScope.launch {
                    val stats = usageStatsRepository.getAppUsageStats(14)
                    val avg = stats.firstOrNull { it.packageName == packages.first() }
                        ?.avgDailyMinutes?.toInt() ?: 0
                    Timber.d("ChallengeSetup: avgDailyMinutes=$avg for ${packages.first()}")
                    _formState.update { it.copy(avgDailyMinutes = avg) }
                }
            }
        }
    }

    // ── Form updates ────────────────────────────────────────────────────────────

    fun updateLimitType(limitType: LimitType) =
        _formState.update { it.copy(limitType = limitType) }

    fun updateDailyBudgetMinutes(minutes: Int) {
        val avg = _formState.value.avgDailyMinutes
        val error = when {
            minutes < 1 -> context.getString(R.string.challenge_setup_error_min_budget)
            avg > 0 && minutes >= avg -> context.getString(R.string.challenge_setup_error_max_minutes, avg)
            else -> null
        }
        _formState.update { it.copy(dailyBudgetMinutes = minutes, dailyBudgetMinutesError = error) }
    }

    fun updateLimitMinutes(minutes: Int) {
        val avg = _formState.value.avgDailyMinutes
        val error = when {
            minutes < 5 -> context.getString(R.string.challenge_setup_error_min_minutes)
            avg > 0 && minutes >= avg -> context.getString(R.string.challenge_setup_error_max_minutes, avg)
            else -> null
        }
        _formState.update { it.copy(limitMinutes = minutes, limitMinutesError = error) }
    }

    fun updateLimitSessions(sessions: Int) {
        val error = if (sessions < 1) context.getString(R.string.challenge_setup_error_min_sessions) else null
        _formState.update { it.copy(limitSessions = sessions, limitSessionsError = error) }
    }

    fun updateSessionMinutes(minutes: Int) {
        val error = if (minutes < 5) context.getString(R.string.challenge_setup_error_min_session_mins) else null
        _formState.update { it.copy(sessionMinutes = minutes, sessionMinutesError = error) }
    }

    fun updateBlockAdultContent(enabled: Boolean) {
        _formState.update { form ->
            form.copy(
                blockAdultContent = enabled,
                // Adult-content challenges have no usage limit — switch to TIME_WINDOW so
                // validateLimitFields skips all usage-limit checks.
                limitType = if (enabled) LimitType.TIME_WINDOW else form.limitType
            )
        }
    }

    fun updateDurationDays(days: Int) = _formState.update { it.copy(durationDays = days) }
    fun updateMotivationText(text: String) = _formState.update { it.copy(motivationText = text) }
    fun updateMode(mode: ChallengeMode) = _formState.update { it.copy(mode = mode) }
    fun updateAmountEuros(euros: Int) = _formState.update { it.copy(amountEuros = euros) }

    // ── Domain toggles (APP mode) ───────────────────────────────────────────────

    fun toggleDomain(packageName: String) {
        _formState.update { form ->
            val current = form.domainToggles[packageName] ?: true
            form.copy(domainToggles = form.domainToggles + (packageName to !current))
        }
    }

    // ── Custom domains ──────────────────────────────────────────────────────────

    fun updateCustomDomainInput(domain: String) =
        _formState.update { it.copy(customDomainInput = domain) }

    fun addCustomDomain() {
        val domain = _formState.value.customDomainInput.trim().lowercase()
        if (domain.isBlank()) return
        _formState.update { form ->
            if (!form.customDomains.contains(domain)) {
                form.copy(customDomains = form.customDomains + domain, customDomainInput = "")
            } else {
                form.copy(customDomainInput = "")
            }
        }
    }

    fun removeCustomDomain(domain: String) =
        _formState.update { it.copy(customDomains = it.customDomains - domain) }

    // ── Schedule ────────────────────────────────────────────────────────────────

    fun updateScheduleStartTime(time: String) =
        _formState.update { it.copy(scheduleStartTime = time) }

    fun updateScheduleEndTime(time: String) =
        _formState.update { it.copy(scheduleEndTime = time) }

    fun toggleActiveDay(day: String) {
        _formState.update { form ->
            val days = form.activeDays
            form.copy(activeDays = if (days.contains(day)) days - day else days + day)
        }
    }

    fun clearSchedule() =
        _formState.update { it.copy(scheduleStartTime = "", scheduleEndTime = "", activeDays = emptySet()) }

    // ── Compute blocked domains ─────────────────────────────────────────────────

    private fun computeBlockedDomains(form: ChallengeSetupFormState): List<String> {
        val domains = mutableListOf<String>()
        when (form.blockingType) {
            BlockingType.APP -> {
                form.domainToggles.forEach { (pkg, enabled) ->
                    if (enabled) APP_DOMAIN_MAP[pkg]?.let { domains.add(it) }
                }
            }
            BlockingType.WEBSITE -> {
                domains.addAll(form.blockedDomains)
                // Adult domains are not stored in Room — DetoxVpnService loads them from
                // assets/adult_domains.txt and applies them when blockAdultContent = true.
            }
        }
        domains.addAll(form.customDomains)
        return domains.distinct()
    }

    private fun resolveSchedule(form: ChallengeSetupFormState): Triple<String?, String?, List<String>> {
        val start = form.scheduleStartTime.takeIf { it.length == 5 }  // "HH:mm"
        val end = form.scheduleEndTime.takeIf { it.length == 5 }
        val days = form.activeDays.toList()
        return Triple(start, end, days)
    }

    // ── Main action ─────────────────────────────────────────────────────────────

    fun createChallenge() {
        val form = _formState.value
        firebaseAuthService.logAuthState("ChallengeSetupViewModel.createChallenge")

        if (!validateLimitFields(form)) {
            _uiState.value = ChallengeSetupUiState.Error(
                context.getString(R.string.challenge_setup_error_fix_limits)
            )
            return
        }

        if (form.mode == ChallengeMode.HARD) initiateHardModePayment(form)
        else saveSoftModeChallenge(form)
    }

    private fun validateLimitFields(form: ChallengeSetupFormState): Boolean {
        // Adult-content challenges are always fully blocked via VPN — no usage limit needed.
        if (form.blockAdultContent) return true

        return when (form.limitType) {
            LimitType.TIME -> {
                updateLimitMinutes(form.limitMinutes)
                _formState.value.limitMinutesError == null
            }
            LimitType.SESSIONS -> {
                updateLimitSessions(form.limitSessions)
                updateSessionMinutes(form.sessionMinutes)
                _formState.value.limitSessionsError == null && _formState.value.sessionMinutesError == null
            }
            LimitType.TIME_BUDGET -> {
                updateDailyBudgetMinutes(form.dailyBudgetMinutes)
                _formState.value.dailyBudgetMinutesError == null
            }
            // TIME_WINDOW: schedule is the constraint — must have a valid start + end time.
            LimitType.TIME_WINDOW -> {
                val hasSchedule = form.scheduleStartTime.length == 5 && form.scheduleEndTime.length == 5
                if (!hasSchedule) {
                    _uiState.value = ChallengeSetupUiState.Error(
                        context.getString(R.string.challenge_setup_error_schedule_required)
                    )
                }
                hasSchedule
            }
        }
    }

    private fun buildDisplayName(form: ChallengeSetupFormState): String {
        if (form.blockingType == BlockingType.APP) return form.displayName
        return form.blockedDomains.firstOrNull() ?: "Website"
    }

    private fun saveSoftModeChallenge(form: ChallengeSetupFormState) {
        _uiState.value = ChallengeSetupUiState.Loading
        viewModelScope.launch {
            val (limitMinutes, limitSessions) = resolveLimitValues(form)
            val (schedStart, schedEnd, days) = resolveSchedule(form)
            createChallengeUseCase(
                appPackageName = form.packageNames.firstOrNull(),
                appDisplayName = buildDisplayName(form),
                limitType = form.limitType,
                limitValueMinutes = limitMinutes,
                limitValueSessions = limitSessions,
                durationDays = form.durationDays,
                customMotivation = form.motivationText.ifBlank { null },
                mode = ChallengeMode.SOFT,
                dailyBudgetMinutes = if (form.limitType == LimitType.TIME_BUDGET) form.dailyBudgetMinutes else null,
                appPackageNames = form.packageNames,
                blockedDomains = computeBlockedDomains(form),
                blockingType = form.blockingType,
                blockAdultContent = form.blockAdultContent,
                scheduleStartTime = schedStart,
                scheduleEndTime = schedEnd,
                activeDays = days,
                sessionDurationMinutes = form.sessionMinutes,
            ).fold(
                onSuccess = { result ->
                    analyticsService.logChallengeCreated(
                        mode = "soft",
                        limitType = form.limitType.name.lowercase(),
                        durationDays = form.durationDays
                    )
                    UsageTrackingService.start(context)
                    _uiState.value = ChallengeSetupUiState.Success(result.challengeId)
                },
                onFailure = { error ->
                    _uiState.value = ChallengeSetupUiState.Error(error.message ?: "Failed to create challenge")
                }
            )
        }
    }

    private fun initiateHardModePayment(form: ChallengeSetupFormState) {
        _uiState.value = ChallengeSetupUiState.Loading
        viewModelScope.launch {
            val tempChallengeId = java.util.UUID.randomUUID().toString()
            processPaymentUseCase(
                amountCents = form.amountEuros * 100,
                durationDays = form.durationDays,
                challengeId = tempChallengeId
            ).fold(
                onSuccess = { paymentData ->
                    isImmediateCapture = paymentData.isImmediateCapture
                    confirmedPaymentIntentId = paymentData.paymentIntentId
                    _uiState.value = ChallengeSetupUiState.AwaitingPayment(
                        clientSecret = paymentData.clientSecret,
                        pendingChallengeId = tempChallengeId
                    )
                },
                onFailure = { error ->
                    _uiState.value = ChallengeSetupUiState.Error(error.message ?: "Payment setup failed")
                }
            )
        }
    }

    fun onPaymentConfirmed() {
        val form = _formState.value
        val paymentIntentId = confirmedPaymentIntentId ?: return
        _uiState.value = ChallengeSetupUiState.Loading
        viewModelScope.launch {
            val (limitMinutes, limitSessions) = resolveLimitValues(form)
            val (schedStart, schedEnd, days) = resolveSchedule(form)
            createChallengeUseCase(
                appPackageName = form.packageNames.firstOrNull(),
                appDisplayName = buildDisplayName(form),
                limitType = form.limitType,
                limitValueMinutes = limitMinutes,
                limitValueSessions = limitSessions,
                durationDays = form.durationDays,
                customMotivation = form.motivationText.ifBlank { null },
                mode = ChallengeMode.HARD,
                amountCents = form.amountEuros * 100,
                stripePaymentIntentId = paymentIntentId,
                dailyBudgetMinutes = if (form.limitType == LimitType.TIME_BUDGET) form.dailyBudgetMinutes else null,
                appPackageNames = form.packageNames,
                blockedDomains = computeBlockedDomains(form),
                blockingType = form.blockingType,
                blockAdultContent = form.blockAdultContent,
                scheduleStartTime = schedStart,
                scheduleEndTime = schedEnd,
                activeDays = days,
                sessionDurationMinutes = form.sessionMinutes,
            ).fold(
                onSuccess = { result ->
                    analyticsService.logChallengeCreated(
                        mode = "hard",
                        limitType = form.limitType.name.lowercase(),
                        durationDays = form.durationDays
                    )
                    UsageTrackingService.start(context)
                    _uiState.value = ChallengeSetupUiState.Success(result.challengeId)
                },
                onFailure = { error ->
                    _uiState.value = ChallengeSetupUiState.Error(error.message ?: "Failed to save challenge")
                }
            )
        }
    }

    fun onPaymentCancelled() {
        confirmedPaymentIntentId = null
        _uiState.value = ChallengeSetupUiState.Idle
    }

    private fun resolveLimitValues(form: ChallengeSetupFormState): Pair<Int, Int?> {
        val limitMinutes = when (form.limitType) {
            LimitType.TIME -> form.limitMinutes
            LimitType.SESSIONS -> form.sessionMinutes
            LimitType.TIME_BUDGET -> 0
            LimitType.TIME_WINDOW -> 0
        }
        val limitSessions = when (form.limitType) {
            LimitType.TIME -> null
            LimitType.SESSIONS -> form.limitSessions
            LimitType.TIME_BUDGET -> null
            LimitType.TIME_WINDOW -> null
        }
        return limitMinutes to limitSessions
    }
}
