# CLAUDE.md — Detox App (Maintenance Phase)

## Project Overview
Detox is an Android screen time reduction app with two modes: Soft Mode (points-based gamification) and Hard Mode (real money via Stripe). Built with Kotlin, Jetpack Compose, Firebase, and Stripe. Target audience: 16–25 year olds.

## Tech Stack
Kotlin, Jetpack Compose (Material 3), MVVM + Clean Architecture, Room, Hilt, Firebase (Auth + Firestore + Cloud Functions + FCM), Stripe Android SDK, Foreground Service, AccessibilityService, WorkManager, DataStore. Min SDK 26, Package: com.detox.app.

## Key File Locations
- **App Entry:** DetoxApplication.kt
- **Navigation:** presentation/navigation/DetoxNavGraph.kt
- **Services:** service/UsageTrackingService.kt, service/AppDetectionAccessibilityService.kt, service/OverlayManager.kt
- **Cloud Functions:** functions/src/index.ts
- **Database:** data/local/db/DetoxDatabase.kt
- **Firebase:** data/remote/firebase/FirebaseAuthService.kt, FirestoreService.kt, CloudFunctionsService.kt
- **Stripe:** data/remote/stripe/StripeService.kt

## Core Business Rules

### Proof of Addiction
App is selectable only if last 14 days average: ≥45 min/day usage OR ≥20 opens/day. System apps excluded.

### Soft Mode
- Time limit (e.g. 60 min/day) OR session limit (e.g. 5x per day, 5 min each)
- Points: +10 per successful day, +1 bonus per 5 min under limit, 0 on exceeded days
- On limit exceeded: overlay warning every 5 min, app NOT locked, user can dismiss but loses points

### Hard Mode
- Same limits as Soft Mode + money (€5–€50) + duration (7/14/30 days or custom)
- Stripe: pre-auth for ≤7 days, immediate capture + refund-on-success for >7 days
- On limit exceeded: money captured immediately, app COMPLETELY LOCKED rest of day
- Emergency unlock: 6-digit code, money still lost + 50 points deducted
- Money split: 50% donation, 50% service fee

### Blocking Screen
Appears BEFORE tracked app opens. Shows: custom motivation text, usage vs limit, opens vs limit, points today, money at stake (hard mode). Two buttons: "Open anyway" / "Skip it".

### Daily Evaluation
WorkManager at 23:59: check limits, calculate points, create DailyLog, trigger Stripe capture if needed, check challenge end date.

## Navigation Structure
Bottom navigation with 3 tabs: Dashboard, Challenges, Profile. Auth screen shown if user not logged in.

## Coding Conventions
- Kotlin only, Jetpack Compose only, no XML layouts
- StateFlow + collectAsStateWithLifecycle
- Coroutines + Flow, no callbacks
- Hilt for DI, Result<T> for repositories
- Timber for logging, all strings in strings.xml
- Sealed interfaces for UI state (Loading, Success, Error)