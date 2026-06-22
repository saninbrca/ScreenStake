# 09 — Payout & Fees
> **Scope:** All payout logic, fee calculations, Stripe flows, IBAN setup.
> **When to load:** Any work on payouts, ProfileScreen payout section,
> cancelOrRefundPayment, completeGroupChallenge, Redemption Challenge payouts.
> _Last verified: 2026-06-22 (commit e287b79)_

---

## Fee Structure Overview

| Scenario | User gets back | App keeps |
|----------|---------------|-----------|
| Hard Mode win | 80% of stake | 20% |
| Redemption Challenge win | 60% of original stake | 40% |
| Group Challenge win (losers exist) | 80% of own stake + prize share | 20% of stake |
| Group Challenge win (nobody fails) | 100% of stake | 0% |
| Group Challenge fail (manual quit) | 0% | stake captured |
| Hard Mode fail | 0% | stake captured |

Prize pool (Group Challenge):
  prizePool = totalCapturedFromLosers - floor(totalCaptured * 0.10)
  prizePerWinner = floor(prizePool / numberOfWinners)

Always use Math.floor / floor() — never round up (avoid overpayment).

---

## Hard Mode Payout

Trigger: DailyEvaluationWorker detects COMPLETED (now ≥ endDate, no limitExceeded)

```kotlin
val refundAmount = floor(amountCents * 0.80).toInt()
// Call cancelOrRefundPayment with refundAmount (partial refund)
// The Room row is marked COMPLETED ONLY after this call returns success.
// On failure (incl. server-side 400 rejection) the challenge stays ACTIVE
// (hardModeWinRefundFailed = true → worker continues, retries next cycle).
```

**Client never marks COMPLETED before the refund succeeds (May 2026):** all three
DailyEvaluationWorker completion paths (TIME_BUDGET, TIME/SESSIONS, "already evaluated today")
gate the status update on `cancelOrRefundPayment` returning success. A failed refund leaves the
challenge ACTIVE for the next worker cycle.

Cloud Function (cancelOrRefundPayment) — **server-side validated** (non-redemption path):

```typescript
// 1. server clock must have passed endDate (Date.now() >= challenge.endDate) — never client time
// 2. challenge.payoutStatus !== "refunded"           — idempotency guard
// 3. challenge.stripePaymentIntentId === paymentIntentId — PI binding check
// 4. refund recomputed from STORED stake — client amountCents is ignored:
const serverAmountCents = Math.floor(challenge.amountCents * 0.80)

if (pi.status === "requires_capture") {
    await stripe.paymentIntents.capture(paymentIntentId)        // capture full pre-auth first
}
await stripe.refunds.create({
    payment_intent: paymentIntentId,
    amount: serverAmountCents     // e.g. 800 = €8 from €10 — recomputed server-side
})
```

The refund amount is **always recomputed server-side** from the stored `amountCents`; the
client-supplied value is discarded. This closes the device-clock-forward exploit. See
`docs/00_changelog.md` → "Hard Mode refund clock-forward exploit" and `docs/03_hard_mode_and_stripe.md`.

Firestore update:
  payoutStatus: "refunded"
  payoutAmount: refundAmount
  appFeeAmount: originalAmount - refundAmount

Notification: "Challenge gewonnen! 💚 €X werden zurückgebucht. (20% App-Gebühr: €Y)"

**Revenue tracking:** the retained app fee (`appFeeAmount`, fallback `floor(20%)`) is also added to
`counters/global.totalRevenueCents` via the CF `bumpCounters()` helper (best-effort, never blocks the
payout). `counters/global.totalRevenueCents` is the cheap source of truth for the admin Umsatz tab —
captured stakes on fails and 10% group fees feed it too. See `docs/11_admin_dashboard.md`.

---

## Stake Capture on Fail / Abandon (`capturePayment`)

When a solo Hard Mode challenge is **lost** (worker fail path, manual **abandon**, permission violation, or
emergency unlock) the **full** stake is captured — `0%` back to the user, full `amount_received` becomes
app revenue.

**`capturePayment` is IDEMPOTENT (June 2026).** It branches on the PI status already fetched for the IDOR
guard:
- `requires_capture` → capture now, record `paymentCaptures`, and **bump counters once**
  (`totalActiveChallenges −1`, `totalFailedChallenges +1`, `totalRevenueCents += amount_received`) →
  `{ success:true, alreadyCaptured:false }`.
- `succeeded` → money is **already gone** (>7-day auto-capture, or a racing/duplicate caller) →
  `{ success:true, alreadyCaptured:true }`, **no re-capture and no counter re-bump** (so revenue is never
  double-counted).
- anything else → **409** (not capturable) so the caller leaves the challenge **ACTIVE**.

**Counters bump ONLY on a fresh capture** (the `requires_capture` branch), never on `succeeded`. **Known
gap — `TODO(counter-gap)`:** a genuine first-time >7-day (auto-captured) loss therefore does **not** bump
the failed/revenue counters (Stripe captured at creation with no CF involvement, and this branch can't
distinguish it from a benign re-capture race). A dedicated server-side fail-accounting source is needed.

**Abandon (manual quit) — June 2026:** `ActiveChallengeViewModel.abandonChallenge()` now captures the
stake for **solo Hard Mode only** (`mode==HARD && groupChallengeId==null && stripePaymentIntentId!=null`)
and marks `FAILED` **only after** `capturePayment` succeeds; on capture failure the challenge stays ACTIVE.
Previously abandon set FAILED without capturing, so a ≤7-day pre-auth expired uncaptured. Group abandons
are settled by `completeGroupChallenge`, not direct-captured. See `docs/03_hard_mode_and_stripe.md`.

---

## Redemption Challenge Payout

Trigger: DailyEvaluationWorker detects Redemption COMPLETED

```kotlin
val refundAmount = floor(originalAmountCents * 0.60).toInt()
// Call cancelOrRefundPayment with originalPaymentIntentId + partialRefundCents
```

Note: refund comes from ORIGINAL paymentIntentId (not redemption — no new payment).
Stripe 90-day limit: originalDays <= 28 enforced at creation.

**Server-side validated (June 2026):** the redemption branch is **no longer** trusted blindly. The
`cancelOrRefundPayment` CF re-fetches the redemption challenge and requires `isRedemption === true`,
`payoutStatus !== "refunded"` (idempotency), `originalPaymentIntentId` matches the supplied PI, and a
server-clock `endDate` that has passed. It then **recomputes the 60% from the *original* challenge's
stored `amountCents`** — the client-supplied `partialRefundCents` is discarded. See
`docs/10_security_and_anticheat.md` and `docs/03_hard_mode_and_stripe.md`.

Notification: "Comeback geschafft! 🎉 €X werden zurückgebucht."

---

## Group Challenge Payout

### Case A — At least one participant failed

Winners — own stake (80% back):

```typescript
const stakeRefund = Math.floor(winner.amountCents * 0.80)
await stripe.refunds.create({
    payment_intent: winner.paymentIntentId,
    amount: stakeRefund
})
```

Prize distribution:

```typescript
const totalCaptured = losers.reduce((sum, p) => sum + p.amountCents, 0)
const appFee = Math.floor(totalCaptured * 0.10)
const prizePool = totalCaptured - appFee
const prizePerWinner = Math.floor(prizePool / winners.length)
```

Prize transfer (if winner has Connected Account):

```typescript
await stripe.transfers.create({
    amount: prizePerWinner,
    currency: "eur",
    destination: winner.stripeConnectedAccountId
})
```

If no Connected Account → store in payoutRequests (status: "pending_account_setup")

### Case B — Nobody failed (all complete)

Full refund for all:

```typescript
const nobodyFailed = participants.every(p =>
    p.status === "active" || p.status === "completed"
)
if (nobodyFailed) {
    // Full cancel/refund for each participant
    await stripe.paymentIntents.cancel(participant.paymentIntentId)
}
```

---

## IBAN Setup (Prize Payout)

User must set up IBAN to receive prize money (not own stake refund).
Own stake always refunded to original card automatically.
Prize share requires Stripe Connected Account.

Flow in ProfileScreen:
1. Winner sees "⏳ Ausstehend" for prize amount
2. Taps "IBAN hinterlegen"
3. Bottom sheet: Name + IBAN (AT + 18 digits validation)
4. Calls createConnectedAccount Cloud Function
5. Stored in Firestore: stripeConnectedAccountId, payoutIban, payoutName

IBAN validation: must start with "AT" + exactly 18 digits

createConnectedAccount Cloud Function:

```typescript
const account = await stripe.accounts.create({
    type: "custom",
    country: "AT",
    capabilities: { transfers: { requested: true } },
    external_account: {
        object: "bank_account",
        country: "AT",
        currency: "eur",
        account_holder_name: accountHolderName,
        account_holder_type: "individual",
        iban: iban
    },
    tos_acceptance: {
        date: Math.floor(Date.now() / 1000),
        ip: req.ip
    }
})
```

Connected Account type: "custom" only (NOT Express — Austria limitation)

---

## ProfileScreen Payout Display

Show "Auszahlungen" section for users with completed challenges.

Hard Mode win:
  Einsatz:            €10,00
  App-Gebühr (20%):   -€2,00
  ─────────────────────────
  Ausgezahlt:         €8,00 ✅

Redemption win:
  Ursprünglicher Einsatz: €10,00
  App-Gebühr (40%):       -€4,00
  ──────────────────────────────
  Ausgezahlt:             €6,00 ✅

Group win (someone failed):
  Eigener Einsatz:    €10,00
  App-Gebühr (20%):   -€2,00
  Gewinnanteil:       +€8,00
  ─────────────────────────
  Gesamt:             €16,00 ✅

Group win (nobody failed):
  Eigener Einsatz:    €10,00
  App-Gebühr:         €0,00
  ─────────────────────────
  Ausgezahlt:         €10,00 ✅

Prize pending (no IBAN):
  Gewinnanteil: €8,00 ⏳ Ausstehend
  [IBAN hinterlegen →]

Status indicators:
  ✅ "Auf deine Karte zurückgebucht"
  ⏳ "Ausstehend — IBAN hinterlegen"
  🔄 "In Bearbeitung"

---

## Stripe Configuration

Mode: Test (sk_test_...) → Live before launch
Key location: functions/.env ONLY
Test card: 4242 4242 4242 4242, 12/34, 123

Partial refund: stripe.refunds.create({payment_intent, amount})
Full refund: stripe.paymentIntents.cancel() if not captured
             stripe.refunds.create() if already captured

After any Cloud Function change:
firebase deploy --only functions

---

## Firestore Payout Data

users/{userId}/:
  stripeConnectedAccountId: String?
  payoutIban: String?
  payoutName: String?
  payoutSetupAt: Long?
  pendingPayouts: Map<groupId, PayoutInfo>

PayoutInfo:
  amount: Int (cents)
  stakeRefund: Int (cents)
  status: "pending_account_setup" | "transferred" | "completed"
  groupId: String
  createdAt: Long
  transferredAt: Long?

payoutRequests/{requestId}/:
  userId, displayName, iban, amountCents, groupId
  status: "pending" | "paid" | "rejected"
  createdAt, paidAt?

---

## Manual Payout Flow (current implementation)

Winners request payout via **"Auszahlen"** button in ProfileScreen → Guthaben Card.
Payout is NEVER automatic. Pressing the button creates a `payoutRequests` document with:
  status: "requested"  ← new status (in addition to "pending" | "paid" | "rejected")

Firestore: payoutRequests/{requestId}/status values:
  "requested"  — user tapped "Auszahlen" (new, pending founder action)
  "pending"    — legacy / queued
  "paid"       — founder marked as paid in admin dashboard
  "rejected"   — rejected by founder

IBAN is set in **Settings → Auszahlungskonto** (not in ProfileScreen).
Prize share still requires Stripe Connected Account for automatic transfer.
Own stake refund always goes back to original card (no IBAN needed).

### Guthaben Card (ProfileScreen)
Shown when user has pending balance or unprocessed winnings.
Displays: pending amount, "Auszahlen" button, current payout status.
Balance includes: prize share not yet transferred + stake refunds in progress.

---

## Permission Violation Capture

Hard Mode stakes (and Group Challenge buy-ins) are captured server-side when permissions
are lost for more than 24 hours, independent of the app being installed or running.

**Trigger paths:**
- Accessibility or Overlay permission lost → `permissionLostAt` written to Firestore by
  `PermissionCheckWorker` → `checkPermissionViolations` CF captures after 24h.
- Accessibility disabled + blocked app used > 1 min → `usageViolationDetectedAt` written by
  `PermissionCheckWorker` → CF captures after **1 hour** (faster path).

**`failReason` field:** Written as `"permission_violation"` to the challenge document on
server-side capture (alongside existing `payoutStatus` + `appFeeAmount` fields).

**Capture order:** Stripe FIRST → Firestore update SECOND (same rule as all other capture paths).

**User notification:** Escalating notifications at hours 6, 12, 23 warn the user before
capture occurs (see `05_huawei_and_permissions.md` for full escalation timeline).

**Firestore rules:** `capturedAt` and `usageCapturedAt` are CF-only fields — client cannot write them.

---

## Known Issues

1. **Stripe Connect (Custom Account):** IBAN collection + Connected Account creation is implemented. Prize transfers are currently initiated **manually by the founder via Stripe Dashboard**. Full automatic transfer via API is planned post-launch.
   Admin dashboard: admin/index.html

2. Account deletion while Hard Mode active →
   Must capture Stripe payment first (partially implemented)

3. completeGroupChallenge not auto-triggering on endDate →
   Must be called from DailyEvaluationWorker AND on app foreground
