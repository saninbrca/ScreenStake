# 06 — Testing Guide
> **Scope:** Debug panel, test accounts, Stripe test cards, manual CF testing, developer setup.
> **When to load:** Any testing, debugging, or developer setup tasks.

---

## Debug Panel (ProfileScreen)
Only visible in debug builds (BuildConfig.DEBUG).

Location: ProfileScreen → bottom → "🛠 Debug Tools" (collapsible)

NEVER add debug code outside BuildConfig.DEBUG check.
NEVER commit debug flags as true.
NEVER use debug time manipulation in production.

### Permission Violation Tests
- "Simulate Permission Loss (Firestore)" — writes permissionLostAt to Firestore 2h ago
- "Simulate Usage Violation" — writes usageViolationDetectedAt to Firestore 2h ago + calls checkPermissionViolations CF
- "Check Root Status" — shows Toast with root detection result
- "Force CF Permission Check" — calls CF + shows result
- "Reset Permission Status" — clears Firestore permissionStatus

### Other Debug Buttons
- Force Daily Evaluation
- Simulate Challenge End
- Adult Domains update trigger
- Domain count display

### Quick Reference — Debug Panel Sections
- Section 1 — Onboarding: reset first-start flag
- Section 2 — Daily Evaluation: trigger worker immediately
- Section 3 — Time Manipulation: shorten challenge duration for testing
- Section 4 — Budget: reset or exhaust daily budget instantly
- Section 5 — Opens: reset or max out conscious opens instantly
- Section 6 — Group Challenge: force complete/fail without waiting
- Section 7 — Stripe: test card info + dashboard link
- Section 8 — Room Database: inspect and clear DailyLogs
- Section 9 — Permissions: check status + simulate permission loss
- Section 10 — Adult Domain Stats: domain count + force update + test domain

---

## Test Accounts
- sanin.brica@gmail.com (uid: munSOfNqn8RUpfSmThtEnDal33y2)
- izo.b@hotmail.com (uid: Mn3ZikQcKiRnyQr2Spto10svOpu1)
- maestro.test@detox.app (uid: tRpu3zfduLbhweBLnCIUkAjtmrZ2)

---

## Stripe Test Card
- 4242 4242 4242 4242, 12/34, 123

---

## Manual CF Testing
Call via curl or Postman with INTERNAL_SECRET header.

```bash
# Example: Force permission violation check
curl -X POST https://us-central1-detox-33208.cloudfunctions.net/checkPermissionViolations \
  -H "x-internal-secret: YOUR_INTERNAL_SECRET" \
  -H "Content-Type: application/json"
```

---

## Developer Setup

### Claude Code Settings Reference

```json
// ~/.claude/settings.json
{
  "model": "sonnet",
  "env": {
    "MAX_THINKING_TOKENS": "10000",
    "CLAUDE_AUTOCOMPACT_PCT_OVERRIDE": "50",
    "CLAUDE_CODE_SUBAGENT_MODEL": "haiku"
  }
}
```

### Development Workflow

```
1. Write prompt here → copy to Claude Code /plan mode
2. Test on real Huawei device after EVERY change
3. Logcat filter: package:com.detox.app level:error
4. git add . && git commit -m "feat/fix: description" after each working feature
5. /compact in Claude Code after long sessions
6. /clear in Claude Code when switching topics
7. firebase deploy --only functions after any Cloud Function change
```
