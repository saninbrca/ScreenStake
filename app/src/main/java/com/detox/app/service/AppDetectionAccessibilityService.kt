package com.detox.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AppDetectionAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Will be implemented in Phase 2
    }

    override fun onInterrupt() {
        // Will be implemented in Phase 2
    }
}
