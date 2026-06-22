# 10 — Security & Anti-Cheat
> **Scope:** SQLCipher DB encryption, server-side money authority, Anti-Cheat detection, ban system, the irreducible cheat residual.
> **When to load:** Any work on `DatabaseKeyManager`, the encrypted Room DB, `cancelOrRefundPayment`/`capturePayment` validation, `dailyLogs` rules, `detectSuspiciousUsers`, `setUserBanStatus`, or `AccountDisabledScreen`.
> _Last verified: 2026-06-22 (commit e287b79)_

---

## Threat Model (what this defends against)

Real money is staked in Hard Mode, so the adversary is a **user trying to cheat their own
challenge** for a wrongful refund (not an external attacker). The two main cheat surfaces:
1. **Offline DB tampering** on a rooted device — editing local Room state to fake a clean win.
2. **Trusting the client** for the win/loss decision and the refund amount.

Defenses below raise the bar on both. One residual remains (the "suppress-gap") — documented at the
bottom; it is covered by the permission-violation backstop, not closed.

---

## 1. SQLCipher — Room encrypted at rest

The Room DB is AES-256 encrypted via **`net.zetetic:sqlcipher-android:4.6.1`** (+
`androidx.sqlite:sqlite:2.4.0`). This is the **current** artifact — the old
`net.zetetic:android-database-sqlcipher` is deprecated. Native AES `.so` per ABI, **no Google Play
Services dependency → Huawei-safe**. APK grows ~4–6 MB universal (~1.5–2 MB/device with ABI splits).

### `DatabaseKeyManager` (`data/local/db`)
- Generates a random **32-byte** passphrase.
- AES/GCM-encrypts it with a key in the **Android Keystore** (`AndroidKeyStore`, alias
  `detox_db_key`, hardware-backed where available — AOSP, works without GMS).
- Stores **only the encrypted** passphrase + IV in the `detox_db_security` prefs. The wrapping key
  never leaves secure hardware.
- The key is intentionally **NOT** `setUserAuthenticationRequired(true)` — so background workers can
  open the DB without a screen unlock, and lock-screen/biometric changes don't invalidate it.
- **NEVER hardcode the passphrase.**

### `DatabaseModule` wiring
Loads `libsqlcipher` and builds Room with
`SupportOpenHelperFactory(passphrase, null, clearPassphrase = false)`. Existing migrations are
unchanged (they run inside the encrypted DB).

### Existing-user migration — Option A (drop + resync), NOT in-place
On first encrypted launch (`db_encrypted_v1` flag unset) the old **unencrypted** `detox_database` is
deleted and repopulated from Firestore (the source of truth). Option A was chosen over an in-place
`sqlcipher_export` migration because untested raw-crypto migration on a live payments DB risks
corrupting **every** existing user — Option A cannot corrupt (worst case: re-sync). No money/active
state is ever at risk (active challenges always resync).

### History-restore path (makes the drop non-lossy)
`syncUserData()` previously resynced **only `status == "active"`** challenges, and `HistoryViewModel`
reads finished (completed/failed) challenges **only from Room** — so clearing Room (logout
`clearAllTables()` OR the SQLCipher plaintext drop) wiped the History screen.
- **`FirestoreService.fetchFinishedChallenges(userId)`** — `whereIn("status", ["completed",
  "failed"])`. Deliberately a **separate** method from `fetchActiveChallenges` with a **duplicated**
  parser, so the money-critical active-sync path is byte-for-byte untouched.
- **`SyncRepositoryImpl.syncUserData()` step 2b (additive)** — after the active sync, fetches finished
  challenges and, **only when absent in Room** (immutable → never `REPLACE`, which would
  CASCADE-delete daily logs), inserts the challenge then its nested daily logs (FK order). Wrapped in
  its own try/catch so a failure here can never break the rest of the sync.
- Effect: History survives logout/login and the encryption upgrade; eventually-consistent if offline
  at the moment Room is cleared.

### Graceful Keystore-invalidation fallback
If the wrapping key is ever lost/invalidated, `DatabaseKeyManager` regenerates the passphrase and
signals `wasReset`; `DatabaseModule` then drops the now-unreadable encrypted DB and lets sync
repopulate it — **the app never crashes on a lost key.**

### ProGuard
`-keep class net.zetetic.** { *; }` + `-keep class androidx.sqlite.** { *; }` +
`-dontwarn net.zetetic.**` so R8 doesn't strip the JNI-referenced SQLCipher classes in release.

---

## 2. Server-side Money Authority

The win/loss decision originally lived entirely client-side (`DailyEvaluationWorker`), so a cheater
editing local Room state could force a wrongful 80% refund; `capturePayment` and the redemption
refund branch also had no server validation. All money decisions are now validated server-side.
**Deployed** (`firebase deploy --only functions,firestore:rules`).

> The earlier **clock-forward** hardening (server-clock `endDate`, `payoutStatus` idempotency, PI
> binding, 80% recomputed from stored stake) is documented in
> `docs/03_hard_mode_and_stripe.md` / `docs/09_payout_and_fees.md`. The fixes below are layered on top.

- **Fix 1 — `cancelOrRefundPayment` win-gate (non-redemption path, FAIL-OPEN).** Before issuing the
  80% win-refund, the CF reads `users/{uid}/challenges/{cid}/dailyLogs` and **denies the refund only
  if it positively sees `limitExceeded === true` on any day** (a violation day means the stake should
  have been captured, not refunded). **Absence of logs never denies** — sync is best-effort, so a
  legitimate winner (who never has an exceeded day) is never wrongly refused. Catches the **Room-only**
  tamper path.
- **Fix 2 — `capturePayment` IDOR guard.** The CF retrieves the PaymentIntent and returns **403** when
  `metadata.userId` ≠ the authenticated caller. `createPaymentIntent` stamps `metadata.userId` on
  every PI. All three callers (`DailyEvaluationWorker`, Emergency Unlock in `OverlayManager`,
  `PermissionCheckWorker`) only pass the caller's own PI. Legacy PIs without the field fall through.
- **Fix 3 — `cancelOrRefundPayment` redemption branch now FULLY server-validated.** Previously it
  refunded the client-supplied `partialRefundCents` with **zero validation**. Now it re-fetches the
  redemption challenge and requires: `isRedemption === true`, `payoutStatus !== "refunded"`
  (idempotency), `originalPaymentIntentId` matches the supplied PI, server-clock `endDate` passed, and
  **recomputes the 60% from the *original* challenge's stored `amountCents`** — the client value is
  discarded.
- **`onChallengeDeleted` CF** (Firestore `onDelete` trigger on
  `users/{userId}/challenges/{challengeId}`). Cascade-deletes the challenge's nested `dailyLogs`
  sub-collection (Admin SDK, batched 400/commit). Required so the `dailyLogs` rules can block ALL
  client deletes. Also fixes a pre-existing bug where per-challenge `deleteChallenge` orphaned its
  `dailyLogs` (Firestore does not cascade sub-collections).
- **`dailyLogs` rules hardening (nested path).** `update` may **never flip `limitExceeded`
  true → false** (false → true still allowed so the worker can record a violation), and `delete` is
  **CF-only** (`allow delete: if false`). Stops a cheater deleting/rewriting an exceeded-day log to
  dodge the Fix 1 win-gate.
  - **Note:** the *flat* `users/{uid}/dailyLogs/{cid}_{date}` path is intentionally left
    client-writable — it carries `consciousOpens`/budget for client restore and does **not** hold
    `limitExceeded`, so it does not feed the server gate.

**Files:** `functions/src/index.ts`, `firestore.rules`. **Never reverse these guards.**

---

## 3. Anti-Cheat Detection System (admin flagging)

A **flagging-only** system that surfaces suspicious users for **manual admin review**.
**It NEVER auto-bans** — money is involved, so a human always decides.

### Part 1 — Data captured for detection
- **On every Hard Mode challenge:** `deviceId` (`Settings.Secure.ANDROID_ID`) + `isRooted` (RootBeer).
  Null on Soft Mode. `isRooted` written on **both** true AND false → full coverage. Threaded through
  `CreateChallengeUseCase` → `ChallengeCreationViewModel.onPaymentConfirmed` → the single
  `saveChallenge` `toMap()` write (NOT a post-create merge — `createChallenge` syncs fire-and-forget,
  so a merge write would race and be overwritten with nulls).
- **On every Group join:** `confirmGroupJoin` reads an optional `deviceId` from the body and adds it
  to the `participants` arrayUnion entry (group participation lives in `groupChallenges.participants`,
  not a `challenges` doc, so detection reads device IDs from **both** sources).

### Part 2 — `detectSuspiciousUsers` Cloud Function
`onRequest` + `requireAdmin`. **READ-ONLY — never bans, modifies, or deletes user data.** Computes an
**additive** risk score per user from 6 signals, returns flagged users sorted by `riskScore` desc:

| Signal | Points | Condition |
|--------|--------|-----------|
| Shared IBAN | **40** | 2+ users share the same `payoutIban` |
| Shared deviceId | **40** | 2+ users share an Android deviceId (solo challenges + group participants) |
| Rooted device | **25** | any challenge with `isRooted === true` |
| Perfect win | **20** | completed solo Hard Mode, ≥ 3 daily logs, ALL `consciousOpens === 0` AND `totalMinutes === 0` |
| Instant win | **15** | completed solo Hard Mode in < 1 day actual elapsed time (`payoutDate`/`endDate` − `startDate`) |
| Reconciliation low-evidence | **6** | challenge has `reconciliationLowEvidence === true` — the server reconciliation net refunded a WIN despite **zero** nested `dailyLogs` (could not confirm a clean day). Soft signal: the money was already returned (favour-user policy), but it surfaces for review (`type: "reconciliation_low_evidence"`, index.ts:2302). |

Response: `{ success, flaggedCount, flagged: [{ userId, username, email, riskScore, signals:
[{type, description, points}], sharedWith: [userIds], reviewed: {decision, reviewedAt, note}|null }] }`.
Reads `antiCheatReviews` to attach prior review decisions. **Cost note in code:** unbounded scans
(all users + collectionGroup challenges + all groups + per-completed-challenge dailyLogs);
admin-gated, manual trigger only.

### Part 3 — Review storage (`antiCheatReviews/{userId}`)
`{ decision: "cleared" | "banned", reviewedAt, reviewedBy, note }`. Cleared false positives are
**remembered** and shown greyed-out ("bereits geprüft (OK)") in future analyses — not re-flagged as
new. Banned users show "gesperrt". Reviewed + acted on from the admin "🛡️ Anti-Cheat" tab
(see `docs/11_admin_dashboard.md`). Firestore rule: admin-email read/write only.

### Indexes
Collection-group field override for `challenges.deviceId` (COLLECTION + COLLECTION_GROUP) +
single-field index for `users.payoutIban`. (The CF currently groups in-memory like `backfillCounters`;
the indexes support a future where-query approach and honor the deploy spec.)

### CRITICAL RULES (never reverse)
- **FLAGGING ONLY** — `detectSuspiciousUsers` never auto-bans and never writes user data; a human
  always reviews.
- Risk score is **additive**.
- Cleared false positives are remembered in `antiCheatReviews` and not re-flagged.
- `deviceId` + `isRooted` stored on every Hard Mode challenge (deviceId also on group joins).

### Privacy (DSGVO)
`deviceId` (ANDROID_ID) is collected for **fraud prevention** — already legally covered in the
Datenschutzerklärung under "Gerätedaten / Betrugsschutz, Art. 6 Abs. 1 lit. f DSGVO" (berechtigtes
Interesse). No new consent flow required.

---

## 4. Ban System

Two enforcement layers via the CF **`setUserBanStatus`** (`onRequest`, admin-email auth):
- **Layer 2 (hard):** `admin.auth().updateUser(uid, { disabled })` — blocks token refresh / sign-in.
- **Layer 1 (instant):** Firestore `users/{uid}.{disabled, disabledReason, disabledAt}` — enforced at
  app startup. A banned user with an active Hard Mode stake is **NOT** auto-refunded — existing
  capture rules apply.

**App enforcement:**
- **`MainActivity` startup ban gate:** after the AppConfig (force-update/maintenance) checks and
  before normal navigation, reads `users/{uid}.disabled` via `FirestoreService.isUserDisabled`
  (**fail-open** — read error → not blocked; the Auth-disable is the hard backstop).
- **`AccountDisabledScreen`** (`presentation/screens/system/`, route `account_disabled`): 🚫 red,
  `BackHandler` blocks back, shows `disabledReason` or default, "Support kontaktieren" → mailto.
  Backed by `AccountDisabledViewModel`.

**Firestore rules:** the owner self-update **blocks** `disabled`/`disabledReason`/`disabledAt` (so a
banned user cannot self-unban within their 1h token window); only the CF (admin) writes them.

---

## 5. The Irreducible Residual — "suppress-gap"

**DECISION — known residual:** there is no server-side source of app-usage truth, so a cheater who
*suppresses* the violation write (stays offline / disables the worker) still looks like a clean win to
`cancelOrRefundPayment`'s fail-open win-gate. This is accepted, not closed.

**Backstop:** the permission-violation capture path (`checkPermissionViolations`, 1h usage path / 24h
permission path) captures the stake server-side when enforcement is disabled — independent of app
state. See `docs/05_huawei_and_permissions.md`. Anti-cheat flagging (perfect/instant win, shared
device/IBAN) catches the statistical fingerprint of suppression for manual review.

**Went-dark heartbeat (June 2026) — closes the uninstall case of the suppress-gap.** The largest
form of suppression — uninstalling the app entirely, after which no `dailyLogs`/permission markers
are ever written and the old reconciliation net auto-refunded a WIN — is now a LOSS. `PermissionCheckWorker`
merges `lastSeenAt` every cycle (gated on ≥1 active HARD challenge); `runDueChallengeReconciliation`
forfeits any active hard challenge whose heartbeat is staler than `config/app.wentDarkGraceMs` (fail-safe
MAX = never forfeit; ships dark behind `reconciliationEnabled`/`reconciliationDryRun` + a positive
grace). `failReason:"device_dark"`. See `docs/00`, `docs/03`.
**Residual A (timing):** went-dark is GRACE-delayed, so a device that goes dark in the **back half of a
5–7 day ≤7d challenge** can still beat the ~7d Stripe manual-auth expiry and escape capture; GRACE is
bounded below by the Huawei false-forfeit risk (EMUI throttles the heartbeat worker for hours/days even
when the app is installed), so it cannot be made arbitrarily tight. Accepted, not closed.
**Residual B (owner-writable `lastSeenAt`):** the heartbeat lives in the user's own
`permissionStatus/current` sub-document, so it is owner-writable by design — there is no rule that only
a CF may set it. A sufficiently determined cheater could forge a fresh `lastSeenAt` to look "alive". But
to keep beating honestly they must keep the **real** app installed and running (which means real
enforcement + real `dailyLogs`), and forging the field requires reverse-engineering the doc path rather
than just deleting the app — so it strictly raises the bar over the old delete-and-win path. Accepted,
not closed.
