# CLAUDE.md — Detox App

## Project Overview

**Detox** is an Android app designed to reduce screen time. It differentiates from existing solutions through two core mechanisms:

1. **Proof of Addiction** — An anti-cheat system that prevents users from tracking apps they never use. Only apps with verifiably high usage (≥45 min/day OR ≥20 opens/day averaged over the last 14 days) can be selected for challenges.
2. **Two Escalation Levels** — A gamified Soft Mode (points) and a Hard Mode where real money is at stake (via Stripe).

**Target Audience:** 16–25 years old, Gen Z, high social media consumption.

---

## Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture |
| Local DB | Room (SQLite) |
| Backend | Firebase (Auth, Firestore, Cloud Functions) |
| Payments | Stripe Android SDK |
| Background Service | Foreground Service + WorkManager |
| App Detection | AccessibilityService |
| Overlay | SYSTEM_ALERT_WINDOW |
| Usage Data | UsageStatsManager |
| Auth | Firebase Auth (Google Sign-In) |
| Push | Firebase Cloud Messaging (FCM) |
| Monitoring | Firebase Crashlytics + Analytics |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 |
| Package Name | com.detox.app |

---

## Project Structure

```
com.detox.app/
├── data/
│   ├── local/
│   │   ├── db/
│   │   │   ├── DetoxDatabase.kt
│   │   │   ├── dao/
│   │   │   │   ├── ChallengeDao.kt
│   │   │   │   ├── DailyLogDao.kt
│   │   │   │   └── PointTransactionDao.kt
│   │   │   └── entity/
│   │   │       ├── ChallengeEntity.kt
│   │   │       ├── DailyLogEntity.kt
│   │   │       └── PointTransactionEntity.kt
│   │   └── preferences/
│   │       └── UserPreferences.kt          # DataStore
│   ├── remote/
│   │   ├── firebase/
│   │   │   ├── FirebaseAuthService.kt
│   │   │   ├── FirestoreService.kt
│   │   │   └── CloudFunctionsService.kt
│   │   └── stripe/
│   │       └── StripeService.kt
│   └── repository/
│       ├── ChallengeRepositoryImpl.kt
│       ├── UsageStatsRepositoryImpl.kt
│       ├── PointsRepositoryImpl.kt
│       └── PaymentRepositoryImpl.kt
│
├── domain/
│   ├── model/
│   │   ├── Challenge.kt
│   │   ├── ChallengeMode.kt               # enum: SOFT, HARD
│   │   ├── LimitType.kt                   # enum: TIME, SESSIONS
│   │   ├── ChallengeStatus.kt             # enum: ACTIVE, COMPLETED, FAILED
│   │   ├── DailyLog.kt
│   │   ├── AppUsageInfo.kt
│   │   ├── PointTransaction.kt
│   │   └── ProofOfAddictionResult.kt
│   ├── repository/
│   │   ├── ChallengeRepository.kt          # Interface
│   │   ├── UsageStatsRepository.kt         # Interface
│   │   ├── PointsRepository.kt             # Interface
│   │   └── PaymentRepository.kt            # Interface
│   └── usecase/
│       ├── GetAddictiveAppsUseCase.kt
│       ├── CreateChallengeUseCase.kt
│       ├── CheckDailyLimitUseCase.kt
│       ├── CalculatePointsUseCase.kt
│       ├── ProcessPaymentUseCase.kt
│       └── GetDailyStatsUseCase.kt
│
├── presentation/
│   ├── navigation/
│   │   └── DetoxNavGraph.kt
│   ├── theme/
│   │   ├── Color.kt
│   │   ├── Type.kt
│   │   └── Theme.kt
│   ├── screens/
│   │   ├── onboarding/
│   │   │   ├── OnboardingScreen.kt
│   │   │   └── OnboardingViewModel.kt
│   │   ├── dashboard/
│   │   │   ├── DashboardScreen.kt
│   │   │   └── DashboardViewModel.kt
│   │   ├── appselection/
│   │   │   ├── AppSelectionScreen.kt
│   │   │   └── AppSelectionViewModel.kt
│   │   ├── challengesetup/
│   │   │   ├── ChallengeSetupScreen.kt
│   │   │   └── ChallengeSetupViewModel.kt
│   │   ├── activechallenge/
│   │   │   ├── ActiveChallengeScreen.kt
│   │   │   └── ActiveChallengeViewModel.kt
│   │   ├── pointshop/
│   │   │   ├── PointShopScreen.kt
│   │   │   └── PointShopViewModel.kt
│   │   └── settings/
│   │       ├── SettingsScreen.kt
│   │       └── SettingsViewModel.kt
│   └── components/
│       ├── BlockingScreenOverlay.kt
│       ├── LimitReachedOverlay.kt
│       ├── AppUsageCard.kt
│       ├── ChallengeCard.kt
│       ├── PointsBadge.kt
│       └── StatsChart.kt
│
├── service/
│   ├── UsageTrackingService.kt             # Foreground Service
│   ├── AppDetectionAccessibilityService.kt # Accessibility Service
│   ├── OverlayManager.kt                  # SYSTEM_ALERT_WINDOW management
│   ├── DailyEvaluationWorker.kt            # WorkManager — daily evaluation
│   └── BootReceiver.kt                    # Restart service after reboot
│
├── di/
│   ├── AppModule.kt                        # Hilt Module
│   ├── DatabaseModule.kt
│   ├── NetworkModule.kt
│   └── RepositoryModule.kt
│
└── DetoxApplication.kt                     # Application class (Hilt)
```

---

## Core Features & Business Rules

### 1. Proof of Addiction (Anti-Cheat)

**Rule:** An app is only selectable for challenges if it meets AT LEAST ONE of these criteria (averaged over the last 14 days):
- ≥ 45 minutes daily usage
- ≥ 20 daily opens

**Technical Implementation:**
- `UsageStatsManager.queryUsageStats(INTERVAL_DAILY, start, end)` for usage time
- `UsageStatsManager.queryEvents(start, end)` for open events (event type `ACTIVITY_RESUMED`)
- Requires permission: `PACKAGE_USAGE_STATS` (manually granted in Settings)
- Third-party apps only — no system apps (no browser, no settings, no launcher)

**UI Display:**
- Apps meeting criteria → green tag "Trackable" + usage statistics shown
- Apps NOT meeting criteria → grayed out with message "Not enough usage in the last 14 days"

### 2. Soft Mode

**Limit Types:**
- **Type A (Time Limit):** e.g. "TikTok max 60 minutes/day"
- **Type B (Session Limit):** e.g. "TikTok max 5 times/day, 5 minutes each"

**Points System:**
- +10 points per day the limit was respected
- +1 bonus point per 5 minutes under the limit
- 0 points on days the limit was exceeded (NO deduction)
- Challenge abort (app uninstalled, service disabled) → challenge ends, no points awarded

**Points Redemption:**
- New app theme (Dark Neon, Retro, etc.): 50 points
- Custom app icon: 100 points
- Joker card (+15 min without point loss): 30 points
- Premium profile badge: 200 points

**On Limit Exceeded:**
- App is NOT locked
- Overlay appears: "You've reached your limit. Do you really want to continue and risk your points?"
- Two buttons: "Yes, continue" (0 points today) / "No, I'll stop" (app closes)
- Overlay reappears every 5 minutes if the user continues using the app

### 3. Hard Mode

**Identical to Soft Mode PLUS:**
- Money amount: €5 to €50 per challenge
- Duration: 7, 14, 30 days or custom period
- Payment via Stripe pre-authorization at challenge start

**Stripe Flow:**
1. Challenge start → `PaymentIntent` with `capture_method: "manual"` (pre-auth)
2. IMPORTANT: Pre-auth expires after 7 days with most card providers. For challenges >7 days: capture immediately and refund on success.
3. Daily limit exceeded → Backend (Firebase Cloud Function) calls `stripe.paymentIntents.capture()`
4. Challenge completed → Backend calls `stripe.paymentIntents.cancel()` (or `refund` if already captured)

**Money Distribution on Loss:**
- 50% → Charitable donation
- 50% → Service fee (company revenue)

**On Limit Exceeded:**
- Overlay: "You've reached your limit. If you continue, you'll lose €[amount]."
- If user continues anyway → money is captured immediately, app is COMPLETELY LOCKED for the rest of the day
- Emergency unlock: 6-digit code (generated at challenge start), but money is still lost + 50 points deducted

### 4. Blocking Screen ("Stop & Think")

**Appears:** Every time BEFORE a tracked app is opened.

**Content:**
- User's custom motivation text (optional, free text field set during challenge setup)
- Today's usage vs. limit (e.g. "38 of 60 min")
- Today's opens vs. limit (e.g. "4 of 5")
- Current points earned today
- In Hard Mode: amount at stake
- Two buttons: "Open app anyway" / "No, I'll skip it"

**Technical:** AccessibilityService detects app switch → OverlayManager shows blocking screen as `TYPE_APPLICATION_OVERLAY`

### 5. Daily Evaluation

**WorkManager job (DailyEvaluationWorker)** runs every day at 23:59:
- Checks if daily limit was respected for each active challenge
- Calculates points
- Creates DailyLog entry
- In Hard Mode: triggers Stripe capture via Cloud Function if needed
- Checks if challenge end date is reached → sets status to COMPLETED or FAILED

---

## Data Model

### Room (Local)

```kotlin
@Entity(tableName = "challenges")
data class ChallengeEntity(
    @PrimaryKey val id: String,                    // UUID
    val appPackageName: String,                     // e.g. "com.zhiliaoapp.musically"
    val appDisplayName: String,                     // e.g. "TikTok"
    val mode: String,                               // "soft" | "hard"
    val limitType: String,                          // "time" | "sessions"
    val limitValueMinutes: Int,                     // For "time": total minutes. For "sessions": minutes per session
    val limitValueSessions: Int?,                   // Only for "sessions": max number of opens
    val startDate: Long,                            // Epoch millis
    val endDate: Long,                              // Epoch millis
    val amountCents: Int?,                          // Hard mode only, in cents (e.g. 2000 = €20)
    val stripePaymentIntentId: String?,             // Hard mode only
    val emergencyCode: String?,                     // Hard mode only, 6 digits
    val customMotivation: String?,                  // User's custom text
    val status: String,                             // "active" | "completed" | "failed"
    val createdAt: Long
)

@Entity(tableName = "daily_logs")
data class DailyLogEntity(
    @PrimaryKey val id: String,                    // UUID
    val challengeId: String,                        // FK → challenges.id
    val date: Long,                                 // Epoch millis (start of day)
    val totalMinutes: Int,
    val openCount: Int,
    val pointsEarned: Int,
    val limitExceeded: Boolean,
    val moneyLostCents: Int                         // 0 if no loss
)

@Entity(tableName = "point_transactions")
data class PointTransactionEntity(
    @PrimaryKey val id: String,                    // UUID
    val type: String,                               // "earned" | "spent" | "penalty"
    val amount: Int,                                // Positive number
    val reason: String,                             // "daily_goal_met" | "bonus_under_limit" | "theme_purchased" | "emergency_unlock"
    val challengeId: String?,                       // Optional, reference to challenge
    val timestamp: Long
)
```

### Firestore (Remote, for sync & Hard Mode)

```
users/{userId}/
  - displayName: String
  - email: String
  - totalPoints: Number
  - createdAt: Timestamp
  
  challenges/{challengeId}/
    - [mirrors ChallengeEntity]
    - syncedAt: Timestamp
    
    dailyLogs/{date}/
      - [mirrors DailyLogEntity]

  pointTransactions/{transactionId}/
    - [mirrors PointTransactionEntity]
```

---

## Android Permissions

```xml
<!-- Read usage data (14-day history) -->
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions" />

<!-- Draw overlay on top of other apps -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<!-- Run in background -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<!-- Restart service after device reboot -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Internet for Firebase & Stripe -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Notifications (Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

**Accessibility Service** is declared in `AndroidManifest.xml` and must be manually enabled by the user in device settings.

---

## Dependencies (build.gradle.kts)

```kotlin
// Jetpack Compose BOM
implementation(platform("androidx.compose:compose-bom:2024.02.00"))
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.ui:ui-tooling-preview")
implementation("androidx.activity:activity-compose:1.8.2")
implementation("androidx.navigation:navigation-compose:2.7.7")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

// Room
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
kapt("androidx.room:room-compiler:2.6.1")

// Hilt (Dependency Injection)
implementation("com.google.dagger:hilt-android:2.50")
kapt("com.google.dagger:hilt-compiler:2.50")
implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

// Firebase
implementation(platform("com.google.firebase:firebase-bom:32.7.1"))
implementation("com.google.firebase:firebase-auth-ktx")
implementation("com.google.firebase:firebase-firestore-ktx")
implementation("com.google.firebase:firebase-functions-ktx")
implementation("com.google.firebase:firebase-messaging-ktx")
implementation("com.google.firebase:firebase-crashlytics-ktx")
implementation("com.google.firebase:firebase-analytics-ktx")

// Google Sign-In
implementation("com.google.android.gms:play-services-auth:21.0.0")

// Stripe
implementation("com.stripe:stripe-android:20.37.4")

// WorkManager
implementation("androidx.work:work-runtime-ktx:2.9.0")

// DataStore
implementation("androidx.datastore:datastore-preferences:1.0.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

// Logging
implementation("com.jakewharton.timber:timber:5.0.1")
```

---

## Coding Conventions

- **Language:** Kotlin only, no Java
- **UI:** Jetpack Compose exclusively, no XML layouts
- **State Management:** StateFlow in ViewModels, collectAsStateWithLifecycle() in Composables
- **Async:** Kotlin Coroutines + Flow, no callbacks
- **DI:** Hilt for all dependencies
- **Naming:** English variable/class/method names, English comments
- **Error Handling:** Result<T> pattern for repository methods, try/catch in ViewModels
- **Logging:** Timber for debug logs
- **Testing:** Write at least one unit test for every UseCase
- **Strings:** All user-facing strings must be in strings.xml for future localization
- **UI State:** Use sealed classes or sealed interfaces (Loading, Success, Error)
- **Deprecated APIs:** Never use deprecated Android APIs, always use the latest recommended approach
- **Composables:** Prefer small, focused composables over large monolithic screens

---

## Onboarding Flow (Permissions)

The onboarding screen must guide the user through these permissions — in this order:

1. **Google Sign-In** → Firebase Auth
2. **Usage Data Access** → `Settings.ACTION_USAGE_ACCESS_SETTINGS` (UsageStats)
3. **Overlay Permission** → `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
4. **Accessibility Service** → `Settings.ACTION_ACCESSIBILITY_SETTINGS`
5. **Notifications** (Android 13+) → Runtime Permission `POST_NOTIFICATIONS`

Each step shows an explanation of WHY the permission is needed and a button that opens the relevant system setting. Upon returning to the app, it checks whether the permission was granted.

---

## Build Order (MVP Phases)

### Phase 1 — Foundation (Week 1–2)
1. Create Android project (Compose + Hilt + Room + Navigation)
2. Define theme & color palette
3. Onboarding with permissions flow
4. UsageStatsRepository: 14-day scan + Proof of Addiction logic
5. AppSelectionScreen: list of all apps with green/gray markers

### Phase 2 — Soft Mode (Week 3–4)
6. ChallengeSetupScreen (limit type, value, motivation text)
7. Save challenge to Room
8. Set up Foreground Service + Accessibility Service
9. Blocking screen overlay
10. Limit exceeded overlay (with 5-min interval)
11. DailyEvaluationWorker (calculate points, write DailyLog)
12. Dashboard with active challenges + points balance

### Phase 3 — Hard Mode (Week 5–6)
13. Firebase Auth (Google Sign-In)
14. Firestore sync for challenges + logs
15. Stripe integration (pre-auth, capture, refund)
16. Cloud Functions for Stripe webhooks + daily evaluation
17. Hard mode UI (amount, duration, emergency code)
18. Complete lockout on limit exceeded in Hard Mode

### Phase 4 — Polish (Week 7–8)
19. Points shop
20. Push notifications (reminders, daily report)
21. Statistics / weekly report screen
22. Set up Crashlytics + Analytics
23. Edge cases & testing
24. Play Store preparation

---

## Important Edge Cases

- **User uninstalls tracked app during challenge:** Challenge stays active, counts as "0 minutes" → earns points. Not cheating, since Proof of Addiction already verified historical addiction.
- **User disables Accessibility Service:** App detects this on next open → warning + prompt to re-enable. Points are NOT awarded for days without active service.
- **Midnight rollover:** Timers and limits reset at 00:00. Ongoing usage is counted toward the new day.
- **Device reboot:** BootReceiver automatically restarts the Foreground Service.
- **Stripe pre-auth expiry:** For challenges >7 days, capture the amount immediately and refund on success. Do NOT use pre-auth for long challenges.
- **Multiple simultaneous challenges:** A user can track multiple apps at the same time. Each challenge is independent.
- **Offline usage:** Tracking runs entirely locally. Firestore sync happens opportunistically when internet is available.
- **App updates:** If the tracked app's package name changes (rare), the challenge should still reference the original package name.

---

## Claude Code Instructions

- Always read this file before making changes.
- Work on ONE feature at a time. Do not combine multiple features in a single step.
- After each completed feature, suggest a git commit with a descriptive message.
- When creating new files, follow the project structure defined above exactly.
- When unsure about a business rule, refer to the "Core Features & Business Rules" section — do not guess.
- Prefer small, focused composables over large monolithic screens.
- All user-facing strings must be extracted to strings.xml for future localization.
- Use sealed classes or sealed interfaces for UI state (Loading, Success, Error).
- Do not use deprecated Android APIs. Always check for the latest recommended approach.
