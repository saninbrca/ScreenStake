# 08 — UI Design System
> **Scope:** Colors, typography, components, overlay design, screen designs.
> **When to load:** Any UI work — screens, overlays, components, animations.
> **Never load for:** Business logic, Stripe, Cloud Functions, permissions.

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
| Overlay large number | 64sp | 700 | #FFF |
| Overlay context header | 13sp | 600 | #00C853 |
| Overlay label | 13sp | 400 | #444 |
| Overlay ghost button | 10sp | 400 | #222 |
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
Background: #00C853, text #000000, 16sp bold
Full width

### Secondary Button (light screens)
Height: 54dp, border-radius 14dp
Background: #FFFFFF, border 1.5dp #E0E0E5, text #00C853
NEVER transparent bg — invisible against #F2F2F7

### Ghost Button (overlays — "trotzdem öffnen")
Height: 32dp, transparent bg, no border
Text: 10sp, #222222
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
| SESSION_LIMIT wizard | 1 | 50 | 5 | Öffnungen |
| TIME_LIMIT wizard | 1 | 480 | 60 | Minuten |
| DAILY_BUDGET wizard | 1 | 480 | 10 | Minuten |
| Duration (Soft) | 1 | 365 | 7 | Tage |
| Duration (Hard) | 14 (1 debug) | 365 | 14 | Tage |
| Duration (Group) | 3 | 365 | 7 | Tage |
| Buy-in (Group) | 10 | 500 | 10 | Euro |
| BudgetSelectionOverlay | 1 | remainingMin | min(5,rem) | Minuten |

---

## Overlay Design System

### Layout (top to bottom, all overlays)
1. Status bar: dark icons (isAppearanceLightStatusBars = false)
   App name top-right: 11sp, #333
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

### SessionIntentionOverlay (Stage 1)
Large number: consciousOpens / timeUsedMin / budgetRemaining
Label: "von X Öffnungen heute verbraucht" etc.
Primary: "Stark bleiben 💪"
Ghost: "trotzdem öffnen" (10sp, #222, barely visible)

### SessionLimitReachedOverlay (Stage 2)
Large number: limit value (full)
Progress: 100%, label "Heutiges Limit erreicht"
Text: "Tageslimit erreicht 🔒" (15sp, bold, #FFF)
Sub: "Morgen bekommst du neue Öffnungen." (13sp, #444)
Primary: "Stark bleiben 💪"
NO ghost button

### BudgetSelectionOverlay
Large number: remaining minutes
Label: "von X Minuten heute verfügbar"
DetoxHorizontalPicker (isDark=true): 1 to remainingMin
Chips replaced by picker — label "Minuten wählen" (11sp, #333)
Primary: "X min starten" (updates with selection)
Ghost: "stark bleiben 💪" (this overlay inverts ghost/primary)

### TimeWindowOverlay
No large number
Status text: "Noch nicht verfügbar" (15sp, bold, #FFF)
Sub: "Dein Zeitfenster beginnt um HH:MM" (13sp, #444)
Dark inset (#111, 14dp radius, 14dp 20dp padding):
  "Verfügbar in" (11sp, #444)
  HH:MM countdown (32sp, bold, #FFF, letter-spacing -1)
  "Stunden" (11sp, #444)
Time row: open/close times with divider
Primary: "Verstanden 👍"

### Critical Overlay Rules
FLAG_SECURE: MANDATORY on all overlays
Handler(Looper.getMainLooper()).post: MANDATORY for showOverlay()
Pre-cache ALL overlay views in AccessibilityService.onCreate()
Never re-inflate — only update dynamic content

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
  3 stats: Aktuelle Streak 🔥 | Beste Streak (#8E8E93) | Tage noch (#00C853)
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
Stats: Streak 🔥 | Einsatz €X | Tage noch
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
Each column rises sequentially with an enter animation.
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
- Avatar 80dp circle + username 20sp bold + "Mitglied seit …" 13sp #8E8E93
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
2. **Aktivität** — Daily Reminder toggle + time picker
3. **Auszahlungskonto** — IBAN hinterlegen / bearbeiten (moved from ProfileScreen)
4. **Erscheinungsbild** — Dark Mode (row labeled "Experimentell")
5. **Benachrichtigungen** — toggle per notification type
6. **Berechtigungen** — live status rows: Overlay ✅/❌, Accessibility ✅/❌, Usage Stats ✅/❌
7. **Datenschutz** — privacy policy link
8. **App Info** — version number, Rate App (opens Play Store)
9. **Entwickler** — debug panel (DEBUG builds only)

Friend Alerts row: removed (feature not implemented).

---

## App/Website Selection (iOS-style — current)

### Search Field
Pill-shaped (fully rounded corners), no dividers between app rows.

### App Tab
- App icons: 48dp with rounded corners
- Selected row: green checkmark overlay + #F9FFF9 background
- No divider lines between rows

### Websites Tab
- Feature cards (Instagram Reels, YouTube Shorts, etc.): platform app icon with red 8dp badge
- Adult Content card: "18+" red circle icon — always at top of the list
- Pill tab switcher: animated indicator slides between "Apps" and "Websites" tabs
