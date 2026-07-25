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
| Maximum participants | **Creator-chosen, 3–20** (wizard step 4, default 20). Hard floor 2, validated server-side in `createGroupChallenge`; `GroupParticipantLimits` is the client's single source of truth |
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
| Firestore participants sync | ❌ | ❌ | ✅ stats sub-collection (self-writable), array CF-only |
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

## Creation Flow (6-Step Wizard)

```
Step 1: App/Website selection (Apps tab | Websites tab — the Websites path skips step 2)
Step 2: Limit type
Step 3: Limit value + duration
Step 4: Buy-in + max players          ← both economic parameters, one card
Step 5: Start date
Step 6: Review & create → PaymentSheet → createGroupChallenge Cloud Function
    ↓
Cloud Function creates Firestore document + generates 6-char join code
    ↓
Creator sees detail screen with join code to share
```

Visible steps come from `visibleGroupSteps(state)` — step 4 is on BOTH paths and is
never skipped. Step 4 has no Solo/Hard counterpart, which is why group-only economic
inputs live there.

### Step 4 — participant cap + the honest best-case figure (2026-07-25)

`maxParticipants` was always present end to end (model, Room entity, Firestore doc,
enforced in `joinGroupChallenge` + `confirmGroupJoin`) but hardcoded to 20 in the
create ViewModel. The creator now picks it from a second `DetoxHorizontalPicker` in
the same card as the buy-in.

- **`GroupParticipantLimits`** (`domain/model`) is the single source of truth:
  `HARD_MIN = 2`, `PICKER_MIN = 3`, `MAX = 20`, `DEFAULT = 20`. It replaced the old
  `GROUP_MAX_PARTICIPANTS` constant. `HARD_MIN` is not cosmetic — `startGroupChallenge`
  cancels every PaymentIntent and refuses to start below 2 participants, so a cap of 1
  would produce a group that can never start. The ViewModel setter clamps defensively.
- **Server validation:** `createGroupChallenge` rejects an absent, non-integer, or
  out-of-`2..20` value with HTTP 400 + code `invalid_max_participants`, **before any
  Firestore read or write**. This is the only server-side gate on the field (the CF
  spreads `groupData` into the doc), and it is input validation only — no Stripe call,
  no capture, no change to the join capacity checks.
- **The `?? 5` / `?: 5` fallbacks are NOT the default** — they are a deliberate
  fail-safe for a document written before the field existed, in
  `GroupChallengeFirestoreService`, `joinGroupChallenge` and `confirmGroupJoin`. All
  three agree at 5 so client and server never disagree about capacity, and 5 errs
  small: an undersized group, never an oversold one. **Do not raise them to 20.**
- **The pot figure is honest.** It used to read `buyIn × 20` — a full lobby in which
  all 20 fail, which pays nobody. It is now `maxPossibleWinCents(stake, cap)`, which
  mirrors `completeGroupChallenge` exactly: own stake back at 80%, plus the failed
  participants' pot minus the 10% app fee, taken whole by a sole winner. €10 at cap 20
  → €179,00; €10 at cap 3 → €26,00. The label states the assumption ("Most you could
  win") and a note names the nobody-fails 100% case. `GroupParticipantLimitsTest`
  locks the maths to the CF's percentages.
- **Editability after creation is out of scope** and blocked by design: `firestore.rules`
  lists `maxParticipants` among the CF-only keys on update, so changing it would need a
  new Cloud Function. Lowering a cap would evict paid participants; if this is ever
  built, it should be raise-only or a separate "close joins" flag.

Every runtime `X/max` display (Friends hub, join screen, detail screen, challenge card
via `DailyStats`, the "challenge is full" message) already read `gc.maxParticipants`
and needed no change.

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
Cloud Function (reserve-then-pay, 2026-07-24):
    1. TRANSACTION: validate status == "waiting", no fresh startLockAt, dedupe,
       capacity = participants + live reservations of others < maxParticipants
       → write groupChallenges/{groupId}/joinReservations/{userId}
         {userId, displayName, reservedAt, expiresAt (+15 min), paymentIntentId: null}
    2. Create Stripe PaymentIntent (manual capture, amount = buyInCents)
    3. Record the PI id on the reservation (fails → PI cancelled, join fails — money-safe)
    4. Return clientSecret
    ↓
Android: Stripe Payment Sheet → user pays
    ↓
Calls confirmGroupJoin Cloud Function
    {groupId, userId, paymentIntentId}
    ↓
Cloud Function: TRANSACTION converts reservation → participants array entry
    (rejection AFTER authorization → PI cancelled FIRST, then
     {error, code: join_rejected_full|started|expired, holdReleased} returned)
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
- Validates payment (PI ownership metadata + status), then a `runTransaction` converts the
  join reservation into a participant: `arrayUnion` on `participants` AND `participantUserIds`
  + delete of the reservation doc, atomically. Converts ONLY a live reservation (doc present,
  no fresh `sweepingAt`); an expired-but-present reservation still converts if capacity allows.
- Reads an optional `deviceId` (ANDROID_ID) from the body and stores it on the participant object
  for anti-cheat shared-device detection (`GroupChallengeJoinViewModel` passes it). See `docs/10`.
- Returns `{success: true}` or `{success: true, alreadyJoined: true}`
- **Post-authorization rejection releases the hold:** if the challenge started/cancelled, filled,
  or the reservation is gone, the CF cancels the PI FIRST (best-effort, Stripe-before-Firestore;
  the reservation doc is deleted only after a CONFIRMED release), then responds
  `{error, code: "join_rejected_started"|"join_rejected_full"|"join_rejected_expired", holdReleased}`.
  The client maps the code to localized "hold released — no money taken" copy, terminal (no retry).
- Uses `onRequest` pattern (never `onCall`)

**Join-integrity machinery (2026-07-24):**
- **`joinReservations` sub-collection is CF-only** (explicit `allow read, write: if false`).
- **`sweepStaleJoinReservations`** (hourly `pubsub.schedule`): collection-group query on
  `expiresAt < now` (needs the `joinReservations.expiresAt` COLLECTION_GROUP index in
  `firestore.indexes.json`), claims each doc with a transactional `sweepingAt` stamp
  (claimPendingPayouts pattern, same 15-min TTL), cancels the PI, deletes the doc only after a
  confirmed release. A dead sweep never blocks joins — capacity counting ignores expired
  reservations; worst case a hold lives until Stripe's ~7-day auto-expiry.
- **`startLockAt`** (group doc, CF-only): `startGroupChallenge` transactionally stamps it and
  takes its participant snapshot from the SAME read before the capture pre-flight; both join CFs
  reject while the stamp is fresh (15-min TTL). Cleared on every start exit path; a stranded lock
  only ever blocks JOINING for the TTL — never start (creator retry re-stamps), leave, or settle.

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
    maxParticipants: Int             ← creator-chosen 3–20 (step 4); CF-validated 2..20 on create
    startDate: Long                  ← Unix ms, 0 if not started yet
    endDate: Long                    ← Unix ms
    completedAt: Long                ← Unix ms, 0 if not completed
    bonusEnabled: Boolean            ← RETIRED, always false; no CF ever read it (see below)
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

> ⚠️ **HISTORY:** the client used to mirror `opensToday`/`timeUsedMinutes` into the
> `participants` ARRAY via `arrayRemove`+`arrayUnion`. That path is GONE: the array is
> Cloud-Function-only in firestore.rules, and live leaderboard stats moved to the
> client-writable **stats sub-collection**. Never resurrect client writes to the array.

```
groupChallenges/{groupId}/participants/{uid}     ← stats sub-collection (doc id == uid)
    ── daily slot (the value FOR the day named by the matching stamp) ──
    opensToday: Int          ← mirrored from DailyLog consciousOpens
    timeUsedMinutes: Int     ← mirrored from DailyLog totalMinutes
    exceededToday: Bool      ← that day blew the limit (DailyEvaluationWorker)
    ── day stamps (Long, DateUtils.todayKey()) ──
    opensDateKey / timeDateKey / exceededDateKey
    dateKey                  ← legacy SHARED stamp, still written, read as fallback
    ── cumulative (sum over all days STRICTLY BEFORE the matching stamp) ──
    totalOpens / totalMinutes / exceededDays: Int
    updatedAt: Long
```

- Each user writes ONLY their own doc (`request.auth.uid == participantId`); the rules
  whitelist exactly these fields, so the doc can never carry money/identity fields.
- Readers (`GroupChallengeFirestoreService.observeParticipantStats`) merge the sub-collection
  over the parent array's frozen `opensToday`/`timeUsedMinutes` values; on listener errors they
  fall back to the array values instead of closing the flow.
- Dot notation on array indices (`participants.$index.field`) remains forbidden everywhere
  (invariant #21) — it causes partial snapshots where `participants` becomes a Map.

### THREE independent day stamps — not one

Three separate writers touch this doc: the **opens** path (`OverlayManager`, on a conscious
"Ja, öffnen"), the **time** path (`UsageTrackingService` polling) and the **exceeded** path
(`DailyEvaluationWorker`, once on a violation day). They must NEVER share one stamp:
whichever wrote first on a new day would roll the shared key to today, and the others would
then see "already today", skip folding their still-pending previous-day value into the
cumulative total, and **silently drop a day**. Each path owns its own
`{daily value, cumulative total, day stamp}` triple and rolls only that triple. This is a
*sequential* bug — locking cannot fix it.

A per-group in-process `Mutex` additionally serialises stat writes so two writes cannot both
observe a stale stamp and double-fold. That is insurance against double-COUNTING only; it is
not what makes the three paths independent.

### The cumulative invariant (load-bearing)

> A cumulative field is the sum over all days **strictly before** its day stamp. The daily
> slot holds the value **for** that stamped day.

Two readings follow, and they differ **deliberately**:

| Question | Formula |
|---|---|
| "What is TODAY's value?" | `daily`, gated on the stamp — **0 if stale** |
| "What is the WHOLE-CHALLENGE total?" | `total + daily`, **ungated** |

The ungated form is what makes the final day of a challenge recoverable forever without an
end-of-challenge flush — which is precisely why `completeGroupChallenge`, settlement and
every Stripe path stay **untouched** by the ranking feature. Gating the total would silently
drop the last day of every finished challenge from the results ranking.

### Reader-side daily reset (invariant #29)

Stat writes are **lazy**: the TIME path skips `totalMs == 0`, SESSIONS only writes on a
conscious open, and a clean day therefore produces **no write at all**. A stat doc routinely
survives midnight still carrying yesterday's numbers, so the reset **cannot** be done from
the write side. `withStats` is the single gate — every one of the ~13 read sites funnels
through it. Do **not** re-implement the reset at a call site: the Room mirror re-serialises
these values, so a downstream reader cannot detect staleness on its own (the mirror carries
a `statsDateKey` inside `participantsJson` for exactly this reason — no column, no migration).

This is not cosmetic. `UsageTrackingService` feeds the value into
`TrackedAppEventBus.groupSessionInfos`, which `AppDetectionAccessibilityService` consults to
decide whether the session limit is reached. Before the fix, a SESSIONS participant who hit
their limit yesterday stayed **blocked from 00:01 on a clean day**.

### Ranking metric — clean days, then total usage (DECISION, 2026-07-25)

`groupRankingComparator` in `domain/model/GroupRanking.kt` is the ONE ordering. Best first:

1. **Fewest exceeded days** (= most clean days; every participant has the same elapsed-day
   count because joining is fenced at start). Primary axis — it is what the challenge asks.
2. **Least total usage across the whole challenge** — `totalOpens` for SESSIONS,
   `totalMinutes` for TIME/TIME_BUDGET. Separates the crowd tied at zero exceeded days.
3. **Earliest `joinedAt`** — deterministic final tiebreak.

`groupRankingMetricComparator` is steps 1–2 only and decides **ties** (shared rank 1,1,3);
`joinedAt` orders the list but must never split a *displayed* rank.

All five ranking surfaces sort through this — results podium, detail leaderboard + rank map,
`OverlayManager.computeGroupRank`, `GetDailyStatsUseCase`, `FriendsHubScreen`. They each used
to hand-roll their own sort and had drifted apart; keeping the ordering in one place is the
point. **Do not re-implement it.**

> ⚠️ **These are SELF-REPORTED CLIENT NUMBERS.** Each participant writes only their own stat
> doc, and one who simply stops writing stops accumulating. Rules enforce monotonicity (a
> total may rise or stay, never fall) but that is the *only* integrity guarantee. Ranking is
> **cosmetic and must never gate money**: settlement classifies purely on `status`
> (failed vs not) in the CF-only parent array and splits the pot equally among winners.
> **Never wire a payout to any of these fields.**

---

## Participants Array Write Protocol (CF-only, transactional merge — invariant #28)

The `participants` array on the group doc carries identity + money + status
(`paymentIntentId`, `amountCents`, `status`, payout fields). Every mutation is a Cloud
Function running a **`runTransaction` that re-reads the doc and merges by `userId`** —
NEVER an in-memory full-array replacement (it silently erases concurrent writes: the
audit's worst case was a `confirmGroupJoin` `arrayUnion` erased mid-flight, leaving a
PAID user in `participantUserIds` but missing from `participants`, invisible to
settlement with a stranded hold). Map-entry `arrayRemove`+`arrayUnion` is also wrong
here: it needs a byte-exact prior value, and entry shapes are heterogeneous (creator
entries lack `deviceId`, settled entries carry payout fields) — a mismatch silently
no-ops the remove and the union then duplicates the participant.

Per function:
- **`confirmGroupJoin`** — the only array ADD: `arrayUnion` of a brand-new entry inside
  its convert transaction (correct use — a new map value cannot collide).
- **`cancelGroupChallenge` / `deleteGroupChallenge` / `expireGroupChallenge`** — shared
  `terminalizeWaitingGroupChallenge`: ① transaction stamps `startLockAt` (the existing
  join fence, invariant #27) and takes the participant snapshot from the SAME read;
  ② Stripe retrieve→cancel per participant OUTSIDE any transaction; ③ final transaction
  merges `status:"refunded"` by `userId` onto the FRESH array and flips a still-waiting
  doc to `cancelled`. A doc a concurrent start activated is left untouched and logged.
- **`leaveGroupChallenge`** — Stripe cancel first (unchanged), then a transaction filters
  the FRESH array by `userId`; the `<2 → cancelled` decision uses the fresh count.
  `participantUserIds` keeps `FieldValue.arrayRemove` (string array — exact-match safe).
- **`failParticipant`** — capture gate unchanged (Stripe outside transactions); the
  status write is a transactional merge onto the fresh entry. Pre-capture guards: 409
  `challenge_settled` on terminal docs, 409 `settlement_in_progress` while
  `settleLockAt` is fresh.
- **`completeGroupChallenge`** — stamps **`settleLockAt`** transactionally and takes the
  settlement snapshot from the SAME read; ALL payout math + Stripe calls run from that
  snapshot (amounts unchanged); the final write merges the computed results onto the
  fresh array by `userId` and derives `payoutIncomplete`/`payoutFailedUserIds` from the
  merged result. A premature `not_expired` call clears its own stamp.

**`settleLockAt` scope (same discipline as `startLockAt`):** a stranded stamp blocks
ONLY `failParticipant`, for at most 15 min (`SETTLE_LOCK_TTL_MS`) — never joining,
leaving, start, cancellation, or a settlement re-run after the TTL. CF-only in rules.

**FAIL-VS-SETTLE CONFLICT (accepted residual):** a self-fail that passed its fence check
before settlement stamped the lock can capture a stake the settlement snapshot already
classified as a winner's. No money is lost and the case favors the user (80% stake
refund despite quitting, i.e. they lose 20% instead of everything). Both CFs keep the
`failed` record, never overwrite the other side's result, and log
`FAIL-VS-SETTLE CONFLICT` (error level, with `groupId` + `userId`) — grep for that
string; manual correction is the resolution path. Per-participant claim tokens were
deliberately NOT added for this.

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

### Target 2 — Participant Stats Sub-collection (Group-specific)
groupChallenges/{groupId}/participants/{uid}      ← sub-collection DOC, not the array
    opensToday / timeUsedMinutes / exceededToday   ← daily slot
    opensDateKey / timeDateKey / exceededDateKey   ← per-path day stamps (+ legacy dateKey)
    totalOpens / totalMinutes / exceededDays       ← cumulative (excludes the stamped day)
    updatedAt: Long

Full field semantics, the three-stamp rule and the cumulative invariant: see
"Critical Sync Pattern: opensToday Updates" above.

SYNC RULE: When DailyLog is written → also merge-write the own stats sub-collection doc.
The participants ARRAY is Cloud-Function-only (see "Participants Array Write Protocol").
opensToday in the stats doc must always match consciousOpens in DailyLog.

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
opensToday++ → Firestore stats sub-collection doc (participants/{uid}, merge write)
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
- 3-column stats: **Gesamtpot €X** | **Teilnehmer X/max** | **Dein Gewinn €X** (green) — the
  max is the group's own `maxParticipants`, not a fixed 20

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
