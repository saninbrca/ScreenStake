# Challenge Risk Inventory — what can endanger a participant's money

> **Scope:** Read-only investigation for the mandatory consent/disclosure flow shown before a user
> CREATES or JOINS a Hard Mode or Group Challenge. Enumerates every path that can take, withhold, or
> strand a participant's money, plus every path where the product cannot take money it should.
> **No code was changed. No deletions. No money path touched.**
> **Date:** 2026-07-29 · **Branch:** `main` @ `1e8f078` · **Method:** verified against current code only;
> prior audit notes (`launch-investigation.md`, `launch-readiness-audit.md`, changelog) were used as
> lead lists and independently re-checked.
>
> ⚠️ This file contains **no legal advice**. The disclosure recommendations are engineering input for
> counsel, not a compliance sign-off. See `docs/compliance.md` for the open legal questions.

---

## 0. Read this first — the two facts that frame everything

1. **Money is OFF in release builds.** `BuildConfig.MONEY_FEATURES_ENABLED = false`
   ([app/build.gradle.kts:69](../app/build.gradle.kts#L69)), and every money surface is gated on
   `FeatureFlags.moneyEnabled && <serverFlag>`
   ([FeatureFlags.kt:22-28](../app/src/main/java/com/detox/app/util/FeatureFlags.kt#L22)). Groups are
   additionally off server-side (`config/app.groupChallengeEnabled=false`, invariant #25). So nothing
   below is currently live — but every finding becomes live the moment that constant flips, which is
   exactly when the consent flow ships.
2. **Stripe is still on TEST keys** in the release build
   ([app/build.gradle.kts:64](../app/build.gradle.kts#L64)). Unchanged since the 2026-06-18 audit.

---

## 1. Findings table

Severity: **money-loss** (participant loses money they shouldn't) · **fairness** (participant cannot
lose / another participant is funded unfairly) · **stuck-funds** (money owed but not delivered) ·
**stalled** (challenge never happens) · **cosmetic**.

User-facing: **prominent** = must be on the payment screen · **link** = behind a "details" link ·
**no** = internal only.

| ID | Title | Cat | Severity | User-facing? | Status | Evidence |
|---|---|---|---|---|---|---|
| A1 | UsageStats daily buckets are summed, not clipped to the day | A | money-loss | prominent (as measurement tolerance) | **OPEN** | [UsageStatsRepositoryImpl.kt:152-168](../app/src/main/java/com/detox/app/data/repository/UsageStatsRepositoryImpl.kt#L152), [:194-212](../app/src/main/java/com/detox/app/data/repository/UsageStatsRepositoryImpl.kt#L194) |
| A2 | One bad day loses the whole multi-day challenge | A | money-loss | **prominent** | working as designed | [DailyEvaluationWorker.kt:860-875](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L860), [index.ts:2884](../functions/src/index.ts#L2884) · fixed by `33b5fea` |
| A3 | >7-day Hard Mode charges the full stake **at creation**, not on breach | A | money-loss | **prominent** | **OPEN (undisclosed)** | [index.ts:192](../functions/src/index.ts#L192), [ChallengeCreationViewModel.kt:829](../app/src/main/java/com/detox/app/presentation/screens/challengecreation/ChallengeCreationViewModel.kt#L829) |
| A4 | Permission-loss deadline can shrink from 24 h to ~12 h | A | money-loss | **prominent** | **OPEN (undisclosed)** | [PermissionCheckWorker.kt:334-343](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L334) |
| A5 | Accessibility off + tracked app used → capture after **1 hour** | A | money-loss | **prominent** | **OPEN (undisclosed)** | [PermissionCheckWorker.kt:254-307](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L254), [index.ts:2605](../functions/src/index.ts#L2605), [:2623](../functions/src/index.ts#L2623) |
| A6 | Went-dark forfeit (uninstall/disable) — armable by server config alone | A | money-loss | **prominent** | ships disarmed, arms without an app update | [index.ts:2730-2747](../functions/src/index.ts#L2730), [:2861-2891](../functions/src/index.ts#L2861) |
| A7 | `OverlayManager.captureAndLock` — fire-and-forget capture + unconditional loss log | A | money-loss | no | **dead code** (no call site) | [OverlayManager.kt:1343-1418](../app/src/main/java/com/detox/app/service/OverlayManager.kt#L1343) |
| A8 | Live block (`>=`) vs settlement (`>`) divergence | A | money-loss | link | **FIXED** `4e1b818` | [CheckDailyLimitUseCase.kt:57](../app/src/main/java/com/detox/app/domain/usecase/CheckDailyLimitUseCase.kt#L57) vs [DailyEvaluationWorker.kt:890](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L890) |
| A9 | Multi-app divergence (each app got its own full limit) | A | money-loss | link | **FIXED** `aaf552a` | [CheckDailyLimitUseCase.kt:30-46](../app/src/main/java/com/detox/app/domain/usecase/CheckDailyLimitUseCase.kt#L30) |
| A10 | Midnight rollover / settling on the wrong day | A | money-loss | no | **FIXED** `33b5fea` | [DateUtils.kt:47](../app/src/main/java/com/detox/app/util/DateUtils.kt#L47) (day-key compare), [:17](../app/src/main/java/com/detox/app/util/DateUtils.kt#L17) |
| A11 | FAILED written before a confirmed capture | A | money-loss | no | **FIXED** `2026-06-18` batch | [PermissionCheckWorker.kt:378-387](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L378), [DailyEvaluationWorker.kt:618-640](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L618), [ActiveChallengeViewModel.kt:274-288](../app/src/main/java/com/detox/app/presentation/screens/activechallenge/ActiveChallengeViewModel.kt#L274) |
| A12 | TIME_BUDGET overshoot between 10-s ticks can exceed the budget | A | money-loss | link | partial (inherent to polling) | [DailyEvaluationWorker.kt:352-353](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L352) |
| **B1** | **Group challenges never auto-fail — "give up" is the only loss path** | B | fairness | **prominent** | **OPEN, structural** | [DailyEvaluationWorker.kt:907-996](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L907) |
| **B2** | **Server-side group permission capture is dead code** | B | fairness | no | **OPEN since `3e00172`** | [index.ts:2570-2597](../functions/src/index.ts#L2570) vs [firestore.rules:212-244](../firestore.rules#L212) |
| **B3** | `PermissionCheckWorker` never fails a group participant | B | fairness | no | **OPEN** | [PermissionCheckWorker.kt:345-397](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L345) |
| **B4** | **Local "Challenge aufgeben" on a group row = free win** | B/E | money-loss (to co-players) | **prominent** | **OPEN** | [ActiveChallengeScreen.kt:921-934](../app/src/main/java/com/detox/app/presentation/screens/activechallenge/ActiveChallengeScreen.kt#L921) → [ActiveChallengeViewModel.kt:270-291](../app/src/main/java/com/detox/app/presentation/screens/activechallenge/ActiveChallengeViewModel.kt#L270) |
| B5 | TIME_WINDOW / website / adult-block challenges are structurally unloseable | B | fairness | link | **OPEN (by design?)** | [DailyEvaluationWorker.kt:901](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L901), [CheckDailyLimitUseCase.kt:77-81](../app/src/main/java/com/detox/app/domain/usecase/CheckDailyLimitUseCase.kt#L77) |
| B6 | SESSIONS is effectively unloseable by daily evaluation | B | fairness | link | acknowledged in code | [DailyEvaluationWorker.kt:558-572](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L558) |
| B7 | Client may still delete its own **active** Hard Mode challenge doc | B | fairness | no | **OPEN (stale rule)** | [firestore.rules:50](../firestore.rules#L50); `FirestoreService.deleteChallenge` is now dead ([:235](../app/src/main/java/com/detox/app/data/remote/firebase/FirestoreService.kt#L235)) |
| B8 | Second account on the same device is flagged, never blocked | B | fairness | link (privacy) | by design (invariant #11) | [index.ts:3260+](../functions/src/index.ts#L3260) |
| B9 | `dailyLogs` are client-writable; the win-gate reads them | B | fairness | no | **OPEN by design** | [firestore.rules:67-72](../firestore.rules#L67), [index.ts:380-383](../functions/src/index.ts#L380) |
| **C1** | **Prize transfers are executed manually by the founder** | C | stuck-funds | **prominent** | **OPEN** | [docs/09:356](09_payout_and_fees.md), [index.ts:2450-2468](../functions/src/index.ts#L2450) (Custom account, AT, transfers-only) |
| **C2** | **No server-side group settlement — endDate settles only if a device runs** | C | stuck-funds | **prominent** | **OPEN** (Option A never built) | `completeGroupChallenge` is `onRequest` only ([index.ts:1746](../functions/src/index.ts#L1746)); callers [MainActivity.kt:301](../app/src/main/java/com/detox/app/MainActivity.kt#L301), [DailyEvaluationWorker.kt:1011](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L1011), [PermissionCheckWorker.kt:460](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L460) |
| C3 | `refund_failed` / `payoutOwedCents` / `payoutIncomplete` have no reader | C | stuck-funds | link | **OPEN** | written [index.ts:751-767](../functions/src/index.ts#L751), [:1850-1862](../functions/src/index.ts#L1850); no CF, no admin tab, no retry reads them |
| **C4** | **Account deletion destroys consent proof and strands owed payouts** | C | stuck-funds | **prominent** | **OPEN** | [FirestoreService.kt:750-770](../app/src/main/java/com/detox/app/data/remote/firebase/FirestoreService.kt#L750); guard covers active challenges only ([SettingsViewModel.kt:296-315](../app/src/main/java/com/detox/app/presentation/screens/settings/SettingsViewModel.kt#L296)) |
| C5 | Unreleasable join hold is "parked" with only a log | C | stuck-funds | no | residual | [index.ts:1549-1552](../functions/src/index.ts#L1549) |
| C6 | Cancel-refund debt on an already-captured stake is conservative + manual | C | stuck-funds | no | residual by decision | [index.ts:687-737](../functions/src/index.ts#L687) |
| D1 | Start date past the card-hold window | D | stalled | link | **FIXED** `a1c9dd1` | [GroupStartWindow.kt](../app/src/main/java/com/detox/app/domain/model/GroupStartWindow.kt), picker [GroupChallengeCreateScreen.kt:705-719](../app/src/main/java/com/detox/app/presentation/screens/groupchallenge/create/GroupChallengeCreateScreen.kt#L705) |
| D2 | Auto-start still depends on the creator's phone in practice | D | stalled | **prominent** | **PARTIAL** `19a9f90` | server twin ships INERT ([index.ts:1623-1633](../functions/src/index.ts#L1623)); client worker is creator-only + 24 h ([GroupChallengeAutoStartWorker.kt:41-48](../app/src/main/java/com/detox/app/service/GroupChallengeAutoStartWorker.kt#L41)) |
| D3 | Fewer than 2 players at start → cancelled, all holds released | D | stalled | link | working as designed | [index.ts:1111-1127](../functions/src/index.ts#L1111) |
| D4 | Waiting-room expiry is device-triggered only | D | stalled | no | residual (Stripe expires holds anyway) | [DailyEvaluationWorker.kt:806-821](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L806) |
| **E1** | **`failAllHardChallenges` reaches group buy-ins — blocked only by accident** | E | money-loss | no | **latent / unguarded** | [GroupChallengeRepositoryImpl.kt:169-192](../app/src/main/java/com/detox/app/data/repository/GroupChallengeRepositoryImpl.kt#L169) creates `mode="hard"` + group PI; only [FirestoreService.kt:298](../app/src/main/java/com/detox/app/data/remote/firebase/FirestoreService.kt#L298) (`!snap.exists() → null → SKIP`) stops it |
| E2 | `claimPendingPayouts`: park-failure re-opens the claim after TTL | E | money-loss (to us) | no | known residual, logged | [index.ts:2268-2273](../functions/src/index.ts#L2268) |
| E3 | Fail-vs-settle photo finish | E | fairness | no | **handled** (invariant #28) | [index.ts:1350-1360](../functions/src/index.ts#L1350), [:1866-1873](../functions/src/index.ts#L1866) |
| E4 | Device refund racing the reconciliation refund | E | money-loss (to us) | no | **guarded** | `payoutStatus=="refunded"` → 409 [index.ts:365-367](../functions/src/index.ts#L365); reconcile skips [index.ts:2828-2848](../functions/src/index.ts#L2828) |
| **F1** | **"€X will be charged if you exceed the limit" is false for >7-day challenges** | F | money-loss | **prominent** | **OPEN** | [strings.xml:27](../app/src/main/res/values/strings.xml#L27) used at [ChallengeCreationScreen.kt:1125](../app/src/main/java/com/detox/app/presentation/screens/challengecreation/ChallengeCreationScreen.kt#L1125) |
| **F2** | **Limit-exceeded overlay says the stake "will now be charged" — it will not** | F | cosmetic→trust | **prominent (fix copy)** | **OPEN** | [strings.xml:451](../app/src/main/res/values/strings.xml#L451) used at [LimitExceededOverlay.kt:114](../app/src/main/java/com/detox/app/presentation/components/LimitExceededOverlay.kt#L114); overlay fires at `>=` ([OverlayManager.kt:475](../app/src/main/java/com/detox/app/service/OverlayManager.kt#L475)) |
| **F3** | **Group abandon dialog claims "you lose €X immediately" — nothing is captured** | F | cosmetic→trust | **prominent (fix copy)** | **OPEN** | [ActiveChallengeScreen.kt:248](../app/src/main/java/com/detox/app/presentation/screens/activechallenge/ActiveChallengeScreen.kt#L248) (HARD branch matches group shadows) |
| F4 | Dead string claims "50% goes to charity" | F | cosmetic | no | **OPEN (unused string)** | [strings.xml:422](../app/src/main/res/values/strings.xml#L422); no `R.string.challenge_setup_hard_warning` reference |
| F5 | Group ranking is entirely self-reported client numbers | F | cosmetic | link | by design (invariant #29) | [firestore.rules:205-211](../firestore.rules#L205) |
| F6 | Group SESSIONS "exceeded" counts raw UsageStats resumes | F | cosmetic (ranking) | no | **OPEN** | [DailyEvaluationWorker.kt:958-964](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L958) → [:892-899](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L892), source [UsageStatsRepositoryImpl.kt:170-183](../app/src/main/java/com/detox/app/data/repository/UsageStatsRepositoryImpl.kt#L170) |
| F7 | Group TIME_BUDGET "exceeded" uses UsageStats minutes, solo uses `budgetUsedMs` | F | cosmetic (ranking) | no | **OPEN** | [DailyEvaluationWorker.kt:958-964](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L958) vs [:339-353](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L339) |
| F8 | Enforcement is best-effort on Huawei/EMUI (task killing, worker throttling) | F | money-loss | **prominent** | inherent | heartbeat nudge [PermissionCheckWorker.kt:206-214](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L206) |

---

## 2. Notes on the findings that need more than a row

### A1 — UsageStats bucket summation (money-loss, OPEN)

`getUsageTimeByPackage` calls `queryUsageStats(INTERVAL_DAILY, start, end)` and **sums**
`stat.totalTimeInForeground` over every returned bucket for a package
([UsageStatsRepositoryImpl.kt:152-168](../app/src/main/java/com/detox/app/data/repository/UsageStatsRepositoryImpl.kt#L152)).
Android returns any bucket that *intersects* the range, carrying its **full** total — it does not clip
to the query window. `getTodayUsageForApp` queries `[localMidnight, now]`
([:194-212](../app/src/main/java/com/detox/app/data/repository/UsageStatsRepositoryImpl.kt#L194)), so a
daily bucket whose boundary is not local midnight (common on OEM builds) contributes pre-midnight
minutes to today, and a second intersecting bucket is added on top rather than merged.

This value is `adjustedMinutes`, which is the **only** automatic loss trigger for a solo Hard Mode TIME
challenge (`adjustedMinutes > limitValueMinutes`,
[DailyEvaluationWorker.kt:890](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L890)).
Over-counting here forfeits a real stake. Note the inconsistency: `PermissionCheckWorker` uses
`INTERVAL_BEST` for the same kind of question
([PermissionCheckWorker.kt:282](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L282)).

### B1–B4 — the group challenge is, today, close to unloseable

Four independent facts stack:

1. `evaluateGroupChallenge` writes a stats-only `DailyLog` on a limit breach and explicitly does not
   fail, does not capture:
   *"Group Challenge NEVER auto-fails … Participant stays active. Stripe only captured on manual Aufgeben"*
   ([DailyEvaluationWorker.kt:966-996](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L966)).
2. The server-side group capture on 24 h permission loss queries
   `collectionGroup("participants").where("userId","==",…).where("status","==","active")`
   ([index.ts:2572-2575](../functions/src/index.ts#L2572)). That sub-collection holds **only** leaderboard
   stats — the rules whitelist (`hasOnly([...])`,
   [firestore.rules:216-221](../firestore.rules#L216)) makes it *impossible* for a doc there to carry
   `userId`, `status`, or `paymentIntentId`. The query has never matched a document. `git log -S`
   dates it to `3e00172`, i.e. it was dead from the day it was written.
3. `PermissionCheckWorker.failAllHardChallenges` only ever touches solo docs
   ([:345-397](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L345)).
4. **The worst one.** The group shadow row is a normal Room `ChallengeEntity` with `mode="hard"`
   ([GroupChallengeRepositoryImpl.kt:173](../app/src/main/java/com/detox/app/data/repository/GroupChallengeRepositoryImpl.kt#L173)),
   so tapping it on the Dashboard routes to `active_challenge/{id}`
   ([MainScreen.kt:296-298](../app/src/main/java/com/detox/app/presentation/navigation/MainScreen.kt#L296))
   and the "Challenge aufgeben" link renders for it
   ([ActiveChallengeScreen.kt:921-934](../app/src/main/java/com/detox/app/presentation/screens/activechallenge/ActiveChallengeScreen.kt#L921)).
   `abandonChallenge()` deliberately excludes `groupChallengeId != null` from the capture branch and
   falls through to `markFailedAndFinish`
   ([ActiveChallengeViewModel.kt:270-291](../app/src/main/java/com/detox/app/presentation/screens/activechallenge/ActiveChallengeViewModel.kt#L270)),
   which writes Room `failed` and calls `markChallengeFailed` with `challengeId = "group_<groupId>"` —
   a doc that does not exist under `users/{uid}/challenges`, so the CF returns 400
   ([index.ts:306](../functions/src/index.ts#L306)) and is swallowed.
   **Result:** local enforcement stops, the group participant record stays `active`, and
   `completeGroupChallenge` classifies them a **winner** — 80 % of their buy-in back plus a share of the
   pot funded by people who used the real "Aufgeben" button on the group detail screen
   ([GroupChallengeDetailViewModel.kt:297-324](../app/src/main/java/com/detox/app/presentation/screens/groupchallenge/detail/GroupChallengeDetailViewModel.kt#L297)).
   The confirm dialog even tells them *"If you quit, you lose €X immediately"* (F3) — which is false.

Net effect for a joiner: **the honest participant who admits defeat is the only one who pays.**
This is the single most important thing to resolve before a consent flow can honestly describe a
Group Challenge.

### C2 — group settlement still has no server backstop

`scheduledGroupChallengeAutoStart` was added for *starting* (`19a9f90`) but the settlement twin from
`launch-investigation.md` §3 Option A was never built. `completeGroupChallenge` remains `onRequest`
only, driven by three device paths. If every participant's device is quiet after `endDate`, the group
never settles: buy-ins were captured at start ([index.ts:1148-1167](../functions/src/index.ts#L1148))
and nothing is returned. On Huawei with no Play Services this is a realistic path.

### C4 — account deletion

`deleteUserData` deletes every `users/{uid}/challenges/*` doc and then `users/{uid}`
([FirestoreService.kt:750-770](../app/src/main/java/com/detox/app/data/remote/firebase/FirestoreService.kt#L750)).
That destroys:
- the **solo** FAGG waiver (`withdrawalWaiverAccepted` lives on the challenge doc,
  [ChallengeCreationViewModel.kt:624-637](../app/src/main/java/com/detox/app/presentation/screens/challengecreation/ChallengeCreationViewModel.kt#L624));
- the **joiner's** FAGG waiver (`users/{uid}.groupWithdrawalWaivers.{groupId}`,
  [GroupChallengeJoinViewModel.kt:291-299](../app/src/main/java/com/detox/app/presentation/screens/groupchallenge/join/GroupChallengeJoinViewModel.kt#L291));
- the **solo uninstall-forfeit consent** (`users/{uid}.uninstallForfeitConsents.{challengeId}`,
  `ChallengeCreationViewModel.recordUninstallForfeitConsent`) — the consent that licenses the
  `device_dark` forfeit. **Accepted remaining risk, not an oversight:** the record is written
  correctly and blocks the payment if it fails, but account deletion still erases the proof.
  Fixing that means an Admin-SDK consent ledger outside `users/{uid}`, tracked with this section;
- the payout identity (`stripeConnectedAccountId`, `payoutIban`, `payoutName`) that both payout rails
  read ([index.ts:2187-2199](../functions/src/index.ts#L2187), [:2316-2324](../functions/src/index.ts#L2316)).

The **creator's** waiver survives, because it sits on `groupChallenges/{groupId}`
([GroupChallengeCreateViewModel.kt:626-639](../app/src/main/java/com/detox/app/presentation/screens/groupchallenge/create/GroupChallengeCreateViewModel.kt#L626)) —
so consent retention is inconsistent across the three roles.

`pendingPayouts` sub-collection docs are *not* deleted (Firestore does not cascade) but become
unusable: the ledger entry survives with no account and no IBAN to pay it to. The pre-delete guard
only checks for active Hard/group challenges
([SettingsViewModel.kt:296-315](../app/src/main/java/com/detox/app/presentation/screens/settings/SettingsViewModel.kt#L296));
it does not check for unpaid winnings.

### E1 — the group double-capture is unguarded, not prevented

The group shadow row has `mode = "hard"` and carries the **group buy-in PaymentIntent**
([GroupChallengeRepositoryImpl.kt:181](../app/src/main/java/com/detox/app/data/repository/GroupChallengeRepositoryImpl.kt#L181)).
`failAllHardChallenges` filters on `mode != HARD` only
([PermissionCheckWorker.kt:352](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L352)) —
there is **no `groupChallengeId == null` check**, unlike the abandon path (which has one) and unlike
invariant #5 which explicitly names `groupChallengeId==null`. The only thing that stops it capturing a
group buy-in outside settlement is `ChallengeSettlementGuard`, which returns `SKIP` because
`fetchChallengeSettlement` returns `null` for a non-existent doc
([FirestoreService.kt:298](../app/src/main/java/com/detox/app/data/remote/firebase/FirestoreService.kt#L298),
[ChallengeSettlementGuard.kt:59-64](../app/src/main/java/com/detox/app/service/ChallengeSettlementGuard.kt#L59)).
That is a correct outcome reached by an unrelated mechanism. Any future change that syncs group
shadow rows up to `users/{uid}/challenges`, or that softens the missing-doc branch, turns this into a
capture of a stake that `completeGroupChallenge` will later also refund 80 % of.

---

## 3. Consent-surface check

### 3.1 What is actually surfaced today, by role

| Disclosure | Solo Hard create | Group create (creator) | Group join (joiner) |
|---|---|---|---|
| Stake / buy-in amount | ✅ [ChallengeCreationScreen.kt:1305-1314](../app/src/main/java/com/detox/app/presentation/screens/challengecreation/ChallengeCreationScreen.kt#L1305) | ✅ [GroupChallengeCreateScreen.kt:969-991](../app/src/main/java/com/detox/app/presentation/screens/groupchallenge/create/GroupChallengeCreateScreen.kt#L969) | ✅ [GroupChallengeJoinScreen.kt:500-518](../app/src/main/java/com/detox/app/presentation/screens/groupchallenge/join/GroupChallengeJoinScreen.kt#L500) |
| 80 / 20 split on success | ✅ | ✅ (with `*possible prize share` footnote) | ✅ (same component) |
| 100 % back if nobody fails | n/a | ✅ `fee_group_no_loser_note` | ✅ |
| FAGG § 18 waiver checkbox | ✅ hard-gates the button | ✅ hard-gates the button | ✅ hard-gates the button |
| Uninstall-forfeit consent checkbox | ✅ [:1321-1325](../app/src/main/java/com/detox/app/presentation/screens/challengecreation/ChallengeCreationScreen.kt#L1321) | ❌ **absent** | ❌ **absent** |
| Card-hold expiry deadline | ❌ | ❌ | ✅ [:521-532](../app/src/main/java/com/detox/app/presentation/screens/groupchallenge/join/GroupChallengeJoinScreen.kt#L521) |
| **Charged upfront when >7 days** | ❌ **and actively contradicted** (F1) | n/a (group is always manual capture) | n/a |
| **One bad day loses the whole stake** | ❌ | ❌ | ❌ |
| **Permission loss → capture in 24 h (or ~12 h, or 1 h)** | ❌ | ❌ | ❌ |
| **Group: only "give up" loses** | n/a | ❌ | ❌ |
| **Prize share is paid out manually** | n/a | ❌ | ❌ |
| **Settlement needs a device to open the app** | ❌ | ❌ | ❌ |
| Root-modified device warning | ✅ [strings.xml:1735](../app/src/main/res/values/strings.xml#L1735) | ❌ | ❌ |

### 3.2 Is consent actually recorded, for both roles?

**FAGG § 18 withdrawal waiver — recorded for all three roles, three different places:**

| Role | Written to | Survives account deletion? |
|---|---|---|
| Solo | `users/{uid}/challenges/{cid}.withdrawalWaiverAccepted` + `…Timestamp` | ❌ destroyed |
| Group creator | `groupChallenges/{groupId}.withdrawalWaiverAccepted` + `…Timestamp` | ✅ survives |
| Group joiner | `users/{uid}.groupWithdrawalWaivers.{groupId} = <ms>` | ❌ destroyed |

All three are **fire-and-forget merges with no failure handling** — a failed write is silent, so the
payment can complete with no consent record at all. All three are permitted by the rules (none of the
waiver keys appear in the deny-lists at [firestore.rules:44-49](../firestore.rules#L44) /
[:168-174](../firestore.rules#L168)), so they do land under normal conditions.

**Uninstall-forfeit consent — RECORDED for Solo Hard (fixed); still absent on both group surfaces.**

| Role | Written to | Survives account deletion? |
|---|---|---|
| Solo | `users/{uid}.uninstallForfeitConsents.{challengeId} = <ms>` | ❌ destroyed |
| Group creator | — nothing | n/a |
| Group joiner | — nothing | n/a |

The Solo tick is no longer local Compose state: it lives in `ChallengeCreationState.uninstallForfeitAcceptedAt`
(the *moment of acceptance*, null when unticked, so a record can never be a default-true), and
`ChallengeCreationViewModel.recordUninstallForfeitConsent` persists it. Two ways this write is
**stronger** than the three FAGG waivers above rather than a weaker parallel mechanism:

- it is **awaited and blocking** — a failure aborts before `createPaymentIntent`, where the waivers
  are silent fire-and-forget merges that can leave a paid challenge with no consent record;
- it lands **before the PaymentIntent exists**, so there is no window in which a stake is captured
  and no forfeit consent is on file.

It is on the user doc rather than beside the Solo waiver on the challenge doc for a hard reason: the
challenge doc does not exist until after the payment, and pre-creating it would consume the single
rules-allowed CREATE (invariant #3) and turn the real Hard Mode mirror into an UPDATE the rules deny
on `status` / `amountCents` / `stripePaymentIntentId` — a captured stake with no challenge doc. The
shape is the group joiner's precedent: consent on a document the consenting user owns.

Group forfeit consent is deliberately still not collected — group forfeit enforcement itself is
broken (shadow-row / permission-revoke block), and recording consent for a forfeit that does not
fire would be a false statement of the same class as the copy defects above. It belongs with the
group-correctness work.

### 3.3 Does the legal text match what the code collects?

Cannot be verified from this repo: the privacy policy and terms are external pages
(`https://saninbrca.github.io/finite-legal/privacy.html` / `terms.html`,
[strings.xml:953-954](../app/src/main/res/values/strings.xml#L953)). What the code **does** collect, for
counsel to check against those pages:

- `Settings.Secure.ANDROID_ID` on every Hard Mode create, on every group join
  ([GroupChallengeJoinViewModel.kt:168-172](../app/src/main/java/com/detox/app/presentation/screens/groupchallenge/join/GroupChallengeJoinViewModel.kt#L168)),
  and on a permission-loss mirror — the last one only when money is enabled
  ([PermissionCheckWorker.kt:240-252](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L240)).
- `isRooted` (RootBeer) on Hard Mode create → `users/{uid}/deviceInfo/security`.
- A `lastSeenAt` heartbeat written every worker cycle while a Hard challenge is active
  ([PermissionCheckWorker.kt:193-222](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L193)) —
  i.e. a continuous liveness signal, disclosed nowhere in-app.
- IBAN + account-holder name + the request IP at Connect-account creation
  ([index.ts:2464-2467](../functions/src/index.ts#L2464)).
- Per-app foreground minutes and open counts, daily, for tracked apps.

**In-app mismatch flagged (F4):** `strings.xml:422` still asserts *"Real money will be charged if you
break your limit. 50% goes to charity."* There is no charity anywhere in the money model (the app
retains 20 % on a win, 100 % on a loss, 10 % of the failed pot in groups). The string is currently
**unused**, so it is not shown — but if that sentence also reached the AGB it needs correcting there.

### 3.4 Every distinct money-loss trigger, in plain language

This is the source list for disclosure copy. Each line is a way a participant's money can end up gone.

**Solo Hard Mode**
1. You go over your daily limit on **any single day** — the whole stake is forfeited, even if every
   other day of the challenge was clean.
2. You tap "Challenge aufgeben" — the stake is charged immediately.
3. You switch off the overlay or accessibility permission and leave it off for **24 hours**.
4. …and that 24 hours can shorten to about **12 hours** if you dismiss a warning notification.
5. You switch off accessibility and then open a blocked app — the stake is charged after **1 hour**.
6. You uninstall the app or disable it during an active challenge (server-armed forfeit).
7. If your challenge is longer than **7 days**, the money leaves your card **when you start**, not
   when you break the limit. Winning refunds 80 % afterwards.
8. Even when you win, the app keeps **20 %**.
9. The measurement is Android's own usage statistics, which are approximate — expect a small tolerance
   around the limit.

**Group Challenge (creator and joiner)**
10. Your buy-in is **charged in full when the challenge starts** — not when you break a rule.
11. If you tap "Aufgeben", your buy-in is captured and split between the players who finish.
12. If everyone finishes clean, everyone gets **100 %** back. If anyone gives up, finishers get **80 %**
    of their own buy-in plus an equal share of the given-up buy-ins, minus a **10 %** fee on that pot.
13. If the challenge never reaches 2 players, or is not started before the card-hold deadline, every
    hold is released and nobody is charged.
14. Your **prize share** (not your own buy-in) needs an IBAN and is transferred **manually** — it is
    not instant.
15. Settlement runs when a participant's phone opens the app after the end date. Until then, money is
    held.
16. Ranking on the leaderboard is self-reported by each player's phone and does **not** decide who is
    paid.

---

## 4. Launch-blocking gates

These must be resolved before a consent screen can truthfully describe the product. Ordered by how
badly they break the description.

1. **B1 + B4 — a Group Challenge cannot honestly be described until "give up" stops being the only
   loss path, and until the group row's local abandon stops paying out as a win.** Right now a
   consent screen would either have to say "you can break every rule every day and still be paid"
   (which nobody will pay into) or say something the code does not do.
2. **B2 + B3 — group permission-loss enforcement does not exist.** The consent copy for solo says
   permission loss costs you the stake; for group it costs nothing. Either make it true for both or
   say so for both.
3. **F1 — the Hard Mode payment screen currently states the opposite of what happens for >7-day
   challenges.** A consent flow cannot ship on top of an untrue statement about when money leaves the
   card. Same class: **F2** and **F3**.
4. **Uninstall-forfeit consent is not recorded** (§3.2). The one forfeit with no usage evidence behind
   it has no evidence of consent either, on the one surface where it is even asked.
5. **C2 — no server-side group settlement.** A participant paying today has no guarantee the money
   comes back without someone opening the app. Either build the scheduled twin or disclose it
   prominently and keep groups off.
6. **C1 — manual prize transfers.** Must be disclosed prominently; "you win" currently implies a
   payout the system does not perform automatically.
7. **C4 — account deletion strands owed winnings and destroys two of three consent records.** Both a
   money problem and an evidence problem.
8. **Stripe live-key flip** (B1 in `launch-readiness-audit.md`) — still `pk_test_` in `release`.

---

## 5. Gaps and optimization opportunities noticed along the way

Not asked for, no action taken.

1. **`OverlayManager.captureAndLock` + `writeDailyLogForHardCapture` are dead code** (~75 lines,
   [OverlayManager.kt:1343-1418](../app/src/main/java/com/detox/app/service/OverlayManager.kt#L1343)).
   They contain a fire-and-forget capture followed by an unconditional `limitExceeded=true` +
   `moneyLostCents` write — i.e. the exact "record a loss for money we may not have taken" shape the
   capture-gate invariants exist to prevent. Worth deleting rather than leaving loaded.
2. **`FirestoreService.deleteChallenge` is dead** ([:235](../app/src/main/java/com/detox/app/data/remote/firebase/FirestoreService.kt#L235))
   since `markChallengeFailed` landed, but `firestore.rules:50` still grants the client delete on its
   own challenge docs. Tightening that rule closes B7 with no client change.
3. **`groupChallenges/{groupId}` update has no participant check** —
   [firestore.rules:166-174](../firestore.rules#L166) allows *any* authenticated user to write any
   non-blocked field on *any* group doc (all group docs are world-readable to authed users). Money and
   identity fields are protected, so this is not a money hole, but a stranger can write arbitrary keys
   onto a stranger's challenge, including overwriting `withdrawalWaiverAccepted`.
4. **`checkPermissionViolations` HTTP 500** is still listed as open in the changelog's known-issues
   block; the per-query `try/catch` guards added since
   ([index.ts:2516-2526](../functions/src/index.ts#L2516), [:2570-2581](../functions/src/index.ts#L2570))
   look like they cover the reported cause. Worth re-testing and closing the note either way.
5. **`reconciliationLowEvidence`, `payoutIncomplete`, `payoutFailedUserIds`, `payoutOwedCents`,
   `FAIL-VS-SETTLE CONFLICT`, `CANCEL-REFUND DEBT`** are all written specifically to be findable, and
   nothing looks at them. One admin tab querying `groupChallenges where payoutIncomplete == true` plus
   a log-based alert would turn six carefully-built liability markers into an actual process.
6. **Three different "what counts as a breach" implementations** now exist for the same limit types:
   solo TIME_BUDGET reads `budgetUsedMs`, group TIME_BUDGET reads UsageStats minutes, solo SESSIONS
   reads Room conscious opens, group SESSIONS reads UsageStats resumes (F6/F7). Consolidating on one
   predicate would remove a class of "the leaderboard disagrees with the app" bugs.
7. **`GroupStartWindow.WINDOW_DAYS = 5` is a hand-maintained second copy** of a literal in
   `createGroupChallenge` ([index.ts:565](../functions/src/index.ts#L565)); the file says so honestly.
   Moving it to `config/app` would make it one source and remotely tunable.
8. **`getOrCreateStripeCustomer` + `createPaymentIntent` bump `totalActiveChallenges` before the
   PaymentSheet resolves** ([index.ts:214-216](../functions/src/index.ts#L214)), so a cancelled sheet
   over-counts. Acknowledged in-comment; the reconciliation net does not correct it.
9. **`versionCode = 2` for a "1.0.0" first release** and the stale *"TODO: Replace with real DSN"*
   comment above a real Sentry DSN — both still exactly as the 2026-06-18 audit found them
   ([app/build.gradle.kts:41](../app/build.gradle.kts#L41), [:46](../app/build.gradle.kts#L46)).
10. **The unit test suite still does not compile.** Every finding above was verified by reading code,
    not by executing it — there is no automated coverage backing any money path.

---

_End of inventory. No fixes proposed; awaiting approval before any implementation._
