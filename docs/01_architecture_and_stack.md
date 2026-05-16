# 01 — Architecture & Stack
> **Scope:** Tech-Stack, MVVM/Clean Architecture, File Structure, Code Rules, DB Migrations, Cloud Functions Pattern.
> **When to load:** Any new screen, ViewModel, Repository, UseCase, DB change, or Cloud Function.

---

## App Identity
- **Package:** `com.detox.app`
- **Platform:** Android (minSdk 26)
- **Language:** Kotlin
- **Firebase Project:** `detox-33208`
- **GitHub:** `https://github.com/saninbrca/ScreenStake` (private, branch: `main`)

---

## Tech Stack

### Android
| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture + Hilt (DI) |
| Local DB | Room (SQLite) |
| Async | Kotlin Coroutines + Flow |
| Navigation | Jetpack Navigation Compose |
| Logging | Timber (NEVER `Log` directly) |
| Font | Poppins (assets/fonts/) |

### Backend & Cloud
| Layer | Technology |
|-------|-----------|
| Auth | Firebase Authentication (Email/Password primary, Google Sign-In secondary) |
| Database | Firestore |
| Functions | Firebase Cloud Functions (Node.js 22 / TypeScript, region: `us-central1`) |
| Crash Reporting | Firebase Crashlytics |
| Payments | Stripe Android SDK + Stripe Node.js SDK in Cloud Functions |

### Services & Workers
- **Foreground Service:** `UsageTrackingService` — screen time tracking
- **Accessibility Service:** `AppDetectionAccessibilityService` — app detection + overlay trigger
- **Workers:** `DailyEvaluationWorker`, `DailyReminderWorker`, `PermissionCheckWorker`, `ServiceWatchdogWorker`
- **Boot:** `BootReceiver` — restarts services after device reboot

---

## File Structure

```
com.detox.app/
├── data/
│   ├── local/db/
│   │   ├── dao/
│   │   │   ├── ChallengeDao.kt
│   │   │   ├── DailyLogDao.kt
│   │   │   └── GroupChallengeDao.kt
│   │   ├── entity/
│   │   │   ├── ChallengeEntity.kt
│   │   │   ├── DailyLogEntity.kt
│   │   │   └── GroupChallengeEntity.kt
│   │   └── DetoxDatabase.kt          ← Room DB, check for current version
│   ├── remote/firebase/
│   │   ├── FirebaseAuthService.kt
│   │   ├── FirestoreService.kt
│   │   ├── CloudFunctionsService.kt  ← onRequest pattern (NOT onCall)
│   │   └── GroupChallengeFirestoreService.kt
│   └── repository/
│       ├── ChallengeRepositoryImpl.kt
│       ├── DailyLogRepositoryImpl.kt
│       ├── GroupChallengeRepositoryImpl.kt
│       ├── PaymentRepositoryImpl.kt
│       ├── SyncRepositoryImpl.kt
│       └── UsageStatsRepositoryImpl.kt
├── di/
│   ├── AppModule.kt
│   ├── DatabaseModule.kt
│   ├── NetworkModule.kt
│   └── RepositoryModule.kt
├── domain/
│   ├── model/
│   │   ├── Challenge.kt
│   │   ├── ChallengeMode.kt
│   │   ├── ChallengeStatus.kt
│   │   ├── DailyLog.kt
│   │   ├── GroupChallenge.kt
│   │   ├── GroupChallengeStatus.kt
│   │   ├── LimitType.kt
│   │   ├── Participant.kt
│   │   ├── PartialBlockSection.kt    ← NEW
│   │   └── ThresholdFlags.kt
│   ├── repository/        ← interfaces
│   └── usecase/
│       ├── CheckDailyLimitUseCase.kt
│       ├── CreateChallengeUseCase.kt
│       ├── CreateGroupChallengeUseCase.kt
│       ├── GetAddictiveAppsUseCase.kt
│       ├── GetDailyStatsUseCase.kt
│       ├── GetStatisticsUseCase.kt
│       ├── JoinGroupChallengeUseCase.kt
│       ├── ProcessPaymentUseCase.kt
│       └── SyncUserDataUseCase.kt
├── presentation/
│   ├── components/
│   │   ├── BlockingScreenOverlay.kt
│   │   ├── BudgetSelectionOverlay.kt
│   │   ├── DetoxHorizontalPicker.kt  ← reusable horizontal scroll number picker
│   │   │                                Used in: all challenge creation wizard steps + BudgetSelectionOverlay
│   │   │                                Params: values: List<Int>, selectedValue: Int,
│   │   │                                        onValueChange: (Int) -> Unit, isDark: Boolean
│   │   │                                isDark=true:  selected=#FFF, unselected=#444 (overlays)
│   │   │                                isDark=false: selected=#000, unselected=#AAA (wizard, white bg)
│   │   ├── HardModeLockoutOverlay.kt
│   │   ├── LimitExceededOverlay.kt
│   │   ├── SessionIntentionOverlay.kt
│   │   ├── SessionLimitReachedOverlay.kt
│   │   ├── TauntOverlay.kt
│   │   └── WebsiteBlockedOverlay.kt
│   ├── navigation/
│   │   ├── DetoxNavGraph.kt
│   │   └── MainScreen.kt
│   └── screens/
│       ├── activechallenge/   ActiveChallengeScreen + ViewModel
│       ├── appselection/      AppSelectionScreen + ViewModel
│       ├── auth/              AuthScreen + ViewModel
│       ├── challengecreation/ ChallengeCreationScreen (7-step wizard) + ViewModel
│       ├── dashboard/         DashboardScreen + ViewModel
│       ├── friends/           FriendsHubScreen + ViewModel
│       ├── groupchallenge/
│       │   ├── create/        GroupChallengeCreateScreen + ViewModel
│       │   ├── detail/        GroupChallengeDetailScreen + ViewModel
│       │   └── join/          GroupChallengeJoinScreen + ViewModel
│       ├── history/           HistoryScreen + ViewModel
│       ├── onboarding/        OnboardingScreen + ViewModel
│       ├── profile/           ProfileScreen + ViewModel
│       ├── settings/          SettingsScreen + ViewModel
│       └── statistics/        StatisticsScreen + ViewModel
├── service/
│   ├── AppDetectionAccessibilityService.kt  ← CORE
│   ├── BootReceiver.kt
│   ├── DailyEvaluationWorker.kt
│   ├── DailyReminderWorker.kt
│   ├── DetoxFirebaseMessagingService.kt
│   ├── NotificationHelper.kt
│   ├── OverlayManager.kt                    ← CORE
│   ├── PermissionCheckWorker.kt
│   ├── TrackedAppEventBus.kt
│   └── UsageTrackingService.kt
├── ui/theme/
│   ├── Colors.kt
│   ├── DetoxTheme.kt
│   ├── Shape.kt
│   └── Type.kt
├── DetoxApplication.kt
└── MainActivity.kt

functions/src/index.ts   ← ALL Cloud Functions
assets/
├── adult_domains.txt    ← 100+ adult domains
└── fonts/               ← Poppins (regular/medium/semibold/bold/extrabold)
admin/index.html         ← Admin payout dashboard (contains Firebase credentials → .gitignore)
```

---

## Naming Conventions

```
ViewModels:   [Screen]ViewModel.kt
Screens:      [Screen]Screen.kt
Entities:     [Name]Entity.kt
UseCases:     [Action][Target]UseCase.kt
Services:     [Name]Service.kt
Workers:      [Name]Worker.kt
Repositories: [Name]RepositoryImpl.kt (impl) / [Name]Repository.kt (interface)
```

---

## Room Database Rules

```kotlin
// Check DetoxDatabase.kt for CURRENT version before any change.
// ALWAYS add a migration for ANY schema change — NEVER destructive reset in production.

// Nullable column (simple):
// ALTER TABLE challenges ADD COLUMN newField TEXT DEFAULT NULL

// Non-nullable / complex changes:
// CREATE new table → INSERT from old → DROP old → RENAME new

// DailyLog date stored as Long (start of day in ms):
val today = DateUtils.todayKey()   // NEVER use 86400000 inline

// consciousOpens: write to Room AND Firestore on every increment.
// Read from Firestore on app start to restore after reinstall.
```

---

## Partial App Blocking — Architecture Notes

### PartialBlockSection Enum
Location: `domain/model/PartialBlockSection.kt`
Contains: `id`, `appPackage`, `displayName`, `subRowDescription`, `activityNames`, `viewIds`, `contentDescriptions`

Supported sections:
- `INSTAGRAM_REELS`    → `com.instagram.android`
- `YOUTUBE_SHORTS`     → `com.google.android.youtube`
- `TIKTOK_FORYOU`      → `com.zhiliaoapp.musically`
- `FACEBOOK_REELS`     → `com.facebook.katana`
- `TWITTER_FORYOU`     → `com.twitter.android`
- `SNAPCHAT_SPOTLIGHT` → `com.snapchat.android`

Companion object helpers:
```kotlin
PartialBlockSection.fromId(id: String): PartialBlockSection?
PartialBlockSection.BY_PACKAGE: Map<String, List<PartialBlockSection>>
PartialBlockSection.SUPPORTED_PACKAGES: Set<String>
```

### In-memory Cache (TrackedAppEventBus)
```kotlin
// Updated by UsageTrackingService whenever the challenge list changes:
TrackedAppEventBus.updateActivePartialBlockSections(
    challenges
        .filter { it.status == ChallengeStatus.ACTIVE }
        .flatMap { it.partialBlockSections }
        .distinct()
)

// Read by AppDetectionAccessibilityService on every event — zero DB queries:
val sections = TrackedAppEventBus.activePartialBlockSections.value
    .filter { it.appPackage == packageName }
```

### Room Migration
Version 20 → 21. Two columns added to `challenges` table:
```sql
ALTER TABLE challenges ADD COLUMN partial_block_sections TEXT NOT NULL DEFAULT ''
ALTER TABLE challenges ADD COLUMN partial_block_only INTEGER NOT NULL DEFAULT 0
```
`partial_block_sections` stored as comma-separated section IDs: `"instagram_reels,youtube_shorts"`
`partial_block_only = 1` → package excluded from `trackedPackages` (no full-block overlay)

### Firestore Storage
```
partialBlockSections: ["instagram_reels", "youtube_shorts"]   ← List<String> of IDs
isPartialBlockOnly: false                                      ← Boolean
```
Parsed in `FirestoreService` → `SyncRepositoryImpl.toEntity()` → mapped to enum via `PartialBlockSection.fromId()`

### Event Detection Order (performance critical)
1. `TYPE_WINDOW_STATE_CHANGED` → `handlePartialBlock()` called FIRST (before content renders)
2. `TYPE_WINDOW_CONTENT_CHANGED` → `handlePartialBlock()` called for in-activity navigation

On match: `performGlobalAction(GLOBAL_ACTION_BACK)` + `Toast.makeText(..., LENGTH_SHORT)`
1-second cooldown (`lastPartialBlockTimeMs`) prevents rapid-fire triggers.

**NEVER query Room/DB in the partial block hot path — always use `TrackedAppEventBus` cache.**
**NEVER use `GLOBAL_ACTION_HOME` for partial blocks — must stay inside the app.**
**NEVER show overlay or increment `consciousOpens` for partial blocks.**

---

## DateUtils.todayKey() — MANDATORY GLOBAL RULE

NEVER calculate today's date key inline anywhere in the codebase.
ALWAYS use DateUtils.todayKey().

```kotlin
// DateUtils.kt
object DateUtils {
    fun todayKey(): Long {
        val cal = Calendar.getInstance() // device local timezone
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

// CORRECT:
val date = DateUtils.todayKey()

// WRONG — NEVER do this:
val date = System.currentTimeMillis() / 86400000 * 86400000
val date = System.currentTimeMillis() / 86400000L * 86400000L
```

Search rule: grep for "86400000" must return 0 results across entire codebase.
If found anywhere → replace with DateUtils.todayKey() immediately.

Why: UTC vs local timezone mismatch caused Dashboard to always show 0
for all challenges (Vienna = UTC+2 = 12h difference from UTC midnight).
Fixed 2026-05-01. Never revert.

---

## Universal Sync Pattern (all time-based tracking)

This pattern applies to ALL challenge types and ALL limit types.
Never invent a new pattern — always follow this:

WRITE (during operation):
1. Write to Room immediately (user sees update instantly)
2. Write to Firestore fire-and-forget (SetOptions.merge())
Never await Firestore before updating UI

RESTORE (on app start):
1. Read from Firestore dailyLogs/{challengeId}_{DateUtils.todayKey()}
2. Write restored values to Room DailyLog
3. Set SharedPreferences if needed (budget session state)
4. UsageTrackingService.onStartCommand() reads from Room

HUAWEI RULE:
Never rely on onDestroy() for critical writes.
Any state that must survive Huawei kill:
→ Write periodically every 10s during operation
→ Sync to Firestore every 10s (fire-and-forget)
→ Restore from Firestore on app start

---

## Logging Rules

```kotlin
// Always Timber, NEVER Log directly.
Timber.d("Challenge $challengeId: opens=$opens limit=$limit exceeded=$exceeded")
Timber.e("Stripe capture failed: ${e.message}")
Timber.w("Group challenge not found: $groupId")

// Include timestamps for performance-critical paths:
Timber.d("[${System.currentTimeMillis()}] Overlay shown for $packageName")
```

---

## Cloud Functions Pattern (ALL functions)

```typescript
// ALL functions use onRequest (NOT onCall).
// Reason: onCall requires Google Play Services → breaks on Huawei.
export const functionName = functions.https.onRequest(async (req, res) => {
    const authHeader = req.headers.authorization
    if (!authHeader?.startsWith('Bearer ')) {
        res.status(401).json({ error: 'Unauthorized' })
        return
    }
    const idToken = authHeader.split('Bearer ')[1]
    const decodedToken = await admin.auth().verifyIdToken(idToken)
    const userId = decodedToken.uid
    // ... function logic
    res.json({ success: true, data: result })
})
```

### Android → Cloud Function Call Pattern

```kotlin
// Always get a FRESH token before every Cloud Function call.
val token = FirebaseAuth.getInstance().currentUser
    ?.getIdToken(true)?.await()?.token
    ?: throw Exception("Not authenticated")

val request = Request.Builder()
    .url("https://us-central1-detox-33208.cloudfunctions.net/functionName")
    .addHeader("Authorization", "Bearer $token")
    .addHeader("Content-Type", "application/json")
    .post(jsonBody.toRequestBody("application/json".toMediaType()))
    .build()
```

### Deployed Cloud Functions (index.ts)
- `createPaymentIntent`
- `capturePayment`
- `cancelOrRefundPayment`
- `createGroupChallenge`
- `joinGroupChallenge`
- `startGroupChallenge`
- `failParticipant`
- `completeGroupChallenge`
- `createConnectedAccount`
- `confirmGroupJoin`

### Deploy Commands
```bash
firebase deploy --only functions               # all functions
firebase deploy --only functions:functionName  # single function (faster)
```

---

## Firestore DailyLog Structure (all challenge types)

Path: `users/{userId}/dailyLogs/{challengeId}_{DateUtils.todayKey()}`
```
{
  challengeId: String,
  date: Long,                  ← DateUtils.todayKey() always
  consciousOpens: Int,         ← SESSION_LIMIT
  timeUsedMs: Long,            ← TIME_LIMIT
  budgetUsedMs: Long,          ← DAILY_BUDGET
  budgetRemainingMs: Long,     ← DAILY_BUDGET
  overlayPausedMs: Long,       ← all types
  limitExceeded: Boolean,      ← all types
  updatedAt: Long              ← all types
}
```

### Write Rules
- Always SetOptions.merge() — NEVER plain `.set()` on dailyLogs documents
- consciousOpens: write immediately on tap (atomic)
- timeUsedMs: write every 10s via UsageTrackingService (fire-and-forget)
- budgetUsedMs: write every 10s via UsageTrackingService (fire-and-forget)
- Never block UI or overlay on Firestore response

### Read Rules (app start restore)
1. Read from Firestore dailyLogs for today (DateUtils.todayKey())
2. Write all fields to Room DailyLog
3. Set SharedPreferences for active session state (budget only)
4. UsageTrackingService reads from Room after restore

### Room DailyLog Entity must match Firestore fields exactly
Check DetoxDatabase.kt version before adding any new column.
Always add Room migration — never destructive reset in production.

---

## Firestore Data Patterns

```kotlin
// endDate smart detection (old records stored duration ms, new = absolute timestamp):
val endDateMs = if (endDate > 1700000000000L) endDate
                else startDate + endDate

// createdAt parsing (Firestore Timestamp object vs Long):
val createdAt = when (val raw = doc.get("createdAt")) {
    is com.google.firebase.Timestamp -> raw.toDate().time
    is Long -> raw
    else -> System.currentTimeMillis()
}

// participants parsing (handles partial update snapshots):
val participants = when (val raw = doc.get("participants")) {
    is List<*> -> (raw as List<Map<String, Any>>).map { it.toParticipant() }
    is Map<*, *> -> raw.values.mapNotNull { (it as? Map<String, Any>)?.toParticipant() }
    else -> emptyList()
}
```

---

## Design System (DetoxTheme.kt)

| Token | Light | Dark |
|-------|-------|------|
| Primary | `#00C853` | `#00E676` |
| Background | `#FFFFFF` | `#0F0F0F` |
| Font | Poppins | Poppins |
| Corner radii | 8 / 16 / 24 / 32 dp | same |

- Material 3 throughout
- Dark Mode toggle saved in `SharedPreferences`

---

## Secrets & .gitignore

```
# Must NEVER be committed:
google-services.json
functions/.env          ← Stripe keys live here ONLY
*.keystore
*.jks
firebase-debug.log
firebase-debug.*.log
admin/index.html
```

---

## Claude Code Settings Reference

```json
// ~/.claude/settings.json
{
  "model": "sonnet",
  "env": {
    "MAX_THINKING_TOKENS": "10000",
    "CLAUDE_AUTOCOMPACT_PCT_OVERRIDE": "50",
    "CLAUDE_CODE_SUBAGENT_MODEL": "haiku"
  }
}
```

## Development Workflow

```
1. Write prompt here → copy to Claude Code /plan mode
2. Test on real Huawei device after EVERY change
3. Logcat filter: package:com.detox.app level:error
4. git add . && git commit -m "feat/fix: description" after each working feature
5. /compact in Claude Code after long sessions
6. /clear in Claude Code when switching topics
7. firebase deploy --only functions after any Cloud Function change
```

## Debug Testing Panel

Available in DEBUG builds only (BuildConfig.DEBUG).
Location: ProfileScreen → bottom → "🛠 Debug Tools" (collapsible)

NEVER add debug code outside BuildConfig.DEBUG check.
NEVER commit debug flags as true.
NEVER use debug time manipulation in production.

Quick reference — what each section does:
- Onboarding: reset first-start flag
- Daily Evaluation: trigger worker immediately
- Time Manipulation: shorten challenge duration for testing
- Budget: reset or exhaust daily budget instantly
- Opens: reset or max out conscious opens instantly
- Group Challenge: force complete/fail without waiting
- Stripe: test card info + dashboard link
- Room Database: inspect and clear DailyLogs
- Permissions: check status + simulate permission loss

Full documentation: docs/06_testing_guide.md

---

## Account & Auth Rules
See docs/07_onboarding_and_auth.md for complete auth documentation.
Quick reference:

* Primary auth: Email/Password (all devices)
* Secondary: Google Sign-In (Play Services only, never on Huawei)
* Logout: clear Room FIRST, then Firebase signOut (never reverse)
* Hard Mode active → logout and account deletion blocked

---

## Fortschrittsbalken — Global Rule

Progress bar and remaining values must be identical in:
- Dashboard card
- Detail screen
- Active challenge screen
- Overlay (where applicable)

Source of truth: Room DailyLog (read fresh via DateUtils.todayKey())

```kotlin
// CORRECT — read fresh from Room on every screen init:
val dailyLog = dailyLogDao.getByKey("${challengeId}_${DateUtils.todayKey()}")

// WRONG — never use passed navigation arguments for progress:
// val opensToday = navBackStackEntry.arguments?.getInt("opensToday")

// WRONG — never use stale ViewModel state:
// val progress = viewModel.progressState.value (if set before navigation)
```

Progress calculation (identical everywhere):
```kotlin
val progress = when (challenge.limitType) {
    SESSION_LIMIT -> dailyLog.consciousOpens.toFloat() / challenge.limitValueSessions
    TIME_LIMIT -> dailyLog.timeUsedMs.toFloat() / (challenge.limitValueMinutes * 60000f)
    DAILY_BUDGET -> dailyLog.budgetUsedMs.toFloat() / (challenge.dailyBudgetMinutes * 60000f)
    TIME_WINDOW_ONLY -> 0f // no progress bar
}.coerceIn(0f, 1f)

val remaining = when (challenge.limitType) {
    SESSION_LIMIT -> "${challenge.limitValueSessions - dailyLog.consciousOpens} opens"
    TIME_LIMIT -> "${((challenge.limitValueMinutes * 60000L) - dailyLog.timeUsedMs) / 60000} min"
    DAILY_BUDGET -> "${dailyLog.budgetRemainingMs / 60000} min"
    TIME_WINDOW_ONLY -> ""
}
```
