# Pre-Launch Readiness Audit

> **Scope:** Read-only audit of release/build, secrets, Stripe, manifest, Firestore rules, Sentry,
> workers, versioning, flags, and DSGVO. **No code changes were made.**
> **Date:** 2026-06-18 · **Branch:** `fix/worker-limit-detection-sessions-budget`
> **⚠️ 2026-07-19 note:** the 2026-07-07 soft-mode-only launch DECISION (`MONEY_FEATURES_ENABLED`
> build floor, release=false — see `docs/13`) supersedes the urgency of blocker **B1**: the first
> release takes no real money, so the Stripe live-switch is only required before money features are
> re-enabled. The rest of this file is a dated snapshot — read it as of its date.
> **Severity legend:** 🔴 BLOCKER · 🟡 SHOULD-FIX · 🟢 NICE / verified-OK

---

## 🔴 BLOCKER

### B1 — Stripe is on TEST keys in the release build (cannot accept real money)
- App publishable key (release): `pk_test_...` at [app/build.gradle.kts:64](../app/build.gradle.kts#L64).
  Debug uses the same test key at [:68](../app/build.gradle.kts#L68).
- Functions secret key: read from `process.env.STRIPE_SECRET_KEY` at
  [functions/src/index.ts:38-40](../functions/src/index.ts#L38); the committed template shows
  `STRIPE_SECRET_KEY=sk_test_...` ([functions/.env.example:1](../functions/.env.example#L1)).
- **The test→live switch happens in exactly two places** (no automated separation exists today):
  1. **App:** `STRIPE_PUBLISHABLE_KEY` in `release { }` → [app/build.gradle.kts:64](../app/build.gradle.kts#L64) (`pk_test_` → `pk_live_`).
  2. **Backend:** `STRIPE_SECRET_KEY` in the (gitignored) `functions/.env` → consumed at [index.ts:38](../functions/src/index.ts#L38) (`sk_test_` → `sk_live_`), then redeploy functions.
- **Risk:** as shipped, real cards will not be charged/captured; live cards fail against a test key.
  Both keys MUST flip to live before taking real money.
- **Minimal recommendation:** flip both for the production build/deploy. Consider deriving the
  publishable key per-buildType from `keystore.properties`/an untracked gradle prop so live keys are
  never committed, and keep the debug type on `pk_test_`.

---

## 🟡 SHOULD-FIX

### S1 — `versionCode = 2` for a "1.0.0" first release
- [app/build.gradle.kts:41-42](../app/build.gradle.kts#L41) (`versionCode = 2`, `versionName = "1.0.0"`).
- Not wrong, but confirm this matches store-console expectations (a prior upload may already hold
  versionCode 1/2). Each store upload must strictly increase `versionCode`. **Verify before upload.**

### S2 — `allowBackup="true"` with sensitive local data
- [AndroidManifest.xml:31](../app/src/main/AndroidManifest.xml#L31) (`android:allowBackup="true"`),
  with `dataExtractionRules`/`fullBackupContent` referenced at [:32-33](../app/src/main/AndroidManifest.xml#L32).
- The Room DB is SQLCipher-encrypted and its key is Keystore-wrapped (not backed up), so the DB blob
  is useless off-device — risk is low. Still, ADB/cloud backup could exfiltrate app files. **Confirm
  `backup_rules.xml`/`data_extraction_rules.xml` exclude DataStore/SharedPrefs that hold anything
  sensitive, or set `allowBackup="false"`.** (Files referenced but not inspected in this audit.)

### S3 — Stale "TODO: replace DSN" comment vs a real Sentry DSN
- [app/build.gradle.kts:46-52](../app/build.gradle.kts#L46): comment says "Replace with real DSN" but a
  real-looking DSN (`o4511430516277248...sentry.io`) is already hardcoded and wired into the manifest
  placeholder. Harmless, but remove the misleading TODO and **confirm the DSN/org is the intended
  production project** before launch.

---

## 🟢 VERIFIED-OK (no action needed)

### Release build / R8 / shrinking — OK
- [app/build.gradle.kts:55-66](../app/build.gradle.kts#L55): release has `isMinifyEnabled = true`,
  `isShrinkResources = true`, R8 via `proguard-android-optimize.txt` + `proguard-rules.pro`, and the
  release signing config. No `isDebuggable = true` on release (default false); no `android:debuggable`
  in the manifest.
- Keep-rules ([app/proguard-rules.pro](../app/proguard-rules.pro)) cover Stripe (`com.stripe.**`),
  Firebase (`com.google.firebase.**`), Room entities/DAOs, Hilt, coroutines, Sentry, and the SQLCipher
  JNI classes (`net.zetetic.**`, `androidx.sqlite.**`) plus `SourceFile,LineNumberTable` for readable
  traces.
- **Firestore model obfuscation is NOT a risk:** the codebase does manual map parsing only — there is
  **zero** use of `toObject`/`toObjects`/`@PropertyName` (grep returned no matches), so R8 renaming
  app model fields cannot break Firestore deserialization. The keep-rules are therefore adequate on
  this front.
- *Recommended verification (not a finding):* do one install-and-smoke-test of an actual release
  (minified) build covering payment + login, since R8 + Compose can still surprise at runtime.

### Debug-only code is not reachable in release — OK
- Every debug trigger is gated. ProfileViewModel debug actions all early-return on `!BuildConfig.DEBUG`
  (≈22 guards, e.g. [ProfileViewModel.kt:607,784,793](../app/src/main/java/com/detox/app/presentation/screens/profile/ProfileViewModel.kt#L607)).
  Debug UI panels gated at [ProfileScreen.kt:338](../app/src/main/java/com/detox/app/presentation/screens/profile/ProfileScreen.kt#L338),
  [SettingsScreen.kt:634](../app/src/main/java/com/detox/app/presentation/screens/settings/SettingsScreen.kt#L634),
  and force-start at [FriendsHubScreen.kt:165](../app/src/main/java/com/detox/app/presentation/screens/friends/FriendsHubScreen.kt#L165).
  Debug duration shortcuts in [CreateChallengeUseCase.kt:104-110](../app/src/main/java/com/detox/app/domain/usecase/CreateChallengeUseCase.kt#L104)
  and [ChallengeCreationViewModel.kt:200](../app/src/main/java/com/detox/app/presentation/screens/challengecreation/ChallengeCreationViewModel.kt#L200)
  are `BuildConfig.DEBUG`-gated. Debug SharedPref flags are force-reset on cold start, and only in debug
  ([DetoxApplication.kt:75-83](../app/src/main/java/com/detox/app/DetoxApplication.kt#L75)).

### Secrets — none committed — OK
- `git ls-files` shows only `functions/.env.example` and `keystore.properties.template` tracked — no
  real `.env`, `serviceAccount.json`, `google-services.json`, `*.jks`/`*.keystore`, or
  `keystore.properties`.
- `.gitignore` excludes `functions/.env`, `google-services.json` (root + app), `*.keystore`, `*.jks`,
  `keystore.properties`, `local.properties` ([.gitignore:16-21](../.gitignore#L16)); `functions/.gitignore`
  excludes `.env`. A local `functions/.env` exists on disk but is untracked.
- The Stripe **publishable** key embedded in the APK is expected (publishable keys are public); the
  **secret** key lives only in `functions/.env`.

### AndroidManifest — OK
- Permissions are scoped to function (usage stats, overlay, FGS special-use, boot, internet, vibrate,
  notifications) — [AndroidManifest.xml:6-27](../app/src/main/AndroidManifest.xml#L6).
- Only the launcher `MainActivity` is `exported="true"` (required) — [:51](../app/src/main/AndroidManifest.xml#L51).
  Accessibility service, UsageTrackingService, BootReceiver, FCM service, and the startup provider are
  all `exported="false"` ([:63,75,84,104,114](../app/src/main/AndroidManifest.xml#L63)); the
  accessibility service is locked behind `BIND_ACCESSIBILITY_SERVICE`.
- Cleartext blocked: `networkSecurityConfig` sets `cleartextTrafficPermitted="false"`
  ([network_security_config.xml:7](../app/src/main/res/xml/network_security_config.xml#L7)). No leftover
  test/usesCleartext config.

### Firestore rules — comprehensive, deny-by-default, nothing world-writable — OK
- Every collection in use is matched: `users` (+ `challenges`/`dailyLogs`/`pendingPayouts`/
  `paymentCaptures`/`permissionStatus`/`deviceInfo`), collection-group `challenges`/`dailyLogs`,
  `groupChallenges` (+ `taunts`/`participants`), `payoutRequests`, `usernames`, `supportTickets`
  (+ `adminNotes`), `antiCheatReviews`, `counters`, `broadcasts`, `config/app`, `rateLimits`, `admin/**`
  ([firestore.rules](../firestore.rules)).
- Money-critical writes are CF-only: `pendingPayouts`/`paymentCaptures` `write: if false`
  ([:100,107](../firestore.rules#L100)); `dailyLogs` `delete: if false` (tamper-evidence,
  [:72](../firestore.rules#L72)); `counters` `write: if false` ([:271](../firestore.rules#L271));
  `rateLimits` and `admin/**` fully closed ([:295,301](../firestore.rules#L295)). No rule grants
  unauthenticated or wildcard write. Firestore deny-by-default covers anything unmatched.
- The hardened `payoutRequests` create rule (status:"pending" allowed, other status + paidAt blocked)
  is in place at [:198-202](../firestore.rules#L198).

### Sentry — DSN/release/env set, no PII — OK
- Init at [DetoxApplication.kt:53-70](../app/src/main/java/com/detox/app/DetoxApplication.kt#L53):
  `environment` = development/production by `BuildConfig.DEBUG`, `release` = appId@versionName,
  `tracesSampleRate` 0.1 in prod, `beforeSend` drops debug events (except the tagged test crash).
- **No PII:** user context is set to the Firebase UID only — explicitly "never email/name (DSGVO)"
  ([:114-116](../app/src/main/java/com/detox/app/DetoxApplication.kt#L114)); cleared on sign-out. No
  tokens are attached.

### Workers — getForegroundInfo present, no obvious main-thread block — OK
- `getForegroundInfo()` implemented in the long-running workers:
  [DailyEvaluationWorker.kt:72](../app/src/main/java/com/detox/app/service/DailyEvaluationWorker.kt#L72)
  and [PermissionCheckWorker.kt:59](../app/src/main/java/com/detox/app/service/PermissionCheckWorker.kt#L59)
  (CoroutineWorkers — `doWork` runs off the main thread). WorkManager state logging uses a listener +
  inline executor rather than a blocking `.get()` on main
  ([DetoxApplication.kt:169-186](../app/src/main/java/com/detox/app/DetoxApplication.kt#L169)).
  *(Overlay-thread behavior in `OverlayManager` was not deep-profiled here — CLAUDE.md mandates
  `Handler(mainLooper).post{}` for overlay show; spot-check unchanged.)*

### Feature flags / Maintenance / Force-update — wired — OK
- `AppConfigRepository` ([data/repository/AppConfigRepository.kt](../app/src/main/java/com/detox/app/data/repository/AppConfigRepository.kt))
  feeds `MainActivity` gating: force-update → `Screen.ForceUpdate`, `maintenanceMode` → `Screen.Maintenance`
  ([MainActivity.kt:127-131](../app/src/main/java/com/detox/app/MainActivity.kt#L127)), with screens
  `MaintenanceScreen`/`ForceUpdateScreen` and `SystemViewModel` present. `config/app` is admin-write,
  authed-read ([firestore.rules:285-287](../firestore.rules#L285)). (CLAUDE.md notes this path is
  fail-open — missing/error config must not lock users out.)

### Account deletion / DSGVO — present — OK
- `deleteAccount(password)` at [SettingsViewModel.kt:254](../app/src/main/java/com/detox/app/presentation/screens/settings/SettingsViewModel.kt#L254):
  deletes Firestore user data (`firestoreService.deleteUserData`, [:306](../app/src/main/java/com/detox/app/presentation/screens/settings/SettingsViewModel.kt#L306)),
  deletes the Firebase Auth account ([:312](../app/src/main/java/com/detox/app/presentation/screens/settings/SettingsViewModel.kt#L312)),
  and clears all Room tables ([:321](../app/src/main/java/com/detox/app/presentation/screens/settings/SettingsViewModel.kt#L321)).
- Logout clears Room **before** sign-out per the CLAUDE.md rule ([:226-227](../app/src/main/java/com/detox/app/presentation/screens/settings/SettingsViewModel.kt#L226)).

---

## Launch checklist (derived)
1. 🔴 **Flip Stripe to live** in both places (B1) and redeploy functions. *(Hard gate on taking money.)*
2. 🟡 Confirm `versionCode` is acceptable to the store (S1).
3. 🟡 Review backup rules / consider `allowBackup="false"` (S2).
4. 🟡 Remove stale DSN TODO; confirm prod Sentry project (S3).
5. 🟢 Smoke-test an actual minified release build (payment + login) before upload.
