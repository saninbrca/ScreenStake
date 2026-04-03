package com.detox.app.presentation.screens.challengesetup

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.R
import com.detox.app.data.remote.firebase.AnalyticsService
import com.detox.app.data.remote.firebase.FirebaseAuthService
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

data class ChallengeSetupFormState(
    val packageName: String = "",
    val displayName: String = "",
    val limitType: LimitType = LimitType.TIME,
    val limitMinutes: Int = 60,
    val limitSessions: Int = 5,
    val sessionMinutes: Int = 5,
    val durationDays: Int = 7,
    val motivationText: String = "",
    val mode: ChallengeMode = ChallengeMode.SOFT,
    /** Amount in whole Euros (€5–€50). Converted to cents when creating challenge. */
    val amountEuros: Int = 10,
    /** Average daily minutes over the last 14 days — used as the upper bound for TIME limit. */
    val avgDailyMinutes: Int = 0,
    /** Per-field validation errors; null = valid. */
    val limitMinutesError: String? = null,
    val limitSessionsError: String? = null,
    val sessionMinutesError: String? = null
)

sealed interface ChallengeSetupUiState {
    data object Idle : ChallengeSetupUiState
    data object Loading : ChallengeSetupUiState

    /** Payment has been prepared — present Stripe PaymentSheet with this client secret. */
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

    private val _formState = MutableStateFlow(
        ChallengeSetupFormState(
            packageName = savedStateHandle.get<String>("packageName") ?: "",
            displayName = savedStateHandle.get<String>("displayName") ?: ""
        )
    )
    val formState: StateFlow<ChallengeSetupFormState> = _formState.asStateFlow()

    private val _uiState = MutableStateFlow<ChallengeSetupUiState>(ChallengeSetupUiState.Idle)
    val uiState: StateFlow<ChallengeSetupUiState> = _uiState.asStateFlow()

    /** Holds the paymentIntentId after payment sheet confirms, until challenge is saved. */
    private var confirmedPaymentIntentId: String? = null
    private var isImmediateCapture: Boolean = false

    init {
        val packageName = savedStateHandle.get<String>("packageName")

        if (packageName != null) {
            // Block challenge creation if the app is already being tracked
            viewModelScope.launch {
                val existing = challengeRepository.getActiveChallengeForApp(packageName)
                if (existing.getOrNull() != null) {
                    val displayName = savedStateHandle.get<String>("displayName") ?: packageName
                    _uiState.value = ChallengeSetupUiState.Error(
                        "You're already tracking $displayName. Abandon the existing challenge first."
                    )
                }
            }

            // Load the 14-day average to use as the upper bound for the TIME limit field
            viewModelScope.launch {
                val stats = usageStatsRepository.getAppUsageStats(14)
                val avg = stats
                    .firstOrNull { it.packageName == packageName }
                    ?.avgDailyMinutes
                    ?.toInt()
                    ?: 0
                Timber.d("ChallengeSetup: avgDailyMinutes=$avg for $packageName")
                _formState.update { it.copy(avgDailyMinutes = avg) }
            }
        }
    }

    // ── Form updates ────────────────────────────────────────────────────────────

    fun updateLimitType(limitType: LimitType) = _formState.update { it.copy(limitType = limitType) }

    fun updateLimitMinutes(minutes: Int) {
        val avg = _formState.value.avgDailyMinutes
        val error = when {
            minutes < 5 ->
                context.getString(R.string.challenge_setup_error_min_minutes)
            avg > 0 && minutes >= avg ->
                context.getString(R.string.challenge_setup_error_max_minutes, avg)
            else -> null
        }
        _formState.update { it.copy(limitMinutes = minutes, limitMinutesError = error) }
    }

    fun updateLimitSessions(sessions: Int) {
        val error = if (sessions < 1) {
            context.getString(R.string.challenge_setup_error_min_sessions)
        } else null
        _formState.update { it.copy(limitSessions = sessions, limitSessionsError = error) }
    }

    fun updateSessionMinutes(minutes: Int) {
        val error = if (minutes < 1) {
            context.getString(R.string.challenge_setup_error_min_session_mins)
        } else null
        _formState.update { it.copy(sessionMinutes = minutes, sessionMinutesError = error) }
    }

    fun updateDurationDays(days: Int) = _formState.update { it.copy(durationDays = days) }
    fun updateMotivationText(text: String) = _formState.update { it.copy(motivationText = text) }
    fun updateMode(mode: ChallengeMode) = _formState.update { it.copy(mode = mode) }
    fun updateAmountEuros(euros: Int) = _formState.update { it.copy(amountEuros = euros) }

    // ── Main action ─────────────────────────────────────────────────────────────

    fun createChallenge() {
        val form = _formState.value
        firebaseAuthService.logAuthState("ChallengeSetupViewModel.createChallenge")

        // Validate limit fields — trigger errors on any fields that haven't been
        // touched yet so the user can see exactly what needs fixing.
        if (!validateLimitFields(form)) {
            _uiState.value = ChallengeSetupUiState.Error(
                context.getString(R.string.challenge_setup_error_fix_limits)
            )
            return
        }

        if (form.mode == ChallengeMode.HARD) {
            initiateHardModePayment(form)
        } else {
            saveSoftModeChallenge(form)
        }
    }

    /** Returns true if all limit fields are valid. Also writes errors to formState. */
    private fun validateLimitFields(form: ChallengeSetupFormState): Boolean {
        return when (form.limitType) {
            LimitType.TIME -> {
                // Re-run the update to ensure error state is set
                updateLimitMinutes(form.limitMinutes)
                _formState.value.limitMinutesError == null
            }
            LimitType.SESSIONS -> {
                updateLimitSessions(form.limitSessions)
                updateSessionMinutes(form.sessionMinutes)
                _formState.value.limitSessionsError == null &&
                        _formState.value.sessionMinutesError == null
            }
        }
    }

    private fun saveSoftModeChallenge(form: ChallengeSetupFormState) {
        _uiState.value = ChallengeSetupUiState.Loading
        viewModelScope.launch {
            val (limitMinutes, limitSessions) = resolveLimitValues(form)
            createChallengeUseCase(
                appPackageName = form.packageName,
                appDisplayName = form.displayName,
                limitType = form.limitType,
                limitValueMinutes = limitMinutes,
                limitValueSessions = limitSessions,
                durationDays = form.durationDays,
                customMotivation = form.motivationText.ifBlank { null },
                mode = ChallengeMode.SOFT
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
                    _uiState.value =
                        ChallengeSetupUiState.Error(error.message ?: "Failed to create challenge")
                }
            )
        }
    }

    /**
     * Step 1 of Hard Mode: prepare the Stripe PaymentIntent.
     * Emits [ChallengeSetupUiState.AwaitingPayment] so the screen can open PaymentSheet.
     */
    private fun initiateHardModePayment(form: ChallengeSetupFormState) {
        _uiState.value = ChallengeSetupUiState.Loading
        viewModelScope.launch {
            val tempChallengeId = java.util.UUID.randomUUID().toString()
            val amountCents = form.amountEuros * 100

            processPaymentUseCase(
                amountCents = amountCents,
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
                    _uiState.value =
                        ChallengeSetupUiState.Error(error.message ?: "Payment setup failed")
                }
            )
        }
    }

    /**
     * Step 2 of Hard Mode: called after Stripe PaymentSheet confirms successfully.
     */
    fun onPaymentConfirmed() {
        val form = _formState.value
        val paymentIntentId = confirmedPaymentIntentId ?: return
        _uiState.value = ChallengeSetupUiState.Loading

        viewModelScope.launch {
            val (limitMinutes, limitSessions) = resolveLimitValues(form)
            createChallengeUseCase(
                appPackageName = form.packageName,
                appDisplayName = form.displayName,
                limitType = form.limitType,
                limitValueMinutes = limitMinutes,
                limitValueSessions = limitSessions,
                durationDays = form.durationDays,
                customMotivation = form.motivationText.ifBlank { null },
                mode = ChallengeMode.HARD,
                amountCents = form.amountEuros * 100,
                stripePaymentIntentId = paymentIntentId
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
                    _uiState.value =
                        ChallengeSetupUiState.Error(error.message ?: "Failed to save challenge")
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
        }
        val limitSessions = when (form.limitType) {
            LimitType.TIME -> null
            LimitType.SESSIONS -> form.limitSessions
        }
        return limitMinutes to limitSessions
    }
}
