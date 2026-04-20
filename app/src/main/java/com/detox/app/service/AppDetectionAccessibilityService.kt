package com.detox.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.detox.app.domain.model.AdultDomains
import timber.log.Timber
import java.util.Calendar

class AppDetectionAccessibilityService : AccessibilityService() {

    private var lastDetectedPackage: String? = null

    /** Launcher/home packages — used to detect Home button press. Resolved once and cached. */
    private val launcherPackages: Set<String> by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    // ── Custom-domain URL blocking throttle ──────────────────────────────────
    private var lastBlockedDomain: String? = null
    private var lastBlockedTimeMs: Long = 0L

    // ── Adult-domain URL blocking throttle ───────────────────────────────────
    /** Prevents sending the user home on every single content-changed event. */
    private var lastAdultBlockTimeMs: Long = 0L
    private val adultBlockCooldownMs = 2_000L

    companion object {
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.huawei.browser",
            "com.opera.browser",
            "com.brave.browser",
            "com.microsoft.emmx",        // Edge
        )

        /**
         * Ordered list of address-bar view-ID suffixes to try per browser.
         * We iterate all candidates and use the first non-empty text found.
         * Using suffixes (not full IDs) so we can call [findAccessibilityNodeInfosByViewId]
         * with the correctly-prefixed full ID.
         */
        private val URL_BAR_IDS = mapOf(
            "com.android.chrome" to listOf(
                "com.android.chrome:id/url_bar",
                "com.android.chrome:id/omnibox_text"
            ),
            "org.mozilla.firefox" to listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title",
                "org.mozilla.firefox:id/url_edit_text"
            ),
            "com.sec.android.app.sbrowser" to listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/url_bar"
            ),
            "com.huawei.browser" to listOf(
                "com.huawei.browser:id/url",
                "com.huawei.browser:id/address_bar"
            ),
            "com.opera.browser" to listOf(
                "com.opera.browser:id/url_field",
                "com.opera.browser:id/address_bar"
            ),
            "com.brave.browser" to listOf(
                "com.brave.browser:id/url_bar",
                "com.brave.browser:id/omnibox_text"
            ),
            "com.microsoft.emmx" to listOf(
                "com.microsoft.emmx:id/address_bar",
                "com.microsoft.emmx:id/url_bar",
                "com.microsoft.emmx:id/location_bar"
            ),
        )

        /** Text fragments that indicate Chrome/Edge incognito mode in the window title. */
        private val INCOGNITO_INDICATORS = listOf("incognito", "private", "privat")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("AppDetectionAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType  = event?.eventType  ?: return
        val packageName = event.packageName?.toString() ?: return

        // Ignore self and system UI
        if (packageName == applicationContext.packageName) return
        if (packageName == "com.android.systemui") return

        // ── Browser URL monitoring ────────────────────────────────────────────
        if (BROWSER_PACKAGES.contains(packageName)) {
            when (eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // Incognito check on any browser window event
                    if (TrackedAppEventBus.adultBlockingActive.value) {
                        checkIncognito(event, packageName)
                    }
                    if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                        checkBrowserUrl(packageName)
                    }
                }
            }
            // Still allow window-state events to fall through to the normal
            // app-open detection below (so browser tracking still works).
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        }

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // Avoid duplicate events for the same app
        if (packageName == lastDetectedPackage) return
        lastDetectedPackage = packageName

        // Always track foreground package so OverlayManager knows which app is visible
        TrackedAppEventBus.updateForegroundPackage(packageName)

        // Home screen detected while overlay is visible — signal OverlayManager to dismiss
        if (TrackedAppEventBus.overlayVisible.value && launcherPackages.contains(packageName)) {
            Timber.d("AppDetectionService: launcher $packageName detected while overlay visible — emitting home detected")
            TrackedAppEventBus.emitHomeDetected()
            return
        }

        // Skip packages freed for today
        if (TrackedAppEventBus.freedPackagesToday.value.contains(packageName)) {
            Timber.d("AppDetectionService: $packageName is freed for today — skipping overlay")
            return
        }

        val trackedPackages = TrackedAppEventBus.trackedPackages.value
        if (trackedPackages.contains(packageName)) {
            Timber.d("Checking package=$packageName against challenge packages=${trackedPackages.toList()}")

            // Overlay already visible — do not emit a second app-open event
            if (TrackedAppEventBus.overlayVisible.value) {
                Timber.d("AppDetectionService: overlay already visible, skipping emitAppOpen for $packageName")
                return
            }

            // Gate on challenge schedule — skip overlay if outside the active window
            val scheduleInfo = TrackedAppEventBus.packageSchedules.value[packageName]
            if (scheduleInfo != null && !isWithinActiveSchedule(scheduleInfo)) {
                Timber.d("AppDetectionService: $packageName — outside schedule window, skipping overlay")
                return
            }

            // If a session timer is still active for this package, let the user back in
            // without showing any overlay. The timer continues running in UsageTrackingService.
            // Only "No, go back" taps or natural timer expiry end a session early.
            val sessionPrefs = applicationContext.getSharedPreferences(
                OverlayManager.SESSION_PREFS_NAME, Context.MODE_PRIVATE
            )
            val sessionEndTime = sessionPrefs.getLong(
                "${OverlayManager.SESSION_END_KEY_PREFIX}$packageName", 0L
            )
            if (sessionEndTime > System.currentTimeMillis()) {
                val remaining = sessionEndTime - System.currentTimeMillis()
                Timber.d("User returned to $packageName, session remaining: ${remaining}ms")
                return
            }

            TrackedAppEventBus.emitAppOpen(packageName)
        }
    }

    // ── Incognito detection ───────────────────────────────────────────────────

    /**
     * Detects when the user opens an incognito/private window in a browser while
     * adult blocking is active. Sends the user to the home screen immediately.
     *
     * Browsers signal incognito mode in various ways:
     *  - Chrome/Edge: window title contains "Incognito"
     *  - Firefox: window title contains "Private"
     *  - Samsung Internet: window title contains "Secret"
     *
     * We also scan visible node text for known incognito indicator strings as a fallback.
     */
    private fun checkIncognito(event: AccessibilityEvent, packageName: String) {
        // Fast path: check window title from the event itself
        val windowTitle = event.text?.joinToString(" ")?.lowercase()
            ?: event.contentDescription?.toString()?.lowercase()
            ?: ""

        val isIncognito = INCOGNITO_INDICATORS.any { windowTitle.contains(it) }
            || checkNodeForIncognito(packageName)

        if (isIncognito) {
            val now = System.currentTimeMillis()
            if (now - lastAdultBlockTimeMs < adultBlockCooldownMs) return
            lastAdultBlockTimeMs = now

            Timber.d("Incognito/private mode detected in $packageName — adult blocking active, sending to home")
            showBlockedToast()
            goHome()
        }
    }

    /**
     * Searches the accessibility tree for incognito-indicator nodes.
     * Used as a fallback when the window title doesn't carry the incognito label.
     */
    private fun checkNodeForIncognito(packageName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            INCOGNITO_INDICATORS.any { indicator ->
                root.findAccessibilityNodeInfosByText(indicator).isNotEmpty()
            }
        } finally {
            root.recycle()
        }
    }

    // ── URL extraction & domain matching ─────────────────────────────────────

    /**
     * Extracts the current URL from the browser's address bar and checks it against:
     *  1. Adult domains ([AdultDomains.BLOCKED_DOMAINS]) — only when adult blocking is active.
     *     On match: send user to home screen immediately.
     *  2. Custom blocked domains ([TrackedAppEventBus.blockedDomains]) — always checked.
     *     On match: emit [TrackedAppEventBus.emitUrlBlocked] to show the challenge overlay.
     */
    private fun checkBrowserUrl(packageName: String) {
        val url = extractUrl(packageName) ?: return

        val adultBlockingActive = TrackedAppEventBus.adultBlockingActive.value

        // ── Adult domain check ────────────────────────────────────────────────
        if (adultBlockingActive) {
            val matchedAdult = AdultDomains.BLOCKED_DOMAINS.firstOrNull { domain ->
                url.contains(domain, ignoreCase = true)
            }
            if (matchedAdult != null) {
                val now = System.currentTimeMillis()
                if (now - lastAdultBlockTimeMs >= adultBlockCooldownMs) {
                    lastAdultBlockTimeMs = now
                    Timber.d("URL detected: $url in $packageName → blocked=$matchedAdult (adult)")
                    showBlockedToast()
                    goHome()
                }
                return  // Don't also fire the custom-domain overlay for the same URL
            }
        }

        Timber.d("URL detected: $url in $packageName → blocked=false")

        // ── Custom blocked-domain check ───────────────────────────────────────
        val customBlockedDomains = TrackedAppEventBus.blockedDomains.value
        if (customBlockedDomains.isEmpty()) return

        val matchedCustom = customBlockedDomains.firstOrNull { domain ->
            url.contains(domain, ignoreCase = true)
        } ?: return

        if (TrackedAppEventBus.freedDomainsToday.value.contains(matchedCustom)) return

        val now = System.currentTimeMillis()
        if (matchedCustom == lastBlockedDomain && now - lastBlockedTimeMs < 2_000L) return
        lastBlockedDomain = matchedCustom
        lastBlockedTimeMs = now

        Timber.d("Custom blocked domain=$matchedCustom detected in browser=$packageName url=$url")
        TrackedAppEventBus.emitUrlBlocked(matchedCustom)
    }

    /**
     * Tries every known URL-bar view ID for [packageName] in order, then falls back to a
     * text search for anything that looks like a URL (contains "." and no spaces).
     * Returns the first non-blank URL found, or null if none can be extracted.
     */
    private fun extractUrl(packageName: String): String? {
        val root = rootInActiveWindow ?: return null
        try {
            // Primary: try each known view ID for this browser
            val viewIds = URL_BAR_IDS[packageName] ?: emptyList()
            for (viewId in viewIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                val text = nodes.firstOrNull()?.text?.toString()?.trim()
                if (!text.isNullOrBlank()) return text
            }

            // Fallback: generic IDs that some browsers use
            val genericIds = listOf("url_bar", "address_bar", "omnibox", "url_field", "location_bar")
            for (id in genericIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
                val text = nodes.firstOrNull()?.text?.toString()?.trim()
                if (!text.isNullOrBlank()) return text
            }

            // Last resort: text search for a URL-shaped string
            val candidates = root.findAccessibilityNodeInfosByText(".")
            return candidates
                .mapNotNull { it.text?.toString()?.trim() }
                .firstOrNull { it.contains(".") && !it.contains(" ") && it.length > 4 }
        } finally {
            root.recycle()
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /** Sends the user to the Android launcher immediately. */
    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        applicationContext.startActivity(homeIntent)
    }

    /** Shows a brief toast so the user understands why they were redirected. */
    private fun showBlockedToast() {
        Toast.makeText(applicationContext, "🔞 Blocked by Detox", Toast.LENGTH_SHORT).show()
    }

    // ── Schedule gate ─────────────────────────────────────────────────────────

    /**
     * Returns true if the current time/day falls within the challenge's active schedule.
     * If no schedule is configured (null times and empty days), always returns true.
     */
    private fun isWithinActiveSchedule(info: TrackedAppEventBus.ScheduleInfo): Boolean {
        val hasTimeBounds = info.scheduleStartTime != null && info.scheduleEndTime != null
        val hasDayBounds = info.activeDays.isNotEmpty()
        if (!hasTimeBounds && !hasDayBounds) return true

        val now = Calendar.getInstance()

        if (hasDayBounds) {
            val dayName = when (now.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY    -> "MON"
                Calendar.TUESDAY   -> "TUE"
                Calendar.WEDNESDAY -> "WED"
                Calendar.THURSDAY  -> "THU"
                Calendar.FRIDAY    -> "FRI"
                Calendar.SATURDAY  -> "SAT"
                Calendar.SUNDAY    -> "SUN"
                else               -> return false
            }
            if (!info.activeDays.contains(dayName)) return false
        }

        if (hasTimeBounds) {
            val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

            fun parseMinutes(time: String): Int {
                val parts = time.split(":")
                return parts[0].toInt() * 60 + parts[1].toInt()
            }

            val startMinutes = parseMinutes(info.scheduleStartTime!!)
            val endMinutes   = parseMinutes(info.scheduleEndTime!!)

            return if (startMinutes <= endMinutes) {
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
