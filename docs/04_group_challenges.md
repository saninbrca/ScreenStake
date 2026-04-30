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

### Participant Fails

```
DailyEvaluationWorker detects opensToday > limit for participant
    ↓
Calls failParticipant Cloud Function
    {groupId, userId}
    ↓
Cloud Function:
    1. stripe.paymentIntents.capture(participant.paymentIntentId)  ← money captured
    2. Calculate app fee: captured amount * 0.10
    3. Update participant.status = "failed" in Firestore
    4. Remove from active blocking
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

## Known Issues (Group Challenges)

1. **Group Challenge blocking unreliable:**
   `AppDetectionAccessibilityService` uses local Room cache for blocked packages. Sync from Firestore → Room for Group Challenge apps is not always immediate. Workaround: force sync on challenge start and on every app foreground.

2. **completeGroupChallenge not auto-triggering:**
   Manual Cloud Function call required when `endDate` passes. `DailyEvaluationWorker` must check `endDate < now` for all active Group Challenges.

3. **Stripe Connect for automatic payouts not implemented.**
   Prize money payout is fully manual. Founder must check admin dashboard and transfer manually.
