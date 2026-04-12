package com.detox.app.domain.model

enum class LimitType {
    TIME,
    SESSIONS,
    /** User picks a total daily time budget (e.g. 39 min). Each session deducts from it. */
    TIME_BUDGET,
    /**
     * App / website is only accessible within a configured schedule window.
     * No usage-based limit applies; blocking is handled entirely by the VPN (websites)
     * and AccessibilityService (apps) using the challenge's scheduleStart/End/activeDays.
     */
    TIME_WINDOW
}
