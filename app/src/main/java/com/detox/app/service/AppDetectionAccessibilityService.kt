package com.detox.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber

class AppDetectionAccessibilityService : AccessibilityService() {

    private var lastDetectedPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("AppDetectionAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Ignore self and system UI
        if (packageName == applicationContext.packageName) return
        if (packageName == "com.android.systemui") return

        // Avoid duplicate events for the same app
        if (packageName == lastDetectedPackage) return
        lastDetectedPackage = packageName

        val trackedPackages = TrackedAppEventBus.trackedPackages.value
        if (trackedPackages.contains(packageName)) {
            Timber.d("Tracked app detected: $packageName")
            TrackedAppEventBus.emitAppOpen(packageName)
        }
    }

    override fun onInterrupt() {
        Timber.d("AppDetectionAccessibilityService interrupted")
    }
}
