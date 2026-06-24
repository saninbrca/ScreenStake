# 08 — UI Design System
> **Scope:** Colors, typography, components, overlay design, screen designs.
> **When to load:** Any UI work — screens, overlays, components, animations.
> **Never load for:** Business logic, Stripe, Cloud Functions, permissions.
> _Last verified: 2026-06-22 (commit e287b79)_

---

## Color Palette

### Light Mode (Wizard, Detail Screens, Dashboard, Friends Tab)
Background:        #F2F2F7
Card background:   #FFFFFF
Card border:       rgba(0,0,0,0.06) — 0.5px
Divider:           #F2F2F7 — 0.5px
Text primary:      #000000
Text secondary:    #8E8E93
Text hint:         #C7C7CC
Accent green:      #00C853
Accent orange:     #FF9500
Accent red:        #FF3B30
Accent purple:     #7B61FF

### Dark Mode (Overlays only)
Background:        #0A0A0A
Surface:           #111111
Surface elevated:  #141414
Border subtle:     #1E1E1E
Border:            #222222
Text primary:      #FFFFFF
Text secondary:    #666666
Text hint:         #444444
Text muted:        #333333
Text ghost:        #222222
Accent green:      #00C853
Accent orange:     #FF9500

### Badge Colors
Soft Mode:    #E8F8EF bg, #1E7A3C text
Hard Mode:    #FFF0E8 bg, #C05A00 text
Group LIVE:   #E8F8EF bg, #1E7A3C text
Group WAIT:   #F2F2F7 bg, #8E8E93 text
"Du" badge:   #E8F8EF bg, #1E7A3C text, 4dp radius

---

## Typography (Poppins — assets/fonts/)

| Usage | Size | Weight | Color |
|-------|------|--------|-------|
| Screen title | 22sp | 700 | #000 |
| App name (overlay) | 11sp | 400 | #444 |
| Card title | 17sp | 600 | #000 |
| Body | 14sp | 400 | #000 |
| Label | 13sp | 500 | #8E8E93 |
| Caption | 12sp | 400 | #8E8E93 |
| Hint | 11sp | 400 | #8E8E93 |
| Overlay large number | 64sp | 700 | #FFF (tabular figures, -2 tracking) |
| Overlay context header | 13sp | 600 | #00C853 (Stage 2 + budget/time overlays) |
| Overlay label | 13sp | 400 | #444 (Stage 2 + budget/time overlays) |
| Overlay ghost button | 10sp | 400 | #FFFFFF @ 28% |
| **Intention header** (Calm Authority, SessionIntentionOverlay) | 11sp | 500 | #00C853, ALL CAPS, 2.5sp tracking, emoji stripped |
| **Intention sub-label** (Calm Authority, SessionIntentionOverlay) | 13sp | 400 | #666 ("übrig" framing) |
| Detail stat value | 24sp | 700 | #000 |
| Detail big number | 36sp | 700 | #000 |
| Section title | 13sp | 600 | #8E8E93 uppercase |
| Motivational quote | 12sp | 400 | #C7C7CC italic |

---

## Button Styles

### Primary Button (light screens)
Height: 54dp, border-radius 14dp
Background: #00C853, text #FFFFFF, 16sp bold
Full width

### Primary Button (dark overlays)
Height: 52dp, border-radius 14dp
Background: #00C853, text #000000, 16sp/600 (SemiBold)
Full width

### Secondary Button (light screens)
Height: 54dp, border-radius 14dp
Background: #FFFFFF, border 1.5dp #E0E0E5, text #00C853
NEVER transparent bg — invisible against #F2F2F7

### Ghost Button (overlays — "trotzdem öffnen")
Height: 32dp, transparent bg, no border
Text: 10sp, #FFFFFF
Intentionally barely visible — psychological design
SessionIntentionOverlay ONLY

### Destructive Text Link ("Challenge aufgeben")
No background, no border
Text: 14sp, #FF3B30, centered
Padding: 16dp top
Tapping triggers confirmation dialog

### Loading State
CircularProgressIndicator replaces button content
Button remains same size, disabled (prevents double tap)

---

## DetoxHorizontalPicker Component

Location: presentation/components/DetoxHorizontalPicker.kt

```kotlin
DetoxHorizontalPicker(
    values: List<Int>,
    selectedValue: Int,
    onValueChange: (Int) -> Unit,
    isDark: Boolean = false  // true for overlays, false for wizard
)
```

### Visual
Selected item: 28sp bold
  isDark=false: #000000
  isDark=true: #FFFFFF
±1 items: 20sp
  isDark=false: #AAAAAA / isDark=true: #444444
±2 items: 16sp
  isDark=false: #CCCCCC / isDark=true: #333333
±3+ items: 14sp
  isDark=false: #E0E0E0 / isDark=true: #222222

Fade edges: gradient 40dp left+right
  isDark=false: #FFFFFF → transparent
  isDark=true: #0A0A0A → transparent

Selected indicator: 2dp green underline (#00C853) below selected item

### Behavior
- LazyRow + snapFlingBehavior
- Hard stop at min and max (no wrapping)
- Haptic: HapticFeedbackType.LongPress on each change
- Auto-scroll to selected on first composition
- Step: always 1

### Where used + defaults
| Screen | Min | Max | Default | Unit |
|--------|-----|-----|---------|------|
| SESSION_LIMIT wizard | 1 | 20 | 5 | Öffnungen |
| TIME_LIMIT wizard | 5 | 120 | 30 | Minuten |
| DAILY_BUDGET wizard | 5 | 120 | 10 | Minuten |
| Duration (Soft) | 3 | 90 | 7 | Tage |
| Duration (Hard) | 7 (1 debug) | 90 | 14 | Tage |
| Duration (Group) | 3 | 30 | 7 | Tage |
| Buy-in (Group) | → `docs/13` | → `docs/13` | 10 | Euro |
| Hard Mode Einsatz | → `docs/13` | → `docs/13` | 10 | Euro |
| Session duration | 1 | 30 | 5 | Minuten |
| BudgetSelectionOverlay | 1 | remainingMin | min(5,rem) | Minuten |

> **Stake/buy-in min/max are remote-configurable — canonical values in `docs/13`** (`hardModeMinStake`/
> `hardModeMaxStake`, `groupMinBuyIn`/`groupMaxBuyIn`). `08` owns picker **UI behaviour + step only**;
> it does not restate those limits as independent truth.

---

## Overlay Design System

### Layout (top to bottom, all overlays)
1. Status bar: dark icons (isAppearanceLightStatusBars = false)
   App name top-right: 11sp, #444
2. Context header (13sp, bold, #00C853)
3. Large number 64sp OR status text
4. Label below number (13sp, #444)
5. Progress bar (8dp height)
6. Progress labels (11sp, #AAAAAA)
7. Additional content (chips / inset / limit text)
8. Spacer flex:1
9. Primary button (52dp, #00C853, #000 text)
10. Ghost button (SessionIntentionOverlay only)

### Context Header per Challenge Type
SESSION_LIMIT Soft:  "🔥 X Tage Streak"         #00C853
SESSION_LIMIT Hard:  "💰 €X auf dem Spiel"       #00C853
SESSION_LIMIT Group: "👥 Platz #X von Y"         #00C853
TIME_LIMIT Soft:     "🔥 X Tage Streak"          #00C853
TIME_LIMIT Hard:     "💰 €X auf dem Spiel"       #00C853
DAILY_BUDGET:        "⏱ X min übrig heute"       #00C853
TIME_WINDOW_ONLY:    "📅 Verfügbar ab HH:MM"     #00C853

Always read LIVE from challenge + DailyLog. Never hardcoded.

**SessionIntentionOverlay (Calm Authority redesign):** strips the leading emoji and renders
the header in spaced ALL-CAPS (11sp / 500 / 2.5sp tracking, #00C853 — the lone accent),
e.g. "X TAGE STREAK", "€X AUF DEM SPIEL", "PLATZ #X VON Y". Done in the composable
(`cleanHeader`), NOT in strings.xml — the shared header strings keep their emoji for the
Stage 2 / budget / time overlays that still read them.

### SessionIntentionOverlay (Stage 1 — Calm Authority)
Large number: **REMAINING** opens (maxOpens − consciousOpens), 64sp tabular, count-up 0→value on show.
Sub-label: "Öffnungen übrig" (13sp, #666 — "übrig" framing).
Progress line: 3dp × ~120dp, centred, track #1E1E1E; green fill = REMAINING fraction,
width animates in sync with the count-up (green shrinks as opens are spent). No dots/percent row.
Motion: native Compose — content fade + slight upward translate (~260ms ease-out); no haptics.
Primary: "Nicht öffnen" (52dp, #00C853, #000 text).
Ghost: "trotzdem öffnen" (10sp, #FFFFFF @ 28%, barely visible).

### SessionLimitReachedOverlay (Stage 2)
Trigger: appears on the next open attempt AFTER the final granted session has expired —
not the instant remaining hits 0. The last conscious open still starts its session (the
user paid for N opens, so the Nth open is honored); the gate in OverlayManager is
`confirmedOpens >= maxOpens` and only triggers on a subsequent open once the active-session
window is over (re-shown in-place if the app is still foreground at expiry).

**Calm Authority — direction "Done":** reframes the exhausted state as completion, not
punishment. No lock icon, no limit-number hero, no emoji. Monochrome on #0A0A0A, single
green accent (#00C853). Same fade + upward-translate entrance as Stage 1; no count-up.
- Eyebrow: per-type, spaced ALL-CAPS, 11sp/500/2.5sp tracking, #00C853 — "STREAK GEHALTEN"
  (Soft) · "EINSATZ GESICHERT" (Hard) · "PLATZ GEHALTEN" (Group) · "GESCHAFFT" (generic).
  Built in OverlayManager (`buildCompletionEyebrow`), same type branching as the headers.
- Hero: closing statement, NOT a number — "Genug für heute" (30sp/700/#FFF, -1 tracking,
  line-height ~1.15, may wrap to 2 lines).
- Sub-label: "Morgen bekommst du neue Öffnungen." (13sp/400/#666).
- Completion line: 3dp × 120dp, centred, FULLY filled #00C853 (signal, not a progress bar).
- Primary: "Verstanden" (52dp, #00C853, #000 text). **NO ghost button** — decision is made.
- New strings (old "Tageslimit erreicht"/"Stark bleiben" left intact for any other reader):
  `overlay_v2_limit_hero`, `overlay_v2_limit_sub`, `overlay_v2_done_eyebrow_*`,
  `overlay_primary_understood`. The shared budget-exhausted callers use the same look.

### BudgetSelectionOverlay
"Calm Authority", stance **"visible friction"** — the user is choosing time to SPEND, so the
friction is VISIBILITY, not punishment. Single green accent (#00C853) only — **no red/orange**
"low budget" alarm (that would break the A-family). No emoji. Entrance = fade + 12dp translate-up
(~260ms), matching SessionIntentionOverlay / SessionLimitReachedOverlay.

Layout (top → bottom, vertically centred, generous space):
- App name top-right (11sp, #444).
- Eyebrow (scarcity anchor): "NOCH X MIN HEUTE" — X = remaining daily budget. 11sp/500, ALL-CAPS,
  letter-spacing 2.5, #00C853 (`overlay_budget_eyebrow`).
- Optional motivation quote (italic, #AAAAAA) — the user's own "why", as on the decision overlays.
- `DetoxHorizontalPicker(darkMode = true, enableHaptics = false)` restyled to A: selected value
  48sp/700/#FFF tabular (letter-spacing -2), immediate neighbours #555, outer neighbours #2E2E2E,
  a small #00C853 indicator dot **above** the selected value (not the light variant's underline).
  Range 1..remainingMin, **default low** = min(5, rem). Bounded by remaining → can't overspend.
- Unit label "Minuten" (12sp, #666) under the picker (`overlay_budget_unit_minutes`).
- Live consequence line: "danach noch Y min übrig", Y = remaining − selected, **updates as the
  picker turns**; the "Y min" portion sits in #888 (same hue family). 13sp, #666
  (`overlay_budget_consequence_before` / `_amount` / `_after`).
- Primary: "X min starten" (updates with selection; `overlay_budget_start_session`).
- Ghost: "stark bleiben" — **visible & legible** (#999, 13sp), restraint is the good choice here
  and honestly offered, but never louder than the primary (`stay_strong_button`). This overlay
  inverts ghost/primary priority.

The dark `DetoxHorizontalPicker` variant is **exclusive to this overlay**; all in-app callers use
the light variant. `contextHeader` is no longer passed (the eyebrow anchors on remaining budget).

### TimeWindowOverlay — "Calm Authority" (countdown)
Info-only (app outside its time window). Calm "not yet, here's when" — not a wall.
No emoji, no ghost button, single green accent.
App name top-right (11sp/400, #444).
Centred block, fade + translate-up entrance (~260ms ease-out) like the siblings —
but NO count-up (the hero is a time, not a count).
  Eyebrow: "VERFÜGBAR IN" (11sp/500, letter-spacing 2.5, ALL CAPS, #00C853)
  Countdown hero in inset card (bg #111, 1px border #1E1E1E, 16dp radius, 26dp×16dp padding):
    hero (48sp/700, #FFF, tabular figures, letter-spacing -2)
    unit label (11sp/500, #666, letter-spacing 2)
    Format mirrors the wait: ≥60 min → "H:MM" + "STUNDEN"; <60 min → bare minutes + "MINUTEN".
  Below inset: "ab HH:MM wieder frei" (13sp, #666) — the durable fact, survives after
  the relative countdown goes stale.
Primary only: "Verstanden". NO ghost button, NO bypass.
`minutesUntilOpen` computed once at show-time in OverlayManager — no live tick.

### CountdownScreen — "Calm Authority" (ring)
The 5s cooldown beat AFTER the user commits ("Ja, öffnen" / "X min starten"), before the
app is released. Framed as a felt pause to let the impulse cool — not a bare counter.
No emoji, single green accent. Rendered inside SessionIntention/Budget overlays.
App name top-right (11sp/400, #444).
Centred block, fade + translate-up entrance (~260ms ease-out) like the siblings.
  Eyebrow: "ÖFFNET IN" (11sp/500, letter-spacing 2.5, ALL CAPS, #00C853)
  Draining ring: ~150dp, 5dp stroke, round cap, track #1E1E1E, progress #00C853.
    The green arc starts full at 5s and drains to empty at 0 (starts at top, -90°).
  Number centred in ring: remaining whole seconds (72sp/700, #FFF, tabular figures), 5→1.
  Sub-label under ring: "Atme kurz durch." (13sp, #666).
Cancel at bottom: "Abbrechen" (14sp, #999) — visible & legible, NOT the 28% ghost; backing
  out here is the good choice, honestly offered but quieter than the ring. Wired to the
  EXISTING cancel path (`onCancel` → caller's home/back route); app is NOT opened.
Single continuous timer (`withFrameNanos` elapsed) drives BOTH: number = ceil(remaining
  seconds), ring sweep = continuous remaining fraction → ring drains smoothly, no per-second
  stutter. 5s duration and completion (`onComplete`) unchanged.

### Critical Overlay Rules
FLAG_SECURE: MANDATORY on all overlays
Handler(Looper.getMainLooper()).post: MANDATORY for showOverlay()
Pre-cache ALL overlay views in AccessibilityService.onCreate()
Never re-inflate — only update dynamic content

### Progress Bar
Height: 8dp (all overlays)
Progress labels below bar: 11sp, #AAAAAA (left = context text, right = percentage)

### Haptic Rule
Haptic feedback is used ONLY in:
- Wizard "Weiter" / "Next" buttons → `HapticManager.light()`
- App selection row taps → `HapticManager.light()`
- `DetoxHorizontalPicker` number change → `HapticManager.light()` — **in-app pickers only**.
  Overlays pass `enableHaptics = false` (overlays never add haptic feedback); the BudgetSelectionOverlay
  picker is therefore silent.
**NEVER in any overlay** — not on button taps, not on dismiss, not on show.

### Group Challenge Overlay (dark fullscreen — same as Solo)
The Group overlay uses the same dark fullscreen design as the Solo overlay (background #0A0A0A).
It is NOT a white card dialog. All English strings replaced with German.
Ghost button "trotzdem öffnen" aligned with Solo design.

---

## Detail Screen Design

### Shared Rules (all types)
Background: #F2F2F7
Cards: #FFFFFF, 16dp radius, 0.5px border rgba(0,0,0,0.06)
Card gap: 12dp, screen padding 16dp
Info list rows: 12dp 16dp padding, 0.5px #F2F2F7 divider
"Challenge aufgeben": text link only, 14sp #FF3B30, centered, no bg

### Soft Mode Detail
Card 1 — Header:
  "SOFT MODE" badge (green) + end date (12sp, #8E8E93) right
  App name: 22sp bold
  2 stats: Streak 🔥 | Tage noch (#00C853) — *(Beste Streak removed)*
  All stat values: 24sp bold

Card 2 — Progress:
  "Heute" left + "X / Y Öffnungen" right (12sp)
  Existing progress bar (UNCHANGED)
  Footer: "X übrig" + "Reset um Mitternacht" (11sp, #8E8E93)

Card 3 — Info list:
  Limit | Session-Dauer | Gestartet | Endet | Erfolgsrate (#00C853)

Quote: 12sp, #C7C7CC, italic — rotates daily from 5-10 German quotes

### Hard Mode Detail
Same as Soft Mode +
Badge: "HARD MODE" (#FFF0E8 bg, #C05A00 text)
Stats: Streak 🔥 | Tage noch — *(Einsatz shown in info list below)*
Info adds: "Einsatz" €X | "Bei Erfolg" €X zurück (80%) #00C853
Below info: "💳 Dein Geld ist sicher verwahrt"
Quit confirmation mentions: "€X wird eingezogen"

### Group Challenge Detail
Card 1 — Header:
  "● LIVE" or "⏳ WARTET" badge + days right
  App name: 22sp bold
  Limit description: 13sp, #8E8E93
  3-column stats: Gesamtpot | Teilnehmer X/20 | Dein Gewinn (#00C853)

Leaderboard section:
  Title: "Leaderboard" (13sp, #8E8E93, uppercase)
  Single white card, rows with 0.5px dividers
  Row: Rank (gold/silver/bronze/#8E8E93) | Avatar 32dp | Name | "Du" badge
  Sub-label: "● Aktiv gerade" or "Zuletzt vor Xh"
  Stat: right-aligned
  Own row: #F9FFF9 background
  Failed: strikethrough name, #C7C7CC
  "Nerv ihn!" TEMPORARILY REMOVED

Session section:
  Title: "Deine Session heute"
  Verbraucht / Noch verfügbar (#00C853) / existing progress bar

Quit: "Du verlierst €X (80% zurück). Wirklich aufgeben?"

---

## Dashboard Card Design

Solo card:
  👤 icon, app name, mode badge
  Progress bar (existing)
  Streak + remaining days

Group card:
  👥 icon, challenge name, LIVE/WARTET badge
  Pot amount + rank
  Progress bar (existing)
  Waiting: "⏳ Wartet auf Start von [name]"

---

## History Screen (Verlauf)

### List view (`HistoryScreen`)
Card background hardcoded #FFFFFF (not `MaterialTheme.colorScheme.surface`).
- App name: 17sp Bold, color #000.
- Date: 12sp, color #8E8E93.
- Right side: **two stacked badges** (Column):
  - Top — **TYPE badge** (pill): GROUP (#EEF0FF / #5856D6), HARD MODE (#FFF0E8 / #C05A00),
    SOFT MODE (#E8F8EF / #1E7A3C). Determined by `groupChallengeId != null` first, then `mode`.
  - Bottom — **STATUS text** (no background): "✓ Geschafft" #00C853, "✗ Aufgegeben" #FF3B30.
- The old single `StatusChip` (pill with background) is replaced by the `TypeBadge` + `StatusText`
  composables.

### Detail view (`HistoryDetailScreen`)
Simplified stats-focused layout:
- Removed the "ZEIT ZURÜCKGEWONNEN" header + large saved-time number + subtext.
- Removed the "weniger Zeit" (percentageReduction) third stats column.
- Stats card shows **2 centered columns only:** "Beste Streak" | "Bewusst geöffnet".
  Both values 17sp bold #000; labels 12sp #8E8E93.
- Removed the "Nochmal starten" button entirely (and its trailing Spacer). `onStartAgain` is kept
  in the public composable signature (navigation still passes it) but dropped from the private
  `DetailContent` — no navigation changes.

---

## Status Bar Rules
Light screens (wizard, detail, dashboard): isAppearanceLightStatusBars = true
Dark screens (overlays): isAppearanceLightStatusBars = false
Onboarding: isAppearanceLightStatusBars = true

---

## Group Challenge Results Screen

Shown once after a Group Challenge ends (guard: SharedPreferences `"podium_shown_{groupId}"`).
Background: #0A0A0A (dark fullscreen). isAppearanceLightStatusBars = false.

### Podium Colors
```
Platz 1 (center / tallest): #FFD700  — gold
Platz 2 (left):             #C0C0C0  — silver
Platz 3 (right):            #CD7F32  — bronze
```
Each column rises sequentially with an enter animation: Platz 3 → Platz 2 → Platz 1.
Konfetti rain on entry (top 3 only). Lottie trophy animation for Platz 1.

### User Result Card
Win/loss outcome + payout info for current user.
"Weiter" button (primary, #00C853) → navigates to Detail Screen.

### Failed Participants
Shown below podium, greyed out (#8E8E93), no animation.

---

## Profile Screen (minimal — current)

Background: #F2F2F7. No stats row.

Layout (top to bottom):
- Avatar 80dp circle + **@username** 20sp bold + "Mitglied seit …" 13sp #8E8E93
  - The unique `@username` is shown (not the email prefix). Legacy accounts without a username
    fall back to the email prefix (before `@`). See `docs/07_onboarding_and_auth.md` → username system.
- **Guthaben Card** — shown only when user has pending balance/winnings
  Contains: pending amount + "Auszahlen" button + payout status indicator
- Settings card row: "Einstellungen →" (taps navigate to SettingsScreen)

Removed: streak 🔥 | challenges done ✅ | apps blocked 🚫 stats row.

---

## Settings Screen (iOS-style — current)

Background: #F2F2F7. Grouped white cards (#FFFFFF, 16dp radius).
Section headers: 13sp, #8E8E93, uppercase.
Each row: colored icon circle (28dp) + label + disclosure arrow or toggle.

Sections in order:
1. **Konto** — E-Mail ändern, Passwort ändern, Konto löschen
2. **Auszahlungskonto** — IBAN hinterlegen / bearbeiten (moved from ProfileScreen)
3. **Erscheinungsbild** — Dark Mode (row labeled "Experimentell")
4. **Benachrichtigungen** — single toggle "Wenn Teilnehmer scheitert" (only user-toggleable notif)
5. **Hilfe & Support** — Support kontaktieren (→ SupportScreen), Häufige Fragen (→ FaqScreen)
6. **Berechtigungen** — live status rows: Overlay ✅/❌, Accessibility ✅/❌, Usage Stats ✅/❌
7. **Datenschutz** — Datenschutz, AGB, Impressum links
8. **App Info** — version number, Rate App (opens Play Store)
9. **Entwickler** — debug panel (DEBUG builds only)

Friend Alerts row: removed (feature not implemented).
Daily Reminder toggle + time picker: removed (daily-reminder feature deleted in notification cleanup).

---

## App/Website Selection (iOS-style — current)

### Search Field
Pill-shaped (fully rounded corners), no dividers between app rows.

### App Tab
- App icons: 48dp with rounded corners
- Selected row: green checkmark overlay + #F9FFF9 background
- No divider lines between rows

### App Row — "Busy" State (app already in active challenge)
- Row background: #F5F5F5
- App name text: #C7C7CC (grey)
- Right side: lock icon (grey, 16dp) — no red label, no duplicate name
- Row is NOT tappable

### Websites Tab
- Feature cards (Instagram Reels, YouTube Shorts, etc.): platform app icon with red 8dp badge
- Adult Content card: "18+" red circle icon — always at top of the list
- Pill tab switcher: animated indicator slides between "Apps" and "Websites" tabs

---

## Permission Warning Banner (current)

iOS-style solid red card on Dashboard when any required permission is missing.

| Property | Value |
|----------|-------|
| Background | #FF3B30 (solid red) |
| Border radius | 12dp |
| Padding | 16dp |
| Elevation | 0 (no shadow) |
| Title | "Berechtigung fehlt" — 15sp, weight 700, #FFFFFF |
| Title icon | ⚠️ emoji |
| Body text | 13sp, white 80% alpha, maxLines=2 |
| CTA button | "Jetzt beheben →" — white bg, #FF3B30 text, 8dp radius, 8×16dp padding |
| Animation | Pulse: scale 1f → 1.01f → 1f, 2000ms infinite Reverse repeat |

**Three message variants:**
1. Accessibility missing only
2. Overlay missing only
3. Both missing

**Visibility rule:** Banner shown only when at least one permission is missing AND active
challenges exist (Success state only). CTA navigates to `Settings.ACTION_ACCESSIBILITY_SETTINGS`.

---

## Win Screen — `ChallengeSuccessDialog` (Dashboard dialog)

Dismissible `Dialog {}` shown on top of the Dashboard (other cards stay visible behind the scrim) —
replaces the old fullscreen success overlays. Handles both modes from one composable.
- **Soft Mode:** time-saved card. **Hard Mode:** money-refund card (€ back + 20% fee line).
- Canvas confetti, staggered phase reveals (0 / 300 / 600 / 900 ms), count-up stat animations.
- Dismiss: an **X** button and a "Zurück zum Dashboard" link both dismiss; "Neue Challenge starten"
  navigates to the wizard.
- Show guard: SharedPreferences `"win_shown_{challengeId}"` (file `"detox_win_popup"`); DB
  `completionShown` flag marked on dismiss (belt-and-suspenders).

## Hard Mode Fail Screen — `HardModeFailScreen` (route `hard_mode_fail/{challengeId}`)

Dark fullscreen (`#0A0A0A`, `isAppearanceLightStatusBars = false`) — for the manual "Aufgeben" quit
path (the auto-fail / limit-exceeded path still uses the Dashboard `HardModeFailOverlay`).
- 💸 icon, "Challenge verloren." title (red period).
- White money card showing the captured stake `€X,XX` (formatted from integer cents — never rounds up).
- Encouragement line.
- Primary: "Zurück zum Dashboard" (white bg, black text, 54dp). Text link: "Neue Challenge starten".

## System Screens (`presentation/screens/system/`) — see docs/13

| Screen | Route | Icon | Behavior |
|--------|-------|------|----------|
| `ForceUpdateScreen` | `force_update` | ⬆️ `#00C853` | `BackHandler` blocks back; "Jetzt aktualisieren" opens `updateUrl` |
| `MaintenanceScreen` | `maintenance` | 🔧 `#FF9500` | shows `maintenanceMessage` or default; "Erneut versuchen" re-reads config, proceeds only if cleared |
| `AccountDisabledScreen` | `account_disabled` | 🚫 red | `BackHandler` blocks back; shows `disabledReason` or default; "Support kontaktieren" → mailto |

## Auth Screens (new)

- **`EmailVerificationScreen`** (route `email_verification?fromRegister={bool}`): "Ich habe bestätigt"
  (inline error if still unverified), "E-Mail erneut senden" with 60s cooldown countdown, auto-polls
  every 5s, "Falsche E-Mail?" link. Detail in docs/07.
- **`UsernameSelectionScreen`** (route `username_selection?fromRegister={bool}`): @-prefixed lowercase
  input (a–z/0–9/_), min 3 / max 20, "X / 20" counter, 500ms debounced availability check
  (green "Verfügbar" / red "Bereits vergeben"); `BackHandler` blocks back. Detail in docs/07.

## Support Screens (`presentation/screens/support/`) — see docs/12

- **`SupportScreen`:** iOS-style white card (bg `#F2F2F7`) — Kategorie dropdown (Bug / Frage /
  Beschwerde / Auszahlung / Sonstiges), Betreff (single line), Nachricht (multiline). Validates
  category + subject + message ≥ 10 chars. Submit → confirmation screen; failure → inline error.
- **`FaqScreen`:** 8 expandable cards (chevron rotates), all Q&A in `strings.xml` (German).

## Dashboard Banners (remote-driven) — see docs/13

- **Soft-update banner:** dismissible green banner when `VERSION_CODE < latestVersionCode` (and not
  force-blocked). "Aktualisieren" opens `updateUrl`; dismissal stored in `detox_update_banner` prefs,
  re-shows after 3 days.
- **Broadcast dialog:** one-time `AlertDialog` (title + message + "Verstanden") for the newest active
  `broadcasts` doc whose id differs from the stored `last_seen_broadcast_id` (prefs `detox_broadcast`).
