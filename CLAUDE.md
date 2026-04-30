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
* **Cloud Functions:** `functions/src/index.ts`
* **Stripe Logic:** `data/remote/repository/PaymentRepositoryImpl.kt`

## 3. Critical Coding Rules (NEVER VIOLATE)
* **Conscious Opens:** NEVER use UsageStatsManager to count opens. Only increment when user taps "Ja, öffnen" in the overlay.
* **Cloud Functions:** ALL functions must use `onRequest` (NOT `onCall`) for Huawei compatibility.
* **Firestore Arrays:** NEVER use dot notation for array updates. ALWAYS use `FieldValue.arrayRemove` + `FieldValue.arrayUnion`.
* **Overlays:** ALWAYS use `FLAG_SECURE` and `TYPE_APPLICATION_OVERLAY`. Use `Handler(mainLooper).post{}` for showing overlays.
* **Logout:** Clear ALL Room tables BEFORE calling `Firebase.signOut()`.

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
  - Architecture/Stack: `docs/01_architecture_and_stack.md`
  - Soft Mode/Logic: `docs/02_core_mechanics_and_soft_mode.md`
  - Hard Mode/Stripe: `docs/03_hard_mode_and_stripe.md`
  - Group Challenges: `docs/04_group_challenges.md`
  - Huawei/Permissions: `docs/05_huawei_and_permissions.md`