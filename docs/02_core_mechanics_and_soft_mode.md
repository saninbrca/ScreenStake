# 02 — Core Mechanics & Soft Mode
> **Scope:** Conscious Opens (the anti-cheat core), Soft Mode rules, all Overlay logic, Daily Evaluation, Streak tracking, Dashboard display.
> **When to load:** Any work on overlays, AccessibilityService, DailyLog, DailyEvaluationWorker, Dashboard, or Soft Mode challenge creation.
> _Last verified: 2026-07-19 (commit 4b54701)_

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
| Minimum duration | 3 days |
| Money involved | ❌ Never |
| Motivation | Streak-based |
| Fail condition | Limit exceeded (opens or time) |
| End date | Optional — can be open-ended forever |
| Apps per challenge | A challenge may block MULTIPLE apps (`selectedApps: Set`); but each APP may be in only ONE active challenge (Solo + Group combined) |

**Check before creating a new challenge:** Query ALL active challenges (Solo + Group) and verify none of the selected apps is already being tracked (per-package "busy" state in the wizard; guard in `CreateChallengeUseCase`).

---

## Limit Types

| LimitType | How it works |
|-----------|-------------|
| `SESSION_LIMIT` | Max number of conscious opens per day |
| `TIME_LIMIT` | Max minutes of usage per day |
| `DAILY_BUDGET` | User selects session duration each time they open |
| `TIME_WINDOW_ONLY` | App blocked outside specific time windows (no opens counted) |

**Usage Schedule:** Time range + day-of-week selection. Configured via Bottom Sheet on the wizard's schedule step (internal step 5 — skipped entirely on the block-only path, see "Creation Wizard — paths & gates" below).

---

## Creation Wizard — paths & gates (2026-07 restructure)

`ChallengeCreationViewModel` / `ChallengeCreationScreen`. The internal step ids 1..7 are stable
content keys (the screen's `when(step)` switch and `canGoNext()` key on them); which ids are
VISIBLE depends on the path.

### `visibleSteps(state)` — path-dependent step list (pure function, unit-tested)
```kotlin
internal fun visibleSteps(state: ChallengeCreationState): List<Int> = when {
    state.activeTab == 1 -> listOf(1, 2, 6, 7)            // block-only (Website tab): 4 steps
    state.limitType == LimitType.TIME_WINDOW -> listOf(1, 2, 3, 5, 6, 7)  // 6 steps
    else -> listOf(1, 2, 3, 4, 5, 6, 7)                   // full app path: 7 steps
}
```
- **Apps tab:** all steps; TIME_WINDOW skips the step-4 value picker (window configured on step 5).
- **Block-only path (Website tab — custom domains and/or adult):** the challenge is a 24/7 hard
  block, so BOTH minute-limit steps (3+4) AND the schedule step (5) are skipped.
- `goNext`/`goBack` walk this list (nearest visible neighbour); the "Schritt X von Y" counter is
  the position in it.

### Step-2 gate — tab-aware "must block something" (`step2HasValidBlockingSource`)
```kotlin
when (state.activeTab) {
    0 -> state.selectedApps.isNotEmpty() &&
         state.selectedApps.none { conflictingPackages.containsKey(it) }   // Apps tab
    else -> state.manualDomains.isNotEmpty() || state.blockAdultContent    // Website tab
}
```
Deliberately **tab-aware, NOT a union of both tabs** (the pre-2026-07-16 union gate let a
"blocks nothing" website challenge through: leftover app selection satisfied the gate, but the
Website-tab submit discards `selectedApps`). Backstop: `CreateChallengeUseCase` fails a WEBSITE
challenge with no domains and adult off. Tab-switching does NOT clear selections.

### Adult-block exclusivity (step 2)
Adult-block and app selection are mutually exclusive; neither direction clears silently:
- Turning adult ON with apps selected → confirmation dialog (`showAdultExclusiveDialog`).
- Tapping an app while adult is ON → mirrored dialog (`pendingAdultAppPackage`).

### Pre-flight enforcement-permission gate (`createChallenge()`, Soft AND Hard)
Runs FIRST — before the root check, the Hard payment branch, and any persistence. Missing
permission ⇒ `MissingPermissions(needsUsage, needsAccessibility, needsOverlay)` dialog, nothing
created. Overlay is required when anything consumes it: app blocking, custom domains, **or
adult-block** (since 2026-07-18 — the about:blank redirect needs SYSTEM_ALERT_WINDOW). Full
mapping + routing: `docs/05` "Pre-flight enforcement-permission gate".

### Duplicate adult-block gate (`createChallenge()`)
The 133k adult list is enforced by ONE global flag, so a second adult-ONLY challenge would block
nothing new. Adult-only (adult on, no apps, no domains) + ANY active challenge with
`blockAdultContent` ⇒ abort with `challenge_error_duplicate_adult_block`. Only ACTIVE challenges
count; DB-read errors fail OPEN (never lock creation out).

---

## Limit Type Flows

### SESSION_LIMIT
```
User opens blocked app
↓
OverlayManager reads consciousOpens from Room DailyLog (DateUtils.todayKey())
↓
consciousOpens < limit → SessionIntentionOverlay (Stage 1)
  "Nicht öffnen" → dismiss + home (no count)
  "öffnen" → 5s countdown → consciousOpens++
    → write Room immediately (atomic)
    → write Firestore immediately (fire-and-forget, SetOptions.merge())
    → allow app 5s whitelist
↓
consciousOpens >= limit → SessionLimitReachedOverlay (Stage 2)
  "Nicht öffnen" only → dismiss + home
  No bypass, no quit option
↓
Quit only via: Dashboard → Detail → "Aufgeben"
```

### TIME_LIMIT
```
User opens blocked app
↓
Check SharedPreferences: active session running? ("session_end_time_{packageName}")
  YES + not expired → allow app directly (no overlay)
  NO or expired →
↓
OverlayManager reads totalMinutes from Room DailyLog (DateUtils.todayKey())
↓
totalMinutes < limitValueMinutes → SessionIntentionOverlay (Stage 1)
  Same flow as SESSION_LIMIT
  After allow: UsageTrackingService starts tracking time
    → writes totalMinutes to Room every 10s
    → writes totalMinutes to Firestore every 10s (fire-and-forget)
    → stores session_end_time_{packageName} in SharedPreferences
↓
totalMinutes >= limitValueMinutes → LimitExceededOverlay (Stage 2)
  "Nicht öffnen" only → dismiss + home
```

**TIME_LIMIT has NO per-session countdown** (by design — LimitType difference):
- `SESSION_LIMIT` and `DAILY_BUDGET` have session-scoped timers (user picks or counts down).
- `TIME_LIMIT` tracks cumulative daily usage only — there is no per-open session timer
  shown to the user. The total daily limit is the only constraint.

**Session persistence for TIME_LIMIT:**
Session end time is stored as `"session_end_time_{packageName}"` in SharedPreferences.
Brief app switches (Recents, pull-down notification shade) do NOT reset the session.
The session is considered active until `session_end_time` passes or the user explicitly
taps "Stark bleiben" (which clears the key and cancels the session timer).

**Timer runs only during active app usage:**
- Pauses while overlay is visible.
- Stops when user navigates away from the blocked app.
- This prevents `totalMinutes` from inflating due to overlay display time.

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
  Outside window → SessionLimitReachedOverlay ("Nicht öffnen" only)
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

// 3. Overlays are COMPOSE, not XML: OverlayManager builds a ComposeView
//    (createSessionComposeView) and attaches it to the WindowManager with the
//    params above. There is no XML layout inflation and no pre-inflated view
//    cache — the composable content is passed per overlay type.
```

### Overlay Inventory

#### 1. `SessionIntentionOverlay` — Stage 1 (Conscious Open Gate)
- Shown when user tries to open a blocked app
- Displays: current streak, motivation text, opens count today
- Buttons:
  - **"Nicht öffnen"** — large, green, prominent → dismiss overlay → go home
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

#### Custom motivation on the decision overlays
The user's own `challenge.customMotivation` (set at creation) is now surfaced **at the decision
moment** on every overlay where the user is choosing whether to open / continue. `OverlayManager`
passes `challenge.customMotivation?.takeIf { it.isNotBlank() }` as the `motivationText` parameter into:
- `SessionIntentionOverlay` (conscious-open gate),
- `SessionLimitReachedOverlay` (session / budget exhausted),
- `WebsiteBlockedOverlay` (blocked website),
- `BudgetSelectionOverlay` (budget-duration picker).

Null/blank motivation simply renders nothing (no empty slot). This reinforces the user's stated reason
exactly when they are tempted, rather than only on the dashboard.

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
- TIME_LIMIT: totalMinutes (used today, in minutes)
- DAILY_BUDGET: budgetRemainingMs / 60000 (remaining)

Label below: 13sp, #444 — clear text e.g. "von 5 Öffnungen heute verbraucht"

### Progress Bar
Keep existing component unchanged.
Add labels below: left = context text (11sp, #333), right = percentage.

### "trotzdem öffnen" button
SessionIntentionOverlay ONLY.
10sp, color #FFFFFF, transparent bg, no border, height 32dp.
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
    {consciousOpens: N, totalMinutes: M, updatedAt: timestamp}
    ↓
allowPackageTemporarily(packageName) → 5s whitelist
    ↓
App opens normally

On app start:
    Read from Firestore → restore consciousOpens (survives reinstall)
```

**Room DailyLog key format:**
```kotlin
val date = DateUtils.todayKey() // ALWAYS use this — never inline calc
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
  - `TIME_LIMIT` → filled based on `totalMinutes / limitValueMinutes`
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

→ Full auth flow: see docs/07_onboarding_and_auth.md

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

### Website Challenge — Icon + Name Display
- Favicons loaded via Google Favicon Service: `https://www.google.com/s2/favicons?domain=X&sz=64`
- Name shows domain (e.g. "instagram.com") per row
- Detail Screen shows "BLOCKIERTE WEBSITES" section: favicon + domain per row

---

## Completion Screens

Challenge outcomes trigger dedicated result surfaces:

| Outcome | Surface | Trigger |
|---------|---------|---------|
| Soft Mode COMPLETED | `ChallengeSuccessDialog` (dismissible Dialog on Dashboard) | `DailyEvaluationWorker` OR the on-app-open `SettleEndedSoftChallengesUseCase` backstop (see below) |
| Hard Mode COMPLETED | `ChallengeSuccessDialog` (money-refund variant) | `DailyEvaluationWorker` after Stripe refund succeeds |
| Soft Mode FAILED (intra-day limit breach) | `SoftFailResultScreen` | `OverlayManager` soft-fail / `DailyEvaluationWorker` |
| ANY FAILED (worker / permission loss / abandon) | unified RED `ChallengeFailedDialog` on Dashboard (names the challenge + human-readable `failReason`) | `DashboardViewModel` detects an unshown FAILED row |

*(The old fullscreen `HardModeFailOverlay`, `HardModeFailScreen`, and `SoftModeSuccessOverlay`
no longer exist — success and failure both surface as Dashboard dialogs.)*

### On-app-open Soft completion backstop — `SettleEndedSoftChallengesUseCase` (2026-07-16)

Solo Soft completion previously happened ONLY in the periodic `DailyEvaluationWorker`, which EMUI
throttles — plus the endDate=23:59:59.999 wrinkle meant the 23:59 run needed a next-day cycle.
The backstop runs in-process on every Dashboard load (`DashboardViewModel.loadStats()`, before
the dialog checks):
- Strictly SOFT-only and money-free: `mode==SOFT && stripePaymentIntentId==null &&
  groupChallengeId==null`; **open-ended challenges (`DateUtils.isOpenEnded`) are never completed**.
- Reuses the worker's exact trigger `DateUtils.hasReachedEnd(start, end, now)` (worker + backstop
  both route through it, so the two paths cannot diverge).
- FAILED iff today's DailyLog has `limitExceeded`, else COMPLETED — an additional net, never a
  worker replacement.

**`ChallengeSuccessDialog` (May 2026 — replaces both success overlays):** the old fullscreen
`SoftModeSuccessOverlay` and `HardModeSuccessOverlay` blocked the entire Dashboard, could not be
dismissed without starting a new challenge, and could accidentally navigate to the Detail screen.
They are replaced by a single **dismissible** `Dialog {}` shown on top of the Dashboard (other
challenge cards stay visible behind the scrim). It handles both Soft Mode (time-saved card) and
Hard Mode (money-refund card), with Canvas confetti, staggered phase reveals (0/300/600/900ms),
and count-up stat animations. An X button and a "Zurück zum Dashboard" link both dismiss it;
"Neue Challenge starten" navigates to the wizard.

- `DashboardViewModel` exposes `successDialogState: StateFlow<SuccessDialogState?>` (replaced the
  separate `completedChallenge` + `completedSoftChallenge` flows) plus `dismissSuccessDialog()`.
- **Show guard (2026-07-18):** SharedPreferences `"win_shown_{challengeId}"` (file
  `"detox_win_popup"`) + the DB `completionShown` flag are both marked **ON SHOW** (not on
  dismiss) — a process death between show and dismiss could otherwise re-pop the dialog. The
  dialog also **names the completed challenge** and offers a "Zum Verlauf" History CTA
  (`success_dialog_cta_history`). The RED `ChallengeFailedDialog` marks `completionShown`
  on show the same way.
- `DailyLogRepository.getLogsForChallengeOnce()` added for one-shot stat reads.

`SoftFailResultScreen` was previously dead code (worker called it but no navigation path existed).
Now correctly triggered via `DailyEvaluationWorker` result state. See `docs/00_changelog.md` →
"ChallengeSuccessDialog".

---

## Partial Block Feature — Removed

The "Spezifische Features sperren" section (Instagram Reels, YouTube Shorts, TikTok For You,
Facebook Reels, Twitter For You, Snapchat Spotlight) has been **removed** from the Websites tab
in all wizard flows.

- `partialBlockPaths` and `partialBlockSections` fields removed from wizard state.
- Adult Content blocking and manual domain blocking are **unchanged**.
- The partial block detection code in `AppDetectionAccessibilityService` may still exist but
  is no longer reachable from the creation flow.

---

## Known Issues (Soft Mode / Core)

1. **Group Challenge blocking unreliable:**
   `AppDetectionAccessibilityService` doesn't always block for Group Challenge participants because sync from Firestore to local Room can be delayed or missed.

---

## Universal Challenge Pattern

All challenge types share identical overlay logic and DailyLog structure.
The ONLY differences are what is added on top.

**CONFIRMED (code analysis):** Soft Mode and Hard Mode use the exact same overlay flow
end-to-end. There is no separate "Hard Mode overlay" implementation. Any overlay bug fix
applied to Soft Mode automatically covers Hard Mode.

| Layer | Soft Mode | Hard Mode | Group Challenge |
|-------|-----------|-----------|-----------------|
| Blocking logic | ✅ base | ✅ same | ✅ same |
| Overlays | ✅ base | ✅ same | ✅ same |
| DailyLog sync | ✅ base | ✅ same | ✅ same + participants |
| Stripe | ❌ | ✅ pre-auth/capture/refund | ✅ per participant |
| Firestore participants | ❌ | ❌ | ✅ arrayRemove+arrayUnion |

### DailyLog Firestore Structure (all types)
> Canonical field schema lives in **`firestore-schema.md`** — this is a simplified overview.
```
users/{userId}/dailyLogs/{challengeId}_{DateUtils.todayKey()}
    consciousOpens: Int      (SESSION_LIMIT)
    totalMinutes: Int        (TIME_LIMIT — minutes, NOT ms)
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
TIME_LIMIT:     progress = totalMinutes / limitValueMinutes
DAILY_BUDGET:   progress = budgetUsedMs / (dailyBudgetMinutes * 60000)
```

Display (remaining):
```
SESSION_LIMIT:  "${limitValueSessions - consciousOpens} opens remaining"
TIME_LIMIT:     "${limitValueMinutes - totalMinutes} min remaining"
DAILY_BUDGET:   "${budgetRemainingMs / 60000} min remaining"
```

**CRITICAL:** Detail screen must use IDENTICAL data source as Dashboard.
Both read from Room DailyLog via `DateUtils.todayKey()`.
Never use ViewModel state or passed-in arguments for progress display.

---

## Horizontal Scroll Number Picker (DetoxHorizontalPicker)

Used in ALL limit value inputs throughout the app.
Single reusable composable in `presentation/components/DetoxHorizontalPicker.kt`

### Updated bounds (current — see docs/08_ui_design_system.md for full table)
| Context | Old Range | New Range |
|---------|-----------|-----------|
| SESSION_LIMIT | 1–50 | 1–20 |
| TIME_LIMIT | 1–480 | 5–120 |
| DAILY_BUDGET | 1–480 | 5–120 |
| Session duration | 1–120 | 1–30 |
| Duration (Soft) | 1–365 | 3–90 |
| Duration (Hard) | 14–365 | 7–90 (1–90 debug) |
| Duration (Group) | 3–365 | 3–30 |
| Buy-in (Group) | 10–500 | 10–50 |
| Hard Mode stake | 5–500 | 5–100 |

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

### Minimum values (enforced — updated)
```
SESSION_LIMIT:  min 1,  max 20
TIME_LIMIT:     min 5,  max 120
DAILY_BUDGET:   min 5,  max 120, default 10
Session duration: min 1, max 30
Duration Soft:  min 3,  max 90
Duration Hard:  min 7 (1 in DEBUG), max 90
Duration Group: min 3,  max 30
Buy-in:         min 10 (€), max 50 (€)
Hard Mode stake: min 5 (€), max 100 (€)
```

### Next Button — App/Website Selection Step

The step-2 "Weiter" gate is **tab-aware** (`step2HasValidBlockingSource`) — the ACTIVE tab must
have a real blocking source that will actually be persisted at submit. See "Creation Wizard —
paths & gates" above. (The old union-of-both-tabs behavior was a bug — it allowed a
blocks-nothing challenge — and was removed 2026-07-16.)

---

## Limit Reduction Feature

Allows users to reduce their daily limit mid-challenge. Rules:

- Available in **Soft Mode and Hard Mode only** (not Group Challenge)
- The new (lower) limit is stored as `pendingLimit` in both Firestore and Room
- `pendingLimit` is applied at **midnight** (by `DailyEvaluationWorker`) — never immediately
- Reductions are **never reversible** — once `pendingLimit` is applied it becomes the new `limitValue`
- Users can see "Neues Limit ab morgen: X" in the Detail Screen when a `pendingLimit` is set
- Increasing the limit is not allowed (not exposed in UI)

Firestore fields added to challenge document:
  `pendingLimitValue: Int?`  — null when no reduction pending
  `pendingLimitApplyAt: Long?`  — Unix ms of next midnight when it applies

Room: same fields added to `ChallengeEntity`.

---

## Detail Screen Design (Soft Mode)

→ For UI/design specs see docs/08_ui_design_system.md

iOS-style, white background #F2F2F7, white cards.

### Card 1 — Header
- "SOFT MODE" badge (green pill) + end date right
- App name: 22sp bold
- 2 stats row: **Streak 🔥** | **Tage noch** (green)
- *(Beste Streak removed)*

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
