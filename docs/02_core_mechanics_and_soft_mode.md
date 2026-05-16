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

## Limit Type Flows

### SESSION_LIMIT
```
User opens blocked app
↓
OverlayManager reads consciousOpens from Room DailyLog (DateUtils.todayKey())
↓
consciousOpens < limit → SessionIntentionOverlay (Stage 1)
  "Stark bleiben 💪" → dismiss + home (no count)
  "öffnen" → 5s countdown → consciousOpens++
    → write Room immediately (atomic)
    → write Firestore immediately (fire-and-forget, SetOptions.merge())
    → allow app 5s whitelist
↓
consciousOpens >= limit → SessionLimitReachedOverlay (Stage 2)
  "Stark bleiben 💪" only → dismiss + home
  No bypass, no quit option
↓
Quit only via: Dashboard → Detail → "Aufgeben"
```

### TIME_LIMIT
```
User opens blocked app
↓
OverlayManager reads timeUsedMs from Room DailyLog (DateUtils.todayKey())
↓
timeUsedMs < limitMs → SessionIntentionOverlay (Stage 1)
  Same flow as SESSION_LIMIT
  After allow: UsageTrackingService starts tracking time
    → writes timeUsedMs to Room every 10s
    → writes timeUsedMs to Firestore every 10s (fire-and-forget)
↓
timeUsedMs >= limitMs → SessionLimitReachedOverlay (Stage 2)
  "Stark bleiben 💪" only → dismiss + home
```

### DAILY_BUDGET
```
User opens blocked app
↓
Check SharedPreferences: active session running?
  YES + not expired → allow app directly (no overlay)
  NO or expired →
↓
Read budgetRemainingMs from Room DailyLog (DateUtils.todayKey())
  budgetRemainingMs <= 0 → SessionLimitReachedOverlay
  budgetRemainingMs > 0  → BudgetSelectionOverlay
    User selects session duration (max = remainingMs / 60000 min)
    → write sessionEndTime to SharedPreferences
    → write sessionStartTime to SharedPreferences
    → allow app
↓
UsageTrackingService monitors sessionEndTime every 10s:
  elapsed → write budgetUsedMs to Room (DateUtils.todayKey())
  elapsed → write budgetUsedMs to Firestore (fire-and-forget, SetOptions.merge())
  sessionEndTime reached →
    budgetRemainingMs > 0  → BudgetSelectionOverlay over current screen
    budgetRemainingMs <= 0 → SessionLimitReachedOverlay over current screen

App start restore:
  Read budgetUsedMs from Firestore → write to Room → set SharedPreferences committedMs
```

### TIME_WINDOW_ONLY
```
App blocked outside configured time window + days
No opens counted, no overlay interaction needed
AccessibilityService checks current time against schedule
  Inside window  → allow
  Outside window → SessionLimitReachedOverlay ("Stark bleiben 💪" only)
```

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

## Overlay Design System (v2 — current)

Background: #0A0A0A (full screen)
Text primary: #FFFFFF
Text secondary: #666666
Accent: #00C853
Font: Poppins

### Context Header (top of every overlay)
One line, 13sp, weight 600, color #00C853:
```
SESSION_LIMIT Soft:  "🔥 X Tage Streak"
SESSION_LIMIT Hard:  "💰 €X auf dem Spiel"
SESSION_LIMIT Group: "👥 Platz #X von Y"
TIME_LIMIT Soft:     "🔥 X Tage Streak"
TIME_LIMIT Hard:     "💰 €X auf dem Spiel"
DAILY_BUDGET:        "⏱ X min übrig heute"
TIME_WINDOW_ONLY:    "📅 Verfügbar ab HH:MM"
```
Always read live from challenge object + DailyLog. Never hardcoded.

### Main Display
Large number: 64sp, bold, #FFF, letter-spacing -3
- SESSION_LIMIT: consciousOpens (used today)
- TIME_LIMIT: timeUsedMinutes
- DAILY_BUDGET: budgetRemainingMs / 60000 (remaining)

Label below: 13sp, #444 — clear text e.g. "von 5 Öffnungen heute verbraucht"

### Progress Bar
Keep existing component unchanged.
Add labels below: left = context text (11sp, #333), right = percentage.

### "trotzdem öffnen" button
SessionIntentionOverlay ONLY.
10sp, color #222, transparent bg, no border, height 32dp.
Intentionally barely visible — psychological design.
All other overlays: NO ghost button.

### Budget Chips (BudgetSelectionOverlay)
Unselected: #141414 bg, #444 text, #1E1E1E border
Selected: #00C853 bg, #000 text, bold
Label: "Minuten wählen" (11sp, #333)

### Status bar
Dark on all overlays: `isAppearanceLightStatusBars = false`

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

### Session Limit Sync Pattern (Source of Truth)
- consciousOpens written to Room IMMEDIATELY on tap (atomic, never delayed)
- consciousOpens written to Firestore IMMEDIATELY on tap (fire-and-forget)
- On app start: read consciousOpens from Firestore → restore to Room
- OverlayManager ALWAYS reads fresh from Room before showing overlay
- Firestore path: `users/{userId}/dailyLogs/{challengeId}_{DateUtils.todayKey()}`
  - Fields: `consciousOpens`, `updatedAt`
  - Always `SetOptions.merge()`

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

## Partial App Blocking (Reels / Shorts / Feed)

Blocks specific content sections within apps while allowing normal app usage.
Distinct from URL-based `partialBlockDomains` — uses AccessibilityService to detect activity class names, view IDs, and content descriptions. Configured in challenge creation wizard (Apps tab) as sub-options under each supported app.

### Supported Sections
| Section | Package | Blocked Content |
|---------|---------|-----------------|
| Instagram Reels | `com.instagram.android` | Reels tab + player |
| YouTube Shorts | `com.google.android.youtube` | Shorts tab + player |
| TikTok For You | `com.zhiliaoapp.musically` | For You feed |
| Facebook Reels | `com.facebook.katana` | Reels player |
| Twitter For You | `com.twitter.android` | For You timeline |
| Snapchat Spotlight | `com.snapchat.android` | Spotlight feed |

### Detection Order (fastest to slowest)
1. **Activity class name** via `TYPE_WINDOW_STATE_CHANGED`
   → Fastest — fires BEFORE content renders → no visible flash
2. **ViewID** via `findAccessibilityNodeInfosByViewId()`
   → Medium — fires when view tree changes
3. **Content description** matching
   → Fallback — slowest

### On Detection
```kotlin
// 1. Navigate back within app (NOT to home screen):
performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

// 2. Show brief Toast:
Handler(Looper.getMainLooper()).post {
    Toast.makeText(applicationContext, "🔒 $sectionName geblockt", Toast.LENGTH_SHORT).show()
}
```
1-second cooldown (`lastPartialBlockTimeMs`) prevents rapid-fire triggers.

NEVER use `GLOBAL_ACTION_HOME` for partial blocks — stays in app.
NEVER show `SessionIntentionOverlay` for partial blocks.
NEVER increment `consciousOpens` for partial blocks.

### Data Model
- `Challenge.partialBlockSections: List<PartialBlockSection>` — sections to block
- `Challenge.isPartialBlockOnly: Boolean` — true when no full-app block configured; package excluded from `trackedPackages` so full-block overlay never fires
- Room columns: `partial_block_sections` (TEXT, comma-separated IDs) and `partial_block_only` (INTEGER 0/1)
- Firestore fields: `partialBlockSections` (List<String> of IDs), `isPartialBlockOnly` (Boolean)

### AppSelectionScreen UI
Apps with partial block support show sub-options (indented ~40dp start padding):
```
📷 Instagram                        [ ]  ← full app block
   └ Instagram Reels  Nur Reels geblockt [ ]  ← partial block (13sp, indented)
▶️ YouTube                          [ ]
   └ YouTube Shorts   Nur Shorts geblockt [ ]
```
Sub-options always visible (not collapsed).
Can select partial block WITHOUT selecting full app block.
Can select BOTH (full block + partial — redundant but allowed).
`Next` button enabled when only section sub-options are selected (no full-app selection required).

### Conscious Opens Rule
Partial block detection → NO `consciousOpens` increment.
Only full app open confirmation (tapping "Ja, öffnen") counts as a conscious open.
Reels/Shorts attempt inside already-open app → silent block only (back navigation + toast).

### Cache Rule
```kotlin
// TrackedAppEventBus.activePartialBlockSections populated by UsageTrackingService:
val sections = challenges.flatMap { it.partialBlockSections }.distinct()
TrackedAppEventBus.updateActivePartialBlockSections(sections)

// AppDetectionAccessibilityService reads synchronously — zero DB queries:
val sections = TrackedAppEventBus.activePartialBlockSections.value
    .filter { it.appPackage == packageName }
if (sections.isEmpty()) return
```
MUST be updated when challenge list changes.
NEVER query Room in `TYPE_WINDOW_STATE_CHANGED` handler.

### Known Limitations
- App updates may change ViewIDs → sections may stop working until `PartialBlockSection` enum is updated with new ViewIDs
- Some Activity class names are obfuscated in newer app versions → content description matching as fallback
- On Huawei: `TYPE_WINDOW_STATE_CHANGED` may fire slightly delayed → brief content flash possible before redirect (Android limitation)

---

## Known Issues (Soft Mode / Core)

1. **`opensToday` in overlay shows wrong value for Group Challenges:**
   Overlay reads from `DailyLog` (Room) but Group Challenge tracking uses `participants` array in Firestore. These are not always in sync.

2. **Group Challenge blocking unreliable:**
   `AppDetectionAccessibilityService` doesn't always block for Group Challenge participants because sync from Firestore to local Room can be delayed or missed.

---

## Universal Challenge Pattern

All challenge types share identical overlay logic and DailyLog structure.
The ONLY differences are what is added on top:

| Layer | Soft Mode | Hard Mode | Group Challenge |
|-------|-----------|-----------|-----------------|
| Blocking logic | ✅ base | ✅ same | ✅ same |
| Overlays | ✅ base | ✅ same | ✅ same |
| DailyLog sync | ✅ base | ✅ same | ✅ same + participants |
| Stripe | ❌ | ✅ pre-auth/capture/refund | ✅ per participant |
| Firestore participants | ❌ | ❌ | ✅ arrayRemove+arrayUnion |

### DailyLog Firestore Structure (all types)
```
users/{userId}/dailyLogs/{challengeId}_{DateUtils.todayKey()}
    consciousOpens: Int      (SESSION_LIMIT)
    timeUsedMs: Long         (TIME_LIMIT)
    budgetUsedMs: Long       (DAILY_BUDGET)
    budgetRemainingMs: Long  (DAILY_BUDGET)
    updatedAt: Long
    (SetOptions.merge() always — fields coexist safely)
```

### Fortschrittsbalken (Progress Bar) — same logic everywhere
Used in: Dashboard card, Detail screen, Overlay
Source of truth: Room DailyLog (read fresh via `DateUtils.todayKey()`)

```
SESSION_LIMIT:  progress = consciousOpens / limitValueSessions
TIME_LIMIT:     progress = timeUsedMs / (limitValueMinutes * 60000)
DAILY_BUDGET:   progress = budgetUsedMs / (dailyBudgetMinutes * 60000)
```

Display (remaining):
```
SESSION_LIMIT:  "${limitValueSessions - consciousOpens} opens remaining"
TIME_LIMIT:     "${(limitMs - timeUsedMs) / 60000} min remaining"
DAILY_BUDGET:   "${budgetRemainingMs / 60000} min remaining"
```

**CRITICAL:** Detail screen must use IDENTICAL data source as Dashboard.
Both read from Room DailyLog via `DateUtils.todayKey()`.
Never use ViewModel state or passed-in arguments for progress display.

---

## Horizontal Scroll Number Picker (DetoxHorizontalPicker)

Used in ALL limit value inputs throughout the app.
Single reusable composable in `presentation/components/DetoxHorizontalPicker.kt`

### Applied to
| Context | Range | Default | Unit |
|---------|-------|---------|------|
| SESSION_LIMIT wizard step | 1–50 | 5 | Öffnungen |
| TIME_LIMIT wizard step | 1–480 | 60 | Minuten |
| DAILY_BUDGET wizard step | 1–480 | 10 | Minuten |
| Duration step | 1–365 | varies by mode | Tage |
| Group buy-in step | 10–500 | 10 | Euro |
| BudgetSelectionOverlay | 1–remainingMinutes | min(5, remaining) | — |

### Behavior
- `LazyRow` with `snapFlingBehavior`
- Hard stop at min and max (no wrapping)
- Haptic feedback on each item change
- Auto-scroll to selected item on composition
- Step: always 1

### Visual
| Position | Light (wizard) | Dark (overlay) | Size |
|----------|---------------|----------------|------|
| Selected | #000 | #FFF | 28sp bold |
| ±1 items | #AAA | #444 | 20sp |
| ±2 items | #CCC | #333 | 16sp |
| ±3+ items | #E0E0E0 | #222 | 14sp |

Fade gradient left/right edges (40dp). Green underline/dot on selected item.

### Minimum values (enforced)
```
SESSION_LIMIT:  min 1
TIME_LIMIT:     min 1
DAILY_BUDGET:   min 1, default 10
Duration Soft:  min 1
Duration Hard:  min 14 (1 in DEBUG)
Duration Group: min 3
Buy-in:         min 10 (€)
```

---

## Detail Screen Design (Soft Mode)

iOS-style, white background #F2F2F7, white cards.

### Card 1 — Header
- "SOFT MODE" badge (green pill) + end date right
- App name: 22sp bold
- 3 stats row: **Aktuelle Streak 🔥** | **Beste Streak** | **Tage noch** (green)

### Card 2 — Progress
- "Heute" + "X / Y Öffnungen" header
- Existing progress bar (unchanged)
- Footer: "X übrig" left + "Reset um Mitternacht" right

### Card 3 — Info list (rows with 0.5px dividers)
Rows: Limit | Session-Dauer | Gestartet | Endet | Erfolgsrate (green)

### Motivational quote
12sp, #C7C7CC, italic. Rotates daily.

### "Challenge aufgeben"
Text only, 14sp, #FF3B30, centered.
No button background — psychologically de-emphasized.
