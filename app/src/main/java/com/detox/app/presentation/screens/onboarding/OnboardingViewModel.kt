package com.detox.app.presentation.screens.onboarding

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.service.AppDetectionAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class OnboardingState(
    val currentStep: Int = 0,
    val isSignedIn: Boolean = false,
    val isSigningIn: Boolean = false,
    val signInError: String? = null,
    val usageStatsGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val notificationsGranted: Boolean = false
) {
    val allPermissionsGranted: Boolean
        get() = isSignedIn && usageStatsGranted && overlayGranted &&
                accessibilityGranted && notificationsGranted

    /**
     * Total steps:
     *   0 = Google Sign-In
     *   1 = Usage Stats
     *   2 = Overlay
     *   3 = Accessibility
     *   4 = Notifications (Android 13+ only)
     */
    val totalSteps: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 5 else 4
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebaseAuthService: FirebaseAuthService
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun refreshPermissions() {
        _state.update {
            it.copy(
                isSignedIn = firebaseAuthService.isSignedIn(),
                usageStatsGranted = checkUsageStatsPermission(),
                overlayGranted = Settings.canDrawOverlays(context),
                accessibilityGranted = checkAccessibilityServiceEnabled(),
                notificationsGranted = checkNotificationPermission()
            )
        }
    }

    fun advanceStep() {
        _state.update { it.copy(currentStep = it.currentStep + 1) }
    }

    /** Called by the screen after the Google Sign-In flow returns a valid ID token. */
    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _state.update { it.copy(isSigningIn = true, signInError = null) }
            firebaseAuthService.signInWithGoogle(idToken)
                .onSuccess {
                    Timber.d("OnboardingViewModel: sign-in success")
                    _state.update { it.copy(isSigningIn = false, isSignedIn = true) }
                    advanceStep()
                }
                .onFailure { e ->
                    Timber.e(e, "OnboardingViewModel: sign-in failed")
                    _state.update {
                        it.copy(isSigningIn = false, signInError = e.message)
                    }
                }
        }
    }

    /**
     * Called when the Google Sign-In flow returns an account but idToken is null.
     * This always means GOOGLE_WEB_CLIENT_ID in build.gradle.kts is wrong — it must be
     * the OAuth 2.0 *Web* client ID from Firebase Console, not the Android client ID.
     */
    fun onGoogleSignInNullToken() {
        _state.update {
            it.copy(
                isSigningIn = false,
                signInError = "Sign-in failed: could not retrieve ID token. " +
                        "Check that GOOGLE_WEB_CLIENT_ID is set to the Web client ID " +
                        "from Firebase Console (Project settings → Your apps → Web app)."
            )
        }
    }

    /**
     * Called when the Google Sign-In flow throws an [ApiException].
     * Status code 12501 = user cancelled; other codes indicate real errors.
     */
    fun onGoogleSignInApiError(statusCode: Int) {
        _state.update {
            it.copy(
                isSigningIn = false,
                signInError = if (statusCode == 12501) null  // cancelled — no error message needed
                else "Google Sign-In failed (code $statusCode). Check your SHA-1 fingerprint " +
                        "is registered in Firebase Console and google-services.json is up to date."
            )
        }
    }

    fun clearSignInError() {
        _state.update { it.copy(signInError = null) }
    }

    private fun checkUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun checkAccessibilityServiceEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { serviceInfo ->
            serviceInfo.resolveInfo.serviceInfo.name ==
                    AppDetectionAccessibilityService::class.java.name
        }
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } else {
            true
        }
    }
}
