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
