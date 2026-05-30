# 03 — Hard Mode & Stripe
> **Scope:** Hard Mode rules, Stripe payment flow (pre-auth → capture on fail → refund on success), app lockout during Hard Mode, Emergency Unlock, device binding.
> **When to load:** Any work on Hard Mode, Stripe integration, `PaymentRepository`, Cloud Functions for payments, or the Hard Mode lockout overlay.

---

## Hard Mode Rules

| Rule | Value |
|------|-------|
| Minimum duration | **7 days** production (1 day DEBUG) — enforced in `CreateChallengeUseCase` |
| Maximum duration | **90 days** |
| Minimum stake | **€5** |
| Maximum stake | **€100** |
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
    // auth check (Bearer token → verifiedUserId) ...
    const { paymentIntentId, challengeId, amountCents, partialRefundCents } = req.body
    const pi = await stripe.paymentIntents.retrieve(paymentIntentId)

    // ── Server-side validation (solo Hard Mode win / non-redemption path) ──────
    // Redemption branch (partialRefundCents > 0) is validated by its own stored
    // refundAmountCents against the original PI and is left intact.
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
| Challenge type | User gets back | App keeps |
|---------------|---------------|-----------|
| Hard Mode Solo win | `floor(amountCents × 0.80)` | 20% |
| Redemption Challenge win | `floor(originalAmountCents × 0.60)` | 40% |
| Group Challenge win (losers exist) | `floor(stake × 0.80)` + prize share | 20% of own stake |
| Group Challenge win (nobody failed) | 100% of stake | 0% |
| Group prize pool | `totalCapturedFromLosers - floor(total × 0.10)` / winners | 10% of losers' pot |

**Rule:** Always use `Math.floor` / `floor()` — never round up (avoid overpayment).

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

## Rooted Device (Hard Mode)

- RootBeer check runs before Hard Mode payment initiation (in `ChallengeCreationViewModel`).
- Non-blocking warning dialog: "Verstanden — trotzdem fortfahren" / "Abbrechen".
- Root status logged to `users/{uid}/deviceInfo/security` for admin visibility.
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
The Cloud Function uses `stripe.refunds.create({ payment_intent, amount: partialRefundCents })` (PI was already captured on original fail).

**On failure:** No refund. The staked money is lost permanently.

**UI surfaces:**
- **Dashboard:** `RedemptionBanner` composable — orange card, session-dismissable via `dismissRedemptionBanner()`
- **History:** "Comeback Challenge starten" button visible on eligible `SoloHistoryRow` entries
- **Confirmation:** `RedemptionConfirmSheet` (ModalBottomSheet) — shows duration, limit, win/lose amounts, warning
- After confirming: navigate back to Dashboard via `onRedemptionStarted` callback
