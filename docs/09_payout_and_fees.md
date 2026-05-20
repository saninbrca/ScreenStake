# 09 — Payout & Fees
> **Scope:** All payout logic, fee calculations, Stripe flows, IBAN setup.
> **When to load:** Any work on payouts, ProfileScreen payout section,
> cancelOrRefundPayment, completeGroupChallenge, Redemption Challenge payouts.

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

Trigger: DailyEvaluationWorker detects COMPLETED

```kotlin
val refundAmount = floor(amountCents * 0.80).toInt()
// Call cancelOrRefundPayment with refundAmount (partial refund)
```

Cloud Function (cancelOrRefundPayment):

```typescript
if (amountCents) {
    // Partial refund:
    await stripe.refunds.create({
        payment_intent: paymentIntentId,
        amount: amountCents  // e.g. 800 = €8 from €10
    })
} else {
    // Full refund (legacy):
    await stripe.paymentIntents.cancel(paymentIntentId)
}
```

Firestore update:
  payoutStatus: "refunded"
  payoutAmount: refundAmount
  appFeeAmount: originalAmount - refundAmount

Notification: "Challenge gewonnen! 💚 €X werden zurückgebucht. (20% App-Gebühr: €Y)"

---

## Redemption Challenge Payout

Trigger: DailyEvaluationWorker detects Redemption COMPLETED

```kotlin
val refundAmount = floor(originalAmountCents * 0.60).toInt()
// Call cancelOrRefundPayment with originalPaymentIntentId + refundAmount
```

Note: refund comes from ORIGINAL paymentIntentId (not redemption — no new payment).
Stripe 90-day limit: originalDays <= 28 enforced at creation.

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

## Known Issues

1. Stripe Connect automatic payouts not yet live
   Prize money currently manual SEPA by founder
   Admin dashboard: admin/index.html

2. Account deletion while Hard Mode active →
   Must capture Stripe payment first (partially implemented)

3. completeGroupChallenge not auto-triggering on endDate →
   Must be called from DailyEvaluationWorker AND on app foreground
