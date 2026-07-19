package com.detox.app.presentation.screens.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.R
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

enum class AuthTab { LOGIN, REGISTER }

sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Loading : AuthUiState
    /** Google Sign-In succeeded — email is already verified, go to the permissions flow. */
    data object RegisterSuccess : AuthUiState
    /** Login succeeded and the email is verified — navigate directly to the dashboard. */
    data object LoginSuccess : AuthUiState
    /**
     * Login succeeded and the email is verified, but the account has no username yet
     * (existing accounts created before the username system) — route through selection.
     */
    data object NeedsUsername : AuthUiState
    /**
     * The account exists but the email is not yet verified.
     * [fromRegister] true → arrived here after a fresh registration (next = permissions onboarding);
     * false → arrived here from a login attempt with an unverified account (next = dashboard).
     */
    data class NeedsEmailVerification(val fromRegister: Boolean) : AuthUiState
    data class Error(val message: String) : AuthUiState
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreService: FirestoreService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _tab = MutableStateFlow(AuthTab.LOGIN)
    val tab: StateFlow<AuthTab> = _tab.asStateFlow()

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /** Transient inline confirmation message (e.g. password-reset email sent). */
    private val _infoMessage = MutableStateFlow<String?>(null)
    val infoMessage: StateFlow<String?> = _infoMessage.asStateFlow()

    fun switchTab(tab: AuthTab) {
        _tab.value = tab
        _uiState.value = AuthUiState.Idle
        _infoMessage.value = null
    }

    fun sendPasswordReset(email: String) {
        val trimmed = email.trim()
        if (trimmed.isBlank()) {
            _uiState.value = AuthUiState.Error(context.getString(R.string.auth_error_email_empty))
            return
        }
        viewModelScope.launch {
            firebaseAuthService.sendPasswordReset(trimmed)
            // Always confirm (success regardless to avoid user enumeration).
            _infoMessage.value = context.getString(R.string.auth_forgot_password_sent)
        }
    }

    fun clearInfo() { _infoMessage.value = null }

    // ── Register ───────────────────────────────────────────────────────────────

    fun register(
        email: String,
        password: String,
        confirmPassword: String,
        consentAGB: Boolean,
        consentDatenschutz: Boolean,
        consentAge18: Boolean
    ) {
        if (password != confirmPassword) {
            _uiState.value = AuthUiState.Error(context.getString(R.string.auth_passwords_mismatch))
            return
        }
        if (password.length < 8) {
            _uiState.value = AuthUiState.Error(context.getString(R.string.auth_password_hint))
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            firebaseAuthService.registerWithEmail(email.trim(), password)
                .onSuccess { user ->
                    val rawUser = FirebaseAuth.getInstance().currentUser
                    Timber.d(
                        "Registration complete. FirebaseAuth.getInstance().currentUser: " +
                        "isNull=%s uid=%s email=%s",
                        rawUser == null, rawUser?.uid, rawUser?.email
                    )

                    // Create the Firestore user document with the legal consent proof.
                    firestoreService.createUserDocument(
                        userId = user.uid,
                        email = user.email ?: email.trim(),
                        displayName = user.displayName,
                        consentAGB = consentAGB,
                        consentDatenschutz = consentDatenschutz,
                        consentAge18 = consentAge18
                    )

                    // Send the verification email and route to the verification screen
                    // instead of straight into the app.
                    firebaseAuthService.sendEmailVerification()
                    _uiState.value = AuthUiState.NeedsEmailVerification(fromRegister = true)
                }
                .onFailure { e ->
                    Timber.e(e, "Registration failed")
                    _uiState.value = AuthUiState.Error(friendlyError(e, isLogin = false))
                }
        }
    }

    // ── Login ──────────────────────────────────────────────────────────────────

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            firebaseAuthService.signInWithEmail(email.trim(), password)
                .onSuccess { user ->
                    val rawUser = FirebaseAuth.getInstance().currentUser
                    Timber.d(
                        "Login complete. FirebaseAuth.getInstance().currentUser: " +
                        "isNull=%s uid=%s email=%s",
                        rawUser == null, rawUser?.uid, rawUser?.email
                    )
                    // Block unverified accounts from reaching the dashboard.
                    if (firebaseAuthService.isEmailVerified()) {
                        // Existing verified accounts without a username must pick one first.
                        _uiState.value = if (firestoreService.getUsername(user.uid) == null) {
                            AuthUiState.NeedsUsername
                        } else {
                            AuthUiState.LoginSuccess
                        }
                    } else {
                        firebaseAuthService.sendEmailVerification()
                        _uiState.value = AuthUiState.NeedsEmailVerification(fromRegister = false)
                    }
                }
                .onFailure { e ->
                    Timber.e(e, "Login failed")
                    _uiState.value = AuthUiState.Error(friendlyError(e, isLogin = true))
                }
        }
    }

    // ── Google Sign-In ─────────────────────────────────────────────────────────

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            firebaseAuthService.signInWithGoogle(idToken)
                .onSuccess { user ->
                    val rawUser = FirebaseAuth.getInstance().currentUser
                    Timber.d(
                        "Google sign-in complete. FirebaseAuth.getInstance().currentUser: " +
                        "isNull=%s uid=%s email=%s",
                        rawUser == null, rawUser?.uid, rawUser?.email
                    )
                    // For Google Sign-In we don't know if this is a new user or returning user.
                    // Use RegisterSuccess to always run through onboarding — permissions screen
                    // will detect if they've already been granted and let the user skip through.
                    firestoreService.createUserDocument(
                        userId = user.uid,
                        email = user.email ?: "",
                        displayName = user.displayName
                    )
                    _uiState.value = AuthUiState.RegisterSuccess
                }
                .onFailure { e ->
                    Timber.e(e, "Google sign-in failed")
                    _uiState.value = AuthUiState.Error(friendlyError(e, isLogin = true))
                }
        }
    }

    fun onGoogleSignInNullToken() {
        Timber.e(
            "Could not retrieve Google ID token. Check that the Web Client ID in " +
            "build.gradle matches the OAuth 2.0 Web Client ID in Firebase Console."
        )
        _uiState.value = AuthUiState.Error(context.getString(R.string.error_google_signin))
    }

    fun onGoogleSignInApiError(statusCode: Int) {
        if (statusCode == 12501) {
            // User cancelled — just reset to Idle, no error message
            _uiState.value = AuthUiState.Idle
        } else {
            Timber.e(
                "Google Sign-In failed (code $statusCode). Ensure the SHA-1 fingerprint " +
                "is registered in Firebase Console."
            )
            _uiState.value = AuthUiState.Error(context.getString(R.string.error_google_signin))
        }
    }

    fun clearError() {
        _uiState.value = AuthUiState.Idle
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun friendlyError(e: Throwable, isLogin: Boolean): String {
        val generic = context.getString(
            if (isLogin) R.string.auth_error_login_generic else R.string.auth_error_register_generic
        )
        val msg = e.message ?: return generic
        return when {
            "EMAIL_ALREADY_IN_USE" in msg || "email address is already in use" in msg ->
                context.getString(R.string.auth_error_email_in_use)
            "INVALID_EMAIL" in msg || "badly formatted" in msg ->
                context.getString(R.string.auth_error_email_invalid)
            "WRONG_PASSWORD" in msg || "password is invalid" in msg || "INVALID_LOGIN_CREDENTIALS" in msg ->
                context.getString(R.string.auth_error_wrong_password)
            "USER_NOT_FOUND" in msg || "no user record" in msg ->
                context.getString(R.string.auth_error_user_not_found)
            "WEAK_PASSWORD" in msg || "password should be at least" in msg ->
                context.getString(R.string.auth_password_hint)
            "TOO_MANY_REQUESTS" in msg ->
                context.getString(R.string.auth_error_too_many_requests)
            "NETWORK_REQUEST_FAILED" in msg || "network error" in msg ->
                context.getString(R.string.auth_error_network)
            else -> generic
        }
    }
}
