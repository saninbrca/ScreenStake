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

## 2026-05-15

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
