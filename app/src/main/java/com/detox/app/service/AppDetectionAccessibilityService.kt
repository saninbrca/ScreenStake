package com.detox.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber
import java.util.Calendar

class AppDetectionAccessibilityService : AccessibilityService() {

    private var lastDetectedPackage: String? = null

    /** Throttle: track the last domain+time to avoid firing the overlay on every content change. */
    private var lastBlockedDomain: String? = null
    private var lastBlockedTimeMs: Long = 0L

    companion object {
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.huawei.browser",
            "com.opera.browser",
            "com.brave.browser",
        )

        /** Known address-bar view IDs per browser package. */
        private val URL_BAR_VIEW_IDS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.huawei.browser" to "com.huawei.browser:id/url",
            "com.opera.browser" to "com.opera.browser:id/url_field",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("AppDetectionAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        val packageName = event.packageName?.toString() ?: return

        // Ignore self and system UI
        if (packageName == applicationContext.packageName) return
        if (packageName == "com.android.systemui") return

        // URL monitoring for browsers (TYPE_WINDOW_CONTENT_CHANGED)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            BROWSER_PACKAGES.contains(packageName)
        ) {
            checkBrowserUrl(packageName)
            return
        }

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // Avoid duplicate events for the same app
        if (packageName == lastDetectedPackage) return
        lastDetectedPackage = packageName

        // Always update the foreground package so OverlayManager can cancel timers
        TrackedAppEventBus.updateForegroundPackage(packageName)

        // Skip packages freed for today
        if (TrackedAppEventBus.freedPackagesToday.value.contains(packageName)) {
            Timber.d("AppDetectionService: $packageName is freed for today — skipping overlay")
            return
        }

        val trackedPackages = TrackedAppEventBus.trackedPackages.value
        if (trackedPackages.contains(packageName)) {
            Timber.d("Checking package=$packageName against challenge packages=${trackedPackages.toList()}")

            // Gate on challenge schedule — skip overlay if outside the active window
            val scheduleInfo = TrackedAppEventBus.packageSchedules.value[packageName]
            if (scheduleInfo != null && !isWithinActiveSchedule(scheduleInfo)) {
                Timber.d("AppDetectionService: $packageName — outside schedule window, skipping overlay")
                return
            }

            TrackedAppEventBus.emitAppOpen(packageName)
        }
    }

    private fun checkBrowserUrl(packageName: String) {
        val blockedDomains = TrackedAppEventBus.blockedDomains.value
        if (blockedDomains.isEmpty()) return

        val root = rootInActiveWindow ?: return
        try {
            // Try the known view ID for this browser first
            val viewId = URL_BAR_VIEW_IDS[packageName]
            val urlNodes = if (viewId != null) {
                root.findAccessibilityNodeInfosByViewId(viewId)
            } else {
                emptyList()
            }

            val url = urlNodes.firstOrNull()?.text?.toString() ?: return

            val matchedDomain = blockedDomains.firstOrNull { domain ->
                url.contains(domain, ignoreCase = true)
            } ?: return

            // Don't re-fire if this domain is freed for today
            if (TrackedAppEventBus.freedDomainsToday.value.contains(matchedDomain)) return

            // Throttle: same domain within 2 seconds → skip
            val now = System.currentTimeMillis()
            if (matchedDomain == lastBlockedDomain && now - lastBlockedTimeMs < 2_000L) return
            lastBlockedDomain = matchedDomain
            lastBlockedTimeMs = now

            Timber.d("Blocked domain=$matchedDomain detected in browser=$packageName url=$url")
            TrackedAppEventBus.emitUrlBlocked(matchedDomain)
        } finally {
            root.recycle()
        }
    }

    /**
     * Returns true if the current time/day falls within the challenge's active schedule.
     * If no schedule is configured (null times and empty days), always returns true.
     */
    private fun isWithinActiveSchedule(info: TrackedAppEventBus.ScheduleInfo): Boolean {
        val hasTimeBounds = info.scheduleStartTime != null && info.scheduleEndTime != null
        val hasDayBounds = info.activeDays.isNotEmpty()
        if (!hasTimeBounds && !hasDayBounds) return true  // no schedule set → always active

        val now = Calendar.getInstance()

        // Check active days first
        if (hasDayBounds) {
            val dayName = when (now.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "MON"
                Calendar.TUESDAY -> "TUE"
                Calendar.WEDNESDAY -> "WED"
                Calendar.THURSDAY -> "THU"
                Calendar.FRIDAY -> "FRI"
                Calendar.SATURDAY -> "SAT"
                Calendar.SUNDAY -> "SUN"
                else -> return false
            }
            if (!info.activeDays.contains(dayName)) {
                return false  // today is not an active day
            }
        }

        // Check time window
        if (hasTimeBounds) {
            val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

            fun parseMinutes(time: String): Int {
                val parts = time.split(":")
                return parts[0].toInt() * 60 + parts[1].toInt()
            }

            val startMinutes = parseMinutes(info.scheduleStartTime!!)
            val endMinutes = parseMinutes(info.scheduleEndTime!!)

            return if (startMinutes <= endMinutes) {
                // Normal range, e.g. 09:00 – 22:00
                currentMinutes in startMinutes..endMinutes
            } else {
                // Wraps midnight, e.g. 22:00 – 07:00
                currentMinutes >= startMinutes || currentMinutes <= endMinutes
            }
        }

        return true
    }

    override fun onInterrupt() {
        Timber.d("AppDetectionAccessibilityService interrupted")
    }
}
