# 03 — Hard Mode & Stripe
> **Scope:** Hard Mode rules, Stripe payment flow (pre-auth → capture on fail → refund on success), app lockout during Hard Mode, Emergency Unlock, device binding.
> **When to load:** Any work on Hard Mode, Stripe integration, `PaymentRepository`, Cloud Functions for payments, or the Hard Mode lockout overlay.

---

## Hard Mode Rules

| Rule | Value |
|------|-------|
| Minimum duration | **14 days** — enforced in `CreateChallengeUseCase`, cannot be bypassed |
| Money | Real money via Stripe |
| End date | Mandatory (minimum 14 days from start) |
| Fail condition | Daily limit exceeded → Stripe capture → marked FAILED |
| Success condition | All days completed without exceeding limit → Stripe refund → marked COMPLETED |
| Logout | **BLOCKED** while Hard Mode challenge is active (device binding) |
| App deletion | User is warned that deleting the app = challenge fails |

---

## Hard Mode vs Soft Mode — Unified Architecture

Hard Mode is NOT a separate system. It is Soft Mode with Stripe added on top.

| Component | Soft Mode | Hard Mode |
|-----------|-----------|-----------|
| AccessibilityService blocking | ✅ identical | ✅ identical |
| Overlay logic | ✅ identical | ✅ identical |
| SessionIntentionOverlay | ✅ identical | ✅ identical |
| SessionLimitReachedOverlay | ✅ identical | ✅ identical |
| DailyLog Room write | ✅ identical | ✅ identical |
| Firestore dailyLogs sync | ✅ identical | ✅ identical |
| DateUtils.todayKey() | ✅ identical | ✅ identical |
| Fortschrittsbalken | ✅ identical | ✅ identical |
| Stripe pre-auth | ❌ | ✅ on challenge start |
| Stripe capture | ❌ | ✅ on fail (FIRST before Room write) |
| Stripe refund | ❌ | ✅ on success (FIRST before Room write) |
| Logout blocking | ❌ | ✅ device binding |
| Minimum duration | none | 14 days |

RULE: Any fix applied to Soft Mode blocking/overlay/DailyLog logic
MUST be applied to Hard Mode as well. They share the same code paths.
Never create separate implementations for Hard vs Soft Mode overlays.

---

## Stripe Configuration

```
Mode:         Test mode (sk_test_...)
Key location: functions/.env ONLY — NEVER in git, NEVER in Android code
Android SDK:  Stripe Android SDK (UI + payment sheet)
Backend SDK:  Stripe Node.js SDK inside Cloud Functions
```

### Stripe Secrets Location
```
functions/.env:
  STRIPE_SECRET_KEY=sk_test_...
  STRIPE_PUBLISHABLE_KEY=pk_test_...
```

---

## Payment Flow — Step by Step

### Phase 1: Challenge Start (Pre-Authorization)

```
User completes 7-step challenge creation wizard → taps "Starten"
    ↓
Android calls Cloud Function: createPaymentIntent
    {userId, amountCents, challengeId}
    ↓
Cloud Function:
    stripe.paymentIntents.create({
        amount: amountCents,
        currency: 'eur',
        capture_method: 'manual',   ← KEY: authorize only, do NOT charge yet
        confirm: false
    })
    → returns {paymentIntentId, clientSecret}
    ↓
Android: Stripe Payment Sheet → user enters card details → confirms
    ↓
Payment status: AUTHORIZED (money held, not captured)
    ↓
challengeId + paymentIntentId stored in Room + Firestore
```

### Phase 2a: Challenge FAILED → Capture Money

```
DailyEvaluationWorker detects limit exceeded
    ↓
⚠️ CRITICAL ORDER: Stripe capture FIRST, then mark FAILED
    ↓
Android calls Cloud Function: capturePayment
    {paymentIntentId, challengeId}
    ↓
Cloud Function:
    stripe.paymentIntents.capture(paymentIntentId)
    ↓
On success:
    Mark challenge as FAILED in Room
    Mark challenge as FAILED in Firestore
    Show failure notification to user
```

### Phase 2b: Challenge COMPLETED → Refund Money

```
DailyEvaluationWorker detects all days completed
    ↓
⚠️ CRITICAL ORDER: Stripe refund FIRST, then mark COMPLETED
    ↓
Android calls Cloud Function: cancelOrRefundPayment
    {paymentIntentId, challengeId}
    ↓
Cloud Function:
    stripe.paymentIntents.cancel(paymentIntentId)  ← if not yet captured
    OR stripe.refunds.create({payment_intent: id})  ← if already captured
    ↓
On success:
    Mark challenge as COMPLETED in Room
    Mark challenge as COMPLETED in Firestore
    Show success notification + confetti
```

### Cloud Function signatures (index.ts)

```typescript
// createPaymentIntent
export const createPaymentIntent = functions.https.onRequest(async (req, res) => {
    // auth check (Bearer token) ...
    const { amountCents, challengeId } = req.body
    const intent = await stripe.paymentIntents.create({
        amount: amountCents,
        currency: 'eur',
        capture_method: 'manual',
    })
    res.json({ paymentIntentId: intent.id, clientSecret: intent.client_secret })
})

// capturePayment
export const capturePayment = functions.https.onRequest(async (req, res) => {
    // auth check ...
    const { paymentIntentId } = req.body
    await stripe.paymentIntents.capture(paymentIntentId)
    res.json({ success: true })
})

// cancelOrRefundPayment
export const cancelOrRefundPayment = functions.https.onRequest(async (req, res) => {
    // auth check ...
    const { paymentIntentId } = req.body
    const intent = await stripe.paymentIntents.retrieve(paymentIntentId)
    if (intent.status === 'requires_capture') {
        await stripe.paymentIntents.cancel(paymentIntentId)
    } else {
        await stripe.refunds.create({ payment_intent: paymentIntentId })
    }
    res.json({ success: true })
})
```

---

## Hard Mode DailyLog Sync Pattern

Identical to Soft Mode — same Room + Firestore pattern:

### SESSION_LIMIT (Hard Mode)
- consciousOpens written to Room immediately on tap
- consciousOpens written to Firestore immediately (fire-and-forget, SetOptions.merge())
- On app start: restore from Firestore → Room
- Firestore path: users/{userId}/dailyLogs/{challengeId}_{DateUtils.todayKey()}

### TIME_LIMIT (Hard Mode)
- timeUsedMs written to Room every 10s via UsageTrackingService
- timeUsedMs written to Firestore every 10s (fire-and-forget, SetOptions.merge())
- On app start: restore from Firestore → Room

### DAILY_BUDGET (Hard Mode)
- budgetUsedMs written to Room every 10s via UsageTrackingService
- budgetUsedMs written to Firestore every 10s (fire-and-forget, SetOptions.merge())
- SharedPreferences: sessionEndTime, sessionStartTime, committedMs
- On app start: restore from Firestore → Room → SharedPreferences

### Fail Detection (all limit types)
DailyEvaluationWorker reads from Room DailyLog (DateUtils.todayKey()):
    consciousOpens >= limitValueSessions → FAIL
    timeUsedMs >= limitValueMinutes * 60000 → FAIL
    budgetUsedMs >= dailyBudgetMinutes * 60000 → FAIL

On FAIL — CRITICAL ORDER (never reverse):
    1. stripe.paymentIntents.capture(paymentIntentId)  ← FIRST
    2. Mark FAILED in Room
    3. Mark FAILED in Firestore challenge document
    4. Show failure notification

On SUCCESS — CRITICAL ORDER (never reverse):
    1. stripe.paymentIntents.cancel/refund(paymentIntentId)  ← FIRST
    2. Mark COMPLETED in Room
    3. Mark COMPLETED in Firestore challenge document
    4. Show success notification + confetti

---

## Hard Mode Lockout — In-App Behavior

While a Hard Mode challenge is active:

- **Logout is blocked** — Settings screen shows disabled logout button with explanation
- **Account deletion is blocked** — user warned it would fail the challenge
- **`HardModeLockoutOverlay`** is shown if user tries to circumvent blocking:
  - Full-screen, cannot be dismissed
  - Shows stake amount, days remaining, motivation text

### Device Binding Logic

```kotlin
// In SettingsViewModel / AuthViewModel:
val hasActiveHardModeChallenge = challengeRepository
    .getActiveChallenges()
    .any { it.mode == ChallengeMode.HARD && it.status == ChallengeStatus.ACTIVE }

if (hasActiveHardModeChallenge) {
    // Block logout
    // Block account deletion
    // Show explanation dialog
}
```

---

## Emergency Unlock

A safety escape hatch for legitimate edge cases (e.g., lost phone, medical emergency):

- Access: Settings → "Notfall-Entsperrung" (hidden or clearly labeled)
- Consequence: **Triggers Stripe capture immediately** (user loses their stake)
- Flow:
  ```
  User taps Emergency Unlock
      ↓
  Confirmation dialog: "Du verlierst €X. Wirklich entsperren?"
      ↓
  User confirms
      ↓
  capturePayment Cloud Function called
      ↓
  Challenge marked FAILED
      ↓
  App blocking removed
  ```
- This is NOT a refund — it's a deliberate fail.

---

## Stripe Firestore Data

Stored in challenge document:

```
challenges/{challengeId}/
    paymentIntentId: String
    amountCents: Int
    stripeStatus: String ("authorized" | "captured" | "refunded" | "cancelled")
```

Stored in user document (for payout tracking):
```
users/{userId}/
    payoutIban: String (optional, set after winning Group Challenge)
    payoutName: String
    stripeConnectedAccountId: String (future — Stripe Express)
    pendingPayouts: [{amount, groupId, createdAt}]
```

---

## Business Model (Hard Mode)

- When a user fails → Stripe captures their stake → money goes to app revenue
- When a user succeeds → Stripe refunds → user gets money back
- App earns **only from failed challenges**
- Group Challenge: 10% fee from failed participants' money (see `04_group_challenges.md`)

---

## Stripe Live Mode Checklist (before going live)

```
□ Switch functions/.env: STRIPE_SECRET_KEY=sk_live_...
□ Complete Stripe platform account verification
□ Enable Stripe Live mode and test full payment flow end-to-end
□ Ensure all Cloud Functions handle Stripe webhook events
□ Consult tax advisor re: income from captured challenges (Austria)
□ Consult lawyer re: gambling law classification in Austria
□ Register Gewerbe if annual revenue > €11,000
```

---

## Known Issues (Hard Mode / Stripe)

1. **Stripe Connect for automatic payouts not implemented.**
   Current flow: winner submits IBAN in Profile → stored in Firestore `payoutRequests` → founder manually transfers via bank (SEPA).
   Planned: Stripe Express — blocked by Austria individual account limitation.

2. **`completeGroupChallenge` Cloud Function not triggering automatically.**
   Needs to be called when `endDate` passes — must check on app foreground AND in DailyEvaluationWorker.

---

## Fortschrittsbalken (Hard Mode)

Identical logic to Soft Mode — read from Room DailyLog always.

SESSION_LIMIT:  progress = consciousOpens / limitValueSessions
TIME_LIMIT:     progress = timeUsedMs / (limitValueMinutes * 60000)
DAILY_BUDGET:   progress = budgetUsedMs / (dailyBudgetMinutes * 60000)

Display in Detail screen:
- Progress bar fills based on usage (same Composable as Soft Mode)
- Remaining opens/minutes shown below bar
- Stake amount shown prominently (e.g. "€20 auf dem Spiel")
- Days remaining shown separately

CRITICAL: Detail screen reads DIRECTLY from Room DailyLog.
Never pass progress values as navigation arguments.
Never use ViewModel state that was set before navigation.
Always read fresh from Room on Detail screen init using DateUtils.todayKey().
