# 03 — Hard Mode & Stripe
> **Scope:** Hard Mode rules, Stripe payment flow (pre-auth → capture on fail → refund on success), app lockout during Hard Mode, Emergency Unlock, device binding.
> **When to load:** Any work on Hard Mode, Stripe integration, `PaymentRepository`, Cloud Functions for payments, or the Hard Mode lockout overlay.
> _Last verified: 2026-06-22 (commit e287b79)_

---

## Hard Mode Rules

| Rule | Value |
|------|-------|
| Minimum duration | **7 days** production (1 day DEBUG) — enforced in `CreateChallengeUseCase` |
| Maximum duration | **90 days** |
| Minimum stake | **€5** (default — remotely configurable via `AppConfig.hardModeMinStake`) |
| Maximum stake | **€100** (default — remotely configurable via `AppConfig.hardModeMaxStake`) |
| Money | Real money via Stripe |
| End date | Mandatory (minimum 7 days from start) |
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
| Minimum duration | none | 7 days (1 day in debug) |

RULE: Any fix applied to Soft Mode blocking/overlay/DailyLog logic
MUST be applied to Hard Mode as well. They share the same code paths.
Never create separate implementations for Hard vs Soft Mode overlays.

**CONFIRMED (code analysis, May 2026):** Hard Mode overlay flow is identical to Soft Mode
end-to-end. No separate Hard Mode overlay composable exists or is needed.

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
challengeId + paymentIntentId stored in Room, then mirrored to Firestore
```

**Write order (June 2026 — "gutted doc" fix, CRITICAL).** `createPaymentIntent` is called with the
**same `challengeId`** that the challenge will be saved under (Stripe `metadata.challengeId`) — the client
never mints a second id for Hard Mode. The CF **no longer writes the challenge doc**; the client's
`saveChallenge` performs a **single full CREATE** under that unified cid (Firestore rules allow all fields
on CREATE but block `status`/`endDate`/`amountCents`/`stripePaymentIntentId` on a client UPDATE — so a
CF pre-write turned the create into a rejected update and left a gutted doc that broke server win-
validation forever). The Hard Mode mirror is **awaited with bounded retry** (not fire-and-forget) so the
doc is guaranteed to land before creation reports success. See `docs/00_changelog.md` → "Hard Mode 'gutted
challenge doc'".

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
Cloud Function (server-side validated — see signatures below):
    rejects unless server clock ≥ endDate, not already refunded, PI matches challenge
    recomputes 80% from stored amountCents
    stripe.paymentIntents.cancel(paymentIntentId)  ← if not yet captured
    OR stripe.refunds.create({payment_intent: id})  ← if already captured
    ↓
On success (Result.success):
    Mark challenge as COMPLETED in Room
    Mark challenge as COMPLETED in Firestore
    Show success notification + confetti
    ↓
On failure (Result.failure — incl. the 400 endDate rejection):
    DailyEvaluationWorker sets hardModeWinRefundFailed = true,
    logs a warning, and continues WITHOUT updating status.
    Challenge stays ACTIVE in Room → next worker cycle retries automatically.
```

**Client gating (May 2026):** `DailyEvaluationWorker` only marks a Hard Mode win `COMPLETED`
**after** `cancelOrRefundPayment` returns success. All three completion paths (TIME_BUDGET,
TIME/SESSIONS, and the "already evaluated today" short-circuit) set `hardModeWinRefundFailed`
in their refund `.onFailure` handler and `continue` — never marking COMPLETED before the money
is actually refunded.

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
        metadata: { userId, challengeId },   // userId → IDOR guard; challengeId → binds PI to the doc
    })
    // NOTE (June 2026): the challenge doc is intentionally NOT written here. The client's
    // saveChallenge() does a single full CREATE under this same challengeId. Pre-writing it turned
    // that create into a rules-rejected UPDATE → gutted doc. stripeCustomerId lives on the user doc.
    res.json({ paymentIntentId: intent.id, clientSecret: intent.client_secret })
})

// capturePayment — IDEMPOTENT (June 2026), IDOR-guarded
export const capturePayment = functions.https.onRequest(async (req, res) => {
    // auth check ... + IDOR guard: PI.metadata.userId must equal the caller (else 403)
    const { paymentIntentId } = req.body
    // Branch on the PI status already fetched for the IDOR guard (no extra Stripe call):
    //   succeeded        → money ALREADY gone → { success:true, alreadyCaptured:true }, NO re-capture,
    //                       NO counter re-bump (covers >7d auto-capture + racing/duplicate callers)
    //   requires_capture → capture now, record paymentCaptures, bump counters ONCE → alreadyCaptured:false
    //   anything else    → 409 (not capturable) so the caller leaves the challenge ACTIVE
    // CONTRACT: a `success` response ALWAYS means "the stake is captured" — callers gate FAILED on it.
    res.json({ success: true, alreadyCaptured: false /* or true */ })
})

// cancelOrRefundPayment
export const cancelOrRefundPayment = functions.https.onRequest(async (req, res) => {
    // auth check (Bearer token → verifiedUserId) ...
    const { paymentIntentId, challengeId, amountCents, partialRefundCents } = req.body
    const pi = await stripe.paymentIntents.retrieve(paymentIntentId)

    // ── Server-side validation (solo Hard Mode win / non-redemption path) ──────
    // Redemption branch (partialRefundCents > 0) is ALSO fully server-validated (June 2026):
    // isRedemption, payoutStatus != "refunded", originalPaymentIntentId match, server-clock
    // endDate, and the 60% recomputed from the original challenge's stored amountCents.
    let serverAmountCents = amountCents
    if (!(partialRefundCents && partialRefundCents > 0)) {
        if (!challengeId) throw new HttpError(400, "challengeId is required.")
        const snap = await admin.firestore()
            .collection("users").doc(verifiedUserId)
            .collection("challenges").doc(challengeId).get()
        if (!snap.exists) throw new HttpError(404, "Challenge not found.")
        const c = snap.data()!

        // 1. endDate must have passed — SERVER clock only, never trust client time
        const endDate = typeof c.endDate === "number" ? c.endDate : c.endDate?.toMillis?.() ?? 0
        if (endDate <= 0 || Date.now() < endDate)
            throw new HttpError(400, "Challenge has not reached its end date.")

        // 2. Idempotency guard — never refund twice
        if (c.payoutStatus === "refunded") throw new HttpError(409, "Challenge already refunded.")

        // 3. PI binding — supplied PI must match the one stored on the challenge
        if (c.stripePaymentIntentId !== paymentIntentId)
            throw new HttpError(400, "paymentIntentId does not match challenge.")

        // 4. Recompute 80% from STORED stake — client-supplied amountCents is ignored
        const storedStake = typeof c.amountCents === "number" ? c.amountCents : 0
        serverAmountCents = Math.floor(storedStake * 0.80)
    }

    // ... branches now use serverAmountCents (not the client amountCents):
    if (partialRefundCents && partialRefundCents > 0) {
        await stripe.refunds.create({ payment_intent: paymentIntentId, amount: partialRefundCents })
    } else if (pi.status === 'requires_capture' && serverAmountCents && serverAmountCents < pi.amount) {
        await stripe.paymentIntents.capture(paymentIntentId)
        await stripe.refunds.create({ payment_intent: paymentIntentId, amount: serverAmountCents })
    } else if (pi.status === 'requires_capture') {
        await stripe.paymentIntents.cancel(paymentIntentId)   // full cancel — group nobody-failed fallback
    } else {
        // already captured: partial (serverAmountCents) or full refund
        await stripe.refunds.create({ payment_intent: paymentIntentId, amount: serverAmountCents })
    }
    res.json({ success: true })
})
```

**Server-side validation (May 2026 hardening):** the non-redemption path NEVER trusts the
client. It re-fetches the challenge doc and rejects the call unless the **server clock** has
passed `endDate`, `payoutStatus` is not already `"refunded"`, and the supplied `paymentIntentId`
matches `challenge.stripePaymentIntentId`. The 80% refund is recomputed from the **stored**
`amountCents` — the client-supplied `amountCents` is discarded. This closes the device-clock-
forward exploit (client could otherwise trigger an early refund). See
`docs/00_changelog.md` → "Hard Mode refund clock-forward exploit".

---

## Server-side Money Authority (June 2026 hardening)

The win/loss decision originally lived entirely client-side (`DailyEvaluationWorker`), so a cheater
editing local Room state could force a wrongful refund. Three Cloud Function fixes + Firestore-rules
hardening moved money authority to the server. **Full detail: `docs/10_security_and_anticheat.md`.**

- **Fix 1 — `cancelOrRefundPayment` win-gate (non-redemption path, FAIL-OPEN).** Before issuing the
  80% win-refund, the CF reads `users/{uid}/challenges/{cid}/dailyLogs` and **denies the refund only
  if it positively sees `limitExceeded === true` on any day** (a violation day means the stake should
  have been captured, not refunded). **Absence of logs never denies** — sync is best-effort, so a
  legitimate winner (who never has an exceeded day) is never wrongly refused. This catches the
  Room-only tamper path.
- **Fix 2 — `capturePayment` IDOR guard.** The CF retrieves the PaymentIntent and returns **403** when
  `metadata.userId` ≠ the authenticated caller. `createPaymentIntent` stamps `metadata.userId` on
  every PI. All three callers (`DailyEvaluationWorker`, Emergency Unlock, `PermissionCheckWorker`)
  only pass the caller's own PI, so no legitimate capture breaks. Legacy PIs without the metadata
  field fall through unchanged.
- **Fix 3 — redemption branch now FULLY server-validated.** Previously the redemption branch refunded
  the client-supplied `partialRefundCents` with **zero validation**. It now re-fetches the redemption
  challenge and requires: `isRedemption === true`, `payoutStatus !== "refunded"` (idempotency),
  `originalPaymentIntentId` matches the supplied PI, server-clock `endDate` passed, and **recomputes
  the 60% refund from the *original* challenge's stored `amountCents`** — the client's
  `partialRefundCents` is discarded. *(This supersedes the earlier "redemption branch is left intact"
  note — that branch is no longer unvalidated.)*
- **`onChallengeDeleted` CF (Firestore `onDelete` trigger on
  `users/{userId}/challenges/{challengeId}`).** Cascade-deletes the challenge's nested `dailyLogs`
  sub-collection (Admin SDK, batched). Required so the `dailyLogs` rules can block ALL client deletes.
  Also fixes a pre-existing orphaned-`dailyLogs` bug on per-challenge delete.
- **`dailyLogs` rules hardening (nested path).** `update` may **never flip `limitExceeded`
  true → false** (false → true still allowed so the worker can record a violation), and `delete` is
  **CF-only** (`allow delete: if false`). This stops a cheater deleting/rewriting an exceeded-day log
  to dodge the Fix 1 win-gate.

**DECISION — known residual ("suppress-gap"):** there is no server-side source of app-usage truth, so
a cheater who *suppresses* the violation write (offline / disabled worker) still looks like a clean
win. The permission-violation capture path (`checkPermissionViolations`, 1h/24h) remains the backstop
for disabled enforcement.

---

## Hard Mode DailyLog Sync Pattern

Identical to Soft Mode — same Room + Firestore pattern:

### SESSION_LIMIT (Hard Mode)
- consciousOpens written to Room immediately on tap
- consciousOpens written to Firestore immediately (fire-and-forget, SetOptions.merge())
- On app start: restore from Firestore → Room
- Firestore path: users/{userId}/dailyLogs/{challengeId}_{DateUtils.todayKey()}

### TIME_LIMIT (Hard Mode)
- totalMinutes written to Room every 10s via UsageTrackingService
- totalMinutes written to Firestore every 10s (fire-and-forget, SetOptions.merge())
- On app start: restore from Firestore → Room

### DAILY_BUDGET (Hard Mode)
- budgetUsedMs written to Room every 10s via UsageTrackingService
- budgetUsedMs written to Firestore every 10s (fire-and-forget, SetOptions.merge())
- SharedPreferences: sessionEndTime, sessionStartTime, committedMs
- On app start: restore from Firestore → Room → SharedPreferences

### Fail Detection (all limit types)
DailyEvaluationWorker reads from Room DailyLog (DateUtils.todayKey()):
    consciousOpens >= limitValueSessions → FAIL
    totalMinutes >= limitValueMinutes → FAIL
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
- When a user succeeds → Stripe captures then partially refunds (80% back) → app keeps 20%
- Redemption win → 60% refunded (40% app fee)
- Group win (losers exist) → 80% of own stake back + prize share; app keeps 20% of each winner's stake
- Group win (nobody failed) → 100% refunded; no fee
- Group Challenge prize pool: 10% fee from failed participants' captured money (see `04_group_challenges.md`)

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

## Payout System

→ Full payout rates and fee tables: see docs/09_payout_and_fees.md

### Payout Rates (App Fee)
> **Canonical owner: `docs/09_payout_and_fees.md`.** The full rate/fee table lives there — not
> duplicated here, to avoid drift. (The inline percentages in the flow explanations above are kept
> for readability.)

**Money-critical rule:** always use `Math.floor` / `floor()` for refund/fee math — never round up
(avoid overpayment).

### Hard Mode
**Trigger:** `DailyEvaluationWorker` detects challenge `COMPLETED` (now ≥ endDate, no limitExceeded)
**Action:** `cancelOrRefundPayment` Cloud Function with `amountCents = floor(stake × 0.80)`:
- If PI is `requires_capture`: **capture full amount first**, then `stripe.refunds.create({ amount: refundAmount })`
- If already captured: `stripe.refunds.create({ amount: refundAmount })`
**Result:** Stripe partial refund → original card → 1–5 business days (app retains 20%)
**Firestore:** `users/{userId}/challenges/{challengeId}` → `payoutStatus: "refunded"`, `payoutAmount`, `appFeeAmount`, `payoutDate`
**Notification:** `sendHardModeCompleted()` — "Challenge gewonnen! 💚 €X werden zurückgebucht. (20% App-Gebühr: €Y)"

### Group Challenge — Own Stake
**Trigger:** `completeGroupChallenge` Cloud Function (called by `DailyEvaluationWorker` on endDate)
**Nobody failed path:** All participants get 100% refund — no fee. PI in `requires_capture` → cancel; else → full refund.
**Action (someone failed):** Per winner: `stakeRefund = floor(stake × 0.80)`. Capture PI if `requires_capture`, then refund `stakeRefund`.
**Result:** Stripe partial refund → original card → 1–5 business days

### Group Challenge — Prize Share
**Trigger:** Same `completeGroupChallenge` CF
**Calculation:**
```
prizePool = totalCapturedFromLosers - 10% appFee    (Math.floor for both)
prizePerWinner = prizePool / numberOfWinners         (Math.floor)
```
**If winner has connected account:** `stripe.transfers.create` to `stripeConnectedAccountId`
**If no connected account:** stored in `users/{userId}/pendingPayouts` subcollection
  with `status: "pending_account_setup"`, `stakeRefundCents`, `displayName`
**Firestore group doc updated with:** `prizePool`, `appFee`, `prizePerWinner`, `status: "completed"`, `completedAt`

### IBAN Setup — Stripe Custom Connected Account
**CF:** `createConnectedAccount` (type: `custom`, NOT Express — Austria individual account limitation)
**Flow:**
1. User taps "IBAN hinterlegen →" in ProfileScreen payout card
2. ModalBottomSheet: name + IBAN (AT + 18 digits validation)
3. "Speichern & Auszahlung beantragen" → `setupPayoutAccount()` → `createConnectedAccount` CF
4. CF creates `type: "custom"` account with IBAN as `external_account`, `tos_acceptance` auto-set
5. Firestore `users/{uid}`: `stripeConnectedAccountId`, `payoutIban`, `payoutName`, `payoutSetupAt`

### Payout Display in ProfileScreen
Per-challenge breakdown cards (show only for completed challenges):
- **Solo Hard Mode card:** Eigener Einsatz zurück ✅ / Status: zurückgebucht 💚
- **Group Challenge card:** Eigener Einsatz ✅ + Gewinnanteil ✅/⏳ + App-Gebühr info + Gesamt
- **Pending prize:** "IBAN hinterlegen →" button → ModalBottomSheet
Data source: Room (solo) + Firestore group doc (prizePerWinner, appFee) + pendingPayouts subcollection

### Notifications (after payout triggers)
`sendGroupChallengePayoutReceived()` — three variants:
- Full payout (stake + prize): "€X werden auf deine Karte zurückgebucht."
- Pending prize (no IBAN): "Dein Einsatz (€X) zurückgebucht. Hinterlege IBAN für Gewinnanteil (€Y)."
- Stake only (no losers): "Challenge abgeschlossen! ✅ Dein Einsatz (€X) wurde zurückgebucht."

### Critical Rules
- `cancelOrRefundPayment` CF: NEVER use `wasImmediate` flag — always auto-detect from PI status
- `cancelOrRefundPayment` CF (non-redemption path): ALWAYS validate server-side before refunding —
  server clock `Date.now() >= challenge.endDate`, `payoutStatus != "refunded"` (idempotency),
  `stripePaymentIntentId === paymentIntentId` (PI binding). NEVER trust the client's clock or
  client-supplied `amountCents` — recompute `floor(storedStake × 0.80)`. **Never reverse these checks.**
- `capturePayment` CF is IDEMPOTENT: a `success` response ALWAYS means "captured". Bump counters ONLY on a
  fresh `requires_capture` capture — never on the `succeeded` branch (would double-count). Non-capturable
  status → 409 so the caller leaves the challenge ACTIVE. Keep the IDOR guard (`metadata.userId`).
- Hard Mode creation = a SINGLE rules-allowed CREATE under the unified `challengeId` (the one passed to
  `createPaymentIntent`); the CF NEVER writes the challenge doc, and the client never mints a second id.
  The Hard Mode Firestore mirror is AWAITED (with bounded retry), never fire-and-forget.
- Abandon captures SOLO Hard Mode only (`mode==HARD && groupChallengeId==null && PI!=null`); status→FAILED
  ONLY inside `capturePayment.onSuccess`. NEVER mark FAILED without a confirmed capture.
- Hard Mode win: PI is `requires_capture` → capture first, then partial refund (80%). NEVER cancel (would give 100%)
- Stripe refund ALWAYS before marking COMPLETED in Room/Firestore — and the client only marks
  COMPLETED after the refund CF returns success; a failed refund leaves the challenge ACTIVE for retry
- `prizePerWinner` uses `Math.floor` (never round up — avoid overpayment)
- App fee on losers' pot = exactly `Math.floor(total * 0.10)`
- IBAN validation client-side: must match `AT[0-9]{18}` regex
- Never store IBAN in Room — only Firestore + Stripe
- All Stripe calls in Cloud Functions — never in Android code directly
- After any CF change: `firebase deploy --only functions`

---

## Dead Code Removed

- **`captureAndLock`** — removed. This was an unused code path that attempted to combine
  Stripe capture with an immediate lockout overlay. It was never triggered in production
  and has been deleted. Hard Mode lockout is handled via `DailyEvaluationWorker` (planned
  fail path) or Emergency Unlock (user-initiated), not via `captureAndLock`.

---

## Permission Violation Capture (Server-side)

Hard Mode stakes are captured server-side if permissions are lost — independent of app state.

**Trigger paths:**
- Permission lost (Accessibility or Overlay) → `permissionLostAt` written to Firestore by
  `PermissionCheckWorker` → `checkPermissionViolations` CF captures after 24h.
- Accessibility disabled + blocked app used > 1 min → `usageViolationDetectedAt` written by
  `PermissionCheckWorker` → CF captures after 1 hour.

**Capture order:** Stripe FIRST → Firestore `failReason: "permission_violation"` SECOND.

`failReason` written to the challenge document on server-side capture. Firestore rules block
client writes to `capturedAt` and `usageCapturedAt` — only Cloud Function Admin SDK can set them.

---

## Device-detected loss → `markChallengeFailed` CF (doc retained)

A **device-detected** Hard Mode loss (limit exceeded in `DailyEvaluationWorker`, permission timeout in
`PermissionCheckWorker`, or **abandon**) now captures the stake first, then marks the challenge FAILED
**without deleting the Firestore doc**. `ChallengeRepositoryImpl.updateChallengeStatus(FAILED, failReason)`
calls the **`markChallengeFailed`** CF, which writes `status:"failed"` + the **passed** `failReason` +
`failedAt` **in place** via the Admin SDK.

- **`failReason` is the real cause now, not a constant.** Each loss path threads its own cause:
  `"limit_exceeded"` (`DailyEvaluationWorker` capture loss + `OverlayManager` soft-fail), `"abandon"`
  (the abandon flow), `"permission_violation"` (`PermissionCheckWorker`). The repo falls back to
  `"client_loss"` only when a caller passes no cause (legacy). The value is also persisted to the Room
  `failReason` column (migration 25→26) so `ChallengeFailedDialog` can show the **challenge name** + a
  human-readable reason. Server-set causes (`usage_violation`/`reconciliation`/`device_dark`) arrive via
  sync and never overwrite an already-terminal local row.

- **Why a CF:** the client can never write the protected `status` key (Firestore rules block it). The
  CF derives the uid from the auth token and updates the doc under that caller's own subcollection
  (no IDOR); a missing doc → HTTP 400.
- **Idempotent:** a doc already `failed`/`completed` returns success with NO second write.
- **Never touches money:** the capture always happens BEFORE this call; the CF never calls Stripe and
  never writes `payoutStatus` or bumps counters (the capture path owns those).
- **Audit trail preserved:** the challenge doc AND its nested tamper-evident `dailyLogs` are KEPT
  (previously the device DELETED the doc + cascade-deleted the logs). This also keeps the **Redemption**
  refund path working — it reads the original challenge's stored stake, which now still exists.
- **Capture-gate:** FAILED is set ONLY inside `capturePayment.onSuccess`; on a capture failure the
  challenge stays ACTIVE (no fail, no `markChallengeFailed` call) for the next worker cycle / the server
  reconciliation net. (The PI-less legacy branch in `PermissionCheckWorker` is the one place FAILED is
  set without a capture — nothing to capture there.)

> **CF change → `firebase deploy --only functions`.** See `docs/firestore-schema.md` Part 2 for the
> full per-field lifecycle.

---

## "Device went dark = forfeit" heartbeat (SHIPS DARK)

An active solo Hard Mode device that stops reporting (app uninstalled, or accessibility/overlay
disabled so nothing tracks) used to be auto-refunded as a WIN by the reconciliation net (clean logs =
no `limitExceeded`). It is now a **LOSS/forfeit**, detected by the ABSENCE of a periodic heartbeat
(Android has no uninstall callback).

- **Heartbeat write.** `PermissionCheckWorker.writeHeartbeatIfHardActive` merges
  `permissionStatus/current.lastSeenAt = now` at the **top of `doWork()`** (before any early return),
  gated on "user has ≥1 active HARD challenge". Owner-writable by design — a cheater can only avoid the
  forfeit by keeping the real app installed and beating (= honest behaviour).
- **Reconciliation went-dark branch (B2.5).** `runDueChallengeReconciliation` now scans ALL active
  hard challenges (the `endDate<=now` query filter is dropped; due-ness is computed per-doc) and,
  between the B2 unresolved-marker deferral and the B3 `lossProven` test, forfeits any whose
  `lastSeenAt` (or `startDate` if never beat) is older than `config/app.wentDarkGraceMs`. It reuses the
  EXISTING loss settlement (capture-first, idempotency key, counter bumps) with
  `failReason:"device_dark"`. A proven `limitExceeded` loss keeps `failReason:"reconciliation"` and
  takes precedence. Runs for not-yet-due challenges too, so a ≤7d manual-capture auth is captured
  mid-challenge while still valid.
- **GRACE & fail-safety.** `wentDarkGraceMs` is server-tunable (recommended 72h). A
  missing/invalid/unreadable value ⇒ `Number.MAX_SAFE_INTEGER` ⇒ the predicate is never true ⇒ NEVER
  forfeit. Combined with the existing `reconciliationEnabled=false` + `reconciliationDryRun=true` gates,
  the feature ships fully dark and forfeits nobody until ops arms all three. See `docs/13`.
- **Disclosure.** A mandatory **Step-7 forfeit-consent checkbox** hard-blocks Start until ticked.
- **Best-effort nudge.** At ~grace/2 (36h) of worker suppression the device posts a "Detox meldet sich
  nicht mehr — open the app" warning (`NotificationHelper.sendHeartbeatWarning`).
- **KNOWN RESIDUALS (accepted).** (1) For a **5–7-day** ≤7-day challenge, a device that goes dark in
  the back half can escape capture if GRACE outruns the remaining Stripe manual-auth window (auth
  releases ~7d after creation) — the auth-expiry is the backstop and the effective grace is shorter for
  1–3-day challenges. (2) `lastSeenAt` is owner-writable (residual by design — see above). Tightening
  GRACE trades the residual against Huawei false-forfeits (EMUI throttles the worker for hours/days even
  when installed — the dominant risk). See `docs/10 §5`.

---

## Rooted Device (Hard Mode)

- RootBeer check runs before Hard Mode payment initiation (in `ChallengeCreationViewModel`).
- Non-blocking warning dialog: "Verstanden — trotzdem fortfahren" / "Abbrechen".
- Root status logged to `users/{uid}/deviceInfo/security` for admin visibility.
- **Anti-cheat metadata on the challenge (June 2026):** `deviceId` (Settings.Secure.ANDROID_ID) and
  `isRooted` are written onto **every** Hard Mode challenge doc (`isRooted` on both true AND false →
  full coverage) so `detectSuspiciousUsers` can risk-score shared-device / rooted patterns. Null on
  Soft Mode. See `docs/10_security_and_anticheat.md`.
- Hard Mode creation is **NOT blocked** for rooted devices — warn + log only.

---

## Known Issues (Hard Mode / Stripe)

1. ~~**Stripe Connect for automatic payouts not implemented.**~~ PARTIALLY RESOLVED — Custom Connected Account with IBAN direct setup is implemented. Prize transfers are currently initiated **manually by the founder via Stripe Dashboard**. Full automatic transfer via API is planned post-launch. `claimPendingPayouts` CF retained as fallback for manual retry.

   **Stripe Connect (Custom Account):** IBAN collection + Connected Account creation is implemented. Prize transfers are currently initiated manually by the founder via Stripe Dashboard. Full automatic transfer via API is planned post-launch.

2. ~~**`completeGroupChallenge` Cloud Function not triggering automatically.**~~ RESOLVED — called by
   `DailyEvaluationWorker.evaluateGroupChallenge()` when `now >= endDate`.

---

## Fortschrittsbalken (Hard Mode)

Identical logic to Soft Mode — read from Room DailyLog always.

SESSION_LIMIT:  progress = consciousOpens / limitValueSessions
TIME_LIMIT:     progress = totalMinutes / limitValueMinutes
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

---

## Limit Reduction

Hard Mode supports mid-challenge limit reduction (same as Soft Mode).
Reduction is pending until midnight, never reversible, stored in Firestore as `pendingLimitValue`.
See docs/02_core_mechanics_and_soft_mode.md for full mechanics.

---

## Detail Screen Design (Hard Mode)

→ For UI/design specs see docs/08_ui_design_system.md

Same layout as Soft Mode with the following differences:

**Badge:** "HARD MODE" (orange — `#FFF0E8` bg, `#C05A00` text)
**Stats row:** Streak 🔥 | Tage noch (green)
*(Einsatz shown in info list below, not in stat row)*

**Info list additions:**
- "Einsatz" → "€X,XX"
- "Bei Erfolg" → "€X zurück (80%)" in `#00C853`

**Below info card:** "💳 Dein Geld ist sicher verwahrt bis zum Ende"

**"Challenge aufgeben":** text only, 14sp, `#FF3B30`, centered.
Mentions "€X wird eingezogen" — no button background (psychologically de-emphasized).

### Abandon (manual quit) — captures the stake (June 2026)

Abandoning is a **LOSS**, so for a **solo Hard Mode** challenge with a live pre-auth the stake is now
captured before the challenge is marked FAILED (previously it set FAILED but never captured — the ≤7-day
manual pre-auth then expired uncaptured and the stake was never taken).

- **`ActiveChallengeViewModel.abandonChallenge()` captures only when** `mode==HARD &&
  groupChallengeId==null && stripePaymentIntentId!=null`. It calls `capturePayment` (idempotent) and only
  inside `.onSuccess` does `markFailedAndFinish()` flip `status=FAILED` + signal navigation.
  `.onFailure` leaves the challenge **ACTIVE** and shows an error — **never** mark FAILED without a
  confirmed capture.
- **Soft Mode / group / no-PI** abandon behaves as before (no capture). Group stakes are settled by the
  `completeGroupChallenge` prize-pool flow, never direct-captured on abandon.
- `AbandonState` (Idle/Loading/Error) drives a blocking loading overlay + an error dialog during capture.
- After FAILED, abandon returns to the Dashboard, where the unified RED `ChallengeFailedDialog` surfaces
  (same dialog as the worker/permission loss paths).

---

## Redemption Challenge

When a Hard Mode Solo challenge fails (`status=failed`, `mode=hard`, `groupChallengeId IS NULL`),
`DailyEvaluationWorker.setRedemptionInfo()` is called, which:

- Sets `redemptionEligible=1` on the original challenge
- Sets `redemptionDeadline = now + 3 days` (Stripe 90-day refund window; 28-day cap on original duration)
- Sets `redemptionShowAfter = now + 24 hours`
- Computes `redemptionRefundAmount = floor(amountCents * 0.60)`
- Stores `redemptionDays = originalDays * 2` and halved `redemptionLimit`
- Schedules `RedemptionNotificationWorker` with a 24-hour `setInitialDelay`

**Eligibility guard:** `originalDays <= 28 && stripePaymentIntentId != null && !challenge.isRedemption`

**Redemption challenge parameters (created in HistoryViewModel.startRedemption()):**
- Duration: `originalDays * 2`
- Limit: `floor(original / 2)`, min 1 session or 5 min, stored in `redemptionLimit`
- `amountCents = 0` (no new payment required)
- `isRedemption = 1`, `originalChallengeId`, `originalPaymentIntentId`, `refundAmountCents`

**On completion:** `DailyEvaluationWorker` calls `cancelOrRefundPayment` with
`partialRefundCents = refundAmountCents` (60% of original) against `originalPaymentIntentId`.
The Cloud Function **re-validates server-side** (June 2026 — `isRedemption`, idempotency,
`originalPaymentIntentId` match, server-clock `endDate`) and **recomputes the 60% from the original
challenge's stored `amountCents`** before `stripe.refunds.create({ payment_intent, amount })` (PI was
already captured on original fail). The client-supplied `partialRefundCents` is discarded.

**On failure:** No refund. The staked money is lost permanently.

**UI surfaces:**
- **Dashboard:** `RedemptionBanner` composable — orange card, session-dismissable via `dismissRedemptionBanner()`
- **History:** "Comeback Challenge starten" button visible on eligible `SoloHistoryRow` entries
- **Confirmation:** `RedemptionConfirmSheet` (ModalBottomSheet) — shows duration, limit, win/lose amounts, warning
- After confirming: navigate back to Dashboard via `onRedemptionStarted` callback
