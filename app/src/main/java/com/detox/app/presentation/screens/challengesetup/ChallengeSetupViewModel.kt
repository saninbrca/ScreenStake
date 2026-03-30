package com.detox.app.presentation.screens.challengesetup

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.usecase.CreateChallengeUseCase
import com.detox.app.domain.usecase.ProcessPaymentUseCase
import com.detox.app.service.UsageTrackingService
import com.google.firebase.auth.FirebaseAuth
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
    val amountEuros: Int = 10
)

sealed interface ChallengeSetupUiState {
    data object Idle : ChallengeSetupUiState
    data object Loading : ChallengeSetupUiState

    /** Payment has been prepared — present Stripe PaymentSheet with this client secret. */
    data class AwaitingPayment(val clientSecret: String, val pendingChallengeId: String) :
        ChallengeSetupUiState

    /** Challenge created successfully. For Hard Mode, show the emergency code before navigating. */
    data class ShowEmergencyCode(val code: String, val challengeId: String) :
        ChallengeSetupUiState

    data class Success(val challengeId: String) : ChallengeSetupUiState
    data class Error(val message: String) : ChallengeSetupUiState
}

@HiltViewModel
class ChallengeSetupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val createChallengeUseCase: CreateChallengeUseCase,
    private val processPaymentUseCase: ProcessPaymentUseCase,
    private val firebaseAuthService: FirebaseAuthService,
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

    /**
     * The email of the currently signed-in Firebase user, or null if not signed in.
     * Exposed so the screen can show a diagnostic auth-state banner.
     */
    val currentUserEmail: String?
        get() = FirebaseAuth.getInstance().currentUser?.email

    /** Holds the paymentIntentId after payment sheet confirms, until challenge is saved. */
    private var confirmedPaymentIntentId: String? = null
    private var isImmediateCapture: Boolean = false

    // ── Form updates ────────────────────────────────────────────────────────────

    fun updateLimitType(limitType: LimitType) = _formState.update { it.copy(limitType = limitType) }
    fun updateLimitMinutes(minutes: Int) = _formState.update { it.copy(limitMinutes = minutes) }
    fun updateLimitSessions(sessions: Int) = _formState.update { it.copy(limitSessions = sessions) }
    fun updateSessionMinutes(minutes: Int) = _formState.update { it.copy(sessionMinutes = minutes) }
    fun updateDurationDays(days: Int) = _formState.update { it.copy(durationDays = days) }
    fun updateMotivationText(text: String) = _formState.update { it.copy(motivationText = text) }
    fun updateMode(mode: ChallengeMode) = _formState.update { it.copy(mode = mode) }
    fun updateAmountEuros(euros: Int) = _formState.update { it.copy(amountEuros = euros) }

    // ── Main action ─────────────────────────────────────────────────────────────

    fun createChallenge() {
        val form = _formState.value

        // ── Auth diagnostic log ────────────────────────────────────────────────
        // Uses both FirebaseAuth.getInstance() (direct SDK check) and the service
        // wrapper so we can confirm they agree and spot any DI misconfiguration.
        val rawUser = FirebaseAuth.getInstance().currentUser
        if (rawUser == null) {
            Timber.w("createChallenge: FirebaseAuth.getInstance().currentUser = NULL — " +
                    "Cloud Function will reject with UNAUTHENTICATED")
        } else {
            Timber.d("createChallenge: FirebaseAuth.getInstance().currentUser — " +
                    "uid=%s email=%s emailVerified=%s",
                rawUser.uid, rawUser.email, rawUser.isEmailVerified)
        }
        firebaseAuthService.logAuthState("ChallengeSetupViewModel.createChallenge")
        // ──────────────────────────────────────────────────────────────────────

        if (form.mode == ChallengeMode.HARD) {
            initiateHardModePayment(form)
        } else {
            saveSoftModeChallenge(form)
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
            // Generate a temporary challenge ID used as idempotency key for the payment
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
     * Saves the challenge to Room with the payment intent ID.
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
                    UsageTrackingService.start(context)
                    val code = result.emergencyCode
                    if (code != null) {
                        _uiState.value =
                            ChallengeSetupUiState.ShowEmergencyCode(code, result.challengeId)
                    } else {
                        _uiState.value = ChallengeSetupUiState.Success(result.challengeId)
                    }
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

    fun onEmergencyCodeConfirmed() {
        val current = _uiState.value
        if (current is ChallengeSetupUiState.ShowEmergencyCode) {
            _uiState.value = ChallengeSetupUiState.Success(current.challengeId)
        }
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
