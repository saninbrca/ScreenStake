# 04 — Group Challenges
> **Scope:** Group Challenge creation, minimum requirements, Firestore data structure, sync patterns, Taunt feature, winner payout flow.
> **When to load:** Any work on Group Challenges, Friends tab, leaderboard, `GroupChallengeFirestoreService`, `GroupChallengeDao`, or payout system.
> _Last verified: 2026-07-19 (commit 4b54701)_

> ⚠️ **Disabled at launch:** Group Challenges ship **OFF** for launch via the remote flag
> `config/app.groupChallengeEnabled = false` (gates NEW group creation/entry only; the code fallback
> stays fail-open `true`). The reason is that groups currently settle **only device-side** — there is
> no server-scheduled settlement backstop like solo Hard Mode's reconciliation net, so an un-opened
> participant device after `endDate` can strand winnings/stakes. Re-enable only after a server-side
> group settlement path lands. See `docs/13` (flag) and `launch-investigation.md` item 3.

---

## Group Challenge Rules

| Rule | Value |
|------|-------|
| Minimum buy-in | **€10** per participant (default — remotely configurable via `AppConfig.groupMinBuyIn`) |
| Maximum buy-in | **€50** per participant (default — remotely configurable via `AppConfig.groupMaxBuyIn`) |
| Minimum participants to start | **2** |
| Minimum duration | **3 days** |
| Maximum duration | **30 days** |
| Maximum participants | **20** (fixed) |
| Who can start | Creator (manual) — OR automatic once an optional scheduled start date passes |
| Start date | Optional (`startDateEnabled`/`startDateMs` in the create wizard); when set, `GroupChallengeAutoStartWorker` (24h periodic, scheduled in `DetoxApplication`) starts due WAITING groups |
| Auto-cancel condition | < 2 participants when creator tries to start → refund all |
| App fee | **10%** of failed participants' money |
| Winner payout | Manual SEPA transfer by founder |
| Completed challenges in Friends tab | Hidden after **3 days** |
| Auto-fail on limit reached | **❌ Never** — for any limit type |
| Stripe capture trigger | **Manual "Aufgeben" only** in Detail screen |
| Limit reached behavior | **SessionLimitReachedOverlay** (all limit types) — app stays blocked |
| endDate success | **Stripe refund** for all participants still "active" |

---

## Group Challenge — Unified Architecture

Group Challenge is NOT a separate blocking system.
It is Soft Mode + Stripe + Firestore participants sync.

| Component | Soft Mode | Hard Mode | Group Challenge |
|-----------|-----------|-----------|-----------------|
| AccessibilityService blocking | ✅ identical | ✅ identical | ✅ identical |
| Overlay logic | ✅ identical | ✅ identical | ✅ identical |
| SessionIntentionOverlay | ✅ identical | ✅ identical | ✅ identical |
| SessionLimitReachedOverlay | ✅ identical | ✅ identical | ✅ identical |
| DailyLog Room write | ✅ identical | ✅ identical | ✅ identical |
| Firestore dailyLogs sync | ✅ identical | ✅ identical | ✅ identical |
| DateUtils.todayKey() | ✅ identical | ✅ identical | ✅ identical |
| Fortschrittsbalken | ✅ identical | ✅ identical | ✅ identical |
| Stripe per participant | ❌ | ❌ | ✅ separate PaymentIntent |
| Stripe capture on fail | ❌ | ✅ | ✅ + 10% app fee |
| Stripe refund on success | ❌ | ✅ | ✅ winners get losers' money |
| Firestore participants sync | ❌ | ❌ | ✅ arrayRemove+arrayUnion |
| opensToday in participants | ❌ | ❌ | ✅ mirrored from DailyLog |
| Leaderboard | ❌ | ❌ | ✅ real-time Firestore listener |
| Taunt feature | ❌ | ❌ | ✅ |

RULE: Any fix applied to Soft Mode blocking/overlay/DailyLog logic
MUST be verified for Group Challenge as well.
Never create separate overlay implementations for Group vs Solo challenges.

---

## Feature Flag — `groupChallengeEnabled`

Group Challenge **creation** is gated by the remote `AppConfig.groupChallengeEnabled` flag
(`config/app`, default `true`). When `false`, the "Erstellen" button in `FriendsHubScreen` is
disabled and an "unavailable" note is shown (`FriendsHubViewModel.groupChallengeEnabled`). Active
challenges are **never** affected — the flag gates new creation only. Fail-open: a missing config or
read error leaves the feature enabled. See `docs/13_remote_config_and_flags.md`.

---

## Creation Flow (5-Step Wizard)

```
Step 1: Mode (Group auto-selected)
Step 2: App/Website selection
Step 3: Limit type + value
Step 4: Duration + Buy-in
Step 5: Review & Start → calls createGroupChallenge Cloud Function
    ↓
Cloud Function creates Firestore document + generates 6-char join code
    ↓
Creator sees detail screen with join code to share
```

---

## Creation Flow — CORRECT ORDER

```
Step 1–5: User fills wizard
Step 6: Review screen
User taps "Erstellen & Bezahlen":
    ↓
1. Call createPaymentIntent (buyInCents) → get clientSecret
   (Challenge NOT created yet)
    ↓
2. Show Stripe PaymentSheet
    ↓
PaymentSheetResult.Completed:
    → Call createGroupChallenge Cloud Function (with paymentIntentId)
    → Navigate to GroupChallengeDetailScreen
PaymentSheetResult.Canceled:
    → Stay on review screen, show "Zahlung abgebrochen"
    → No challenge created in Firestore
PaymentSheetResult.Failed:
    → Stay on review screen, show error, allow retry
```

**CRITICAL:** `createGroupChallenge` ONLY called after `PaymentSheetResult.Completed`.
Never create challenge document before payment is confirmed.

---

## Join Flow

```
Friend opens FriendsHubScreen → "Challenge beitreten" → enters 6-char code
    ↓
App looks up groupChallenges where code == input
    ↓
Calls joinGroupChallenge Cloud Function
    {groupId, userId, displayName}
    ↓
Cloud Function:
    1. Validate: status == "waiting", participants < maxParticipants
    2. Create Stripe PaymentIntent (manual capture, amount = buyInCents)
    3. Return clientSecret
    ↓
Android: Stripe Payment Sheet → user pays
    ↓
Calls confirmGroupJoin Cloud Function
    {groupId, userId, paymentIntentId}
    ↓
Cloud Function: adds participant to participants array in Firestore
    ↓
Participant appears in leaderboard in real-time
```

---

## Join Flow — CORRECT ORDER (with confirmGroupJoin)

```
Code eingeben → Vorschau erscheint
User taps "Beitreten":
    ↓
1. Button → CircularProgressIndicator (preview stays open)
2. Call createPaymentIntent (buyInCents) → clientSecret
    ↓
3. PaymentSheet opens automatically
    ↓
PaymentSheetResult.Completed:
    → Call confirmGroupJoin Cloud Function {groupId, paymentIntentId}
    → 409 "Already joined" = treat as SUCCESS
    → Navigate to GroupChallengeDetailScreen
PaymentSheetResult.Canceled:
    → Preview stays visible
    → Show "Zahlung abgebrochen"
PaymentSheetResult.Failed:
    → Show error, allow retry
```

**confirmGroupJoin Cloud Function:**
- Validates payment
- Adds participant to `participants` array AND `participantUserIds`
- Reads an optional `deviceId` (ANDROID_ID) from the body and stores it on the participant object
  for anti-cheat shared-device detection (`GroupChallengeJoinViewModel` passes it). See `docs/10`.
- Returns `{success: true}` or `{success: true, alreadyJoined: true}`
- Uses `onRequest` pattern (never `onCall`)

---

## Start Flow

```
Creator opens GroupChallengeDetailScreen → taps "Challenge starten"
    ↓
Check: participants.length >= 2 (else: show error, offer to cancel + refund all)
    ↓
Calls startGroupChallenge Cloud Function
    {groupId}
    ↓
Cloud Function:
    status = "active"
    startDate = now
    endDate = endOfDayMillis(now, durationDays)   ← 23:59:59.999 of the last day (NOT now + N×86400000)
    ↓
All participants' AccessibilityService starts blocking selected apps
```

**endDate calculation (May 2026 — "Last Day Loophole" fix):** `startGroupChallenge` previously
computed `endDate = now + durationDays * 86_400_000`, which ended mid-day on the final day — after
that time the app stopped blocking but the day wasn't over. Both client (`DateUtils.endOfDayMillis`)
and the Cloud Function (`endOfDayMillis(startMs, durationDays)` helper added to `functions/src/index.ts`)
now land `endDate` on **23:59:59.999 of the last day**. Note: the Cloud Function runs in UTC, so its
end-of-day is UTC-based vs. the client's device-local timezone — both close the loophole, but are
not bit-identical. **Never compute `endDate` as `startTime + N × 86_400_000`.** See
`docs/00_changelog.md` → "Last Day Loophole".

---

## Firestore Data Structure

```
groupChallenges/{groupId}/
    code: String                     ← 6-char join code
    creatorUserId: String
    creatorDisplayName: String
    appPackageNames: String          ← comma-separated, e.g. "com.instagram.android,com.tiktok.android"
    blockedDomains: String?          ← comma-separated, nullable
    limitType: String                ← "sessions" | "time" | "budget"
    limitValueMinutes: Int
    limitValueSessions: Int
    sessionDurationMinutes: Int
    durationDays: Int
    buyInCents: Int                  ← minimum 1000 (€10)
    maxParticipants: Int             ← fixed at 20
    startDate: Long                  ← Unix ms, 0 if not started yet
    endDate: Long                    ← Unix ms
    completedAt: Long                ← Unix ms, 0 if not completed
    bonusEnabled: Boolean
    authorizationExpiresAt: Timestamp? ← 5 days after creation
    status: String                   ← "waiting" | "active" | "completed" | "cancelled"
    participants: Array<Participant>
    participantUserIds: Array<String> ← for Firestore query filtering

Participant object:
    userId: String
    displayName: String
    paymentIntentId: String
    amountCents: Int
    status: String                   ← "active" | "failed" | "completed"
    opensToday: Int
    timeUsedMinutes: Int
    joinedAt: Long
    deviceId: String?                ← ANDROID_ID, added on join for anti-cheat (docs/10)

payoutRequests/{requestId}/
    userId: String
    displayName: String
    iban: String
    payoutName: String
    amountCents: Int
    groupId: String
    status: String                   ← "pending" | "paid" | "rejected"
    createdAt: Long
    paidAt: Long?
```

---

## Critical Sync Pattern: opensToday Updates

```kotlin
// ⚠️ ALWAYS use arrayRemove + arrayUnion pattern for participant updates
// NEVER use dot notation (.update("participants.$index.field")) → causes partial snapshots

db.collection("groupChallenges").document(groupId)
    .update(
        "participants", FieldValue.arrayRemove(oldParticipant.toMap()),
        "participants", FieldValue.arrayUnion(newParticipant.toMap())
    )
```

**Why:** Dot notation on array indices causes Firestore to return partial snapshots where the `participants` field is a Map instead of a List. This breaks the entire leaderboard. The `arrayRemove + arrayUnion` pattern is the only safe approach.

---

## Group Challenge DailyLog Sync Pattern

Group Challenge uses TWO parallel sync targets:

### Target 1 — DailyLog (identical to Solo challenges)
users/{userId}/dailyLogs/{challengeId}_{DateUtils.todayKey()}
    consciousOpens: Int
    totalMinutes: Int        (if TIME_LIMIT — minutes, NOT ms)
    budgetUsedMs: Long       (if DAILY_BUDGET)
    budgetRemainingMs: Long  (if DAILY_BUDGET)
    updatedAt: Long
    (SetOptions.merge() always)

### Target 2 — Participants Array (Group-specific)
groupChallenges/{groupId}/participants[]/
    opensToday: Int          ← mirrored from DailyLog consciousOpens
    timeUsedMinutes: Int     ← participants-array field, mirrored from DailyLog totalMinutes

SYNC RULE: When DailyLog is written → also update participants array.
Use arrayRemove + arrayUnion pattern (NEVER dot notation).
opensToday in participants must always match consciousOpens in DailyLog.

### opensToday Sync — FIXED
OverlayManager now reads `opensToday` from `TrackedAppEventBus.groupSessionInfos` (not stale Room DAO).
Room upsert runs unconditionally on every ACTIVE Firestore snapshot (not only on status change).
opensToday in overlay and leaderboard are now in sync.

---

## Participants Parsing (handles both formats)

```kotlin
// Firestore can return participants as List OR Map (partial snapshot bug)
val participants = when (val raw = doc.get("participants")) {
    is List<*> -> (raw as List<Map<String, Any>>).map { it.toParticipant() }
    is Map<*, *> -> raw.values.mapNotNull { (it as? Map<String, Any>)?.toParticipant() }
    else -> emptyList()
}
```

---

## Leaderboard Logic

- Real-time Firestore listener on `groupChallenges/{groupId}`
- Sorted by: `opensToday ASC` (fewer opens = better rank)
- Secondary sort: `timeUsedMinutes ASC`
- Failed participants shown at bottom with strikethrough
- User's own row highlighted
- Rank displayed on Dashboard group card

### Shared Rank (Standard Competition Ranking)

Equal `opensToday` = shared rank. Pattern: 1, 1, 3 (not 1, 2, 3).

- `rankMap` (userId → rank) pre-calculated before leaderboard render.
- Failed participants get rank 0, displayed as "—".
- Rank colors: gold (#FFD700) for rank 1, silver (#C0C0C0) for rank 2,
  bronze (#CD7F32) for rank 3, #8E8E93 for rank 4+.
- `OverlayManager.computeGroupRank()` uses shared ranking — finds the index of the first
  participant with the same `opensToday` as the user. Failed participants excluded from rank.
- Context header (`"👥 Platz #X von Y"`) reflects correct shared rank.

---

## Taunt Feature

**"👀 Nerv ihn!" button** in leaderboard — users can taunt each other.

```
User A taps "Nerv ihn!" next to User B's name
    ↓
Firestore write: groupChallenges/{groupId}/taunts/{timestamp}
    {fromUserId, toUserId, fromDisplayName}
    ↓
User B's device (if they currently have a blocked app open):
    AppDetectionAccessibilityService listens to Firestore taunts collection
    ↓
TauntOverlay appears at top of screen
    "👀 [User A] schaut zu!"
    Auto-dismisses after 4 seconds
```

- Taunt only shows if target user currently has a blocked app open
- No rate limiting currently implemented (future: max 1 taunt per minute per user)

---

## Fail & Complete Logic (Group)

### DECISION — Group Challenge never auto-fails for any limit type

All limit types (SESSION, TIME, BUDGET): limit reached = **SessionLimitReachedOverlay only**.
App stays blocked. Participant status remains "active". No Stripe capture.
Stripe capture ONLY on manual "Aufgeben" in Detail screen.

```
Limit reached (any type) — in OverlayManager:
    Show SessionLimitReachedOverlay ("Stark bleiben 💪")
    NO failGroupParticipant call
    NO Stripe capture
    NO status change

Limit reached (any type) — in DailyEvaluationWorker:
    Write DailyLog with limitExceeded=true, moneyLostCents=0  ← statistics only
    NO failGroupParticipant call
    NO Stripe capture
    NO status change
```

### Participant Quits Manually ("Aufgeben")

```
User opens GroupChallengeDetailScreen → taps "Aufgeben" button
    ↓
Confirmation dialog: "Willst du wirklich aufgeben? €X werden eingezogen."
    ↓
User confirms → ViewModel.quitChallenge()
    ↓
Calls failParticipant Cloud Function
    {groupId, userId}
    ↓
Cloud Function:
    1. stripe.paymentIntents.capture(participant.paymentIntentId)  ← money captured FIRST
    2. Calculate app fee: captured amount * 0.10
    3. Update participant.status = "failed" in Firestore
    ↓
Navigate to FriendsHubScreen
    ↓
Failed participant stays visible in leaderboard (greyed out / strikethrough)
```

### Permission Violation Capture (Group Participants)

Group Challenge participants are also subject to server-side permission violation capture.

- If a participant loses Accessibility or Overlay permission while the challenge is active,
  `permissionLostAt` is written to `users/{uid}/permissionStatus/current` by `PermissionCheckWorker`.
- `checkPermissionViolations` CF queries all active Hard Mode + Group Challenge participants.
- After 24h without permission restore: Stripe capture triggered server-side for that participant.
- `failReason: "permission_violation"` written to challenge document.
- UsageStats backup path also applies: usage > 1 min → `usageViolationDetectedAt` → capture after 1h.

---

### Challenge Completes

```
Trigger: endDate passed (checked in DailyEvaluationWorker + on app foreground)
    ↓
Calls completeGroupChallenge Cloud Function
    {groupId}
    ↓
Cloud Function:
    1. Collect all failed participants' captured amounts
    2. Subtract 10% app fee
    3. Divide remainder equally among surviving (non-failed) participants
    4. For each winner: stripe.paymentIntents.cancel (refund their own stake)
    5. Create payoutRequests documents for each winner's prize money
    6. status = "completed", completedAt = now
    ↓
Winners see "Du hast gewonnen! 🎉" screen
Winners prompted to submit IBAN in Profile for payout
```

✅ **RESOLVED:** `completeGroupChallenge` is called automatically by DailyEvaluationWorker when endDate passes.

---

## Winner Payout Flow (Manual)

→ Full payout flow: see docs/09_payout_and_fees.md

```
Winner opens ProfileScreen → taps "Gewinn einfordern"
    ↓
IBAN input form → stored in:
    Firestore: users/{userId}/payoutIban + payoutName
    Firestore: payoutRequests/{requestId}/ (status: "pending")
    ↓
Founder opens admin/index.html
    ↓
Admin dashboard shows all pending payouts with IBAN + amount
    ↓
Founder manually transfers via bank (SEPA)
    ↓
Founder marks payout as "paid" in admin dashboard
    ↓
payoutRequests/{requestId}/status = "paid"
```

**Future plan:** Stripe Express for automatic payouts — currently blocked by Austria individual account limitation.

---

## Friends Tab / FriendsHubScreen

- Shows all Group Challenges the user is part of (active + waiting)
- Completed challenges hidden after 3 days
- "Neue Challenge" → starts GroupChallengeCreateScreen wizard
- "Beitreten" → code input → GroupChallengeJoinScreen
- Real-time updates via Firestore `participantUserIds` array queries

---

## Group Challenge Results Screen

Shown once after a Group Challenge ends.
Guard: `SharedPreferences` key `"podium_shown_{groupId}"` — set to `true` after first display, never shown again.

### Layout
- Background: #0A0A0A (dark fullscreen)
- Konfetti rain animation on entry (top 3 winners only)
- Lottie trophy animation for Platz 1

### Podium
Center column (Platz 1 / tallest), left column (Platz 2), right column (Platz 3).
Each column rises sequentially with enter animation.

Podium column colors:
  Platz 1: #FFD700 (gold)
  Platz 2: #C0C0C0 (silver)
  Platz 3: #CD7F32 (bronze)

### User Result Card
Shows win/loss outcome + payout info for the current user.
"Weiter" button → navigates to GroupChallengeDetailScreen.

### Sequential Reveal
Columns animate in order: Platz 3 → Platz 2 → Platz 1 (lowest to highest).

### Failed Participants
Shown below the podium, greyed out, no animation.

---

## Removed Dead Code

- `captureAndLock` — removed. Was an unused code path that attempted to combine
  Stripe capture with a lockout overlay in a single call. Never triggered in production.
- `handleGroupChallengeFail` — removed. Group Challenge never auto-fails (see
  "DECISION — Group Challenge never auto-fails for any limit type" above).
  The only fail path is manual "Aufgeben" via `failParticipant` Cloud Function.

---

## Leave / Delete Flow

Two distinct Cloud Functions (both use `onRequest`):

### leaveGroupChallenge
Called when a **non-creator** participant quits a **waiting** challenge before it starts.
- Full Stripe PaymentIntent cancel (no money captured, challenge not yet active)
- Participant removed from `participants` array + `participantUserIds`
- If last participant leaves → challenge stays in `waiting`, creator can cancel

### deleteGroupChallenge
Called by the **creator** to cancel a challenge in `waiting` status.
- Stripe PaymentIntent cancel for ALL participants (full refund)
- Challenge status → `"cancelled"`
- All participants notified via local notification

**Rule:** A `active` challenge can NOT be deleted — only individual participants can quit via "Aufgeben".

---

## 5-Day Authorization Window (self-imposed buffer — NOT Stripe's limit)

Stripe PaymentIntents created with `capture_method: "manual"` hold their authorization for
**~7 days** (Stripe's real limit); after that the authorization expires and capture becomes impossible.
Finite does **not** rely on that full window for groups. `createGroupChallenge` sets a
**conservative 5-day buffer** — `authorizationExpiresAt = now + 5 days` (`functions/src/index.ts`,
`Date.now() + 5 * MILLIS_PER_DAY`) — which sits *before* Stripe's ~7-day expiry, leaving margin for
capture/retry. The **5 days is our own enforced cap, not Stripe's limit.**

**Rule for challenge start timing:**
- `joinGroupChallenge` CF creates the PaymentIntent (Stripe's ~7-day authorization clock starts)
- Challenge MUST be started within our **5-day buffer** of the last participant joining
- If `endDate - startDate` would push any capture beyond the 5-day buffer → creator must be warned

✅ **Automatic enforcement:** `expireGroupChallenge` CF runs via DailyEvaluationWorker and enforces the
5-day buffer (`authorizationExpiresAt`). After the buffer elapses without start → PaymentIntents
cancelled automatically — well before Stripe's ~7-day authorization actually expires.

---

## Known Issues (Group Challenges)

1. **Group Challenge blocking unreliable:**
   `AppDetectionAccessibilityService` uses local Room cache for blocked packages. Sync from Firestore → Room for Group Challenge apps is not always immediate. Workaround: force sync on challenge start and on every app foreground.

2. ✅ **completeGroupChallenge — RESOLVED:** Called automatically by DailyEvaluationWorker when endDate passes.

3. **Stripe Connect payouts:** IBAN collection + Connected Account creation is implemented. Prize transfers are currently initiated manually by the founder via Stripe Dashboard. Full automatic transfer via API is planned post-launch.

---

## Fortschrittsbalken (Group Challenge)

Identical logic to Solo challenges — read from Room DailyLog always.

SESSION_LIMIT:  progress = consciousOpens / limitValueSessions
TIME_LIMIT:     progress = totalMinutes / limitValueMinutes
DAILY_BUDGET:   progress = budgetUsedMs / (dailyBudgetMinutes * 60000)

Display in Dashboard group card:
- Progress bar (same Composable as Solo card)
- User's current rank in leaderboard
- Pot amount (total buy-ins)
- Participants count

Display in Group Detail screen:
- Full leaderboard with progress per participant
- Each participant's progress bar based on their opensToday / limit
- CRITICAL: Read participant data from Firestore real-time listener
- CRITICAL: Read own progress from Room DailyLog (DateUtils.todayKey())
- Never mix sources — own data from Room, others' data from Firestore

---

## Group Challenge Limit Type Flows

### SESSION_LIMIT (Group)
User opens blocked app
↓
OverlayManager reads consciousOpens from Room DailyLog
↓
consciousOpens < limit → SessionIntentionOverlay (identical to Solo)
On confirm:
consciousOpens++ → Room (immediate)
consciousOpens++ → Firestore dailyLogs (fire-and-forget)
opensToday++ → Firestore participants array (arrayRemove+arrayUnion)
↓
consciousOpens >= limit → SessionLimitReachedOverlay ("Stark bleiben 💪" only)
App stays blocked. Participant stays active. NO auto-fail.
Manual "Aufgeben" in Detail screen is the only way to quit.

**SESSION_LIMIT Room fallback fix:** `opensToday` now has a `containsKey` guard + Room fallback
that matches Solo behavior. Previously `opensToday` showed 5/5 or 6/5 instead of 0 on challenge start.

### TIME_LIMIT (Group)
Same as SESSION_LIMIT flow but:
totalMinutes tracked via UsageTrackingService — ONLY during active app usage.
Timer pauses when overlay is shown. Timer stops when user leaves the app.
timeUsedMinutes (participants array) mirrored from DailyLog totalMinutes every 10s
Limit reached: totalMinutes >= limitValueMinutes → **LimitExceededOverlay** (same as Solo)
NO auto-fail. Manual "Aufgeben" only.

**TIME_LIMIT session persistence:** Session end time stored in SharedPreferences as
`"session_end_time_{packageName}"`. Brief app switches (Recents, notifications) no longer
reset the session — the end time survives and the overlay is not re-shown if the session
is still valid on return.

**TIME_LIMIT timer fix:** Timer previously incremented `totalMinutes` during overlay
display and when the user was not in the app. Both cases are now correctly excluded.

### DAILY_BUDGET (Group)
Same as Solo DAILY_BUDGET flow but:
budgetUsedMs tracked via UsageTrackingService
budgetUsedMs written to Room + Firestore dailyLogs every 10s
budgetUsedMinutes mirrored to participants array every 10s
Limit reached: budgetUsedMs >= dailyBudgetMinutes * 60000 → BudgetSelectionOverlay
  → if remaining > 0: BudgetSelectionOverlay (horizontal picker, matches Solo behavior)
  → if remaining <= 0: SessionLimitReachedOverlay
NO auto-fail. Manual "Aufgeben" only.

**DAILY_BUDGET context header fix:** Context header now shows `"👥 Platz #X von Y"` (same as
every other Group Challenge overlay). Previously hardcoded `"⏱ 0 min"`.

**DAILY_BUDGET BudgetSelectionOverlay fix:** Now shows `BudgetSelectionOverlay` with
`DetoxHorizontalPicker` + 5-second countdown, matching Solo behavior. Previously the
exhausted path jumped straight to `SessionLimitReachedOverlay`.

---

## Payout Fees (Group Challenge)

**Case A — At least one participant failed:**
- Winner stake refund: `floor(amountCents * 0.80)` — 20% app fee on own stake
- Prize pool: `(totalCaptured - floor(total * 0.10)) / winnersCount` — 10% app fee on losers' pot

**Case B — Nobody failed (all complete):**
- Full 100% refund for all — no fee
- `stripe.paymentIntents.cancel()` for each participant (PI still in `requires_capture`)

**Rule:** Always use `Math.floor` — never round up (avoid overpayment).

---

## Detail Screen Design (Group Challenge)

→ For UI/design specs see docs/08_ui_design_system.md

### Card 1 — Header
- `"● LIVE"` badge (green) or `"⏳ WARTET"` (gray) + days remaining
- App name: 22sp bold
- Subtitle: limit description
- 3-column stats: **Gesamtpot €X** | **Teilnehmer X/20** | **Dein Gewinn €X** (green)

### Leaderboard section
Single white card, rows with 0.5px dividers.
Each row: Rank (gold/silver/bronze) | Avatar | Name | "Du" badge | Sub-label | Stat
- Own row: `#F9FFF9` background
- Failed: name strikethrough, `#C7C7CC` color
- **"Nerv ihn!" button: TEMPORARILY REMOVED** — pending re-implementation

### Session section
"Deine Session heute" title.
Verbraucht / Noch verfügbar / existing progress bar.

### "Challenge aufgeben"
Text only, 14sp, `#FF3B30`.
Confirmation: "Du verlierst €X (80% zurück). Wirklich aufgeben?"

---

## Friends Tab Query

Uses real-time Firestore listener (not one-shot fetch):

```kotlin
firestore.collection("groupChallenges")
    .whereArrayContains("participantUserIds", currentUserId)
    .whereIn("status", listOf("waiting", "active"))
    .addSnapshotListener { snapshot, error -> ... }
```

- Shows BOTH `"waiting"` and `"active"` challenges.
- Waiting challenge card: `"⏳ Wartet auf Start von [creatorName]"` badge.
- Auto-updates when status changes from `waiting` → `active`.
