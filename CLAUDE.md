# CLAUDE.md — Detox App System Instructions

## 1. Tech Stack & App Info
* **Platform:** Android (Min SDK 26).
* **Languages:** Kotlin, Node.js/TypeScript (Firebase Functions).
* **UI:** Jetpack Compose (Material 3).
* **Architecture:** MVVM, Clean Architecture, Hilt (DI).
* **Storage:** Room (Local), Firestore (Cloud).
* **Target Device:** MUST be compatible with Huawei (assume NO Google Play Services).

## 2. Key File Locations
* **Navigation:** `presentation/navigation/DetoxNavGraph.kt`
* **Core Services:** `service/UsageTrackingService.kt`, `service/AppDetectionAccessibilityService.kt`, `service/OverlayManager.kt`
* **Database:** `data/local/db/DetoxDatabase.kt`
* **DB Encryption:** `data/local/db/DatabaseKeyManager.kt` (SQLCipher passphrase, Keystore-wrapped)
* **Remote Config:** `data/repository/AppConfigRepository.kt` (feature flags, maintenance, force-update)
* **Cloud Functions:** `functions/src/index.ts`
* **Stripe Logic:** `data/remote/repository/PaymentRepositoryImpl.kt`

## 3. Critical Coding Rules (NEVER VIOLATE)
* **Conscious Opens:** NEVER use UsageStatsManager to count opens. Only increment when user taps "Ja, öffnen" in the overlay.
* **Cloud Functions:** ALL functions must use `onRequest` (NOT `onCall`) for Huawei compatibility.
* **Firestore Arrays:** NEVER use dot notation for array updates. ALWAYS use `FieldValue.arrayRemove` + `FieldValue.arrayUnion`.
* **Overlays:** ALWAYS use `FLAG_SECURE` and `TYPE_APPLICATION_OVERLAY`. Use `Handler(mainLooper).post{}` for showing overlays.
* **Logout:** Clear ALL Room tables BEFORE calling `Firebase.signOut()`.
* **Money Authority:** Refund/capture decisions are validated SERVER-SIDE. NEVER trust the client's win/loss, clock, refund amount, or PaymentIntent id — re-derive them from the stored challenge doc. (See `docs/10`.)
* **Hard Mode create:** A SINGLE rules-allowed Firestore CREATE under the unified `challengeId` (the same id passed to `createPaymentIntent`). The CF NEVER writes the challenge doc; never mint a second id. The Hard Mode mirror is AWAITED (bounded retry), never fire-and-forget. (See `docs/03`.)
* **capturePayment idempotency:** A `success` response ALWAYS means "captured". Counters bump ONLY on a fresh `requires_capture` capture — never on the `succeeded` branch. Non-capturable status → 409. Keep the IDOR guard.
* **Abandon:** Captures SOLO Hard Mode only (`mode==HARD && groupChallengeId==null && PI!=null`); status→FAILED ONLY after a confirmed capture (inside `capturePayment.onSuccess`). NEVER mark FAILED without the stake captured.
* **DB Encryption:** The Room DB is SQLCipher-encrypted; the passphrase comes from `DatabaseKeyManager` (Keystore-wrapped). NEVER hardcode it.
* **dailyLogs tamper-evidence:** `limitExceeded` may NEVER flip true→false; `dailyLogs` deletes are Cloud-Function-only (`allow delete: if false`).
* **Anti-Cheat:** FLAGGING ONLY — `detectSuspiciousUsers` never auto-bans or writes user data; a human always reviews.
* **AppConfig:** FAIL-OPEN — a missing config or network error keeps cached/safe defaults and NEVER locks the user out.

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
  - **Changelog (ALWAYS load first):** `docs/00_changelog.md`
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