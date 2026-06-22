# 12 — Support System
> **Scope:** In-app support (contact form + FAQ), the `supportTickets` collection, private admin notes, the dashboard reply flow, and the Firestore rules.
> **When to load:** Any work on `SupportScreen`, `FaqScreen`, `supportTickets`, the admin Support-Tickets tab, or support-related Firestore rules.
> _Last verified: 2026-06-22 (commit e287b79)_

---

## App side

### `SupportScreen` (`presentation/screens/support/`)
iOS-style white card (bg `#F2F2F7`):
- **Kategorie** dropdown — Bug / Frage / Beschwerde / Auszahlung / Sonstiges
- **Betreff** — single line
- **Nachricht** — multiline

Validates category + subject + message ≥ 10 chars. On submit, writes a **new** doc via
`supportTickets.add()` (auto-ID — **NOT** `SetOptions.merge`, this is a create not an upsert).
Success → confirmation screen; failure → inline error. Reached from **Settings → Hilfe & Support →
"Support kontaktieren"** (route `support`).

### `FaqScreen`
8 expandable cards (chevron rotates) with all Q&A in `strings.xml` (`faq_q1..8` / `faq_a1..8`, German).
Reached from **Settings → Hilfe & Support → "Häufige Fragen (FAQ)"** (route `faq`).

> These replaced the two old `mailto:` rows ("Feedback senden" / "Support kontaktieren") in Settings.

---

## `supportTickets/{ticketId}` schema

Written by the app on submit (all user identity fields come from Firebase Auth):

```
userId: String              ← request.auth.uid
username: String
email: String
category: String            ← Bug | Frage | Beschwerde | Auszahlung | Sonstiges
subject: String
message: String
status: String              ← "open" | "in_progress" | "resolved"
appVersion: String          ← BuildConfig.VERSION_NAME
deviceModel: String         ← Build.MANUFACTURER + MODEL
androidVersion: String      ← Build.VERSION.RELEASE
createdAt: Long             ← epoch millis
resolvedAt: Long?           ← null until resolved (epoch millis)

— admin-written archive fields (added later, see below) —
adminReply: String?         ← admin's archived reply text
adminReplyAt: Long?         ← epoch millis
```

### Private admin notes — sub-collection (NOT an array field)
`supportTickets/{ticketId}/adminNotes/{noteId}` → `{ note: String, createdAt: Long }` (epoch millis),
written via `.add()`. Stored as a **separate sub-collection** specifically because the ticket owner can
read their own ticket doc — an array field on the doc would leak notes to them. The sub-collection rule
is admin-read/write-only, so notes never reach the user.

---

## Admin side (Support-Tickets tab)

See `docs/11_admin_dashboard.md` for the dashboard context. Summary of the ticket detail panel:
- **Ticket-Info** (category, subject, full message, status, created/resolved dates, device model,
  Android + app version) + an auto-loaded **Benutzer-Kontext** section (username, email, member since,
  account status, challenge counts, payment summary, "X weitere Tickets", and a link to the full
  profile in the Benutzer tab).
- **Interne Notizen** — list + "📝 Notiz hinzufügen" (`addAdminNote` / `loadAdminNotes`), backed by the
  private `adminNotes` sub-collection.
- **Antwort (archive):** textarea (pre-filled with any existing `adminReply`), an "Als erledigt
  markieren" checkbox, and two buttons — **"✉️ Per E-Mail antworten"** (saves `adminReply` + opens
  `mailto:` with the reply pre-filled) and **"💾 Nur speichern"** — both via `saveTicketReply(sendEmail)`,
  which also sets `status='resolved'` + `resolvedAt` when the checkbox is ticked.
  - **The actual reply reaches the user via email.** `adminReply` is the admin's archive only — the app
    has no in-app reply view (and although the user *could* read `adminReply` via direct Firestore, the
    app never surfaces it).
- **Filter & search** (client-side): single filterable list with category dropdown, status dropdown,
  sort (newest/oldest), and a username/subject search box. Rows are click-to-open; a ✉️ marker shows
  tickets that have a saved reply. "In Bearbeitung" → `status='in_progress'`.

---

## Firestore rules

```
match /supportTickets/{ticketId} {
  // user: create only with userId == request.auth.uid, read only their own
  // admin (sanin.brica@gmail.com): update + list
  // delete: false (nobody)
  // admin update rule is field-unrestricted → covers adminReply/adminReplyAt + status/resolvedAt

  match /adminNotes/{noteId} {
    // admin email read + write ONLY — the parent ticket read rule does NOT cascade
    // to sub-collections, so the ticket owner can never read notes
    allow read, write: if request.auth.token.email == "sanin.brica@gmail.com";
  }
}
```

**Deploy:** `firebase deploy --only firestore:rules`. **No Cloud Functions** — the support system is
pure Firestore + the admin dashboard.
