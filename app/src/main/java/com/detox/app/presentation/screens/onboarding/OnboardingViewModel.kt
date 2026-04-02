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
import com.detox.app.service.AppDetectionAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class OnboardingState(
    val currentStep: Int = 0,
    val usageStatsGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val notificationsGranted: Boolean = false
) {
    /**
     * Total steps (all permissions):
     *   0 = Usage Stats
     *   1 = Overlay
     *   2 = Accessibility
     *   3 = Notifications (Android 13+ only)
     */
    val totalSteps: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 4 else 3
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /** Re-check all permission states. Call from LaunchedEffect(Lifecycle.State.RESUMED). */
    fun refreshPermissions() {
        Timber.d("Onboarding: refreshPermissions called (app resumed)")
        val overlayGranted = Settings.canDrawOverlays(context)
        Timber.d("Onboarding: canDrawOverlays = $overlayGranted")
        _state.update {
            it.copy(
                usageStatsGranted = checkUsageStatsPermission(),
                overlayGranted = overlayGranted,
                accessibilityGranted = checkAccessibilityServiceEnabled(),
                notificationsGranted = checkNotificationPermission()
            )
        }

        // Android 10+ may have a brief delay before canDrawOverlays() returns true
        // immediately after the user grants the permission and returns to the app.
        // Retry once after 500 ms to catch this race condition.
        if (!overlayGranted) {
            viewModelScope.launch {
                delay(500L)
                val retryResult = Settings.canDrawOverlays(context)
                Timber.d("Onboarding: canDrawOverlays retry (500 ms) = $retryResult")
                if (retryResult) {
                    _state.update { it.copy(overlayGranted = true) }
                }
            }
        }
    }

    fun advanceStep() {
        _state.update { it.copy(currentStep = it.currentStep + 1) }
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
        return enabledServices.any { info ->
            info.resolveInfo.serviceInfo.name == AppDetectionAccessibilityService::class.java.name
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
