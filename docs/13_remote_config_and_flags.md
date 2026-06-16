# 13 — Remote Config & Feature Flags
> **Scope:** The `config/app` Firestore document, `AppConfigRepository`, force-update / maintenance gating, feature flags, remote stake limits, the soft-update banner, and broadcasts.
> **When to load:** Any work on `AppConfigRepository`, `config/app`, `MainActivity` startup gating, `ForceUpdateScreen`/`MaintenanceScreen`, the Hard Mode / Group feature flags, or the App Config admin tab.

---

## Why not Firebase Remote Config?

Firebase Remote Config requires Google Play Services → breaks on Huawei. Instead, a single Firestore
document **`config/app`** remotely controls the app. All reads go through the existing
`FirebaseFirestore` instance.

> **FAIL-OPEN CONTRACT (never violate):** a missing doc or any read error (offline / Huawei) keeps
> cached or safe defaults and **NEVER** locks the user out.

---

## `config/app` document

| Field | Type | Safe default | Purpose |
|-------|------|--------------|---------|
| `minVersionCode` | Int | 1 | below → Force Update |
| `latestVersionCode` | Int | — | below → soft-update banner |
| `maintenanceMode` | Bool | false | true → Maintenance screen |
| `maintenanceMessage` | String | — | shown on Maintenance screen |
| `hardModeEnabled` | Bool | true | false → Hard Mode creation disabled |
| `groupChallengeEnabled` | Bool | true | false → Group creation disabled |
| `updateUrl` | String | — | opened by update buttons |
| `hardModeMinStake` | Int | 5 | Hard Mode stake picker min (€) |
| `hardModeMaxStake` | Int | 100 | Hard Mode stake picker max (€) |
| `groupMinBuyIn` | Int | 10 | Group buy-in picker min (€) |
| `groupMaxBuyIn` | Int | 50 | Group buy-in picker max (€) |
| `reconciliationEnabled` | Bool | **false** | master kill-switch for the reconciliation net (server-read only) |
| `reconciliationDryRun` | Bool | **true** | true → net logs intended actions, makes NO Stripe call / NO write |

---

## `AppConfigRepository` (`data/repository/AppConfigRepository.kt`)

`@Singleton`, injects `FirebaseFirestore` + `@ApplicationContext`. Exposes
`config: StateFlow<AppConfig>`. `refresh()` reads `config/app`, mirrors it to SharedPreferences
(`detox_app_config`) and **never throws** — returns cached/defaults on failure. The `AppConfig` data
class holds the fields above with hardcoded fallbacks so a missing config never breaks a picker.

---

## Startup gating (`MainActivity.onCreate`)

After computing the normal start destination, `appConfigRepository.refresh()` runs, then in **priority
order**:
1. `VERSION_CODE < minVersionCode` → **`ForceUpdateScreen`**
2. else `maintenanceMode` → **`MaintenanceScreen`**
3. else the normal destination (then the ban gate — see `docs/10`)

Offline → fail-open into the app. The real destination is threaded into `DetoxNavGraph`
(`maintenanceClearedDestination`) so Maintenance "Erneut versuchen" can forward the user once cleared.

### System screens (`presentation/screens/system/`)
- **`ForceUpdateScreen`** (route `force_update`) — ⬆️ `#00C853`, `BackHandler` blocks back, "Jetzt
  aktualisieren" opens `updateUrl`.
- **`MaintenanceScreen`** (route `maintenance`) — 🔧 `#FF9500`, shows `maintenanceMessage` or default,
  "Erneut versuchen" re-reads config and proceeds only if cleared.
- **`SystemViewModel`** — config flow + retry.

*(`AccountDisabledScreen` lives here too but belongs to the ban system — see `docs/10`.)*

---

## Feature flags (NEW creation only — active challenges untouched)

- **Hard Mode** (`hardModeEnabled = false`): greys the Hard Mode card in wizard Step 1
  (`ChallengeCreationScreen`) with "Vorübergehend nicht verfügbar"; `selectMode` also guards. Flag via
  `ChallengeCreationViewModel.appConfig`.
- **Group Challenge** (`groupChallengeEnabled = false`): disables the "Erstellen" button in
  `FriendsHubScreen` + shows the unavailable note. Flag via `FriendsHubViewModel.groupChallengeEnabled`.

Both gate **new creation only** — active challenges are never affected.

---

## Remote stake limits

The Hard Mode stake picker (`ChallengeCreationScreen.Step6Duration`) and the Group buy-in picker
(`GroupChallengeCreateScreen.GStep4BuyIn`) build their `DetoxHorizontalPicker` range from AppConfig
(`(safeMin..safeMax)`, value `coerceIn`'d) instead of the old hardcoded `(5..100)` / `(10..50)`.
`GroupChallengeCreateViewModel` injects `AppConfigRepository` and exposes `appConfig`. Hardcoded
fallbacks apply if the config is missing.

---

## Soft-update banner (`DashboardScreen`)

Dismissible green banner when `VERSION_CODE < latestVersionCode` (and not force-blocked).
"Aktualisieren" opens `updateUrl`; dismissal stored in `detox_update_banner` SharedPreferences and
re-shows after 3 days (`DashboardViewModel.showUpdateBanner` / `dismissUpdateBanner`).

---

## Broadcasts

Admin → all-users announcements live in the `broadcasts/{id}` collection and are acknowledged once per
user via a Dashboard `AlertDialog`. Full flow (collection schema, admin tab, app-side dialog +
`last_seen_broadcast_id` guard, string `broadcast_acknowledge`): **`docs/11_admin_dashboard.md`**.

---

## Reconciliation safety net flags (server-read only — money fail-SAFE)

`reconciliationEnabled` / `reconciliationDryRun` gate the scheduled reconciliation Cloud Function
(`runDueChallengeReconciliation`, see `docs/09`/changelog). Unlike every other flag here, they are
read **server-side** by the Cloud Function via the Admin SDK — **not** by `AppConfigRepository`.

> **FAIL-SAFE CONTRACT (opposite of the app-side fail-OPEN above):** for money, a missing/unreadable
> `config/app` or a missing flag is treated as **disabled** (`enabled=false`, `dryRun=true`). A config
> problem must NEVER trigger an unattended capture or refund.

- `reconciliationEnabled=false` (default) → the function is a no-op (no query, no Stripe, no writes).
- `reconciliationDryRun=true` (default) → the function logs each intended WIN/LOSS/RECONCILE/SKIP but
  makes **no** Stripe call and **no** Firestore write. Only an explicit `false` disarms it.

**Rollout:** seed `reconciliationEnabled=true` + `reconciliationDryRun=true` first, verify logs in the
Cloud Functions console, **then** set `reconciliationDryRun=false`. Edited via the ⚙️ App Config admin
tab (`.set(payload, { merge: true })`).

---

## Firestore rules

```
match /config/app {
  allow read:  if request.auth != null;
  allow write: if request.auth.token.email == "sanin.brica@gmail.com";
}
```

Admin edits via the **⚙️ App Config** tab (`.set(payload, { merge: true })`).
**Deploy:** `firebase deploy --only firestore:rules`.
