# 07 — Onboarding & Auth
> **Scope:** Onboarding flow, permission setup, authentication,
> logout rules, account management.
> **When to load:** Any work on OnboardingScreen, AuthScreen,
> LoginScreen, RegisterScreen, SettingsScreen (account section),
> or permission-related flows.

---

## Onboarding Flow

### When shown
Only on first app start.
SharedPreferences key: "onboarding_completed" (Boolean, default false)

Check in MainActivity.onCreate():
val onboardingCompleted = prefs.getBoolean("onboarding_completed", false)
if (!onboardingCompleted) → show Onboarding
else → normal flow (Auth or Dashboard)

On completion (Screen 5 button tap):
prefs.edit().putBoolean("onboarding_completed", true).apply()

Debug reset: ProfileScreen Debug Panel → "Reset Onboarding"
→ prefs.edit().putBoolean("onboarding_completed", false).apply()

### 5 Screens (HorizontalPager, swipeable)

Screen 1 — Willkommen:
- Logo: 72dp, #00C853, white checkmark SVG, border-radius 20dp
- App name: "De" #000 + "tox" #00C853, 32sp Poppins Bold
- Stats card: "4.2h" in #00C853, "Das sind 63 Tage pro Jahr"
- CTA card: "Nimm dein Leben zurück 💪"
- Buttons: "Jetzt starten" (primary) + "Überspringen" (ghost → Screen 5)

Screen 2 — Konzept:
- Title: "Jedes Öffnen ist eine Entscheidung" — "Entscheidung" in #00C853
- 3-step card: Overlay erscheint → Bewusst öffnen → Streak aufbauen
- Mini preview: progress bar + "🔥 7 Tage Streak"
- Button: "Weiter"

Screen 3 — Modi:
- Title: "Wähle deinen Modus"
- Soft Mode card: green #E8F8EF icon, "Gratis" badge
- Hard Mode card: orange #FFF0E8 icon, "Ab €5" badge
- Group Challenge card: purple #EEF0FF icon, "Sozial" badge
- Button: "Verstanden"

Screen 4 — Berechtigungen:
- Single white card with 3 permission rows + dividers
- Row 1: Overlay Permission (green icon)
- Row 2: Accessibility Service (purple icon)
- Row 3: Nutzungsstatistiken (orange icon)
- Each row shows ✅ when permission granted (check onResume)
- Privacy note: green card "Deine Daten bleiben auf deinem Gerät"
- On Huawei: extra dialog for battery optimization
  "Einstellungen → Apps → Detox → Akku → Keine Einschränkungen"
- Button: "Berechtigungen aktivieren"
  → REQUEST_OVERLAY: Settings.ACTION_MANAGE_OVERLAY_PERMISSION
  → REQUEST_ACCESSIBILITY: Settings.ACTION_ACCESSIBILITY_SETTINGS
  → REQUEST_USAGE_STATS: Settings.ACTION_USAGE_ACCESS_SETTINGS

Screen 5 — Los geht's:
- Logo + "Bereit." ("." in #00C853)
- Recommendation card: 3 checkmark rows
- Primary button: "Konto erstellen" → RegisterScreen
  Style: filled #00C853, white text, height 54dp
- Secondary button: "Ich habe bereits ein Konto" → LoginScreen
  Style: white background #FFFFFF (NEVER transparent),
         border 1.5dp #E0E0E5, text #00C853, height 54dp
  CRITICAL: Always explicit white bg — invisible against #F2F2F7 if transparent

### Design System (Onboarding only)
Background: #F2F2F7
Cards: #FFFFFF, border-radius 16dp, no elevation, border #E0E0E5
Primary: #00C853
Text primary: #000000
Text secondary: #8E8E93
Font: Poppins (assets/fonts/)
Status bar: light mode (dark icons)
  WindowCompat.getInsetsController().isAppearanceLightStatusBars = true

### Dot Indicators
Active dot: 22dp wide green pill (#00C853)
Inactive dot: 6dp circle (#D1D1D6)
Animated width transition on page change

### Navigation rules
- Swipe left/right between screens
- Back on Screen 1 → finishAffinity() (exit app)
- Back on Screen 2-5 → previous screen
- "Überspringen" → jumps to Screen 5 directly

---

## Authentication

### Auth Methods
Primary: Email/Password (works on ALL devices including Huawei)
Secondary: Google Sign-In (ONLY on devices with Google Play Services)

```kotlin
// Show Google Sign-In only if Play Services available:
val isGoogleAvailable = GoogleApiAvailability.getInstance()
    .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

if (isGoogleAvailable) {
    GoogleSignInButton(onClick = { ... })
}
// On Huawei: Google Sign-In button never shown
```

### Login Flow
1. Email + Password fields
2. "Anmelden" button → Firebase signInWithEmailAndPassword
3. On success → sync data from Firestore → navigate to Dashboard
4. On error → show inline error message (not Toast)
5. "Passwort vergessen?" → Firebase sendPasswordResetEmail

### Register Flow
1. Email + Password + Confirm Password fields
2. Password requirements: min 8 chars
3. "Registrieren" button → Firebase createUserWithEmailAndPassword
4. On success → create user document in Firestore → navigate to Dashboard
5. Set displayName from email prefix (before @)

### Firestore User Document (created on register)
users/{userId}/
    email: String
    displayName: String
    createdAt: Long
    payoutIban: String? (set later for Group Challenge winnings)
    payoutName: String?

### Logout Rules (CRITICAL ORDER — never reverse)
```kotlin
// ALWAYS clear Room BEFORE Firebase signOut:
// 1. Clear all Room tables
roomDatabase.clearAllTables()
// 2. Clear relevant SharedPreferences
prefs.edit().clear().apply()
// 3. THEN sign out Firebase
FirebaseAuth.getInstance().signOut()
// 4. Navigate to AuthScreen
```
Reason: prevents account-switching exploit where previous user's
data briefly appears for new user.

### Hard Mode Device Binding
While any Hard Mode challenge is active:
- Logout button is DISABLED in SettingsScreen
- Account deletion is DISABLED
- Show explanation: "Du kannst dich nicht abmelden während eine
  Hard Mode Challenge aktiv ist."

```kotlin
val hasActiveHardMode = challenges.any {
    it.mode == ChallengeMode.HARD && it.status == ChallengeStatus.ACTIVE
}
if (hasActiveHardMode) {
    // Disable logout + delete account buttons
}
```

### Account Deletion (DSGVO Pflicht)
Flow:
1. Settings → "Konto löschen"
2. Confirmation dialog: "Alle Daten werden gelöscht. Aktive
   Hard Mode Challenges schlagen fehl."
3. On confirm:
   a. Capture any active Hard Mode Stripe payments (FIRST)
   b. Delete all Firestore user data
   c. Clear Room database
   d. Delete Firebase Auth account
   e. Navigate to AuthScreen

### Passwort ändern
Settings → "Passwort ändern"
→ Firebase sendPasswordResetEmail(currentUser.email)
→ Toast: "Eine E-Mail wurde an ${email} gesendet"

---

## Permission Monitoring (post-onboarding)

After onboarding, permissions are monitored continuously.
See docs/05_huawei_and_permissions.md for full 24h escalation system.

Quick reference:
- PermissionCheckWorker runs every 15 min
- Red pulsing banner on ALL screens when permission missing
- 24h timer → Hard Mode auto-fail if never restored

Permission check functions:
```kotlin
fun isOverlayPermissionGranted(): Boolean =
    Settings.canDrawOverlays(context)

fun isAccessibilityServiceEnabled(): Boolean {
    val service = "${context.packageName}/.service.AppDetectionAccessibilityService"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
    return enabled?.contains(service) == true
}

fun isUsageStatsPermissionGranted(): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(), context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}
```

---

## Profile Screen (minimal — current)

Layout (top to bottom):
- Avatar (80dp circle) + username (20sp bold) + "Mitglied seit …" (13sp, #8E8E93)
- Guthaben Card — shown only when user has pending balance or winnings
- Settings card row: "Einstellungen →" navigates to SettingsScreen

Removed: streak 🔥 | challenges done ✅ | apps blocked 🚫 stats row.

---

## Settings Screen (iOS-style — current)

Full redesign: grouped white cards (#FFFFFF), colored icon circles (28dp), section headers
(13sp, #8E8E93, uppercase). Sections in order:

1. **Konto** — E-Mail ändern, Passwort ändern, Konto löschen
2. **Aktivität** — Daily Reminder toggle + time picker
3. **Auszahlungskonto** — IBAN hinterlegen / bearbeiten
4. **Erscheinungsbild** — Dark Mode (marked "Experimentell")
5. **Benachrichtigungen** — toggle per notification type
6. **Berechtigungen** — live status: Overlay ✅/❌, Accessibility ✅/❌, Usage Stats ✅/❌
7. **Datenschutz** — privacy policy link
8. **App Info** — version number, Rate App (opens Play Store)
9. **Entwickler** — debug panel (DEBUG builds only)

Friend Alerts row: removed (feature not implemented).

---

## Known Issues

1. Google Sign-In not available on Huawei — by design, not a bug
2. Password reset email may land in spam — mention in UI
3. Account deletion while Hard Mode active → must capture Stripe first
   (not yet fully implemented — manual process currently)
