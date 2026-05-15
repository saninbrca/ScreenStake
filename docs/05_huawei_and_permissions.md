# 05 — Huawei & Permissions
> **Scope:** All Huawei-specific constraints, 24h permission monitoring system, AccessibilityService rules, FLAG_SECURE overlay requirements, Adult Content blocking.
> **When to load:** Any work on notifications, auth, AccessibilityService, overlay permissions, permission monitoring, adult content filtering, or anything that might behave differently on Huawei.

---

## Huawei Compatibility Rules (MANDATORY)

Huawei devices ship **without Google Play Services**. This breaks several standard Android APIs.

| Feature | Standard Android | Huawei (EMUI/HarmonyOS) |
|---------|-----------------|--------------------------|
| Push Notifications | FCM ✅ | ❌ FCM does NOT work → use WorkManager / AlarmManager ONLY |
| Google Sign-In | ✅ | ❌ Does NOT work → Email/Password is the ONLY auth method |
| Cloud Functions | `onCall` ✅ | ❌ `onCall` requires Play Services → use `onRequest` ONLY |
| Battery optimization | Normal | Kills background services aggressively → must guide user to whitelist |
| AccessibilityService | Stable | Can be killed by EMUI → `PermissionCheckWorker` as 15min backup |
| Overlay permission | Stable | Can be silently revoked → 24h monitoring system required |

### Always test on:
1. Real Huawei device (EMUI / HarmonyOS)
2. Standard Android emulator

### Huawei Launcher Package Names
```kotlin
// Used to detect Huawei-specific home screen / system manager
val HUAWEI_LAUNCHER = "com.huawei.android.launcher"
val HUAWEI_SYSTEM_MANAGER = "com.huawei.systemmanager"
```

---

## Notification System (Huawei-safe)

```
❌ NEVER: FirebaseMessaging / FCM push notifications
✅ ALWAYS: WorkManager (periodic) or AlarmManager (exact time)
```

### Notification Channels Used
- **Daily Reminder:** `DailyReminderWorker` — user-configurable time, toggleable in Settings
- **Permission warnings:** `PermissionCheckWorker` — escalating urgency (see 24h system below)
- **Challenge events:** Fail, success, group events — all via local notifications

### WorkManager Scheduling

```kotlin
// Daily reminder (user-configured time):
val dailyWork = PeriodicWorkRequestBuilder<DailyReminderWorker>(1, TimeUnit.DAYS)
    .setInitialDelay(delayUntilTime, TimeUnit.MILLISECONDS)
    .build()
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "daily_reminder",
    ExistingPeriodicWorkPolicy.REPLACE,
    dailyWork
)

// Permission check (every 15 min):
val permCheck = PeriodicWorkRequestBuilder<PermissionCheckWorker>(15, TimeUnit.MINUTES)
    .build()
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "permission_check",
    ExistingPeriodicWorkPolicy.KEEP,
    permCheck
)
```

---

## 24-Hour Permission Monitoring System

Triggered when: overlay permission OR AccessibilityService permission is lost while a Hard Mode challenge is active.

### Escalation Timeline

| Time since loss | Action |
|----------------|--------|
| Hour 0 | Notification: "⚠️ Deine Challenge ist in Gefahr!" |
| Hour 2 | Escalated notification (higher priority) |
| Hour 6 | Urgent notification |
| Hour 12 | Notification: "🔴 Letzte Warnung!" |
| Hour 18 | Fullscreen block shown on every app open (cannot be dismissed) |
| Hour 24 | **Hard Mode: Stripe capture → Challenge marked FAILED** |

**Acceleration rule:** If user opens the app and ignores the warning in the first 12 hours, the timer accelerates (hours count down faster). After hour 12, timer runs at fixed pace regardless of user action.

**Visual indicator:** Red pulsing banner shown on ALL screens while any required permission is missing.

### Soft Mode: No money capture — only notification escalation, challenge marked FAILED at hour 24.

### Implementation

```kotlin
// PermissionCheckWorker (runs every 15 min):
class PermissionCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val overlayGranted = Settings.canDrawOverlays(applicationContext)
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        if (!overlayGranted || !accessibilityEnabled) {
            val firstLostAt = prefs.getLong("permission_lost_at", 0L)
            if (firstLostAt == 0L) {
                prefs.edit().putLong("permission_lost_at", System.currentTimeMillis()).apply()
            }
            val hoursLost = (System.currentTimeMillis() - firstLostAt) / 3600000
            handleEscalation(hoursLost)
        } else {
            // Reset timer if permissions restored
            prefs.edit().remove("permission_lost_at").apply()
        }
        return Result.success()
    }
}

// UsageTrackingService also checks every 60 seconds:
// If permission lost detected, fires immediate local notification
```

---

## AccessibilityService Rules

### What it does
- Detects when user opens an app that is in the blocked list
- Shows the appropriate overlay immediately
- Monitors browser URL bar for blocked domains

### Performance Rules (CRITICAL)

```kotlin
// 1. blockedPackagesCache: HashSet<String>
//    O(1) lookup — check FIRST before any coroutine or DB query
if (packageName !in blockedPackagesCache) return   // fast path exit
if (packageName in allowedPackages) return          // temporarily allowed

// 2. Pre-cache ALL overlay layouts in onCreate():
override fun onCreate() {
    sessionIntentionView = LayoutInflater.from(this)
        .inflate(R.layout.session_intention, null)
    limitExceededView = LayoutInflater.from(this)
        .inflate(R.layout.limit_exceeded, null)
    // etc. — inflate ALL overlays at startup
    // Only update dynamic content before showing, NEVER re-inflate
}

// 3. Dual event detection for maximum reliability:
//    TYPE_WINDOW_STATE_CHANGED — catches Activity opens
//    TYPE_WINDOW_CONTENT_CHANGED — catches Fragment/tab changes within apps
```

### Temporary Whitelist (after user confirms open)

```kotlin
// After user taps "öffnen" and countdown finishes:
fun allowPackageTemporarily(packageName: String) {
    allowedPackages.add(packageName)
    Handler(Looper.getMainLooper()).postDelayed({
        allowedPackages.remove(packageName)
    }, 5000)  // 5 second window
}
```

### On Huawei: Service Watchdog

```kotlin
// ServiceWatchdogWorker runs periodically:
// Checks if AppDetectionAccessibilityService is running
// If not → shows notification guiding user to re-enable
// Cannot auto-restart AccessibilityService (Android restriction)
```

### Known Huawei Overlay Timing Issue

**Problem:** On Huawei, the blocked app briefly appears on screen before the overlay covers it.

**What was tried:** `performGlobalAction(GLOBAL_ACTION_HOME)` — removed because it caused an infinite loop (home screen triggers another window state change event).

**Potential fix (not yet implemented):** Poll via `UsageStatsManager` at 500ms intervals as the PRIMARY detection method on Huawei, instead of relying solely on AccessibilityService events.

---

## Overlay Permission Rules

### FLAG_SECURE — MANDATORY on ALL overlays

```kotlin
val params = WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        or WindowManager.LayoutParams.FLAG_SECURE,   // ← MANDATORY
    PixelFormat.TRANSLUCENT
)
```

**Why:** `FLAG_SECURE` makes the overlay appear as a black screen in the recents/task-switcher. Without it, users could see the overlay content in recents and potentially screenshot the blocked content.

### Overlay Display Rule

```kotlin
// NEVER use coroutines or async for showOverlay() — causes timing delay
// The overlay must appear INSTANTLY when the blocked app is detected.

// ❌ WRONG:
viewModelScope.launch { overlayManager.showOverlay(...) }

// ✅ CORRECT:
Handler(Looper.getMainLooper()).post { overlayManager.showOverlay(...) }
```

### Required Permissions (declared in AndroidManifest.xml)
```
SYSTEM_ALERT_WINDOW       ← overlay drawing
BIND_ACCESSIBILITY_SERVICE ← app detection
PACKAGE_USAGE_STATS       ← screen time tracking (UsageTrackingService)
FOREGROUND_SERVICE        ← UsageTrackingService
RECEIVE_BOOT_COMPLETED    ← BootReceiver
```

---

## Adult Content Blocking

### Rules (ABSOLUTE — no exceptions)
```
Adult content = 100% blocked, ALWAYS.
No overlay, no bypass, no "öffnen" option.
Silent redirect to home screen + brief Toast only.
```

### Implementation

```kotlin
// AppDetectionAccessibilityService monitors URL bar content
// in ALL major browsers (Chrome, Firefox, Samsung Browser, etc.)

// Domain list: assets/adult_domains.txt (100+ domains)
// Loaded at service start, cached in memory as HashSet<String>

// Subdomain matching:
fun isDomainBlocked(url: String): Boolean {
    val host = Uri.parse(url).host ?: return false
    return blockedAdultDomains.any { domain ->
        host == domain || host.endsWith(".$domain")
    }
    // "www.pornhub.com" matches "pornhub.com" ✅
}

// On match:
// 1. performGlobalAction(GLOBAL_ACTION_HOME)  ← immediate
// 2. Show Toast: "🔞 Blocked by Detox"        ← brief, no dismiss needed
// 3. NO overlay, NO counter increment, NO bypass

// Incognito mode detection:
// If user opens incognito tab in any browser AND adult content challenge is active
// → treat as adult content → redirect home immediately
```

### Browser URL Bar Detection

```kotlin
// In onAccessibilityEvent:
if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
    val source = event.source ?: return
    val urlNode = findUrlBarNode(source)
    val url = urlNode?.text?.toString() ?: return
    if (isDomainBlocked(url)) {
        blockAdultContent()
    }
}
```

---

## Onboarding — Permission Setup Flow

Huawei users need extra guidance due to aggressive battery optimization:

```
Step 1: Request SYSTEM_ALERT_WINDOW (overlay) permission
Step 2: Request PACKAGE_USAGE_STATS permission
Step 3: Enable AccessibilityService (deep-link to settings)
Step 4 (Huawei only): Guide user to disable battery optimization for Detox
    → "Einstellungen → Apps → Detox → Akku → Keine Einschränkungen"
Step 5: Verify all permissions granted → show green checkmarks
```

Settings screen also shows live permission status:
```
Accessibility Service:  ✅ / ❌
Overlay permission:     ✅ / ❌  
Usage Stats:            ✅ / ❌
```

---

## BootReceiver

```kotlin
// Restarts all services after device reboot:
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Restart UsageTrackingService (Foreground Service)
            // Re-schedule WorkManager workers
            // AccessibilityService restarts automatically if enabled in settings
        }
    }
}
```

---

## Summary: Huawei-Safe Development Checklist

```
□ Notifications → WorkManager/AlarmManager (never FCM)
□ Auth → Email/Password shown first (Google Sign-In hidden or shown last)
□ Cloud Functions → onRequest only (never onCall)
□ Overlays → Handler(Looper.getMainLooper()).post { } (never coroutines)
□ Overlays → FLAG_SECURE always set
□ Services → PermissionCheckWorker as 15min watchdog backup
□ Battery optimization → guide user in onboarding to whitelist Detox
□ Test → always verify on real Huawei hardware, not just emulator
```

## Debug: Permission Testing

Use Debug Panel in ProfileScreen to test permission scenarios:

"Check All Permissions"
→ Shows Overlay ✅/❌, Accessibility ✅/❌, Usage Stats ✅/❌
→ All three must be ✅ for normal operation

"Simulate Permission Lost"
→ Sets permission_lost_at = now - 2h in SharedPreferences
→ PermissionCheckWorker detects it within 15 min
→ Red banner appears on all screens
→ Notifications escalate per 24h system

"Reset Permission Lost Timer"
→ Clears permission_lost_at from SharedPreferences
→ Red banner disappears
→ Notifications stop

On Huawei specifically:
→ After "Simulate Permission Lost" verify:
   - AccessibilityService status also checked
   - ServiceWatchdogWorker detects missing service
   - Battery optimization warning shown if service killed
