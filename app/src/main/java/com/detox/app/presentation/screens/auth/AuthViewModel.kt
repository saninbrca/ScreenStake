package com.detox.app.presentation.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
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
    /** Registration succeeded — navigate to the permissions / onboarding flow. */
    data object RegisterSuccess : AuthUiState
    /** Login succeeded — navigate directly to the dashboard. */
    data object LoginSuccess : AuthUiState
    data class Error(val message: String) : AuthUiState
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreService: FirestoreService
) : ViewModel() {

    private val _tab = MutableStateFlow(AuthTab.LOGIN)
    val tab: StateFlow<AuthTab> = _tab.asStateFlow()

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun switchTab(tab: AuthTab) {
        _tab.value = tab
        _uiState.value = AuthUiState.Idle
    }

    // ── Register ───────────────────────────────────────────────────────────────

    fun register(email: String, password: String, confirmPassword: String) {
        if (password != confirmPassword) {
            _uiState.value = AuthUiState.Error("Passwords do not match.")
            return
        }
        if (password.length < 6) {
            _uiState.value = AuthUiState.Error("Password must be at least 6 characters.")
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            firebaseAuthService.registerWithEmail(email.trim(), password)
                .onSuccess { user ->
                    // ── Critical diagnostic log ───────────────────────────────
                    val rawUser = FirebaseAuth.getInstance().currentUser
                    Timber.d(
                        "Registration complete. FirebaseAuth.getInstance().currentUser: " +
                        "isNull=%s uid=%s email=%s",
                        rawUser == null, rawUser?.uid, rawUser?.email
                    )
                    // ─────────────────────────────────────────────────────────

                    // Create the Firestore user document immediately after registration
                    firestoreService.createUserDocument(
                        userId = user.uid,
                        email = user.email ?: email.trim(),
                        displayName = user.displayName
                    )
                    _uiState.value = AuthUiState.RegisterSuccess
                }
                .onFailure { e ->
                    Timber.e(e, "Registration failed")
                    _uiState.value = AuthUiState.Error(friendlyError(e))
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
                    _uiState.value = AuthUiState.LoginSuccess
                }
                .onFailure { e ->
                    Timber.e(e, "Login failed")
                    _uiState.value = AuthUiState.Error(friendlyError(e))
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
                    _uiState.value = AuthUiState.Error(friendlyError(e))
                }
        }
    }

    fun onGoogleSignInNullToken() {
        _uiState.value = AuthUiState.Error(
            "Could not retrieve Google ID token. Check that the Web Client ID in " +
            "build.gradle matches the OAuth 2.0 Web Client ID in Firebase Console."
        )
    }

    fun onGoogleSignInApiError(statusCode: Int) {
        if (statusCode == 12501) {
            // User cancelled — just reset to Idle, no error message
            _uiState.value = AuthUiState.Idle
        } else {
            _uiState.value = AuthUiState.Error(
                "Google Sign-In failed (code $statusCode). Ensure the SHA-1 fingerprint " +
                "is registered in Firebase Console."
            )
        }
    }

    fun clearError() {
        _uiState.value = AuthUiState.Idle
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun friendlyError(e: Throwable): String {
        val msg = e.message ?: return "An unknown error occurred."
        return when {
            "EMAIL_ALREADY_IN_USE" in msg || "email address is already in use" in msg ->
                "This email is already registered. Try logging in instead."
            "INVALID_EMAIL" in msg || "badly formatted" in msg ->
                "Please enter a valid email address."
            "WRONG_PASSWORD" in msg || "password is invalid" in msg || "INVALID_LOGIN_CREDENTIALS" in msg ->
                "Incorrect email or password."
            "USER_NOT_FOUND" in msg || "no user record" in msg ->
                "No account found with this email. Register first."
            "WEAK_PASSWORD" in msg || "password should be at least" in msg ->
                "Password must be at least 6 characters."
            "TOO_MANY_REQUESTS" in msg ->
                "Too many attempts. Please wait and try again."
            "NETWORK_REQUEST_FAILED" in msg || "network error" in msg ->
                "Network error. Check your internet connection."
            else -> msg
        }
    }
}
