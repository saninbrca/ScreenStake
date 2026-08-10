package com.finite.focus.util

import android.content.Context
import android.provider.Settings

/**
 * Canonical permission checks — the go-forward single source for permission state queries.
 *
 * Historical note: MainActivity, WelcomeOnboardingScreen, PermissionCheckWorker and
 * UsageTrackingService still carry private copies of the accessibility check; new call sites
 * must use this helper instead of adding another copy.
 */
object PermissionUtils {

    /** True when [com.finite.focus.service.AppDetectionAccessibilityService] is enabled in system settings. */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(context.packageName)
    }
}
