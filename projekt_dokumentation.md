# Detox App — Complete Project Documentation

## 1. Project Overview

**App Name:** Detox (Package: `com.detox.app`)  
**Platform:** Android (min SDK 26)  
**Target Audience:** Gen Z (16–25 years)  
**Language:** Kotlin  

### Purpose
Detox is an Android app for reducing screen time through gamified challenges.
Users select apps to block and set limits. When they try to open a blocked app,
an overlay appears asking if they really want to open it (conscious open system).

### Core Concept
- **Soft Mode:** No money involved. Streak-based motivation.
- **Hard Mode:** Real money staked via Stripe. Money captured on fail, refunded on success.
- **Group Challenges:** Hard Mode only. Multiple users stake money into a pot.
  Losers' money is distributed to winners.

### Anti-Cheat: Conscious Opens
The system NEVER uses UsageStatsManager to count opens.
Only explicit user confirmation via overlay ("Ja, öffnen") counts as an open.
This is the core differentiator from other apps.

### Business Model
- Hard Mode: App earns from failed challenges (money stays on Stripe)
- Group Challenges: 10% app fee from failed pot
- Payout to winners: Manual SEPA transfer by founder after winner submits IBAN

---

## 2. Tech Stack

### Android
- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Architecture:** MVVM + Clean Architecture + Hilt (DI)
- **Local DB:** Room (SQLite)
- **Async:** Kotlin Coroutines + Flow
- **Navigation:** Jetpack Navigation Compose
- **Logging:** Timber
- **Font:** Poppins (assets/fonts/)

### Backend & Cloud
- **Auth:** Firebase Authentication (Email/Password primary, Google Sign-In secondary)
- **Database:** Firestore (cloud sync)
- **Functions:** Firebase Cloud Functions (Node.js/TypeScript, region: us-central1)
- **Crash Reporting:** Firebase Crashlytics
- **Payments:** Stripe Android SDK + Stripe Node.js SDK in Cloud Functions

### Services & Workers
- **Foreground Service:** UsageTrackingService (screen time tracking)
- **Accessibility Service:** AppDetectionAccessibilityService (app detection + overlay)
- **Workers:** DailyEvaluationWorker, DailyReminderWorker, PermissionCheckWorker, ServiceWatchdogWorker
- **Boot:** BootReceiver (restarts services after reboot)

### Firebase Project
- **Project ID:** detox-33208
- **Auth:** Email/Password + Google Sign-In
- **Firestore Rules:** Auth-based per user
- **Cloud Functions:** 10 functions deployed in us-central1, Node.js 22

### Stripe
- **Mode:** Test mode (sk_test_...)
- **Key location:** functions/.env (NEVER in git)
- **Payment flow:** Authorize on start → Capture on fail → Refund on success

---

## 3. Architecture & File Structure

```
com.detox.app/
├── data/
│   ├── local/
│   │   └── db/
│   │       ├── dao/
│   │       │   ├── ChallengeDao.kt
│   │       │   ├── DailyLogDao.kt
│   │       │   └── GroupChallengeDao.kt
│   │       ├── entity/
│   │       │   ├── ChallengeEntity.kt
│   │       │   ├── DailyLogEntity.kt
│   │       │   └── GroupChallengeEntity.kt
│   │       └── DetoxDatabase.kt          ← Room DB, current version: 9+
│   ├── remote/
│   │   └── firebase/
│   │       ├── FirebaseAuthService.kt
│   │       ├── FirestoreService.kt
│   │       ├── CloudFunctionsService.kt  ← onRequest pattern (NOT onCall)
│   │       └── GroupChallengeFirestoreService.kt
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
│   ├── repository/
│   │   ├── ChallengeRepository.kt
│   │   ├── DailyLogRepository.kt
│   │   ├── GroupChallengeRepository.kt
│   │   ├── PaymentRepository.kt
│   │   ├── SyncRepository.kt
│   │   └── UsageStatsRepository.kt
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
│       ├── activechallenge/
│       │   ├── ActiveChallengeScreen.kt
│       │   └── ActiveChallengeViewModel.kt
│       ├── appselection/
│       │   ├── AppSelectionScreen.kt
│       │   └── AppSelectionViewModel.kt
│       ├── auth/
│       │   ├── AuthScreen.kt
│       │   └── AuthViewModel.kt
│       ├── challengecreation/
│       │   ├── ChallengeCreationScreen.kt   ← 7-step wizard
│       │   └── ChallengeCreationViewModel.kt
│       ├── dashboard/
│       │   ├── DashboardScreen.kt
│       │   └── DashboardViewModel.kt
│       ├── friends/
│       │   ├── FriendsHubScreen.kt
│       │   └── FriendsHubViewModel.kt
│       ├── groupchallenge/
│       │   ├── create/
│       │   │   ├── GroupChallengeCreateScreen.kt
│       │   │   └── GroupChallengeCreateViewModel.kt
│       │   ├── detail/
│       │   │   ├── GroupChallengeDetailScreen.kt
│       │   │   └── GroupChallengeDetailViewModel.kt
│       │   └── join/
│       │       ├── GroupChallengeJoinScreen.kt
│       │       └── GroupChallengeJoinViewModel.kt
│       ├── history/
│       │   ├── HistoryScreen.kt
│       │   └── HistoryViewModel.kt
│       ├── onboarding/
│       │   ├── OnboardingScreen.kt
│       │   └── OnboardingViewModel.kt
│       ├── profile/
│       │   ├── ProfileScreen.kt
│       │   └── ProfileViewModel.kt
│       ├── settings/
│       │   ├── SettingsScreen.kt
│       │   └── SettingsViewModel.kt
│       └── statistics/
│           ├── StatisticsScreen.kt
│           └── StatisticsViewModel.kt
├── service/
│   ├── AppDetectionAccessibilityService.kt  ← CORE: detects app opens
│   ├── BootReceiver.kt
│   ├── DailyEvaluationWorker.kt
│   ├── DailyReminderWorker.kt
│   ├── DetoxFirebaseMessagingService.kt
│   ├── NotificationHelper.kt
│   ├── OverlayManager.kt                   ← CORE: shows overlays
│   ├── PermissionCheckWorker.kt
│   ├── TrackedAppEventBus.kt
│   └── UsageTrackingService.kt
├── ui/theme/
│   ├── Colors.kt
│   ├── DetoxTheme.kt
│   ├── Shape.kt
│   └── Type.kt                             ← Poppins font
├── DetoxApplication.kt
└── MainActivity.kt

functions/src/
└── index.ts                                ← All Cloud Functions
    ├── createPaymentIntent
    ├── capturePayment
    ├── cancelOrRefundPayment
    ├── createGroupChallenge
    ├── joinGroupChallenge
    ├── startGroupChallenge
    ├── failParticipant
    ├── completeGroupChallenge
    ├── createConnectedAccount
    └── confirmGroupJoin

assets/
├── adult_domains.txt                       ← 100+ adult domains list
└── fonts/
    ├── poppins_regular.ttf
    ├── poppins_medium.ttf
    ├── poppins_semibold.ttf
    ├── poppins_bold.ttf
    └── poppins_extrabold.ttf

admin/
└── index.html                              ← Admin dashboard for payouts
```

---

## 4. Current State & Features

### ✅ COMPLETED & WORKING

#### Challenge System
- 7-step wizard for challenge creation (Soft/Hard Mode)
- 6-step wizard for Group Challenge creation
- Limit Types: TIME_LIMIT, SESSION_LIMIT, DAILY_BUDGET, TIME_WINDOW_ONLY
- Usage Schedule (time range + day selection via Bottom Sheet)
- Soft Mode: optional end date, streak tracking, marked FAILED when limit exceeded
- Hard Mode: minimum 14 days, Stripe payment flow
- One app per active challenge enforcement (Solo + Group combined)
- Challenge history in Profile tab

#### Blocking System
- AppDetectionAccessibilityService detects app opens
- Overlay appears directly over blocked app
- FLAG_SECURE on all overlays (black screen in recents)
- allowedPackages temporary whitelist (5s) after "Open anyway"
- blockedPackagesCache HashSet for O(1) lookup
- Pre-cached overlay layouts for instant display
- Dual event detection (TYPE_WINDOW_STATE_CHANGED + TYPE_WINDOW_CONTENT_CHANGED)

#### Overlay System (all fullscreen, opaque)
- **SessionIntentionOverlay:** Stage 1 — conscious open confirmation
  - Shows streak, motivation text, opens count
  - "Stark bleiben 💪" (large green) + "öffnen" (tiny grey, barely visible)
  - 5-second countdown after "öffnen" tap
  - Back button = dismiss + home screen (does NOT count as open)
- **SessionLimitReachedOverlay:** Stage 2 — limit reached
  - "Ja, ich akzeptiere — Challenge verlieren" (small font)
  - Confirmation dialog before marking as failed
- **LimitExceededOverlay:** Time limit used up
- **WebsiteBlockedOverlay:** Website blocking (one button only: "Zurück")
- **BudgetSelectionOverlay:** Daily budget session selection
- **TauntOverlay:** Group challenge taunt (top of screen, 4s auto-dismiss)

#### Conscious Opens Tracking
- ONLY incremented when user taps "Ja, öffnen" in overlay
- Written to Room DailyLog AND synced to Firestore
- Synced back from Firestore on app start (survives reinstalls)
- Used for progress bar on Dashboard and overlays

#### Group Challenges
- Create with 6-step wizard
- Share code with friends
- Join via code + Stripe payment
- Manual start by creator (no mandatory start date)
- Start date optional
- Minimum 2 participants to start
- Auto-cancel + refund if < 2 participants at start
- Leaderboard with real-time Firestore updates
- opensToday tracked via arrayRemove + arrayUnion (prevents partial updates)
- Taunt feature: "👀 Nerv ihn!" button in leaderboard
- Taunt overlay appears in real-time while friend uses blocked app
- Failed participants: money captured, removed from active tracking
- Completed challenges hidden from Friends tab after 3 days
- Winner payout: manual SEPA transfer by founder after IBAN submission

#### Payment System
- Stripe Test Mode fully functional
- Hard Mode: authorize on start, capture on fail, refund on success
- Group Challenge: separate PaymentIntent per participant
- Winner submits IBAN in Profile → stored in Firestore payoutRequests
- Admin dashboard (admin/index.html) shows pending payouts
- Payout processed manually by founder via bank transfer

#### Permission Monitoring (24h System)
- PermissionCheckWorker runs every 15 minutes
- UsageTrackingService checks every 60 seconds
- If overlay permission lost + active challenge:
  - Hour 0: notification "⚠️ Deine Challenge ist in Gefahr!"
  - Hour 2: escalated notification
  - Hour 6: urgent notification
  - Hour 12: "🔴 Letzte Warnung!"
  - Hour 18: fullscreen block on app open (cannot dismiss)
  - Hour 24: Hard Mode → Stripe capture → Challenge FAILED
- Timer accelerates if user opens app and ignores warning (only in first 12h)
- Red pulsing banner on all screens when permission missing
- On Huawei: also checks AccessibilityService status

#### Adult Content Blocking
- AccessibilityService monitors URL bar in all major browsers
- Blocked domains loaded from assets/adult_domains.txt (100+ domains)
- Immediate redirect to home screen (no overlay, no bypass)
- Brief Toast: "🔞 Blocked by Detox"
- Incognito mode detection → blocked if adult content challenge active
- Subdomain matching (www.pornhub.com matches pornhub.com)

#### Website Blocking
- Manual domain entry in challenge creation
- Auto-suggest domains when selecting apps (instagram.com, tiktok.com etc.)
- WebsiteBlockedOverlay shown (one button only, no bypass)
- Partial blocking: specific URL paths (instagram.com/reels, youtube.com/shorts)

#### Account & Auth
- Email/Password login and registration
- Google Sign-In (on devices with Google Play Services)
- Logout clears Room database (prevents account switching exploit)
- Device binding: Hard Mode challenge prevents logout
- Settings: change password, delete account

#### Dashboard
- Active Solo + Group challenges as cards
- Solo card: 👤 icon, progress bar, remaining days
- Group card: 👥 icon, participant count, rank, pot amount
- Progress bar filled based on conscious opens (SESSION) or time (TIME)
- Smart endDate display: timestamp detection vs days calculation
- "No end date" handling for open-ended Soft Mode challenges

#### Profile & History
- Avatar with initials, stats row (streak, completed, blocked)
- Last 3 challenges preview → "Alle anzeigen" → HistoryScreen
- HistoryScreen: filter by Solo/Group/Won/Lost
- IBAN input for Group Challenge payouts
- Dark Mode toggle (saved in SharedPreferences)
- Debug button: "Run Daily Evaluation Now" (DEBUG builds only)

#### Settings
- Account: email display, change password, logout, delete account
- Appearance: Dark Mode toggle
- Notifications: Daily Reminder toggle + time picker
- Permissions: Accessibility ✅/❌, Overlay ✅/❌, Usage Stats ✅/❌
- Privacy: Privacy Policy, Terms, Export data, Delete data
- App Info: version, feedback, rate app

#### Design System (DetoxTheme.kt)
- Light Mode default: Primary #00C853, Background #FFFFFF
- Dark Mode optional: Primary #00E676, Background #0F0F0F
- Font: Poppins (all weights in assets/fonts/)
- Material 3 throughout
- Rounded corners: 8/16/24/32dp

### 🔴 KNOWN ISSUES / IN PROGRESS

1. **Overlay timing on Huawei:** App briefly visible before overlay appears.
   performGlobalAction(HOME) was tried and removed (caused infinite loop).
   Potential fix: UsageStatsManager polling at 500ms intervals as primary method.

2. **Group Challenge blocking:** AccessibilityService doesn't always block
   for group challenge participants — sync from Firestore to local Room unreliable.

3. **completeGroupChallenge not triggering automatically:**
   Needs check on app foreground + in workers when endDate passes.

4. **Stripe Connect for automatic payouts:** Not implemented.
   Current: manual IBAN → founder transfers manually via bank.
   Future: Stripe Express (blocked by Austria individual account issue) or SEPA API.

5. **opensToday in overlay shows wrong value:**
   Overlay reads from DailyLog but Group Challenge uses Firestore participants array.

---

## 5. Coding Guidelines & Rules

### Critical Business Rules (NEVER violate)

```
1. consciousOpens ONLY increments when user taps "Ja, öffnen" in overlay
   NEVER use UsageStatsManager for open counting

2. Hard Mode minimum 14 days, Soft Mode end date optional

3. One app per active challenge (Solo + Group combined)
   Check ALL active challenges before creating new one

4. Adult content = always 100% blocked, no overlay bypass
   Silent redirect to home screen + Toast only

5. Group Challenge: minimum €10 buy-in, minimum 2 participants, minimum 3 days

6. Session timer runs in Foreground Service (NOT ViewModel)
   Persisted as sessionEndTime timestamp in SharedPreferences
   Timer pauses when screen off, resumes when screen on

7. On logout: clear ALL Room tables BEFORE Firebase signOut()
   Prevents account switching exploit

8. Hard Mode fail: Stripe capture FIRST, then mark FAILED in Room + Firestore
   Hard Mode success: Stripe refund FIRST, then mark COMPLETED

9. Group Challenge opensToday: use arrayRemove + arrayUnion pattern
   NEVER use dot notation (.update("participants.$index.field")) — causes partial snapshots
```

### Firestore Data Patterns

```kotlin
// CORRECT: arrayRemove + arrayUnion for participant updates
db.collection("groupChallenges").doc(groupId)
    .update(
        "participants", FieldValue.arrayRemove(oldParticipant.toMap()),
        "participants", FieldValue.arrayUnion(newParticipant.toMap())
    )

// endDate smart detection (old records = duration ms, new = timestamp)
val endDateMs = if (endDate > 1700000000000L) endDate
                else startDate + endDate

// createdAt parsing (Firestore Timestamp object vs Long)
val createdAt = when (val raw = doc.get("createdAt")) {
    is com.google.firebase.Timestamp -> raw.toDate().time
    is Long -> raw
    else -> System.currentTimeMillis()
}

// participants parsing (handles partial update snapshots)
val participants = when (val raw = doc.get("participants")) {
    is List<*> -> (raw as List<Map<String, Any>>).map { it.toParticipant() }
    is Map<*, *> -> raw.values.mapNotNull { (it as? Map<String, Any>)?.toParticipant() }
    else -> emptyList()
}
```

### Cloud Functions Pattern

```typescript
// ALL functions use onRequest (NOT onCall)
// Required because Huawei has no Google Play Services → onCall fails
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

### Android Cloud Function Call Pattern

```kotlin
// Always get fresh token before Cloud Function call
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

### Room Database Rules

```kotlin
// Check DetoxDatabase.kt for current version
// ALWAYS add migration for ANY schema change — never skip
// Nullable column addition: ALTER TABLE ... ADD COLUMN x TYPE DEFAULT NULL
// Non-nullable or complex changes: CREATE new + INSERT + DROP + RENAME

// DailyLog date stored as Long (start of day in ms):
val today = System.currentTimeMillis() / 86400000 * 86400000

// consciousOpens sync: write to Room AND Firestore on every increment
// Read from Firestore on app start to restore after reinstall
```

### Overlay Rules

```kotlin
// ALL overlays MUST have FLAG_SECURE (black in recents)
val params = WindowManager.LayoutParams(
    MATCH_PARENT, MATCH_PARENT,
    TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_SECURE,
    PixelFormat.TRANSLUCENT
)

// NEVER use coroutines/async for showOverlay() — causes timing delay
// CORRECT:
Handler(Looper.getMainLooper()).post { overlayManager.showOverlay(...) }

// Back button = "Nein" (home screen, NO counter increment)
// Home button while overlay visible = dismiss overlay

// allowedPackages whitelist: add package for 5s after "Open anyway"
fun allowPackageTemporarily(packageName: String) {
    allowedPackages.add(packageName)
    Handler(Looper.getMainLooper()).postDelayed({
        allowedPackages.remove(packageName)
    }, 5000)
}
```

### AccessibilityService Optimization

```kotlin
// blockedPackagesCache: HashSet<String>
// Update when challenges change, check BEFORE any coroutine or DB query
if (packageName !in blockedPackagesCache) return
if (packageName in allowedPackages) return  // temporarily allowed

// Pre-cache all overlay layouts in onCreate():
private lateinit var sessionIntentionView: View
private lateinit var limitExceededView: View

override fun onCreate() {
    sessionIntentionView = LayoutInflater.from(this)
        .inflate(R.layout.session_intention, null)
    // Only update dynamic content before showing, never re-inflate
}
```

### Naming Conventions

```
ViewModels:   [Screen]ViewModel.kt
Screens:      [Screen]Screen.kt
Entities:     [Name]Entity.kt
UseCases:     [Action][Target]UseCase.kt
Services:     [Name]Service.kt
Workers:      [Name]Worker.kt
Repositories: [Name]RepositoryImpl.kt (impl) / [Name]Repository.kt (interface)
```

### Logging

```kotlin
// Always Timber, never Log directly
Timber.d("Challenge $challengeId: opens=$opens limit=$limit exceeded=$exceeded")
Timber.e("Stripe capture failed: ${e.message}")
Timber.w("Group challenge not found: $groupId")

// Include timestamps for performance tracking:
Timber.d("[${System.currentTimeMillis()}] Overlay shown for $packageName")
```

### Huawei Compatibility Rules

```
- FCM does NOT work → all notifications via WorkManager/AlarmManager ONLY
- Google Sign-In does NOT work → Email/Password is primary auth method
- Battery Optimization kills services → guide user to whitelist in onboarding
- AccessibilityService can be killed → PermissionCheckWorker as backup (15min)
- Overlay permissions can be revoked → 24h monitoring system with escalation
- Always test on real Huawei device AND standard Android emulator
- Huawei launcher packages: "com.huawei.android.launcher", "com.huawei.systemmanager"
```

### Firestore Collections Structure

```
users/{userId}/
    payoutIban: String
    payoutName: String
    stripeConnectedAccountId: String (future)
    pendingPayouts: [{amount, groupId, createdAt}]

users/{userId}/dailyLogs/{challengeId}_{date}/
    consciousOpens: Int
    timeUsedMinutes: Int
    updatedAt: Long

groupChallenges/{groupId}/
    code: String (6 chars)
    creatorUserId: String
    creatorDisplayName: String
    appPackageNames: String (comma-separated)
    blockedDomains: String (comma-separated, nullable)
    limitType: String ("sessions"|"time"|"budget")
    limitValueMinutes: Int
    limitValueSessions: Int
    sessionDurationMinutes: Int
    durationDays: Int
    buyInCents: Int
    maxParticipants: Int (fixed at 20)
    startDate: Long (Unix ms, 0 if not set)
    endDate: Long (Unix ms)
    completedAt: Long (Unix ms, 0 if not completed)
    bonusEnabled: Boolean
    status: String ("waiting"|"active"|"completed"|"cancelled")
    participants: Array of {
        userId, displayName, paymentIntentId,
        amountCents, status, opensToday,
        timeUsedMinutes, joinedAt
    }
    participantUserIds: Array<String>

payoutRequests/{requestId}/
    userId: String
    displayName: String
    iban: String
    payoutName: String
    amountCents: Int
    groupId: String
    status: String ("pending"|"paid"|"rejected")
    createdAt: Long
    paidAt: Long (nullable)
```

### GitHub & Secrets

```
Repository: https://github.com/saninbrca/ScreenStake (private)
Branch: main

.gitignore must include:
  google-services.json
  functions/.env
  *.keystore
  *.jks
  firebase-debug.log
  firebase-debug.*.log
  admin/index.html  (contains Firebase credentials)

Firebase Project: detox-33208
Stripe: Test mode keys in functions/.env only
Node.js version for Cloud Functions: 22 (package.json engines.node)
```

### Claude Code Settings

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

### Development Workflow

```
1. Write prompt in planning chat → copy to Claude Code
2. Use Plan Mode (claude --plan) for complex multi-file changes
3. Test on real Huawei device after every change
4. Check Logcat for errors: package:com.detox.app level:error
5. git add . && git commit -m "feat/fix: description" after each working feature
6. /compact in Claude Code after long sessions
7. /clear in Claude Code when switching topics
8. firebase deploy --only functions after any Cloud Function changes
9. Deploy single function for speed: firebase deploy --only functions:functionName
```

### Deployment Checklist (before going live)

```
□ Switch Stripe from test to live keys
□ Update functions/.env with live STRIPE_SECRET_KEY
□ Generate new google-services.json for production Firebase project
□ Set up real Firestore security rules (no test overrides)
□ Add Privacy Policy URL to SettingsScreen
□ Add Terms of Service URL to SettingsScreen
□ Add Impressum (Austrian law requirement)
□ Consult lawyer re: gambling law in Austria (pot distribution)
□ Consult tax advisor re: income from captured challenges
□ Register Gewerbe if revenue exceeds €11,000/year
□ Complete Stripe platform account verification
□ Enable Stripe Live mode and test full payment flow
□ Submit app to Google Play with AccessibilityService justification
□ Prepare Play Store listing with screenshots
□ Set up Firebase App Distribution for beta testing
```
