package com.detox.app.presentation.screens.groupchallenge.join

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.R
import com.detox.app.util.CloudFunctionException
import com.detox.app.util.ErrorMessages
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.PaymentIntentData
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.usecase.JoinGroupChallengeUseCase
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
            _uiState.value = GroupJoinUiState.Error(context.getString(R.string.error_join_code_incomplete))
            return
        }
        val currentUserId = firebaseAuthService.currentUserId() ?: run {
            _uiState.value = GroupJoinUiState.Error(context.getString(R.string.error_not_signed_in))
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
                    _uiState.value = GroupJoinUiState.Error(ErrorMessages.from(context, e))
                }
            )
        }
    }

    fun initiatePayment(groupChallenge: GroupChallenge) {
        val userId = firebaseAuthService.currentUserId() ?: run {
            _uiState.value = GroupJoinUiState.Error(context.getString(R.string.error_not_signed_in))
            return
        }
        val displayName = firebaseAuthService.currentUser()?.let { user ->
            user.displayName?.takeIf { it.isNotBlank() }
                ?: user.email?.substringBefore('@')
                ?: context.getString(R.string.display_name_fallback)
        } ?: context.getString(R.string.display_name_fallback)
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
                    context.getString(R.string.join_conflict_active_challenge, conflictName)
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
                        // Pre-payment rejections (slot reservation refused) — no money
                        // existed yet, so the plain "full"/"started" copy is correct.
                        val message = when ((e as? CloudFunctionException)?.code) {
                            "join_rejected_full" ->
                                context.getString(R.string.uc_join_full, groupChallenge.maxParticipants)
                            "join_rejected_started" ->
                                context.getString(R.string.uc_join_already_started)
                            else -> ErrorMessages.from(context, e, R.string.error_payment)
                        }
                        _uiState.value = GroupJoinUiState.Error(message)
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
            _uiState.value = GroupJoinUiState.Error(context.getString(R.string.error_not_signed_in))
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
                    // Legal: persist the FAGG § 18 withdrawal-rights waiver the joiner ticked
                    // before paying — same point in the flow as the creator's.
                    logWithdrawalWaiver(awaiting.groupId)
                    lastAwaitingPayment = null
                    _uiState.value = GroupJoinUiState.JoinedSuccessfully(awaiting.groupId)
                },
                onFailure = { e ->
                    Timber.e(e, "GroupJoinVM: confirmJoin failed groupId=%s", awaiting.groupId)
                    val rejectionRes = when ((e as? CloudFunctionException)?.code) {
                        "join_rejected_full" -> R.string.join_rejected_full_refunded
                        "join_rejected_started" -> R.string.join_rejected_started_refunded
                        "join_rejected_expired" -> R.string.join_rejected_expired_refunded
                        else -> null
                    }
                    if (rejectionRes != null) {
                        // Definitive rejection — the server released the card hold before
                        // responding. Terminal: no retry button, no held payment state.
                        lastAwaitingPayment = null
                        _uiState.value = GroupJoinUiState.Error(
                            message = context.getString(rejectionRes),
                            retryGroupChallenge = null
                        )
                        return@fold
                    }
                    val msg = ErrorMessages.from(context, e)
                    when {
                        msg.contains("already", ignoreCase = true) -> {
                            // Idempotency: user already added — treat as success
                            Timber.d("GroupJoinVM: 'already joined' → success groupId=%s", awaiting.groupId)
                            joinGroupChallengeUseCase.refreshCacheAfterJoin(awaiting.groupId)
                            logWithdrawalWaiver(awaiting.groupId)
                            lastAwaitingPayment = null
                            _uiState.value = GroupJoinUiState.JoinedSuccessfully(awaiting.groupId)
                        }
                        msg.contains("page not found", ignoreCase = true) ||
                        msg.contains("not found", ignoreCase = true) -> {
                            // 404 — function not deployed or wrong URL; retry won't help
                            _uiState.value = GroupJoinUiState.Error(
                                message = context.getString(R.string.join_error_server_unreachable),
                                retryGroupChallenge = null
                            )
                        }
                        else -> {
                            _uiState.value = GroupJoinUiState.Error(
                                message = context.getString(R.string.join_error_confirm_failed),
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

    /**
     * "Change" on the collapsed code row — drops the resolved preview and returns to code
     * entry. The screen only offers this in [GroupJoinUiState.Preview]; once a hold exists
     * there is money in flight and the code is no longer the user's to change.
     */
    fun resetToCodeEntry() {
        if (lastAwaitingPayment != null) return
        _codeInput.value = ""
        _uiState.value = GroupJoinUiState.Idle
    }

    /**
     * Stores the joiner's explicit FAGG § 18 withdrawal-rights waiver consent.
     *
     * The creator's copy goes on the group document because the creator owns it; a joiner
     * does not. The precedent that actually generalises is Solo's
     * (`users/{uid}/challenges/{challengeId}`): the consent lives on a document the
     * consenting user owns and only they can write. So the joiner's lands on their own user
     * doc, keyed by group, as `groupWithdrawalWaivers.{groupId} = <ms>`.
     *
     * Deliberately NOT the group doc: its `withdrawalWaiverAccepted` is the CREATOR's single
     * flag, and a joiner writing it would masquerade as the creator's consent. Deliberately
     * NOT the participants array either — that is Cloud-Function-only (invariant #28), and
     * routing consent through it would mean changing the join CFs.
     *
     * Fire-and-forget merge, mirroring both existing waiver writes. Nested-map merge, so
     * one joiner's entry never clobbers another group's.
     */
    private fun logWithdrawalWaiver(groupId: String) {
        val uid = firebaseAuthService.currentUserId() ?: return
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .set(
                mapOf("groupWithdrawalWaivers" to mapOf(groupId to System.currentTimeMillis())),
                SetOptions.merge(),
            )
    }
}
