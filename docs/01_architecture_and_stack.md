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
val today = System.currentTimeMillis() / 86400000 * 86400000

// consciousOpens: write to Room AND Firestore on every increment.
// Read from Firestore on app start to restore after reinstall.
```

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
