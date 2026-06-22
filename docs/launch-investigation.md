# Launch Investigation — Consolidated Findings

> **Scope:** Read-only investigation of 7 launch-readiness items. No code changes, no deploys,
> no Firestore/Stripe writes were made while producing this document.
> **Date:** 2026-06-18 · **Branch examined:** `fix/worker-limit-detection-sessions-budget`
> **Format per item:** current behavior (file:line) → gap/risk → recommended *minimal* approach.

---

## 1. payoutRequests create rule — `status` key (was BLOCKER, now RESOLVED)

**Current behavior**
- Rule: [firestore.rules:198-202](../firestore.rules#L198).
- Client writers — both send `status: "pending"` in the initial create payload:
  - [ProfileViewModel.kt:286](../app/src/main/java/com/detox/app/presentation/screens/profile/ProfileViewModel.kt#L286) — batch `.set`, keys: `userId, displayName, payoutName, iban, amountCents, groupId, createdAt, status`.
  - [GroupChallengeDetailViewModel.kt:406](../app/src/main/java/com/detox/app/presentation/screens/groupchallenge/detail/GroupChallengeDetailViewModel.kt#L406) — `.add`, same 8 keys.

**Gap / risk (as originally found)**
- The previous rule was `!request.resource.data.keys().hasAny(['status', 'paidAt'])`. Because both
  writers include `status`, `hasAny([...])` was always `true`, so the create condition was always
  `false` → **every payout request creation hit `PERMISSION_DENIED`**. Payouts were uncreatable.
  ProfileViewModel surfaced a generic error toast; GroupChallengeDetailViewModel swallowed it in
  `runCatching{}.onFailure{}` (Timber-only) — i.e. **silent** in the group path.

**Status: FIXED this session.** The rule now allows `status` only when it equals `"pending"`, and
still blocks `paidAt` entirely:
```
allow create: if request.auth != null
  && request.resource.data.userId == request.auth.uid
  && (!request.resource.data.keys().hasAny(['paidAt']))
  && (!request.resource.data.keys().hasAny(['status'])
      || request.resource.data.status == "pending");
```
Committed as `77ff177` and deployed to `detox-33208` (rules deploy succeeded earlier this session).

**Recommended minimal approach** — none further required. Optional hardening (not a blocker):
constrain `amountCents` to a positive int and `userId`/`iban`/`groupId` to strings in the same
create rule to block malformed/forged payloads. Defer unless desired pre-launch.

---

## 2. `reconciliationLowEvidence` — written but never read

**Current behavior**
- Written by the reconciliation net on a WIN with zero nested dailyLogs:
  [functions/src/index.ts:1857](../functions/src/index.ts#L1857) (`lowEvidence = logsSnap.size === 0`)
  and [index.ts:1893](../functions/src/index.ts#L1893) (`update["reconciliationLowEvidence"] = true`).
  The refund still proceeds (favour-the-user); the flag is the only residual signal for admin review.
- The Anti-Cheat surface is `detectSuspiciousUsers` ([index.ts:2150-2358](../functions/src/index.ts#L2150)).
  It computes 5 signals only: shared IBAN, shared device, rooted, perfect win, instant win
  ([index.ts:2156-2162](../functions/src/index.ts#L2156)). Per-challenge it reads `deviceId`,
  `isRooted`, `mode`, `groupChallengeId`, `isRedemption`, `status`, `payoutStatus`, `startDate`,
  `endDate`, `payoutDate` and the nested `dailyLogs` ([index.ts:2238-2266](../functions/src/index.ts#L2238)).
  It **never reads `reconciliationLowEvidence`**. The admin tab renders exactly what this function returns.

**Gap / risk**
- A low-evidence auto-refund (a plausible suppression-gap cheat: user wins with no usage logs at all)
  raises no Anti-Cheat signal. The flag is dead data — visible only to someone querying Firestore directly.

**Recommended minimal approach**
- Add a 6th signal inside the existing per-challenge scan loop (already iterating every challenge at
  [index.ts:2238](../functions/src/index.ts#L2238)): when `c.reconciliationLowEvidence === true`,
  `pushUnique(uid, { type: "low_evidence_win", ..., points: <small, e.g. 15> })`. Zero new queries,
  zero schema changes — it rides the existing `collectionGroup("challenges")` read and flows straight
  into the admin tab via the existing `flagged` response. No client/admin-UI change required (it
  renders generic signals already).

**UPDATE 2026-06-18: RESOLVED (#4).** `detectSuspiciousUsers` now adds a 6th signal
`type:"reconciliation_low_evidence"` (6 pts) on `c.reconciliationLowEvidence === true`
([index.ts:2302](../functions/src/index.ts#L2302)) — rides the existing `collectionGroup("challenges")`
scan as recommended. See `docs/10` and the changelog `2026-06-18` entry.

---

## 3. Group settlement gap — no server-side timer

**Current behavior — how groups settle today (all device-triggered):**
- `completeGroupChallenge` is an `onRequest` CF ([index.ts:1040](../functions/src/index.ts#L1040))
  that no-ops unless `endDate` reached or all participants failed ([index.ts:1079](../functions/src/index.ts#L1079)).
- It is invoked ONLY from device code:
  - [MainActivity.kt:284](../app/src/main/java/com/detox/app/MainActivity.kt#L284) (app open)
  - [DailyEvaluationWorker.kt:861](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L861) (endDate reached)
  - [PermissionCheckWorker.kt:377](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L377)
  - [ProfileViewModel.kt:786](../app/src/main/java/com/detox/app/presentation/screens/profile/ProfileViewModel.kt#L786) (admin debug button)
- `expireGroupChallenge` (waiting-room auth expiry) is likewise device-triggered
  ([DailyEvaluationWorker.kt:714](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L714)).
- The server reconciliation net is **solo-only**: `runDueChallengeReconciliation` queries
  `collectionGroup("challenges")` filtered `mode=="hard" && status=="active" && endDate<=now`
  ([index.ts:1684-1689](../functions/src/index.ts#L1684)) — i.e. `users/{uid}/challenges`, never the
  top-level `groupChallenges` collection. `scheduledChallengeReconciliation`
  ([index.ts:1911](../functions/src/index.ts#L1911)) only drives that solo sweep.

**Gap / risk**
- If no participant's device opens the app / runs WorkManager after `endDate`, a group challenge
  **never settles**: winners aren't refunded/paid out and losers' stakes aren't captured. There is no
  server-side backstop for groups (unlike solo). On Huawei (no Play Services, aggressive task-killing)
  this is a realistic stranded-money path.

**What a fix would touch (outline only):**
- *Option A — server-side group settlement (preferred long-term):* add a scheduled twin, e.g.
  `scheduledGroupSettlement` (pubsub, mirror [index.ts:1911](../functions/src/index.ts#L1911)) that
  queries `groupChallenges` where `status` active/started and `endDate<=now`, then runs the existing
  `completeGroupChallenge` settlement body. Requires refactoring the settlement core out of the
  `onRequest` handler into a shared function + a `groupChallenges` composite index on
  (`status`, `endDate`). Idempotency already exists (`already completed` guard at
  [index.ts:1061](../functions/src/index.ts#L1061)), so overlap with device triggers is safe.
- *Option B — launch flag `groupChallengeEnabled=false` (preferred for launch):* gate group-challenge
  creation/entry behind a remote-config flag in `config/app` (see `AppConfigRepository`, pattern in
  `docs/13`). Touches: the config flag read, the create/join UI entry points, and rules on
  `groupChallenges` create. Ships launch without the stranded-money risk; defer Option A post-launch.
- Recommendation: **B for launch, A as the follow-up** before re-enabling groups.

**UPDATE 2026-06-18: RESOLVED for launch (#6, Option B).** Groups ship OFF via
`config/app.groupChallengeEnabled = false` (the code fallback stays fail-open `true`). Option A
(server-side group settlement) remains the post-launch follow-up before re-enabling. See `docs/04`,
`docs/13`, and the changelog `2026-06-18` entry.

---

## 4. Loss audit-trail — device deletes the doc on FAILED

**Current behavior**
- `updateChallengeStatus(FAILED)` keeps the Room row at `failed` (History) but **deletes the Firestore
  doc**: [ChallengeRepositoryImpl.kt:193-195](../app/src/main/java/com/detox/app/data/repository/ChallengeRepositoryImpl.kt#L193)
  → `firestoreService.deleteChallenge(uid, id)` ([FirestoreService.kt:234](../app/src/main/java/com/detox/app/data/remote/firebase/FirestoreService.kt#L234)).
- The doc delete cascades the nested `dailyLogs` via `onChallengeDeleted`
  ([index.ts:1997](../functions/src/index.ts#L1997)).
- Call sites that drive a loss → FAILED (each triggers the delete):
  - [DailyEvaluationWorker.kt:564](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L564) (SESSIONS/budget loss; also the FAILED branches at :196, :418, :649)
  - [PermissionCheckWorker.kt:302](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L302) (permission/usage violation)
  - [OverlayManager.kt:1591](../app/src/main/java/com/detox/app/service/OverlayManager.kt#L1591) (in-overlay loss)
  - [ActiveChallengeViewModel.kt:272](../app/src/main/java/com/detox/app/presentation/screens/activechallenge/ActiveChallengeViewModel.kt#L272) (abandon)

**What depends on the deletion (why it exists):** `fetchActiveChallenges` filters `status=="active"`,
and Firestore rules block client `status` updates — so a non-deleted loss doc would stay `"active"` and
be re-inserted into Room on every sync/restart. Deleting is the current mechanism to stop the zombie
re-insert. The cascade also lets `dailyLogs` keep `allow delete: if false` for clients
([index.ts:1994-1996](../functions/src/index.ts#L1994) comment).

**Gap / risk**
- Losses leave **no server-side record**: doc + tamper-evident `dailyLogs` are destroyed. This blunts
  reconciliation/anti-cheat (the very `limitExceeded` logs the net relies on at
  [index.ts:1779](../functions/src/index.ts#L1779) are gone), erases the audit trail for disputes, and
  makes counters the only trace of a failed challenge.

**Recommended minimal approach (to RETAIN losses)**
- Stop deleting on FAILED; instead have the device write a terminal `status:"failed"` + `payoutStatus`
  to the doc, and change `fetchActiveChallenges` to filter on `status=="active"` server-side (already
  does) so failed docs simply aren't re-inserted. This requires a **rules change** to permit a client
  `active→failed` transition *only* alongside a confirmed `payoutStatus` (mirroring the money-authority
  rules in `docs/10`) — without it, the client can't write `status`, which is the original reason for
  the delete. Net touches: challenges update rule (firestore.rules), `ChallengeRepositoryImpl.updateChallengeStatus`
  (write status instead of delete), and keep `onChallengeDeleted` only for genuine user-initiated deletes.
  This is a non-trivial money-rules change — recommend scoping as its own task, not a launch hotfix.

**UPDATE 2026-06-18: RESOLVED (#7).** Losses are now RETAINED. Instead of a rules change, a new
**`markChallengeFailed`** CF ([index.ts:291](../functions/src/index.ts#L291), `onRequest` + `requireAuth`)
writes `status:"failed"` + `failReason:"client_loss"` + `failedAt` in place via the Admin SDK;
`ChallengeRepositoryImpl.updateChallengeStatus(FAILED)` calls it instead of `deleteChallenge`, so the
doc + nested `dailyLogs` survive. Idempotent, IDOR-safe (caller's own subcollection), never touches
Stripe/`payoutStatus`. See `docs/03`, `docs/firestore-schema.md` Part 2, and the changelog `2026-06-18`
entry.

**UPDATE 2026-06-21: `failReason` now carries the real cause (supersedes the hardcoded `"client_loss"`
above).** `updateChallengeStatus(FAILED, failReason)` threads the actual loss cause —
`"limit_exceeded"` (`DailyEvaluationWorker` / soft-fail), `"abandon"` (abandon flow),
`"permission_violation"` (`PermissionCheckWorker`) — and `markChallengeFailed` writes the **passed**
value; `"client_loss"` is now only a no-cause fallback. A new Room `failReason` column (migration 25→26,
DB v27) persists it for the loss dialog. Separately, the reconciliation net gained a went-dark forfeit
branch (`failReason:"device_dark"`, gated on `config/app.wentDarkGraceMs`). See the changelog
`2026-06-21` entry, `docs/03`, `docs/05`, `docs/10 §5`, `docs/13`.

---

## 5. SoftFailResultScreen "Zur Startseite" button — present & committed ✅

**Current behavior**
- Button exists: [SoftFailResultScreen.kt:74-84](../app/src/main/java/com/detox/app/presentation/screens/softfail/SoftFailResultScreen.kt#L74)
  (`OutlinedButton(onClick = onHome)`, text `R.string.soft_fail_result_home`).
- String defined: [strings.xml:402](../app/src/main/res/values/strings.xml#L402) → `Zur Startseite`.
- The "dead button" fix wired the `onHome` callback in
  [MainScreen.kt](../app/src/main/java/com/detox/app/presentation/navigation/MainScreen.kt) — commit
  `2d9cc2c` ("fix: dead 'Zur Startseite' button on SoftFailResultScreen", 1 file, +1/-5).
- Working tree is **clean** for `SoftFailResultScreen.kt` (no uncommitted changes).

**Gap / risk:** none. Present, wired, committed, on the current branch.

---

## 6. perm-worker-fail-gate — FAILED set even if capture fails

**Current behavior**
- `failAllHardChallenges` ([PermissionCheckWorker.kt:268](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L268)):
  for each active HARD challenge with a PI it runs the server settlement guard
  ([:284-291](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L284)), then calls
  `capturePayment(...)` whose **failure is only logged** ([:297-299](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L297)),
  then **unconditionally** calls `updateChallengeStatus(FAILED)` ([:302](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L302)).
- There is an explicit `TODO(perm-worker-fail-gate)` at [:293-296](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L293)
  acknowledging this.

**Gap / risk**
- A transient `capturePayment` error still flips the challenge to FAILED → which (per item 4) **deletes
  the Firestore doc and cascades its dailyLogs**. Result: status says the user lost, but **the stake was
  never captured** and the audit trail is gone. This contradicts the abandon/worker money-safety
  invariant ("NEVER mark FAILED without the stake captured", CLAUDE.md §3). The server net can't recover
  it cleanly because the doc/logs are deleted.

**Recommended minimal approach**
- Gate the status flip on capture success, mirroring the worker/abandon pattern: only call
  `updateChallengeStatus(FAILED)` inside `capturePayment(...).onSuccess { ... }`; on `.onFailure`, log
  and **leave the challenge ACTIVE** so the next cycle / the server reconciliation+permission net retries.
  For PI-less challenges (`paymentIntentId == null`), the unconditional FAILED is fine (nothing to
  capture). One-function change in `PermissionCheckWorker.failAllHardChallenges`, no rules/CF change.

**UPDATE 2026-06-18: RESOLVED (#9).** `failAllHardChallenges` now calls `updateChallengeStatus(FAILED)`
only inside `capturePayment(...).onSuccess`; `.onFailure` logs and leaves the challenge ACTIVE
([PermissionCheckWorker.kt:300-309](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L300)).
The PI-less branch still marks FAILED directly. The `TODO(perm-worker-fail-gate)` marker is gone. Note
that — per #7 — a FAILED flip now retains the doc (markChallengeFailed), so the old "doc/logs gone"
consequence no longer applies. See the changelog `2026-06-18` entry.

---

## 7. Unmerged branches — inventory & safe merge order

| Branch | Commits ahead of main | Diff vs main | Relationship |
|---|---|---|---|
| `fix/worker-limit-detection-sessions-budget` (current, tracks origin) | **10** | 35 files, +2028/-1916 | **Superset** — see below |
| `fix/hard-mode-challenge-doc-gutted` | 5 | 22 files, +759/-1839 | **Fully subsumed** by current |
| `security/sqlcipher-db-encryption` | 0 | empty | Already in main (ancestor/merged) |
| `claude/loving-feynman-e612eb` | 0 | empty | No commits vs main (no-op) |

**Key relationship:** `fix/worker-limit-detection-sessions-budget` already CONTAINS every commit of
`fix/hard-mode-challenge-doc-gutted` (`69edd5a, 6f63eae, 74a5d6b, 525f3c2, 2d9cc2c`) plus:
- `a6b316b` — docs/CLAUDE.md pre-fix
- `78bd22f` — **"Limit-Fix"** (SESSIONS + TIME_BUDGET money-critical loss trigger in worker)
- `d7576ca` — **"500er branch"** (checkPermissionViolations 500 unblock: collection-group indexes + per-query try/catch)
- `b44805b` — server-side reconciliation safety net + money-critical guards
- `77ff177` — payoutRequests create rule fix (item 1)

So the "Limit-Fix" and the "500er" work are **not separate open branches** — they are commits already
inside the current branch.

**Safe merge order (read-only recommendation — NO merge performed):**
1. **Merge `fix/worker-limit-detection-sessions-budget` → `main`.** It is the superset and carries all
   launch fixes. (It is already pushed to `origin`, so a PR is the natural path.)
2. **`fix/hard-mode-challenge-doc-gutted`:** no merge needed — subsumed by step 1. Delete after step 1
   to avoid confusion.
3. **`security/sqlcipher-db-encryption`** and **`claude/loving-feynman-e612eb`:** no-ops vs main
   (0 commits). Delete if stale, or rebase first if either is meant to carry future work.

> Caveat before merging step 1: this branch deletes large legacy files (`ChallengeSetupScreen.kt`
> −854, `ChallengeSetupViewModel.kt` −515, `HardModeFailScreen.kt` −174) — confirm those are
> intentional removals (replaced by the dashboard dialog set) and that the build is green on the
> branch before opening the PR.

---

### Open items still requiring action (none deploy-blocking after item 1's fix)
- **Item 6 (perm-worker-fail-gate):** money-safety bug — recommend fixing before launch (small, contained).
- **Item 3 (group settlement):** recommend shipping `groupChallengeEnabled=false` for launch.
- **Item 4 (loss retention)** and **Item 2 (low-evidence signal):** post-launch hardening.
- **Item 7:** open the `fix/worker-limit-detection-sessions-budget → main` PR.

> **UPDATE 2026-06-18:** Items **2, 4, 6 are now RESOLVED** (low-evidence signal #4, loss retention via
> `markChallengeFailed` #7, capture-gate #9) and item **3 is RESOLVED for launch** via
> `groupChallengeEnabled=false` (#6; Option A still the post-launch follow-up). Remaining: item 3's
> Option A (server-side group settlement) before re-enabling groups, and item 7 (open the PR). See the
> per-item UPDATE notes above and the changelog `2026-06-18` entry.
