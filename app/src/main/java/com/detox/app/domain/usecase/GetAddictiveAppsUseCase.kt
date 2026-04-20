package com.detox.app.domain.usecase

import android.content.Context
import android.content.pm.PackageManager
import com.detox.app.domain.model.ProofOfAddictionResult
import com.detox.app.domain.repository.UsageStatsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Package prefixes that belong to system utilities — never shown in app selection.
 * We keep this intentionally narrow so that browsers, messengers, and social apps are
 * not accidentally excluded.
 */
private val EXCLUDED_PACKAGE_PREFIXES = listOf(
    "com.detox.app",                    // this app itself
    "com.android.phone",                // Phone dialer
    "com.android.contacts",             // Contacts
    "com.android.settings",             // Settings
    "com.android.camera",               // AOSP Camera
    "com.android.systemui",             // System UI
    "com.android.inputmethod",          // Keyboard
    "com.google.android.inputmethod",   // Gboard
    "com.google.android.gms",           // Play Services
    "com.google.android.gsf",           // Google Services Framework
    "com.android.launcher",             // Launcher
    "com.google.android.launcher",
    "com.sec.android.app.launcher",
    "com.miui.home",
    "com.oneplus.launcher",
    "com.huawei.android.launcher",
    "com.android.providers",            // Content providers
    "com.android.server",
    "com.android.shell",
    "com.android.bluetooth",
    "com.android.nfc",
    "com.android.wifi",
    "com.android.calculator",
    "com.android.calendar",
    "com.android.clock",
    "com.android.deskclock",
    "com.google.android.deskclock",
    "com.android.mms",                  // SMS (stock)
    "com.android.messaging",
    "com.android.dialer",
    "com.android.contacts",
    "com.android.music",
    "com.android.gallery",
    "com.android.email",
    "com.android.packageinstaller",
    "android",
    "com.google.android.packageinstaller",
)

class GetAddictiveAppsUseCase @Inject constructor(
    private val usageStatsRepository: UsageStatsRepository,
    @ApplicationContext private val context: Context
) {
    suspend operator fun invoke(): Result<ProofOfAddictionResult> {
        return try {
            val pm = context.packageManager
            val allApps = usageStatsRepository.getAppUsageStats(days = 14)
                .filter { shouldInclude(it.packageName, pm) }
            val (trackable, nonTrackable) = allApps.partition { it.isTrackable }
            Result.success(
                ProofOfAddictionResult(
                    trackableApps = trackable,
                    nonTrackableApps = nonTrackable
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Returns true if [packageName] should appear in the app-selection list.
     * Rules:
     * 1. Must not start with any of the excluded system-utility prefixes.
     * 2. Must have a launcher icon (i.e. getLaunchIntentForPackage != null).
     * 3. Must be installed.
     */
    private fun shouldInclude(packageName: String, pm: PackageManager): Boolean {
        // Rule 1: exclude system utilities
        if (EXCLUDED_PACKAGE_PREFIXES.any { packageName.startsWith(it) }) return false

        // Rule 2: must be user-launchable (has a home-screen icon)
        if (pm.getLaunchIntentForPackage(packageName) == null) return false

        // Rule 3: must be installed
        return try {
            pm.getApplicationInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
