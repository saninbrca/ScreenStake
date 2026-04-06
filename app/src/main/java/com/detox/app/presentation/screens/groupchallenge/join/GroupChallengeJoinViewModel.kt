package com.detox.app.presentation.screens.groupchallenge.join

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.PaymentIntentData
import com.detox.app.domain.usecase.JoinGroupChallengeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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
    data object ProcessingPayment : GroupJoinUiState
    data class AwaitingPayment(val paymentData: PaymentIntentData, val groupChallenge: GroupChallenge) : GroupJoinUiState
    data object JoinedSuccessfully : GroupJoinUiState
    data class Error(val message: String) : GroupJoinUiState
}

@HiltViewModel
class GroupChallengeJoinViewModel @Inject constructor(
    private val joinGroupChallengeUseCase: JoinGroupChallengeUseCase,
    private val firebaseAuthService: FirebaseAuthService
) : ViewModel() {

    private val _codeInput = MutableStateFlow("")
    val codeInput: StateFlow<String> = _codeInput.asStateFlow()

    private val _uiState = MutableStateFlow<GroupJoinUiState>(GroupJoinUiState.Idle)
    val uiState: StateFlow<GroupJoinUiState> = _uiState.asStateFlow()

    fun onCodeChanged(code: String) {
        _codeInput.update { code.uppercase().take(6) }
        if (_uiState.value is GroupJoinUiState.Error) _uiState.value = GroupJoinUiState.Idle
    }

    fun lookupCode() {
        val code = _codeInput.value.trim()
        if (code.length != 6) {
            _uiState.value = GroupJoinUiState.Error("Enter the full 6-character code.")
            return
        }
        _uiState.value = GroupJoinUiState.LookingUp
        viewModelScope.launch {
            joinGroupChallengeUseCase.fetchByCode(code).fold(
                onSuccess = { gc ->
                    Timber.d("GroupJoinVM: found groupId=%s", gc.groupId)
                    _uiState.value = GroupJoinUiState.Preview(gc)
                },
                onFailure = { e ->
                    Timber.e(e, "GroupJoinVM: lookup failed code=%s", code)
                    _uiState.value = GroupJoinUiState.Error(e.message ?: "Challenge not found.")
                }
            )
        }
    }

    fun initiatePayment(groupChallenge: GroupChallenge) {
        val userId = firebaseAuthService.currentUserId() ?: run {
            _uiState.value = GroupJoinUiState.Error("Not signed in.")
            return
        }
        val displayName = firebaseAuthService.currentUser()?.displayName ?: firebaseAuthService.currentUser()?.email ?: "Anonymous"
        _uiState.value = GroupJoinUiState.ProcessingPayment
        viewModelScope.launch {
            joinGroupChallengeUseCase.initiatePayment(groupChallenge.groupId, userId, displayName)
                .fold(
                    onSuccess = { paymentData ->
                        Timber.d("GroupJoinVM: payment intent created %s", paymentData.paymentIntentId)
                        _uiState.value = GroupJoinUiState.AwaitingPayment(paymentData, groupChallenge)
                    },
                    onFailure = { e ->
                        Timber.e(e, "GroupJoinVM: initiatePayment failed")
                        _uiState.value = GroupJoinUiState.Error(e.message ?: "Payment setup failed.")
                    }
                )
        }
    }

    fun onPaymentSuccess() {
        Timber.d("GroupJoinVM: payment confirmed — joined successfully")
        _uiState.value = GroupJoinUiState.JoinedSuccessfully
    }

    fun onPaymentCancelled() {
        val gc = (_uiState.value as? GroupJoinUiState.AwaitingPayment)?.groupChallenge
        _uiState.value = if (gc != null) GroupJoinUiState.Preview(gc) else GroupJoinUiState.Idle
    }

    fun clearError() {
        _uiState.value = GroupJoinUiState.Idle
    }
}
