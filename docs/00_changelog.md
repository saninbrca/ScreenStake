# 00 — Changelog & Session Log
> **Scope:** Chronological log of all fixes, features, and architectural decisions.
> **When to load:** Always load this file first before any other docs/ file.
> This gives Claude Code full context on what has already been fixed and decided.

---

## How to use this file
- Read this BEFORE starting any task
- Add a new entry AFTER every fix or feature with date + short description
- Mark bugs as FIXED so they are never "re-fixed" accidentally
- Mark architectural decisions as DECISION so they are never reversed

---

## [Unreleased] — May 2026

### FIXED — Group Challenge win popup fires on every Detail Screen open

**Root cause (`GroupChallengeDetailViewModel`):**
`lastSyncedStatus` and `winDialogShown` are in-memory fields. Every time the Detail Screen is
opened a new ViewModel instance is created, both reset to their initial values, and the Firestore
snapshot fires immediately with `COMPLETED` status → `syncToLocalTracking` runs again →
`finishLocalGroupChallenge` is called every time and the win popup is shown every time.

**Fixes:**
- `winDialogShown` in-memory flag replaced with a persistent SharedPreferences guard:
  key `"win_popup_shown_$groupId"` in file `"detox_win_popup"`.
  Popup is shown at most once per challenge, survives screen close and process death.
  Set to `true` immediately before `triggerWinDialog` is called.
- `finishLocalGroupChallenge` guarded by a Room status check via new
  `GroupChallengeRepository.isLocalChallengeCompleted(groupId)`:
  skipped if local challenge is already `"completed"` or `"failed"`.
- Same Room guard applied to CANCELLED path (idempotent).
- New `isLocalChallengeCompleted` method added to `GroupChallengeRepository` interface and
  implemented in `GroupChallengeRepositoryImpl` (reads `challengeDao.getChallengeById("group_$groupId")?.status`).

**Files changed:** `GroupChallengeDetailViewModel.kt`, `GroupChallengeRepository.kt`, `GroupChallengeRepositoryImpl.kt`
**No Cloud Function changes. No Room schema changes. No UI layout changes.**

---

### FIXED — Hard Mode Duration Bug

**Root cause:**
endDate was calculated using wrong multiplier (minutes instead of days) in the ViewModel/Repository
layer before being passed to `CreateChallengeUseCase`. 14 days was saved as 14 minutes
(840000ms instead of 1209600000ms).

**Fix:**
- Replaced wrong multiplier with `durationDays.toLong() * DateUtils.MILLIS_PER_DAY`.
- Added `MILLIS_PER_DAY = 24L * 60 * 60 * 1000` constant to `DateUtils`.
- Fix verified for Hard Mode, Soft Mode, and Group Challenge creation.

---

## 2026-05-17

### FIXED — debug_use_minutes_as_days persists across clean builds → auto-reset on cold start

**Root cause (this session):**
The `debug_use_minutes_as_days` SharedPref was `true` from a prior debug session. A clean build does NOT wipe
SharedPreferences, so the flag survived all 3 builds. The endDate calculation in `CreateChallengeUseCase` is
correct (uses `DateUtils.MILLIS_PER_DAY` when the flag is off), but with the flag on, `durationMultiplier=60_000L`
→ `14 * 60_000 = 840_000ms = 14 min`. The Logcat ordering ("Challenge created:" before "Creating challenge:")
was misinterpreted as external calculation — both logs are inside `CreateChallengeUseCase.execute()` at lines
101 and 132 respectively.

**Fix:**
- `DetoxApplication.onCreate()`: inside the existing `if (BuildConfig.DEBUG)` block, auto-reset
  `debug_use_minutes_as_days` to `false` on every cold start.
  `Timber.d("DetoxApplication: debug_use_minutes_as_days reset to false on cold start")` is logged.
  The user must explicitly re-enable the flag each session via Debug Panel → Section 3 if needed for testing.

**DECISION:** `debug_use_minutes_as_days` resets to `false` on every cold start in DEBUG builds.
It is session-scoped — not persistent. No other files changed.

**Files changed:** `DetoxApplication.kt`
**No Cloud Function changes. No Room schema changes. No UseCase changes.**

---

### FIXED — Remaining inline 86_400_000L literals + debug flag still active + stale challenge repair

**Root cause (this session):**
The previous session fixed `CreateChallengeUseCase`, but the `debug_use_minutes_as_days` SharedPref was
still `true` from a prior test session. The debug toggle in the debug panel (Section 3 "Duration Mode: MINUTES ✓")
must be turned OFF before creating real challenges. A clean Build does NOT wipe SharedPreferences.
Additionally, five files still used inline `86_400_000L` literals instead of `DateUtils.MILLIS_PER_DAY`.

**Fixes:**
- `CreateChallengeUseCase`: Timber log now shows `diff=Xms = Ydays (Zmin)` — both ms AND days AND minutes.
  When debug flag is on, log will show e.g. `diff=840000ms = 0days (14min)`, making the issue unmistakable.
- `HistoryViewModel`: `originalDays` and Redemption `endDate` calc → `DateUtils.MILLIS_PER_DAY` (was `86_400_000L`).
  Also `sortDate` bucketing for history list.
- `ActiveChallengeScreen`: legacy endDate fallback and `daysLeft` calc → `DateUtils.MILLIS_PER_DAY`.
- `DashboardScreen`: `HardModeSuccessOverlay` endDate fallback and durationDays calc → `DateUtils.MILLIS_PER_DAY`.
  Redemption banner `daysLeft` calc → `DateUtils.MILLIS_PER_DAY`.
- `ProfileViewModel`: two `durationDays` display calcs → `DateUtils.MILLIS_PER_DAY`.
- `ProfileScreen` Debug Panel: added "Fix stale challenges (endDate < 1 day)" button in Section 3.
  Tap to auto-repair any active challenge whose `endDate - startDate < MILLIS_PER_DAY`.
  Reverses the `60_000L` debug multiplier: `originalDays = diff / 60_000`, then
  `newEnd = startDate + originalDays * MILLIS_PER_DAY`. Updates Room + Firestore.

**`debug_use_minutes_as_days` reminder:** Toggle is in Debug Panel → Section 3 "CHALLENGE TIME MANIPULATION".
When ON it shows "Duration Mode: MINUTES ✓". MUST be OFF for production testing.

**Files changed:** `CreateChallengeUseCase.kt`, `HistoryViewModel.kt`, `ActiveChallengeScreen.kt`,
`DashboardScreen.kt`, `ProfileViewModel.kt`, `ProfileScreen.kt`
**No Cloud Function changes. No Room schema changes.**

---

### FIXED — Hard Mode (and all challenge types) endDate calculated as minutes instead of days (critical)

**Root cause (`CreateChallengeUseCase`, `CreateGroupChallengeUseCase`, `GroupChallengeDetailViewModel`):**
Inline magic-number multipliers (`24 * 60 * 60 * 1000L`) were used instead of `DateUtils.MILLIS_PER_DAY`
in every endDate creation path. In DEBUG builds, `CreateChallengeUseCase` also reads a
`debug_use_minutes_as_days` SharedPref; if left `true` after testing, `durationMultiplier = 60_000L`
→ 14 days stored as 14 minutes (840 000 ms). Logcat proof:
`startDate=1779033208012 endDate=1779034048012 diff=840000ms = 0 days`

**Fixes:**
- `CreateChallengeUseCase`: both `86_400_000L` literals in the `durationMultiplier` block replaced
  with `DateUtils.MILLIS_PER_DAY`. DEBUG `60_000L` path unchanged (intentional fast-test shortcut).
  Added verification Timber log immediately after `val endDate`:
  `Challenge created: durationDays=N startDate=X endDate=Y diff=Zms = N days`
- `CreateGroupChallengeUseCase`: `durationDays.toLong() * 24 * 60 * 60 * 1000L`
  → `durationDays.toLong() * DateUtils.MILLIS_PER_DAY`
- `GroupChallengeDetailViewModel`: local endDate for group-start optimistic update
  `currentGc.durationDays * 24L * 60 * 60 * 1000` → `currentGc.durationDays.toLong() * DateUtils.MILLIS_PER_DAY`
- `GroupChallengeFirestoreService`: Firestore read fallback
  `startDate + 7L * 24 * 60 * 60 * 1000` → `startDate + 7L * DateUtils.MILLIS_PER_DAY`

**Inline constant cleanup (MILLIS_PER_DAY rule):**
- `GetDailyStatsUseCase`: `* 24L * 60L * 60L * 1000L` and `/ 86_400_000L` → `DateUtils.MILLIS_PER_DAY`
- `GetChallengeStreakUseCase`: private `DAY_MS = 24L * 60 * 60 * 1000` companion removed,
  usages replaced with `DateUtils.MILLIS_PER_DAY`
- `OverlayManager`: `/ 86_400_000L` → `DateUtils.MILLIS_PER_DAY`

**Files changed:** `CreateChallengeUseCase.kt`, `CreateGroupChallengeUseCase.kt`,
`GroupChallengeDetailViewModel.kt`, `GroupChallengeFirestoreService.kt`,
`GetDailyStatsUseCase.kt`, `GetChallengeStreakUseCase.kt`, `OverlayManager.kt`
**No Cloud Function changes. No Room schema changes. No UI layout changes.**
**Redemption Challenge** goes through `CreateChallengeUseCase` (HistoryViewModel calls it) — covered by fix #1.

---

## [Unreleased] — May 2026

### Fixed
- **nobodyFailed Bug (Group Challenge):** Fixed payout logic —
  when all participants win, 100% is refunded correctly
  (App fee: €0.00) instead of incorrectly applying 80% refund
- **Group Challenge Duration:** Audited startDate → endDate calculation —
  challenge runs exactly X days from the moment creator taps Start.
  Replaced inline 86400000L with MILLIS_PER_DAY constant in DateUtils

### Added
- **Payout UI — Expected Date:** Payout cards now show
  "Erwartet bis: DD. MMM YYYY" (endDate + 5 business days, 
  Saturday/Sunday skipped). Added DateUtils.addBusinessDays()
- **Payout Notification:** DailyEvaluationWorker sends local
  notification when challenge completes — 
  "💸 Deine Auszahlung ist unterwegs — €X bis DD. MMM YYYY"
- **Balance Card (ProfileScreen):** Shows pending prize amounts
  with manual "Auszahlen" button (never automatic). IBAN prompt
  only shown if no IBAN stored yet
- **Settings → Auszahlungskonto:** IBAN + account holder name
  now managed in Settings instead of directly in ProfileScreen
- **Settings → Verlauf:** Verlauf Screen now accessible via
  Settings under new "Aktivität" section
- **Verlauf Screen — Filter Tabs:** "Alle / Gewonnen / Ausgeschieden /
  Abgebrochen" — in-memory filtering, no new Firestore queries
- **Detail Screen — Abrechnung Section:** Payout breakdown added
  at the bottom of Group Challenge + Hard Mode Detail Screens
  (completed challenges only, never Soft Mode)

### Changed
- **ProfileScreen:** Verlauf section removed completely —
  only Avatar, Stats, and Balance Card remain
- **ProfileScreen:** Standalone "Auszahlungen" section removed —
  payout details now live in Detail Screen only
- **IBAN Flow:** IBAN no longer shown permanently in ProfileScreen —
  managed exclusively in Settings → Auszahlungskonto
- **Payout:** Never automatic — user must actively confirm "Auszahlen".
  Added Firestore status "requested"
- **payoutRequests Collection:** New entry created on payout request
  for manual admin processing

---

## 2026-05-17

### FIXED — Group Challenge endDate never written on challenge start (critical)

**Root cause (`startGroupChallenge` CF, `functions/src/index.ts`):**
`startGroupChallenge` wrote `{ status: "active", startDate: Date.now() }` but never
calculated or stored `endDate`. Every group challenge therefore had `endDate = 0` in
Firestore. `DailyEvaluationWorker.evaluateGroupChallenge` guards with
`endDate > 0L && now >= endDate` — this condition was permanently `false`, so
`completeGroupChallenge` was never auto-triggered.

**Fixes:**
- `startGroupChallenge` now reads `durationDays` from the Firestore doc, computes
  `endDate = startDate + durationDays * MILLIS_PER_DAY`, and writes both fields atomically.
- Added `functions.logger.info` at the startDate/endDate calculation point.
- Added `const MILLIS_PER_DAY = 86_400_000` in `index.ts` — no inline magic numbers.

**Android cleanup (no logic change):**
- Added `DateUtils.MILLIS_PER_DAY = 86_400_000L` constant.
- Replaced all 7 inline `86_400_000L` occurrences in `DailyEvaluationWorker.kt`
  (including the `24L * 3_600_000L` form in `setRedemptionInfo`) with
  `DateUtils.MILLIS_PER_DAY`.

**Files changed:** `functions/src/index.ts`, `DateUtils.kt`, `DailyEvaluationWorker.kt`
**Requires deploy:** `firebase deploy --only functions:startGroupChallenge`
**Existing active challenges with `endDate == 0`:** Must be patched manually in Firestore
  — set `endDate = startDate + (durationDays * 86400000)` on each affected document.

---

### FEATURE — Payout Card: expected refund date

ProfileScreen payout cards now show the expected or confirmed refund date below the status line.

**Logic:**
- `DateUtils.addBusinessDays(timestampMs, days)` added — skips Saturday + Sunday, no library needed.
- `endDateMs: Long` added to `PayoutChallengeInfo` (populated from `entity.endDate` in all three payout paths).
- Expected date = `endDate + 5 business days`, formatted as `d. MMM yyyy` with German locale.

**Display:**
- `payoutStatus == "refunded"` → "Gutschrift: DD. MMM YYYY" (black, #000000)
- `payoutStatus == "pending_payout"` → "Erwartet bis: DD. MMM YYYY" (gray, #8E8E93)
- `payoutStatus == "pending_payout" + prizeShareCents > 0` → IBAN CTA kept + note "Gewinnanteil wird nach IBAN-Hinterlegung überwiesen" below button

**Files changed:** `DateUtils.kt`, `ProfileViewModel.kt`, `ProfileScreen.kt`, `strings.xml`
**No Cloud Function or Stripe changes.**

---

### FIXED — Group Challenge nobodyFailed: wrong fee applied when nobody failed

**Root causes (3 bugs in `completeGroupChallenge` CF, `functions/src/index.ts`):**

1. `successParticipants` (old line 512) only filtered `status === "active"`, excluding winners with
   `status === "completed"` (already marked on a prior CF run). This caused `perWinnerBonus` to be
   inflated (denominator too small) in the someone-failed path.

2. `nobodyFailed` check was `failedParticipants.length === 0`. Per docs/09_payout_and_fees.md the
   correct check is `participants.every(p => active || completed)`. The old check returned `true` for
   any participant with status `"success"` (written by the someone-failed path on a previous partial
   run), potentially routing into the nobody-failed branch and attempting a double-refund.

3. No logging at the `nobodyFailed` decision point — impossible to diagnose production failures.

**Fixes (CF only — no Android changes):**
- `successParticipants` now includes both `"active"` and `"completed"` participants.
- `nobodyFailed` now uses `participants.every(p => p.status === "active" || p.status === "completed")`.
- Detailed `functions.logger.info` added at: participant-status dump, nobodyFailed decision,
  per-PI Stripe action (cancel / full-refund / partial-refund / unexpected-status), pot calculation.
- Nobody-failed Stripe branch: explicit `requires_capture` → cancel; `succeeded` → full refund;
  any other status → warn log only (no silent failure).
- `firebase deploy --only functions:completeGroupChallenge` required after this change.

**Manual Firestore fix for existing Booking.com challenge (already completed with wrong data):**
In the Firebase console, find the `groupChallenges/{groupId}` document for Booking.com and set:
  `nobodyFailed: true`, `appFee: 0`, `prizePool: 0`, `prizePerWinner: 0`.
Also update each participant to: `status: "completed"`, `payoutStatus: "completed"`.
ProfileScreen will then re-read and display €0 fee on next open (reads live from Firestore).

---

## 2026-05-16 (10)

### DOCS — Created docs/09_payout_and_fees.md
Centralized all payout and fee documentation.
Covers: fee structure table, Hard Mode 80%, Redemption 60%,
Group 80%/100%, IBAN setup, Stripe Connected Account,
ProfileScreen display, Firestore payout structure.
Extracted from 03 and 04 — those files keep flow logic only.

---

## 2026-05-16 (9)

### DOCS — Created docs/08_ui_design_system.md
Centralized all UI design documentation into single file.
Covers: colors (light+dark), typography, buttons, DetoxHorizontalPicker,
overlay design system, detail screen designs, dashboard cards.
Extracted from 02, 03, 04, 07 — those files keep business logic only.

---

## 2026-05-16 (8)

### DOCS — All documentation files updated

docs/01_architecture_and_stack.md:
- DetoxHorizontalPicker.kt added to file structure

docs/02_core_mechanics_and_soft_mode.md:
- Overlay Design System v2 documented (dark, minimal, context header)
- DetoxHorizontalPicker documented (all limit inputs, min values, behavior)
- Soft Mode Detail Screen design documented

docs/03_hard_mode_and_stripe.md:
- Payout fees updated: Hard Mode 80% (was 100%), Redemption 60% (was 70%)
- Redemption Challenge fully documented
- Hard Mode Detail Screen design documented

docs/04_group_challenges.md:
- Creation Flow fixed: PaymentSheet BEFORE createGroupChallenge
- Join Flow fixed: confirmGroupJoin added, button loading state
- Payout fees: 80% stake refund (20% fee), 100% if nobody fails
- Detail Screen redesign documented
- "Nerv ihn!" marked as temporarily removed
- Friends Tab: real-time listener documented (waiting + active)

CLAUDE.md:
- docs/06_testing_guide.md added to docs list
- docs/07_onboarding_and_auth.md added to docs list

---

## 2026-05-16 (7)

### FIXED — BudgetSelectionOverlay missing horizontal scroll picker
Root cause: Overlay still used old chip LazyRow instead of DetoxHorizontalPicker.
Fix: Replaced chip row with DetoxHorizontalPicker (darkMode = true).
Values: 1..remainingMinutes (inclusive). Default selected: min(5, remainingMinutes).
Button label "X min starten" updates dynamically on every picker scroll.
DetoxHorizontalPicker gains optional `darkMode` param (default false — no change for wizard callers).
Dark colors: bg #0A0A0A, selected #FFFFFF, adjacent #444444/#333333/#222222, fade edges #0A0A0A.
Unit label hidden when empty string passed (overlay has its own "Minuten wählen" label below).
Snap behavior and haptic feedback unchanged. Hard stop at both ends when remainingMinutes is small.

---

## 2026-05-16 (6)

### FEATURE — Overlay redesign v2
Context header: challenge-type specific line at top of every overlay (13sp, weight 600, #00C853).
  SESSION_LIMIT Soft: "🔥 X Tage Streak" | Hard: "💰 €X auf dem Spiel" | Group: "👥 Platz #X von Y".
  DAILY_BUDGET: "⏱ X min übrig heute". TIME_WINDOW: "📅 Verfügbar ab HH:MM".
Large number (64sp, bold, #FFF, letter-spacing -3) shows used/remaining value with clear label.
  SESSION_LIMIT: opens used + "von X Öffnungen heute verbraucht".
  TIME_LIMIT: minutes used + "von X Minuten heute verbraucht".
  DAILY_BUDGET: remaining minutes + "von X Minuten heute verfügbar".
  TIME_WINDOW: no number — status text "Noch nicht verfügbar" instead.
Progress bar kept unchanged; two text labels added below (left context, right %).
"trotzdem öffnen" reduced to 10sp #222222, height 32dp — SessionIntentionOverlay ONLY.
Daily Budget chips: dark #141414 bg, #444 text, #1E1E1E border; selected: #00C853 bg, #000 text.
  Ghost "Stark bleiben" removed from BudgetSelectionOverlay (ghost only on SessionIntentionOverlay).
Limit Reached: "Tageslimit erreicht 🔒" + "Morgen bekommst du neue Öffnungen." below progress bar.
  Single "Stark bleiben 💪" button only — no ghost.
Time Window: countdown inset radius 14dp, 32sp countdown font; button changed to "Stark bleiben 💪".
OverlayManager: buildContextHeader() + computeGroupRank() helpers added.
  SessionIntentionOverlay: removed lastSessionEndedAt, motivationText, challengeDaysLeft, streak params.
  SessionLimitReachedOverlay: new appName, contextHeader, largeNumber, largeNumberLabel params; removed streak.
Files: SessionIntentionOverlay.kt, SessionLimitReachedOverlay.kt, BudgetSelectionOverlay.kt,
       TimeWindowOverlay.kt, OverlayManager.kt, strings.xml.

---

## 2026-05-16 (5)

### FEATURE — Detail Screen Redesign
Soft Mode: Streak + Best Streak + Days, info list, success rate, quote.
Hard Mode: Orange badge, Einsatz, 80% payout info, Stripe note.
Group Challenge: Pot + Teilnehmer + Dein Gewinn, leaderboard with Du badge.
Existing progress bar kept. Challenge aufgeben = text link only.
Nerv ihn button removed temporarily.

---

## 2026-05-16 (4)

### FEATURE — Horizontal scroll picker for all limit inputs
DetoxHorizontalPicker composable implemented.
Applied to: SESSION_LIMIT, TIME_LIMIT, DAILY_BUDGET, duration, buy-in,
BudgetSelectionOverlay, session duration input.
Min values: 1 open / 1 min / 14 days Hard / 3 days Group / €10 buy-in.
Default DAILY_BUDGET = 10 min. Step = 1 everywhere.
Haptic feedback on each scroll step. Snap behavior. White background.

---

## 2026-05-16 (3)

### REVERT — Remove DetoxScrollPicker; restore original limit inputs
Reverted the iOS-style scroll picker feature in full.
- Deleted `presentation/components/DetoxScrollPicker.kt`
- Restored `ChallengeCreationScreen.kt` Step4 (StepperField) and Step6 (StepperField + FilterChip presets)
- Restored `GroupChallengeCreateScreen.kt` Step3 (StepperField per limit type + duration) and Step4BuyIn (StepperField, max €50, step €5)
- Restored `BudgetSelectionOverlay.kt` to chip picker (CHIP_OPTIONS: 5/10/15/20/30/45 min)
- Reverted `ChallengeCreationViewModel.kt` dailyBudgetMinutes default: 10 → 39
- Removed picker_* string resources from strings.xml
- Removed dark #0A0A0A backgrounds from wizard steps; light Material theme restored

---

## 2026-05-16 (2)

### FEATURE — iOS-style scroll picker for all limit inputs
Replaced all limit value inputs with `DetoxScrollPicker` composable.
Applied to: SESSION_LIMIT, TIME_LIMIT, DAILY_BUDGET, duration (Soft + Hard Mode), buy-in (Group Challenge), BudgetSelectionOverlay.
Min values enforced: 1 open / 1 min / 14 days Hard Mode (1 in DEBUG) / 3 days Group / €10 buy-in.
Default DAILY_BUDGET = 10 minutes (was 39).
Step size = 1 everywhere. Max = 480 min / 50 opens / 365 days / 500 € buy-in.
Haptic feedback (`HapticFeedbackType.TextHandleMove`) on each scroll step.
Dark #0A0A0A background on all wizard picker steps.
New file: `presentation/components/DetoxScrollPicker.kt`.

---

## 2026-05-16

### FIXED — Group Challenge Join Flow (alle 3 Bugs)
Root cause: confirmGroupJoin Cloud Function nie deployed → HTTP 404
→ "Erneut versuchen" obwohl Payment erfolgreich
→ Keine Navigation nach Join
→ Friends Tab leer bis App-Neustart

Fix: confirmGroupJoin zu index.ts hinzugefügt + deployed.
Updates participants array + participantUserIds (für Friends Tab Query).
alreadyJoined response = Success → navigiert zu GroupChallengeDetailScreen.
firebase deploy --only functions:confirmGroupJoin

### FIXED — Group Challenge Create ohne Zahlung
Root cause: createGroupChallenge vor PaymentSheet aufgerufen.
Fix: createPaymentIntent zuerst → PaymentSheet → Completed → createGroupChallenge.

### FIXED — Group Challenge Join ohne Zahlung
Root cause: joinGroupChallenge vor PaymentSheet aufgerufen.
Fix: createPaymentIntent zuerst → PaymentSheet → Completed → joinGroupChallenge.

---

## 2026-05-15

### FEATURE — Dark minimal overlay redesign
All overlays redesigned: #0A0A0A bg, no white cards, no app icons. Poppins throughout.
Psychologically intentional: ghost button "trotzdem öffnen" 12sp #333.
6 overlays updated: SessionIntention, LimitReached, BudgetSelection, TimeWindow, WebsiteBlocked, HardModeLockout.
SessionIntentionOverlay: emoji 📱, streak line, 3-col stats row, thin green progress bar, ghost button.
SessionLimitReachedOverlay: emoji 🔒, orange stats row, orange 100% progress bar, dark inset hint, no bypass.
BudgetSelectionOverlay: chip picker (5/10/15/20/30/45 min), inverted buttons (primary=start, ghost=stay strong).
TimeWindowOverlay: NEW — emoji ⏰, live countdown, open/close time row. Replaces BlockingScreenOverlay for TIME_WINDOW.
WebsiteBlockedOverlay: emoji 🌐, domain in dark inset, "Zurück" only.
HardModeLockoutOverlay: emoji 🔐, dark inset shows €X + days remaining.
OverlayManager: challengeDaysLeft computed and passed to Intention overlay; opensUsed/maxOpens/streak passed to LimitReached; budgetTotalMinutes passed to BudgetSelection; TIME_WINDOW routes to new showTimeWindowOverlay + computeMinutesUntilOpen helper.
Dark status bar: no code change required (overlays are ComposeViews without a Window).
No blocking logic changed — visual only.
Files: SessionIntentionOverlay.kt, SessionLimitReachedOverlay.kt, BudgetSelectionOverlay.kt, TimeWindowOverlay.kt (new), WebsiteBlockedOverlay.kt, HardModeLockoutOverlay.kt, OverlayManager.kt, strings.xml.

### FIXED — CRITICAL: User added to Group Challenge without paying
Root cause: `joinGroupChallenge` CF added user to `participants` + `participantUserIds` arrays before PaymentSheet was shown, before any Stripe payment.
Fix: `joinGroupChallenge` CF now only creates the PaymentIntent and returns `clientSecret` — no Firestore write.
New `confirmGroupJoin` CF added: called after `PaymentSheetResult.Completed`, verifies PaymentIntent status with Stripe, then adds user to participants. Handles idempotency — if user already in `participantUserIds`, returns `{ success: true, alreadyJoined: true }` (200, not 409).
Android ViewModel/UseCase flow was already correct (initiatePayment → PaymentSheet → onPaymentSuccess → confirmJoin). No Android changes needed.
**Requires:** `firebase deploy --only functions` after this change.
Files: `functions/src/index.ts`.

---


### FEATURE — App Fee on Winnings
Hard Mode Solo win: 80% refund (20% app fee). Cloud Function `cancelOrRefundPayment` now captures the pre-auth PI before issuing a partial refund — NEVER cancels on win anymore.
Redemption Challenge win: 60% refund (40% app fee, was 70%).
Group Challenge win (losers exist): 80% of own stake back + prize share from losers' pot.
Group Challenge win (nobody failed): 100% refund, no fee.
`completeGroupChallenge` CF detects `nobodyFailed` and issues full cancel/refund for all; otherwise captures-then-partially-refunds each winner's stake at 80%.
`cancelOrRefundPayment` CF: new `appFeeAmount` field written to Firestore alongside `payoutAmount`.
ProfileScreen now shows fee breakdown for all challenge types (20%/40%/none).
`DailyEvaluationWorker`: `setRedemptionInfo` changed from 0.70 → 0.60.
**Requires:** `firebase deploy --only functions` after this change.
Files: `functions/src/index.ts`, `DailyEvaluationWorker.kt`, `NotificationHelper.kt`, `strings.xml`, `ProfileViewModel.kt`, `ProfileScreen.kt`, `ChallengeEntity.kt`, `docs/03_hard_mode_and_stripe.md`.

### FIXED — createGroupChallengePaymentIntent HTTP 404
Root cause: `createGroupChallengePaymentIntent` CF was added to `index.ts` by the previous fix but never deployed to Firebase → 404 on every Group Challenge creation attempt.
Fix: Removed `createGroupChallengePaymentIntent` CF from `index.ts` (dead code). Android now calls existing `createPaymentIntent` with `amountCents=buyInCents` and `challengeId=groupId` — already tested and working. `createGroupChallenge` CF is still called only after `PaymentSheetResult.Completed`, with the `paymentIntentId` returned from `createPaymentIntent`.
Files: `functions/src/index.ts`, `CloudFunctionsService.kt`, `CreateGroupChallengeUseCase.kt`.

### FIXED — Group Challenge created before Creator pays
Root cause 1: `createGroupChallenge` CF was called before PaymentSheet — it created a Stripe PaymentIntent AND wrote the Firestore document (with creator as participant) in one step. Cancelling payment left an orphaned challenge in Firestore.
Root cause 2: New Cloud Function `createGroupChallengePaymentIntent` never deployed → HTTP 404.
Fix: Reuse existing `createPaymentIntent` for Creator buy-in (already deployed and tested).
Flow: `createPaymentIntent` → PaymentSheet → `Completed` → `createGroupChallenge` CF.
`createGroupChallenge` CF accepts `paymentIntentId` as required parameter; only called after `PaymentSheetResult.Completed`.
`onPaymentCancelled()` / `onPaymentFailed()` clear pending data and return to Idle — no CF call, no Firestore write.
Files: `functions/src/index.ts`, `CloudFunctionsService.kt`, `CreateGroupChallengeUseCase.kt`, `GroupChallengeCreateViewModel.kt`, `GroupChallengeCreateScreen.kt`, `strings.xml`.

## 2026-05-14

### FIXED — User added to Group Challenge without completing payment
Root cause: `confirmGroupJoin` CF was not explicitly gated; additionally, `Canceled` and `Failed` PaymentSheet results showed no feedback to the user and were treated identically.
Fix: `confirmGroupJoin` only called inside `PaymentSheetResult.Completed` branch (was already wired correctly in ViewModel; verified and unchanged).
Fix: `PaymentSheetResult.Canceled` now shows snackbar "Zahlung abgebrochen" and returns user to Preview.
Fix: `PaymentSheetResult.Failed` now shows snackbar "Zahlung fehlgeschlagen. Bitte erneut versuchen." and returns user to Preview.
Fix: `AwaitingPayment` state added to `isLoading` condition — spinner shown while PaymentSheet is loading, preventing double-tap.
Files: `GroupChallengeJoinScreen.kt`, `res/values/strings.xml`.

### FIXED — Debug buttons never wrote to Room DailyLog
Root cause: `challengeDao` returned `Flow` instead of `suspend List` → never emitted in a one-shot coroutine → 0 challenges found → nothing written.
Fix: Added `suspend fun getActiveChallengesSync()` to `ChallengeDao`.
Fix: Added upsert with `OnConflictStrategy.REPLACE` to `DailyLogDao`.
Fix: Wrapped all debug write buttons in `try-catch` with `Timber.e`.
Fix: `DashboardViewModel` now observes `DailyLog` via `Flow` for live updates.
Applied to: Max Opens Now, Reset Opens Today, Exhaust Budget Now, Reset Budget Today.

### FIXED — Debug buttons never wrote to Room DailyLog (limitType case mismatch)
Root cause: `ChallengeRepositoryImpl.Challenge.toEntity()` stores `limitType` as **lowercase** in Room (`"sessions"`, `"time_budget"`). All 4 debug functions in `ProfileViewModel` filtered against **uppercase** strings (`"SESSIONS"`, `"TIME_BUDGET"`), so the filter matched zero rows. The forEach loop ran on an empty list — `upsert()` was never called, no DailyLog was ever written, the Dashboard Flow never fired.
Fix: All 4 filters changed to lowercase: `"sessions"` and `"time_budget"`.
Fix: Every function wrapped in `try-catch(Exception)` with `Timber.e` on failure.
Fix: Added `Timber.d` at every step: function start, total active count, SESSIONS/TIME_BUDGET count, challengeId, key, existing row state, before upsert, after upsert verification read.
DailyLogDao.upsert() and Dashboard observeLogsForDate() Flow were already correct — they work now that Room is actually written.
Applies to: `debugMaxOpensNow`, `debugResetOpensToday`, `debugExhaustBudgetNow`, `debugResetBudgetToday`.

### FIXED — Debug buttons not updating Dashboard (previous entry — partially correct)
Root cause: All 4 debug functions (`debugMaxOpensNow`, `debugResetOpensToday`, `debugExhaustBudgetNow`, `debugResetBudgetToday`) used plain SQL `UPDATE` queries (`updateConsciousOpens`, `updateBudgetStateMs`). When no `DailyLogEntity` row exists for today yet, `UPDATE WHERE ...` matches zero rows and silently does nothing — Room emits no change, Dashboard Flow never re-fires.
Fix: All 4 functions now use `getLogForDate()` to read the existing row (or null), then call `dailyLogDao.upsert()` (new `@Insert(onConflict = REPLACE)` method) with either `existing.copy(...)` or a freshly constructed entity.
Fix: `DailyLogDao` gains a `upsert(log: DailyLogEntity)` method for this pattern.
Fix: All Firestore writes now include `updatedAt` timestamp.
Note: This fix was incomplete — the limitType case mismatch was still causing zero rows to match (see entry above).
Dashboard Flow was already correct (`observeLogsForDate()` Flow with `drop(1)`).

---

## 2026-05-09

### FEATURE — Redemption Challenge

- Hard Mode Solo failures now trigger a "comeback" challenge after 24h
- Users fight to recover 70% of their lost stake at double duration, half the limit
- Dashboard banner (session-dismissable, orange) shows when redemption is available
- History screen "Starten" button with `RedemptionConfirmSheet` (ModalBottomSheet)
- Stripe partial refund on success via new `partialRefundCents` parameter in CF
- DB migration 21→22: 11 new columns on `challenges` table
- `RedemptionNotificationWorker` (HiltWorker) fires after 24h delay via WorkManager
- Eligibility: `originalDays <= 28`, has Stripe PI, not itself a redemption, within 3 days

---

### DOCS — Partial App Blocking documented in architecture files

docs/01_architecture_and_stack.md updated:
- `PartialBlockSection.kt` added to `domain/model/` file structure
- New section "Partial App Blocking — Architecture Notes" added
- Cache pattern: `activePartialBlockSections` in `TrackedAppEventBus` / `AppDetectionAccessibilityService`
- Room migration: `partial_block_sections` column (TEXT, default `""`) + `partial_block_only` (INTEGER, default `0`)
- Firestore storage format documented (`partialBlockSections: List<String>`, `isPartialBlockOnly: Boolean`)
- Event detection order documented (performance critical: `TYPE_WINDOW_STATE_CHANGED` first)

docs/02_core_mechanics_and_soft_mode.md updated:
- New section "Partial App Blocking (Reels / Shorts / Feed)"
- All 6 supported sections with packages and blocked content
- Detection order: Activity class name → ViewID → Content description (fastest to slowest)
- On detection: `GLOBAL_ACTION_BACK` + `Toast.LENGTH_SHORT` (NEVER `GLOBAL_ACTION_HOME`)
- `consciousOpens` rule: partial blocks NEVER increment counter
- `AppSelectionScreen` UI pattern documented (indented sub-options, always visible)
- Known limitations documented (ViewID changes on app updates, Huawei timing)

### FEATURE — Partial App Blocking (Reels / Shorts / For You)
New feature allowing challenges to block specific content sections inside native apps while leaving the rest of the app usable.
Detection via `AppDetectionAccessibilityService` using activity class names, view IDs, and content descriptions — zero DB queries on hot path.
6 sections supported: Instagram Reels, YouTube Shorts, TikTok For You, Facebook Reels, Twitter For You, Snapchat Spotlight.
`PartialBlockSection` enum in `domain/model/` holds detection metadata per section.
`isPartialBlockOnly = true` challenges skip full-block overlay — package excluded from `trackedPackages` in `UsageTrackingService`.
Room DB: version 20→21, adds `partial_block_sections` (TEXT) and `partial_block_only` (INTEGER) columns to `challenges` table.
Firestore: `partialBlockSections` (List<String> of IDs) + `isPartialBlockOnly` (Boolean) written/read on sync.
UI: `ChallengeCreationScreen` step 2 shows indented `PartialSectionSubRow` composable after each supported app row.
`AppSelectionScreen`: same sub-option rows; `onAppsSelected` callback extended with `partialSections: List<String>`.
Detection response: `GLOBAL_ACTION_BACK` + short Toast "🔒 <section> geblockt" — no overlay, no `consciousOpens` increment, 1s cooldown.
Docs: `02_core_mechanics_and_soft_mode.md` updated with Partial App Blocking section.

### FEATURE — Automatic Payout System
Hard Mode: automatic Stripe refund on completion via DailyEvaluationWorker — no manual action required.
Group Challenge: automatic stake refund + prize transfer on completeGroupChallenge CF.
Prize calculation: (total captured from losers - 10% appFee) / winners count (Math.floor).
`cancelOrRefundPayment` CF: removed `wasImmediate` flag — now auto-detects PI status (`requires_capture` → cancel, else → refund).
`completeGroupChallenge` CF: aligned field names (`prizePool`, `appFee`, `prizePerWinner`); enriched pendingPayout with `stakeRefundCents`/`status`/`displayName`.
`createConnectedAccount` CF: rewritten Express → Custom account with IBAN direct setup (no onboarding URL).
UI: detailed per-challenge payout breakdown cards in ProfileScreen (stake refund + prize share + app fee + status).
IBAN setup: ModalBottomSheet with AT IBAN validation → `createConnectedAccount` CF → prize released automatically.
Pending payouts: stored in `users/{uid}/pendingPayouts` subcollection with `status: "pending_account_setup"`.
Notifications: `sendGroupChallengePayoutReceived()` — three variants (full payout / stake + pending IBAN / stake only).
Docs: `03_hard_mode_and_stripe.md` updated with full Payout System section.

### FEATURE — Automatic Payout System implemented
Hard Mode: Stripe refund via DailyEvaluationWorker on COMPLETED.
Group Challenge: stake refund + prize transfer via completeGroupChallenge CF.
Prize calculation: (losers total - 10% fee) / winners count (Math.floor, never round up).
IBAN: Stripe Custom Connected Account (type "custom", not Express).
UI: detailed breakdown in ProfileScreen (Einsatz + Gewinnanteil + Gebühr).
Pending: stored in payoutRequests if no IBAN yet.
Notifications: automatic after payout.
Docs: `03_hard_mode_and_stripe.md` updated with Payout System section.

### DOCS — Added docs/07_onboarding_and_auth.md
Extracted onboarding and auth documentation from 01_architecture_and_stack.md into dedicated file for token efficiency. Covers: 5-screen onboarding flow, design system, permission setup, auth methods, logout rules, Hard Mode device binding, account deletion.

### FEATURE — Comprehensive DEBUG Testing Panel
Added collapsible debug section in ProfileScreen (DEBUG builds only).
9 sections: Onboarding, Daily Evaluation, Time Manipulation, Budget,
Opens, Group Challenge, Stripe, Room Database, Permissions.
Enables fast testing of all features without waiting real time.
- DailyLogDao: +getAllForDate, +deleteAllForDate, +updateConsciousOpens
- ChallengeDao: +updateEndDate
- GroupChallengeDao: +updateEndDate
- CreateChallengeUseCase: debug_use_minutes_as_days flag wired (multiplies durationDays * 60000 instead of 86400000)
- ChallengeCreationViewModel: debug_hard_mode_min_1 flag wired (Hard Mode min = 1 day in debug)
- ProfileViewModel: 10 debug functions + 3 debug StateFlows added
- ProfileScreen: DebugPanel composable (collapsed by default, 9 sections, orange card)
- Old standalone debug buttons (Test Blocking, Reset Onboarding) merged into Section 1

### FEATURE — Onboarding Flow implemented
5 swipeable screens: Welcome, Concept, Modes, Permissions, Start.
iOS-style design: #F2F2F7 background, white cards, #00C853 primary.
Shows only on first app start (SharedPreferences "onboarding_completed" in "detox_settings").
determineStartDestination() checks flag first — before any auth/permission logic.
Permission setup on Screen 4 with live checkmarks on every onResume.
Huawei battery optimization guidance dialog on Screen 4.
Debug reset button added in ProfileScreen (BuildConfig.DEBUG block).
New route: Screen.Welcome ("welcome") in DetoxNavGraph.
AuthScreen gains initialTab: AuthTab parameter; auth route extended to "auth?tab={tab}".
New file: presentation/screens/welcome/WelcomeOnboardingScreen.kt

---

## 2026-04-29

### FIXED — completeGroupChallenge HTTP 500
- Root cause: participants.filter() called on Map instead of Array (Firestore partial snapshot)
- Fix: Safe parser added before any .filter()/.map() call in Cloud Function
- Pattern: Array.isArray(raw) ? raw : Object.values(raw ?? {})
- Applied to: completeGroupChallenge, failParticipant, startGroupChallenge

---

## 2026-04-30

### FIXED — SessionLimitReachedOverlay: "Challenge verlieren" removed
- Root cause: User could quit challenge directly from overlay (moment of weakness)
- Fix: Removed quit button from ALL overlays
- New behavior: Single "Stark bleiben 💪" button only
- Only way to quit: Dashboard → Detail → "Aufgeben" button
- Applies to: ALL limit types, ALL overlays

### FIXED — Usage Schedule default state
- Root cause: All days (Mon-Sun) were pre-selected by default
- Fix: All days deactivated by default (empty = no schedule = blocked 24/7)
- User must explicitly activate days they want

### FIXED — Phantom Group Challenge lock (Hevy, Booking.com)
- Root cause: Cancelled/completed Group Challenges still marked "active" in Room
- Fix: "Is app occupied?" check only considers status == "active" OR "waiting"
- Fix: Sync Group Challenge status from Firestore to Room on app start

### DECISION — Daily Budget timer architecture
- Timer runs in UsageTrackingService (Foreground Service) — NOT OverlayManager
- sessionEndTime persisted in SharedPreferences (survives kills)
- On expiry: BudgetSelectionOverlay shown immediately over any screen (TYPE_APPLICATION_OVERLAY)
- On expiry while app closed: overlay shown on next foreground (Option B1 rejected → B2 chosen)

---

## 2026-05-01

### FIXED — Timezone bug: DailyLog written with wrong date key
- Root cause: OverlayManager used local timezone, GetDailyStatsUseCase used UTC
- Difference: 12 hours (UTC+2 Vienna) → Dashboard always read "null" from Room
- Fix: DateUtils.todayKey() created — single shared function using Calendar.getInstance()
- RULE: Search for "86400000" must return 0 results anywhere in codebase

### FIXED — Dashboard shows 0 for all challenges (consciousOpens, budgetUsedMs)
- Root cause: GetDailyStatsUseCase read DailyLog with wrong todayKey (UTC vs local)
- Fix: DateUtils.todayKey() applied to ALL date key calculations
- Status: consciousOpens ✅ working, budgetUsedMs ✅ working after Firestore sync added

### FIXED — budgetUsedMs resets to 0 on app restart (Huawei)
- Root cause: Huawei kills UsageTrackingService without calling onDestroy()
  budgetUsedMs was only written on session end or onDestroy() → never persisted
- Fix: Write budgetUsedMs to Room + Firestore every 10 seconds (fire-and-forget)
- Fix: On app start, restore budgetUsedMs from Firestore → Room → SharedPreferences
- Pattern: Identical to consciousOpens sync (see 02_core_mechanics_and_soft_mode.md)

### DECISION — Firestore sync pattern for ALL time-based tracking
- Room = immediate local display (always read for UI)
- Firestore = persistent backup (restored on app start)
- SharedPreferences = active session state only (sessionEndTime, committedMs)
- SetOptions.merge() always — never overwrite other fields
- Fire-and-forget — never block UI on Firestore response

### KNOWN BUG — Fortschrittsbalken in Detailansicht shows 0 for all limit types
- All limit types affected (SESSION, TIME, DAILY_BUDGET, TIME_WINDOW)
- Dashboard shows correct values ✅
- Detail screen shows 0 ❌
- Not yet fixed — next priority

---

## 2026-05-02

### DECISION — Hard Mode unified with Soft Mode
Hard Mode shares ALL blocking/overlay/DailyLog code with Soft Mode.
Only addition: Stripe pre-auth on start, capture on fail, refund on success.
Any Soft Mode fix must be verified against Hard Mode as well.

### DECISION — Group Challenge unified with Soft/Hard Mode
Group Challenge shares ALL blocking/overlay/DailyLog code with Solo challenges.
Additions: Stripe per participant, Firestore participants sync, leaderboard.
Any Solo fix must be verified against Group Challenge as well.
Two sync targets: DailyLog (identical to Solo) + participants array (Group-specific).

### DECISION — Universal architecture documented
DateUtils.todayKey() mandatory everywhere — grep for 86400000 must return 0.
Universal sync pattern documented for all challenge types.
Fortschrittsbalken global rule: always read from Room DailyLog fresh.
Never pass progress values as navigation arguments.

---

## 2026-05-03

### FIXED — Fortschrittsbalken shows 0 in Detail screen (all limit types)
Root cause: Detail screen read progress from stale one-shot suspend call instead
of live Room DailyLog Flow. TIME_BUDGET also used wrong field (budgetUsedMinutes
instead of budgetUsedMs).
Fix: ActiveChallengeViewModel now collects observeLogForDate() Flow — auto-refreshes
whenever UsageTrackingService writes to Room (every 10 s) or on every conscious open.
Fix: TIME_BUDGET uses budgetUsedMs / 60_000 (source of truth), not budgetUsedMinutes.
Fix: Removed private todayMidnightMs() duplicate — replaced with DateUtils.todayKey().
Fix: Added DailyLogDao.observeLogForDate + DailyLogRepository.observeLogForDate.
Applies to: Solo Detail screen (ActiveChallengeViewModel) + Group Challenge Detail screen.
Group fix: MyStatusCard now reads myOpensToday / myTimeUsedMinutes from Room DailyLog
via GroupDetailUiState.Success fields — NOT from Firestore participants array.

### FIXED — DAILY_BUDGET not working in Group Challenge
Root cause 1: syncGroupChallengeToLocalTracking() did not set dailyBudgetMinutes on the local
ChallengeEntity. handleTimeBudgetApp() computed totalBudgetMs = 0 → budget always 0ms,
overlay logic immediately skipped to "exhausted" but no Group Challenge fail path was reached.
Root cause 2: showBudgetExhaustedOverlay() did not check groupChallengeId — it only showed
SessionLimitReachedOverlay and freed the app. handleGroupChallengeFail() was never called,
so the Stripe capture and Firestore fail were silently skipped.
Root cause 3: checkBudgetSession() 10s tick wrote budgetUsedMs to Room but did not mirror
timeUsedMinutes to the Firestore participants array for Group Challenge DAILY_BUDGET.
Fix 1: GroupChallengeRepositoryImpl.syncGroupChallengeToLocalTracking() now sets
  dailyBudgetMinutes = limitValueMinutes when limitType == TIME_BUDGET.
Fix 2: OverlayManager.showBudgetExhaustedOverlay() now checks challenge.groupChallengeId —
  if non-null, delegates to handleGroupChallengeFail() (Stripe capture + fail overlay).
  Solo path unchanged (SessionLimitReachedOverlay).
Fix 3: UsageTrackingService.checkBudgetSession() now mirrors totalUsedMs/60000 to
  groupChallengeRepository.updateParticipantTimeUsed() on every 10s tick (fire-and-forget).
Applies to: Group Challenge DAILY_BUDGET. SESSION and TIME paths were already unified.

### FIXED — DAILY_BUDGET Group Challenge: failedGroupChallengeIds guard not applied + double-fail
- **Root cause 1:** `failedGroupChallengeIds` guard in `handleAppOpen()` was placed AFTER the
  `SESSIONS` and `TIME_BUDGET` early-return branches. Both limit types bypassed the guard entirely,
  so `handleTimeBudgetApp` could be called on a package whose Group Challenge was already failed.
- **Root cause 2:** `onBudgetSessionExpired()` calls `handleTimeBudgetApp()` directly (bypasses
  `handleAppOpen`). After a Group DAILY_BUDGET fail, budget session prefs were never cleared by
  `handleGroupChallengeFail()`. The next 10-second `checkBudgetSession` tick saw the prefs, fired
  `onBudgetSessionExpired` → budget exhausted → `handleGroupChallengeFail` again →
  `failGroupParticipant` cloud function called **twice**.
- **Fix 1:** `handleAppOpen()` refactored — `failedGroupChallengeIds` guard moved before the
  `when (challenge.limitType)` dispatch so it covers ALL limit types (SESSIONS, TIME_BUDGET, TIME,
  TIME_WINDOW). Single unified `when` block replaces the previous chain of `if … return` branches.
- **Fix 2:** `onBudgetSessionExpired()` now checks `failedGroupChallengeIds` before calling
  `handleTimeBudgetApp()`.
- **Added:** Diagnostic log `Group challenge check: pkg=… challengeType=GROUP limitType=DAILY_BUDGET`
  in both budget-session re-entry blocks in `AppDetectionAccessibilityService`.
- SESSION_LIMIT and TIME_LIMIT Group Challenge blocking verified via same unified dispatch.

### FIXED — Group Challenge Join Flow compile error
- Root cause: `@Composable` invocation in `GroupChallengeJoinScreen.kt` line 85 was called
  outside the Composable tree (direct invocation from a non-Composable lambda/block).
- Fix: Moved `@Composable` call into the Composable tree, controlled via a `showDetails: Boolean`
  state variable instead of direct invocation.
- Rule: Never invoke a `@Composable` function from outside a Composable context — use state
  variables to conditionally include Composable blocks.

### FIXED — Group Challenge Join Flow (double payment bug)
Root cause: confirmGroupJoin Cloud Function was never called after PaymentSheetResult.Completed.
Screen closed prematurely (navigated to group_detail), leaving user unenrolled. They had
to enter code and pay a second time before the join was confirmed.
Fix: onPaymentSuccess() now sets ConfirmingJoin state, calls confirmGroupJoin CF, and only
sets JoinedSuccessfully after the CF succeeds. Loading state prevents double-tap on Pay button.
Fix: Navigation after join now goes to FriendsHubScreen (Friends tab), not group_detail.
Fix: lastAwaitingPayment stored in ViewModel for confirmJoin retry if CF fails after payment.
New state: ConfirmingJoin(groupChallenge) — disables Pay button, shows spinner.
UX: GroupDetailsCard redesigned — shows app icon, buy-in prominently, limit type in German,
    duration, participants count, creator name, start info.
Strings: join screen translated to German (Suchen, Jetzt beitreten — €X bezahlen, etc.)

### FIXED — Group Challenge DAILY_BUDGET always shows full budget on first open
Root cause: budgetUsedMs restored from Firestore on app start for Solo only.
Group challenges have no entries in the nested Solo dailyLogs sub-collection (step 2
in syncUserData). Step 4 (fetchTodayDailyLogs) is unreliable for group challenges:
if refreshFromFirestore fails, a FK constraint violation aborts step 4 silently.
Result: Group challenge DailyLog (key: "group_{groupId}_{todayKey}") never in Room
on first open → OverlayManager falls back to totalBudgetMs.
Fix: Added step 3a to syncUserData() — explicit Group Challenge budget restore:
- After refreshFromFirestore (ChallengeEntity in Room), for each ACTIVE TIME_BUDGET group challenge
- Reads Firestore: users/{userId}/dailyLogs/group_{groupId}_{todayKey} via fetchDailyLogDocument()
- Writes budgetUsedMs + budgetRemainingMs to Room DailyLog
- Sets SharedPreferences budget_committed_ms = budgetUsedMs (guarded: skip if session active)
- If document does not exist yet (first use today): budgetUsedMs=0, budgetRemainingMs=totalBudgetMs
- Log: "Group budget restore: groupId=... usedMs=... remainingMs=..."
Added FirestoreService.fetchDailyLogDocument(userId, challengeId, date) to read single flat doc.
Key format verified consistent: write (updateDailyLogBudget) and read both use "group_{groupId}_{todayKey}".

### FIXED — Group Challenge DAILY_BUDGET budget_committed_ms wrong on restart
Root cause 1: SharedPreferences used single "budget_committed_ms" key for Solo + Group.
  onStartCommand restart-recovery looped through all TIME_BUDGET challenges and the last
  challenge's usedMs overwrote the others → wrong committed value for the active challenge.
Root cause 2: Leaderboard time read from UsageStatsManager (system screen time) instead of
  Room DailyLog budgetUsedMs → showed 13 min (all-day screen time) instead of 3 min budget used.
Root cause 3: Midnight reset only cleared the single shared key, not per-challenge keys.
Fix 1: Per-challenge SharedPreferences key "budget_committed_ms_{challengeId}" used everywhere:
  onStartCommand, checkBudgetSession (read + write), SyncRepositoryImpl step 3a.
  Key for Group Challenge = "budget_committed_ms_group_{groupId}" (challengeId = "group_{groupId}").
Fix 2: updateGroupChallengeStats() for TIME_BUDGET reads dailyLogRepository.getLogForDate()
  and uses budgetUsedMs / 60_000 as timeUsedMinutes — Room is always source of truth.
  Non-budget Group Challenges (TIME/SESSIONS) still read from UsageStatsManager unchanged.
Fix 3: Midnight reset uses budgetSessionPrefs.edit().clear() — wipes all keys in the prefs
  file including all per-challenge "budget_committed_ms_*" entries from the day.
Added Timber logs in SyncRepositoryImpl step 3a for Firestore path, read values, Room state,
  and SharedPreferences key to allow pinpointing future failures.

### FIXED — Group Challenge budget tick never wrote to Firestore dailyLogs
Root cause: Firestore dailyLogs write existed for Solo (via DailyLogRepositoryImpl appScope)
but was not explicitly mirrored in the Group Challenge path of checkBudgetSession().
Fix: Added explicit serviceScope.launch { firestoreService.updateDailyLogBudget(...) }
inside the challenge.groupChallengeId?.let block, alongside the existing participants mirror.
docId format: "group_{groupId}_{DateUtils.todayKey()}" — matches restore step 3a in SyncRepositoryImpl.
Solo path unchanged (handled via DailyLogRepositoryImpl).

### DECISION — Group Challenge never auto-fails for any limit type
All limit types (SESSION, TIME, BUDGET): limit reached = SessionLimitReachedOverlay only.
App stays blocked. Participant status remains "active". No Stripe capture.
Stripe capture ONLY on manual "Aufgeben" in Detail screen.
endDate reached = success → Stripe refund for all active participants.

Changes:
- OverlayManager: TIME_LIMIT group challenge → showSessionLimitReachedOverlay instead of handleGroupChallengeFail
- OverlayManager: DAILY_BUDGET group challenge → showSessionLimitReachedOverlay instead of handleGroupChallengeFail
- DailyEvaluationWorker: group evaluateGroupChallenge → removed failGroupParticipant CF call; DailyLog still written with limitExceeded=true (stats only), moneyLostCents=0
- GroupChallengeDetailViewModel: added QuitState + quitChallenge() — calls failGroupParticipant CF on manual quit
- GroupChallengeDetailScreen: added "Aufgeben" button with confirmation dialog; navigates to FriendsHub on confirm
- completeGroupChallenge CF: already correct — active participants get refunded on endDate
- docs/04_group_challenges.md: updated Group Challenge Rules table + Fail & Complete Logic section

### FIXED — Group Challenge budget resets to 0 on tab switch
Root cause: GetDailyStatsUseCase read DailyLog from Room using challenge.id (UUID) for Group
Challenges, but Group DailyLogs are stored with challengeId = "group_{groupId}". todayLog was
always null → budgetUsedMs always 0 in the no-active-session path.
Secondary: BUDGET_COMMITTED_MS_KEY used the base key ("budget_committed_ms") instead of
the per-challenge key ("budget_committed_ms_group_{groupId}") → committedMs was 0 in the
active session path.
Fix: dailyLogChallengeId = "group_${groupChallengeId}" for Group Challenges in GetDailyStatsUseCase.
Fix: per-challenge committedMs key used in active session path.
Fix: DashboardViewModel.todayMidnightMs() replaced with DateUtils.todayKey() (architecture rule).

### FIXED — Group Challenge DailyLog deleted from Room during Firestore sync
Root cause: `syncGroupChallengeToLocalTracking()` called `challengeDao.insertChallenge(entity)` with
`OnConflictStrategy.REPLACE`. SQLite's `INSERT OR REPLACE` deletes the existing `ChallengeEntity`
row before re-inserting it. The FK `onDelete = CASCADE` on `DailyLogEntity` cascade-deletes the
Group DailyLog (`challengeId = "group_{groupId}"`) in the same transaction.
Trigger: The Firestore real-time listener in `startSyncingForUser` fires its FIRST emit ~1s after
`syncUserData()` completes (network latency). `syncedStatuses` is still empty at that point, so the
status guard allows `syncGroupChallengeToLocalTracking` to run and wipe the freshly-created log.
Fix: Added `@Update updateChallenge()` to `ChallengeDao`. In `syncGroupChallengeToLocalTracking`,
check if the `ChallengeEntity` already exists: if so, call `updateChallenge()` (SQL UPDATE — no
delete, no cascade) instead of `insertChallenge()`. First-time inserts are unaffected.
Fix: `SyncRepositoryImpl` step 4 now uses `DateUtils.todayKey()` instead of inline Calendar calc
(architecture rule). Added diagnostic logs for DailyLog keys being written.

---

## 2026-05-04

### FIXED — Group Challenge budget resets to 0 on tab switch / app restart
Multiple root causes fixed over several sessions:

1. Firestore dailyLogs write missing in Group budget tick
   Fix: Added identical Firestore write (SetOptions.merge(), fire-and-forget)
   every 10s in UsageTrackingService for Group challenges
   docId: "group_{groupId}_{DateUtils.todayKey()}"

2. Group Challenge DailyLog deleted from Room during Firestore sync
   Root cause: DashboardViewModel syncJob deleted ALL DailyLogs before
   re-inserting from Firestore. Group DailyLogs not in Firestore fetch → deleted.
   Fix: UPSERT only (never DELETE). Sync includes Group challenge IDs.

3. SharedPreferences keys not per-challenge
   Fix: "budget_committed_ms_{challengeId}" — Solo and Group never overwrite each other

4. Leaderboard showed wrong time (13 min instead of 3)
   Fix: Leaderboard reads from Room DailyLog directly, not SharedPreferences

### DECISION — DailyLog sync rules (permanent)
- NEVER delete DailyLog rows during any sync operation
- UPSERT only: @Insert(onConflict = REPLACE) always
- Firestore sync must include ALL challenge types (Solo + Group)
- Group DailyLog key: "group_{groupId}_{DateUtils.todayKey()}"
- Solo DailyLog key: "{challengeId}_{DateUtils.todayKey()}"

---

## Architectural Decisions (permanent rules)

### DateUtils.todayKey() — MANDATORY
Always use DateUtils.todayKey() for DailyLog date keys.
Never calculate inline with System.currentTimeMillis() / 86400000.
Search for "86400000" in codebase must return 0 results.

### Firestore sync — SetOptions.merge() always
Never use .set() without merge on dailyLogs documents.
Always .set(data, SetOptions.merge()) to avoid overwriting sibling fields.

### Huawei — never rely on onDestroy()
Any state that must survive a Huawei kill must be:
1. Written periodically (every 10s) during operation
2. Synced to Firestore (fire-and-forget)
3. Restored from Firestore on app start

### consciousOpens — atomic write on tap only
Never increment consciousOpens anywhere except the "Ja, öffnen" button tap.
Never use UsageStatsManager for open counting.

### Overlays — never quit from overlay
No "Challenge verlieren" or quit option in any overlay.
Single action only: "Stark bleiben 💪" or "Zurück".

### Universal Challenge Pattern
Soft Mode = base blocking logic + DailyLog sync
Hard Mode = Soft Mode + Stripe pre-auth/capture/refund
Group Challenge = Soft Mode + Stripe + Firestore participants sync
All three share identical overlay logic, DailyLog structure, and Fortschrittsbalken.

### Cloud Functions — onRequest only
Never use onCall() — breaks on Huawei (no Google Play Services).
Always use onRequest() with Bearer token auth.

### Overlay display — Handler only
Never use coroutines for showOverlay().
Always: Handler(Looper.getMainLooper()).post { overlayManager.showOverlay(...) }
