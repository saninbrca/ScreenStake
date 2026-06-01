# 00 — Changelog & Session Log
> **Scope:** Chronological log of all fixes, features, and architectural decisions.
> **When to load:** Always load this file first before any other docs/ file.
> This gives Claude Code full context on what has already been fixed and decided.

---

## How to use this file
- Read this BEFORE starting any task
- Add a new entry AFTER every fix or feature with date + short description
- Mark bugs as FIXED so they are never "re-fixed" accidentally
- Mark architectural decisions as DECISION so they are never reversed

---

## [Unreleased] — June 2026

### FEATURE — Admin Dashboard Paket 3: Statistics, Revenue, Broadcast, Counter Backfill, Remote Stake Limits

Final admin-dashboard expansion: an overview landing tab, revenue tracking, a broadcast system
(admin → all users), a counter backfill Cloud Function, and remote-controlled stake limits.

- **`backfillCounters` Cloud Function** (`functions/src/index.ts`, onRequest + `requireAdmin`):
  one-time/sparingly-run recompute. Counts all `users`; scans `collectionGroup("challenges")` for
  solo Hard Mode (`mode==="hard" && !groupChallengeId`) active/completed/failed; sums revenue =
  retained app fees on wins (`appFeeAmount`, fallback `floor(20%)`) + full captured stakes on
  fails + 10% fees from `groupChallenges` with `status=="completed"`. **OVERWRITES** `counters/global`
  (plain `.set`, not increment). Cost note in code: unbounded scan, admin-gated, manual trigger only.
  **Deployed.**
- **Übersicht tab (new, default landing):** first tab, active on load. Cheap live cards read from
  `counters/global` (Nutzer gesamt / Aktive / Abgeschlossen / Gescheitert / Gesamtumsatz). "Heute"
  cards via limited queries (`users createdAt>=startOfToday`, `collectionGroup challenges
  createdAt>=startOfToday`, open-ticket count). Recent activity feed: last 10 challenges
  (collection-group, `orderBy createdAt desc limit 10`, usernames batch-resolved from parent user
  docs) + last 5 tickets (reuses loaded tickets). "🧮 Counter neu berechnen" button → confirm modal →
  `backfillCounters` → reload.
- **Umsatz tab (new):** Gesamtumsatz / diesen Monat / letzte 7 Tage + breakdown (Hard Mode 20%
  fees, eingezogene Einsätze, Group 10% fees) + Ausstehende Verbindlichkeiten (sum of
  `payoutRequests` status `requested`/`pending`). Scans `collectionGroup("challenges")` +
  completed groups client-side; time-bucketed via `payoutDate`/`completedAt`. **Documented cost
  tradeoff** (move to counter-based tracking if it grows) — `counters/global.totalRevenueCents`
  already exists as the cheap alternative.
- **Broadcast system (Part 4):** new `broadcasts/{id}` collection (`title`, `message`, `createdAt`,
  `active`). Admin "📢 Broadcast" tab sends (confirm modal → `add` with `active:true`) + lists past
  broadcasts with Aktivieren/Deaktivieren toggle. **App side** (`DashboardViewModel` +
  `DashboardScreen`): on Dashboard load reads the newest active broadcast (`whereEqualTo("active",
  true).orderBy("createdAt", DESC).limit(1)`); if its id differs from the SharedPreferences
  `last_seen_broadcast_id` (prefs file `detox_broadcast`), shows a one-time `AlertDialog`
  (title + message + "Verstanden" → stores the id, never shown again). Fail-open: any read error
  leaves the banner hidden. New string `broadcast_acknowledge`.
- **Remote stake limits (Part 5):** `AppConfig` gains `hardModeMinStake` (5), `hardModeMaxStake`
  (100), `groupMinBuyIn` (10), `groupMaxBuyIn` (50) — read from `config/app`, cached in
  SharedPreferences, **hardcoded fallback so a missing config never breaks the picker**. The Hard
  Mode stake picker (`ChallengeCreationScreen.Step6Duration`) and Group buy-in picker
  (`GroupChallengeCreateScreen.GStep4BuyIn`) now build their `DetoxHorizontalPicker` range from
  AppConfig (`(safeMin..safeMax)`, value `coerceIn`'d), replacing the hardcoded `(5..100)` /
  `(10..50)`. `GroupChallengeCreateViewModel` now injects `AppConfigRepository` and exposes
  `appConfig`. Admin App Config tab gains 4 number fields wired into `loadConfig`/`saveConfig`.
- **Firestore rules:** new `broadcasts/{id}` — `read: if request.auth != null`,
  `write: if admin email`. **Deployed.**
- **Indexes:** new composite `broadcasts (active ASC, createdAt DESC)` + collection-group
  `fieldOverride` for `challenges.createdAt` (ASC + DESC, COLLECTION + COLLECTION_GROUP scope) so
  the Übersicht/today/recent collection-group queries work. **Deployed** (`firebase deploy --only
  firestore:rules,firestore:indexes,functions:backfillCounters`).

**ACTION REQUIRED:** run "🧮 Counter neu berechnen" once in the dashboard to seed `counters/global`
with real current totals. Optionally set the stake-limit fields in App Config (defaults apply if unset).

**Files changed:** `functions/src/index.ts`, `admin/index.html`, `firestore.rules`,
`firestore.indexes.json`, `data/repository/AppConfigRepository.kt`,
`presentation/screens/challengecreation/ChallengeCreationScreen.kt`,
`presentation/screens/groupchallenge/create/{GroupChallengeCreateScreen,GroupChallengeCreateViewModel}.kt`,
`presentation/screens/dashboard/{DashboardScreen,DashboardViewModel}.kt`, `res/values/strings.xml`,
`docs/00_changelog.md`
**New Firestore: `broadcasts` collection; `config/app` stake-limit fields. New CF: `backfillCounters`.
No Room schema changes. No Stripe flow changes.**

### FEATURE — Admin Dashboard Paket 2: Enhanced Support Ticket Management

Made support tickets powerful in the admin dashboard (`admin/index.html`) — full user context,
private internal notes, archived dashboard replies, and filtering. **Admin-dashboard-only — no
user-facing app changes.**

- **Ticket detail panel (large modal):** clicking any ticket row opens a scrollable detail modal
  (`#ticket-modal`) with: **Ticket-Info** (category, subject, full message, status, created/resolved
  dates, device model, Android + app version) and an auto-loaded **Benutzer-Kontext** section
  (username, email, member since, account status Aktiv/Gesperrt, challenge counts gesamt/aktiv/
  gewonnen/verloren, payment summary eingesetzt/erstattet/einbehalten, "Dieser User hat X weitere
  Tickets gestellt", and a "Vollständiges Profil ansehen →" link → switches to the Benutzer tab and
  opens that user's detail via `gotoUserProfile`). User context is loaded from the ticket's `userId`
  (same queries as the Benutzer tab, minus the redundant payout query).
- **Interne Notizen (admin-only, truly private):** stored in a **separate sub-collection**
  `supportTickets/{ticketId}/adminNotes/{noteId}` (`{ note, createdAt }`, epoch millis) written via
  `.add()`. NOT an array field on the ticket doc — because the ticket owner can read their own ticket
  doc, array notes would leak to them. The sub-collection rule is admin-read/write-only so notes
  never reach the user. UI: notes list (newest first) + text field + "📝 Notiz hinzufügen"
  (`addAdminNote` / `loadAdminNotes`).
- **Dashboard reply (archive):** new ticket-doc fields `adminReply` (String) + `adminReplyAt`
  (Long, epoch millis). "Antwort" section with a textarea (pre-filled with any existing reply),
  an "Als erledigt markieren" checkbox (defaults checked unless already resolved), and two buttons:
  **"✉️ Per E-Mail antworten"** (saves `adminReply` + opens `mailto:` with the reply pre-filled in
  the body) and **"💾 Nur speichern"** (saves without email) — both via `saveTicketReply(sendEmail)`,
  which also sets `status='resolved'` + `resolvedAt` when the checkbox is ticked. **The actual reply
  reaches the user via email; `adminReply` is the admin's archive only** (the app has no in-app reply
  view, and although the user *could* read `adminReply` via direct Firestore, the app never surfaces it).
- **Filter & search (client-side on loaded tickets):** the two separate open/resolved tables were
  merged into a **single filterable list** (`allTickets = open + resolved`, `tickets-content`) with a
  filter bar — category dropdown (Alle/Bug/Frage/Beschwerde/Auszahlung/Sonstiges), status dropdown
  (Alle/Offen/In Bearbeitung/Erledigt), sort (Neueste/Älteste zuerst, default newest), and a
  username/subject search box. All filtering/sorting is client-side in `renderTickets()`. Rows are
  click-to-open; a ✉️ marker shows tickets that have a saved reply. The "Offene Tickets" stat card
  still counts `openTickets` only. `markInProgress` retained (now also closes the modal + refreshes);
  the old per-row `confirmResolve`/`replyTicket`/`renderOpenTickets`/`renderResolvedTickets` and the
  `resolveTicket` confirm-modal branch were removed (dead code).
- **Firestore rules:** new `supportTickets/{ticketId}/adminNotes/{noteId}` sub-collection match —
  `allow read, write: if request.auth.token.email == "sanin.brica@gmail.com"` (admin-only; the parent
  ticket read rule does NOT cascade to sub-collections, so the owner can never read notes). The
  existing admin `update` rule on the ticket doc is field-unrestricted, so `adminReply`/`adminReplyAt`
  + `status`/`resolvedAt` writes are already covered (comment added). **Deployed** (`firebase deploy
  --only firestore:rules` → compiled + released successfully).

**Files changed:** `admin/index.html`, `firestore.rules`, `docs/00_changelog.md`
**No Cloud Function changes. No Room schema changes. No Stripe changes. New Firestore:
`supportTickets/{id}/adminNotes` sub-collection; `supportTickets.adminReply`/`adminReplyAt` fields.**

### FEATURE — Admin Dashboard Paket 1: Counters + User Management + Ban System

Data foundation + user moderation for the admin dashboard. (Statistics dashboards and
broadcast are Paket 3 — not built here.)

- **Counter document `counters/global`** (best-effort dashboard stats): `totalUsers`,
  `totalActiveChallenges`, `totalCompletedChallenges`, `totalFailedChallenges`,
  `totalRevenueCents`, `updatedAt`. All updates use `FieldValue.increment` (never
  read-then-write) via a `bumpCounters()` helper wrapped in try/catch — **a counter failure
  never blocks a payment/challenge op**. Increment points (Cloud Functions, `functions/src/index.ts`):
  - `onUserCreated` (new v1 Firestore `onCreate(users/{userId})` trigger) → `totalUsers +1`.
  - `createPaymentIntent` (solo, `!isGroupChallenge`) → `totalActiveChallenges +1`.
  - `capturePayment` (Hard fail / emergency unlock) → active −1, failed +1, revenue += captured.
  - `cancelOrRefundPayment` (solo/redemption win) → active −1, completed +1, revenue += app fee.
  - `checkPermissionViolations` (permission + usage capture) → active −1, failed +1, revenue += captured.
  - `completeGroupChallenge` (someone-failed) → revenue += 10% group fee.
  - **Scope note:** active/completed/failed track **Hard Mode** challenges only (the events with
    an authoritative server-side money step); Soft Mode has no reliable completion signal and is
    excluded to keep counts balanced. Doc auto-creates on first increment (`set(merge)`).
- **Ban system — both layers** via new CF `setUserBanStatus` (onRequest, admin-email auth):
  Layer 2 `admin.auth().updateUser(uid, { disabled })` (hard — blocks token refresh/sign-in) +
  Layer 1 Firestore `users/{uid}.{disabled, disabledReason, disabledAt}` (instant, app-startup
  enforced). A banned user with an active Hard Mode stake is NOT auto-refunded — existing capture
  rules apply.
- **`AccountDisabledScreen`** (`presentation/screens/system/`, route `account_disabled`): 🚫 red,
  `BackHandler` blocks back, shows `disabledReason` or default, "Support kontaktieren" → mailto.
  Backed by `AccountDisabledViewModel`.
- **`MainActivity` startup ban gate:** after the AppConfig (force-update/maintenance) checks and
  before normal navigation, reads `users/{uid}.disabled` via `FirestoreService.isUserDisabled`
  (**fail-open** — read error → not blocked; Auth-disable is the hard backstop).
- **Admin dashboard "👤 Benutzer" tab:** user search by username OR email (two queries merged —
  Firestore can't OR across fields); detail view (account info + consent + Stripe/IBAN status,
  challenge history with counts, payment totals staked/refunded/captured, support tickets);
  "Sperren"/"Entsperren" actions with a `reason` prompt + confirmation modal → `setUserBanStatus`.
- **Firestore rules:** new `counters/{doc}` (admin read, CF-only write); `users` rule now allows
  **admin read/list** (user search) and admin-only writes to the ban fields, and the **owner
  self-update now blocks `disabled`/`disabledReason`/`disabledAt`** (prevents a banned user
  self-unbanning within their 1h token window). **Deployed** (functions + rules).

**Files changed:** `functions/src/index.ts`,
`presentation/screens/system/{AccountDisabledScreen,AccountDisabledViewModel}.kt` (new),
`data/remote/firebase/FirestoreService.kt`, `MainActivity.kt`,
`presentation/navigation/DetoxNavGraph.kt`, `res/values/strings.xml`, `firestore.rules`,
`admin/index.html`, `docs/00_changelog.md`
**New Firestore: `counters/global` doc, `users.disabled/disabledReason/disabledAt` fields.
New CFs: `setUserBanStatus`, `onUserCreated`. No Room schema changes. No Stripe flow changes.**

### FEATURE — Huawei-safe remote control: Feature Flags + Maintenance + Force Update

A single Firestore document `config/app` now remotely controls the app — no Firebase Remote
Config (incompatible with Huawei/no-GMS). All reads go through the existing `FirebaseFirestore`
instance. **Fail-open contract:** a missing doc or any read error (offline/Huawei) keeps cached
or safe defaults and NEVER locks the user out.

- **`config/app` document fields:** `minVersionCode` (Int), `latestVersionCode` (Int),
  `maintenanceMode` (Bool), `maintenanceMessage` (String), `hardModeEnabled` (Bool),
  `groupChallengeEnabled` (Bool), `updateUrl` (String). Safe defaults: minVersionCode=1,
  maintenanceMode=false, hardModeEnabled=true, groupChallengeEnabled=true.
- **`data/repository/AppConfigRepository.kt` (new):** `@Singleton`, injects `FirebaseFirestore`
  + `@ApplicationContext`. Exposes `config: StateFlow<AppConfig>`; `refresh()` reads `config/app`,
  mirrors to SharedPreferences (`detox_app_config`) and never throws (returns cached/defaults on
  failure). `AppConfig` data class holds the seven fields.
- **Startup gating (`MainActivity.onCreate`):** after computing the normal start destination,
  `appConfigRepository.refresh()` runs, then — highest priority — `VERSION_CODE < minVersionCode`
  → `ForceUpdateScreen`; else `maintenanceMode` → `MaintenanceScreen`; else normal destination.
  Offline → fail-open into the app. The real destination is threaded into `DetoxNavGraph`
  (`maintenanceClearedDestination`) so maintenance "Erneut versuchen" can forward the user once
  cleared.
- **`presentation/screens/system/` (new):** `ForceUpdateScreen` (route `force_update`, ⬆️ icon
  #00C853, `BackHandler` blocks back, "Jetzt aktualisieren" opens `updateUrl`), `MaintenanceScreen`
  (route `maintenance`, 🔧 icon #FF9500, shows `maintenanceMessage` or default, "Erneut versuchen"
  re-reads config and proceeds only if cleared), `SystemViewModel` (config flow + retry).
- **Feature flags (NEW creation only — active challenges untouched):**
  - Hard Mode: `hardModeEnabled=false` greys the Hard Mode card in the wizard Step 1
    (`ChallengeCreationScreen`) with "Vorübergehend nicht verfügbar"; `selectMode` also guards
    server-side. Flag exposed via `ChallengeCreationViewModel.appConfig`.
  - Group Challenge: `groupChallengeEnabled=false` disables the "Erstellen" button in
    `FriendsHubScreen` + shows the unavailable note. Flag from `FriendsHubViewModel.groupChallengeEnabled`.
- **Soft update banner (`DashboardScreen`):** dismissible green banner when
  `VERSION_CODE < latestVersionCode` (and not force-blocked). "Aktualisieren" opens `updateUrl`;
  dismissal stored in `detox_update_banner` SharedPreferences and re-shows after 3 days
  (`DashboardViewModel.showUpdateBanner` / `dismissUpdateBanner`).
- **Firestore rules:** `match /config/app` — `read: if request.auth != null`,
  `write: if request.auth.token.email == "sanin.brica@gmail.com"`. **Deployed** (`firebase deploy
  --only firestore:rules` → released successfully).
- **Admin dashboard:** fourth tab "⚙️ App Config" — toggles (Wartungsmodus, Hard Mode,
  Gruppen-Challenge), text fields (Wartungsnachricht, Update URL), number fields (min/latest
  VersionCode), "Speichern" writes via `.set(payload, { merge: true })`. Loads on tab open.

**Files changed:** `data/repository/AppConfigRepository.kt` (new),
`presentation/screens/system/{ForceUpdateScreen,MaintenanceScreen,SystemViewModel}.kt` (new),
`MainActivity.kt`, `presentation/navigation/DetoxNavGraph.kt`,
`presentation/screens/challengecreation/{ChallengeCreationScreen,ChallengeCreationViewModel}.kt`,
`presentation/screens/friends/{FriendsHubScreen,FriendsHubViewModel}.kt`,
`presentation/screens/dashboard/{DashboardScreen,DashboardViewModel}.kt`, `res/values/strings.xml`,
`firestore.rules`, `admin/index.html`, `docs/00_changelog.md`
**No Cloud Function changes. No Room schema changes. No Stripe changes. New Firestore doc: `config/app`.**

### FEATURE — In-App Support System + Admin Support-Tickets tab + security fix

Added an in-app support flow (contact form + FAQ), extended the admin dashboard with a
Support-Tickets tab, secured the new `supportTickets` collection in Firestore rules, and
removed a hardcoded admin password comment.

- **SECURITY (Part 0):** Removed the `<!-- ADMIN PASSWORD: admin123 -->` comment from line 1
  of `admin/index.html`. Passwords must never live in code/comments. **ACTION REQUIRED:** verify
  the Firebase admin account password is NOT `admin123` and change it in the Firebase console if so.
- **Settings (Part 1):** Replaced the two `mailto:` rows ("Feedback senden" / "Support
  kontaktieren") with a new **HILFE & SUPPORT** section (above APP INFO) containing
  "Support kontaktieren" → `SupportScreen` and "Häufige Fragen (FAQ)" → `FaqScreen`. New nav
  callbacks `onNavigateToSupport` / `onNavigateToFaq` wired in `MainScreen.kt` (routes `support`, `faq`).
- **Support form:** `SupportScreen.kt` + `SupportViewModel.kt`. iOS-style white card (bg `#F2F2F7`):
  Kategorie dropdown (Bug/Frage/Beschwerde/Auszahlung/Sonstiges), Betreff (single line), Nachricht
  (multiline). Validates category + subject + message≥10 chars. On submit writes a **new** doc via
  `supportTickets.add()` (auto-ID — NOT `SetOptions.merge`, this is a create not an upsert) with
  fields: userId, username, email (all from Firebase Auth), category, subject, message,
  status="open", appVersion (`BuildConfig.VERSION_NAME`), deviceModel (`Build.MANUFACTURER+MODEL`),
  androidVersion (`Build.VERSION.RELEASE`), createdAt (epoch millis), resolvedAt (null). Success →
  confirmation screen; failure → inline error.
- **FAQ (Part 2):** `FaqScreen.kt` — 8 expandable cards (chevron rotates) with all Q&A in
  `strings.xml` (`faq_q1..8` / `faq_a1..8`), German.
- **Admin (Part 3):** Third tab "💬 Support Tickets" + "Offene Tickets" stat card. Open tickets
  query `where('status','in',['open','in_progress']).orderBy('createdAt','desc')`; resolved
  `where('status','==','resolved').orderBy('resolvedAt','desc')`. Actions: "In Bearbeitung"
  (status='in_progress'), "Erledigt" (status='resolved' + `resolvedAt: Date.now()` millis to match
  the Android format), "Antworten" (`mailto:` to the ticket's Firebase-Auth email). Category +
  status colored badges.
- **Firestore rules (Part 4):** New `supportTickets` match block — user may `create` only with
  `userId == request.auth.uid` and `read` only their own; `update`/`list` gated on
  `request.auth.token.email == "sanin.brica@gmail.com"`; `delete: false`. **ACTION REQUIRED:**
  `firebase deploy --only firestore:rules`.

**Files changed:** `presentation/screens/settings/SettingsScreen.kt`,
`presentation/navigation/MainScreen.kt`, `presentation/screens/support/SupportScreen.kt` (new),
`presentation/screens/support/SupportViewModel.kt` (new),
`presentation/screens/support/FaqScreen.kt` (new), `res/values/strings.xml`, `firestore.rules`,
`admin/index.html`, `docs/00_changelog.md`
**No Cloud Function changes. No Room schema changes. No Stripe changes. New Firestore collection: `supportTickets`.**

### FEATURE — New launcher icon: brand-green Shield + Checkmark

Replaced the default Android launcher icon with a Shield + Checkmark design while keeping the
existing adaptive-icon structure (background/foreground/monochrome layers) and the
`AndroidManifest.xml` `@mipmap/ic_launcher` + `@mipmap/ic_launcher_round` references unchanged.

- **Background layer** (`drawable/ic_launcher_background.xml`): solid brand green `#00C853`
  (replaces the old `#3DDC84` Android-default grid).
- **Foreground layer** (`drawable/ic_launcher_foreground.xml`): white shield centered within the
  72dp adaptive-icon safe zone (shield bounds x 34–74, y 30–82) with a `#00C853` green checkmark
  stroked inside it.
- **Monochrome layer** (`drawable/ic_launcher_monochrome.xml`, new): line-art silhouette (shield
  outline + check, single `#FFFFFF` fill so the system can tint it) for Android 13+ themed icons.
  Both `mipmap-anydpi/ic_launcher.xml` and `ic_launcher_round.xml` now point `<monochrome>` at it
  (was reusing the foreground).
- **Legacy `.webp` fallbacks** regenerated for mdpi→xxxhdpi (square + round) via a Pillow script
  (`scripts/render_launcher_icons.py`) — these are only used on legacy render paths; all API 26+
  devices (min SDK is 26) use the adaptive XML. Verified via `:app:processDebugResources` (exit 0).

**Files changed:** `drawable/ic_launcher_background.xml`, `drawable/ic_launcher_foreground.xml`,
`drawable/ic_launcher_monochrome.xml` (new), `mipmap-anydpi/ic_launcher.xml`,
`mipmap-anydpi/ic_launcher_round.xml`, all `mipmap-*/ic_launcher{,_round}.webp`,
`scripts/render_launcher_icons.py` (new), `docs/00_changelog.md`
**No Cloud Function changes. No Room schema changes. No Firestore changes. No Stripe changes.**

## [Unreleased] — May 2026

### FEATURE — Legal documents wired to GitHub Pages + clickable consent links

Legal URLs are now real, hosted on GitHub Pages, and externalized to `strings.xml`
(`url_agb`, `url_datenschutz`, `url_impressum`):
- AGB → `https://saninbrca.github.io/screenstake-legal/agb.html`
- Datenschutz → `https://saninbrca.github.io/screenstake-legal/datenschutz.html`
- Impressum → `https://saninbrca.github.io/screenstake-legal/impressum.html`

**Settings (`SettingsScreen.kt`, Datenschutz section):** the two placeholder URLs
(`detox-app.com/privacy` + `/terms`) were replaced by `url_datenschutz` / `url_agb`, and a new
**Impressum** row (`settings_impressum`) was added pointing to `url_impressum`.

**Registration consent (`AuthScreen.kt`, `ConsentRow`):** the "Allgemeinen Geschäftsbedingungen"
and "Datenschutzerklärung" text portions of the consent checkboxes are now clickable links that
open the document in a Custom Tab (`CustomTabsIntent`) — the user can read the docs before
accepting. Implemented with `buildAnnotatedString` + `LinkAnnotation.Url` (Compose 1.7); the link
span consumes the tap so it does not toggle the checkbox. New dependency `androidx.browser:browser`
(`1.8.0`, version catalog). Clickable substrings are separate string resources
(`auth_consent_agb_link`, `auth_consent_datenschutz_link`) so they stay localizable — if a
translation rewords the label, the matching `_link` string must be updated or it falls back to
plain (non-clickable) text.

**Support email:** `support@detox-app.com` → `sanin.brica@gmail.com` everywhere
(`settings_contact_support_subtitle` + both `mailto:` intents in `SettingsScreen.kt`).

### DECISION — Sentry Android SDK integrated for crash + error tracking (replaces Firebase Crashlytics)

Sentry Android SDK integrated (`io.sentry:sentry-android`, pinned `7.14.0` via the version
catalog). Huawei compatible — no Google Play Services dependency (uses its own HTTP transport).

**Crashlytics removed (replaced, not duplicated):** the Firebase Crashlytics Gradle plugin
(root + app `build.gradle.kts`), `firebase-crashlytics-ktx` dependency, and the catalog
plugin/library entries were removed. Code refs swapped: `FirebaseAuthService` dropped its three
`FirebaseCrashlytics.setUserId(uid)` calls (now centralized in Sentry — see below);
`DailyEvaluationWorker`'s `recordException(e)` → `Sentry.captureException(e)`.

**Init (`DetoxApplication.onCreate`):** `SentryAndroid.init` runs first (before
`PaymentConfiguration.init`). DSN from `BuildConfig.SENTRY_DSN`; environment
development/production from `BuildConfig.DEBUG`; release `${APPLICATION_ID}@${VERSION_NAME}`;
auto session tracking + ANR + activity/app-component breadcrumbs enabled; `sampleRate = 1.0`;
`tracesSampleRate` 1.0 debug / 0.1 production. `beforeSend` returns `null` in DEBUG so **debug
builds never send events**.

**DSN placeholder** in `defaultConfig` `buildConfigField("SENTRY_DSN", ...)` +
`manifestPlaceholders["SENTRY_DSN"]` (`https://PLACEHOLDER@sentry.io/PLACEHOLDER`) — replace
with the real DSN after sentry.io project creation (marked with a TODO).

**GDPR/DSGVO:** only the Firebase UID is sent as the Sentry user id — never email or name.
`Sentry.setUser` is set/cleared in the existing `FirebaseAuth.addAuthStateListener` in
`DetoxApplication.startGroupChallengeSyncing()` (single chokepoint covering login, register,
Google, and logout).

**Breadcrumbs added:** `PaymentRepositoryImpl.cancelOrRefundPayment` (central Stripe
refund/cancel chokepoint — every Hard Mode refund flows through it, logs operation/challengeId/
amountCents); `PermissionCheckWorker` (permission loss, logs permissionType + elapsed);
`AppDetectionAccessibilityService` (`onServiceConnected` / `onInterrupt`).

**Manifest:** `io.sentry.dsn` meta-data (`${SENTRY_DSN}`) + `io.sentry.auto-init = false`
(manual init). **ProGuard:** keep `io.sentry.**` + annotation/source/line attributes.

**Setup TODO (after sentry.io project creation):** replace the placeholder DSN in
`app/build.gradle.kts` (`buildConfigField` + `manifestPlaceholders`), build a release APK on
Huawei, and trigger a test crash to confirm it reaches the dashboard.

**Files changed:** `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`,
`AndroidManifest.xml`, `app/proguard-rules.pro`, `DetoxApplication.kt`, `FirebaseAuthService.kt`,
`DailyEvaluationWorker.kt`, `PaymentRepositoryImpl.kt`, `PermissionCheckWorker.kt`,
`AppDetectionAccessibilityService.kt`, `docs/00_changelog.md`
**No Cloud Function changes. No Room schema changes. No Firestore changes. No Stripe logic changes.**

### FIXED — Two History/Hard-Mode bugs: multi-app history display + black screen on Hard Mode quit

Two independent UI/navigation bugs. Data was stored correctly in both cases — fixes are
display/navigation-layer only. Verified via `:app:compileDebugKotlin` (exit 0).

**Bug 1 — History detail showed only the first app of a multi-app challenge — FIXED.**
- Root cause (display): `HistoryDetailScreen` took only `appPackageNames.split(",").firstOrNull()`
  and showed a single icon + the single `appDisplayName`. Multi-app challenges store all packages
  in `ChallengeEntity.appPackageNames` (comma-separated) but were rendered as one.
- Root cause (creation): `ChallengeCreationViewModel.displayName()` returned only the **first**
  selected app's name, so `appDisplayName` held one name even for multi-app challenges.
- Fix (`HistoryDetailScreen.kt`): the header now loops over **all** of `appPackageNames`, rendering
  one icon + name row per app with a 0.5px `#F2F2F7` divider between them. Each app's label is
  resolved via `PackageManager` (`resolveAppName`, falls back to the split `appDisplayName` then the
  raw package) — so already-existing multi-app challenges also display correctly. Website challenges
  (no package) still show the single display name. The date range moved below the app list (it
  applies to the whole challenge, not per app).
- Fix (`ChallengeCreationViewModel.kt`): `displayName()` now joins all selected app names
  comma-separated, so newly created multi-app challenges store all names.

**Bug 2 — Quitting a Hard Mode challenge ("Aufgeben") showed a black screen — FIXED.**
- Root cause: `abandonChallenge()` set status FAILED then signaled `abandonSuccess`, and
  `ActiveChallengeScreen` navigated via a bare `navController.popBackStack()`. When the challenge
  was reached via a notification deep-link (`active_challenge/{id}`, used by Hard-Mode
  failure/redemption/80%-usage notifications), there was no valid destination below → black screen.
  There was no failure/redemption screen registered in the nav graph for the manual quit path.
- Fix: new `HardModeFailScreen` (+ `HardModeFailViewModel`) at route `hard_mode_fail/{challengeId}`.
  Dark fullscreen (`#0A0A0A`, `isAppearanceLightStatusBars = false`, same style as the Group Results
  screen): 💸 icon, "Challenge verloren." title (red period), white money card showing the captured
  stake (`€X,XX` formatted from integer cents — never rounds up), encouragement line, "Zurück zum
  Dashboard" primary button (white bg, black text, 54dp) + "Neue Challenge starten" text link.
  `HardModeFailViewModel` loads the stake one-shot via `ChallengeRepository.getChallengeById`.
- Wiring: `ActiveChallengeViewModel.abandonChallenge()` routes **HARD** mode to a new
  `hardModeFailChallengeId` flow; Soft/Group quit keep the existing `popBackStack()` behavior
  unchanged. `ActiveChallengeScreen` gained an `onHardModeFail` callback. `MainScreen` registers the
  new route and navigates to it with `popUpTo(Dashboard)` so the Dashboard always sits below — no
  black screen even on the deep-link entry path. The pre-existing Dashboard `HardModeFailOverlay`
  (auto-fail / limit-exceeded path) is untouched.

**Strings (`strings.xml`):** reused existing `hard_fail_title`; added `hard_fail_screen_subtitle`,
`hard_fail_captured_label`, `hard_fail_captured_subtext`, `hard_fail_encouragement`,
`hard_fail_back_to_dashboard`, `hard_fail_new_challenge`. The Dashboard overlay's other
`hard_fail_*` keys were left intact.

**Files changed:** `HistoryDetailScreen.kt`, `ChallengeCreationViewModel.kt`,
`ActiveChallengeViewModel.kt`, `ActiveChallengeScreen.kt`, `MainScreen.kt`,
`HardModeFailScreen.kt` (new), `HardModeFailViewModel.kt` (new), `strings.xml`, `docs/00_changelog.md`
**No Cloud Function changes. No Room schema changes. No Firestore changes. No Stripe changes.**

### AUDIT — Three audit findings reviewed: join-window validation, debug-log cleanup, Room emission race

Three findings from a security/hygiene audit. Verified via `:app:compileDebugKotlin` (exit 0).
Net code change: one client comment + removal of debug-only log locals. No CF deploy, no schema
change.

**Fix 1 — Server-clock join-window validation — VERIFIED (already enforced).** The concern was
that `JoinGroupChallengeUseCase` gates the join window with `System.currentTimeMillis()` (device
clock), which a user could move forward to bypass. Investigation confirmed the **server is already
the security boundary**: `joinGroupChallenge` (`functions/src/index.ts`) re-validates with the
server clock — `status !== "waiting"` and `startDate <= Date.now()` (there is no `joinWindowEnd`
field in this codebase; the join window *is* `startDate`). No Cloud Function change or deploy was
needed. Added a clarifying comment above the client-side check in `JoinGroupChallengeUseCase.kt`:
`// UX guard only — server re-validates with Date.now() in joinGroupChallenge CF`.

**Fix 2 — Removed debug-only log locals in `AppDetectionAccessibilityService` — FIXED.** Deleted
the four `_`-prefixed locals (`_sessionPrefsForLog`, `_nowForLog`, `_activeSessionPackage`,
`_sessionEndTimeForLog`) and their two `Timber.d` calls — leftover instrumentation used only for
logging, not in any detection/blocking logic. No behavior change; confirmed callerless before
removal.

**Fix 3 — Room first-emission race — VERIFIED (existing guards sufficient, no change).** The
concern was that a service's first Room Flow emission can be empty (DAO returns before sync
populates), causing the overlay to show wrong limit values. Investigation found this is already
covered:
- Overlay limit decisions read DailyLog via **one-shot** `getLogForDate(...)` suspend reads (e.g.
  `OverlayManager`), not a Flow — so there is no empty-then-real first emission on the overlay
  path. The DAILY_BUDGET path additionally falls back to the full budget when `budgetUsedMs == 0`.
- The package-tracking Flow in `UsageTrackingService` already has an empty-emission guard: it skips
  an all-empty update when Room still reports active challenges (race-condition guard).
- Applying the suggested `.drop(1)` to that Flow would prevent blocking from starting until the
  challenge list *changes* — a regression — so it was deliberately not applied.
- The group-session-info Flow (`startGroupSessionLimitTracking`) was reviewed and left unchanged: an
  empty emission there is benign (the synchronous fast-path in `AppDetectionAccessibilityService`
  guards with `if (sessionInfo != null)` and falls back to the Room-based overlay path). Adding a
  retain-last-value guard would risk surfacing a stale `opensToday >= limit` after a challenge ends.

**Files changed:** `JoinGroupChallengeUseCase.kt`, `AppDetectionAccessibilityService.kt`,
`docs/00_changelog.md`
**No Cloud Function changes. No Room schema changes. No Firestore changes. No Stripe changes.**

### CLEANUP — Codebase hygiene pass: day-math constant, hardcoded strings, Firestore merge audit, dead ThresholdFlags removal + DB migration 24→25

Four low-risk hygiene fixes from an audit. All verified via `:app:compileDebugKotlin`,
`:app:assembleDebug` (Room schema validation passes), and `functions` `tsc` build. Functions
re-deployed (`firebase deploy --only functions`).

**Fix 1 — Inline day-in-millis replaced with the shared constant.**
- `ChallengeSuccessDialog.kt` `Challenge.durationDays`: both inline `86_400_000L` →
  `DateUtils.MILLIS_PER_DAY` (added `import com.detox.app.util.DateUtils`).
- `functions/src/index.ts:1169`: `const twentyFourHours = 24 * 60 * 60 * 1000` → reuse existing
  `MILLIS_PER_DAY` constant (`index.ts:8`). **Deployed** to production.

**Fix 2 — Hardcoded production UI strings moved to `res/values/strings.xml`** (German, snake_case
keys; interpolated values use `%1$s`/`%1$d` format args; non-composable `NotificationHelper` uses
`context.getString`). English strings were translated to German in the process.
- New keys: `done`, `ok`, `retry`, `add`, `fix_now`, `loading_apps`, `grant_permission`,
  `cancel_challenge_title`, `cancel_challenge_body`, `discard`, `keep_editing`, `delete_schedule`,
  `also_block_domains`, `join_code_placeholder`, `iban_placeholder`, `enter_iban_now`,
  `group_result_too_few`, `group_result_all_succeeded`, `group_result_all_failed`,
  `group_bonus_per_winner`, `group_stake_refunded`, `connect_bank_account`, `group_bonus_transfer`,
  `notif_overlay_captured_body`.
- Files: `AppWebsiteSelectionStep.kt`, `ChallengeCreationScreen.kt`, `GroupChallengeDetailScreen.kt`,
  `ChallengeSetupScreen.kt`, `GroupChallengeJoinScreen.kt`, `GroupChallengeCreateScreen.kt`,
  `SettingsScreen.kt`, `MainActivity.kt` (added `stringResource` import), `NotificationHelper.kt`.
- **Not touched** (out of scope): ProfileScreen debug panel, FriendsHubScreen debug labels, and two
  English error strings in `AppWebsiteSelectionStep.kt` ("Usage access permission…", "Failed to load apps.").

**Fix 3 — DECISION: Firestore `.set()` merge audit.** Rule reaffirmed: any **DailyLog** write MUST use
`SetOptions.merge()`; create-only writes stay full-document with an explanatory comment.
- `FirestoreService.saveDailyLog` → now `.set(log.toMap(), SetOptions.merge())`.
- `FirestoreService.saveChallenge` → kept full create (only caller is `createChallenge`), commented.
- `GroupChallengeFirestoreService.saveGroupChallenge` → kept full create/overwrite (no update
  call-site), commented.
- `FirestoreService` `usernames/{name}` `txn.set` → kept (new doc, created once), commented.

**Fix 4 — Removed dead `ThresholdFlags` usage-threshold subsystem + Room migration 24→25.** The
`sendUsageThreshold` (50/75/90%) notifications were removed in the earlier notification cleanup; the
Room columns were left behind "to avoid a migration" — now removed cleanly. Confirmed callerless
before deleting.
- Deleted: `domain/model/ThresholdFlags.kt`; `DailyLog`/`DailyLogEntity` fields `notified50/75/90`;
  DAO `getThresholdFlags` + `markNotified50/75/90`; repo `getThresholdFlags` + `markThresholdNotified`
  (interface + impl) and the mapper lines in `toDomain`/`toEntity`.
- **`DetoxDatabase`**: `version = 24` → `25`. `MIGRATION_24_25` recreates `daily_logs` without the
  three columns (CREATE-new + INSERT-select 14 cols + DROP + RENAME — non-destructive, no DROP COLUMN;
  matches Room's expected schema incl. FK + both indices). Registered in `di/DatabaseModule.kt`.

### FEATURE — Notification deep-links: tapping any notification opens the relevant screen

**Problem:** Of the 11 remaining notifications, only `sendGroupChallengePayoutReceived` had a tap
action (and it used a stale `navigate_to` extra that nothing consumed — a dead link). Every other
notification just re-opened the app to wherever it had last been.

**Change:** All 11 notifications now deep-link to the correct screen when tapped.
- `MainActivity` is `launchMode="singleTop"` with an `onNewIntent` + `handleDeepLink(intent)` helper
  (also called from `onCreate` after `startDestination` resolves). It reads `nav_target`/`nav_arg`
  extras and emits the matching `TrackedAppEventBus` event.
- `TrackedAppEventBus` gained 4 replay=1 SharedFlows: `navigateToDashboard`, `navigateToProfile`,
  `navigateToChallengeDetail`, `navigateToHistoryDetail` (reusing existing `navigateToGroupDetail`).
- `MainScreen` collects all of them and navigates via its own NavController to the real routes
  (`dashboard`, `profile`, `active_challenge/{id}`, `history_detail/{id}`, `group_detail/{id}`).
- `NotificationHelper.buildDeepLinkIntent()` builds each `PendingIntent` with `FLAG_IMMUTABLE` and a
  unique request code (the notification's own notifId). Mapping: hard-mode/challenge-completed →
  history detail; redemption available/failed + 80% usage → challenge detail; permission-failed +
  redemption-completed + group payout → profile; group-completed + participant-failed → group detail;
  usage-violation → dashboard. Functions needing an id gained an optional `challengeId`/`groupId`
  param (passed from `DailyEvaluationWorker`, `RedemptionNotificationWorker`, FCM handler).

**Auth guard:** if the user is not yet on `Screen.Main` (logged out / onboarding), the target is
stashed in `detox_settings` prefs (`pending_deep_link_target`/`_arg`) and replayed by `MainScreen`
once it is first shown post-login, then cleared.

**Not changed:** `sendPermissionWarning` / `sendPermissionEscalation` still deep-link to system
settings (overlay/accessibility), as before.

**Files changed:** `AndroidManifest.xml`, `MainActivity.kt`, `TrackedAppEventBus.kt`, `MainScreen.kt`,
`NotificationHelper.kt`, `DailyEvaluationWorker.kt`, `RedemptionNotificationWorker.kt`,
`DetoxFirebaseMessagingService.kt`.

### DECISION — Notification cleanup: 26 → 11 functions, low-value notifications removed, one made toggleable

**Problem:** `NotificationHelper` had grown to 26 notification functions. Many fired low-value or
redundant notifications (daily reports, per-day congratulations, sub-80% usage thresholds, group
lifecycle chatter, accessibility lost/restored confirmations, daily/start reminders, taunts) that
added noise without informing a money- or security-relevant event.

**Change — removed 15 notification functions** and every call site (no dead code left behind):
`sendChallengeFailed`, `sendDailyReport`, `sendDayCongratulations`, `sendUsageThreshold` (50/75/90),
`sendPermissionRestored`, `sendAccessibilityLost`, `sendAccessibilityRestored`,
`sendGroupChallengeStartWarning`, `sendGroupChallengeLeft`, `sendGroupChallengeDeleted`,
`sendGroupChallengeCancelled`, `sendGroupChallengeExpired`, `sendDailyReminder`,
`sendGroupStartReminder`, `showTauntNotification`. Also removed the now-orphaned
`CHANNEL_DAILY_REPORT` channel and the unused notif-ID constants.

**Kept (all money/security + completion + 80% usage):** `sendHardModeCompleted`,
`sendPermissionWarning`, `sendPermissionFailed`, `sendPermissionEscalation`,
`sendGroupChallengeCompleted`, `sendGroupChallengePayoutReceived`, `sendRedemptionAvailable`/
`Completed`/`Failed`, `sendUsageViolationDetected`, `sendUsage80Percent`, `sendChallengeCompleted`,
and `sendGroupParticipantFailed`.

**Made toggleable:** `sendGroupParticipantFailed` (the one surviving "social" notification) now
checks `detox_notifications` SharedPreferences key `notif_group_participant_failed` (default `true`)
and returns early if disabled. New Settings → Benachrichtigungen toggle "Wenn Teilnehmer scheitert"
routes through `SettingsViewModel.setGroupParticipantFailedEnabled`.

**Cascading removals (sole purpose was a removed notification):** deleted `DailyReminderWorker`,
`GroupStartReminderWorker`, and `ServiceWatchdogWorker` files; removed their scheduling in
`DetoxApplication` (+ `scheduleDailyReminder`/`scheduleServiceWatchdog`/`observeAppForeground`/
`isAccessibilityServiceRunning`) and `BootReceiver`; removed `scheduleStartReminder` in
`GroupChallengeCreateViewModel`; removed `startUsagePolling`/`checkUsageThresholds` and the
`USAGE_THRESHOLDS`/`ThresholdSpec` subsystem in `UsageTrackingService`. **Full removal** of the
orphaned daily-reminder Settings feature (toggle + time-picker UI, VM functions, prefs keys, strings).

**DECISION:** Only money-related, security/permission-related, challenge-completion, and 80%-usage
notifications survive. `sendGroupParticipantFailed` is the sole user-toggleable notification. Do not
re-add daily-report/day-congrats/sub-80%-threshold/lifecycle/taunt notifications. Room `ThresholdFlags`
columns (`notified50/75/90`) + DAO/repo methods left intact (now unused) to avoid a migration — a
possible follow-up cleanup.

**Files changed:** `NotificationHelper.kt`, `DailyEvaluationWorker.kt`,
`DetoxFirebaseMessagingService.kt`, `PermissionCheckWorker.kt`, `UsageTrackingService.kt`,
`GroupChallengeAutoStartWorker.kt`, `MainActivity.kt`, `GroupChallengeDetailViewModel.kt`,
`OverlayManager.kt`, `DetoxApplication.kt`, `BootReceiver.kt`, `GroupChallengeCreateViewModel.kt`,
`SettingsViewModel.kt`, `SettingsScreen.kt`, `strings.xml`, `docs/00_changelog.md`. Deleted:
`DailyReminderWorker.kt`, `GroupStartReminderWorker.kt`, `ServiceWatchdogWorker.kt`.
**No Room schema changes. No Firestore/Cloud Function changes.**

### FIXED — Hard Mode refund clock-forward exploit (server-side validation + client COMPLETED gating)

**Problem (two compounding holes):** The solo Hard Mode completion path trusted the client.
`DailyEvaluationWorker` decided a win locally with `now >= challenge.endDate` (device clock), then
called `cancelOrRefundPayment` — and the Cloud Function issued the Stripe refund **without any
server-side checks**, trusting the client-supplied `amountCents` and `paymentIntentId`. A user
could set the device clock forward, trigger the worker, and collect the 80% refund before the
challenge actually ended. Worse, the worker marked the Room row `COMPLETED` **before** confirming
the refund succeeded, so a failed/rejected refund still closed the challenge with no retry.

**Fix (server — `functions/src/index.ts`, `cancelOrRefundPayment`):** for the non-redemption
(solo Hard Mode win) path, before issuing any Stripe refund the CF now:
1. Fetches `users/{verifiedUserId}/challenges/{challengeId}` (requires `challengeId`; 404 if missing).
2. Asserts `Date.now() >= challenge.endDate` using the **server clock** — never the client's time
   (400 "Challenge has not reached its end date.").
3. Asserts `challenge.payoutStatus !== "refunded"` — idempotency guard (409 if already paid out).
4. Asserts `challenge.stripePaymentIntentId === paymentIntentId` — PI binding check (400 on mismatch).
5. Recomputes the refund as `Math.floor(challenge.amountCents * 0.80)` from the **stored** stake and
   uses that (`serverAmountCents`) for all three non-redemption Stripe branches — the client-supplied
   `amountCents` is ignored entirely.
The redemption/partial-refund branch (`partialRefundCents > 0`, uses the original PaymentIntent and
its own stored `refundAmountCents`) is left fully intact.

**Fix (client — `DailyEvaluationWorker.kt`):** the Room row is only marked `COMPLETED` **after**
`cancelOrRefundPayment` succeeds. A new `hardModeWinRefundFailed` flag is set in every win-refund
`.onFailure` handler across all three completion paths (TIME_BUDGET, TIME/SESSIONS, and the
"already evaluated today" short-circuit). If the flag is set, the worker logs a warning and
`continue`s — the challenge stays `ACTIVE` in Room and the next worker cycle retries automatically.
Only the order of operations changed; capture/notification/analytics logic is untouched.

**DECISION:** Hard Mode refunds are validated server-side. The Cloud Function NEVER trusts the
client's clock, refund amount, or PaymentIntent id — it re-derives `endDate`, the 80% amount, and
the PI match from the stored challenge doc, and guards idempotency via `payoutStatus`. The client
NEVER marks a Hard Mode challenge `COMPLETED` before the refund Cloud Function returns success;
a failed refund leaves the challenge `ACTIVE` for retry. **Never reverse either guard.**

**Files changed:** `functions/src/index.ts`, `DailyEvaluationWorker.kt`, `docs/00_changelog.md`,
`docs/03_hard_mode_and_stripe.md`, `docs/09_payout_and_fees.md`
**Deployed:** `firebase deploy --only functions` run successfully for project `detox-33208`.
**No Room schema changes. No Firestore rule changes.**

### FIXED — Accessibility-only permission loss now mirrors to Firestore (server-side capture gap)

**Problem:** When ONLY accessibility permission was lost (overlay still granted),
`PermissionCheckWorker.checkAccessibilityPermission()` wrote only to SharedPreferences
(`accessibilityLostAt`) and never to Firestore `permissionStatus/current`. The server-side
`checkPermissionViolations` CF queries `permissionLostAt != null`, so it never saw this case.
Result: a user could disable accessibility, wait 24h, and the Hard Mode stake was never
captured server-side (no protection against app uninstall / data clear for this path).

**Fix (`PermissionCheckWorker.checkAccessibilityPermission`):**
- On accessibility loss (after the existing SharedPreferences write): mirror to Firestore
  `users/{uid}/permissionStatus/current` with `permissionLostAt`, `permissionType="accessibility"`,
  `deviceId` (ANDROID_ID), via `SetOptions.merge()` — fire-and-forget, same pattern as the
  overlay branch in `doWork()`.
- On accessibility restore (after the existing SharedPreferences clear): write
  `permissionLostAt = FieldValue.delete()` + `permissionRestoredAt = now` so the CF does not
  capture after the user re-enables the service.
- Existing SharedPreferences logic, the overlay branch in `doWork()`, and the
  `sendAccessibilityLost` / `sendAccessibilityRestored` notifications are unchanged.

**DECISION:** Accessibility-only permission loss now mirrors to Firestore
`permissionStatus/current`. Server-side 24h capture now covers all three cases:
overlay lost only ✅, accessibility lost only ✅ (was missing — now fixed), both lost ✅.

**Files changed:** `PermissionCheckWorker.kt`, `docs/00_changelog.md`
**No Cloud Function changes (CF query already covers `permissionLostAt`). No Room schema changes. No Stripe changes.**

### FIXED — DailyEvaluationWorker now requires CONNECTED network constraint

**Problem:** `scheduleDailyEvaluation()` built the `PeriodicWorkRequest` with no constraints.
If the device was offline at the 23:59 run time, the worker still executed but its Cloud
Function calls failed — Hard Mode refunds and challenge completions were silently not processed.

**Fix (`DetoxApplication.scheduleDailyEvaluation`):** added a `Constraints` with
`setRequiredNetworkType(NetworkType.CONNECTED)` and `.setConstraints(constraints)` on the
builder. If online at 23:59 → runs immediately as before. If offline → WorkManager defers the
run until internet returns, then runs automatically. Scheduling time (23:59), the 24h period,
`ExistingPeriodicWorkPolicy.KEEP`, the `TAG_DAILY_EVALUATION` tag, and the worker's internal
logic are all unchanged. No other workers touched.

**DECISION:** `DailyEvaluationWorker` requires the `CONNECTED` network constraint. WorkManager
retries automatically when internet returns. **Never remove this constraint** — Hard Mode
Stripe refunds depend on it.

**Note (KEEP policy):** because the work is enqueued with `ExistingPeriodicWorkPolicy.KEEP`,
installs that already have `"daily_evaluation"` scheduled keep the old (constraint-free) request
until it is cancelled/re-enqueued (e.g. via `BootReceiver` re-enqueue or a fresh install). New
installs get the constraint immediately.

**Files changed:** `DetoxApplication.kt`, `ProfileViewModel.kt` (same constraint on the debug
manual-trigger `OneTimeWorkRequest`), `docs/00_changelog.md`
**No Cloud Function changes. No Room schema changes. No Stripe changes. No other workers changed.**

### FIXED — "Last Day Loophole": challenge endDate now ends at midnight of the last day

**Problem:** `endDate` was computed as `startTime + durationDays × 86_400_000ms`. A 7-day
challenge started 19. Mai 16:00 ended 26. Mai 16:00 — after 16:00 on the final day the app
stopped blocking, so the user could scroll freely until midnight.

**Fix (client):** `endDate` is now always 23:59:59.999 of the last day, never mid-day. Always
use `DateUtils.endOfDayMillis(startMs, durationDays)` — a `Calendar`-based helper (DST-safe),
never raw millisecond arithmetic. The solo path preserves the debug `minutes-as-days` test
mode (branch on `durationMultiplier`); the group path uses the helper directly.

**Fix (server):** `startGroupChallenge` recomputed `endDate = now + durationDays * 86400000`
when the creator starts a group challenge, overwriting the client value. Added a matching
`endOfDayMillis(startMs, durationDays)` helper to `functions/src/index.ts` and `startGroupChallenge`
now uses it. Note: the Cloud Function runs in UTC, so server end-of-day is UTC-based vs. the
client's device-local timezone — both land at end-of-day (loophole closed), but are not bit-identical.

**DECISION:** Challenge `endDate` is always 23:59:59.999 of the last day. Never calculated as
`startTime + N × 86_400_000`. Always use `DateUtils.endOfDayMillis(startMs, durationDays)`
(client) / `endOfDayMillis(startMs, durationDays)` (Cloud Function).

**Files changed:** `DateUtils.kt`, `CreateChallengeUseCase.kt`, `CreateGroupChallengeUseCase.kt`,
`functions/src/index.ts`
**Deployed:** `firebase deploy --only functions` run successfully for project `detox-33208`
(all 18 functions updated, including `startGroupChallenge`).
**No Room schema changes. No Stripe changes.**

### Feature — Unique @username system (Instagram-style)

Every user picks a permanent, unique, lowercase `@username` right after email verification.

**Storage & service (`FirestoreService.kt`, `FirebaseAuthService.kt`):**
- New `usernames/{username}` collection (doc id = lowercase name, `{uid, createdAt}`).
- `getUsername(uid)`, `isUsernameAvailable(username)` (fail-closed on error), and
  `saveUsername(uid, username)` which claims the name **atomically** via `runTransaction`
  (rejects if taken → `IllegalStateException("username_taken")`) and writes
  `username` + `displayName` onto `users/{uid}` (SetOptions.merge).
- `updateDisplayName(name)` mirrors the username onto the FirebaseUser Auth profile.
  **DECISION:** No Cloud Function changes — group create/join/taunt already read
  `user.displayName`, so new participants carry the username automatically.

**Selection screen (`username/UsernameSelectionScreen.kt` + `…ViewModel.kt`, new):**
- @-prefixed lowercase input (a–z/0–9/_), min 3 / max 20, "X / 20" counter, 500ms
  debounced availability check (green Verfügbar / red Bereits vergeben). `BackHandler`
  blocks back — a username is mandatory. Self-skips if the account already has one.
- Route `username_selection?fromRegister={bool}` in `DetoxNavGraph.kt`. EmailVerification
  and Google sign-up now route through it; `AuthViewModel.NeedsUsername` routes verified
  logins without a username.

**Migration gate (`MainActivity.determineStartDestination`):** verified users without a
username (cached in `detox_settings`/`username` or looked up in Firestore) are routed to
the selection screen on next launch.

**Display:** `@username` shown on Profile (20sp bold), Group Challenge leaderboard, results
podium + failed list, and taunt messages. Falls back to email prefix for legacy accounts.

**Rules (`firestore.rules`):** `usernames/{username}` — authed read, owner-only create of a
free name (`uid` match + `!exists`), no update/delete.

### Feature — Auth overhaul: consent, email verification, validation, re-auth

**Registration (RegisterForm in `AuthScreen.kt`):**
- Inline email validation (empty / invalid format), no Toasts.
- Password strength indicator (3-segment bar + label): Schwach (#FF3B30) < 8 chars,
  Mittel (#FF9500), Stark (#00C853). Show/hide password eye toggle.
- Three required consent checkboxes — AGB, Datenschutz, Age 18. "Konto erstellen"
  button enabled only when email valid, password ≥ 8, passwords match, and all three checked.
- On register: writes `consentAGB`/`consentDatenschutz`/`consentAge18`/`consentTimestamp`
  to the Firestore user doc (`createUserDocument`, SetOptions.merge — legal proof of consent),
  sends a verification email, and routes to the new EmailVerificationScreen (NOT the dashboard).

**Email verification (`EmailVerificationScreen.kt` + `EmailVerificationViewModel.kt`, new):**
- Route `email_verification?fromRegister={bool}` in `DetoxNavGraph.kt`.
- "Ich habe bestätigt" → `reload()` + `isEmailVerified` check; inline error if still unverified.
- "E-Mail erneut senden" with 60s cooldown countdown + Toast. Auto-polls reload() every 5s and
  auto-navigates when verified. "Falsche E-Mail?" link signs out + returns to registration.
- After register → verified routes to permissions Onboarding; after login → verified routes to Main.

**Login (LoginForm):** inline field errors (empty/invalid email, empty password), show/hide
password, German Firebase error mapping, "Passwort vergessen?" link with inline confirmation.
Unverified accounts are blocked from the dashboard and redirected to EmailVerificationScreen.

**Settings — Passwort ändern:** inline confirmation text + 60s resend cooldown (was Toast).

**Settings — Konto löschen:** re-authentication password field added to the delete dialog;
`deleteAccount(password)` calls `reauthenticateWithPassword` before the existing deletion flow
(Hard Mode guard → Firestore delete → Auth delete → Room clear). Wrong password → inline error.

**`FirebaseAuthService`:** added `sendEmailVerification()`, `reloadAndCheckEmailVerified()`,
`isEmailVerified()`, `reauthenticateWithPassword()`.

**Files changed:** `AuthScreen.kt`, `AuthViewModel.kt`, `EmailVerificationScreen.kt` (new),
`EmailVerificationViewModel.kt` (new), `FirebaseAuthService.kt`, `FirestoreService.kt`,
`DetoxNavGraph.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`, `strings.xml`
**No Cloud Function changes. No Room schema changes. No Stripe changes.**

---

### FIXED — Group Challenge Room status never updated after endDate (critical)

**Root cause (`DailyEvaluationWorker.evaluateGroupChallenge`):**
After `completeGroupChallenge` CF call succeeded, the code read `localStatus` from Room to
determine whether the user won or lost. But Room was never updated — the challenge row stayed
`status="active"` forever, so it never appeared in the History screen.

**Fix:**
- After CF success, call `groupChallengeRepository.fetchAndCacheById(groupId)` (always, not
  only on the "succeeded" path).
- Find the participant with matching `userId` in the returned group doc.
- Map `ParticipantStatus.FAILED → ChallengeStatus.FAILED`, else `ChallengeStatus.COMPLETED`.
- Call `challengeRepository.updateChallengeStatus(challenge.id, finalStatus)` to update Room.
- Derive `succeeded` from `finalStatus` (not from the stale local Room read).

**DECISION:** Group Challenge local Room row MUST reflect final outcome after CF completes.
Never leave groupChallenge rows as `status="active"` after `endDate` is reached.

**Files changed:** `DailyEvaluationWorker.kt`
**No Cloud Function changes. No Room schema changes. No Firestore changes.**

---

### Changed — History screen (HistoryScreen + HistoryDetailScreen) UI updates

**HistoryScreen (list view):**
- App name: 16sp SemiBold → 17sp Bold, color explicitly #000
- Date: 13sp → 12sp, color #8E8E93
- Right side now shows TWO badges stacked (Column):
  - Top: TYPE badge (pill) — GROUP (#EEF0FF/#5856D6), HARD MODE (#FFF0E8/#C05A00),
    SOFT MODE (#E8F8EF/#1E7A3C). Determined by `groupChallengeId != null` first, then `mode`.
  - Bottom: STATUS text (no background) — "✓ Geschafft" #00C853, "✗ Aufgegeben" #FF3B30.
- Old single `StatusChip` (pill with bg) replaced by `TypeBadge` + `StatusText` composables.
- Card background hardcoded to #FFFFFF (was `MaterialTheme.colorScheme.surface`).

**HistoryDetailScreen (detail view):**
- Removed "ZEIT ZURÜCKGEWONNEN" section header + large saved-time number + subtext.
- Removed "weniger Zeit" (percentageReduction) third column from stats card.
- Stats card now shows 2 centered columns only: "Beste Streak" | "Bewusst geöffnet".
  Both values: 17sp bold #000. Labels: 12sp #8E8E93.
- Removed "Nochmal starten" button entirely (and its trailing Spacer).
- `onStartAgain` kept in public composable signature (navigation still passes it); removed
  from private `DetailContent`. No navigation changes needed.
- Removed unused `Button` + `ButtonDefaults` imports.

**Files changed:** `HistoryScreen.kt`, `HistoryDetailScreen.kt`
**No Cloud Function changes. No Room schema changes. No strings.xml changes.**

---

## [Unreleased] — May 2026 (Summary)

### Added
- **ChallengeSuccessDialog:** Replaced `SoftModeSuccessOverlay` and `HardModeSuccessOverlay`
  (fullscreen black blocking composables) with a single dismissible `Dialog {}` composable
  shown on top of the Dashboard. Supports both Soft Mode (time saved card) and Hard Mode
  (money refund card). Confetti animation, staggered phase reveals, count-up stat animations.
  SharedPreferences guard `"win_shown_{challengeId}"` prevents re-show after dismiss.
  `DashboardViewModel` now exposes `successDialogState: StateFlow<SuccessDialogState?>`.
  `DailyLogRepository.getLogsForChallengeOnce()` added for one-shot log reads.

- **Server-side 24h Permission Violation Timer:** permissionLostAt
  written to Firestore (users/{uid}/permissionStatus/current) when
  Accessibility or Overlay permission is lost. Cloud Function
  checkPermissionViolations (onRequest) captures Stripe payment
  after 24h server-side — independent of app state/installation.
  scheduledPermissionCheck runs every 1 hour via Cloud Scheduler.
  capturedAt field only writable by Cloud Function (Admin SDK).
- **UsageStats Backup Detection:** PermissionCheckWorker checks
  UsageStatsManager for blocked app usage when Accessibility is
  disabled. If blocked app used > 1 min → usageViolationDetectedAt
  written to Firestore → Cloud Function captures after 1 hour.
- **Rooted Device Detection:** RootBeer library integration.
  Checks on Hard Mode challenge creation. Non-blocking warning
  dialog shown. Root info logged to Firestore deviceInfo collection.
- **Escalation Notifications:** New stage-aware notifications at
  6h, 12h, 23h after permission loss with escalating urgency.
  Hour 23: explicit warning that payment will be captured in 1h.
- **Debug Panel — Permission Violation Tests:** 5 new debug buttons:
  Simulate Permission Loss (Firestore), Simulate Usage Violation,
  Check Root Status, Force CF Permission Check, Reset Permission
  Status. Debug builds only.
- **Completion Screens:** Added missing completion/failure screens:
  SoftModeSuccessOverlay (Soft Mode COMPLETED — was silent),
  HardModeFailOverlay (Hard Mode FAILED — was silent),
  SoftFailResultScreen now correctly triggered via
  DailyEvaluationWorker (was dead code).
- **DetoxHorizontalPicker — correct bounds:**
  Session limit: 1-20 (was 1-50)
  Session duration: 1-30 min (was 1-120)
  Time limit: 5-120 min (was 1-480)
  Daily budget: 5-120 min (was 1-480)
  Soft Mode duration: 3-90 days (was 1-365)
  Hard Mode duration: 7-90 days production, 1-90 debug (was 14-365)
  Group duration: 3-30 days (was 3-365)
  Group buy-in: 10-50€ (was 10-500)
  Hard Mode stake: 5-100€ (was 5-50)
- **Shared Rank in Group Challenge Leaderboard:** Equal opensToday
  = shared rank (standard competition ranking 1,1,3 not 1,2,3).
  Applies to leaderboard display and overlay context header.
- **Remove "Spezifische Features sperren":** Entire partial block
  section removed from Websites tab (Instagram Reels, YouTube
  Shorts etc.). Adult Content and domain blocking unchanged.
- **Warning Banner redesign:** Permission warning banner redesigned
  to solid #FF3B30 red with white text, subtle pulse animation,
  "Jetzt beheben →" white button. Clear and urgent iOS style.
- **App Selection — "busy" state fix:** Apps already in active
  challenge show grey background + grey text + lock icon.
  No red "busy" label, no duplicate app name in red.
- **Next button fix:** Next button in App/Website selection step
  now enabled if ANY selection exists across both tabs (Apps OR
  Websites), not just the active tab.
- **Wizard Review — app names:** Shows actual app names instead
  of "X ausgewählt". Limit format uses lowercase "x" consistently.
- **Onboarding rotating stats:** 3 stats rotate every 2s with
  fade transition on Screen 1 (4,2 Stunden / 96 Mal / 63 Tage).
- **Group Challenge — 5-day auth window:** Payment authorized
  (not captured) on join. Captured on Start. Auto-cancel after
  5 days if not started.

### Fixed
- **Wizard text:** All wizard steps translated to German.
  No emojis in titles or descriptions.
- **Weekdays:** Mo Di Mi Do Fr Sa So (was English abbreviations)
- **Hard Mode debug:** BuildConfig.DEBUG check for min duration.

---

## [Unreleased] — May 2026 (Detailed)

### Feature — ChallengeSuccessDialog (replaces both success overlays)

**Problem:** `SoftModeSuccessOverlay` and `HardModeSuccessOverlay` were fullscreen Compose
composables that blocked the entire Dashboard. They could not be dismissed without starting a
new challenge, and tapping through could accidentally navigate to the Detail Screen.

**Solution:** Both replaced by a single `ChallengeSuccessDialog` — a Compose `Dialog {}`
shown on top of the Dashboard. Other challenge cards remain visible behind the dialog scrim.

**Android changes:**
- New `ChallengeSuccessDialog.kt` — handles both Soft Mode (time saved card) and Hard Mode
  (money refund card). Canvas-based confetti animation (40 particles, 4 colors).
  Staggered phase reveals at 0ms / 300ms / 600ms / 900ms. Count-up stat animations.
  X close button + "Zurück zum Dashboard" secondary link both dismiss dialog.
  "Neue Challenge starten" primary CTA navigates to wizard.
- `DashboardViewModel`: replaced `completedChallenge` + `completedSoftChallenge` StateFlows
  with unified `successDialogState: StateFlow<SuccessDialogState?>`.
  Added `dismissSuccessDialog()`. Added `@ApplicationContext` injection.
  Added SP guard check: `"win_shown_{challengeId}"` in `"detox_win_popup"` prefs.
- `DashboardScreen`: removed `HardModeSuccessOverlay` + `SoftModeSuccessOverlay` composables.
  Now renders `ChallengeSuccessDialog` when `successDialogState != null`.
- `DailyLogDao`: added `getLogsForChallengeOnce(challengeId)` suspend query.
- `DailyLogRepository` + `DailyLogRepositoryImpl`: added matching interface + implementation.
- 14 new German strings in `strings.xml` (`success_dialog_*`).

**DECISION:** SharedPreferences guard `"win_shown_{challengeId}"` in `"detox_win_popup"` file
is the primary show guard (checked before setting state). DB `completionShown` flag is
also marked on dismiss as belt-and-suspenders for the existing DB mechanism.

**Stats calculation:**
- TIME: `totalUsedMinutes = sum(dailyLog.totalMinutes)`
- TIME_BUDGET: `totalUsedMinutes = sum(dailyLog.budgetUsedMs) / 60_000`
- SESSIONS: `totalUsedMinutes = totalConsciousOpens * sessionDurationMinutes`
- Hard Mode money: `refund = floor(amountCents × 0.80) / 100`, `fee = amountCents/100 - refund`
- Reduction %: `(1 - totalUsed/budget) * 100`, clamped 0–99

**Files changed:** `ChallengeSuccessDialog.kt` (new), `DashboardViewModel.kt`, `DashboardScreen.kt`,
`DailyLogDao.kt`, `DailyLogRepository.kt`, `DailyLogRepositoryImpl.kt`, `strings.xml`, `docs/00_changelog.md`
**No Cloud Function changes. No Room schema changes. No Firestore changes. No Stripe changes.**

---

### Feature — UsageStats Backup Detection + Rooted Device Detection

**UsageStats Backup Detection (`PermissionCheckWorker.kt`):**
- When Accessibility Service is disabled, `checkAndReportUsageViolation()` runs each worker cycle.
- `detectUsageViolation()` queries `UsageStatsManager.INTERVAL_BEST` for the last 1 hour.
  If any blocked package has > 1 min foreground time → violation detected.
- First detection: writes `usageViolationDetectedAt`, `violatingPackage`, `usageMinutes` to
  `users/{uid}/permissionStatus/current` (SetOptions.merge()). Local flag in
  `"detox_usage_violation"` SharedPrefs prevents repeat writes.
- Clears local flag when accessibility is re-enabled (service working again).
- Sends "⚠️ App-Nutzung erkannt" notification via new `NotificationHelper.sendUsageViolationDetected()`.
- NEVER counts as conscious open — purely for violation detection.

**Cloud Function (`checkPermissionViolations` / `scheduledPermissionCheck`):**
- `runPermissionViolationCheck()` now also queries `usageViolationDetectedAt != null`.
- If > 1 hour elapsed since violation AND no `usageCapturedAt` set → capture Hard Mode Stripe payments.
- Writes `usageCapturedAt` (CF-only field, blocked by Firestore rules on client).

**Rooted Device Detection:**
- New `RootDetectionManager.kt` object (uses `com.scottyab:rootbeer-lib:0.1.0`).
- `ChallengeCreationViewModel.createChallenge()`: before initiating Hard Mode payment,
  calls `RootDetectionManager.checkAndWarn()`. If rooted → sets `RootedDeviceWarning` UiState
  and logs `isRooted: true` to `users/{uid}/deviceInfo/security` (fire-and-forget).
- `ChallengeCreationScreen`: shows non-blocking AlertDialog with "Verstanden — trotzdem fortfahren"
  / "Abbrechen" buttons. User must explicitly acknowledge before payment proceeds.
- Root never blocks challenge creation — warn + log only.

**Firestore rules:**
- `permissionStatus`: adds `usageCapturedAt` to CF-only blocked keys list.
- New `deviceInfo` sub-collection: user read/write, `adminVerified` field blocked on client.

**Files changed:** `PermissionCheckWorker.kt`, `NotificationHelper.kt`, `RootDetectionManager.kt` (new),
`ChallengeCreationViewModel.kt`, `ChallengeCreationScreen.kt`, `functions/src/index.ts`,
`firestore.rules`, `strings.xml`, `app/build.gradle.kts`
**Requires deploy:** `firebase deploy --only functions` and `firebase deploy --only firestore:rules`

---

### Feature — Server-side 24h permission loss timer for Hard Mode

Mirrors the `permissionLostAt` timer to Firestore so Stripe capture happens server-side
even if the user uninstalls the app or clears data before the 24h deadline.

**Android (`PermissionCheckWorker.kt`):**
- On first permission loss: writes `permissionLostAt`, `permissionType`, `deviceId` to
  `users/{uid}/permissionStatus/current` (fire-and-forget, SetOptions.merge()).
- On permission restore: clears `permissionLostAt`, sets `permissionRestoredAt`.
- Hour-aware escalation notifications at 6h, 12h, 23h via new `NotificationHelper.sendPermissionEscalation()`.

**Android (`DailyEvaluationWorker.kt`):**
- Calls `checkPermissionViolations` CF as non-fatal fallback at end of daily evaluation.

**Cloud Functions (`functions/src/index.ts`):**
- `checkPermissionViolations` (onRequest): captures Stripe for Hard Mode solo + group participants
  whose permission has been missing > 24h. Accepts Bearer token or x-internal-secret header.
- `scheduledPermissionCheck` (pubsub, every 1 hour): same logic, runs automatically.
- Shared `runPermissionViolationCheck()` helper.

**`firestore.rules`:** New `permissionStatus` sub-collection rule. Client cannot write
`capturedAt`/`captureReason` (CF-only fields via Admin SDK).

**`functions/.env`:** Added `INTERNAL_SECRET` for scheduler auth.

**Files changed:** `PermissionCheckWorker.kt`, `NotificationHelper.kt`, `CloudFunctionsService.kt`,
`DailyEvaluationWorker.kt`, `functions/src/index.ts`, `functions/.env`, `firestore.rules`, `strings.xml`
**Requires deploy:** `firebase deploy --only functions:checkPermissionViolations,functions:scheduledPermissionCheck`
and `firebase deploy --only firestore:rules`

---

### Fixed — DetoxHorizontalPicker min/max values corrected across all wizards

Updated picker ranges to realistic UX bounds in UI layer only. No logic, schema, or Stripe changes.
- SESSION_LIMIT: 1–50 → 1–20
- TIME_LIMIT: 1–480 → 5–120
- DAILY_BUDGET: 1–480 → 5–120
- Session duration: 1–60 → 1–30
- Duration Soft Mode: 1–365 → 3–90 (min raised to 3)
- Duration Hard Mode: 14–365 → 7–90 production (debug stays 1); max lowered to 90
- Duration Group: 3–365 → 3–30
- Buy-in Group: 10–500 → 10–50
- Hard Mode Einsatz: 5–500 → 5–100
- All pickers clamp selectedValue to new range via `coerceIn`/`coerceAtMost`

**Files changed:** `ChallengeCreationScreen.kt`, `GroupChallengeCreateScreen.kt`, `docs/08_ui_design_system.md`

---

### Fixed — Group Challenge leaderboard shared rank (standard competition ranking)

Leaderboard now uses standard competition ranking (1,1,3) instead of sequential (1,2,3).
When multiple participants have the same `opensToday`, they receive the same rank.
- `GroupChallengeDetailScreen.kt`: Pre-calculates `rankMap` (userId → rank) for active
  participants before the leaderboard section. Failed participants get rank 0, displayed as "—".
- `LeaderboardRow`: rank display now shows "—" for failed participants (was "#N").
- Rank colors corrected: silver `#B0BEC5` → `#C0C0C0`; Platz 4+ `TextSecondary` → `#8E8E93`.
- `OverlayManager.kt`: `computeGroupRank()` updated to use shared ranking — finds the index
  of the first participant with the same `opensToday` as the user. Failed participants excluded
  from rank calculation. `ParticipantStatus` import added.
- Context header (`"👥 Platz #X von Y"`) now reflects correct shared rank.

**Files changed:** `GroupChallengeDetailScreen.kt`, `OverlayManager.kt`
**No Cloud Function changes. No Room schema changes. No Firestore changes. No Stripe changes.**

---

### Changed — Permission warning banner redesign (DashboardScreen)
- Replaced dark-red aggressive banner with clean iOS-style solid red card (#FF3B30).
- Card: 12dp radius, 16dp padding, no elevation, no shadow.
- Layout: title row (⚠️ + "Berechtigung fehlt" 15sp 700 white), body text (13sp white 80% alpha, maxLines=2), right-aligned "Jetzt beheben →" button (white bg, red text, 8dp radius, 8×16dp padding).
- Pulsing animation: scale 1f→1.01f, 2000ms infinite Reverse repeat via `rememberInfiniteTransition`.
- Permission check on every RESUME via `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` + `Settings.canDrawOverlays`.
- Three message variants: accessibility missing / overlay missing / both missing.
- Banner visible only when at least one permission is missing AND active challenges exist (Success state only).
- CTA navigates to `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
- 5 new German strings added to strings.xml.

**Files changed:** `DashboardScreen.kt`, `strings.xml`
**No Cloud Function changes. No Room schema changes. No business logic changes.**

---

### Feature — Full wizard redesign (Soft, Hard, Group modes)

**ChallengeCreationScreen.kt (Solo wizard — Soft + Hard Mode):**
- Global: Background #F2F2F7, screen padding 16dp, card radius 16dp, 0.5px border rgba(0,0,0,0.06)
- WizardHeader: "Schritt X von Y" (13sp, #8E8E93, centered) + 2dp #00C853 progress bar
- Next button: 54dp height, 14dp radius, #00C853 bg, #FFFFFF text, 16sp bold; disabled: #E0E0E5 bg, #8E8E93 text
- Step 1 (Mode): redesigned ModeCard with icon circles, badge pills, radio/checkmark indicator
- Step 3 (Limit Type): redesigned LimitTypeCard with icon circles, radio/checkmark; 4 cards for solo (includes Time Window Only)
- Step 4 (Set Limit): "Limit festlegen" title, German unit labels, card wrapper
- Step 5 (Schedule): OPTIONAL label + skip button; German pill day buttons (Mo/Di/Mi/Do/Fr/Sa/So); "Nutzungsplan" title
- Step 6 (Duration): "Challenge-Dauer" title, card-style no-end-date toggle
- Step 7 (Review): "Überprüfen & starten" title, summary card with SummaryDividerRow, "Challenge starten" button

**GroupChallengeCreateScreen.kt (Group wizard):**
- Same design tokens as Solo wizard (private GWiz* color constants)
- WizardHeader: "Schritt X von Y" + 2dp green progress bar
- Surface background: #F2F2F7; next button: 54dp, 14dp radius, #00C853, "Weiter"
- Step 2 (Limit Type): 3 GGroupLimitTypeCard composables (TIME, SESSIONS, TIME_BUDGET — no Time Window); icon circles, radio/checkmark
- Step 3 (Limit+Duration): "Limit festlegen" title, German unit labels, card wrappers
- Step 4 (Buy-In): German title/subtitle, pot estimate inline
- Step 5 (Start Date + Bonus): German text, white card layout with Switch rows
- Step 6 (Review): "Überprüfen & erstellen" title, GSummaryDividerRow summary card, "Challenge erstellen" button
- DECISION: Group wizard has no Mode Selection step — user already committed to Group mode when navigating here

**WelcomeOnboardingScreen.kt:**
- RotatingStatCard: delay(3000) → delay(2000) — stats rotate every 2 seconds

---

### Fixed — Design consistency audit (typography, spacing, colors, border radius)

**Overlays:**
- `BlockingScreenOverlay.kt`: App name top-right 13sp→11sp, color #666666→#333333. Progress labels 13sp→11sp.
- `SessionIntentionOverlay.kt`: App name 13sp→11sp, color TextSecond(#666)→#333333. Progress labels 13sp→11sp.
- `SessionLimitReachedOverlay.kt`: Progress labels color #333333→#AAAAAA (spec: 11sp #AAAAAA).
- `BudgetSelectionOverlay.kt`: Progress labels color #333333→#AAAAAA.
- `LimitExceededOverlay.kt`: Background #1A0000/#0D0D0D→#0A0A0A. All `MaterialTheme.typography.*` replaced with explicit sp values (title 15sp bold, appName 15sp, time 13sp bold, message 14sp, streak 13sp bold). Button #2E7D32→#00C853, 52dp height, 14dp radius, black text.

**Detail Screen (ActiveChallengeScreen.kt):**
- Progress bar height 10dp→8dp. Colors: MaterialTheme.primary→AccentGreen (#00C853), surfaceVariant→#E0E0E5.
- Soft Mode badge: bg #E8F5E9→#E8F8EF, text #2E7D32→#1E7A3C. Badge radius 50dp pill→4dp.

**Onboarding (WelcomeOnboardingScreen.kt):**
- OnboardingCard and ModeCard borders: 1dp #E0E0E5→0.5dp rgba(0,0,0,0.06).
- ModeCard badge radius: 8dp→4dp.

**strings.xml:**
- `limit_exceeded_title`: "Limit Reached"→"Tageslimit erreicht"
- `limit_exceeded_hard_title`: "You'll lose €%.2f!"→"Du verlierst €%.2f!"
- `limit_exceeded_hard_message`, `limit_exceeded_message`, `limit_exceeded_time_used`: English→German

**Files changed:** `BlockingScreenOverlay.kt`, `SessionIntentionOverlay.kt`, `SessionLimitReachedOverlay.kt`, `BudgetSelectionOverlay.kt`, `LimitExceededOverlay.kt`, `ActiveChallengeScreen.kt`, `WelcomeOnboardingScreen.kt`, `strings.xml`
**No Cloud Function changes. No Room schema changes. No business logic changes.**

---

### Changed
- **Onboarding Screen 3 (Modi) — badge text colors:**
  - Soft Mode badge: text color `#00C853` → `#1E7A3C` (bg #E8F8EF unchanged).
  - Hard Mode badge: text color `#FF6B35` → `#C05A00` (bg #FFF0E8 unchanged).
  - Group Challenge badge: `#7B61FF` on `#EEF0FF` — already correct, no change.
  - Title "Wähle deinen Modus" with "Modus" green — already correct.
  - Mode descriptions ("Kostenlos. Streak-basiert." etc.) — already correct.
  - Two new private color constants added: `GreenBadgeText`, `OrangeBadgeText`.

**Files changed:** `WelcomeOnboardingScreen.kt`
**No strings.xml changes. No Cloud Function changes. No Room schema changes. No other onboarding screens touched.**

---

- **Dashboard — title + card badge labels:**
  - Screen title changed "Your Challenges" → "Aktive Challenges".
  - Challenge card badge strings updated: "SOFT" → "SOFT MODE", "HARD" → "HARD MODE",
    "GROUP" → "LIVE".
  - No debug button "Demo: blockierte App öffnen" was found — already absent from production code.
  - FAB confirmed bottom-right (Scaffold default `floatingActionButton` slot — no change needed).
  - Note: streak/stake/pot fields absent from `DailyStats`; adding them requires model/ViewModel
    changes outside the scope of UI-only tasks.

**Files changed:** `strings.xml`
**No Cloud Function changes. No Room schema changes. No business logic changes.**

---

- **Challenge Detail Screen — stats row & info list cleanup (Soft + Hard Mode):**
  - Stats row reduced from 3 columns to 2: only "Streak" and "Tage noch" remain.
    "Beste Streak" column removed for Soft Mode; "Einsatz" column removed from stat row for
    Hard Mode (still available in the info list below).
  - Fire emoji `🔥` removed from streak value; label changed "Aktuelle Streak" → "Streak".
  - "Erfolgsrate" row removed from the info list card.
  - Top padding before "Challenge aufgeben" link increased from 16dp → 40dp.

**Files changed:** `ActiveChallengeScreen.kt`, `strings.xml`
**No Cloud Function changes. No Room schema changes. No business logic changes. Group Challenge Detail Screen untouched.**

---

### Added
- **Onboarding Screen 1 — rotating motivational statistics:**
  Static stat card replaced with `RotatingStatCard` composable that cycles through 3 German
  statistics every 3 seconds with 300ms fade transition (`AnimatedContent`):
  1. "96 Mal" / "So oft entsperrst du dein Handy täglich"
  2. "4,2 Stunden" / "Durchschnittliche tägliche Bildschirmzeit"
  3. "63 Tage" / "Lebenszeit pro Jahr am Handy verschwendet"
  - Stat number: 48sp bold #00C853; description: 14sp #8E8E93, centered.
  - 3 dot indicators below: 6dp circles, active=#00C853, inactive=#C7C7CC, 6dp gap.
  - `LaunchedEffect(Unit)` loop with `delay(3000)`.
  - No changes to any other onboarding screen, navigation, or auth logic.
  - 6 new strings added: `welcome_p0_stat1_value/desc`, `welcome_p0_stat2_value/desc`,
    `welcome_p0_stat3_value/desc`.

**Files changed:** `WelcomeOnboardingScreen.kt`, `strings.xml`
**No Cloud Function changes. No Room schema changes. No auth logic changes.**

---

### Changed
- **Overlay redesign — dots indicator + button text:**
  - Progress bar replaced by circular dot indicators (10dp, 8dp gap) for all overlays
    where `limit ≤ 10`. Filled dots = `#00C853` (used), empty dots = `#333333` (remaining).
    If limit > 10, progress bar is kept unchanged.
  - Primary button text changed from "Stark bleiben 💪" → "Nicht öffnen" across all overlays
    (`SessionIntentionOverlay`, `BlockingScreenOverlay`, `SessionLimitReachedOverlay`,
    `BudgetSelectionOverlay`, `TimeWindowOverlay`, `LimitExceededOverlay`).
  - Emoji removed from `stay_strong_button` string: "Stark bleiben 💪" → "Stark bleiben".
  - Ghost button "trotzdem öffnen" text color changed from `#222222` → `#FFFFFF` in
    `SessionIntentionOverlay` and `BlockingScreenOverlay`.
  - `SessionLimitReachedOverlay` gains new `limitCount: Int = 0` parameter —
    passed from `OverlayManager.showSessionLimitReachedOverlay` as `maxOpens`.
  - New `OverlayDotsIndicator` internal composable added to `SessionIntentionOverlay.kt`
    (shared across the overlay package via same-package visibility).
  - New string resource: `overlay_primary_not_open` = "Nicht öffnen".

**Files changed:** `SessionIntentionOverlay.kt`, `BlockingScreenOverlay.kt`,
`SessionLimitReachedOverlay.kt`, `BudgetSelectionOverlay.kt`, `TimeWindowOverlay.kt`,
`LimitExceededOverlay.kt`, `OverlayManager.kt`, `strings.xml`
**No Cloud Function changes. No Room schema changes. No Stripe changes. No business logic changes.**

---

### Added
- **Group Challenge Results Screen:** Animated podium with top 3 players (Platz 1 center/tallest,
  Platz 2 left, Platz 3 right), each column rising sequentially. Konfetti rain + Lottie trophy
  animation for Platz 1. Shown once per challenge via SharedPreferences guard
  `"podium_shown_{groupId}"`.

### Fixed
- **Group Challenge TIME_LIMIT:** `timeUsedMinutes` was counted incorrectly — incremented
  during overlay display and when user was not in the app. Timer now only runs during
  active app usage, pauses during overlay, stops when user leaves app.
- **Group Challenge SESSION_LIMIT:** `opensToday` had no Room fallback. Added `containsKey`
  guard + Room fallback matching Solo behavior.
- **Group Challenge TIME_LIMIT exceeded:** Now shows `LimitExceededOverlay` same as Solo,
  instead of `SessionLimitReachedOverlay`.
- **Group Challenge DAILY_BUDGET exhausted:** Context header now shows rank
  `"👥 Platz #X von Y"` instead of hardcoded `"⏱ 0 min"`.
- **Group Challenge DAILY_BUDGET:** Added `BudgetSelectionOverlay` (horizontal picker)
  + 5-second countdown matching Solo behavior.
- **Session persistence:** `TIME_LIMIT` session end time now stored in SharedPreferences
  (`"session_end_time_{packageName}"`) so brief app switches don't reset the session.
- **Dead code removed:** `captureAndLock` and `handleGroupChallengeFail` removed.
- **Lottie compile errors fixed:** Correct imports for `rememberLottieComposition`
  + `animateLottieCompositionAsState`.
- **Haptic feedback on Huawei:** Replaced `LocalHapticFeedback` with direct `Vibrator` API
  via `HapticManager`.

### Changed
- **Haptic feedback:** Removed from all overlays. Kept only in wizard Next buttons,
  app selection taps, and `DetoxHorizontalPicker` number changes.

---

## [Unreleased] — May 2026

### Added
- **Group Challenge Results Screen:** Fullscreen podium celebration
  shown once after Group Challenge ends. Animated podium with top 3
  players (Platz 1 center/tallest, Platz 2 left, Platz 3 right),
  each rising sequentially. Konfetti rain + Lottie trophy animation
  for Platz 1. User result card shows win/loss + payout info.
  "Weiter" button navigates to Detail Screen. Shown only once per
  challenge via SharedPreferences guard "podium_shown_{groupId}".
  Failed participants shown below podium.
- **Haptic Feedback:** Added HapticManager (direct Vibrator API,
  Huawei-compatible). Light haptic on wizard Next buttons, app
  selection taps, and DetoxHorizontalPicker number changes.
  DetoxHorizontalPicker upgraded from TextHandleMove to LongPress.
  All haptic removed from overlays.
- **UI Animations:** Staggered card entrance on Dashboard,
  FAB pulse when no challenges exist, stats count-up animation
  in Detail Screen, animated progress bar fill on screen open,
  Settings sections slide in, Profile avatar bounce, Verlauf
  filter tab indicator slide, Group leaderboard animateItemPlacement,
  challenge card scale on tap, bottom nav icon bounce.
- **Overlay Redesign:** Solo overlay progress bar increased to 8dp,
  progress labels improved contrast (#AAAAAA), better vertical
  balance. Group overlay redesigned from white card dialog to
  full-screen dark overlay matching Solo structure. All English
  strings replaced with German. Ghost button "trotzdem öffnen"
  aligned with Solo design.
- **Adult Content — Domain List Expansion:** Expanded from ~100
  to 50,000+ domains using OISD, StevenBlack, and ut1 blocklists.
  Auto-update via AdultDomainsUpdateWorker (monthly, WorkManager).
  Domains saved to filesDir for updates without app reinstall.
  Debug Panel shows domain count + source + force update button.
- **Adult Content — Block moved to top of Websites Tab:** Adult
  Content card now appears at top of Websites tab in wizard
  (before URL input and feature cards).
- **Website Challenge — Icon + Name Display:** Favicons loaded
  via Google Favicon Service. Name shows feature name
  (e.g. "Instagram Reels") or domain (e.g. "instagram.com").
  Detail Screen shows "BLOCKIERTE WEBSITES" section with
  favicon + name + URL path per row.
- **Group Challenge — shared App/Website selection:** Group
  Challenge Wizard now uses same App/Website selection component
  as Solo Wizard (1:1 identical, shared Composable).
- **Profile Screen — minimized:** Removed stats row (streak,
  challenges done, apps blocked). Only Avatar, username,
  member since, Guthaben Card (if applicable) remain.
  Settings accessible via card row.
- **Settings Screen — iOS-style redesign:** Full redesign with
  grouped white cards, colored icon circles, section headers.
  Sections: Konto, Aktivität, Auszahlungskonto, Erscheinungsbild,
  Benachrichtigungen, Berechtigungen, Datenschutz, App Info,
  Entwickler. Friend Alerts removed (not implemented).
  Rate App opens Play Store. Dark Mode marked "Experimentell".
- **App/Website Selection — iOS-style redesign:** Pill-shaped
  search field, no dividers between app rows, 48dp rounded icons,
  green checkmark + #F9FFF9 background on selection. Websites tab:
  platform app icons for feature cards with red 8dp badge,
  Adult Content card with "18+" circle. Pill tab switcher animation.

### Fixed
- **Group DAILY_BUDGET — BudgetSelectionOverlay context header:**
  `BudgetSelectionOverlay` hardcoded "⏱ X min übrig heute" for all
  challenge types. Group challenges must show "👥 Platz #X von Y".
  Fix: added `contextHeader: String` parameter to the composable.
  `OverlayManager.handleTimeBudgetApp()` now calls `buildContextHeader()`
  (same as every other overlay) and passes the result down.
- **BudgetSelectionOverlay — missing "stark bleiben 💪" ghost button:**
  Ghost button (10sp, #222222, height 32dp) now shown below the
  primary button, matching the design spec. Tapping it calls `onGoBack`
  (dismiss + go home) without starting or consuming any budget.
- **Group Challenge opensToday bug:** opensToday showed 5/5 or
  6/5 instead of 0. Two fixes: (1) Room upsert now runs
  unconditionally for every ACTIVE Firestore snapshot instead
  of only on status change. (2) OverlayManager now reads
  opensToday from TrackedAppEventBus.groupSessionInfos instead
  of stale Room DAO value.
- **Deleted challenge reappears after Recents kill:** Challenge
  now deleted from Firestore before Room. SyncRepository skips
  cancelled/deleted/completed challenges on refresh.
- **Website challenge icon/domain reset after Recents:** Added
  empty list guard in UsageTrackingService — never overwrites
  non-empty cache with empty list when active challenges exist.
- **PERMISSION_DENIED for dailyLogs:** Added explicit Firestore
  rule for /users/{userId}/challenges/{challengeId}/dailyLogs/{logId}
  allowing read/write by owner. Deployed to production.
- **Lottie compile errors:** Fixed wrong imports in
  GroupChallengeResultsScreen — replaced with correct
  rememberLottieComposition + animateLottieCompositionAsState API.

---

## [Unreleased] — May 2026

### FIXED — Group Challenge TIME_LIMIT session lost when user briefly leaves app
- **Root cause 1 (`OverlayManager`):** `showBlockingOverlay` (TIME_LIMIT) never called
  `startSessionTimer()` on "trotzdem öffnen". Only `allowTemporarily()` (5s in-memory)
  was called, so `session_end_{pkg}` was never written to SharedPreferences. On return,
  `sessionEndTime=0` and the overlay fired again immediately.
  **Fix:** `onOpenAnyway` now calls `startSessionTimer(sessionDurationMinutes ?: 5)`,
  persisting the session end time. `onStayStrong` and `onBack` now clear the key and
  call `cancelSessionTimer()`.
- **Root cause 2 (`AppDetectionAccessibilityService`):** The `TYPE_WINDOW_CONTENT_CHANGED`
  re-entry path (Recents-based return) had a DAILY_BUDGET session check but no
  `session_end_{pkg}` check. Even if the key was present, CONTENT_CHANGED would bypass it.
  **Fix:** Added session end-time check in the CONTENT_CHANGED path, matching the existing
  check in the `TYPE_WINDOW_STATE_CHANGED` path. Expired sessions are cleaned up inline.

### FIXED — Website challenge icon and domains disappear after Recents kill (incomplete prior fix)

**Root causes (3, compounding):**

1. **Guard used in-memory bus instead of Room** (`UsageTrackingService`):
   The race-condition guard checked `TrackedAppEventBus.trackedPackages.value.isNotEmpty()` as
   `busHasData`. After a Recents kill, the entire process dies — the bus is always empty on
   restart. So `busHasData` was always `false`, the guard never fired, and the first empty
   Room emission always cleared the bus. Fix: guard now calls `challengeRepository.getActiveChallengesList()`
   directly. If Room has active challenges but the emission is all-empty → skip. Only accept
   an empty update when Room is also empty (genuinely no active challenges).

2. **Guard didn't cover partial block paths/sections** (`UsageTrackingService`):
   The old `newDataIsEmpty` check only tested `packages.isEmpty() && blockedDomains.isEmpty()`.
   For website-only challenges with only partial paths (e.g. `youtube.com/shorts`), both were
   already empty → guard would incorrectly allow overwriting with empty even if the bus had data.
   Fix: `allNewDataIsEmpty` now also checks `partialPaths.isEmpty() && partialSections.isEmpty()`.

3. **`partialBlockDomains` missing from sync mapper** (`SyncRepositoryImpl`):
   `SyncRepositoryImpl.Challenge.toEntity()` mapped `blockedDomains` but NOT `partialBlockDomains`.
   Every `syncUserData()` call overwrote the challenge entity via `INSERT OR REPLACE` with
   `partialBlockDomains = null`, silently clearing partial block paths from Room on every restart.
   Fix: added `partialBlockDomains = partialBlockDomains.joinToString(",").ifEmpty { null }`.

**Additional fix — Part B (post-sync bus push):**
After `syncUserData()` fully populates Room (end of step 4), explicitly push packages, domains,
partial paths, and sections to `TrackedAppEventBus`. Guarantees the bus is correct immediately
after sync, even if the Room Flow re-emission was suppressed by the guard or arrived before
sync completed. Non-fatal — wrapped in try/catch, logged as `SyncRepository: post-sync bus update`.

**Also improved logging** in `UsageTrackingService`:
- Pre-guard log: `"updating packages=N domains=M from X challenges"` (always printed)
- Guard log: `"skipping empty update — Room has N active challenges"` (when guard fires)

**Files changed:** `UsageTrackingService.kt`, `SyncRepositoryImpl.kt`
**No Cloud Function changes. No Room schema changes. No Firestore changes. No Stripe changes.**

---

### FIXED — 3 bugs after app restart from Recents

**Bug 1 — Deleted challenge reappears after Recents**

Root cause: When `updateChallengeStatus(FAILED)` is called, Room is updated correctly to
"failed" but Firestore rules block the client from updating `status` → Firestore document
stays "active". On restart, `syncUserData()` calls `fetchActiveChallenges()` (filters
`status="active"`) → challenge is re-inserted into Room via `insertChallenge(REPLACE)` →
challenge reappears on Dashboard.

Fixes:
- `firestore.rules`: `allow delete: if false` → `allow delete: if request.auth != null && request.auth.uid == userId`
  for `/users/{userId}/challenges/{challengeId}`. Allows client to delete own challenge docs.
- `FirestoreService.kt`: New `deleteChallenge(userId, challengeId)` method (`.delete().await()`).
- `ChallengeRepositoryImpl.kt`: `updateChallengeStatus` — when status is FAILED, delete the
  Firestore document instead of calling `updateChallengeStatus` (which is blocked by rules).
  Room row kept as "failed" so History still shows the challenge.
- `SyncRepositoryImpl.kt`: Guard in step 1 — skip inserting a Firestore "active" challenge if
  Room already has that id with a non-active status (safety net while async delete propagates).

**Bug 2 — Website/App icon and domains reset after Recents**

Root cause: `UsageTrackingService.onCreate()` subscribes to `getActiveChallenges()`. The
first emission may be empty (race condition — Room not yet populated when the service starts,
or service restarted by Huawei while sync is in flight). This clears `TrackedAppEventBus`
→ `AppDetectionAccessibilityService` sees no packages/domains → apps are unblocked.

Fix:
- `UsageTrackingService.kt`: Added race condition guard at the top of the collect block.
  If the derived lists (packages + domains) are ALL empty AND the bus currently has non-empty
  data, the update is skipped with `Timber.w`. The bus retains the previously loaded state
  until the next legitimate emission (non-empty or genuine all-deleted).

**Bug 3 — PERMISSION_DENIED for dailyLogs**

Root cause: Firestore rules for `/users/{userId}/challenges/{challengeId}` do NOT
automatically cover sub-collections. The nested `dailyLogs` sub-collection at
`/users/{userId}/challenges/{challengeId}/dailyLogs/{logId}` had no explicit rule →
`PERMISSION_DENIED` on any read or write to that path.

Fix:
- `firestore.rules`: Added `match /dailyLogs/{logId}` nested inside the `challenges` match
  block with `allow read, write: if request.auth != null && request.auth.uid == userId`.

**Files changed:** `firestore.rules`, `FirestoreService.kt`, `ChallengeRepositoryImpl.kt`, `SyncRepositoryImpl.kt`, `UsageTrackingService.kt`
**Requires deploy:** `firebase deploy --only firestore:rules`
**No Cloud Function changes. No Room schema changes.**

---

## [Unreleased] — May 2026

### Added
- **Adult Content Blocking — 133,713-domain blocklist + auto-update (Stufe 1):**
  Expanded adult domain coverage from ~60 hardcoded entries to 133,713 unique domains.
  Added monthly auto-update mechanism via WorkManager. Zero impact on blocking logic or VPN.

  **Step 1 — Domain list:**
  - New script `scripts/update_adult_domains.py` downloads and merges:
    OISD Small (AdBlock Plus format, 57k domains) + StevenBlack porn-only (hosts format, 77k domains).
    Deduplicates, validates, writes one domain per line to `assets/adult_domains.txt`.
    Run `python3 scripts/update_adult_domains.py` from project root to regenerate.
  - Result: 133,713 unique domains in `assets/adult_domains.txt` (up from ~60).

  **Step 2 — Optimised service loading:**
  - `AdultDomains.kt` rewritten from hardcoded `Set<String>` to a dynamic singleton.
    - `loadDomains(context)`: reads `filesDir/adult_domains_updated.txt` if present (worker output),
      falls back to `assets/adult_domains.txt`. Stores result in a `HashSet<String>`.
    - `isBlocked(url)`: O(1) host-based lookup — extracts host, then strips subdomains one label
      at a time. `"www.pornhub.com"` → checks `"www.pornhub.com"`, then `"pornhub.com"`.
    - `isDomainBlocked(domain)`: same logic for a bare domain string (debug panel test).
    - Exposes `domainsCount: Int` and `domainSource: String` for the debug panel.
  - `AppDetectionAccessibilityService` gains `override fun onCreate()` that calls
    `AdultDomains.loadDomains(this)`.
    Timber.d log: `"Adult domains loaded: 133713 (source: bundled)"`.
  - O(n) `firstOrNull { url.contains(domain) }` loop replaced with single `AdultDomains.isBlocked(url)` call.

  **Step 3 — Auto-update worker:**
  - New `AdultDomainsUpdateWorker` (`@HiltWorker`, `CoroutineWorker`):
    Downloads OISD Small (`https://small.oisd.nl/`), parses AdBlock Plus format,
    saves result to `context.filesDir/adult_domains_updated.txt`, then calls
    `AdultDomains.loadDomains(context)` so the running service reloads immediately.
    Returns `Result.retry()` if fewer than 10,000 domains parsed.
  - Scheduled in `DetoxApplication.onCreate()` as `enqueueUniquePeriodicWork` with
    interval 30 days, `ExistingPeriodicWorkPolicy.KEEP`, requires network.
    Work name: `"adult_domains_update"`.

  **Step 4 — Debug Panel (Section 10: ADULT DOMAIN STATS):**
  - "Domains loaded: X" + "Source: bundled / updated (DD. MMM YYYY)" info card.
  - "Force update now" button — enqueues `AdultDomainsUpdateWorker` as one-time work.
  - "Test domain" input + Test button → shows 🔴 BLOCKED or 🟢 ALLOWED.
  - New `ProfileViewModel` methods: `debugGetAdultDomainStats()`, `debugTriggerAdultDomainsUpdate()`,
    `debugTestAdultDomain()`.

**Files changed:** `scripts/update_adult_domains.py` (new), `AdultDomains.kt`,
`AppDetectionAccessibilityService.kt`, `AdultDomainsUpdateWorker.kt` (new),
`DetoxApplication.kt`, `ProfileViewModel.kt`, `ProfileScreen.kt`
**No Cloud Function changes. No Room schema changes. No Firestore changes. No blocking logic changes.**

---

## [Unreleased] — May 2026

### Changed
- **App & Website Selection Step — extracted shared composable (refactor):**
  The App/Website selection UI (formerly duplicated between Solo Wizard Step 2 and Group Wizard Step 1)
  is now a single shared composable in `presentation/components/AppWebsiteSelectionStep.kt`.
  Both wizards now render the exact same iOS-style UI with no duplication.
  - New file: `AppWebsiteSelectionStep.kt` — contains `AppWebsiteSelectionStep` + helpers
    (`AppsTabContent`, `WebsitesTabContent`, `AppSelectionRow`, `PartialSectionSubRow`,
    `DomainSuggestionsSection`, `PlatformAppIconWithBadge`, `DOMAIN_TO_PACKAGE`, `DOMAIN_BRAND_COLOR`).
  - `ChallengeCreationScreen.kt`: removed ~500 lines of now-duplicate local composables
    (`Step2AppOrWebsite`, `AppsTabContent`, `WebsitesTabContent`, `AppListRow`, etc.);
    Step 2 now calls the shared `AppWebsiteSelectionStep`.
  - `GroupChallengeCreateScreen.kt`: removed ~250 lines of local composables
    (`Step1AppSelection`, `GroupAppListRow`, `GroupDomainSuggestionsSection`);
    Step 1 now calls the shared `AppWebsiteSelectionStep`.
  - `GroupChallengeCreateViewModel.kt`: `GroupCreateFormState` gains 5 new fields:
    `activeTab`, `manualDomainError`, `blockAdultContent`, `partialBlockDomains`, `partialBlockSections`.
    New ViewModel methods: `updateActiveTab`, `updateBlockAdultContent`,
    `togglePartialBlockDomain`, `togglePartialSection`.
    `canGoNext()` and `validateCurrentStep()` for Step 1 updated to be tab-aware
    (matching Solo wizard logic). `computeBlockedDomains()` includes `partialBlockDomains`.

**Files changed:** `AppWebsiteSelectionStep.kt` (new), `ChallengeCreationScreen.kt`, `GroupChallengeCreateScreen.kt`, `GroupChallengeCreateViewModel.kt`
**No Cloud Function changes. No Room schema changes. No business logic changes. No Firestore structure changes.**

---

## [Unreleased] — May 2026

### Fixed
- **Website Challenge — icon and name display (Dashboard Card & Detail Screen):**
  Website challenges now show real favicons and meaningful names instead of a generic "W" placeholder and "Website".
  - New `FaviconImage` composable: loads favicon via `https://www.google.com/s2/favicons?domain={domain}&sz=64` (Coil `SubcomposeAsyncImage`). Shape: 10dp rounded corners. Fallback: grey circle (`#AEAEB2`) with first letter of domain in white, 14sp bold.
  - `websiteDisplayName()` helper (priority: features first, then full domains):
    - 1 item → item name (e.g. "Instagram Reels", "instagram.com")
    - 2 items → "A & B"
    - 3+ items → "A +X weitere"
  - `websitePrimaryDomain()` helper: extracts base domain from first feature path or first blocked domain.
  - Dashboard Card: website challenges show favicon icon stack (same overlap logic as multi-app) + computed name. Both `AppIconStack` and `AppNameLabel` updated with optional website parameters.
  - Detail Screen Card 1: title computed from `websiteDisplayName()` when `blockingType == WEBSITE`.
  - Detail Screen: new "BLOCKIERTE WEBSITES" section (same style as "BLOCKIERTE APPS") showing favicon + name (15sp 600) + URL path subtitle (12sp #8E8E93) for each feature; pure domains shown without path subtitle.
  - `DailyStats` gains `partialBlockDomains: List<String>` field; `GetDailyStatsUseCase` populates it.
  - 2 new German strings: `detail_blocked_websites_section`, `website_name_more`.

**Files changed:** `DailyStats.kt`, `GetDailyStatsUseCase.kt`, `ChallengeCard.kt`, `ActiveChallengeScreen.kt`, `strings.xml`
**No Cloud Function changes. No Room schema changes. No Firestore changes. No blocking logic changes.**

---

## [Unreleased] — May 2026

### Changed
- **App & Website Selection Screen — iOS-style redesign (visual only):**
  Redesigned Step 2 of the Solo Challenge Wizard and Step 1 of the Group Challenge Wizard
  to match an iOS Screen Time–inspired aesthetic. Zero logic changes.
  - Tab bar: replaced Material3 `TabRow` with custom pill-shaped switcher
    (`#F2F2F7` container, white active pill with shadow, `#8E8E93` inactive text).
  - Search field: replaced `OutlinedTextField` with pill-shaped `BasicTextField`
    (`#F2F2F7` bg, no border, `#8E8E93` search icon).
  - App rows: removed all `HorizontalDivider` separators; app icons get 12dp rounded
    corners (iOS style); usage stats subtitle removed; selected state shows `#F9FFF9`
    row background + `#00C853` 24dp checkmark; unselected has no indicator.
  - Websites tab — URL input: pill-shaped input + `#00C853` "Add" button, 50dp radius.
  - Websites tab — chips: custom `#F2F2F7` rounded chips (8dp) with `#8E8E93` ✕ button.
  - Websites tab — feature cards: white `Card` with 14dp radius + 0.5px border,
    real platform app icon via `PackageManager` (40dp, 10dp radius) with 8dp `#FF3B30`
    badge bottom-right; fallback colored circle + domain initial if app not installed.
    `DOMAIN_TO_PACKAGE` + `DOMAIN_BRAND_COLOR` maps + `PlatformAppIconWithBadge` composable added.
  - Websites tab — adult content card: `#FFF5F5` bg, `#FFD0D0` border,
    40dp `#FF3B30` circle with "18+" (12sp bold white) replacing emoji,
    red `Switch` (`#FF3B30`) when on.
  - Group wizard Step 1 domain input: `OutlinedTextField` → pill `BasicTextField`
    (`#F2F2F7` bg, 50dp radius); Add button → `#00C853` pill; added domain display →
    `FlowRow` chips matching Solo wizard style.
  - 5 new German strings added to `strings.xml`.

**Files changed:** `ChallengeCreationScreen.kt`, `GroupChallengeCreateScreen.kt`, `strings.xml`
**No Cloud Function changes. No Room schema changes. No business logic changes.**

---

## [Unreleased] — May 2026

### Fixed
- **Multi-app display in Dashboard Card, Detail Screen & Overlay:**
  When a challenge tracks multiple apps, all apps are now displayed properly.
  - Dashboard Card: 1 app → single 32dp icon + name; 2-3 apps → overlapping 28dp icons with white border + comma-separated names; 4+ apps → 3 icons + "+X" grey overflow circle + "X Apps" label.
  - Detail Screen: new "BLOCKIERTE APPS" section card between progress card and info list. Shows each app with 40dp circular icon, app name (15sp, 600), and package name (12sp, #8E8E93). Dividers between rows, none after last.
  - Overlay: 2 apps → "App1, App2"; 3+ apps → "X Apps" display name.
  - `AppIconImage` fallback changed from Android icon to grey circle with first letter of app name.
  - `DailyStats` model gains `appPackageNames: List<String>` field, populated by `GetDailyStatsUseCase`.
  **Files changed:** `DailyStats.kt`, `GetDailyStatsUseCase.kt`, `ChallengeCard.kt`, `ActiveChallengeScreen.kt`, `OverlayManager.kt`, `strings.xml`
  **No Room schema changes. No Firestore changes. No Cloud Function changes.**

---

### Added
- **Solo Challenge — Reduce limit during active challenge (next day):**
  Users can permanently reduce their daily limit (opens / minutes / budget) while a Solo Challenge is active. The change takes effect at the next midnight and can never be increased again.
  - 2 new nullable DB columns on `challenges`: `pending_limit_value`, `pending_limit_applies_at`. Room migration 23→24.
  - `ChallengeDao.updatePendingLimit()` + `applyPendingLimit()` (COALESCE-based targeted UPDATE — never touches FK-cascade-sensitive INSERT).
  - `ChallengeRepository.updatePendingLimit()` writes Firestore first (source of truth), then Room.
  - `FirestoreService.updateChallengePendingLimit()` uses `SetOptions.merge()`.
  - `DailyEvaluationWorker`: applies pending reduction at midnight, discards silently if would not reduce. Firestore write before Room write.
  - `ActiveChallengeViewModel`: `ReduceLimitState` sealed interface + `reducePendingLimit()` + `resetReduceLimitState()`.
  - UI (`ActiveChallengeScreen`): "LIMIT ANPASSEN" section visible for Solo ACTIVE challenges (not TIME_WINDOW, not group shadow, currentLimitValue > 1, no pending yet). `ModalBottomSheet` with `DetoxHorizontalPicker` (range 1..currentLimit-1) + warning text + confirm `AlertDialog`. Pending indicator (orange text) shown when `pendingLimitValue != null`.
  - `DateUtils.nextMidnightTimestamp()` added.
  - 14 new German strings in `strings.xml`.
  - Group Challenges: NEVER show this option.

- **Group Challenge — Leave (participant) and Delete (creator) for WAITING status:**
  Regular participants can leave a WAITING challenge → 100% Stripe refund.
  Creator can delete a WAITING challenge → ALL participants get 100% refund.
  Neither action is available once status=ACTIVE.
  - New CF `leaveGroupChallenge`: verifies WAITING + non-creator, cancels PI, removes from participants array, auto-cancels challenge if < 2 remain.
  - New CF `deleteGroupChallenge`: verifies WAITING + creator, cancels ALL PIs idempotently, sets status=cancelled.
  - UI: "Challenge verlassen" text link (14sp, #FF3B30) below participant list — WAITING + non-creator only.
  - UI: "Challenge löschen" text link (14sp, #FF3B30) below Start button in header card — WAITING + creator only.
  - Confirmation dialogs for both actions with amount/participant count.
  - Local WorkManager notifications after each action via NotificationHelper.
  - LeaveState + DeleteState added to GroupChallengeDetailViewModel.
  - Critical rule: Stripe cancel ALWAYS before Firestore update.

**Files changed:** `functions/src/index.ts`, `CloudFunctionsService.kt`, `GroupChallengeDetailViewModel.kt`, `GroupChallengeDetailScreen.kt`, `NotificationHelper.kt`, `strings.xml`
**Requires deploy:** `firebase deploy --only functions:leaveGroupChallenge,functions:deleteGroupChallenge`

---

## [Unreleased] — May 2026

### Fixed
- **Group Challenge Wizard — UI alignment with Solo Wizard:**
  Audited and fixed all Group Challenge Wizard steps to match
  Solo Wizard design exactly:
  - Limit Type screen: Next button now grey/disabled when nothing
    selected, green/enabled only after selection
  - Limit Type cards: fixed text overflow, all cards same height
  - Set Your Limit screen: replaced — / + button input with
    DetoxHorizontalPicker (isDark=false) to match Solo
  - All steps: background, card style, typography, spacing,
    padding aligned to docs/08_ui_design_system.md
  - Next button: 54dp height, 14dp radius, full width on all steps
  - Input components consistent throughout — no mixed styles

---

## [Unreleased] — May 2026

### FIXED — Group Challenge Wizard: full visual alignment to Solo Wizard design

**Root causes (multiple):**
- `limitType` in `GroupCreateFormState` was non-nullable with default `LimitType.TIME` →
  Step 2 always had a card pre-selected and Next button always enabled, violating the
  "grey/disabled until selection made" requirement.
- `GroupLimitTypeCard` used smaller padding (12dp vs 20dp), smaller emoji (20sp vs 24sp),
  smaller description text (`bodySmall` vs `bodyMedium`), and tighter column spacing (2dp vs 4dp)
  — all diverging from `LimitTypeCard` in the Solo Wizard.
- `Step3LimitAndDuration` SESSIONS path used `StepperField` (— / + buttons) for session
  duration instead of `DetoxHorizontalPicker`, and range was capped at min=5 instead of min=1.
- All step screen titles used `titleMedium` instead of `titleLarge` (Steps 1–6).
- Step 1 search field missing `shape = RoundedCornerShape(12.dp)`.
- `WizardHeader` progress bar was 4dp height instead of 6dp.

**Fixes (`GroupChallengeCreateViewModel.kt`):**
- `limitType: LimitType = LimitType.TIME` → `limitType: LimitType? = null`
- `canGoNext()` step 2: `true` → `s.limitType != null`
- `validateCurrentStep()`: added step 2 null guard
- `setSessionMinutes` coerceIn: `(5, 120)` → `(1, 120)` to match Solo's (1..120) range
- `createChallenge()` and `onPaymentSuccess()`: `limitType = s.limitType ?: LimitType.TIME` for safe nullable handling

**Fixes (`GroupChallengeCreateScreen.kt`):**
- `WizardHeader` progress bar: `height(4.dp)` → `height(6.dp)` + explicit primary color
- Step 1 title: `titleMedium` → `titleLarge`
- Step 1 search field: added `shape = RoundedCornerShape(12.dp)`
- `Step2LimitType` param: `selected: LimitType` → `selected: LimitType?`
- `Step2LimitType` title: `titleMedium` → `titleLarge`
- `GroupLimitTypeCard` inner padding: `12dp` → `20dp`
- `GroupLimitTypeCard` emoji: `20sp` → `24sp`
- `GroupLimitTypeCard` title: `bodyMedium` → `bodyLarge` (matching Solo's `bodyLarge`)
- `GroupLimitTypeCard` description: `bodySmall` → `bodyMedium`
- `GroupLimitTypeCard` column spacing: `2dp` → `4dp`
- `GroupLimitTypeCard` description: added `maxLines=2, overflow=Ellipsis`
- Step 3 SESSIONS: replaced `StepperField` with `DetoxHorizontalPicker(values=(1..120))` for session duration
- Step 3 title: `titleMedium` → `titleLarge`
- Step 4 title: `titleMedium` → `titleLarge`
- Step 5 title: `titleMedium` → `titleLarge`
- Step 6 title: `titleMedium` → `titleLarge`
- Removed `StepperField` import (no longer used)

**Files changed:** `GroupChallengeCreateViewModel.kt`, `GroupChallengeCreateScreen.kt`
**No Cloud Function changes. No Room schema changes. No Firestore changes. No Stripe changes.**

---

## [Unreleased] — May 2026

### Added
- **Group Challenge — 5-day Authorization Window:** PaymentIntents
  for Group Challenges now use capture_method: "manual" — money is
  reserved but NOT charged until the creator taps Start.
  Hard Mode is completely unaffected (immediate capture unchanged).
- **Group Challenge — Auto-Cancel after 5 days:** New
  expireGroupChallenge Cloud Function + DailyEvaluationWorker check.
  If challenge is not started within 5 days of creation:
  all Stripe PaymentIntents cancelled → 100% refund → all participants
  notified via local notification. authorizationExpiresAt field added
  to groupChallenges Firestore collection + Room (Migration 22→23,
  DEFAULT 0 with expiresAt <= 0L guard for existing challenges).
- **Group Challenge — Pre-flight Check before Start:** startGroupChallenge
  CF verifies ALL participant PaymentIntents show requires_capture
  before capturing any. If any fails → abort immediately, no partial
  captures. UI shows clear error dialog if payment_not_ready.
- **Group Challenge — Day 4 Warning Notification:** Creator receives
  local notification one day before authorization expires:
  "Starte deine Challenge oder alle bekommen ihr Geld zurück."
- **Group Challenge — Countdown UI in WAITING state:** Detail Screen
  shows days remaining until authorization expires with color coding:
  grey (> 1 day), orange (≤ 1 day), red (expired).
- **TypeScript — Participant interface:** Added explicit Participant
  interface in index.ts with optional status, displayName, amountCents,
  payoutStatus fields to fix TS2353 compile errors.

### Changed
- **Group Challenge Join:** isGroupChallenge: true flag now passed
  to createPaymentIntent from both joinGroupChallenge and
  confirmGroupJoin CFs to enforce manual capture for all participants.

---

## [Unreleased] — May 2026

### Security
- **Firestore Rules — full rewrite:** Replaced critically under-secured
  rules with granular field-level protection. Key fixes:
  - /users/{userId} — blocks client writes to stripeConnectedAccountId,
    stripeCustomerId via diff().affectedKeys()
  - /users/{userId}/challenges — blocks client writes to status,
    payoutStatus, payoutAmount, appFeeAmount, stripePaymentIntentId,
    finalPayout, endDate, startDate, amountCents, paymentIntentId
  - /users/{userId}/pendingPayouts + /paymentCaptures — write: false,
    Cloud Function Admin SDK only
  - /groupChallenges — blocks client writes to status, startDate,
    endDate, completedAt, prizePool, appFee, prizePerWinner, nobodyFailed
  - /payoutRequests — user can create own only, no updates allowed
  - /admin — permanently blocked for all clients
  - /dailyLogs — remains fully writable (required for 10s sync)
- **Cloud Functions — 4 auth vulnerabilities patched:**
  - cancelOrRefundPayment: requireAuth result was discarded, used
    req.body.userId for Firestore writes → attacker could write
    payoutStatus: "refunded" to any user. Fixed: use verifiedUserId
  - createConnectedAccount: same issue → attacker could overwrite any
    user's stripeConnectedAccountId. Fixed: use verifiedUserId
  - failParticipant: any authenticated user could fail any other
    participant. Fixed: added 403 guard if verifiedUserId !== failedUserId
  - startGroupChallenge: any authenticated user could start any
    challenge. Fixed: added 403 guard if creatorUserId !== verifiedUserId
- **firebase-functions updated to latest version**
- **Deployed to production:** firestore:rules + functions

---

## [Unreleased] — May 2026

### SECURITY — Firestore Rules hardened + Cloud Function auth vulnerabilities fixed

**`firestore.rules` — full rewrite:**
- Replaced broad `match /users/{userId}/{document=**} { allow write }` wildcard with granular sub-collection rules:
  - `/users/{userId}` — update blocks `stripeConnectedAccountId` and `stripeCustomerId` (CF-only)
  - `/users/{userId}/challenges/{challengeId}` — create allowed; update blocks `status`, `payoutStatus`, `payoutAmount`, `appFeeAmount`, `stripePaymentIntentId`, `stripeCustomerId`, `finalPayout`, `endDate`, `startDate`, `amountCents`, `paymentIntentId`
  - `/users/{userId}/dailyLogs/{logId}` — full read/write (app writes every 10 s)
  - `/users/{userId}/pendingPayouts/{payoutId}` — read-only; CF writes only
  - `/users/{userId}/paymentCaptures/{captureId}` — read-only; CF writes only
- `groupChallenges/{groupId}` — update now blocks `status`, `startDate`, `endDate`, `completedAt`, `prizePool`, `appFee`, `prizePerWinner`, `nobodyFailed`; participants array (opensToday/timeUsedMinutes) still writable
- Added `groupChallenges/{groupId}/taunts/{tauntId}` rule — create allowed, no edit/delete
- Added `payoutRequests/{requestId}` rule — user can create own (no `status`/`paidAt`), no updates
- Added `admin/{document=**}` rule — permanently blocked from client
- **DECISION:** Use `diff().affectedKeys()` for update checks (not `keys().hasAny()`) — the latter would block all updates once a protected field exists in the doc.

**`functions/src/index.ts` — 4 auth vulnerabilities fixed:**
- `cancelOrRefundPayment`: was discarding `requireAuth` result; now captures `verifiedUserId` and uses it for the Firestore write (was using `req.body.userId` → attacker could write payout status to any user's challenge)
- `createConnectedAccount`: same bug — now uses `verifiedUserId` for all Firestore writes; removed `userId` from req.body destructuring entirely
- `failParticipant`: now captures `verifiedUserId`; added `if (verifiedUserId !== failedUserId) throw 403` — users can only fail themselves
- `startGroupChallenge`: now captures `verifiedUserId`; added `if (gc["creatorUserId"] !== verifiedUserId) throw 403` — only the creator can start

**Files changed:** `firestore.rules`, `functions/src/index.ts`
**Requires deploy:**
```
firebase deploy --only firestore:rules
firebase deploy --only functions
```
**No Android Kotlin changes. No Room schema changes.**

---

### FIXED — Group Challenge win popup fires on every Detail Screen open

**Root cause (`GroupChallengeDetailViewModel`):**
`lastSyncedStatus` and `winDialogShown` are in-memory fields. Every time the Detail Screen is
opened a new ViewModel instance is created, both reset to their initial values, and the Firestore
snapshot fires immediately with `COMPLETED` status → `syncToLocalTracking` runs again →
`finishLocalGroupChallenge` is called every time and the win popup is shown every time.

**Fixes:**
- `winDialogShown` in-memory flag replaced with a persistent SharedPreferences guard:
  key `"win_popup_shown_$groupId"` in file `"detox_win_popup"`.
  Popup is shown at most once per challenge, survives screen close and process death.
  Set to `true` immediately before `triggerWinDialog` is called.
- `finishLocalGroupChallenge` guarded by a Room status check via new
  `GroupChallengeRepository.isLocalChallengeCompleted(groupId)`:
  skipped if local challenge is already `"completed"` or `"failed"`.
- Same Room guard applied to CANCELLED path (idempotent).
- New `isLocalChallengeCompleted` method added to `GroupChallengeRepository` interface and
  implemented in `GroupChallengeRepositoryImpl` (reads `challengeDao.getChallengeById("group_$groupId")?.status`).

**Files changed:** `GroupChallengeDetailViewModel.kt`, `GroupChallengeRepository.kt`, `GroupChallengeRepositoryImpl.kt`
**No Cloud Function changes. No Room schema changes. No UI layout changes.**

---

### FIXED — Hard Mode Duration Bug

**Root cause:**
endDate was calculated using wrong multiplier (minutes instead of days) in the ViewModel/Repository
layer before being passed to `CreateChallengeUseCase`. 14 days was saved as 14 minutes
(840000ms instead of 1209600000ms).

**Fix:**
- Replaced wrong multiplier with `durationDays.toLong() * DateUtils.MILLIS_PER_DAY`.
- Added `MILLIS_PER_DAY = 24L * 60 * 60 * 1000` constant to `DateUtils`.
- Fix verified for Hard Mode, Soft Mode, and Group Challenge creation.

---

## 2026-05-17

### FIXED — debug_use_minutes_as_days persists across clean builds → auto-reset on cold start

**Root cause (this session):**
The `debug_use_minutes_as_days` SharedPref was `true` from a prior debug session. A clean build does NOT wipe
SharedPreferences, so the flag survived all 3 builds. The endDate calculation in `CreateChallengeUseCase` is
correct (uses `DateUtils.MILLIS_PER_DAY` when the flag is off), but with the flag on, `durationMultiplier=60_000L`
→ `14 * 60_000 = 840_000ms = 14 min`. The Logcat ordering ("Challenge created:" before "Creating challenge:")
was misinterpreted as external calculation — both logs are inside `CreateChallengeUseCase.execute()` at lines
101 and 132 respectively.

**Fix:**
- `DetoxApplication.onCreate()`: inside the existing `if (BuildConfig.DEBUG)` block, auto-reset
  `debug_use_minutes_as_days` to `false` on every cold start.
  `Timber.d("DetoxApplication: debug_use_minutes_as_days reset to false on cold start")` is logged.
  The user must explicitly re-enable the flag each session via Debug Panel → Section 3 if needed for testing.

**DECISION:** `debug_use_minutes_as_days` resets to `false` on every cold start in DEBUG builds.
It is session-scoped — not persistent. No other files changed.

**Files changed:** `DetoxApplication.kt`
**No Cloud Function changes. No Room schema changes. No UseCase changes.**

---

### FIXED — Remaining inline 86_400_000L literals + debug flag still active + stale challenge repair

**Root cause (this session):**
The previous session fixed `CreateChallengeUseCase`, but the `debug_use_minutes_as_days` SharedPref was
still `true` from a prior test session. The debug toggle in the debug panel (Section 3 "Duration Mode: MINUTES ✓")
must be turned OFF before creating real challenges. A clean Build does NOT wipe SharedPreferences.
Additionally, five files still used inline `86_400_000L` literals instead of `DateUtils.MILLIS_PER_DAY`.

**Fixes:**
- `CreateChallengeUseCase`: Timber log now shows `diff=Xms = Ydays (Zmin)` — both ms AND days AND minutes.
  When debug flag is on, log will show e.g. `diff=840000ms = 0days (14min)`, making the issue unmistakable.
- `HistoryViewModel`: `originalDays` and Redemption `endDate` calc → `DateUtils.MILLIS_PER_DAY` (was `86_400_000L`).
  Also `sortDate` bucketing for history list.
- `ActiveChallengeScreen`: legacy endDate fallback and `daysLeft` calc → `DateUtils.MILLIS_PER_DAY`.
- `DashboardScreen`: `HardModeSuccessOverlay` endDate fallback and durationDays calc → `DateUtils.MILLIS_PER_DAY`.
  Redemption banner `daysLeft` calc → `DateUtils.MILLIS_PER_DAY`.
- `ProfileViewModel`: two `durationDays` display calcs → `DateUtils.MILLIS_PER_DAY`.
- `ProfileScreen` Debug Panel: added "Fix stale challenges (endDate < 1 day)" button in Section 3.
  Tap to auto-repair any active challenge whose `endDate - startDate < MILLIS_PER_DAY`.
  Reverses the `60_000L` debug multiplier: `originalDays = diff / 60_000`, then
  `newEnd = startDate + originalDays * MILLIS_PER_DAY`. Updates Room + Firestore.

**`debug_use_minutes_as_days` reminder:** Toggle is in Debug Panel → Section 3 "CHALLENGE TIME MANIPULATION".
When ON it shows "Duration Mode: MINUTES ✓". MUST be OFF for production testing.

**Files changed:** `CreateChallengeUseCase.kt`, `HistoryViewModel.kt`, `ActiveChallengeScreen.kt`,
`DashboardScreen.kt`, `ProfileViewModel.kt`, `ProfileScreen.kt`
**No Cloud Function changes. No Room schema changes.**

---

### FIXED — Hard Mode (and all challenge types) endDate calculated as minutes instead of days (critical)

**Root cause (`CreateChallengeUseCase`, `CreateGroupChallengeUseCase`, `GroupChallengeDetailViewModel`):**
Inline magic-number multipliers (`24 * 60 * 60 * 1000L`) were used instead of `DateUtils.MILLIS_PER_DAY`
in every endDate creation path. In DEBUG builds, `CreateChallengeUseCase` also reads a
`debug_use_minutes_as_days` SharedPref; if left `true` after testing, `durationMultiplier = 60_000L`
→ 14 days stored as 14 minutes (840 000 ms). Logcat proof:
`startDate=1779033208012 endDate=1779034048012 diff=840000ms = 0 days`

**Fixes:**
- `CreateChallengeUseCase`: both `86_400_000L` literals in the `durationMultiplier` block replaced
  with `DateUtils.MILLIS_PER_DAY`. DEBUG `60_000L` path unchanged (intentional fast-test shortcut).
  Added verification Timber log immediately after `val endDate`:
  `Challenge created: durationDays=N startDate=X endDate=Y diff=Zms = N days`
- `CreateGroupChallengeUseCase`: `durationDays.toLong() * 24 * 60 * 60 * 1000L`
  → `durationDays.toLong() * DateUtils.MILLIS_PER_DAY`
- `GroupChallengeDetailViewModel`: local endDate for group-start optimistic update
  `currentGc.durationDays * 24L * 60 * 60 * 1000` → `currentGc.durationDays.toLong() * DateUtils.MILLIS_PER_DAY`
- `GroupChallengeFirestoreService`: Firestore read fallback
  `startDate + 7L * 24 * 60 * 60 * 1000` → `startDate + 7L * DateUtils.MILLIS_PER_DAY`

**Inline constant cleanup (MILLIS_PER_DAY rule):**
- `GetDailyStatsUseCase`: `* 24L * 60L * 60L * 1000L` and `/ 86_400_000L` → `DateUtils.MILLIS_PER_DAY`
- `GetChallengeStreakUseCase`: private `DAY_MS = 24L * 60 * 60 * 1000` companion removed,
  usages replaced with `DateUtils.MILLIS_PER_DAY`
- `OverlayManager`: `/ 86_400_000L` → `DateUtils.MILLIS_PER_DAY`

**Files changed:** `CreateChallengeUseCase.kt`, `CreateGroupChallengeUseCase.kt`,
`GroupChallengeDetailViewModel.kt`, `GroupChallengeFirestoreService.kt`,
`GetDailyStatsUseCase.kt`, `GetChallengeStreakUseCase.kt`, `OverlayManager.kt`
**No Cloud Function changes. No Room schema changes. No UI layout changes.**
**Redemption Challenge** goes through `CreateChallengeUseCase` (HistoryViewModel calls it) — covered by fix #1.

---

## [Unreleased] — May 2026

### Fixed
- **nobodyFailed Bug (Group Challenge):** Fixed payout logic —
  when all participants win, 100% is refunded correctly
  (App fee: €0.00) instead of incorrectly applying 80% refund
- **Group Challenge Duration:** Audited startDate → endDate calculation —
  challenge runs exactly X days from the moment creator taps Start.
  Replaced inline 86400000L with MILLIS_PER_DAY constant in DateUtils

### Added
- **Payout UI — Expected Date:** Payout cards now show
  "Erwartet bis: DD. MMM YYYY" (endDate + 5 business days, 
  Saturday/Sunday skipped). Added DateUtils.addBusinessDays()
- **Payout Notification:** DailyEvaluationWorker sends local
  notification when challenge completes — 
  "💸 Deine Auszahlung ist unterwegs — €X bis DD. MMM YYYY"
- **Balance Card (ProfileScreen):** Shows pending prize amounts
  with manual "Auszahlen" button (never automatic). IBAN prompt
  only shown if no IBAN stored yet
- **Settings → Auszahlungskonto:** IBAN + account holder name
  now managed in Settings instead of directly in ProfileScreen
- **Settings → Verlauf:** Verlauf Screen now accessible via
  Settings under new "Aktivität" section
- **Verlauf Screen — Filter Tabs:** "Alle / Gewonnen / Ausgeschieden /
  Abgebrochen" — in-memory filtering, no new Firestore queries
- **Detail Screen — Abrechnung Section:** Payout breakdown added
  at the bottom of Group Challenge + Hard Mode Detail Screens
  (completed challenges only, never Soft Mode)

### Changed
- **ProfileScreen:** Verlauf section removed completely —
  only Avatar, Stats, and Balance Card remain
- **ProfileScreen:** Standalone "Auszahlungen" section removed —
  payout details now live in Detail Screen only
- **IBAN Flow:** IBAN no longer shown permanently in ProfileScreen —
  managed exclusively in Settings → Auszahlungskonto
- **Payout:** Never automatic — user must actively confirm "Auszahlen".
  Added Firestore status "requested"
- **payoutRequests Collection:** New entry created on payout request
  for manual admin processing

---

## 2026-05-17

### FIXED — Group Challenge endDate never written on challenge start (critical)

**Root cause (`startGroupChallenge` CF, `functions/src/index.ts`):**
`startGroupChallenge` wrote `{ status: "active", startDate: Date.now() }` but never
calculated or stored `endDate`. Every group challenge therefore had `endDate = 0` in
Firestore. `DailyEvaluationWorker.evaluateGroupChallenge` guards with
`endDate > 0L && now >= endDate` — this condition was permanently `false`, so
`completeGroupChallenge` was never auto-triggered.

**Fixes:**
- `startGroupChallenge` now reads `durationDays` from the Firestore doc, computes
  `endDate = startDate + durationDays * MILLIS_PER_DAY`, and writes both fields atomically.
- Added `functions.logger.info` at the startDate/endDate calculation point.
- Added `const MILLIS_PER_DAY = 86_400_000` in `index.ts` — no inline magic numbers.

**Android cleanup (no logic change):**
- Added `DateUtils.MILLIS_PER_DAY = 86_400_000L` constant.
- Replaced all 7 inline `86_400_000L` occurrences in `DailyEvaluationWorker.kt`
  (including the `24L * 3_600_000L` form in `setRedemptionInfo`) with
  `DateUtils.MILLIS_PER_DAY`.

**Files changed:** `functions/src/index.ts`, `DateUtils.kt`, `DailyEvaluationWorker.kt`
**Requires deploy:** `firebase deploy --only functions:startGroupChallenge`
**Existing active challenges with `endDate == 0`:** Must be patched manually in Firestore
  — set `endDate = startDate + (durationDays * 86400000)` on each affected document.

---

### FEATURE — Payout Card: expected refund date

ProfileScreen payout cards now show the expected or confirmed refund date below the status line.

**Logic:**
- `DateUtils.addBusinessDays(timestampMs, days)` added — skips Saturday + Sunday, no library needed.
- `endDateMs: Long` added to `PayoutChallengeInfo` (populated from `entity.endDate` in all three payout paths).
- Expected date = `endDate + 5 business days`, formatted as `d. MMM yyyy` with German locale.

**Display:**
- `payoutStatus == "refunded"` → "Gutschrift: DD. MMM YYYY" (black, #000000)
- `payoutStatus == "pending_payout"` → "Erwartet bis: DD. MMM YYYY" (gray, #8E8E93)
- `payoutStatus == "pending_payout" + prizeShareCents > 0` → IBAN CTA kept + note "Gewinnanteil wird nach IBAN-Hinterlegung überwiesen" below button

**Files changed:** `DateUtils.kt`, `ProfileViewModel.kt`, `ProfileScreen.kt`, `strings.xml`
**No Cloud Function or Stripe changes.**

---

### FIXED — Group Challenge nobodyFailed: wrong fee applied when nobody failed

**Root causes (3 bugs in `completeGroupChallenge` CF, `functions/src/index.ts`):**

1. `successParticipants` (old line 512) only filtered `status === "active"`, excluding winners with
   `status === "completed"` (already marked on a prior CF run). This caused `perWinnerBonus` to be
   inflated (denominator too small) in the someone-failed path.

2. `nobodyFailed` check was `failedParticipants.length === 0`. Per docs/09_payout_and_fees.md the
   correct check is `participants.every(p => active || completed)`. The old check returned `true` for
   any participant with status `"success"` (written by the someone-failed path on a previous partial
   run), potentially routing into the nobody-failed branch and attempting a double-refund.

3. No logging at the `nobodyFailed` decision point — impossible to diagnose production failures.

**Fixes (CF only — no Android changes):**
- `successParticipants` now includes both `"active"` and `"completed"` participants.
- `nobodyFailed` now uses `participants.every(p => p.status === "active" || p.status === "completed")`.
- Detailed `functions.logger.info` added at: participant-status dump, nobodyFailed decision,
  per-PI Stripe action (cancel / full-refund / partial-refund / unexpected-status), pot calculation.
- Nobody-failed Stripe branch: explicit `requires_capture` → cancel; `succeeded` → full refund;
  any other status → warn log only (no silent failure).
- `firebase deploy --only functions:completeGroupChallenge` required after this change.

**Manual Firestore fix for existing Booking.com challenge (already completed with wrong data):**
In the Firebase console, find the `groupChallenges/{groupId}` document for Booking.com and set:
  `nobodyFailed: true`, `appFee: 0`, `prizePool: 0`, `prizePerWinner: 0`.
Also update each participant to: `status: "completed"`, `payoutStatus: "completed"`.
ProfileScreen will then re-read and display €0 fee on next open (reads live from Firestore).

---

## 2026-05-16 (10)

### DOCS — Created docs/09_payout_and_fees.md
Centralized all payout and fee documentation.
Covers: fee structure table, Hard Mode 80%, Redemption 60%,
Group 80%/100%, IBAN setup, Stripe Connected Account,
ProfileScreen display, Firestore payout structure.
Extracted from 03 and 04 — those files keep flow logic only.

---

## 2026-05-16 (9)

### DOCS — Created docs/08_ui_design_system.md
Centralized all UI design documentation into single file.
Covers: colors (light+dark), typography, buttons, DetoxHorizontalPicker,
overlay design system, detail screen designs, dashboard cards.
Extracted from 02, 03, 04, 07 — those files keep business logic only.

---

## 2026-05-16 (8)

### DOCS — All documentation files updated

docs/01_architecture_and_stack.md:
- DetoxHorizontalPicker.kt added to file structure

docs/02_core_mechanics_and_soft_mode.md:
- Overlay Design System v2 documented (dark, minimal, context header)
- DetoxHorizontalPicker documented (all limit inputs, min values, behavior)
- Soft Mode Detail Screen design documented

docs/03_hard_mode_and_stripe.md:
- Payout fees updated: Hard Mode 80% (was 100%), Redemption 60% (was 70%)
- Redemption Challenge fully documented
- Hard Mode Detail Screen design documented

docs/04_group_challenges.md:
- Creation Flow fixed: PaymentSheet BEFORE createGroupChallenge
- Join Flow fixed: confirmGroupJoin added, button loading state
- Payout fees: 80% stake refund (20% fee), 100% if nobody fails
- Detail Screen redesign documented
- "Nerv ihn!" marked as temporarily removed
- Friends Tab: real-time listener documented (waiting + active)

CLAUDE.md:
- docs/06_testing_guide.md added to docs list
- docs/07_onboarding_and_auth.md added to docs list

---

## 2026-05-16 (7)

### FIXED — BudgetSelectionOverlay missing horizontal scroll picker
Root cause: Overlay still used old chip LazyRow instead of DetoxHorizontalPicker.
Fix: Replaced chip row with DetoxHorizontalPicker (darkMode = true).
Values: 1..remainingMinutes (inclusive). Default selected: min(5, remainingMinutes).
Button label "X min starten" updates dynamically on every picker scroll.
DetoxHorizontalPicker gains optional `darkMode` param (default false — no change for wizard callers).
Dark colors: bg #0A0A0A, selected #FFFFFF, adjacent #444444/#333333/#222222, fade edges #0A0A0A.
Unit label hidden when empty string passed (overlay has its own "Minuten wählen" label below).
Snap behavior and haptic feedback unchanged. Hard stop at both ends when remainingMinutes is small.

---

## 2026-05-16 (6)

### FEATURE — Overlay redesign v2
Context header: challenge-type specific line at top of every overlay (13sp, weight 600, #00C853).
  SESSION_LIMIT Soft: "🔥 X Tage Streak" | Hard: "💰 €X auf dem Spiel" | Group: "👥 Platz #X von Y".
  DAILY_BUDGET: "⏱ X min übrig heute". TIME_WINDOW: "📅 Verfügbar ab HH:MM".
Large number (64sp, bold, #FFF, letter-spacing -3) shows used/remaining value with clear label.
  SESSION_LIMIT: opens used + "von X Öffnungen heute verbraucht".
  TIME_LIMIT: minutes used + "von X Minuten heute verbraucht".
  DAILY_BUDGET: remaining minutes + "von X Minuten heute verfügbar".
  TIME_WINDOW: no number — status text "Noch nicht verfügbar" instead.
Progress bar kept unchanged; two text labels added below (left context, right %).
"trotzdem öffnen" reduced to 10sp #222222, height 32dp — SessionIntentionOverlay ONLY.
Daily Budget chips: dark #141414 bg, #444 text, #1E1E1E border; selected: #00C853 bg, #000 text.
  Ghost "Stark bleiben" removed from BudgetSelectionOverlay (ghost only on SessionIntentionOverlay).
Limit Reached: "Tageslimit erreicht 🔒" + "Morgen bekommst du neue Öffnungen." below progress bar.
  Single "Stark bleiben 💪" button only — no ghost.
Time Window: countdown inset radius 14dp, 32sp countdown font; button changed to "Stark bleiben 💪".
OverlayManager: buildContextHeader() + computeGroupRank() helpers added.
  SessionIntentionOverlay: removed lastSessionEndedAt, motivationText, challengeDaysLeft, streak params.
  SessionLimitReachedOverlay: new appName, contextHeader, largeNumber, largeNumberLabel params; removed streak.
Files: SessionIntentionOverlay.kt, SessionLimitReachedOverlay.kt, BudgetSelectionOverlay.kt,
       TimeWindowOverlay.kt, OverlayManager.kt, strings.xml.

---

## 2026-05-16 (5)

### FEATURE — Detail Screen Redesign
Soft Mode: Streak + Best Streak + Days, info list, success rate, quote.
Hard Mode: Orange badge, Einsatz, 80% payout info, Stripe note.
Group Challenge: Pot + Teilnehmer + Dein Gewinn, leaderboard with Du badge.
Existing progress bar kept. Challenge aufgeben = text link only.
Nerv ihn button removed temporarily.

---

## 2026-05-16 (4)

### FEATURE — Horizontal scroll picker for all limit inputs
DetoxHorizontalPicker composable implemented.
Applied to: SESSION_LIMIT, TIME_LIMIT, DAILY_BUDGET, duration, buy-in,
BudgetSelectionOverlay, session duration input.
Min values: 1 open / 1 min / 14 days Hard / 3 days Group / €10 buy-in.
Default DAILY_BUDGET = 10 min. Step = 1 everywhere.
Haptic feedback on each scroll step. Snap behavior. White background.

---

## 2026-05-16 (3)

### REVERT — Remove DetoxScrollPicker; restore original limit inputs
Reverted the iOS-style scroll picker feature in full.
- Deleted `presentation/components/DetoxScrollPicker.kt`
- Restored `ChallengeCreationScreen.kt` Step4 (StepperField) and Step6 (StepperField + FilterChip presets)
- Restored `GroupChallengeCreateScreen.kt` Step3 (StepperField per limit type + duration) and Step4BuyIn (StepperField, max €50, step €5)
- Restored `BudgetSelectionOverlay.kt` to chip picker (CHIP_OPTIONS: 5/10/15/20/30/45 min)
- Reverted `ChallengeCreationViewModel.kt` dailyBudgetMinutes default: 10 → 39
- Removed picker_* string resources from strings.xml
- Removed dark #0A0A0A backgrounds from wizard steps; light Material theme restored

---

## 2026-05-16 (2)

### FEATURE — iOS-style scroll picker for all limit inputs
Replaced all limit value inputs with `DetoxScrollPicker` composable.
Applied to: SESSION_LIMIT, TIME_LIMIT, DAILY_BUDGET, duration (Soft + Hard Mode), buy-in (Group Challenge), BudgetSelectionOverlay.
Min values enforced: 1 open / 1 min / 14 days Hard Mode (1 in DEBUG) / 3 days Group / €10 buy-in.
Default DAILY_BUDGET = 10 minutes (was 39).
Step size = 1 everywhere. Max = 480 min / 50 opens / 365 days / 500 € buy-in.
Haptic feedback (`HapticFeedbackType.TextHandleMove`) on each scroll step.
Dark #0A0A0A background on all wizard picker steps.
New file: `presentation/components/DetoxScrollPicker.kt`.

---

## 2026-05-16

### FIXED — Group Challenge Join Flow (alle 3 Bugs)
Root cause: confirmGroupJoin Cloud Function nie deployed → HTTP 404
→ "Erneut versuchen" obwohl Payment erfolgreich
→ Keine Navigation nach Join
→ Friends Tab leer bis App-Neustart

Fix: confirmGroupJoin zu index.ts hinzugefügt + deployed.
Updates participants array + participantUserIds (für Friends Tab Query).
alreadyJoined response = Success → navigiert zu GroupChallengeDetailScreen.
firebase deploy --only functions:confirmGroupJoin

### FIXED — Group Challenge Create ohne Zahlung
Root cause: createGroupChallenge vor PaymentSheet aufgerufen.
Fix: createPaymentIntent zuerst → PaymentSheet → Completed → createGroupChallenge.

### FIXED — Group Challenge Join ohne Zahlung
Root cause: joinGroupChallenge vor PaymentSheet aufgerufen.
Fix: createPaymentIntent zuerst → PaymentSheet → Completed → joinGroupChallenge.

---

## 2026-05-15

### FEATURE — Dark minimal overlay redesign
All overlays redesigned: #0A0A0A bg, no white cards, no app icons. Poppins throughout.
Psychologically intentional: ghost button "trotzdem öffnen" 12sp #333.
6 overlays updated: SessionIntention, LimitReached, BudgetSelection, TimeWindow, WebsiteBlocked, HardModeLockout.
SessionIntentionOverlay: emoji 📱, streak line, 3-col stats row, thin green progress bar, ghost button.
SessionLimitReachedOverlay: emoji 🔒, orange stats row, orange 100% progress bar, dark inset hint, no bypass.
BudgetSelectionOverlay: chip picker (5/10/15/20/30/45 min), inverted buttons (primary=start, ghost=stay strong).
TimeWindowOverlay: NEW — emoji ⏰, live countdown, open/close time row. Replaces BlockingScreenOverlay for TIME_WINDOW.
WebsiteBlockedOverlay: emoji 🌐, domain in dark inset, "Zurück" only.
HardModeLockoutOverlay: emoji 🔐, dark inset shows €X + days remaining.
OverlayManager: challengeDaysLeft computed and passed to Intention overlay; opensUsed/maxOpens/streak passed to LimitReached; budgetTotalMinutes passed to BudgetSelection; TIME_WINDOW routes to new showTimeWindowOverlay + computeMinutesUntilOpen helper.
Dark status bar: no code change required (overlays are ComposeViews without a Window).
No blocking logic changed — visual only.
Files: SessionIntentionOverlay.kt, SessionLimitReachedOverlay.kt, BudgetSelectionOverlay.kt, TimeWindowOverlay.kt (new), WebsiteBlockedOverlay.kt, HardModeLockoutOverlay.kt, OverlayManager.kt, strings.xml.

### FIXED — CRITICAL: User added to Group Challenge without paying
Root cause: `joinGroupChallenge` CF added user to `participants` + `participantUserIds` arrays before PaymentSheet was shown, before any Stripe payment.
Fix: `joinGroupChallenge` CF now only creates the PaymentIntent and returns `clientSecret` — no Firestore write.
New `confirmGroupJoin` CF added: called after `PaymentSheetResult.Completed`, verifies PaymentIntent status with Stripe, then adds user to participants. Handles idempotency — if user already in `participantUserIds`, returns `{ success: true, alreadyJoined: true }` (200, not 409).
Android ViewModel/UseCase flow was already correct (initiatePayment → PaymentSheet → onPaymentSuccess → confirmJoin). No Android changes needed.
**Requires:** `firebase deploy --only functions` after this change.
Files: `functions/src/index.ts`.

---


### FEATURE — App Fee on Winnings
Hard Mode Solo win: 80% refund (20% app fee). Cloud Function `cancelOrRefundPayment` now captures the pre-auth PI before issuing a partial refund — NEVER cancels on win anymore.
Redemption Challenge win: 60% refund (40% app fee, was 70%).
Group Challenge win (losers exist): 80% of own stake back + prize share from losers' pot.
Group Challenge win (nobody failed): 100% refund, no fee.
`completeGroupChallenge` CF detects `nobodyFailed` and issues full cancel/refund for all; otherwise captures-then-partially-refunds each winner's stake at 80%.
`cancelOrRefundPayment` CF: new `appFeeAmount` field written to Firestore alongside `payoutAmount`.
ProfileScreen now shows fee breakdown for all challenge types (20%/40%/none).
`DailyEvaluationWorker`: `setRedemptionInfo` changed from 0.70 → 0.60.
**Requires:** `firebase deploy --only functions` after this change.
Files: `functions/src/index.ts`, `DailyEvaluationWorker.kt`, `NotificationHelper.kt`, `strings.xml`, `ProfileViewModel.kt`, `ProfileScreen.kt`, `ChallengeEntity.kt`, `docs/03_hard_mode_and_stripe.md`.

### FIXED — createGroupChallengePaymentIntent HTTP 404
Root cause: `createGroupChallengePaymentIntent` CF was added to `index.ts` by the previous fix but never deployed to Firebase → 404 on every Group Challenge creation attempt.
Fix: Removed `createGroupChallengePaymentIntent` CF from `index.ts` (dead code). Android now calls existing `createPaymentIntent` with `amountCents=buyInCents` and `challengeId=groupId` — already tested and working. `createGroupChallenge` CF is still called only after `PaymentSheetResult.Completed`, with the `paymentIntentId` returned from `createPaymentIntent`.
Files: `functions/src/index.ts`, `CloudFunctionsService.kt`, `CreateGroupChallengeUseCase.kt`.

### FIXED — Group Challenge created before Creator pays
Root cause 1: `createGroupChallenge` CF was called before PaymentSheet — it created a Stripe PaymentIntent AND wrote the Firestore document (with creator as participant) in one step. Cancelling payment left an orphaned challenge in Firestore.
Root cause 2: New Cloud Function `createGroupChallengePaymentIntent` never deployed → HTTP 404.
Fix: Reuse existing `createPaymentIntent` for Creator buy-in (already deployed and tested).
Flow: `createPaymentIntent` → PaymentSheet → `Completed` → `createGroupChallenge` CF.
`createGroupChallenge` CF accepts `paymentIntentId` as required parameter; only called after `PaymentSheetResult.Completed`.
`onPaymentCancelled()` / `onPaymentFailed()` clear pending data and return to Idle — no CF call, no Firestore write.
Files: `functions/src/index.ts`, `CloudFunctionsService.kt`, `CreateGroupChallengeUseCase.kt`, `GroupChallengeCreateViewModel.kt`, `GroupChallengeCreateScreen.kt`, `strings.xml`.

## 2026-05-14

### FIXED — User added to Group Challenge without completing payment
Root cause: `confirmGroupJoin` CF was not explicitly gated; additionally, `Canceled` and `Failed` PaymentSheet results showed no feedback to the user and were treated identically.
Fix: `confirmGroupJoin` only called inside `PaymentSheetResult.Completed` branch (was already wired correctly in ViewModel; verified and unchanged).
Fix: `PaymentSheetResult.Canceled` now shows snackbar "Zahlung abgebrochen" and returns user to Preview.
Fix: `PaymentSheetResult.Failed` now shows snackbar "Zahlung fehlgeschlagen. Bitte erneut versuchen." and returns user to Preview.
Fix: `AwaitingPayment` state added to `isLoading` condition — spinner shown while PaymentSheet is loading, preventing double-tap.
Files: `GroupChallengeJoinScreen.kt`, `res/values/strings.xml`.

### FIXED — Debug buttons never wrote to Room DailyLog
Root cause: `challengeDao` returned `Flow` instead of `suspend List` → never emitted in a one-shot coroutine → 0 challenges found → nothing written.
Fix: Added `suspend fun getActiveChallengesSync()` to `ChallengeDao`.
Fix: Added upsert with `OnConflictStrategy.REPLACE` to `DailyLogDao`.
Fix: Wrapped all debug write buttons in `try-catch` with `Timber.e`.
Fix: `DashboardViewModel` now observes `DailyLog` via `Flow` for live updates.
Applied to: Max Opens Now, Reset Opens Today, Exhaust Budget Now, Reset Budget Today.

### FIXED — Debug buttons never wrote to Room DailyLog (limitType case mismatch)
Root cause: `ChallengeRepositoryImpl.Challenge.toEntity()` stores `limitType` as **lowercase** in Room (`"sessions"`, `"time_budget"`). All 4 debug functions in `ProfileViewModel` filtered against **uppercase** strings (`"SESSIONS"`, `"TIME_BUDGET"`), so the filter matched zero rows. The forEach loop ran on an empty list — `upsert()` was never called, no DailyLog was ever written, the Dashboard Flow never fired.
Fix: All 4 filters changed to lowercase: `"sessions"` and `"time_budget"`.
Fix: Every function wrapped in `try-catch(Exception)` with `Timber.e` on failure.
Fix: Added `Timber.d` at every step: function start, total active count, SESSIONS/TIME_BUDGET count, challengeId, key, existing row state, before upsert, after upsert verification read.
DailyLogDao.upsert() and Dashboard observeLogsForDate() Flow were already correct — they work now that Room is actually written.
Applies to: `debugMaxOpensNow`, `debugResetOpensToday`, `debugExhaustBudgetNow`, `debugResetBudgetToday`.

### FIXED — Debug buttons not updating Dashboard (previous entry — partially correct)
Root cause: All 4 debug functions (`debugMaxOpensNow`, `debugResetOpensToday`, `debugExhaustBudgetNow`, `debugResetBudgetToday`) used plain SQL `UPDATE` queries (`updateConsciousOpens`, `updateBudgetStateMs`). When no `DailyLogEntity` row exists for today yet, `UPDATE WHERE ...` matches zero rows and silently does nothing — Room emits no change, Dashboard Flow never re-fires.
Fix: All 4 functions now use `getLogForDate()` to read the existing row (or null), then call `dailyLogDao.upsert()` (new `@Insert(onConflict = REPLACE)` method) with either `existing.copy(...)` or a freshly constructed entity.
Fix: `DailyLogDao` gains a `upsert(log: DailyLogEntity)` method for this pattern.
Fix: All Firestore writes now include `updatedAt` timestamp.
Note: This fix was incomplete — the limitType case mismatch was still causing zero rows to match (see entry above).
Dashboard Flow was already correct (`observeLogsForDate()` Flow with `drop(1)`).

---

## 2026-05-09

### FEATURE — Redemption Challenge

- Hard Mode Solo failures now trigger a "comeback" challenge after 24h
- Users fight to recover 70% of their lost stake at double duration, half the limit
- Dashboard banner (session-dismissable, orange) shows when redemption is available
- History screen "Starten" button with `RedemptionConfirmSheet` (ModalBottomSheet)
- Stripe partial refund on success via new `partialRefundCents` parameter in CF
- DB migration 21→22: 11 new columns on `challenges` table
- `RedemptionNotificationWorker` (HiltWorker) fires after 24h delay via WorkManager
- Eligibility: `originalDays <= 28`, has Stripe PI, not itself a redemption, within 3 days

---

### DOCS — Partial App Blocking documented in architecture files

docs/01_architecture_and_stack.md updated:
- `PartialBlockSection.kt` added to `domain/model/` file structure
- New section "Partial App Blocking — Architecture Notes" added
- Cache pattern: `activePartialBlockSections` in `TrackedAppEventBus` / `AppDetectionAccessibilityService`
- Room migration: `partial_block_sections` column (TEXT, default `""`) + `partial_block_only` (INTEGER, default `0`)
- Firestore storage format documented (`partialBlockSections: List<String>`, `isPartialBlockOnly: Boolean`)
- Event detection order documented (performance critical: `TYPE_WINDOW_STATE_CHANGED` first)

docs/02_core_mechanics_and_soft_mode.md updated:
- New section "Partial App Blocking (Reels / Shorts / Feed)"
- All 6 supported sections with packages and blocked content
- Detection order: Activity class name → ViewID → Content description (fastest to slowest)
- On detection: `GLOBAL_ACTION_BACK` + `Toast.LENGTH_SHORT` (NEVER `GLOBAL_ACTION_HOME`)
- `consciousOpens` rule: partial blocks NEVER increment counter
- `AppSelectionScreen` UI pattern documented (indented sub-options, always visible)
- Known limitations documented (ViewID changes on app updates, Huawei timing)

### FEATURE — Partial App Blocking (Reels / Shorts / For You)
New feature allowing challenges to block specific content sections inside native apps while leaving the rest of the app usable.
Detection via `AppDetectionAccessibilityService` using activity class names, view IDs, and content descriptions — zero DB queries on hot path.
6 sections supported: Instagram Reels, YouTube Shorts, TikTok For You, Facebook Reels, Twitter For You, Snapchat Spotlight.
`PartialBlockSection` enum in `domain/model/` holds detection metadata per section.
`isPartialBlockOnly = true` challenges skip full-block overlay — package excluded from `trackedPackages` in `UsageTrackingService`.
Room DB: version 20→21, adds `partial_block_sections` (TEXT) and `partial_block_only` (INTEGER) columns to `challenges` table.
Firestore: `partialBlockSections` (List<String> of IDs) + `isPartialBlockOnly` (Boolean) written/read on sync.
UI: `ChallengeCreationScreen` step 2 shows indented `PartialSectionSubRow` composable after each supported app row.
`AppSelectionScreen`: same sub-option rows; `onAppsSelected` callback extended with `partialSections: List<String>`.
Detection response: `GLOBAL_ACTION_BACK` + short Toast "🔒 <section> geblockt" — no overlay, no `consciousOpens` increment, 1s cooldown.
Docs: `02_core_mechanics_and_soft_mode.md` updated with Partial App Blocking section.

### FEATURE — Automatic Payout System
Hard Mode: automatic Stripe refund on completion via DailyEvaluationWorker — no manual action required.
Group Challenge: automatic stake refund + prize transfer on completeGroupChallenge CF.
Prize calculation: (total captured from losers - 10% appFee) / winners count (Math.floor).
`cancelOrRefundPayment` CF: removed `wasImmediate` flag — now auto-detects PI status (`requires_capture` → cancel, else → refund).
`completeGroupChallenge` CF: aligned field names (`prizePool`, `appFee`, `prizePerWinner`); enriched pendingPayout with `stakeRefundCents`/`status`/`displayName`.
`createConnectedAccount` CF: rewritten Express → Custom account with IBAN direct setup (no onboarding URL).
UI: detailed per-challenge payout breakdown cards in ProfileScreen (stake refund + prize share + app fee + status).
IBAN setup: ModalBottomSheet with AT IBAN validation → `createConnectedAccount` CF → prize released automatically.
Pending payouts: stored in `users/{uid}/pendingPayouts` subcollection with `status: "pending_account_setup"`.
Notifications: `sendGroupChallengePayoutReceived()` — three variants (full payout / stake + pending IBAN / stake only).
Docs: `03_hard_mode_and_stripe.md` updated with full Payout System section.

### FEATURE — Automatic Payout System implemented
Hard Mode: Stripe refund via DailyEvaluationWorker on COMPLETED.
Group Challenge: stake refund + prize transfer via completeGroupChallenge CF.
Prize calculation: (losers total - 10% fee) / winners count (Math.floor, never round up).
IBAN: Stripe Custom Connected Account (type "custom", not Express).
UI: detailed breakdown in ProfileScreen (Einsatz + Gewinnanteil + Gebühr).
Pending: stored in payoutRequests if no IBAN yet.
Notifications: automatic after payout.
Docs: `03_hard_mode_and_stripe.md` updated with Payout System section.

### DOCS — Added docs/07_onboarding_and_auth.md
Extracted onboarding and auth documentation from 01_architecture_and_stack.md into dedicated file for token efficiency. Covers: 5-screen onboarding flow, design system, permission setup, auth methods, logout rules, Hard Mode device binding, account deletion.

### FEATURE — Comprehensive DEBUG Testing Panel
Added collapsible debug section in ProfileScreen (DEBUG builds only).
9 sections: Onboarding, Daily Evaluation, Time Manipulation, Budget,
Opens, Group Challenge, Stripe, Room Database, Permissions.
Enables fast testing of all features without waiting real time.
- DailyLogDao: +getAllForDate, +deleteAllForDate, +updateConsciousOpens
- ChallengeDao: +updateEndDate
- GroupChallengeDao: +updateEndDate
- CreateChallengeUseCase: debug_use_minutes_as_days flag wired (multiplies durationDays * 60000 instead of 86400000)
- ChallengeCreationViewModel: debug_hard_mode_min_1 flag wired (Hard Mode min = 1 day in debug)
- ProfileViewModel: 10 debug functions + 3 debug StateFlows added
- ProfileScreen: DebugPanel composable (collapsed by default, 9 sections, orange card)
- Old standalone debug buttons (Test Blocking, Reset Onboarding) merged into Section 1

### FEATURE — Onboarding Flow implemented
5 swipeable screens: Welcome, Concept, Modes, Permissions, Start.
iOS-style design: #F2F2F7 background, white cards, #00C853 primary.
Shows only on first app start (SharedPreferences "onboarding_completed" in "detox_settings").
determineStartDestination() checks flag first — before any auth/permission logic.
Permission setup on Screen 4 with live checkmarks on every onResume.
Huawei battery optimization guidance dialog on Screen 4.
Debug reset button added in ProfileScreen (BuildConfig.DEBUG block).
New route: Screen.Welcome ("welcome") in DetoxNavGraph.
AuthScreen gains initialTab: AuthTab parameter; auth route extended to "auth?tab={tab}".
New file: presentation/screens/welcome/WelcomeOnboardingScreen.kt

---

## 2026-04-29

### FIXED — completeGroupChallenge HTTP 500
- Root cause: participants.filter() called on Map instead of Array (Firestore partial snapshot)
- Fix: Safe parser added before any .filter()/.map() call in Cloud Function
- Pattern: Array.isArray(raw) ? raw : Object.values(raw ?? {})
- Applied to: completeGroupChallenge, failParticipant, startGroupChallenge

---

## 2026-04-30

### FIXED — SessionLimitReachedOverlay: "Challenge verlieren" removed
- Root cause: User could quit challenge directly from overlay (moment of weakness)
- Fix: Removed quit button from ALL overlays
- New behavior: Single "Stark bleiben 💪" button only
- Only way to quit: Dashboard → Detail → "Aufgeben" button
- Applies to: ALL limit types, ALL overlays

### FIXED — Usage Schedule default state
- Root cause: All days (Mon-Sun) were pre-selected by default
- Fix: All days deactivated by default (empty = no schedule = blocked 24/7)
- User must explicitly activate days they want

### FIXED — Phantom Group Challenge lock (Hevy, Booking.com)
- Root cause: Cancelled/completed Group Challenges still marked "active" in Room
- Fix: "Is app occupied?" check only considers status == "active" OR "waiting"
- Fix: Sync Group Challenge status from Firestore to Room on app start

### DECISION — Daily Budget timer architecture
- Timer runs in UsageTrackingService (Foreground Service) — NOT OverlayManager
- sessionEndTime persisted in SharedPreferences (survives kills)
- On expiry: BudgetSelectionOverlay shown immediately over any screen (TYPE_APPLICATION_OVERLAY)
- On expiry while app closed: overlay shown on next foreground (Option B1 rejected → B2 chosen)

---

## 2026-05-01

### FIXED — Timezone bug: DailyLog written with wrong date key
- Root cause: OverlayManager used local timezone, GetDailyStatsUseCase used UTC
- Difference: 12 hours (UTC+2 Vienna) → Dashboard always read "null" from Room
- Fix: DateUtils.todayKey() created — single shared function using Calendar.getInstance()
- RULE: Search for "86400000" must return 0 results anywhere in codebase

### FIXED — Dashboard shows 0 for all challenges (consciousOpens, budgetUsedMs)
- Root cause: GetDailyStatsUseCase read DailyLog with wrong todayKey (UTC vs local)
- Fix: DateUtils.todayKey() applied to ALL date key calculations
- Status: consciousOpens ✅ working, budgetUsedMs ✅ working after Firestore sync added

### FIXED — budgetUsedMs resets to 0 on app restart (Huawei)
- Root cause: Huawei kills UsageTrackingService without calling onDestroy()
  budgetUsedMs was only written on session end or onDestroy() → never persisted
- Fix: Write budgetUsedMs to Room + Firestore every 10 seconds (fire-and-forget)
- Fix: On app start, restore budgetUsedMs from Firestore → Room → SharedPreferences
- Pattern: Identical to consciousOpens sync (see 02_core_mechanics_and_soft_mode.md)

### DECISION — Firestore sync pattern for ALL time-based tracking
- Room = immediate local display (always read for UI)
- Firestore = persistent backup (restored on app start)
- SharedPreferences = active session state only (sessionEndTime, committedMs)
- SetOptions.merge() always — never overwrite other fields
- Fire-and-forget — never block UI on Firestore response

### KNOWN BUG — Fortschrittsbalken in Detailansicht shows 0 for all limit types
- All limit types affected (SESSION, TIME, DAILY_BUDGET, TIME_WINDOW)
- Dashboard shows correct values ✅
- Detail screen shows 0 ❌
- Not yet fixed — next priority

---

## 2026-05-02

### DECISION — Hard Mode unified with Soft Mode
Hard Mode shares ALL blocking/overlay/DailyLog code with Soft Mode.
Only addition: Stripe pre-auth on start, capture on fail, refund on success.
Any Soft Mode fix must be verified against Hard Mode as well.

### DECISION — Group Challenge unified with Soft/Hard Mode
Group Challenge shares ALL blocking/overlay/DailyLog code with Solo challenges.
Additions: Stripe per participant, Firestore participants sync, leaderboard.
Any Solo fix must be verified against Group Challenge as well.
Two sync targets: DailyLog (identical to Solo) + participants array (Group-specific).

### DECISION — Universal architecture documented
DateUtils.todayKey() mandatory everywhere — grep for 86400000 must return 0.
Universal sync pattern documented for all challenge types.
Fortschrittsbalken global rule: always read from Room DailyLog fresh.
Never pass progress values as navigation arguments.

---

## 2026-05-03

### FIXED — Fortschrittsbalken shows 0 in Detail screen (all limit types)
Root cause: Detail screen read progress from stale one-shot suspend call instead
of live Room DailyLog Flow. TIME_BUDGET also used wrong field (budgetUsedMinutes
instead of budgetUsedMs).
Fix: ActiveChallengeViewModel now collects observeLogForDate() Flow — auto-refreshes
whenever UsageTrackingService writes to Room (every 10 s) or on every conscious open.
Fix: TIME_BUDGET uses budgetUsedMs / 60_000 (source of truth), not budgetUsedMinutes.
Fix: Removed private todayMidnightMs() duplicate — replaced with DateUtils.todayKey().
Fix: Added DailyLogDao.observeLogForDate + DailyLogRepository.observeLogForDate.
Applies to: Solo Detail screen (ActiveChallengeViewModel) + Group Challenge Detail screen.
Group fix: MyStatusCard now reads myOpensToday / myTimeUsedMinutes from Room DailyLog
via GroupDetailUiState.Success fields — NOT from Firestore participants array.

### FIXED — DAILY_BUDGET not working in Group Challenge
Root cause 1: syncGroupChallengeToLocalTracking() did not set dailyBudgetMinutes on the local
ChallengeEntity. handleTimeBudgetApp() computed totalBudgetMs = 0 → budget always 0ms,
overlay logic immediately skipped to "exhausted" but no Group Challenge fail path was reached.
Root cause 2: showBudgetExhaustedOverlay() did not check groupChallengeId — it only showed
SessionLimitReachedOverlay and freed the app. handleGroupChallengeFail() was never called,
so the Stripe capture and Firestore fail were silently skipped.
Root cause 3: checkBudgetSession() 10s tick wrote budgetUsedMs to Room but did not mirror
timeUsedMinutes to the Firestore participants array for Group Challenge DAILY_BUDGET.
Fix 1: GroupChallengeRepositoryImpl.syncGroupChallengeToLocalTracking() now sets
  dailyBudgetMinutes = limitValueMinutes when limitType == TIME_BUDGET.
Fix 2: OverlayManager.showBudgetExhaustedOverlay() now checks challenge.groupChallengeId —
  if non-null, delegates to handleGroupChallengeFail() (Stripe capture + fail overlay).
  Solo path unchanged (SessionLimitReachedOverlay).
Fix 3: UsageTrackingService.checkBudgetSession() now mirrors totalUsedMs/60000 to
  groupChallengeRepository.updateParticipantTimeUsed() on every 10s tick (fire-and-forget).
Applies to: Group Challenge DAILY_BUDGET. SESSION and TIME paths were already unified.

### FIXED — DAILY_BUDGET Group Challenge: failedGroupChallengeIds guard not applied + double-fail
- **Root cause 1:** `failedGroupChallengeIds` guard in `handleAppOpen()` was placed AFTER the
  `SESSIONS` and `TIME_BUDGET` early-return branches. Both limit types bypassed the guard entirely,
  so `handleTimeBudgetApp` could be called on a package whose Group Challenge was already failed.
- **Root cause 2:** `onBudgetSessionExpired()` calls `handleTimeBudgetApp()` directly (bypasses
  `handleAppOpen`). After a Group DAILY_BUDGET fail, budget session prefs were never cleared by
  `handleGroupChallengeFail()`. The next 10-second `checkBudgetSession` tick saw the prefs, fired
  `onBudgetSessionExpired` → budget exhausted → `handleGroupChallengeFail` again →
  `failGroupParticipant` cloud function called **twice**.
- **Fix 1:** `handleAppOpen()` refactored — `failedGroupChallengeIds` guard moved before the
  `when (challenge.limitType)` dispatch so it covers ALL limit types (SESSIONS, TIME_BUDGET, TIME,
  TIME_WINDOW). Single unified `when` block replaces the previous chain of `if … return` branches.
- **Fix 2:** `onBudgetSessionExpired()` now checks `failedGroupChallengeIds` before calling
  `handleTimeBudgetApp()`.
- **Added:** Diagnostic log `Group challenge check: pkg=… challengeType=GROUP limitType=DAILY_BUDGET`
  in both budget-session re-entry blocks in `AppDetectionAccessibilityService`.
- SESSION_LIMIT and TIME_LIMIT Group Challenge blocking verified via same unified dispatch.

### FIXED — Group Challenge Join Flow compile error
- Root cause: `@Composable` invocation in `GroupChallengeJoinScreen.kt` line 85 was called
  outside the Composable tree (direct invocation from a non-Composable lambda/block).
- Fix: Moved `@Composable` call into the Composable tree, controlled via a `showDetails: Boolean`
  state variable instead of direct invocation.
- Rule: Never invoke a `@Composable` function from outside a Composable context — use state
  variables to conditionally include Composable blocks.

### FIXED — Group Challenge Join Flow (double payment bug)
Root cause: confirmGroupJoin Cloud Function was never called after PaymentSheetResult.Completed.
Screen closed prematurely (navigated to group_detail), leaving user unenrolled. They had
to enter code and pay a second time before the join was confirmed.
Fix: onPaymentSuccess() now sets ConfirmingJoin state, calls confirmGroupJoin CF, and only
sets JoinedSuccessfully after the CF succeeds. Loading state prevents double-tap on Pay button.
Fix: Navigation after join now goes to FriendsHubScreen (Friends tab), not group_detail.
Fix: lastAwaitingPayment stored in ViewModel for confirmJoin retry if CF fails after payment.
New state: ConfirmingJoin(groupChallenge) — disables Pay button, shows spinner.
UX: GroupDetailsCard redesigned — shows app icon, buy-in prominently, limit type in German,
    duration, participants count, creator name, start info.
Strings: join screen translated to German (Suchen, Jetzt beitreten — €X bezahlen, etc.)

### FIXED — Group Challenge DAILY_BUDGET always shows full budget on first open
Root cause: budgetUsedMs restored from Firestore on app start for Solo only.
Group challenges have no entries in the nested Solo dailyLogs sub-collection (step 2
in syncUserData). Step 4 (fetchTodayDailyLogs) is unreliable for group challenges:
if refreshFromFirestore fails, a FK constraint violation aborts step 4 silently.
Result: Group challenge DailyLog (key: "group_{groupId}_{todayKey}") never in Room
on first open → OverlayManager falls back to totalBudgetMs.
Fix: Added step 3a to syncUserData() — explicit Group Challenge budget restore:
- After refreshFromFirestore (ChallengeEntity in Room), for each ACTIVE TIME_BUDGET group challenge
- Reads Firestore: users/{userId}/dailyLogs/group_{groupId}_{todayKey} via fetchDailyLogDocument()
- Writes budgetUsedMs + budgetRemainingMs to Room DailyLog
- Sets SharedPreferences budget_committed_ms = budgetUsedMs (guarded: skip if session active)
- If document does not exist yet (first use today): budgetUsedMs=0, budgetRemainingMs=totalBudgetMs
- Log: "Group budget restore: groupId=... usedMs=... remainingMs=..."
Added FirestoreService.fetchDailyLogDocument(userId, challengeId, date) to read single flat doc.
Key format verified consistent: write (updateDailyLogBudget) and read both use "group_{groupId}_{todayKey}".

### FIXED — Group Challenge DAILY_BUDGET budget_committed_ms wrong on restart
Root cause 1: SharedPreferences used single "budget_committed_ms" key for Solo + Group.
  onStartCommand restart-recovery looped through all TIME_BUDGET challenges and the last
  challenge's usedMs overwrote the others → wrong committed value for the active challenge.
Root cause 2: Leaderboard time read from UsageStatsManager (system screen time) instead of
  Room DailyLog budgetUsedMs → showed 13 min (all-day screen time) instead of 3 min budget used.
Root cause 3: Midnight reset only cleared the single shared key, not per-challenge keys.
Fix 1: Per-challenge SharedPreferences key "budget_committed_ms_{challengeId}" used everywhere:
  onStartCommand, checkBudgetSession (read + write), SyncRepositoryImpl step 3a.
  Key for Group Challenge = "budget_committed_ms_group_{groupId}" (challengeId = "group_{groupId}").
Fix 2: updateGroupChallengeStats() for TIME_BUDGET reads dailyLogRepository.getLogForDate()
  and uses budgetUsedMs / 60_000 as timeUsedMinutes — Room is always source of truth.
  Non-budget Group Challenges (TIME/SESSIONS) still read from UsageStatsManager unchanged.
Fix 3: Midnight reset uses budgetSessionPrefs.edit().clear() — wipes all keys in the prefs
  file including all per-challenge "budget_committed_ms_*" entries from the day.
Added Timber logs in SyncRepositoryImpl step 3a for Firestore path, read values, Room state,
  and SharedPreferences key to allow pinpointing future failures.

### FIXED — Group Challenge budget tick never wrote to Firestore dailyLogs
Root cause: Firestore dailyLogs write existed for Solo (via DailyLogRepositoryImpl appScope)
but was not explicitly mirrored in the Group Challenge path of checkBudgetSession().
Fix: Added explicit serviceScope.launch { firestoreService.updateDailyLogBudget(...) }
inside the challenge.groupChallengeId?.let block, alongside the existing participants mirror.
docId format: "group_{groupId}_{DateUtils.todayKey()}" — matches restore step 3a in SyncRepositoryImpl.
Solo path unchanged (handled via DailyLogRepositoryImpl).

### DECISION — Group Challenge never auto-fails for any limit type
All limit types (SESSION, TIME, BUDGET): limit reached = SessionLimitReachedOverlay only.
App stays blocked. Participant status remains "active". No Stripe capture.
Stripe capture ONLY on manual "Aufgeben" in Detail screen.
endDate reached = success → Stripe refund for all active participants.

Changes:
- OverlayManager: TIME_LIMIT group challenge → showSessionLimitReachedOverlay instead of handleGroupChallengeFail
- OverlayManager: DAILY_BUDGET group challenge → showSessionLimitReachedOverlay instead of handleGroupChallengeFail
- DailyEvaluationWorker: group evaluateGroupChallenge → removed failGroupParticipant CF call; DailyLog still written with limitExceeded=true (stats only), moneyLostCents=0
- GroupChallengeDetailViewModel: added QuitState + quitChallenge() — calls failGroupParticipant CF on manual quit
- GroupChallengeDetailScreen: added "Aufgeben" button with confirmation dialog; navigates to FriendsHub on confirm
- completeGroupChallenge CF: already correct — active participants get refunded on endDate
- docs/04_group_challenges.md: updated Group Challenge Rules table + Fail & Complete Logic section

### FIXED — Group Challenge budget resets to 0 on tab switch
Root cause: GetDailyStatsUseCase read DailyLog from Room using challenge.id (UUID) for Group
Challenges, but Group DailyLogs are stored with challengeId = "group_{groupId}". todayLog was
always null → budgetUsedMs always 0 in the no-active-session path.
Secondary: BUDGET_COMMITTED_MS_KEY used the base key ("budget_committed_ms") instead of
the per-challenge key ("budget_committed_ms_group_{groupId}") → committedMs was 0 in the
active session path.
Fix: dailyLogChallengeId = "group_${groupChallengeId}" for Group Challenges in GetDailyStatsUseCase.
Fix: per-challenge committedMs key used in active session path.
Fix: DashboardViewModel.todayMidnightMs() replaced with DateUtils.todayKey() (architecture rule).

### FIXED — Group Challenge DailyLog deleted from Room during Firestore sync
Root cause: `syncGroupChallengeToLocalTracking()` called `challengeDao.insertChallenge(entity)` with
`OnConflictStrategy.REPLACE`. SQLite's `INSERT OR REPLACE` deletes the existing `ChallengeEntity`
row before re-inserting it. The FK `onDelete = CASCADE` on `DailyLogEntity` cascade-deletes the
Group DailyLog (`challengeId = "group_{groupId}"`) in the same transaction.
Trigger: The Firestore real-time listener in `startSyncingForUser` fires its FIRST emit ~1s after
`syncUserData()` completes (network latency). `syncedStatuses` is still empty at that point, so the
status guard allows `syncGroupChallengeToLocalTracking` to run and wipe the freshly-created log.
Fix: Added `@Update updateChallenge()` to `ChallengeDao`. In `syncGroupChallengeToLocalTracking`,
check if the `ChallengeEntity` already exists: if so, call `updateChallenge()` (SQL UPDATE — no
delete, no cascade) instead of `insertChallenge()`. First-time inserts are unaffected.
Fix: `SyncRepositoryImpl` step 4 now uses `DateUtils.todayKey()` instead of inline Calendar calc
(architecture rule). Added diagnostic logs for DailyLog keys being written.

---

## 2026-05-04

### FIXED — Group Challenge budget resets to 0 on tab switch / app restart
Multiple root causes fixed over several sessions:

1. Firestore dailyLogs write missing in Group budget tick
   Fix: Added identical Firestore write (SetOptions.merge(), fire-and-forget)
   every 10s in UsageTrackingService for Group challenges
   docId: "group_{groupId}_{DateUtils.todayKey()}"

2. Group Challenge DailyLog deleted from Room during Firestore sync
   Root cause: DashboardViewModel syncJob deleted ALL DailyLogs before
   re-inserting from Firestore. Group DailyLogs not in Firestore fetch → deleted.
   Fix: UPSERT only (never DELETE). Sync includes Group challenge IDs.

3. SharedPreferences keys not per-challenge
   Fix: "budget_committed_ms_{challengeId}" — Solo and Group never overwrite each other

4. Leaderboard showed wrong time (13 min instead of 3)
   Fix: Leaderboard reads from Room DailyLog directly, not SharedPreferences

### DECISION — DailyLog sync rules (permanent)
- NEVER delete DailyLog rows during any sync operation
- UPSERT only: @Insert(onConflict = REPLACE) always
- Firestore sync must include ALL challenge types (Solo + Group)
- Group DailyLog key: "group_{groupId}_{DateUtils.todayKey()}"
- Solo DailyLog key: "{challengeId}_{DateUtils.todayKey()}"

---

## Architectural Decisions (permanent rules)

### DateUtils.todayKey() — MANDATORY
Always use DateUtils.todayKey() for DailyLog date keys.
Never calculate inline with System.currentTimeMillis() / 86400000.
Search for "86400000" in codebase must return 0 results.

### Firestore sync — SetOptions.merge() always
Never use .set() without merge on dailyLogs documents.
Always .set(data, SetOptions.merge()) to avoid overwriting sibling fields.

### Huawei — never rely on onDestroy()
Any state that must survive a Huawei kill must be:
1. Written periodically (every 10s) during operation
2. Synced to Firestore (fire-and-forget)
3. Restored from Firestore on app start

### consciousOpens — atomic write on tap only
Never increment consciousOpens anywhere except the "Ja, öffnen" button tap.
Never use UsageStatsManager for open counting.

### Overlays — never quit from overlay
No "Challenge verlieren" or quit option in any overlay.
Overlay buttons: Primary = 'Nicht öffnen' (#00C853 bg, #000 text). Ghost = 'trotzdem öffnen' (#FFFFFF text, transparent bg). No emojis in any button text.

### Universal Challenge Pattern
Soft Mode = base blocking logic + DailyLog sync
Hard Mode = Soft Mode + Stripe pre-auth/capture/refund
Group Challenge = Soft Mode + Stripe + Firestore participants sync
All three share identical overlay logic, DailyLog structure, and Fortschrittsbalken.

### Cloud Functions — onRequest only
Never use onCall() — breaks on Huawei (no Google Play Services).
Always use onRequest() with Bearer token auth.

### Overlay display — Handler only
Never use coroutines for showOverlay().
Always: Handler(Looper.getMainLooper()).post { overlayManager.showOverlay(...) }
