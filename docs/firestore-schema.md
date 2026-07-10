# Firestore Schema Reference

> **Purpose:** a precise, code-derived reference of every Firestore collection / subcollection,
> every field, its type, and where it is written/read. Use this so future work never guesses field
> names or types.
>
> **Method:** extracted READ-ONLY from `functions/src/index.ts` (Cloud Functions, Admin SDK),
> the Kotlin Firestore services/mappers (`FirestoreService.kt`, `GroupChallengeFirestoreService.kt`,
> various ViewModels/Workers), `firestore.rules`, and `firestore.indexes.json`. No live data was
> sampled (`GOOGLE_APPLICATION_CREDENTIALS` was unset). All `file:line` references are to the state
> of the repo at generation time.
>
> **Type conventions used below:**
> - `number (millis)` = epoch milliseconds stored as a Firestore **NUMBER** (Kotlin `Long`,
>   `System.currentTimeMillis()` / `Date.now()`). **NOT** a Firestore `Timestamp`.
> - `Timestamp` = a real Firestore `Timestamp` (`com.google.firebase.Timestamp.now()` /
>   `FieldValue.serverTimestamp()`).
> - `amountCents` / `*Cents` = **integer cents** (EUR). `500` = €5.00.
> - `number` without qualifier = plain numeric (count, version, etc.).
> - `string (csv)` = a comma-joined list serialized into a single Firestore string field
>   (`List.joinToString(",")`), split back on read. **Not** a Firestore array.
> - `array<…>` = a true Firestore array; `map` = a nested object.
>
> ⚠️ **Mixed-type timestamp hazard:** several timestamp fields are written as a NUMBER by the
> client but the CFs also tolerate a Firestore `Timestamp` on read via `tsToMillis()` /
> `(x as Timestamp)?.toMillis()`. These are flagged per-field. New writers should always use
> **number (millis)** for `startDate`/`endDate`/`*At` on challenge & group docs to match
> `Challenge.toMap()`.
> _Last verified: 2026-06-22 (commit e287b79)_

---

## Collection / subcollection index

| Path | Doc id | Owner of writes |
|------|--------|-----------------|
| `users/{uid}` | Firebase uid | Client (profile/consent) + CF (Stripe/ban/payout) |
| `users/{uid}/challenges/{challengeId}` | unified `challengeId` | Client (create + non-protected) + CF (settlement) |
| `users/{uid}/challenges/{challengeId}/dailyLogs/{dateKey}` | `date` (millis) as string | Client (create/update) — delete CF-only |
| `users/{uid}/dailyLogs/{challengeId}_{date}` | `{challengeId}_{date}` | Client only (flat reinstall-persistence logs) |
| `users/{uid}/pendingPayouts/{payoutId}` | auto-id (or `{groupId}`) | CF-only |
| `users/{uid}/paymentCaptures/{captureId}` | auto-id | CF-only |
| `users/{uid}/permissionStatus/{doc=current}` | `"current"` | Client (loss/restore) + CF (capture markers) |
| `users/{uid}/deviceInfo/{doc=security}` | `"security"` | Client (`isRooted`) — `adminVerified` admin-only |
| `groupChallenges/{groupId}` | client-supplied `groupId` | CF (canonical create + settlement) + client (participant stats) |
| `groupChallenges/{groupId}/taunts/{tauntId}` | UUID | Client (participant) — no edit/delete |
| `groupChallenges/{groupId}/participants/{participantId}` | (future-proof; not currently written) | — |
| `payoutRequests/{requestId}` | auto-id | Client (create `pending`) + admin (mark paid/rejected) |
| `usernames/{username}` | lowercase username | Client (claim once) — permanent |
| `supportTickets/{ticketId}` | auto-id | Client (create) + admin (resolve/reply) |
| `supportTickets/{ticketId}/adminNotes/{noteId}` | auto-id | Admin-only |
| `antiCheatReviews/{userId}` | reviewed uid | Admin-only |
| `counters/{doc=global}` | `"global"` | CF-only |
| `broadcasts/{broadcastId}` | auto-id | Admin-only (client read) |
| `config/app` | `"app"` | Admin-only (client read) |
| `rateLimits/{uid}` | uid | CF-only (Admin SDK; client fully denied) |
| `admin/{document=**}` | — | Fully denied (client) |

---

## `users/{uid}`

Top-level user profile. Created at registration / first sign-in; merged thereafter. Stripe + ban +
payout fields are CF/admin-owned (Admin SDK bypasses rules; client UPDATE of those keys is blocked).

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `email` | string | Required | `FirestoreService.createUserDocument` (FirestoreService.kt:56) | `detectSuspiciousUsers` (index.ts:2174); `getOrCreateStripeCustomer` uses Auth, not this | — |
| `displayName` | string | Optional | `createUserDocument` (FirestoreService.kt:59); `saveUsername` sets =username (FirestoreService.kt:156) | `detectSuspiciousUsers` (index.ts:2174) | mirrors username on claim |
| `createdAt` | Timestamp | Required | `createUserDocument` (FirestoreService.kt:57) | — | real `Timestamp.now()` (NOT millis) |
| `username` | string (lowercase) | Optional | `saveUsername` txn (FirestoreService.kt:156) | `getUsername` (FirestoreService.kt:111); `detectSuspiciousUsers` (index.ts:2174) | permanent; mirrored from `usernames/{username}` |
| `consentAGB` | boolean | Optional | `createUserDocument` (FirestoreService.kt:62) | — | legal proof; only when granted |
| `consentDatenschutz` | boolean | Optional | `createUserDocument` (FirestoreService.kt:63) | — | — |
| `consentAge18` | boolean | Optional | `createUserDocument` (FirestoreService.kt:64) | — | — |
| `consentTimestamp` | Timestamp | Optional | `createUserDocument` (FirestoreService.kt:65) | — | real `Timestamp` |
| `fcmToken` | string | Optional | `saveFcmToken` (FirestoreService.kt:177 / merge 183) | — | FCM push token |
| `disabled` | boolean | Optional | `setUserBanStatus` CF (index.ts:2044); client admin path (rules) | `isUserDisabled` (FirestoreService.kt:86) | ban flag (Layer-1); rules: client cannot self-set; admin-only client write |
| `disabledReason` | string \| null | Optional | `setUserBanStatus` (index.ts:2045) | `getDisabledReason` (FirestoreService.kt:97) | `null` on unban |
| `disabledAt` | number (millis) \| null | Optional | `setUserBanStatus` (index.ts:2046) | — | `Date.now()`; `null` on unban |
| `stripeCustomerId` | string | Optional | `getOrCreateStripeCustomer` CF (index.ts:1958) | `getOrCreateStripeCustomer` (index.ts:1947) | client UPDATE blocked by rules |
| `stripeConnectedAccountId` | string | Optional | `createConnectedAccount` CF (index.ts:1404) | `completeGroupChallenge` (index.ts:1218); `claimPendingPayouts` (index.ts:1306); `getConnectedAccountStatus` (index.ts:1421); `createConnectedAccount` idempotency (index.ts:1368) | Stripe Connect (custom) account; client UPDATE blocked |
| `payoutIban` | string | Optional | `createConnectedAccount` CF (index.ts:1405); `ProfileViewModel.set` (ProfileViewModel.kt:235) | `detectSuspiciousUsers` shared-IBAN signal (index.ts:2170); `GroupChallengeDetailViewModel` (…DetailViewModel.kt:402) | single-field COLLECTION index (indexes.json:97) |
| `payoutName` | string | Optional | `createConnectedAccount` (index.ts:1406); `ProfileViewModel.set` (ProfileViewModel.kt:235) | `GroupChallengeDetailViewModel` (…DetailViewModel.kt:403) | account holder name |
| `payoutSetupAt` | number (millis) | Optional | `createConnectedAccount` (index.ts:1407) | — | `Date.now()` |
| `pendingPayouts_completed.{groupId}` | map | Optional | `completeGroupChallenge` transfer record (index.ts:1261) | — | dot-keyed MAP on user doc; `{amount, stakeRefundCents, status:"transferred", groupId, transferredAt(millis)}` |

**Lifecycle:** created on registration (`onUserCreated` trigger bumps `counters.totalUsers`, index.ts:1985). Stripe fields appear lazily on first payment/payout. Ban fields toggled by `setUserBanStatus`. Deleted by `deleteUserData` (FirestoreService.kt:711) on account deletion (rules: `allow delete: if false` — so deletion only via owner path? Note: rule line 28 sets `allow delete: if false`; client `deleteUserData` delete will be rejected by rules — only the CF/Admin SDK can truly delete).

---

## `users/{uid}/challenges/{challengeId}`

Solo challenge document. `challengeId` is the **unified id** also passed to `createPaymentIntent`
(the CF never writes this doc). A single rules-allowed CREATE writes all fields
(`Challenge.toMap()`); afterward the client may not UPDATE the money/status-protected keys
(rules lines 44–49: `status, payoutStatus, payoutAmount, appFeeAmount, stripePaymentIntentId,
stripeCustomerId, finalPayout, endDate, startDate, amountCents, paymentIntentId`).

Written by `FirestoreService.saveChallenge` → `Challenge.toMap()` (FirestoreService.kt:742).

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `id` | string | Required | toMap (FirestoreService.kt:743) | `fetchActiveChallenges` (FirestoreService.kt:346) | == doc id |
| `appPackageName` | string | Required (`""` sentinel) | toMap (744) | `fetchActiveChallenges` (329) | `""` for WEBSITE-type |
| `appPackageNames` | string (csv) \| null | Optional | toMap (745) | `fetchActiveChallenges` (332) | comma-joined; falls back to `appPackageName` |
| `appDisplayName` | string | Required | toMap (746) | `fetchActiveChallenges` (349) | — |
| `mode` | string enum | Required | toMap (747) | `fetchActiveChallenges` (350); CFs filter `mode=="hard"` (index.ts:1479, 1568, 1688, 2086) | values: `soft` \| `hard` (lowercased) |
| `limitType` | string enum | Required | toMap (748) | `fetchActiveChallenges` (353) | `time` \| `sessions` \| `time_budget` (LimitType, lowercased) |
| `limitValueMinutes` | number | Required | toMap (749) | `fetchActiveChallenges` (356) | Int |
| `limitValueSessions` | number \| null | Optional | toMap (750) | `fetchActiveChallenges` (357) | SESSIONS only |
| `startDate` | number (millis) | Required | toMap (751) | `fetchActiveChallenges` (358); `detectSuspiciousUsers` via `tsToMillis` (index.ts:2261) | **client UPDATE blocked**; tolerates Timestamp on CF read |
| `endDate` | number (millis) | Required | toMap (752) | `cancelOrRefundPayment` (index.ts:312); reconciliation per-doc `isDue` (`endDate<=now`, index.ts:1801 — the `endDate<=now` *query* filter was DROPPED so the went-dark branch can reach not-yet-due docs); `fetchActiveChallenges` (359) | **client UPDATE blocked**; tolerates Timestamp via `?.toMillis()` |
| `amountCents` | number (cents) \| null | Optional | toMap (753) | `cancelOrRefundPayment` recompute (index.ts:349, 396); reconciliation (index.ts:1738); `backfillCounters` (index.ts:2091) | **client UPDATE blocked** (trust anchor for refunds) |
| `stripePaymentIntentId` | string \| null | Optional | toMap (754) | `cancelOrRefundPayment` PI-binding (index.ts:325); `checkPermissionViolations` (index.ts:1480, 1569); reconciliation (index.ts:1739) | **client UPDATE blocked**; CFs also fall back to legacy `paymentIntentId` |
| `customMotivation` | string \| null | Optional | toMap (755) | `fetchActiveChallenges` (362) | — |
| `status` | string enum | Required | toMap (756) on create; `updateChallengeStatus` best-effort COMPLETED write (FirestoreService.kt:219); **`markChallengeFailed` CF (index.ts:313)** for device-detected losses; CF settlement | many CFs + `fetchActiveChallenges`/`fetchFinishedChallenges` | `active` \| `completed` \| `failed`. **client UPDATE blocked** — the client can never write `status` directly (rules reject the protected key). WIN ⇒ the settlement CF (`cancelOrRefundPayment`/reconciliation) owns `completed`. LOSS ⇒ a device-detected loss now routes through the **`markChallengeFailed` CF** (Admin-SDK in-place `failed`, doc + dailyLogs RETAINED — it no longer deletes the doc); server losses set `failed` via `checkPermissionViolations`/reconciliation. |
| `createdAt` | number (millis) | Required | toMap (757) | `fetchActiveChallenges` (366) | COLLECTION/COLLECTION_GROUP asc+desc index (indexes.json:79) |
| `dailyBudgetMinutes` | number \| null | Optional | toMap (758) | `fetchActiveChallenges` (367) | TIME_BUDGET only |
| `blockedDomains` | string (csv) \| null | Optional | toMap (759) | `fetchActiveChallenges` (339) | website blocking |
| `partialBlockDomains` | string (csv) \| null | Optional | toMap (760) | `fetchActiveChallenges` (369) | feature-level partial block |
| `blockingType` | string enum | Required | toMap (761) | `fetchActiveChallenges` (372) | `app` \| `website` (BlockingType, lowercased) |
| `blockAdultContent` | boolean | Required | toMap (762) | `fetchActiveChallenges` (377) | — |
| `scheduleStartTime` | string \| null | Optional | toMap (763) | `fetchActiveChallenges` (378) | `"HH:mm"` |
| `scheduleEndTime` | string \| null | Optional | toMap (764) | `fetchActiveChallenges` (379) | `"HH:mm"` |
| `activeDays` | string (csv) \| null | Optional | toMap (765) | `fetchActiveChallenges` (380) | e.g. `"MON,TUE"` |
| `partialBlockSections` | array<string> | Required (may be empty) | toMap (766) | `fetchActiveChallenges` (385) | **true array** of section ids (e.g. `instagram_reels`) — the only array on the challenge doc |
| `isPartialBlockOnly` | boolean | Required | toMap (767) | `fetchActiveChallenges` (389) | — |
| `isRedemption` | boolean | Required | toMap (768) | `cancelOrRefundPayment` (index.ts:366); reconciliation (index.ts:1740); `detectSuspiciousUsers` (index.ts:2255) | `true` = this doc IS a redemption (amountCents=0, no new payment) |
| `originalChallengeId` | string \| null | Optional | toMap (769) | `cancelOrRefundPayment` (index.ts:391); reconciliation (index.ts:1836) | redemption → original challenge id |
| `originalPaymentIntentId` | string \| null | Optional | toMap (770) | `cancelOrRefundPayment` PI-binding (index.ts:376); reconciliation (index.ts:1834) | redemption → original PI to partially refund |
| `refundAmountCents` | number (cents) \| null | Optional | toMap (771) | (client-side redemption logic) | partial refund on redemption win |
| `pendingLimitValue` | number \| null | Optional | toMap (772); `updateChallengePendingLimit` (FirestoreService.kt:727) | `fetchActiveChallenges` (390) | next-midnight limit reduction |
| `pendingLimitAppliesAt` | number (millis) \| null | Optional | toMap (773); `updateChallengePendingLimit` (FirestoreService.kt:728) | `fetchActiveChallenges` (391) | — |
| `deviceId` | string \| null | Optional | toMap (775) | `detectSuspiciousUsers` shared-device signal (index.ts:2243); single-field index (indexes.json:88) | `Settings.Secure.ANDROID_ID`; Hard Mode creation only (anti-cheat) |
| `isRooted` | boolean \| null | Optional | toMap (776) | `detectSuspiciousUsers` rooted signal (index.ts:2246) | RootBeer result; Hard Mode creation only |
| `syncedAt` | Timestamp | Required | toMap (777); `updateChallengeStatus` (FirestoreService.kt:219) | — | real `Timestamp.now()` |
| **CF-written settlement fields (not in toMap):** | | | | | |
| `payoutStatus` | string enum | Added at settle | `cancelOrRefundPayment` (index.ts:441); `checkPermissionViolations` (index.ts:1489, 1578); reconciliation (index.ts:1789, 1820, 1887); `updateChallengePayoutStatus` client (FirestoreService.kt:255) | `cancelOrRefundPayment` idempotency (index.ts:320, 371); reconciliation (index.ts:1736); `fetchChallengeSettlement` (FirestoreService.kt:287); `backfillCounters` (index.ts:2090) | values: `refunded` (win) \| `captured` (loss). **client UPDATE blocked** (but client `updateChallengePayoutStatus` writes `refunded` via merge — note: this would be rejected by rules unless the CF path is used). Presence ⇒ settled. |
| `payoutAmount` | number (cents) | Added at WIN settle | `cancelOrRefundPayment` (index.ts:442); reconciliation (index.ts:1888); client `updateChallengePayoutStatus` (FirestoreService.kt:256) | — | refunded amount (80%/60%) |
| `appFeeAmount` | number (cents) | Added at WIN settle | `cancelOrRefundPayment` (index.ts:443); reconciliation (index.ts:1889) | `backfillCounters` (index.ts:2092) | retained app fee = stake − refund |
| `payoutDate` | number (millis) | Added at settle | `cancelOrRefundPayment` (index.ts:444); reconciliation (index.ts:1890); client `updateChallengePayoutStatus` (FirestoreService.kt:256) | `detectSuspiciousUsers` instant-win (index.ts:2263) | `Date.now()` |
| `failReason` | string enum | Added at LOSS settle | **`markChallengeFailed` CF (index.ts:315)** writes the **passed** reason on a device-detected loss; `checkPermissionViolations` (index.ts:1530, 1566); reconciliation (index.ts:1874, 1905) | `fetchFinishedChallenges` reads it back into the `Challenge` model (FirestoreService.kt:490) → Room `failReason` column | values: `limit_exceeded` \| `abandon` \| `permission_violation` (the three device-set causes) \| `usage_violation` \| `reconciliation` \| `device_dark` (went-dark forfeit) \| `client_loss` (legacy fallback when a caller passes no cause). UX-only (loss dialog); never money logic |
| `failedAt` | number (millis) | Added at LOSS settle | **`markChallengeFailed` CF (index.ts:316)**; `checkPermissionViolations` (index.ts:1488, 1578); reconciliation (index.ts:1789, 1820) | — | `now` / `Date.now()` |
| `reconciliationLowEvidence` | boolean | Optional | reconciliation WIN low-evidence (index.ts:1893) | `detectSuspiciousUsers` Signal 6 (index.ts:2302) | flags a refund made with zero nested dailyLogs; surfaces in the Anti-Cheat dashboard (6 pts) |
| `redemptionEligible` | boolean | Optional | `updateChallengeRedemptionInfo` (FirestoreService.kt:651) | (client redemption banner) | — |
| `redemptionDeadline` | number (millis) | Optional | `updateChallengeRedemptionInfo` (FirestoreService.kt:652) | — | failedAt + 3d |
| `redemptionShowAfter` | number (millis) | Optional | `updateChallengeRedemptionInfo` (FirestoreService.kt:653) | — | failedAt + 24h |
| `redemptionRefundAmount` | number (cents) | Optional | `updateChallengeRedemptionInfo` (FirestoreService.kt:654) | — | — |
| `redemptionDays` | number | Optional | `updateChallengeRedemptionInfo` (FirestoreService.kt:655) | — | — |
| `redemptionLimit` | number | Optional | `updateChallengeRedemptionInfo` (FirestoreService.kt:656) | — | — |
| `redemptionChallengeId` | string | Optional | `updateChallengeRedemptionChallengeId` (FirestoreService.kt:677) | — | non-null ⇒ redemption already used |

> **Note on `groupChallengeId`:** present in the Room/`Challenge` model and used by CFs to exclude
> group-mirror challenges from solo counters (`backfillCounters` index.ts:2086;
> `detectSuspiciousUsers` index.ts:2255), but it is **NOT** in `Challenge.toMap()` — it is never
> written to the Firestore challenge doc. CF reads of `c.groupChallengeId` therefore see `undefined`
> for docs written by the current client (solo docs simply lack the key, which still yields the
> intended "solo" classification).

**Composite/collection-group indexes touching this collection:**
`(mode ASC, status ASC, endDate ASC)` collection-group (indexes.json:52) — its `(mode, status)` prefix
serves the broadened reconciliation candidate query (`mode=="hard" && status=="active"`, endDate filter
dropped; due-ness computed per-doc). `createdAt` and `deviceId` single-field overrides (indexes.json:79, 88).

**Lifecycle:**
- **create** — full doc via `saveChallenge` after PaymentSheet confirms (all toMap fields). For Hard
  Mode, `createPaymentIntent` bumps `counters.totalActiveChallenges`.
- **settle WIN** — `cancelOrRefundPayment` (or reconciliation) sets
  `status=completed, payoutStatus=refunded, payoutAmount, appFeeAmount, payoutDate`.
- **settle LOSS (server)** — `checkPermissionViolations` / reconciliation set
  `status=failed, payoutStatus=captured, failReason, failedAt`.
- **settle LOSS (device-detected)** — a limit-exceeded loss / abandon captures the stake first, then
  `ChallengeRepositoryImpl.updateChallengeStatus(FAILED, failReason)` calls the **`markChallengeFailed`
  CF** (index.ts:291), which writes `status=failed`, the **passed** `failReason` (`limit_exceeded` /
  `abandon` / `permission_violation`; `client_loss` only if a caller omits the cause), and `failedAt`
  **in place** via the Admin SDK. **The doc and its nested `dailyLogs` are RETAINED** (this REPLACED the
  old `deleteChallenge` path, which destroyed both). `markChallengeFailed` never writes `payoutStatus`
  (the capture path owns that) and is idempotent on an already-terminal doc.
- **delete** — client `deleteChallenge` (FirestoreService.kt:235) still exists for genuine
  user-initiated removal / `deleteUserData` on account deletion (NOT used for the loss path anymore).
  **A delete fires `onChallengeDeleted` (index.ts:1997), which cascades a batched delete of the nested
  `dailyLogs` subcollection** (Firestore does not auto-delete subcollections).

---

## `users/{uid}/challenges/{challengeId}/dailyLogs/{dateKey}`

Nested, **tamper-evident** per-day log. `dateKey` = `log.date.toString()` (the epoch-millis date as a
string, FirestoreService.kt:299). Written by `FirestoreService.saveDailyLog` → `DailyLog.toMap()`
(FirestoreService.kt:780).

Money-critical: `limitExceeded` gates the Hard Mode win-refund. Rules: `limitExceeded` may **never**
flip `true → false` (rules lines 69–71); `false → true` is allowed; **client delete is blocked**
(`allow delete: if false`, rules line 72) — only the `onChallengeDeleted` CF cascade deletes them.

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `id` | string | Required | toMap (FirestoreService.kt:781) | `fetchDailyLogs` (FirestoreService.kt:503) | — |
| `challengeId` | string | Required | toMap (782) | `fetchDailyLogs` (504) | — |
| `date` | number (millis) | Required | toMap (783) | `fetchDailyLogs` (505) | == dateKey |
| `totalMinutes` | number | Required | toMap (784) | `fetchDailyLogs` (506); `detectSuspiciousUsers` perfect-win (index.ts:2310) | — |
| `openCount` | number | Required | toMap (785) | `fetchDailyLogs` (507) | — |
| `consciousOpens` | number | Required | toMap (786) | `fetchDailyLogs` (508); `detectSuspiciousUsers` perfect-win (index.ts:2309) | conscious-open count |
| `overlayPausedMs` | number (millis) | Required | toMap (787) | `fetchDailyLogs` (509) | overlay-visible ms excluded from limit |
| `budgetUsedMinutes` | number | Required | toMap (788) | — | TIME_BUDGET |
| `budgetRemainingMinutes` | number | Required | toMap (789) | — | TIME_BUDGET |
| `pointsEarned` | number | Required | toMap (790) | `fetchDailyLogs` (510) | — |
| `limitExceeded` | boolean | Required | toMap (791) | `cancelOrRefundPayment` win-gate (index.ts:343); reconciliation `lossProven` (index.ts:1779) | **never true→false** (rules); `true` ⇒ stake should be captured, not refunded |
| `moneyLostCents` | number (cents) | Required | toMap (792) | `fetchDailyLogs` (512) | — |

> Note: `DailyLog.toMap()` does **not** write `budgetUsedMs` / `budgetRemainingMs` (those live only
> in Room and on the *flat* dailyLogs doc below). `fetchDailyLogs` does not read `budgetUsed*` fields.

**Lifecycle:** created/merged on each daily evaluation (clean day → `limitExceeded=false`; violation
day → `true`). Deleted only via `onChallengeDeleted` cascade when the parent challenge is deleted.

---

## `users/{uid}/dailyLogs/{challengeId}_{date}`  (flat — distinct from the nested one)

Flat, reinstall-persistence logs written every ~10 s. Doc id = `"{challengeId}_{date}"`. Full
owner read/write, no field restrictions (rules lines 92–94). **Separate collection** from the nested
per-challenge `dailyLogs` above.

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `challengeId` | string | Required | `updateDailyLogConsciousOpens` (FirestoreService.kt:543); `updateDailyLogBudget` (575) | `fetchTodayDailyLogs` / `fetchDailyLogDocument` (FirestoreService.kt:603, 626) | — |
| `date` | number (millis) | Required | (543, 575) | `fetchTodayDailyLogs` query `date >= startOfToday` (FirestoreService.kt:600) | range-queried |
| `consciousOpens` | number | Optional | `updateDailyLogConsciousOpens` (544); `ProfileViewModel` debug (ProfileViewModel.kt:736, 774) | (sync restore) | merged |
| `budgetUsedMs` | number (millis) | Optional | `updateDailyLogBudget` (578); `ProfileViewModel` debug (ProfileViewModel.kt:656, 699) | (sync restore) | source of truth for budget |
| `budgetRemainingMs` | number (millis) | Optional | `updateDailyLogBudget` (579); `ProfileViewModel` debug (656, 699) | (sync restore) | — |
| `updatedAt` | number (millis) | Required | (545, 580) | — | `System.currentTimeMillis()` |

---

## `users/{uid}/pendingPayouts/{payoutId}`

Group-challenge winnings owed but not yet transferred (no Connect account / payouts disabled / transfer
failed). CF-only writes (rules lines 98–101: client read, `write: if false`).

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `amount` | number (cents) | Required | `completeGroupChallenge.writePendingPayout` (index.ts:1226) | `claimPendingPayouts` (index.ts:1332) | the prize-share bonus |
| `stakeRefundCents` | number (cents) | Required | (index.ts:1227) | — | 80% own-stake refund |
| `currency` | string | Required | (index.ts:1228) | — | `"eur"` |
| `groupId` | string | Required | (index.ts:1229) | `claimPendingPayouts` (index.ts:1333) | — |
| `displayName` | string | Required | (index.ts:1230) | — | — |
| `status` | string | Required | (index.ts:1231) | — | `pending_account_setup`; client `ProfileViewModel` updates a `{groupId}`-id doc to `requested` (ProfileViewModel.kt:282) |
| `createdAt` | Timestamp | Required | (index.ts:1232) | — | `serverTimestamp()` |

> **Doc-id duality:** `completeGroupChallenge` writes auto-id docs. `ProfileViewModel` (manual payout
> request) updates `pendingPayouts/{groupId}` (`.document(group.groupId)`, ProfileViewModel.kt:281) —
> a different keying scheme; treat the id as opaque. `claimPendingPayouts` deletes each doc after a
> successful transfer (index.ts:1341).

---

## `users/{uid}/paymentCaptures/{captureId}`

Audit record of a Stripe capture. CF-only (rules lines 105–107). Auto-id (`.add`).

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `paymentIntentId` | string | Required | `capturePayment` (index.ts:262) | — | — |
| `amountCaptured` | number (cents) | Required | (index.ts:263) | — | `amount_received` |
| `capturedAt` | Timestamp | Required | (index.ts:264) | — | `serverTimestamp()` |

> Written ONLY on a fresh `requires_capture` capture (never the already-`succeeded` branch).

---

## `users/{uid}/permissionStatus/{doc}`  (only doc id used: `current`)

Mirror of overlay/accessibility permission loss & usage violations, so the server can capture a Hard
Mode stake after 24h (permission) / 1h (usage). Rules (lines 115–121): client may create/update
EXCEPT the CF-only keys `capturedAt`, `captureReason`, `usageCapturedAt`; delete blocked.

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `permissionLostAt` | number (millis) \| null | Optional | `PermissionCheckWorker` (PermissionCheckWorker.kt:122); cleared to `null` on restore (kt:90); `ProfileViewModel` debug (ProfileViewModel.kt:907) | `runPermissionviolationCheck` query `!= null` (index.ts:1452) + age check (index.ts:1463); reconciliation marker (index.ts:1726) | single-field COLLECTION + COLLECTION_GROUP index (indexes.json:63) |
| `permissionType` | string enum | Optional | `PermissionCheckWorker` (kt:123); `ProfileViewModel` (kt:908) | — | `overlay` \| `accessibility` \| `both` |
| `deviceId` | string | Optional | `PermissionCheckWorker` (kt:124); `ProfileViewModel` (kt:909) | — | ANDROID_ID |
| `permissionRestoredAt` | number (millis) | Optional | `PermissionCheckWorker` on restore (kt:91) | `runPermissionViolationCheck` skip (index.ts:1467); reconciliation (index.ts:1726) | set when `permissionLostAt` cleared |
| `usageViolationDetectedAt` | number (millis) \| null | Optional | `PermissionCheckWorker` (kt:219); `ProfileViewModel` debug (kt:933) | `runPermissionViolationCheck` query `!= null` (index.ts:1543) + 1h age (index.ts:1556); reconciliation (index.ts:1727) | single-field COLLECTION + COLLECTION_GROUP index (indexes.json:70) |
| `violatingPackage` | string | Optional | `PermissionCheckWorker` (kt:221); `ProfileViewModel` (kt:934) | — | the blocked package used |
| `usageMinutes` | number | Optional | `PermissionCheckWorker` (kt:221) | — | minutes in last hour |
| `lastSeenAt` | number (millis) | Optional | `PermissionCheckWorker.writeHeartbeatIfHardActive` (kt:226), ~15-min merge, gated on ≥1 active HARD challenge | reconciliation went-dark forfeit (index.ts:1846) | **heartbeat** — proves the app is still installed/running. Owner-writable (merge); absence/staleness beyond `config/app.wentDarkGraceMs` ⇒ went-dark LOSS (`failReason:"device_dark"`). Falls back to `startDate` as the reference when never written |
| `capturedAt` | number (millis) | Optional (CF-only) | `runPermissionViolationCheck` (index.ts:1533) | self-skip (index.ts:1466); reconciliation (index.ts:1726) | **CF-only** (rules block client) |
| `captureReason` | string enum | Optional (CF-only) | `runPermissionViolationCheck` (index.ts:1533, 1591) | — | `permission_loss_24h` \| `usage_violation_1h` |
| `usageCapturedAt` | number (millis) | Optional (CF-only) | `runPermissionViolationCheck` (index.ts:1591) | self-skip (index.ts:1558); reconciliation (index.ts:1727) | **CF-only** |

> `ProfileViewModel` also has a debug reset writing `permissionLostAt/usageViolationDetectedAt/capturedAt = null`
> (ProfileViewModel.kt:1036) — note writing `capturedAt` from the client is normally blocked by rules; this
> is a debug-only path and may fail under production rules.

---

## `users/{uid}/deviceInfo/{doc}`  (only doc id used: `security`)

Device security flags. Client may write all keys EXCEPT `adminVerified` (rules lines 125–130).

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `isRooted` | boolean | Optional | `ChallengeCreationViewModel` (ChallengeCreationViewModel.kt:471) | — | written `true` when root detected at Hard Mode creation |
| `detectedAt` | number (millis) | Optional | (ChallengeCreationViewModel.kt:471) | — | `System.currentTimeMillis()` |
| `adminVerified` | boolean | Optional (admin-only) | (admin) | — | client write of this key blocked by rules |

---

## `groupChallenges/{groupId}`

Group challenge. **Canonical create is the `createGroupChallenge` CF** (index.ts:492). The Android
`GroupChallengeFirestoreService.saveGroupChallenge` (…Service.kt:35) ALSO writes via `toMap()` (…Service.kt:336)
but the money/lifecycle keys are blocked from the client on both create and update (rules lines 138–153:
blocked = `status, startDate, endDate, completedAt, prizePool, appFee, prizePerWinner, nobodyFailed,
authorizationExpiresAt`). The client legitimately updates only the embedded `participants` array
(leaderboard stats).

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `groupId` | string | Required | CF `createGroupChallenge` (index.ts:495); client toMap (…Service.kt:337) | `toGroupChallenge` (…Service.kt:422) | == doc id |
| `code` | string | Required | CF (index.ts:494, may regenerate on collision index.ts:486); client toMap (338) | `fetchGroupChallengeByCode` query (…Service.kt:48); `toGroupChallenge` (423) | 6-char A–Z2–9 invite code, uppercased |
| `creatorUserId` | string | Required | client (must match auth, CF check index.ts:478); toMap (339) | many CF ownership checks (index.ts:478, 650, 751, 933, 998, 850, 1056); `observeUserGroupChallenges` query (…Service.kt:103) | — |
| `creatorDisplayName` | string | Optional | client `groupData` (read at index.ts:490) | `toGroupChallenge` (…Service.kt:425) | used to seed participant[0].displayName |
| `appPackageNames` | string (csv) | Required | client toMap (…Service.kt:340) | `toGroupChallenge` (…Service.kt:375) | comma-joined |
| `appDisplayName` | string | Required | client toMap (341) | `toGroupChallenge` (427) | — |
| `limitType` | string enum | Required | client toMap (342) | `toGroupChallenge` (428) | `time`\|`sessions`\|`time_budget` |
| `limitValueMinutes` | number | Required | client toMap (343) | `toGroupChallenge` (431) | — |
| `limitValueSessions` | number \| null | Optional | client toMap (344) | `toGroupChallenge` (432) | — |
| `sessionDurationMinutes` | number | Required | client toMap (345) | `toGroupChallenge` (433) | default 5 |
| `durationDays` | number | Required | client toMap (346) | `startGroupChallenge` (index.ts:711); `toGroupChallenge` (434) | default 7 |
| `buyInCents` | number (cents) | Required | client toMap (347) | `joinGroupChallenge` (index.ts:550); `confirmGroupJoin` (index.ts:611); `completeGroupChallenge` (index.ts:1067); `toGroupChallenge` (435) | default 500 |
| `maxParticipants` | number | Required | client toMap (348) | `joinGroupChallenge`/`confirmGroupJoin` caps (index.ts:543, 599); `toGroupChallenge` (436) | default 5 |
| `startDate` | number (millis) | Set by CF | `startGroupChallenge` (index.ts:722); client toMap writes it too but key blocked | `joinGroupChallenge` join-window (index.ts:545); `toGroupChallenge` (412) | **client write blocked**; set when challenge activates |
| `endDate` | number (millis) | Set by CF | `startGroupChallenge` (index.ts:722) | `completeGroupChallenge` expiry (index.ts:1069); `toGroupChallenge` (413) | **client write blocked**; `endOfDayMillis(start, durationDays)` |
| `bonusEnabled` | boolean | Required | client toMap (351) | `toGroupChallenge` (439) | — |
| `status` | string enum | Set by CF | CF only (`createGroupChallenge`→`waiting` index.ts:496; start/cancel/complete/expire) | every CF + `toGroupChallenge` (440) | values: `waiting` \| `active` \| `completed` \| `cancelled`. **client write blocked** |
| `participants` | array<map> | Required | CF create (index.ts:498) + arrayUnion on join (index.ts:615); client array-replace for stats (…Service.kt:179, 202, 231) | every settlement CF; `toGroupChallenge` (380) | **embedded array** (NOT a subcollection). Element shape below. Note: may deserialize as map/object — `parseParticipants` (index.ts:1939) / `parseRawParticipants` (…Service.kt:326) normalize both |
| `participantUserIds` | array<string> | Required | CF create (index.ts:508) + arrayUnion (index.ts:627) / arrayRemove (index.ts:956) | join/ownership checks (index.ts:541, 591, 849, 1055); `observeUserGroupChallenges` array-contains (…Service.kt:115) | denormalized for array-contains queries |
| `authorizationExpiresAt` | number (millis) | Set by CF | `createGroupChallenge` (index.ts:497) | `expireGroupChallenge` (index.ts:865); `toGroupChallenge` (451) | **client write blocked**; `now + 5 days` |
| `blockedDomains` | string (csv) \| null | Optional | client toMap (…Service.kt:367) | `toGroupChallenge` (448) | — |
| `createdAt` | Timestamp | Required | CF `createGroupChallenge` (index.ts:509) | `toGroupChallenge` (tolerates Timestamp or Long, …Service.kt:407) | `serverTimestamp()` |
| **CF settlement fields:** | | | | | |
| `completedAt` | number (millis) | At completion | `completeGroupChallenge` (index.ts:1136, 1281) | — | `Date.now()` |
| `prizePool` | number (cents) | At completion | `completeGroupChallenge` (index.ts:1138 nobody-failed=0; 1282 distributablePot) | — | — |
| `appFee` | number (cents) | At completion | `completeGroupChallenge` (index.ts:1139=0; 1283) | `backfillCounters` (index.ts:2111) | 10% of failed pot |
| `prizePerWinner` | number (cents) | At completion | `completeGroupChallenge` (index.ts:1140, 1284) | `toGroupChallenge` reads as `prizePerWinner`/`perWinnerBonus` (…Service.kt:446) | per-winner bonus |
| `nobodyFailed` | boolean | At completion | `completeGroupChallenge` (index.ts:1141, 1285) | — | — |

### Embedded `participants[]` element shape

Written by CF (index.ts:498–507 create, index.ts:615–626 join) and by client `toMap` (…Service.kt:353–363).
Settlement mutates these in place.

| Field | Type | Written by | Read by | Notes |
|-------|------|------------|---------|-------|
| `userId` | string | CF (index.ts:499/616); client (…Service.kt:354) | everywhere | — |
| `displayName` | string | CF (index.ts:500/617); client (…Service.kt:355) | `toGroupChallenge` (…Service.kt:384, strips `@domain`) | — |
| `paymentIntentId` | string | CF (index.ts:501/618); client (…Service.kt:356) | all Stripe settlement | — |
| `amountCents` | number (cents) | CF (index.ts:502/619); client (…Service.kt:357) | pot math (index.ts:1157, 1183) | == buyInCents |
| `status` | string enum | CF (`active` on create/join) + settlement; client (…Service.kt:358) | settlement classification | `active` \| `failed` \| `success` \| `completed` \| `refunded` \| `lost` (ParticipantStatus client enum is narrower: ACTIVE/FAILED/COMPLETED/…) |
| `opensToday` | number | CF (0 on create, index.ts:504/623); client stats (…Service.kt:360, 174, 230) | leaderboard; `toGroupChallenge` (399) | reset/maintained client-side |
| `timeUsedMinutes` | number | CF (0, index.ts:505/623); client stats (…Service.kt:361, 175, 200) | leaderboard; `toGroupChallenge` (400) | — |
| `joinedAt` | number (millis) | CF (index.ts:506/623); client (…Service.kt:362) | `toGroupChallenge` (401) | `Date.now()` |
| `deviceId` | string | CF on `confirmGroupJoin` (index.ts:624) | `detectSuspiciousUsers` shared-device (index.ts:2272) | `""` if omitted; NOT written by client toMap |
| `failedAt` | number (millis) | CF `failParticipant` (index.ts:802) | — | only on self-fail |
| `payoutStatus` | string | CF settlement (index.ts:1133, 1178, 1211, 1240, …) | `toGroupChallenge` (402) | `completed` \| `pending_payout` \| `lost` \| `""` |
| `finalPayout` | number (cents) | CF settlement (index.ts:1133, 1178, 1211, …) | `toGroupChallenge` (403) | stake refund + bonus (0 for losers) |

**Lifecycle:** `createGroupChallenge` (status `waiting`, participants=[creator], authExpiresAt) →
joins via `joinGroupChallenge`(PI) + `confirmGroupJoin`(arrayUnion) → `startGroupChallenge` captures
all PIs, sets `status=active, startDate, endDate` (or `cancelled` if <2) → `completeGroupChallenge`
sets `status=completed` + payout fields, or `cancelGroupChallenge`/`deleteGroupChallenge`/
`leaveGroupChallenge`/`expireGroupChallenge` → `status=cancelled` (PIs cancelled/refunded). Rules:
`allow delete: if false` — group docs are never hard-deleted.

---

## `groupChallenges/{groupId}/taunts/{tauntId}`

Trash-talk between participants. `tauntId` = client UUID (…Service.kt:249). Rules (lines 159–168):
any authed user reads; create requires `createdAt` int within ±5 min of `request.time` and
`message` ≤ 500 chars; update/delete blocked.

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `fromUserId` | string | Required | `sendTaunt` (…Service.kt:253) | `countTauntsToday` (…Service.kt:273); `observeUnshownTaunts` (299) | — |
| `fromDisplayName` | string | Required | (…Service.kt:254) | `observeUnshownTaunts` (300) | — |
| `toUserId` | string | Required | (…Service.kt:255) | `observeUnshownTaunts` query (…Service.kt:286); `countTauntsToday` (275) | — |
| `message` | string | Required | (…Service.kt:256) | `observeUnshownTaunts` (301) | rules cap ≤ 500 |
| `createdAt` | number (millis) | Required | (…Service.kt:257) | `countTauntsToday` (276); `observeUnshownTaunts` (302) | `System.currentTimeMillis()`; rules require ±5 min of `request.time` |
| `shown` | boolean | Required | create (…Service.kt:258); `markTauntShown` update→true (…Service.kt:317) | `observeUnshownTaunts` filter (…Service.kt:295) | ⚠️ `markTauntShown` does `.update("shown", true)` but rules say `update: if false` for taunts — this client update is rejected under current rules |

---

## `groupChallenges/{groupId}/participants/{participantId}`  (future-proof — not currently written)

No code writes this subcollection (participants are the embedded array above). Rules exist (lines
174–186) and a **collection-group index** `(userId ASC, status ASC)` exists (indexes.json:44).

> ⚠️ **Discrepancy:** `runPermissionViolationCheck` queries
> `collectionGroup("participants").where("userId"==).where("status"=="active")` (index.ts:1505) to
> capture group stakes on permission loss. Since participants live in an **embedded array**, this
> query currently matches **nothing** — the group-capture branch is effectively dormant until/unless
> participants are migrated to this subcollection. Fields it would read: `userId`, `status`,
> `paymentIntentId`; fields it would write on a fail: `status=failed, failReason=permission_violation,
> failedAt` (index.ts:1521).

---

## `payoutRequests/{requestId}`

Manual payout requests (group winnings via IBAN). Auto-id. Rules (lines 192–206): owner creates with
`userId==uid`, no `paidAt`, `status` absent or `"pending"`; owner+admin read; admin-only update
(mark paid/rejected); delete blocked.

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `userId` | string | Required | `ProfileViewModel` (ProfileViewModel.kt:287); `GroupChallengeDetailViewModel` (…DetailViewModel.kt:407) | admin dashboard; rules read-guard | must == auth uid |
| `displayName` | string | Optional | (ProfileViewModel.kt:288; …DetailViewModel.kt:408) | admin | — |
| `payoutName` | string | Optional | (ProfileViewModel.kt:289; …DetailViewModel.kt:410) | admin | account holder |
| `iban` | string | Optional | (ProfileViewModel.kt:290; …DetailViewModel.kt:409) | admin | — |
| `amountCents` | number (cents) | Required | (ProfileViewModel.kt:291; …DetailViewModel.kt:411) | admin | == `perWinnerBonus`/group.amountCents |
| `groupId` | string | Required | (ProfileViewModel.kt:292; …DetailViewModel.kt:412) | admin | — |
| `status` | string enum | Required | created `"pending"` (ProfileViewModel.kt:294; …DetailViewModel.kt:413); admin → paid/rejected | composite indexes (indexes.json:4) | `pending` \| (admin sets paid/rejected). Rules force create-value to `pending` |
| `createdAt` | Timestamp | Required | `serverTimestamp()` (ProfileViewModel.kt:293 uses `Timestamp.now()`; …DetailViewModel.kt:414 uses `serverTimestamp()`) | `(status ASC, createdAt DESC)` index (indexes.json:4) | — |
| `paidAt` | Timestamp \| number | Optional (admin) | admin dashboard | `(status ASC, paidAt DESC)` index (indexes.json:12) | rules forbid on create |

---

## `usernames/{username}`

Unique handle registry. Doc id = lowercase username. Rules (lines 213–219): authed read; create only
if not exists and `uid==auth.uid`; no update/delete.

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `uid` | string | Required | `saveUsername` txn (FirestoreService.kt:150) | rules `create` guard | owner of the handle |
| `createdAt` | Timestamp | Required | (FirestoreService.kt:150) | — | `Timestamp.now()` |

> Existence is also probed by `isUsernameAvailable` (FirestoreService.kt:122).

---

## `supportTickets/{ticketId}`

In-app support tickets. Auto-id (`.add`). Rules (lines 230–247): owner creates with `userId==uid`,
`createdAt` int within ±5 min, `subject`≤200, `message`≤5000; owner reads own; admin-only update/list;
delete blocked.

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `userId` | string | Required | `SupportViewModel` (SupportViewModel.kt:86) | admin; rules read-guard | == auth uid |
| `username` | string | Optional | (SupportViewModel.kt:87) | admin | — |
| `email` | string | Optional | (SupportViewModel.kt:88) | admin | — |
| `category` | string | Required | (SupportViewModel.kt:89) | admin | `category.firestoreValue` |
| `subject` | string | Required | (SupportViewModel.kt:90) | admin | rules cap ≤ 200 |
| `message` | string | Required | (SupportViewModel.kt:91) | admin | rules cap ≤ 5000 |
| `status` | string enum | Required | created `"open"` (SupportViewModel.kt:92); admin resolves | `(status ASC, createdAt DESC)` + `(status ASC, resolvedAt DESC)` indexes (indexes.json:20, 28) | `open` \| (admin → resolved/closed) |
| `appVersion` | string | Optional | (SupportViewModel.kt:93) | admin | `BuildConfig.VERSION_NAME` |
| `deviceModel` | string | Optional | (SupportViewModel.kt:94) | admin | `MANUFACTURER MODEL` |
| `androidVersion` | string | Optional | (SupportViewModel.kt:95) | admin | `Build.VERSION.RELEASE` |
| `createdAt` | number (millis) | Required | (SupportViewModel.kt:96) | rules ±5-min check; index | `System.currentTimeMillis()` (NUMBER, not Timestamp — note the index orders on it) |
| `resolvedAt` | number/Timestamp \| null | Optional | created `null` (SupportViewModel.kt:97); admin sets on resolve | `(status, resolvedAt DESC)` index | — |
| `adminReply` | string | Optional (admin) | admin dashboard | owner *can* read it (lives on ticket) | per rules note: private content goes to `adminNotes` instead |
| `adminReplyAt` | timestamp/number | Optional (admin) | admin dashboard | — | — |

### `supportTickets/{ticketId}/adminNotes/{noteId}`
Private admin-only notes (owner can never read). Rules: `read, write: if admin` (lines 253–255).
Shape is admin-dashboard-defined (not written from the app) — treat as free-form admin content.

---

## `antiCheatReviews/{userId}`

One doc per reviewed user (doc id = userId). Admin-only read+write (rules lines 263–265). Written by
the admin dashboard; read by `detectSuspiciousUsers` via Admin SDK (index.ts:2325).

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `decision` | string | Optional | admin dashboard | `detectSuspiciousUsers` (index.ts:2343) | e.g. cleared / banned |
| `reviewedAt` | number (millis) | Optional | admin dashboard | `detectSuspiciousUsers` (index.ts:2343) | — |
| `note` | string | Optional | admin dashboard | `detectSuspiciousUsers` (index.ts:2343) | — |

---

## `counters/{doc}`  (only doc id used: `global`)

Dashboard statistics. Admin read; CF-only writes (rules lines 269–272). Bumped atomically via
`FieldValue.increment` (`bumpCounters`, index.ts:113) or overwritten by `backfillCounters`
(index.ts:2124).

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `totalUsers` | number | Required | `onUserCreated` (index.ts:1988); `backfillCounters` (index.ts:2115) | admin dashboard | — |
| `totalActiveChallenges` | number | Required | `createPaymentIntent` +1 (index.ts:213); decremented on settle (index.ts:271, 451, 1491, 1580, 1822, 1895); `backfillCounters` | admin | solo Hard Mode only |
| `totalCompletedChallenges` | number | Required | `cancelOrRefundPayment` +1 (index.ts:451); reconciliation (index.ts:1895); `backfillCounters` | admin | solo Hard Mode wins |
| `totalFailedChallenges` | number | Required | `capturePayment` (index.ts:271); `checkPermissionViolations` (index.ts:1491, 1580); reconciliation (index.ts:1822); `backfillCounters` | admin | solo Hard Mode losses |
| `totalRevenueCents` | number (cents) | Required | capture/refund/reconciliation/group-fee paths (index.ts:273, 452, 1494, 1583, 1822, 1290); `backfillCounters` | admin | retained fees + captured stakes + 10% group fees |
| `updatedAt` | number (millis) | Required | `bumpCounters` (index.ts:115); `backfillCounters` (index.ts:2120) | admin | `Date.now()` |

> Scope: active/completed/failed track **solo Hard Mode** only (`mode=="hard" && !groupChallengeId`);
> `totalRevenueCents` also includes the 10% group-challenge fee.

---

## `broadcasts/{broadcastId}`

Admin → all users message banner. Auto-id. Rules (lines 277–280): authed read; admin-only write.

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `active` | boolean | Required | admin dashboard | `DashboardViewModel` query `active==true` (DashboardViewModel.kt:151) | `(active ASC, createdAt DESC)` index (indexes.json:36) |
| `title` | string | Required | admin | `DashboardViewModel` (DashboardViewModel.kt:161) | — |
| `message` | string | Required | admin | `DashboardViewModel` (DashboardViewModel.kt:162) | — |
| `createdAt` | timestamp/number | Required | admin | orderBy DESC (DashboardViewModel.kt:152); index | — |

---

## `config/app`  (single doc)

Remote config / feature flags / version gates / money-safety flags. Rules (lines 285–288): authed
read; admin-only write. Read defensively (fail-open for UX; fail-SAFE for money).

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `minVersionCode` | number | Optional | admin | `AppConfigRepository` (AppConfigRepository.kt:77) | force-update floor |
| `latestVersionCode` | number | Optional | admin | (AppConfigRepository.kt:78) | soft-update prompt |
| `maintenanceMode` | boolean | Optional | admin | (AppConfigRepository.kt:79) | default false |
| `maintenanceMessage` | string | Optional | admin | (AppConfigRepository.kt:80) | — |
| `hardModeEnabled` | boolean | Optional | admin | (AppConfigRepository.kt:81) | default true |
| `groupChallengeEnabled` | boolean | Optional | admin | (AppConfigRepository.kt:82) | default true |
| `updateUrl` | string | Optional | admin | (AppConfigRepository.kt:83) | — |
| `hardModeMinStake` | number (cents) | Optional | admin | (AppConfigRepository.kt:84) | — |
| `hardModeMaxStake` | number (cents) | Optional | admin | (AppConfigRepository.kt:85) | — |
| `groupMinBuyIn` | number (cents) | Optional | admin | (AppConfigRepository.kt:86) | — |
| `groupMaxBuyIn` | number (cents) | Optional | admin | (AppConfigRepository.kt:87) | — |
| `reconciliationEnabled` | boolean | Optional | admin | `runDueChallengeReconciliation` (index.ts:1714) | **money-safety**: default false; reconciliation no-ops unless `=== true` |
| `reconciliationDryRun` | boolean | Optional | admin | `runDueChallengeReconciliation` (index.ts:1715) | default true; only an explicit `false` arms real Stripe ops |
| `wentDarkGraceMs` | number (millis) | Optional | admin | `runDueChallengeReconciliation` (index.ts:1716) | **money-safety**: went-dark forfeit grace. Missing/non-positive/unreadable ⇒ `Number.MAX_SAFE_INTEGER` ⇒ NEVER forfeit. Recommended 72h = `259200000` |

---

## `rateLimits/{uid}`

Per-UID fixed-window throttle store. Fully client-denied (`read, write: if false`, rules lines
294–296); only `enforceRateLimit` via Admin SDK touches it (index.ts:132).

| Field | Type | Req/Opt | Written by | Read by | Notes |
|-------|------|---------|------------|---------|-------|
| `{key}` | map `{windowStart:number(millis), count:number}` | per-endpoint | `enforceRateLimit` (index.ts:151) | `enforceRateLimit` (index.ts:138) | one map field per throttled endpoint name (e.g. `createPaymentIntent`, `capturePayment`, …) |

---

## `admin/{document=**}`

Fully denied to clients (rules lines 300–302). No app/CF writes observed.

---

## Firestore ⇄ Room mapping (challenge doc vs `ChallengeEntity`)

The Room `ChallengeEntity` (data/local/db/entity/ChallengeEntity.kt) and the Firestore challenge doc
overlap but are **not** identical. Key differences:

**In BOTH (round-tripped):** `id`, `appPackageName`, `appPackageNames` (csv), `appDisplayName`,
`mode`, `limitType`, `limitValueMinutes`, `limitValueSessions`, `startDate`, `endDate`, `amountCents`,
`stripePaymentIntentId`, `customMotivation`, `status`, `createdAt`, `dailyBudgetMinutes`,
`blockedDomains`, `partialBlockDomains`, `blockingType`, `blockAdultContent`, `scheduleStartTime`,
`scheduleEndTime`, `activeDays`, `partialBlockSections` (Room: csv `partial_block_sections`;
Firestore: **array**), `isPartialBlockOnly`, all `redemption*` fields, `isRedemption`,
`originalChallengeId`, `originalPaymentIntentId`, `refundAmountCents`, `pendingLimitValue`,
`pendingLimitAppliesAt`.

**Room-ONLY (never written to Firestore):**
- `completionShown` (in `Challenge` model + Room; NOT in `toMap()`).
- `groupChallengeId` (in `Challenge` model + Room; NOT in `toMap()` — though CFs *read* `c.groupChallengeId`, current docs never carry it; see note under the challenge collection).
- `sessionDurationMinutes` exists on the `Challenge` model but is **not** in the solo `toMap()` (it
  IS written for *group* challenges). Solo Firestore docs omit it.

**Firestore-ONLY (NOT in `ChallengeEntity` / not persisted to Room):**
- `deviceId`, `isRooted` (anti-cheat metadata — written at Hard Mode creation, never read back into Room).
- `syncedAt` (Firestore bookkeeping `Timestamp`).
- The **CF-written settlement money fields**: `payoutStatus`, `payoutAmount`, `appFeeAmount`,
  `payoutDate`, `failedAt`, `reconciliationLowEvidence`. Room tracks the outcome only via `status`
  (and locally-computed fields); it does **not** store `payoutStatus`/`payoutDate`/etc.

**Read-back exception — `failReason` (Room column, migration 25→26):** unlike the other settlement
fields, `failReason` **IS** a Room column on `ChallengeEntity`/`Challenge`. It is NOT in `toMap()`
(the Firestore side is CF-written by `markChallengeFailed`/`checkPermissionViolations`/reconciliation),
but it is **written into Room two ways**: (1) locally at loss time by
`ChallengeRepositoryImpl.updateChallengeStatus(FAILED, failReason)` → `challengeDao.updateFailReason`,
and (2) pulled from Firestore on sync — `fetchFinishedChallenges` parses it (FirestoreService.kt:490)
and `SyncRepositoryImpl` writes it via `updateFailReason` for a server-detected loss the device never
classified (SyncRepositoryImpl.kt:122). Sync **never** overwrites an already-terminal local row's
reason. UX-only (the loss dialog); never money logic.

**Settlement read-back:** the client learns server settlement via `fetchChallengeSettlement`
(FirestoreService.kt:277), which reads only `status` + `payoutStatus` into the transient
`ChallengeSettlement` data class — these are **not** mirrored into Room columns.

---

## Type / unit gotchas (quick reference)

- **`endDate` / `startDate` (challenge & group) = NUMBER millis**, not Firestore `Timestamp`. CFs
  defensively accept either (`tsToMillis`, `?.toMillis()`), but the client always writes a NUMBER.
- **`createdAt` is inconsistent across collections:** `Timestamp` on `users`, challenge docs
  (`createdAt` is millis NUMBER via toMap!), group `createdAt` is `Timestamp` (CF), `supportTickets`/
  `taunts` `createdAt` are NUMBER millis (rules enforce int ±5 min), `payoutRequests`/`broadcasts`
  `createdAt` are `Timestamp`. **Challenge `createdAt` = NUMBER millis** (toMap FirestoreService.kt:757)
  despite the same field name being a `Timestamp` elsewhere — do not assume.
- **`partialBlockSections` is the only true array on the solo challenge doc**; `appPackageNames`,
  `blockedDomains`, `partialBlockDomains`, `activeDays` are **csv strings**.
- **`participants` (group) is an embedded array of maps**, occasionally deserialized as a map/object —
  always normalize via `parseParticipants` / `parseRawParticipants`.
- **`amountCents` and every `*Cents` field = integer cents.** `500` = €5.00.
- **`limitExceeded` is monotonic** (`false→true` only) and the daily-log delete is CF-only — both are
  enforced by rules for money tamper-evidence.
- **Money-safety flags invert the usual fail-open contract:** `reconciliationEnabled`/
  `reconciliationDryRun` default to OFF/DRY-RUN; a config read failure ⇒ disabled.

---
---

# PART 2 — FIELD-LIFECYCLE LAYER

> This part answers a different question than Part 1: **when does each field first come into
> existence, and which concrete event creates / changes / removes it.** Part 1 = "what exists";
> Part 2 = "when and how it appears and mutates".
>
> **Event vocabulary** (used throughout — these are the only writers):
>
> | Event | Trigger | Code |
> |-------|---------|------|
> | **PI-create** | `createPaymentIntent` CF | index.ts:174 — *does NOT write the challenge doc*; bumps `counters.totalActiveChallenges` |
> | **CREATE** | device writes the challenge doc after PaymentSheet confirms | `saveChallenge`→`Challenge.toMap()` FirestoreService.kt:205/742 |
> | **WIN-server** | server settles a win/refund | `cancelOrRefundPayment` index.ts:435; reconciliation WIN index.ts:1885 |
> | **WIN-device-wrap** | device wraps a win after the CF returns | `ChallengeRepositoryImpl.updateChallengeStatus(COMPLETED)`→`FirestoreService.updateChallengeStatus` (status+syncedAt) repo:198, FirestoreService.kt:214 |
> | **LOSS-device** | device-detected loss (limit exceeded) OR abandon | capture (Hard Mode) → `updateChallengeStatus(FAILED)` (worker:564) → `ChallengeRepositoryImpl` repo:196 → **`markChallengeFailed` CF** (index.ts:291) → **`status:"failed"` written IN PLACE** (`failReason="client_loss"`, `failedAt`); **doc + nested `dailyLogs` RETAINED** (no delete, no cascade) |
> | **LOSS-server** | permission/usage capture, or reconciliation loss | `checkPermissionViolations` index.ts:1485/1574; reconciliation LOSS index.ts:1789/1820 |
> | **ABANDON** | user abandons an active solo Hard Mode | `ActiveChallengeViewModel.abandonChallenge` VM:228 → capture → `markFailedAndFinish` → same **`markChallengeFailed`** in-place path as LOSS-device (doc retained) |
> | **PERM-mark** | device records permission loss/restore | `PermissionCheckWorker` kt:121/89 |
> | **USAGE-mark** | device records a usage violation | `PermissionCheckWorker` kt:217 |
> | **REDEEM-meta** | device stamps redemption eligibility on the *failed original* | `updateChallengeRedemptionInfo` FirestoreService.kt:635; `updateChallengeRedemptionChallengeId` FirestoreService.kt:667 |
> | **PENDING-LIMIT** | device schedules a midnight limit cut | `updateChallengePendingLimit` FirestoreService.kt:719 |
> | **DELETE** | doc removed (genuine user-initiated removal or account deletion — **no longer the loss path**) | `deleteChallenge` FirestoreService.kt:235; `deleteUserData` FirestoreService.kt:697 |
>
> **Loss-path note (updated — the old asymmetry is GONE):** a **device-detected loss/abandon now
> KEEPS the challenge document.** After capturing the stake, the device calls the **`markChallengeFailed`
> CF**, which writes `status:"failed"` + the **passed `failReason`** (`limit_exceeded` / `abandon` /
> `permission_violation`; `client_loss` only as a no-cause fallback) + `failedAt` **in place** via the
> Admin SDK; the doc and its nested tamper-evident `dailyLogs` are RETAINED. (Previously a device loss
> DELETED the whole doc + cascade-deleted its `dailyLogs`; that destructive path is removed.) It still
> does NOT write `payoutStatus` — only the capture path owns that. So a `status:"failed"` solo
> challenge doc can now come from **either** a device-detected loss (`failReason` ∈
> {`limit_exceeded`,`abandon`,`permission_violation`}, no `payoutStatus`) **or** a server-detected loss
> (permission/usage/reconciliation/went-dark —
> `failReason` ∈ {`permission_violation`,`usage_violation`,`reconciliation`,`device_dark`}, `payoutStatus:"captured"`).
> The remaining asymmetry is the `failReason` value + whether `payoutStatus` is present, NOT the
> doc's existence.

---

## `users/{uid}/challenges/{challengeId}` — lifecycle table

| Field | Type | Created/Set BY (event) | Changed/removed BY | Notes |
|-------|------|------------------------|--------------------|-------|
| `id` | string | CREATE (toMap:743) | — | == doc id; immutable |
| `appPackageName` | string | CREATE (744) | — | `""` for WEBSITE |
| `appPackageNames` | string (csv) | CREATE (745) | — | — |
| `appDisplayName` | string | CREATE (746) | — | — |
| `mode` | string `soft\|hard` | CREATE (747) | — | drives every CF `mode=="hard"` filter |
| `limitType` | string `time\|sessions\|time_budget` | CREATE (748) | — | — |
| `limitValueMinutes` | number | CREATE (749) | — | — |
| `limitValueSessions` | number\|null | CREATE (750) | — | — |
| `startDate` | **number (millis)** | CREATE (751) | — | client UPDATE blocked by rules |
| `endDate` | **number (millis)** | CREATE (752) | — | client UPDATE blocked; numeric range-queried by reconciliation |
| `amountCents` | number (cents)\|null | CREATE (753) | — | client UPDATE blocked — refund trust anchor |
| `stripePaymentIntentId` | string\|null | CREATE (754) | — | client UPDATE blocked |
| `customMotivation` | string\|null | CREATE (755) | — | — |
| `status` | string `active\|completed\|failed` | CREATE (756, ="active") | WIN-server→`completed` (441); WIN-device-wrap→`completed`+syncedAt (FirestoreService.kt:219); **LOSS-device/ABANDON→`failed` IN PLACE via `markChallengeFailed` CF (index.ts:314) — doc RETAINED**; LOSS-server→`failed` (1486/1574); reconciliation (1750/1789/1886) | client UPDATE of `status` blocked by rules — only a CF (settlement or `markChallengeFailed`) writes it |
| `createdAt` | **number (millis)** | CREATE (757) | — | ⚠ NUMBER here (unlike `Timestamp` `createdAt` on other collections) |
| `dailyBudgetMinutes` | number\|null | CREATE (758) | — | TIME_BUDGET |
| `blockedDomains` | string (csv)\|null | CREATE (759) | — | — |
| `partialBlockDomains` | string (csv)\|null | CREATE (760) | — | — |
| `blockingType` | string `app\|website` | CREATE (761) | — | — |
| `blockAdultContent` | boolean | CREATE (762) | — | — |
| `scheduleStartTime` | string\|null | CREATE (763) | — | — |
| `scheduleEndTime` | string\|null | CREATE (764) | — | — |
| `activeDays` | string (csv)\|null | CREATE (765) | — | — |
| `partialBlockSections` | **array<string>** | CREATE (766) | — | only true array on the doc |
| `isPartialBlockOnly` | boolean | CREATE (767) | — | — |
| `isRedemption` | boolean | CREATE (768) | — | `true` ⇒ this doc IS a redemption (amountCents 0) |
| `originalChallengeId` | string\|null | CREATE (769) | — | redemption only |
| `originalPaymentIntentId` | string\|null | CREATE (770) | — | redemption only |
| `refundAmountCents` | number (cents)\|null | CREATE (771) | — | redemption only |
| `pendingLimitValue` | number\|null | CREATE (772, usually null) | PENDING-LIMIT (FirestoreService.kt:727) | applied + cleared at midnight client-side |
| `pendingLimitAppliesAt` | number (millis)\|null | CREATE (773) | PENDING-LIMIT (728) | — |
| `deviceId` | string\|null | CREATE (775) | — | Hard Mode anti-cheat |
| `isRooted` | boolean\|null | CREATE (776) | — | Hard Mode anti-cheat |
| `syncedAt` | **Timestamp** | CREATE (777) | WIN-device-wrap re-stamps it (FirestoreService.kt:219) | bookkeeping |
| `redemptionEligible` | boolean | **REDEEM-meta** (FirestoreService.kt:651) — ABSENT until the original fails | — | set on the *failed original* doc |
| `redemptionDeadline` | number (millis) | REDEEM-meta (652) — absent until fail | — | failedAt + 3d |
| `redemptionShowAfter` | number (millis) | REDEEM-meta (653) — absent until fail | — | failedAt + 24h |
| `redemptionRefundAmount` | number (cents) | REDEEM-meta (654) — absent until fail | — | — |
| `redemptionDays` | number | REDEEM-meta (655) — absent until fail | — | — |
| `redemptionLimit` | number | REDEEM-meta (656) — absent until fail | — | — |
| `redemptionChallengeId` | string | REDEEM-meta-2 (FirestoreService.kt:677) — absent until a redemption is started | — | non-null ⇒ already used |
| `payoutStatus` | string `refunded\|captured` | **ABSENT until settlement.** WIN-server→`refunded` (441); LOSS-server→`captured` (1489/1578); reconciliation (1789/1820/1887) | — | **never written on LOSS-device** — `markChallengeFailed` writes only `status/failReason/failedAt`, so a device loss leaves the doc with `status:"failed"` but NO `payoutStatus` |
| `payoutAmount` | number (cents) | **ABSENT until WIN.** WIN-server (442); reconciliation WIN (1888) | — | refunded amount |
| `appFeeAmount` | number (cents) | **ABSENT until WIN.** WIN-server (443); reconciliation WIN (1889) | — | stake − refund |
| `payoutDate` | **number (millis)** | **ABSENT until settlement.** WIN-server (444); reconciliation WIN (1890) | — | `Date.now()` |
| `failReason` | string `limit_exceeded\|abandon\|permission_violation\|usage_violation\|reconciliation\|device_dark\|client_loss` | **ABSENT until LOSS.** LOSS-device → the **passed** cause via `markChallengeFailed` (index.ts:315): `limit_exceeded` (worker/soft-fail), `abandon` (abandon flow), `permission_violation` (perm worker); LOSS-server → `permission_violation`/`usage_violation` (1530/1566), `reconciliation`/`device_dark` (1874/1905). `client_loss` only when a caller omits the cause | — | set on LOSS-device now (doc retained); read back into Room `failReason` |
| `failedAt` | **number (millis)** | **ABSENT until LOSS.** LOSS-device via `markChallengeFailed` (index.ts:316); LOSS-server (1488/1578, 1789/1820) | — | — |
| `reconciliationLowEvidence` | boolean | **ABSENT unless** a reconciliation WIN ran with zero nested dailyLogs (index.ts:1893) | `detectSuspiciousUsers` Signal 6 (index.ts:2302) | admin-review flag — now surfaced in the Anti-Cheat dashboard (6 pts) |

### Event timeline — `users/{uid}/challenges/{challengeId}`

```
PI-create (createPaymentIntent CF, index.ts:174)
    └─ challenge doc: NOT written. (counters.totalActiveChallenges += 1 for solo hard)

CREATE (saveChallenge, FirestoreService.kt:205)  — merge set, all toMap fields
    + status="active"
    + mode, limitType, limitValue*, dailyBudgetMinutes
    + amountCents (cents|null), stripePaymentIntentId
    + startDate (millis number), endDate (millis number), createdAt (millis number)
    + isRedemption, originalChallengeId, originalPaymentIntentId, refundAmountCents
    + deviceId, isRooted (Hard Mode anti-cheat)
    + partialBlockSections (ARRAY), all blocking/schedule fields, syncedAt (Timestamp)
    (payoutStatus / payoutAmount / appFeeAmount / payoutDate / failReason / failedAt ABSENT)

── then exactly ONE of the terminal branches ───────────────────────────────────

WIN (end date reached, no limit-exceeded day):
  WIN-server  (cancelOrRefundPayment CF, index.ts:435, merge set)
    ~ status: "active" → "completed"
    + payoutStatus = "refunded"
    + payoutAmount  = floor(stake*0.80)  (redemption: floor(originalStake*0.60))
    + appFeeAmount  = stake − payoutAmount
    + payoutDate    = Date.now()  (millis number)
    (counters: active−1, completed+1, revenue += appFee)
  WIN-device-wrap  (ChallengeRepositoryImpl.updateChallengeStatus(COMPLETED) → FirestoreService.kt:219)
    ~ status = "completed"  (idempotent re-write of same value)
    ~ syncedAt = Timestamp.now()  (re-stamped)

LOSS — limit exceeded on a day, OR ABANDON (solo Hard Mode):   (doc RETAINED)
  1. capturePayment CF (index.ts:222): captures stake; writes users/{uid}/paymentCaptures/* ;
     counters active−1, failed+1, revenue += amount.  (NO field written to the challenge doc.)
  2. updateChallengeStatus(FAILED, failReason) → ChallengeRepositoryImpl repo:196 → markChallengeFailed CF (index.ts:291)
       ~ status      = "active" → "failed"   (in place, Admin SDK)
       + failReason  = the PASSED cause: "limit_exceeded" (DailyEvaluationWorker / soft-fail) |
                       "abandon" (abandon flow) | "permission_violation" (PermissionCheckWorker).
                       "client_loss" only if a caller passes no cause.  (also persisted to Room.)
       + failedAt    = Date.now() (millis number)
       ⇒ the challenge doc AND its nested dailyLogs are KEPT (audit trail + redemption refund preserved).
       (NO payoutStatus — only the capture path owns it. Idempotent if already terminal.)
     NOTE: a Soft Mode device fail takes the same markChallengeFailed in-place path (no money step).

LOSS — permission loss > 24h / usage violation > 1h (server, doc KEPT):
  checkPermissionViolations CF (index.ts:1485 perm / 1574 usage), update:
    ~ status      = "failed"
    + failReason  = "permission_violation" | "usage_violation"
    + failedAt    = now (millis number)
    + payoutStatus = "captured"
    (counters active−1, failed+1, revenue += captured amount)

RECONCILIATION (server safety net, only if config flags armed; index.ts:1787+):
  • payoutStatus already set but status=="active" → ~ status → completed/failed (stale-status fix)
  • WENT-DARK (B2.5, NEW): lastSeenAt (or startDate if never beat) older than config wentDarkGraceMs
      → LOSS, failReason="device_dark". Runs for due AND not-yet-due challenges. A proven
        limitExceeded loss takes precedence (failReason="reconciliation"). FAIL-SAFE: grace
        defaults to MAX_SAFE_INTEGER ⇒ never fires.
  • WIN  → same field delta as WIN-server (+ reconciliationLowEvidence=true if 0 dailyLogs)
  • LOSS → same field delta as LOSS-server but failReason="reconciliation" (or "device_dark" if went-dark)

REDEEM-meta (on the FAILED ORIGINAL doc — now ALWAYS present after a loss, since device losses
             retain the doc via markChallengeFailed; the doc to stamp always exists):
    + redemptionEligible, redemptionDeadline, redemptionShowAfter,
      redemptionRefundAmount, redemptionDays, redemptionLimit   (FirestoreService.kt:651)
    + redemptionChallengeId  (when the redemption is actually started, FirestoreService.kt:677)

DELETE (deleteChallenge on genuine user-initiated removal, or deleteUserData on account deletion —
        NO LONGER the loss path):
    ⇒ doc removed + onChallengeDeleted cascades nested dailyLogs.
```

> **History note:** `fetchFinishedChallenges` reads `status in [completed, failed]`
> (FirestoreService.kt:419). Now that device losses RETAIN the doc (via `markChallengeFailed`), it can
> return `failed` docs from **both** device-detected losses (`failReason` ∈
> {`limit_exceeded`,`abandon`,`permission_violation`}, or legacy `client_loss`) and server-side
> captures (`permission_violation`/`usage_violation`/`reconciliation`/`device_dark`). (Previously
> device losses deleted the doc, so only server captures appeared.)

---

## `users/{uid}/challenges/{challengeId}/dailyLogs/{dateKey}` — lifecycle

`dateKey` = `log.date.toString()` (epoch-millis as string). Created/merged each evaluation tick.

| Field | Type | Created/Set BY | Changed/removed BY | Notes |
|-------|------|----------------|--------------------|-------|
| `id`,`challengeId`,`date` | string/string/number(millis) | first `saveDailyLog` merge (FirestoreService.kt:780) | — | identity |
| `totalMinutes`,`openCount`,`consciousOpens`,`overlayPausedMs`,`pointsEarned`,`moneyLostCents`,`budgetUsedMinutes`,`budgetRemainingMinutes` | number(s) | first `saveDailyLog` | re-merged each tick with new values | running daily counters |
| `limitExceeded` | boolean | first `saveDailyLog` (default false) | flips **`false→true` only** (rules block `true→false`) | money tamper-evidence gate read by WIN-gate (index.ts:343) & reconciliation `lossProven` (index.ts:1779) |
| **(whole doc)** | — | per-day create | **DELETED only by `onChallengeDeleted` cascade** (index.ts:1997) when the parent challenge is deleted — client delete blocked by rules. A device loss/abandon NO LONGER deletes the parent (it writes `status:"failed"` in place via `markChallengeFailed`), so these logs now survive a loss. | — |

> Timeline: `create` (clean day → `limitExceeded=false`) → repeated `merge` updates as usage
> accrues → possibly `false→true` on a violation day → cascade-`delete` ONLY if the parent challenge is
> genuinely deleted (user-initiated cancel/cleanup or account deletion — **not** on a loss/abandon,
> which now retains the doc + logs). No standalone delete path.

---

## `users/{uid}/dailyLogs/{challengeId}_{date}` — lifecycle (flat, reinstall-persistence)

| Field | Type | Created/Set BY | Changed/removed BY | Notes |
|-------|------|----------------|--------------------|-------|
| `challengeId`,`date` | string / number(millis) | first flat write (`updateDailyLogConsciousOpens` FirestoreService.kt:543 / `updateDailyLogBudget` 575) | — | identity; `date` range-queried |
| `consciousOpens` | number | `updateDailyLogConsciousOpens` (544) | re-merged on each conscious-open; ProfileViewModel debug reset (kt:736/774) | survives reinstall |
| `budgetUsedMs`,`budgetRemainingMs` | number (millis) | `updateDailyLogBudget` (578/579) | re-merged every 10 s tick | source of truth for budget |
| `updatedAt` | number (millis) | every write (545/580) | every write | `System.currentTimeMillis()` |

> No delete path observed (these are not cascade-cleaned by `onChallengeDeleted`, which only targets
> the *nested* per-challenge `dailyLogs`). They age out logically by date key, not by deletion.

---

## `groupChallenges/{groupId}` — lifecycle

Canonical creator is the CF; the client only mutates the embedded `participants` array (stats).

| Field | Type | Created/Set BY (event) | Changed/removed BY | Notes |
|-------|------|------------------------|--------------------|-------|
| `groupId`,`code`,`creatorUserId`,`appPackageNames`,`appDisplayName`,`limitType`,`limitValue*`,`sessionDurationMinutes`,`durationDays`,`buyInCents`,`maxParticipants`,`bonusEnabled`,`blockedDomains` | mixed | **createGroupChallenge** CF (index.ts:492, spreads client `groupData`) | `code` may be regenerated on collision at create (index.ts:486) | config fields |
| `status` | string `waiting\|active\|completed\|cancelled` | createGroupChallenge → `"waiting"` (index.ts:496) | `startGroupChallenge`→`active` or `cancelled` (722/672); `completeGroupChallenge`→`completed` (1135/1280); `cancel/delete/leave/expire`→`cancelled` (777/1026/959/894) | client write blocked by rules |
| `authorizationExpiresAt` | number (millis) | createGroupChallenge → `now + 5d` (index.ts:497) | — | client write blocked |
| `participants` | **array<map>** | createGroupChallenge → `[creator]` (index.ts:498) | `confirmGroupJoin` arrayUnion (615); client stats array-replace (…Service.kt:179/202/231); per-participant settlement mutation; `leave` arrayfilter (951) | element shape & per-field lifecycle below |
| `participantUserIds` | array<string> | createGroupChallenge (index.ts:508) | arrayUnion on join (627); arrayRemove on leave (956) | denormalized for queries |
| `createdAt` | **Timestamp** | createGroupChallenge `serverTimestamp()` (index.ts:509) | — | ⚠ Timestamp (challenge `createdAt` is millis-number) |
| `startDate` | **number (millis)** | **ABSENT until start.** `startGroupChallenge` (index.ts:722) | — | client write blocked |
| `endDate` | **number (millis)** | **ABSENT until start.** `startGroupChallenge` `endOfDayMillis(start,days)` (index.ts:722) | — | client write blocked |
| `completedAt` | number (millis) | **ABSENT until completion.** `completeGroupChallenge` (index.ts:1136/1281) | — | — |
| `prizePool` | number (cents) | **ABSENT until completion.** (index.ts:1138=0 nobody-failed / 1282) | — | distributable pot |
| `appFee` | number (cents) | **ABSENT until completion.** (index.ts:1139=0 / 1283) | — | 10% of failed pot → revenue |
| `prizePerWinner` | number (cents) | **ABSENT until completion.** (index.ts:1140/1284) | — | per-winner bonus |
| `nobodyFailed` | boolean | **ABSENT until completion.** (index.ts:1141/1285) | — | — |

### Embedded `participants[]` element — field lifecycle

| Field | Type | Created/Set BY | Changed/removed BY | Notes |
|-------|------|----------------|--------------------|-------|
| `userId`,`displayName`,`paymentIntentId`,`amountCents`,`joinedAt` | mixed | creator: createGroupChallenge (index.ts:499); joiner: confirmGroupJoin (index.ts:616) | — | identity/buy-in |
| `status` | string `active\|failed\|success\|completed\|refunded\|lost` | `"active"` at create/join | `failParticipant`→`failed` (802); settlement→`success`/`completed`/`lost`/`refunded` (1133/1178/1211); cancel/expire/delete→`refunded` (771/888/1019) | — |
| `opensToday`,`timeUsedMinutes` | number | `0` at create/join (index.ts:504/623) | client stats array-replace (…Service.kt:174/200/230) | leaderboard |
| `deviceId` | string | **only on join** (confirmGroupJoin index.ts:624; `""` if omitted) | — | NOT set for the creator element; read by anti-cheat |
| `failedAt` | number (millis) | **ABSENT until self-fail.** failParticipant (index.ts:802) | — | — |
| `payoutStatus` | string `completed\|pending_payout\|lost\|""` | **ABSENT until completion.** completeGroupChallenge (1133/1178/1211/1240/1270) | — | — |
| `finalPayout` | number (cents) | **ABSENT until completion.** completeGroupChallenge (1133/1178/1211…) | — | stake refund + bonus; 0 for losers |

### Event timeline — `groupChallenges/{groupId}`

```
createGroupChallenge (index.ts:492)
  + status="waiting", code, all config fields, authorizationExpiresAt=now+5d
  + participants=[creator{status:"active",opensToday:0,timeUsedMinutes:0,joinedAt}], participantUserIds=[creator]
  + createdAt=serverTimestamp()
joinGroupChallenge (index.ts:526)  → no doc write (creates PI only)
confirmGroupJoin (index.ts:571)
  ~ participants += {joiner, deviceId, status:"active", ...} (arrayUnion); participantUserIds += joiner
startGroupChallenge (index.ts:637)
  if <2 participants: ~ status="cancelled" (PIs cancelled)
  else: captures all PIs; ~ status="active"; + startDate, + endDate
completeGroupChallenge (index.ts:1040)   [expired OR all-failed]
  + completedAt, prizePool, appFee, prizePerWinner, nobodyFailed
  ~ each participant: + payoutStatus, + finalPayout, ~ status
  (counters revenue += appFee on someone-failed path)
cancel / delete / leave / expire  → ~ status="cancelled"; participant status→"refunded"; PIs cancelled
(rules: allow delete:false — group docs are NEVER hard-deleted)
```

---

## `users/{uid}/permissionStatus/current` — lifecycle

| Field | Type | Created/Set BY (event) | Changed/removed BY | Notes |
|-------|------|------------------------|--------------------|-------|
| `permissionLostAt` | number (millis)\|null | **PERM-mark** on first loss (PermissionCheckWorker.kt:122) | cleared to `null` on restore (kt:90) | server query gate (index.ts:1452) |
| `permissionType` | string `overlay\|accessibility\|both` | PERM-mark (kt:123) | re-set on next loss | — |
| `deviceId` | string | PERM-mark (kt:124) | — | — |
| `permissionRestoredAt` | number (millis) | **ABSENT until restore.** PERM-mark restore (kt:91) | — | — |
| `usageViolationDetectedAt` | number (millis)\|null | **USAGE-mark** (kt:219) | cleared by debug reset (ProfileViewModel.kt:1037) | server query gate (index.ts:1543) |
| `violatingPackage` | string | USAGE-mark (kt:221) | — | — |
| `usageMinutes` | number | USAGE-mark (kt:221) | — | — |
| `lastSeenAt` | number (millis) | **HEARTBEAT** — `writeHeartbeatIfHardActive` (kt:226), ~15-min merge while ≥1 active HARD challenge | re-written each beat | proves liveness; staleness > `config wentDarkGraceMs` ⇒ reconciliation went-dark LOSS (index.ts:1846). Owner-writable |
| `capturedAt` | number (millis) | **CF-only; ABSENT until permission capture.** runPermissionViolationCheck (index.ts:1533) | — | client write blocked by rules |
| `captureReason` | string `permission_loss_24h\|usage_violation_1h` | **CF-only; ABSENT until capture.** (index.ts:1533/1591) | — | — |
| `usageCapturedAt` | number (millis) | **CF-only; ABSENT until usage capture.** (index.ts:1591) | — | — |

> Timeline: loss → `permissionLostAt+permissionType+deviceId` appear → (a) restore → `permissionLostAt=null`,
> `+permissionRestoredAt`; or (b) >24h with no restore → CF adds `capturedAt`,`captureReason` (and
> captures the stake). Usage path is parallel: `usageViolationDetectedAt+violatingPackage+usageMinutes`
> → CF adds `usageCapturedAt`,`captureReason`. Doc is never deleted (rules `delete:false`).
> **Heartbeat (parallel, independent of any loss):** while the user has ≥1 active HARD challenge,
> `PermissionCheckWorker` merges `lastSeenAt=now` every cycle (~15 min). The reconciliation net reads
> the latest `lastSeenAt` (or `startDate` if never beat) and forfeits the stake as a went-dark LOSS
> (`failReason:"device_dark"`) once it is staler than `config/app.wentDarkGraceMs`. FAIL-SAFE: a
> missing grace ⇒ never forfeit (see `config/app` lifecycle + `docs/13`).

---

## `counters/global` — lifecycle

Doc first materialized by the first `bumpCounters` merge (index.ts:119) or a `backfillCounters`
overwrite (index.ts:2124). Every numeric field is **created on first increment** and only ever
mutated by `FieldValue.increment` (never read-modify-write), except backfill which OVERWRITES.

| Field | Created/Set BY | Changed by (Δ) | Notes |
|-------|----------------|-----------------|-------|
| `totalUsers` | onUserCreated +1 (index.ts:1988) | backfill overwrite (2115) | — |
| `totalActiveChallenges` | createPaymentIntent +1 (213) | −1 on every settle (271/451/1491/1580/1822/1895); backfill | solo hard only |
| `totalCompletedChallenges` | WIN-server +1 (451) | reconciliation WIN +1 (1895); backfill | — |
| `totalFailedChallenges` | capturePayment +1 (271) | LOSS-server +1 (1491/1580); reconciliation LOSS +1 (1822); backfill | — |
| `totalRevenueCents` | various +amount (273/452/1494/1583/1822/1290) | every settle / group-fee path; backfill | cents |
| `updatedAt` | every bumpCounters (115) / backfill (2120) | every write | millis number |

---

## `users/{uid}/pendingPayouts/{payoutId}` — lifecycle

| Field | Type | Created/Set BY | Changed/removed BY | Notes |
|-------|------|----------------|--------------------|-------|
| `amount`,`stakeRefundCents`,`currency`,`groupId`,`displayName`,`createdAt`,`status` | mixed | **completeGroupChallenge** when a winner has no payouts-enabled account (index.ts:1226) — `status="pending_account_setup"`, `createdAt=serverTimestamp()` | `status` → `"requested"` by ProfileViewModel manual request (ProfileViewModel.kt:282); **whole doc DELETED** by `claimPendingPayouts` after a successful transfer (index.ts:1341) | doc-id is auto-id (CF) or `{groupId}` (client request path) |

> Timeline: created on completion-with-no-account → optionally `status:"requested"` (manual) →
> DELETED on successful Stripe transfer (`claimPendingPayouts`).

---

## `users/{uid}/paymentCaptures/{captureId}` — lifecycle

| Field | Type | Created/Set BY | Changed/removed BY | Notes |
|-------|------|----------------|--------------------|-------|
| `paymentIntentId`,`amountCaptured`,`capturedAt` | string/number(cents)/Timestamp | **capturePayment** on a fresh `requires_capture` capture only (index.ts:261) | never updated/deleted | append-only audit; NOT written on the already-`succeeded` branch |

---

## `payoutRequests/{requestId}` — lifecycle

| Field | Type | Created/Set BY | Changed/removed BY | Notes |
|-------|------|----------------|--------------------|-------|
| `userId`,`displayName`,`payoutName`,`iban`,`amountCents`,`groupId`,`createdAt` | mixed | **client create** (ProfileViewModel.kt:287 / GroupChallengeDetailViewModel.kt:407) | — | `createdAt`=`serverTimestamp()` (detail) or `Timestamp.now()` (profile) |
| `status` | string | create → `"pending"` (rules force this) | **admin** marks paid/rejected (rules line 204) | indexed `(status, createdAt)` |
| `paidAt` | Timestamp | **ABSENT until admin marks paid** (rules forbid on create) | admin | indexed `(status, paidAt)` |

> Timeline: `create` (`status:"pending"`, no `paidAt`) → admin `update` (`status:paid/rejected`, `+paidAt`).
> Never deleted (rules `delete:false`).

---

## `config/app` — lifecycle

All fields are **admin-authored** (no app write path). Each appears only once the admin sets it; the
client `AppConfigRepository.refresh` reads each defensively and falls back to cached/default for any
field the admin hasn't set yet (FAIL-OPEN for UX). The two money-safety fields are the exception:

| Field | Created/Set BY | Default if absent | Notes |
|-------|----------------|-------------------|-------|
| `minVersionCode`,`latestVersionCode`,`maintenanceMode`,`maintenanceMessage`,`hardModeEnabled`,`groupChallengeEnabled`,`updateUrl`,`hardModeMinStake`,`hardModeMaxStake`,`groupMinBuyIn`,`groupMaxBuyIn` | admin | cached/safe default (AppConfigRepository.kt:76) | fail-open: missing ⇒ non-blocking |
| `reconciliationEnabled` | admin | **false** (index.ts:1714) | fail-SAFE: missing/unreadable ⇒ reconciliation disabled |
| `reconciliationDryRun` | admin | **true** (index.ts:1715) | only an explicit `false` arms real Stripe ops |
| `wentDarkGraceMs` | admin | **MAX_SAFE_INTEGER ⇒ never forfeit** (index.ts:1716) | fail-SAFE: missing/non-positive/unreadable ⇒ went-dark forfeit disabled; recommended 72h=`259200000` |

> No per-field creation event beyond "admin set it"; there is no document lifecycle (single
> long-lived doc, fields toggled in place).

---

## `rateLimits/{uid}` — lifecycle

| Field | Type | Created/Set BY | Changed/removed BY | Notes |
|-------|------|----------------|--------------------|-------|
| `{endpointKey}` | map `{windowStart:number(millis), count:number}` | **enforceRateLimit** on the first call to that endpoint within a window (index.ts:151) | `count` increments within the window; `windowStart`+`count` reset when the window expires (index.ts:142) | one map field per endpoint name; over-limit calls do NOT write (count pinned at cap). No delete path. |

---

## Other docs — lifecycle (brief)

| Doc | Field creation events | Mutation / removal |
|-----|----------------------|--------------------|
| `users/{uid}` | `email`,`createdAt`(Timestamp),`displayName` at **registration** (createUserDocument); `consent*`+`consentTimestamp` only if granted at registration; `fcmToken` on token refresh; `username`+`displayName` on **username claim** (txn); `stripeCustomerId` on first payment; `stripeConnectedAccountId`+`payoutIban`+`payoutName`+`payoutSetupAt` on **Connect onboarding**; `disabled`+`disabledReason`+`disabledAt` on **ban** | `disabled*` toggled on unban (`null`); `pendingPayouts_completed.{groupId}` map added on a successful prize transfer (index.ts:1261); rules `delete:false` |
| `usernames/{username}` | `uid`,`createdAt` at **claim** (txn, FirestoreService.kt:150) | immutable — no update/delete (permanent) |
| `supportTickets/{ticketId}` | all fields at **create** (SupportViewModel.kt:86); `status="open"`, `resolvedAt=null`, `createdAt`=millis-number | admin sets `status`/`resolvedAt`/`adminReply`/`adminReplyAt`; `delete:false` |
| `supportTickets/{id}/adminNotes/{noteId}` | admin-authored on note add | admin-only; owner can never read |
| `antiCheatReviews/{userId}` | `decision`,`reviewedAt`,`note` when **admin reviews** a flagged user | admin re-writes on re-review |
| `broadcasts/{broadcastId}` | `active`,`title`,`message`,`createdAt` on **admin create** | admin flips `active=false` to deactivate; `delete:false` |
| `users/{uid}/deviceInfo/security` | `isRooted`+`detectedAt` written **only when root detected at Hard Mode creation** (ChallengeCreationViewModel.kt:471) | `adminVerified` admin-only; never deleted |
| `groupChallenges/{id}/taunts/{tauntId}` | all fields at **send** (`shown=false`, `createdAt`=millis ±5min) | `shown→true` via `markTauntShown` (rules currently block this update); no delete |

---

## "When does it first exist?" quick map (absent-until-event)

| Field(s) | Absent until… |
|----------|---------------|
| challenge `payoutStatus`,`payoutAmount`,`appFeeAmount`,`payoutDate` | settlement (WIN-server / reconciliation; LOSS-server sets only `payoutStatus=captured`) |
| challenge `failReason`,`failedAt` | ANY loss — device-detected (`limit_exceeded`/`abandon`/`permission_violation`, via `markChallengeFailed`, doc retained) OR server (`permission_violation`/`usage_violation`/`reconciliation`/`device_dark`) |
| challenge `reconciliationLowEvidence` | a reconciliation WIN with zero nested dailyLogs |
| challenge `redemption*` / `redemptionChallengeId` | the original fails server-side and REDEEM-meta runs |
| group `startDate`,`endDate` | `startGroupChallenge` |
| group `completedAt`,`prizePool`,`appFee`,`prizePerWinner`,`nobodyFailed` | `completeGroupChallenge` |
| participant `deviceId` | join (not present on the creator element) |
| participant `failedAt`,`payoutStatus`,`finalPayout` | self-fail / completion |
| permissionStatus `capturedAt`,`captureReason`,`usageCapturedAt` | a server capture (CF-only) |
| permissionStatus `permissionRestoredAt` | a restore |
| payoutRequests `paidAt` | admin marks paid |
| user `stripeCustomerId` | first payment; `stripeConnectedAccountId`+payout fields → Connect onboarding; `disabled*` → ban |

> **Note — there is no `redemptions` collection.** "Redemption" is not a separate collection: it is
> (a) a set of `redemption*` fields stamped on the failed **original** challenge doc, and (b) a new
> challenge doc with `isRedemption=true` (+ `originalChallengeId`/`originalPaymentIntentId`,
> `amountCents=0`) under the same `users/{uid}/challenges` collection. Its lifecycle is the standard
> challenge lifecycle above; on a redemption WIN, `cancelOrRefundPayment` refunds 60% of the
> *original* stake via `originalPaymentIntentId`.

---
---

# PART 3 — MODE & limitType FIELD MATRIX

> Part 1 = "what fields exist"; Part 2 = "when each field appears/mutates"; **Part 3 = "which fields
> apply for which `mode` / `limitType`, and which field is the money SOURCE OF TRUTH for each
> limitType."** Derived from the actual evaluation/settlement code, not just the schema:
> `DailyEvaluationWorker` (per-`limitType` branches), the win-gate in `cancelOrRefundPayment`
> (index.ts:343), and reconciliation `lossProven` (index.ts:1779).
>
> **One fact dominates both matrices:** the SERVER money gates are **`limitType`-agnostic** — both
> the win-gate (index.ts:343) and reconciliation `lossProven` (index.ts:1779) read *only* the boolean
> `dailyLogs.limitExceeded` (`.some(d => d.data()["limitExceeded"] === true)`). They NEVER read
> `totalMinutes`/`openCount`/`budgetUsedMinutes`. So **the device is the sole authority that maps the
> right usage field → `limitExceeded` per `limitType`**; the server just trusts that boolean.

---

## A) BY mode — `soft` vs `hard`

### A1. Challenge-doc fields by mode

| Field | soft | hard | Notes |
|-------|:----:|:----:|-------|
| `id`,`appPackageName(s)`,`appDisplayName`,`mode`,`limitType`,`limitValue*`,`dailyBudgetMinutes`,`startDate`,`endDate`,`createdAt`,`status`,`blocking*`,`schedule*`,`activeDays`,`partialBlock*`,`customMotivation`,`blockAdultContent`,`syncedAt` | ✅ | ✅ | common config — written by `Challenge.toMap()` for both |
| `amountCents` | ✅* | ✅ | *soft writes `null` (no stake); meaningful only for hard |
| `stripePaymentIntentId` | ✅* | ✅ | *`null` for soft; the gate `mode==HARD && stripePaymentIntentId!=null` (worker:533) excludes soft from all money paths |
| `deviceId`,`isRooted` | ✅* | ✅ | *toMap always writes them, but they are only POPULATED at Hard Mode creation (anti-cheat); soft → `null` |
| `isRedemption`,`originalChallengeId`,`originalPaymentIntentId`,`refundAmountCents`,`redemption*`,`redemptionChallengeId` | ❌ | ✅ | redemption is a Hard-Mode-only mechanism |
| `payoutStatus`,`payoutAmount`,`appFeeAmount`,`payoutDate` | ❌ | ✅ | **settlement set — written only by hard win/loss CFs**; never appears on a soft doc |
| `failReason`,`failedAt` | ✅ | ✅ | set on ANY device-detected loss via `markChallengeFailed` — soft fail writes `failReason:"limit_exceeded"`; hard device-losses write `limit_exceeded`/`abandon`/`permission_violation`. Server-loss values (`usage_violation`/`reconciliation`/`device_dark`) are hard only. Also persisted to the Room `failReason` column |
| `reconciliationLowEvidence` | ❌ | ✅ | reconciliation WIN flag — hard only |

### A2. Whole mechanisms by mode

| Mechanism | soft | hard | Code |
|-----------|:----:|:----:|------|
| `createPaymentIntent` / `capturePayment` / `cancelOrRefundPayment` | ❌ | ✅ | worker gate `mode==HARD && PI!=null` (worker:322/533); `createPaymentIntent` only for paid challenges |
| `paymentCaptures/*`, `pendingPayouts/*` (solo) | ❌ | ✅ | written only on a hard capture/payout |
| reconciliation (`scheduledChallengeReconciliation`) | ❌ | ✅ | candidate query filters `mode=="hard" && status=="active"` (endDate filter dropped; due-ness per-doc, index.ts:1743) |
| `permissionStatus` CAPTURE (`capturedAt`/`captureReason`/`usageCapturedAt` + stake capture) | ❌ | ✅ | `checkPermissionViolations` skips non-hard (`cd["mode"] !== "hard"` index.ts:1479/1568). *(`permissionLostAt`/`usageViolationDetectedAt`/`permissionType` markers are still WRITTEN device-side regardless of mode, but only acted on for hard. The `deviceId` field, however, is written ONLY when `BuildConfig.MONEY_FEATURES_ENABLED` — a soft-only release omits it; see `PermissionCheckWorker.buildPermissionLostUpdate`. It has no permissionStatus consumer anyway — anti-cheat reads `deviceId` from the challenge/participant doc.)* |
| counters `totalActive/Completed/FailedChallenges`, revenue | ❌ | ✅ | scope = solo hard only (`bumpCounters` only on hard money events; `backfillCounters` `isSoloHard` index.ts:2086) |
| anti-cheat signals (rooted / perfect-win / instant-win) | ❌ | ✅ | `detectSuspiciousUsers` collects `isSoloHard` completed wins (index.ts:2255) |
| `DailyEvaluationWorker` daily log write + local complete/fail | ✅ | ✅ | both write `dailyLogs`; both flip Room/Firestore `status` |

### A3. What a SOFT challenge looks like end-to-end

- **Create:** `Challenge.toMap()` writes the doc with `amountCents=null`, `stripePaymentIntentId=null`,
  `deviceId/isRooted=null`. No `createPaymentIntent` call.
- **Daily:** `DailyEvaluationWorker` writes a `dailyLogs` doc each day with `limitExceeded`
  true/false per `limitType` — **but skips the entire `mode==HARD` money block** (worker:533/322).
  No capture, no refund, no `payoutStatus`.
- **Complete:** end date with no exceed → `updateChallengeStatus(COMPLETED)` → Firestore
  `status="completed"` (+`syncedAt`); on SOFT it also emits the streak result UI
  (`TrackedAppEventBus`, worker:283/453).
- **Fail:** a limit-exceeded day → `updateChallengeStatus(FAILED, "limit_exceeded")` (OverlayManager
  soft-fail / DailyEvaluationWorker) → **`markChallengeFailed` CF** writes `status:"failed"` +
  `failReason:"limit_exceeded"` + `failedAt` **in place** (same retain-the-doc path as a hard
  device-loss, but with NO money step). The doc + `dailyLogs` are KEPT; `payoutStatus` is never
  written (soft has no stake).
- **Never** touches: counters, reconciliation, permissionStatus capture, redemption,
  paymentCaptures/pendingPayouts.

---

## B) BY limitType — money source-of-truth (TIME · SESSIONS · TIME_BUDGET)

> `DailyEvaluationWorker` has **three distinct code paths**: a dedicated `TIME_BUDGET` branch
> (worker:296, `continue`s at :461) and a shared `TIME/SESSIONS` branch (worker:464+) which itself
> forks on `limitType == SESSIONS` (worker:502). The `computeLimitExceeded` helper (worker:741) is
> consequently only ever invoked for **TIME** in practice (see flags). `dailyBudgetMinutes` /
> `limitValueSessions` are mutually exclusive with the others by type.

| | **TIME** | **SESSIONS** | **TIME_BUDGET** |
|---|---|---|---|
| Challenge limit field | `limitValueMinutes` | `limitValueSessions` | `dailyBudgetMinutes` |
| Usage source read by evaluator | UsageStats minutes − overlay (`adjustedMinutes`, worker:484) | **Room conscious opens** `dailyLogRepository.getConsciousOpens` (worker:503) | **`existingLog.budgetUsedMs`** (Room *Ms* column, worker:304) |
| `dailyLogs` field that IS the source of truth | `totalMinutes` (=`adjustedMinutes`, worker:612) | `consciousOpens` (worker:616) | `budgetUsedMinutes`/`budgetRemainingMinutes` for display, **`budgetUsedMs`/`budgetRemainingMs` are authoritative** (worker:392/393); flat `users/{uid}/dailyLogs/{cid}_{date}.budgetUsedMs` is the live writer |
| Exact `limitExceeded` comparison (file:line) | `adjustedMinutes >= limitValueMinutes` (computeLimitExceeded, worker:748) | `consciousOpens > maxSessions` — **strict `>`**, null limit ⇒ NOT exceeded (worker:507) | `budgetUsedMs > totalBudgetMs` where `totalBudgetMs = dailyBudgetMinutes*60000` — **strict `>`**, null budget ⇒ NOT exceeded (worker:318) |
| Operator rationale | `>=` : reaching the cap is a loss | `>` : overlay caps conscious opens at N, so using exactly N is a WIN | `>` : overlay caps usage at the budget, so consuming the full budget is a WIN |
| Server win-gate / lossProven input | reads only `limitExceeded` (index.ts:343 / 1779) | same | same |
| Wrong-source check | ✅ verified consistent | ✅ verified consistent (money path uses conscious opens) | ✅ verified consistent **now** — see FLAG B3 (was a real bug) |

### B-flags — wrong-source landmines

- **FLAG B1 — `computeLimitExceeded` SESSIONS branch uses RAW UsageStats opens.** Inside the helper,
  `LimitType.SESSIONS -> opens >= maxSessions` (worker:756) compares **raw `todayUsage.opens`** with
  `>=`. This contradicts the money rule (conscious opens, strict `>`). **Not reached today** because
  the inline `if (limitType == SESSIONS)` (worker:502) handles SESSIONS before the `else` that calls
  the helper — so the helper effectively only runs for TIME. ⚠ It is a latent wrong-source +
  wrong-operator bug if a future refactor routes SESSIONS through `computeLimitExceeded`. Violates
  the CLAUDE.md rule "NEVER use UsageStatsManager to count opens."
- **FLAG B2 — `computeLimitExceeded` TIME_BUDGET branch is dead + would use the wrong field.**
  `LimitType.TIME_BUDGET -> adjustedMinutes >= limitValueMinutes` (worker:749) would compare
  *UsageStats minutes* against `limitValueMinutes` (NOT `dailyBudgetMinutes`/`budgetUsedMs`).
  Unreachable because the dedicated TIME_BUDGET branch `continue`s at worker:461. ⚠ Dead code that
  encodes the wrong source; safe only as long as the dedicated branch precedes it.
- **FLAG B3 — TIME_BUDGET source field (historical bug, now FIXED).** The dedicated branch reads
  `existingLog.budgetUsedMs` (worker:304). The code comment (worker:297-302) documents that it
  previously read `budgetUsedMinutes`, which the live tracker (`UsageTrackingService.checkBudgetSession`)
  never writes — so it was always `0`, the overrun was never detected, and the stake was never
  captured. Reading the `*Ms` column fixed it. ✅ Currently consistent; the minute columns are still
  written (worker:387/388) only for display + the same-day-retry skip guard.
- **FLAG B4 — anti-cheat "perfect win" reads `consciousOpens`, which is structurally 0 for
  TIME/TIME_BUDGET.** `detectSuspiciousUsers` (index.ts:2309-2311) flags a win where every dailyLog
  has `consciousOpens==0 && totalMinutes==0`. Only **SESSIONS** logs populate `consciousOpens`
  (TIME/TIME_BUDGET write `0`/default), so the "0 opens" half is trivially true for non-SESSIONS
  challenges; the heuristic effectively rests on `totalMinutes==0` for those. Not a money bug
  (flagging-only, human-reviewed), but a `limitType`-blind source assumption worth noting.

### B-extra — per-type `dailyLogs` field population (what the worker writes)

| dailyLog field | TIME | SESSIONS | TIME_BUDGET |
|---|---|---|---|
| `totalMinutes` | `adjustedMinutes` | `adjustedMinutes` | `budgetUsedMin` (worker:384) |
| `openCount` | `todayUsage.opens` (raw) | `todayUsage.opens` (raw) | `0` (worker:385) |
| `consciousOpens` | `0` (worker:513) | from Room (worker:505) | `0`/default |
| `budgetUsedMinutes`/`budgetRemainingMinutes` | not set (0) | not set (0) | set (worker:387/388) |
| `budgetUsedMs`/`budgetRemainingMs` | not set (0) | not set (0) | set, **authoritative** (worker:392/393) |
| `limitExceeded` | `adjustedMinutes >= limitValueMinutes` | `consciousOpens > limitValueSessions` | `budgetUsedMs > dailyBudgetMinutes*60000` |

> Note `TIME_WINDOW` exists in the `LimitType` enum and `computeLimitExceeded` returns `false` for it
> (worker:759), i.e. it never auto-fails on usage; it is a schedule-based block, not a money-metered
> limit, and is out of scope for the win/loss gates.

