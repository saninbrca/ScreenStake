package com.detox.app.presentation.screens.groupchallenge.join

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.PaymentIntentData
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.usecase.JoinGroupChallengeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface GroupJoinUiState {
    data object Idle : GroupJoinUiState
    data object LookingUp : GroupJoinUiState
    data class Preview(val groupChallenge: GroupChallenge) : GroupJoinUiState
    /** joinGroupChallenge CF called — waiting for Stripe PaymentSheet to open. */
    data class ProcessingPayment(val groupChallenge: GroupChallenge) : GroupJoinUiState
    /** Stripe PaymentSheet is visible — waiting for user to complete / cancel / fail. */
    data class AwaitingPayment(
        val paymentData: PaymentIntentData,
        val groupChallenge: GroupChallenge,
        val groupId: String,
    ) : GroupJoinUiState
    /** Payment confirmed by Stripe — calling confirmGroupJoin CF. Cannot tap Pay again. */
    data class ConfirmingJoin(val groupChallenge: GroupChallenge) : GroupJoinUiState
    /** confirmGroupJoin succeeded — navigate to Friends tab. */
    data class JoinedSuccessfully(val groupId: String) : GroupJoinUiState
    /**
     * [retryGroupChallenge] is non-null when the error occurred after the payment was captured
     * (confirmGroupJoin CF failed). The card and retry button stay visible so the user can
     * retry without re-entering their code. When null, a snackbar is sufficient.
     */
    data class Error(
        val message: String,
        val retryGroupChallenge: GroupChallenge? = null
    ) : GroupJoinUiState
}

@HiltViewModel
class GroupChallengeJoinViewModel @Inject constructor(
    private val joinGroupChallengeUseCase: JoinGroupChallengeUseCase,
    private val firebaseAuthService: FirebaseAuthService,
    private val challengeRepository: ChallengeRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _codeInput = MutableStateFlow("")
    val codeInput: StateFlow<String> = _codeInput.asStateFlow()

    private val _uiState = MutableStateFlow<GroupJoinUiState>(GroupJoinUiState.Idle)
    val uiState: StateFlow<GroupJoinUiState> = _uiState.asStateFlow()

    // Held across state transitions so confirmJoin can retry without re-paying.
    private var lastAwaitingPayment: GroupJoinUiState.AwaitingPayment? = null

    fun onCodeChanged(code: String) {
        _codeInput.update { code.uppercase().take(6) }
        if (_uiState.value is GroupJoinUiState.Error) _uiState.value = GroupJoinUiState.Idle
    }

    fun lookupCode() {
        val code = _codeInput.value.trim()
        if (code.length != 6) {
            _uiState.value = GroupJoinUiState.Error("Bitte den vollständigen 6-stelligen Code eingeben.")
            return
        }
        val currentUserId = firebaseAuthService.currentUserId() ?: run {
            _uiState.value = GroupJoinUiState.Error("Nicht angemeldet.")
            return
        }
        _uiState.value = GroupJoinUiState.LookingUp
        viewModelScope.launch {
            joinGroupChallengeUseCase.fetchByCode(code, currentUserId).fold(
                onSuccess = { gc ->
                    Timber.d("GroupJoinVM: found groupId=%s", gc.groupId)
                    _uiState.value = GroupJoinUiState.Preview(gc)
                },
                onFailure = { e ->
                    Timber.e(e, "GroupJoinVM: lookup failed code=%s", code)
                    _uiState.value = GroupJoinUiState.Error(e.message ?: "Challenge nicht gefunden.")
                }
            )
        }
    }

    fun initiatePayment(groupChallenge: GroupChallenge) {
        val userId = firebaseAuthService.currentUserId() ?: run {
            _uiState.value = GroupJoinUiState.Error("Nicht angemeldet.")
            return
        }
        val displayName = firebaseAuthService.currentUser()?.let { user ->
            user.displayName?.takeIf { it.isNotBlank() }
                ?: user.email?.substringBefore('@')
                ?: "Anonymous"
        } ?: "Anonymous"
        _uiState.value = GroupJoinUiState.ProcessingPayment(groupChallenge)
        viewModelScope.launch {
            val activeChallenges = challengeRepository.getActiveChallengesList().getOrNull().orEmpty()
            val activePackages = activeChallenges.flatMap { it.appPackageNames }.toSet()
            val conflictingPkg = groupChallenge.appPackageNames.firstOrNull { it in activePackages }
            if (conflictingPkg != null) {
                val conflictName = activeChallenges
                    .firstOrNull { it.appPackageNames.contains(conflictingPkg) }
                    ?.appDisplayName ?: conflictingPkg
                _uiState.value = GroupJoinUiState.Error(
                    "Du hast bereits eine aktive Challenge für '$conflictName'. Beende sie zuerst."
                )
                return@launch
            }

            joinGroupChallengeUseCase.initiatePayment(groupChallenge.groupId, userId, displayName)
                .fold(
                    onSuccess = { joinData ->
                        Timber.d("GroupJoinVM: payment intent created %s", joinData.paymentData.paymentIntentId)
                        val awaitingState = GroupJoinUiState.AwaitingPayment(
                            paymentData = joinData.paymentData,
                            groupChallenge = groupChallenge,
                            groupId = joinData.groupId
                        )
                        lastAwaitingPayment = awaitingState
                        _uiState.value = awaitingState
                    },
                    onFailure = { e ->
                        Timber.e(e, "GroupJoinVM: initiatePayment failed")
                        _uiState.value = GroupJoinUiState.Error(e.message ?: "Zahlungseinrichtung fehlgeschlagen.")
                    }
                )
        }
    }

    /** Called by Stripe PaymentSheet callback on PaymentSheetResult.Completed. */
    fun onPaymentSuccess() {
        val awaiting = (_uiState.value as? GroupJoinUiState.AwaitingPayment)
            ?: lastAwaitingPayment
            ?: run {
                Timber.w("GroupJoinVM: onPaymentSuccess called but no AwaitingPayment state")
                return
            }
        val userId = firebaseAuthService.currentUserId() ?: run {
            _uiState.value = GroupJoinUiState.Error("Nicht angemeldet.")
            return
        }
        // Anti-cheat: capture deviceId (ANDROID_ID) so the participant entry carries it
        // for multi-account detection (same purpose as the solo Hard Mode challenge field).
        @Suppress("HardwareIds")
        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )
        _uiState.value = GroupJoinUiState.ConfirmingJoin(awaiting.groupChallenge)
        viewModelScope.launch {
            joinGroupChallengeUseCase.confirmJoin(
                groupId = awaiting.groupId,
                userId = userId,
                paymentIntentId = awaiting.paymentData.paymentIntentId,
                deviceId = deviceId
            ).fold(
                onSuccess = {
                    Timber.d("GroupJoinVM: join confirmed — groupId=%s", awaiting.groupId)
                    joinGroupChallengeUseCase.refreshCacheAfterJoin(awaiting.groupId)
                    lastAwaitingPayment = null
                    _uiState.value = GroupJoinUiState.JoinedSuccessfully(awaiting.groupId)
                },
                onFailure = { e ->
                    Timber.e(e, "GroupJoinVM: confirmJoin failed groupId=%s", awaiting.groupId)
                    val msg = e.message ?: ""
                    when {
                        msg.contains("already", ignoreCase = true) -> {
                            // Idempotency: user already added — treat as success
                            Timber.d("GroupJoinVM: 'already joined' → success groupId=%s", awaiting.groupId)
                            joinGroupChallengeUseCase.refreshCacheAfterJoin(awaiting.groupId)
                            lastAwaitingPayment = null
                            _uiState.value = GroupJoinUiState.JoinedSuccessfully(awaiting.groupId)
                        }
                        msg.contains("page not found", ignoreCase = true) ||
                        msg.contains("not found", ignoreCase = true) -> {
                            // 404 — function not deployed or wrong URL; retry won't help
                            _uiState.value = GroupJoinUiState.Error(
                                message = "Server nicht erreichbar. Bitte kontaktiere den Support.",
                                retryGroupChallenge = null
                            )
                        }
                        else -> {
                            _uiState.value = GroupJoinUiState.Error(
                                message = "Deine Zahlung wurde empfangen, aber der Beitritt konnte nicht bestätigt werden. Bitte erneut versuchen.",
                                retryGroupChallenge = awaiting.groupChallenge
                            )
                        }
                    }
                }
            )
        }
    }

    /** Called by Stripe PaymentSheet callback on Canceled or Failed. */
    fun onPaymentCancelled() {
        val gc = ((_uiState.value as? GroupJoinUiState.AwaitingPayment)
            ?: lastAwaitingPayment)?.groupChallenge
        _uiState.value = if (gc != null) GroupJoinUiState.Preview(gc) else GroupJoinUiState.Idle
    }

    /** Retries the confirmGroupJoin CF call if it failed after a successful payment. */
    fun retryConfirmJoin() {
        val awaiting = lastAwaitingPayment ?: run {
            _uiState.value = GroupJoinUiState.Idle
            return
        }
        _uiState.value = GroupJoinUiState.ConfirmingJoin(awaiting.groupChallenge)
        onPaymentSuccess()
    }

    fun clearError() {
        if (lastAwaitingPayment != null) {
            retryConfirmJoin()
        } else {
            _uiState.value = GroupJoinUiState.Idle
        }
    }
}
