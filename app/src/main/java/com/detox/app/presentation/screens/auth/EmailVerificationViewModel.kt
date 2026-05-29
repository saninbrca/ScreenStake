package com.detox.app.presentation.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val RESEND_COOLDOWN_SECONDS = 60

data class EmailVerificationState(
    val email: String = "",
    val checking: Boolean = false,
    val showNotVerifiedError: Boolean = false,
    val resendCooldownSeconds: Int = 0,
    val verified: Boolean = false
)

@HiltViewModel
class EmailVerificationViewModel @Inject constructor(
    private val firebaseAuthService: FirebaseAuthService
) : ViewModel() {

    private val _state = MutableStateFlow(
        EmailVerificationState(email = firebaseAuthService.currentUser()?.email ?: "")
    )
    val state: StateFlow<EmailVerificationState> = _state.asStateFlow()

    private var cooldownJob: Job? = null

    /**
     * Reloads the user and updates [EmailVerificationState.verified].
     * @param manual true when triggered by the "Ich habe bestätigt" button — shows an
     *               inline error if still not verified. The 5-second auto-poll passes false.
     */
    fun checkVerification(manual: Boolean) {
        if (_state.value.verified) return
        viewModelScope.launch {
            if (manual) _state.update { it.copy(checking = true, showNotVerifiedError = false) }
            firebaseAuthService.reloadAndCheckEmailVerified()
                .onSuccess { verified ->
                    _state.update {
                        it.copy(
                            checking = false,
                            verified = verified,
                            showNotVerifiedError = manual && !verified
                        )
                    }
                }
                .onFailure { e ->
                    Timber.w(e, "Email verification check failed")
                    _state.update {
                        it.copy(checking = false, showNotVerifiedError = manual)
                    }
                }
        }
    }

    fun resendVerificationEmail() {
        if (_state.value.resendCooldownSeconds > 0) return
        viewModelScope.launch {
            firebaseAuthService.sendEmailVerification()
            startCooldown()
        }
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            var remaining = RESEND_COOLDOWN_SECONDS
            while (remaining > 0) {
                _state.update { it.copy(resendCooldownSeconds = remaining) }
                delay(1000)
                remaining--
            }
            _state.update { it.copy(resendCooldownSeconds = 0) }
        }
    }

    /** Signs the unverified user out so they can register with a different email. */
    fun signOut() {
        firebaseAuthService.signOut()
    }
}
