# 05 — Huawei & Permissions
> **Scope:** All Huawei-specific constraints, 24h permission monitoring system, AccessibilityService rules, FLAG_SECURE overlay requirements, Adult Content blocking.
> **When to load:** Any work on notifications, auth, AccessibilityService, overlay permissions, permission monitoring, adult content filtering, or anything that might behave differently on Huawei.
> _Last verified: 2026-07-19 (commit 4b54701)_

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
- **Permission warnings:** `PermissionCheckWorker` — escalating urgency (see 24h system below)
- **Challenge events:** money/security events, completion, and 80%-usage — all via local notifications
  (the daily-reminder / report / lifecycle notifications were removed — see changelog "Notification cleanup")

### WorkManager Scheduling

```kotlin
// Permission check (every 15 min):
val permCheck = PeriodicWorkRequestBuilder<PermissionCheckWorker>(15, TimeUnit.MINUTES)
    .build()
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "permission_check",
    ExistingPeriodicWorkPolicy.KEEP,
    permCheck
)

// Daily evaluation (23:59, requires network — Hard Mode refunds depend on it):
// PeriodicWorkRequest<DailyEvaluationWorker> with
// Constraints.setRequiredNetworkType(NetworkType.CONNECTED). NEVER remove the constraint.
```

---

## 24-Hour Permission Monitoring System

Triggered when: overlay permission OR AccessibilityService permission is lost while a Hard Mode challenge is active.

### Escalation Timeline (current)

**Acceleration rule (implemented):** If the user opens the app and ignores the warning within the first 12 hours, the effective deadline accelerates (`ACCELERATE_THRESHOLD_MS = 12h`). After hour 12 the timer runs at a fixed pace regardless of user action.

**Visual indicator:** Red pulsing banner shown on ALL screens while any required permission is missing.

**Notification stages (actual — `PermissionCheckWorker.handleEscalation` / `NotificationHelper.sendPermissionEscalation`):** exactly three staged notifications fire at **6h, 12h, 23h** elapsed (highest passed threshold wins). There is no hour-0 or hour-2 notification.

| Time since loss | Action |
|----------------|--------|
| Hour 6  | `sendPermissionEscalation("6h")` — escalating-urgency notification |
| Hour 12 | `sendPermissionEscalation("12h")` — "Letzte Warnung" (also acceleration cutoff) |
| Hour 23 | `sendPermissionEscalation("23h")` — explicit "In 1 Stunde wird der Einsatz eingezogen" |
| Hour 24 | **Server-side Stripe capture via Cloud Function** (`DEADLINE_MS = 24h`) |

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
Adult content = 100% blocked, ALWAYS — but ONLY per-URL.
No bypass, no "öffnen" option, no "Visit anyway".
Redirect the BROWSER to about:blank (user STAYS in the browser — never a
home-kick) + Toast, then an explanatory overlay (WebsiteBlockedOverlay
isAdultBlock variant) whose "Zurück" only dismisses.
goHome (GLOBAL_ACTION_HOME) is the FALLBACK only: used when overlay
permission is missing (no background-activity-launch exemption — the VIEW
intent would be silently dropped) or startActivity throws.
Adult-block REQUIRES overlay permission at creation (pre-flight gate,
2026-07-18): needsOverlay includes blockAdultContent, because the
about:blank redirect needs SYSTEM_ALERT_WINDOW as its background-activity-
launch exemption AND the explanation overlay needs it to draw. The goHome
fallback is DEFENSIVE only — it covers a user revoking the permission
AFTER the challenge started, never a supported configuration.
```

> **DECISION (2026-07-17): NO blanket incognito blocking.** The former incognito
> detection (window-title / page-text scan for "incognito"/"private"/"privat")
> locked users out of the ENTIRE browser while any private tab existed (until the
> browser was swiped from Recents) and false-positived on normal pages containing
> the word "privat". Removed. Adult blocking relies on the per-URL address-bar
> check, which works in incognito too (Chrome exposes `url_bar` to accessibility
> in private tabs). Do NOT re-add content-text scanning.

### Implementation

```kotlin
// AppDetectionAccessibilityService monitors URL bar content
// in ALL major browsers (Chrome, Firefox, Samsung Browser, etc.)

// Domain list: ~133k domains (script-generated — see scripts/update_adult_domains.py; OISD, StevenBlack, ut1 blocklists)
// Updated monthly by AdultDomainsUpdateWorker (WorkManager) from https://nsfw-small.oisd.nl/
//   (NSFW list! NOT small.oisd.nl = ad-block list — that mistake silently disabled
//    adult blocking; a canary check ["pornhub.com" must be present] now prevents it)
// Loaded at service start: bundled assets list MERGED with the updated file (union —
// updates only ever ADD coverage), cached in HashSet<String>
// Self-heal (AdultDomains.loadDomains): an existing updated file whose header
// contains "OISD Small" (the old wrong-list worker bug) is DELETED on load, so
// poisoned devices recover on the next service start without waiting a month.

// Subdomain matching (AdultDomains.hostMatches — dot-boundary HashSet walk):
// checks the host itself, then every dot-boundary suffix, each as an O(1)
// HashSet lookup — O(label count), NOT an O(list size) scan, and never a bare
// substring match:
private fun hostMatches(rawHost: String): Boolean {
    val host = rawHost.lowercase().trim('.')
    if (domains.contains(host)) return true
    var remaining = host
    while (remaining.contains('.')) {
        remaining = remaining.substringAfter('.')
        if (domains.contains(remaining)) return true
    }
    return false
}
// "de.pornhub.com" matches "pornhub.com" ✅
// "notpornhub.com" / "pornhub.com.evil.com" do NOT match ✅ (pinned by AdultDomainsMatchTest)

// On match (2s cooldown + dismissal-anchored suppression, see below):
// 1. Toast R.string.adult_block_toast ("🔞 Von Finite blockiert")
// 2. emitAdultBlocked(host) → OverlayManager.showAdultBlockedOverlay
//    (WebsiteBlockedOverlay isAdultBlock=true, over the browser's neutral page;
//     "Zurück" only dismisses; skipped gracefully only in the DEFENSIVE
//     revoked-mid-challenge case — creation requires the permission)
// 3. redirectToNeutralPage(browserPackage):
//    - ONE GLOBAL_ACTION_BACK (pop adult page from visible history; never iterated)
//    - VIEW intent "about:blank" + setPackage(browser) + NEW_TASK → browser fronts
//      a neutral tab, adult tab demoted to background (media pauses). User STAYS
//      in the browser; loop broken by construction (about:blank → host "about" →
//      never matches). Fallback goHome() [GLOBAL_ACTION_HOME] when overlay
//      permission is missing (VIEW would be silently BAL-dropped) or start throws.
// 4. NO counter increment, NO bypass
// Known limitation (accepted): in incognito the VIEW intent opens a NORMAL-mode
// tab; the incognito adult tab survives in background and re-bounces if manually
// reopened — no lockout, page never viewable.

// URL extraction: address-bar view IDs ONLY (URL_BAR_IDS + generic id fallback).
// NEVER scan page text for URL-shaped strings — a displayed adult domain must
// not be mistaken for the current URL. Unreadable address bar ⇒ FAIL OPEN.
// Address-bar text is scheme-less ("example.com") ⇒ normalized to
// "https://example.com" before Uri.parse(), otherwise host is null (no match).
```

### Dismissal-anchored re-detect suppression (2026-07-18)

The 2s detection cooldown is anchored to the ORIGINAL detection, so after the user read the
block overlay for longer than 2s and dismissed it, the very next `WINDOW_CONTENT_CHANGED`
(address bar still on the blocked URL until the about:blank redirect lands) re-fired the block
and the overlay popped right back. Fix: a second, DISMISSAL-anchored guard.

- `OverlayManager` calls `TrackedAppEventBus.markBlockOverlayDismissed(target)` when a
  website/adult block overlay is dismissed (`target` = host for adult, matched domain for custom).
- The detection path (`checkBrowserUrl`, both adult and custom branches) checks
  `TrackedAppEventBus.isBlockRedetectSuppressed(target)` FIRST and skips re-firing while the
  same target's overlay was dismissed < 2s ago (`BLOCK_DISMISS_SUPPRESS_MS`).
- Per-target and short-lived: a different blocked page, or a genuine re-visit after the window,
  blocks normally. This is a UX guard, not a bypass — the page itself is already being redirected.

### Pre-flight enforcement-permission gate (challenge creation)

`ChallengeCreationViewModel.createChallenge()` refuses to create ANY challenge (Soft or Hard,
before the root check / payment / save) while an enforcement permission is missing — a challenge
without them would silently block nothing. UiState `MissingPermissions(needsUsage,
needsAccessibility, needsOverlay)` renders a dialog naming each missing permission with a grant
action (accessibility routes through the `AccessibilityDisclosureDialog` prominent disclosure).

- **Usage stats** — always required.
- **Accessibility** — always required (sole trigger for app blocking AND browser URL reading).
- **Overlay** — required when anything consumes it: the challenge blocks apps, OR
  `computeBlockedDomains()` is non-empty, OR `blockAdultContent` is on (since 2026-07-18 —
  the about:blank redirect's BAL exemption + the explanation overlay both need it).

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
Step 4 (Huawei only): Guide user to disable battery optimization for Finite
    → "Einstellungen → Apps → Finite → Akku → Keine Einschränkungen"
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

## Sentry SDK — Huawei Compatible (integrated)

Sentry Android SDK does **not** require Google Play Services. It uses its own HTTP transport
layer directly, so it is safe on Huawei. **Sentry is now the sole crash + error reporter —
Firebase Crashlytics has been removed.**

**Dependency:** `io.sentry:sentry-android`, pinned `7.14.0` via the version catalog
(`libs.sentry.android`). NEVER add `sentry-android-firebase` or any Firebase Performance
monitoring integration — those require Google Play Services and break Huawei.

**Init (`DetoxApplication.onCreate`):** `SentryAndroid.init` runs first (before
`PaymentConfiguration.init`). DSN from `BuildConfig.SENTRY_DSN`; environment from
`BuildConfig.DEBUG`; release `${APPLICATION_ID}@${VERSION_NAME}`; auto session tracking + ANR +
activity/app-component breadcrumbs; `sampleRate = 1.0`, `tracesSampleRate` 1.0 debug / 0.1
production. `beforeSend` returns `null` in DEBUG, so **debug builds never send events**.

**Manifest:** `io.sentry.dsn` meta-data (`${SENTRY_DSN}`) + `io.sentry.auto-init = false`
(we init manually for full control). DSN is a `buildConfigField` + `manifestPlaceholders`
placeholder in `defaultConfig` — replace with the real DSN after sentry.io project creation.

**User context (GDPR/DSGVO):** only the Firebase UID is sent as the Sentry user id — never
email or name. `Sentry.setUser` is set/cleared in the existing `FirebaseAuth.addAuthStateListener`
in `DetoxApplication.startGroupChallengeSyncing()` (single chokepoint for login/register/Google/logout).

**Breadcrumbs:** `PaymentRepositoryImpl.cancelOrRefundPayment` (central Stripe refund/cancel
chokepoint), `PermissionCheckWorker` (permission loss — type + elapsed),
`AppDetectionAccessibilityService` (`onServiceConnected` / `onInterrupt`).

**ProGuard:** keep `io.sentry.**` + annotation/source/line attributes.

See the changelog DECISION entry for the full file list.

---

## HapticManager

Direct `Vibrator` API is required for Huawei compatibility.
Compose `LocalHapticFeedback` (which calls `HapticFeedbackType.*`) is NOT reliable on Huawei EMUI.

```kotlin
// HapticManager.kt — wraps Vibrator directly
object HapticManager {
    fun light(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(30)
        }
    }
}
```

### Where haptic is used
- Wizard "Weiter" / "Next" buttons → `HapticManager.light()`
- App selection row taps → `HapticManager.light()`
- `DetoxHorizontalPicker` number change → `HapticFeedbackType.LongPress` (Compose local, sufficient for picker)

### Where haptic is NOT used
- ALL overlays (SessionIntentionOverlay, SessionLimitReachedOverlay, BudgetSelectionOverlay,
  TimeWindowOverlay, WebsiteBlockedOverlay, HardModeLockoutOverlay, LimitExceededOverlay) —
  **no haptic at all**. This is intentional: overlays are moments of friction, not reward.

---

## Summary: Huawei-Safe Development Checklist

```
□ Notifications → WorkManager/AlarmManager (never FCM)
□ Auth → Email/Password shown first (Google Sign-In hidden or shown last)
□ Cloud Functions → onRequest only (never onCall)
□ Overlays → Handler(Looper.getMainLooper()).post { } (never coroutines)
□ Overlays → FLAG_SECURE always set
□ Services → PermissionCheckWorker as 15min watchdog backup
□ Battery optimization → guide user in onboarding to whitelist Finite
□ Test → always verify on real Huawei hardware, not just emulator
```

## Server-side Permission Enforcement

When a Hard Mode challenge is active, the app mirrors the permission state to Firestore
so Stripe capture can happen server-side even if the app is uninstalled or data is cleared.

**All three loss cases now mirror to Firestore (May 2026):** overlay lost only ✅,
accessibility lost only ✅, both lost ✅. Previously `checkAccessibilityPermission()` wrote the
accessibility-only loss to SharedPreferences (`accessibilityLostAt`) only and never to Firestore,
so `checkPermissionViolations` (which queries `permissionLostAt != null`) could not see it — a
user could disable accessibility, keep overlay granted, wait 24h, and avoid server-side capture.
`checkAccessibilityPermission()` now also writes `permissionLostAt` / `permissionType="accessibility"`
/ `deviceId` on loss (via `SetOptions.merge()`, same pattern as the overlay branch), and on restore
writes `permissionLostAt = FieldValue.delete()` + `permissionRestoredAt = now`. The existing
SharedPreferences logic and the `sendAccessibilityLost` / `sendAccessibilityRestored` notifications
are unchanged.

**Firestore path:** `users/{uid}/permissionStatus/current`

| Field | Written by | Purpose |
|-------|-----------|---------|
| `permissionLostAt` | Android (PermissionCheckWorker) | Timestamp of first permission loss |
| `permissionType` | Android | "accessibility" \| "overlay" \| "both" |
| `deviceId` | Android | For multi-device detection |
| `permissionRestoredAt` | Android | Set when permissions restored; clears permissionLostAt |
| `lastSeenAt` | Android (PermissionCheckWorker heartbeat) | Liveness beat — proves the app is still installed/running (see below) |
| `capturedAt` | Cloud Function only | Set after Stripe capture — client CANNOT write |
| `captureReason` | Cloud Function only | "permission_violation" — client CANNOT write |

**Cloud Functions:**
- `checkPermissionViolations` (onRequest): queries Firestore for users with `permissionLostAt` > 24h
  and no `capturedAt`. Captures Stripe payment for all active Hard Mode solo + group participants.
  Accepts Bearer token or x-internal-secret header.
- `scheduledPermissionCheck` (pubsub, every 1 hour): same logic, runs automatically via Cloud Scheduler.
- Shared `runPermissionViolationCheck()` helper used by both.

**Capture order:** Stripe FIRST → Firestore update SECOND (same rule as all other capture paths).

---

## "Device went dark" heartbeat (`lastSeenAt`)

Android has no uninstall/disable callback, so an active solo Hard Mode device that simply stops running
can't be detected by a missing permission marker alone (uninstalling also stops the worker that would
write `permissionLostAt`). The fix is an inverted signal: prove **liveness** every cycle and treat its
ABSENCE as a forfeit.

- `PermissionCheckWorker.writeHeartbeatIfHardActive()` merges `lastSeenAt = now` into
  `permissionStatus/current` at the **top of `doWork()`** (before any early return), **gated on "user
  has ≥1 active HARD challenge"** so Soft-only / idle users never trigger needless writes.
- The server `runDueChallengeReconciliation` forfeits a Hard Mode stake as a went-dark LOSS
  (`failReason:"device_dark"`) once `lastSeenAt` (or `startDate` if never beat) is staler than
  `config/app.wentDarkGraceMs`. **FAIL-SAFE:** a missing grace ⇒ never forfeit. Triple-gated, ships dark.
- Best-effort local nudge: if EMUI suspended the worker for longer than ~grace/2 (36h) since the last
  successful beat, the device warns the user to open the app (`NotificationHelper.sendHeartbeatWarning`)
  — necessarily best-effort, since it can only fire once the throttled worker finally runs again.
- Full money-side detail: `docs/03` (went-dark section) + `docs/10 §5`; flag: `docs/13`.

---

## UsageStats Backup Detection

When Accessibility Service is disabled, `PermissionCheckWorker` runs `checkAndReportUsageViolation()`
each worker cycle as a backup detection path.

- `detectUsageViolation()` queries `UsageStatsManager.INTERVAL_BEST` for the last 1 hour.
- If any blocked package has > 1 min foreground time → violation detected.
- First detection: writes `usageViolationDetectedAt`, `violatingPackage`, `usageMinutes` to
  `users/{uid}/permissionStatus/current` (SetOptions.merge()).
- Local flag in `"detox_usage_violation"` SharedPrefs prevents repeat writes.
- Clears local flag when accessibility is re-enabled.
- Cloud Function `runPermissionViolationCheck()` also queries `usageViolationDetectedAt != null`.
  If > 1 hour elapsed and no `usageCapturedAt` set → captures Hard Mode Stripe payments.
- `usageCapturedAt` is CF-only (blocked by Firestore rules on client).
- NEVER counts as a conscious open — purely for violation detection.

---

## Rooted Device Detection

- Library: `com.scottyab:rootbeer-lib:0.1.0`
- Checked in `ChallengeCreationViewModel.createChallenge()` before initiating Hard Mode payment.
- If rooted: sets `RootedDeviceWarning` UiState + logs `isRooted: true` to
  `users/{uid}/deviceInfo/security` (fire-and-forget, does NOT block challenge creation).
- UI shows non-blocking `AlertDialog` with "Verstanden — trotzdem fortfahren" / "Abbrechen".
  User must explicitly acknowledge before payment proceeds.
- Root **never blocks** challenge creation — warn + log only.
- Firestore rule: `deviceInfo` sub-collection has `adminVerified` field blocked on client.

---

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
   - Red banner appears + permission escalation notifications fire
   - Battery optimization warning shown if service killed

### Debug Panel — PERMISSION VIOLATION TESTS section (DEBUG builds only)

5 buttons available in ProfileScreen Debug Panel:

1. **Simulate Permission Loss (Firestore)** — writes `permissionLostAt = now - 25h` directly to
   Firestore `permissionStatus/current`. Use to test server-side capture without waiting 24h.
2. **Simulate Usage Violation** — writes `usageViolationDetectedAt = now - 2h` to Firestore.
   Use to test the UsageStats backup capture path.
3. **Check Root Status** — runs RootDetectionManager and shows result in a dialog.
4. **Force CF Permission Check** — calls `checkPermissionViolations` CF immediately.
   Shows result (captured / nothing to capture / error).
5. **Reset Permission Status** — clears entire `permissionStatus/current` document in Firestore.
   Use to clean up after tests.
