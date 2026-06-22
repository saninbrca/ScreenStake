# 11 — Admin Dashboard
> **Scope:** The founder admin dashboard (`admin/index.html`) — its 7 tabs, the `counters/global` stats doc, the ban system, broadcasts, and the admin auth model.
> **When to load:** Any work on `admin/index.html`, `counters/global`, `backfillCounters`, `setUserBanStatus`, `broadcasts`, or the admin-facing side of support/anti-cheat.
> _Last verified: 2026-06-22 (commit e287b79)_

---

## What it is

A single-file dashboard, **`admin/index.html`**, used by the founder to monitor and moderate the app.
It talks directly to Firebase (Firestore + the admin Cloud Functions). It is **`.gitignore`d**
(contains Firebase credentials — see `docs/01`).

### Auth model
- Admin identity is the email **`sanin.brica@gmail.com`**.
- Every admin Cloud Function (`setUserBanStatus`, `backfillCounters`, `detectSuspiciousUsers`) is
  `requireAdmin`-gated on that email; every admin-only Firestore rule checks
  `request.auth.token.email == "sanin.brica@gmail.com"`.
- **SECURITY:** the old `<!-- ADMIN PASSWORD: admin123 -->` comment was removed from the file.
  Passwords must never live in code/comments.

### Hosting — local-only (security decision)
The dashboard is run **locally only** (opened via `file://`, with the CORS handling needed for that
context) and is **not** hosted online. Keeping it off the public web removes it as an attack surface —
the admin credentials and the unbounded admin queries never touch a public origin.

---

## Tabs

### 1. 🏠 Übersicht (default landing)
Active on load. Cheap live cards read from `counters/global` (Nutzer gesamt / Aktive / Abgeschlossen /
Gescheitert / Gesamtumsatz). "Heute" cards via limited queries (`users createdAt >= startOfToday`,
`collectionGroup challenges createdAt >= startOfToday`, open-ticket count). Recent-activity feed: last
10 challenges (collection-group, `orderBy createdAt desc limit 10`, usernames batch-resolved) + last 5
tickets. "🧮 Counter neu berechnen" button → confirm modal → `backfillCounters` → reload.

### 2. 👤 Benutzer
User search by username OR email (two queries merged — Firestore can't OR across fields). Detail view:
account info + consent + Stripe/IBAN status, challenge history with counts, payment totals (staked /
refunded / captured), support tickets. Actions: "Sperren" / "Entsperren" with a `reason` prompt +
confirmation modal → `setUserBanStatus` (see Ban System below).

### 3. 💬 Support Tickets
Full ticket management — see **`docs/12_support_system.md`** for the schema and admin-side detail
(detail panel, user context, private internal notes, archived dashboard reply, filter/search).
"Offene Tickets" stat card counts `status in [open, in_progress]`.

### 4. 💰 Umsatz
Gesamtumsatz / diesen Monat / letzte 7 Tage + breakdown (Hard Mode 20% fees, eingezogene Einsätze,
Group 10% fees) + Ausstehende Verbindlichkeiten (sum of `payoutRequests` status `requested`/`pending`).
Scans `collectionGroup("challenges")` + completed groups client-side; time-bucketed via
`payoutDate`/`completedAt`. **Documented cost tradeoff** — `counters/global.totalRevenueCents` is the
cheap alternative if it grows (see `docs/09`).

### 5. 📢 Broadcast
Sends an admin → all-users announcement (confirm modal → `add` to `broadcasts` with `active: true`) and
lists past broadcasts with an Aktivieren/Deaktivieren toggle. App side: see Broadcasts below.

### 6. 🛡️ Anti-Cheat
"Analyse starten" (confirmation modal → `detectSuspiciousUsers`), results table sorted by risk with a
color-coded badge (rot ≥ 60, orange 30–59, gelb < 30), expandable per-signal details, "Verbundene
Konten" links (→ Benutzer tab), and actions: "Profil ansehen", "✓ Geprüft – OK" (false positive), and
"🚫 Sperren" (reuses `setUserBanStatus`). Decisions stored in `antiCheatReviews/{userId}`; cleared
users show greyed out in future analyses. Full detail: **`docs/10_security_and_anticheat.md`**.

### 7. ⚙️ App Config
Remote control of the app via the `config/app` document — toggles (Wartungsmodus, Hard Mode,
Gruppen-Challenge), text fields (Wartungsnachricht, Update URL), number fields (min/latest VersionCode,
+ the 4 stake-limit fields). "Speichern" writes via `.set(payload, { merge: true })`. Full detail:
**`docs/13_remote_config_and_flags.md`**.

---

## `counters/global` — dashboard stats doc

Best-effort aggregate counters (`totalUsers`, `totalActiveChallenges`, `totalCompletedChallenges`,
`totalFailedChallenges`, `totalRevenueCents`, `updatedAt`). All updates use `FieldValue.increment`
(never read-then-write) via a `bumpCounters()` helper wrapped in try/catch — **a counter failure never
blocks a payment/challenge op**. Doc auto-creates on first increment (`set(merge)`).

**Increment points (Cloud Functions):**
- `onUserCreated` (Firestore `onCreate(users/{uid})` trigger) → `totalUsers +1`.
- `createPaymentIntent` (solo, `!isGroupChallenge`) → `totalActiveChallenges +1`.
- `capturePayment` (Hard fail / emergency unlock) → active −1, failed +1, revenue += captured.
- `cancelOrRefundPayment` (solo/redemption win) → active −1, completed +1, revenue += app fee.
- `checkPermissionViolations` (permission + usage capture) → active −1, failed +1, revenue += captured.
- `completeGroupChallenge` (someone-failed) → revenue += 10% group fee.

**Scope note:** active/completed/failed track **Hard Mode** challenges only (the events with an
authoritative server-side money step); Soft Mode has no reliable completion signal and is excluded to
keep counts balanced.

### `backfillCounters` CF (`onRequest` + `requireAdmin`)
One-time/sparingly-run recompute. Counts all `users`; scans `collectionGroup("challenges")` for solo
Hard Mode active/completed/failed; sums revenue = retained app fees on wins (`appFeeAmount`, fallback
`floor(20%)`) + full captured stakes on fails + 10% fees from completed `groupChallenges`.
**OVERWRITES** `counters/global` (plain `.set`, not increment). Cost note in code: unbounded scan,
admin-gated, manual trigger only.

> **ACTION REQUIRED after deploy:** run "🧮 Counter neu berechnen" once to seed `counters/global` with
> real current totals.

---

## Ban System (admin actions)

CF **`setUserBanStatus`** (`onRequest`, admin-email auth) — two layers: `admin.auth().updateUser(uid,
{ disabled })` (hard) + Firestore `users/{uid}.{disabled, disabledReason, disabledAt}` (instant,
app-startup enforced). Triggered from the Benutzer tab (Sperren/Entsperren) and the Anti-Cheat tab
(🚫 Sperren). Full app-side enforcement + `AccountDisabledScreen`: **`docs/10_security_and_anticheat.md`**.

---

## Broadcasts

New `broadcasts/{id}` collection (`title`, `message`, `createdAt`, `active`). Sent + managed from the
Broadcast tab. **App side** (`DashboardViewModel` + `DashboardScreen`): on Dashboard load reads the
newest active broadcast (`whereEqualTo("active", true).orderBy("createdAt", DESC).limit(1)`); if its id
differs from the SharedPreferences `last_seen_broadcast_id` (file `detox_broadcast`), shows a one-time
`AlertDialog` (title + message + "Verstanden" → stores the id, never shown again). Fail-open: any read
error leaves the banner hidden. See `docs/13`.

---

## Firestore rules (admin surfaces)
- `counters/{doc}` — admin read, CF-only write.
- `broadcasts/{id}` — `read: if request.auth != null`, `write: if admin email`.
- `antiCheatReviews/{userId}` — admin email read/write.
- `supportTickets` + `supportTickets/{id}/adminNotes` — see `docs/12`.
- `users` — admin read/list (search) + admin-only writes to the ban fields.

**Deploy:** `firebase deploy --only firestore:rules,firestore:indexes,functions`.
