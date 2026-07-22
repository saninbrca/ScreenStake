# AGENTS.md — Detox App System Instructions

## 1. Tech Stack & App Info
* **Platform:** Android (Min SDK 26).
* **Languages:** Kotlin, Node.js/TypeScript (Firebase Functions).
* **UI:** Jetpack Compose (Material 3).
* **Architecture:** MVVM, Clean Architecture, Hilt (DI).
* **Storage:** Room (Local), Firestore (Cloud).
* **Target Device:** MUST be compatible with Huawei (assume NO Google Play Services).

## 2. Key File Locations
* _Paths are hints — confirm against the actual tree before relying on them._
* **Navigation:** `presentation/navigation/DetoxNavGraph.kt`
* **Core Services:** `service/UsageTrackingService.kt`, `service/AppDetectionAccessibilityService.kt`, `service/OverlayManager.kt`
* **Money workers (capture-gate loss/win paths):** `service/DailyEvaluationWorker.kt`, `service/PermissionCheckWorker.kt` (+ `service/ChallengeSettlementGuard.kt` — server-settled check that MUST precede every client capture/refund)
* **Challenge repo:** `data/repository/ChallengeRepositoryImpl.kt` (`updateChallengeStatus` → local Room mirror + `markChallengeFailed` CF)
* **Database:** `data/local/db/DetoxDatabase.kt` (current DB version: see `docs/01`)
* **DB Encryption:** `data/local/db/DatabaseKeyManager.kt` (SQLCipher passphrase, Keystore-wrapped)
* **Remote Config:** `data/repository/AppConfigRepository.kt` (feature flags, maintenance, force-update)
* **Cloud Functions:** `functions/src/index.ts`
* **Stripe Logic:** `data/repository/PaymentRepositoryImpl.kt`

## 3. Critical Coding Rules (NEVER VIOLATE)
> Always-loaded safety-net checklist — one line each. **Full rationale + canonical list: `docs/invariants.md`** (numbered #1–26; the numbers below match it). Condense here, never delete.

**Money authority & Stripe capture**
1. **Stripe before Firestore:** the Stripe op (capture/refund) gates the Firestore status write on EVERY path — never COMPLETED before the refund succeeds, nor FAILED before the capture succeeds. Room is a local mirror only (written just before the fire-and-forget Firestore sync), NOT a money-authority step. (`docs/03`, `docs/09`)
2. **Money authority is SERVER-SIDE:** never trust client win/loss, clock, refund amount, or PI id — re-derive from the stored doc. (`docs/10`)
3. **Hard Mode create = ONE rules-allowed CREATE** under the unified `challengeId`; the CF never writes the challenge doc; mirror is awaited, not fire-and-forget. (`docs/03`)
4. **capturePayment idempotency:** `success` ALWAYS means captured; counters bump only on a fresh `requires_capture`; non-capturable → 409; keep the IDOR guard. (`docs/03`)
5. **Abandon captures SOLO Hard Mode only;** FAILED only after a confirmed capture. (`docs/03`)
6. **Capture-gate (all loss paths):** FAILED only inside `capturePayment.onSuccess`; on capture failure stays ACTIVE. The PI-less legacy branch is the only no-capture FAILED. (`docs/03`)
7. **Loss = retain the doc:** `updateChallengeStatus(FAILED, reason)` → `markChallengeFailed` CF writes `status`/`failReason`/`failedAt` in place (Admin SDK; client can't write `status`); `dailyLogs` preserved; never writes `payoutStatus` or touches Stripe. Room `failReason` is UX-only. (`docs/firestore-schema.md`, `docs/03`)
8. **Went-dark = forfeit:** `lastSeenAt` heartbeat; reconciliation forfeits as `device_dark`; FAIL-SAFE — missing/invalid grace ⇒ `MAX_SAFE_INTEGER` ⇒ never forfeit; triple-gated. (`docs/03`, `docs/13`)
9. **Payout fee math uses `Math.floor`** — never round up. (`docs/09`)

**Data integrity & anti-cheat**
10. **dailyLogs tamper-evidence:** `limitExceeded` never true→false; deletes CF-only (`allow delete: if false`). (`docs/10`)
11. **Anti-cheat is FLAGGING ONLY:** `detectSuspiciousUsers` never auto-bans or writes user data; a human reviews. (`docs/10`)
12. **Anti-cheat capture:** `deviceId` (ANDROID_ID) + `isRooted` written on EVERY Hard Mode create (deviceId also on every group join) — never stop. (`docs/10`)
13. **DB encryption:** SQLCipher; passphrase from `DatabaseKeyManager` (Keystore-wrapped); NEVER hardcode; intentionally NOT `setUserAuthenticationRequired(true)` (workers open the DB without unlock). (`docs/10`)
14. **`pending_hard_challenges` is local-only** — never synced to Firestore. (`docs/01`)

**Conscious-opens core & overlays**
15. **Conscious Opens:** never use `UsageStatsManager` to count opens; increment only on "Ja, öffnen". (`docs/02`)
16. **Overlays:** always `FLAG_SECURE` + `TYPE_APPLICATION_OVERLAY`; show via `Handler(mainLooper).post{}`. (`docs/05`)
17. **DailyLog date key = `DateUtils.todayKey()`** — never inline `86400000`. (`docs/02`)
18. **`endDate = endOfDayMillis`** (23:59:59.999), not `now + N×86400000`. (`docs/04`)

**Huawei compatibility**
19. **Cloud Functions use `onRequest`** (never `onCall`). (`docs/05`)
20. **No FCM push** — WorkManager / AlarmManager only. (`docs/05`)
21. **Firestore array updates:** `FieldValue.arrayRemove` + `arrayUnion` — never dot notation. (`docs/04`)
22. **`DailyEvaluationWorker` `NetworkType.CONNECTED` constraint** — never remove (Hard Mode refunds depend on it). (`docs/05`)

**Lifecycle & config**
23. **Logout:** clear ALL Room tables BEFORE `Firebase.signOut()`. (`docs/07`)
24. **AppConfig is FAIL-OPEN:** a missing config or network error keeps cached/safe defaults and NEVER locks the user out. (`docs/13`)
25. **Groups disabled at launch** via `config/app.groupChallengeEnabled=false`; the hardcoded `AppConfig` fallback stays `true` (fail-open). (`docs/13`, `docs/04`)
26. **Counters are best-effort:** `FieldValue.increment` only (never read-then-write); a counter failure NEVER blocks or fails a payment/challenge op. (`docs/11`, `docs/09`)

## 4. Coding Conventions
* **UI:** Kotlin & Jetpack Compose ONLY. No XML.
* **State:** Use `StateFlow` + `collectAsStateWithLifecycle`.
* **Logging:** ALWAYS use `Timber`. Never use standard `Log`.
* **Strings:** All user-facing strings MUST be in `res/values/strings.xml`.
* **Logic:** Use `Result<T>` for Repository/UseCase outputs.

## 5. Workflow (Documentation Access)
* **IMPORTANT:** This file contains only technical rules. 
* For ANY information regarding Business Logic, Feature Details, or App Rules, you **MUST** look into the `docs/` folder.
* Read **ONLY** the specific file needed for the current task to save tokens:
  - **Invariants (ALWAYS load FIRST):** `docs/invariants.md` (the never-reverse rules — load before anything else)
  - **Changelog (load second):** `docs/00_changelog.md`
  - Architecture/Stack: `docs/01_architecture_and_stack.md`
  - Soft Mode/Logic: `docs/02_core_mechanics_and_soft_mode.md`
  - Hard Mode/Stripe: `docs/03_hard_mode_and_stripe.md`
  - Group Challenges: `docs/04_group_challenges.md`
  - Huawei/Permissions: `docs/05_huawei_and_permissions.md`
  - Testing Guide: `docs/06_testing_guide.md`
  - Onboarding & Auth: `docs/07_onboarding_and_auth.md`
  - UI Design System: `docs/08_ui_design_system.md`
  - Payout & Fees: `docs/09_payout_and_fees.md`
  - Security & Anti-Cheat: `docs/10_security_and_anticheat.md` (SQLCipher, server-side money authority, anti-cheat, ban system)
  - Admin Dashboard: `docs/11_admin_dashboard.md` (7 tabs, counters, broadcasts, ban actions)
  - Support System: `docs/12_support_system.md` (in-app support form, FAQ, supportTickets)
  - Remote Config & Flags: `docs/13_remote_config_and_flags.md` (config/app, feature flags, maintenance, force-update)