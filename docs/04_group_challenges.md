# 04 — Group Challenges
> **Scope:** Group Challenge creation, minimum requirements, Firestore data structure, sync patterns, Taunt feature, winner payout flow.
> **When to load:** Any work on Group Challenges, Friends tab, leaderboard, `GroupChallengeFirestoreService`, `GroupChallengeDao`, or payout system.

---

## Group Challenge Rules

| Rule | Value |
|------|-------|
| Minimum buy-in | **€10** per participant |
| Minimum participants to start | **2** |
| Minimum duration | **3 days** |
| Maximum participants | **20** (fixed) |
| Who can start | Creator only (manual start, no auto-start) |
| Start date | Optional — creator starts manually |
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

## Creation Flow (6-Step Wizard)

```
Step 1: Select app(s) to block
Step 2: Select limit type (sessions / time / budget)
Step 3: Set limit value
Step 4: Set duration (days) — minimum 3
Step 5: Set buy-in amount — minimum €10
Step 6: Review + confirm → calls createGroupChallenge Cloud Function
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
    endDate = now + durationDays * 86400000
    ↓
All participants' AccessibilityService starts blocking selected apps
```

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
    timeUsedMs: Long         (if TIME_LIMIT)
    budgetUsedMs: Long       (if DAILY_BUDGET)
    budgetRemainingMs: Long  (if DAILY_BUDGET)
    updatedAt: Long
    (SetOptions.merge() always)

### Target 2 — Participants Array (Group-specific)
groupChallenges/{groupId}/participants[]/
    opensToday: Int          ← mirrored from DailyLog consciousOpens
    timeUsedMinutes: Int     ← mirrored from DailyLog timeUsedMs / 60000

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

⚠️ **Known Issue:** `completeGroupChallenge` is not triggering automatically when `endDate` passes. Must be called from `DailyEvaluationWorker` AND checked on app foreground resume.

---

## Winner Payout Flow (Manual)

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

### Failed Participants
Shown below the podium, greyed out, no animation.

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

## 5-Day Stripe Authorization Window

Stripe PaymentIntents created with `capture_method: "manual"` are only authorized for **5 days**.
After 5 days the authorization expires and capture becomes impossible.

**Rule for challenge start timing:**
- `joinGroupChallenge` CF creates the PaymentIntent (authorization clock starts)
- Challenge MUST be started within 5 days of the last participant joining
- If `endDate - startDate` would push any capture beyond 5 days → creator must be warned

**Current workaround:** No automatic expiry enforcement. Founder monitors admin dashboard.
**Future:** CF should check `created + 5d > now` before attempting capture; if expired → mark participant failed without capture, notify founder.

---

## Known Issues (Group Challenges)

1. **Group Challenge blocking unreliable:**
   `AppDetectionAccessibilityService` uses local Room cache for blocked packages. Sync from Firestore → Room for Group Challenge apps is not always immediate. Workaround: force sync on challenge start and on every app foreground.

2. **completeGroupChallenge not auto-triggering:**
   Manual Cloud Function call required when `endDate` passes. `DailyEvaluationWorker` must check `endDate < now` for all active Group Challenges.

3. **Stripe Connect for automatic payouts not implemented.**
   Prize money payout is fully manual. Founder must check admin dashboard and transfer manually.

---

## Fortschrittsbalken (Group Challenge)

Identical logic to Solo challenges — read from Room DailyLog always.

SESSION_LIMIT:  progress = consciousOpens / limitValueSessions
TIME_LIMIT:     progress = timeUsedMs / (limitValueMinutes * 60000)
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

### TIME_LIMIT (Group)
Same as SESSION_LIMIT flow but:
timeUsedMs tracked via UsageTrackingService
timeUsedMinutes mirrored to participants array every 10s
Limit reached: timeUsedMs >= limitValueMinutes * 60000 → SessionLimitReachedOverlay
NO auto-fail. Manual "Aufgeben" only.

### DAILY_BUDGET (Group)
Same as Solo DAILY_BUDGET flow but:
budgetUsedMs tracked via UsageTrackingService
budgetUsedMs written to Room + Firestore dailyLogs every 10s
budgetUsedMinutes mirrored to participants array every 10s
Limit reached: budgetUsedMs >= dailyBudgetMinutes * 60000 → SessionLimitReachedOverlay
NO auto-fail. Manual "Aufgeben" only.

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
