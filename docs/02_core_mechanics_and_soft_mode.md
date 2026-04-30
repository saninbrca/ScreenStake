# 02 — Core Mechanics & Soft Mode
> **Scope:** Conscious Opens (the anti-cheat core), Soft Mode rules, all Overlay logic, Daily Evaluation, Streak tracking, Dashboard display.
> **When to load:** Any work on overlays, AccessibilityService, DailyLog, DailyEvaluationWorker, Dashboard, or Soft Mode challenge creation.

---

## The Core Concept: Conscious Opens

**The SINGLE most important rule in the entire app:**

```
consciousOpens ONLY increments when the user explicitly taps
"Ja, öffnen" inside the SessionIntentionOverlay.

NEVER use UsageStatsManager to count opens.
This is the core differentiator from every other screen-time app.
```

This means:
- Back button on overlay → does NOT count as an open
- Home button while overlay is visible → does NOT count as an open
- App detected by AccessibilityService → does NOT count as an open
- Only the explicit user confirmation tap counts.

---

## Soft Mode Rules

| Rule | Value |
|------|-------|
| Minimum duration | No minimum — end date is optional |
| Money involved | ❌ Never |
| Motivation | Streak-based |
| Fail condition | Limit exceeded (opens or time) |
| End date | Optional — can be open-ended forever |
| Apps per challenge | 1 app per active challenge (Solo + Group combined) |

**Check before creating a new challenge:** Query ALL active challenges (Solo + Group) and verify the selected app is not already being tracked. This check must happen in `CreateChallengeUseCase`.

---

## Limit Types

| LimitType | How it works |
|-----------|-------------|
| `SESSION_LIMIT` | Max number of conscious opens per day |
| `TIME_LIMIT` | Max minutes of usage per day |
| `DAILY_BUDGET` | User selects session duration each time they open |
| `TIME_WINDOW_ONLY` | App blocked outside specific time windows (no opens counted) |

**Usage Schedule:** Time range + day-of-week selection. Configured via Bottom Sheet in challenge creation wizard (Step 5 of 7).

---

## Overlay System

### Rules (apply to ALL overlays)

```kotlin
// 1. ALL overlays MUST have FLAG_SECURE → black screen in recents
val params = WindowManager.LayoutParams(
    MATCH_PARENT, MATCH_PARENT,
    TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_SECURE,
    PixelFormat.TRANSLUCENT
)

// 2. NEVER use coroutines/async for showOverlay() → causes timing delay
// CORRECT:
Handler(Looper.getMainLooper()).post { overlayManager.showOverlay(...) }

// 3. Pre-cache overlay layouts in AppDetectionAccessibilityService.onCreate()
//    Only update dynamic content before showing — NEVER re-inflate
private lateinit var sessionIntentionView: View
private lateinit var limitExceededView: View
override fun onCreate() {
    sessionIntentionView = LayoutInflater.from(this)
        .inflate(R.layout.session_intention, null)
    // update text/data just before showOverlay(), never reinflate
}
```

### Overlay Inventory

#### 1. `SessionIntentionOverlay` — Stage 1 (Conscious Open Gate)
- Shown when user tries to open a blocked app
- Displays: current streak, motivation text, opens count today
- Buttons:
  - **"Stark bleiben 💪"** — large, green, prominent → dismiss overlay → go home
  - **"öffnen"** — tiny, grey, barely visible → starts 5-second countdown
- 5-second countdown after tapping "öffnen" (user can cancel)
- After countdown: `consciousOpens++` → write to Room + Firestore → allow app temporarily (5s whitelist)
- **Back button** = same as "Stark bleiben" → dismiss + home, NO counter increment
- **Home button while overlay visible** = dismiss overlay only

#### 2. `SessionLimitReachedOverlay` — Stage 2 (Limit Hit)
- Shown when opens or time limit is already exceeded
- Contains: "Ja, ich akzeptiere — Challenge verlieren" (small font, intentionally hard to tap)
- Must show a confirmation dialog before marking challenge as FAILED
- FAILED state: written to Room + Firestore

#### 3. `LimitExceededOverlay`
- Shown when daily time limit is used up
- No bypass available

#### 4. `WebsiteBlockedOverlay`
- Shown when a blocked website is detected in a browser
- One button only: **"Zurück"** — no bypass option

#### 5. `BudgetSelectionOverlay`
- Shown for `DAILY_BUDGET` limit type
- User selects session duration before entering blocked app

#### 6. `TauntOverlay`
- Shown at the top of screen when a Group Challenge participant taunts you
- Auto-dismisses after 4 seconds
- Triggered in real-time via Firestore listener while friend uses blocked app

#### 7. `HardModeLockoutOverlay`
- See `03_hard_mode_and_stripe.md` for details

---

## AppDetectionAccessibilityService — Performance Rules

```kotlin
// blockedPackagesCache: HashSet<String>
// Update cache whenever challenge list changes.
// Check FIRST before any coroutine or DB query:
if (packageName !in blockedPackagesCache) return
if (packageName in allowedPackages) return  // temporarily allowed → 5s whitelist

// Dual event detection:
// TYPE_WINDOW_STATE_CHANGED + TYPE_WINDOW_CONTENT_CHANGED
// Both needed for reliable detection across all Android versions.

// Temporary whitelist after user confirms open:
fun allowPackageTemporarily(packageName: String) {
    allowedPackages.add(packageName)
    Handler(Looper.getMainLooper()).postDelayed({
        allowedPackages.remove(packageName)
    }, 5000)
}
```

---

## Conscious Opens Tracking — Full Data Flow

```
User taps "öffnen" → 5s countdown → user does NOT cancel
    ↓
consciousOpens++ in memory
    ↓
Write to Room: DailyLog (challengeId + date key)
    ↓
Write to Firestore: users/{userId}/dailyLogs/{challengeId}_{date}
    {consciousOpens: N, timeUsedMinutes: M, updatedAt: timestamp}
    ↓
allowPackageTemporarily(packageName) → 5s whitelist
    ↓
App opens normally

On app start:
    Read from Firestore → restore consciousOpens (survives reinstall)
```

**Room DailyLog key format:**
```kotlin
val date = System.currentTimeMillis() / 86400000 * 86400000  // start of day in ms
val key = "${challengeId}_${date}"
```

---

## Dashboard Display Logic

- **Solo challenge card:** 👤 icon, progress bar, remaining days
- **Group challenge card:** 👥 icon, participant count, user's rank, pot amount
- **Progress bar:**
  - `SESSION_LIMIT` → filled based on `consciousOpens / sessionLimit`
  - `TIME_LIMIT` → filled based on `timeUsedMinutes / timeLimitMinutes`
- **endDate display:** Smart detection needed:
  ```kotlin
  // Old records stored duration (ms), new records store absolute timestamp
  val endDateMs = if (endDate > 1700000000000L) endDate
                  else startDate + endDate
  ```
- **"No end date"** handling: Soft Mode challenges with no endDate → show "Kein Enddatum"

---

## Daily Evaluation Worker (`DailyEvaluationWorker`)

Runs once per day (typically midnight). Logic:

```
For each ACTIVE challenge:
    1. Read DailyLog for today
    2. Check if limit exceeded (opens or time)
    3. If exceeded:
        - Soft Mode → mark FAILED in Room + Firestore, increment failStreak
        - Hard Mode → trigger Stripe capture FIRST, then mark FAILED (see 03_hard_mode_and_stripe.md)
    4. If NOT exceeded:
        - Increment streak counter
        - If today == endDate → mark COMPLETED
    5. Reset dailyOpens + dailyTime for tomorrow

DEBUG only:
    ProfileScreen has "Run Daily Evaluation Now" button for testing.
```

---

## Streak Logic

- Streak increments each day the user stays within limit
- Broken when limit is exceeded → `failStreak` resets `streak` to 0
- Displayed on: Dashboard card, `SessionIntentionOverlay`, `ProfileScreen`
- Profile stats row: streak 🔥 | completed ✅ | blocked 🚫

---

## Account & Auth Rules

- **Primary auth:** Email/Password
- **Secondary:** Google Sign-In (only on devices with Google Play Services — NOT Huawei)
- **On logout:**
  ```kotlin
  // CRITICAL ORDER: clear Room FIRST, then Firebase signOut
  // Prevents account-switching exploit where previous user's data shows briefly
  clearAllRoomTables()
  FirebaseAuth.getInstance().signOut()
  ```
- **Hard Mode active = device binding:** Logout is BLOCKED while a Hard Mode challenge is active
- **Delete account:** Clears Room + Firestore user data + Firebase Auth account

---

## Website Blocking (non-adult)

- Manual domain entry during challenge creation wizard
- Auto-suggest: when user selects Instagram app → suggests `instagram.com`, `instagram.com/reels`
- Same for TikTok → `tiktok.com`, YouTube → `youtube.com/shorts`
- `WebsiteBlockedOverlay` shown — single "Zurück" button, no bypass
- Partial path blocking supported: `youtube.com/shorts` blocks only Shorts, not all of YouTube
- Implementation: `AppDetectionAccessibilityService` monitors URL bar content in all major browsers

---

## Known Issues (Soft Mode / Core)

1. **`opensToday` in overlay shows wrong value for Group Challenges:**
   Overlay reads from `DailyLog` (Room) but Group Challenge tracking uses `participants` array in Firestore. These are not always in sync.

2. **Group Challenge blocking unreliable:**
   `AppDetectionAccessibilityService` doesn't always block for Group Challenge participants because sync from Firestore to local Room can be delayed or missed.
