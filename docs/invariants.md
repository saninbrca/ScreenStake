# Invariants — Never-Reverse Rules

> **Scope:** The canonical, short list of money-critical and architectural invariants that must NEVER
> be reversed. This file is the **owner** of the invariant list; new invariants are added here.
> **When to load:** ALWAYS load this FIRST (before `00_changelog.md`), every session, before any task.
> _Last verified: 2026-06-22 (commit e287b79)_

> ℹ️ Each rule is a one-liner; the **rationale + history** live in the linked doc / changelog entry.
> If a rule here ever conflicts with code, the code is ground truth — fix the rule and flag it.

---

## Money authority & Stripe capture
1. **Stripe before Firestore, always.** The Stripe money op (capture OR refund) ALWAYS precedes and gates the Firestore status write, on EVERY path — never write COMPLETED before the refund succeeds, nor FAILED before the capture succeeds (win + loss + permission-violation + group settlement). Room is a local mirror only (written synchronously just before the fire-and-forget Firestore sync in `updateChallengeStatus`), NOT a money-authority step. Rules 5/6 are the loss-side specialisation. → [docs/09](09_payout_and_fees.md), [docs/03](03_hard_mode_and_stripe.md), [docs/10](10_security_and_anticheat.md)
2. **Server-side money authority.** Refund/capture decisions are validated SERVER-SIDE; never trust the client's win/loss, clock, refund amount, or PaymentIntent id — re-derive from the stored challenge doc. → [docs/10](10_security_and_anticheat.md), [docs/03](03_hard_mode_and_stripe.md)
3. **Hard Mode create = one CREATE.** A single rules-allowed Firestore CREATE under the unified `challengeId` (same id passed to `createPaymentIntent`); the CF NEVER writes the challenge doc; never mint a second id; the Hard Mode mirror is AWAITED (bounded retry), never fire-and-forget. → [docs/03](03_hard_mode_and_stripe.md)
4. **capturePayment idempotency.** A `success` response ALWAYS means "captured". Counters bump ONLY on a fresh `requires_capture` capture — never on the `succeeded` branch. Non-capturable status → 409. Keep the IDOR guard. → [docs/03](03_hard_mode_and_stripe.md)
5. **Abandon captures SOLO Hard Mode only** (`mode==HARD && groupChallengeId==null && PI!=null`); status→FAILED ONLY after a confirmed capture. NEVER mark FAILED without the stake captured. → [docs/03](03_hard_mode_and_stripe.md)
6. **Capture-gate on ALL Hard Mode loss paths.** FAILED is set ONLY inside `capturePayment.onSuccess` (`DailyEvaluationWorker`, `PermissionCheckWorker`, abandon). On capture FAILURE the challenge stays ACTIVE. The PI-less legacy branch is the only place FAILED is set without a capture. → [docs/03](03_hard_mode_and_stripe.md)
7. **Loss = retain the doc (`markChallengeFailed` CF).** A device-detected loss/abandon does NOT delete the Firestore challenge doc; the CF writes `status:"failed"` + the passed `failReason` + `failedAt` in place, preserves nested `dailyLogs`, is idempotent on terminal docs, and NEVER writes `payoutStatus` or touches Stripe. `failReason` in Room is UX-only, never money logic. → [docs/firestore-schema.md](firestore-schema.md), [docs/03](03_hard_mode_and_stripe.md)
8. **Went-dark = forfeit (heartbeat, FAIL-SAFE).** Active solo Hard Mode writes `permissionStatus/current.lastSeenAt`; `runDueChallengeReconciliation` forfeits a stale device as `failReason:"device_dark"`. A missing/invalid/unreadable `wentDarkGraceMs` ⇒ `Number.MAX_SAFE_INTEGER` ⇒ NEVER forfeit. Triple-gated (`reconciliationEnabled` + `!reconciliationDryRun` + positive grace). → [docs/03](03_hard_mode_and_stripe.md), [docs/13](13_remote_config_and_flags.md)
9. **Payout fee math uses `Math.floor` — never round up.** Canonical rates in [docs/09](09_payout_and_fees.md).

## Data integrity & anti-cheat
10. **dailyLogs tamper-evidence.** `limitExceeded` may NEVER flip true→false; `dailyLogs` deletes are Cloud-Function-only (`allow delete: if false`). → [docs/10](10_security_and_anticheat.md)
11. **Anti-cheat is FLAGGING ONLY.** `detectSuspiciousUsers` never auto-bans or writes user data; a human always reviews. → [docs/10](10_security_and_anticheat.md)
12. **Anti-cheat capture.** `deviceId` (`Settings.Secure.ANDROID_ID`) + `isRooted` are written on EVERY Hard Mode challenge (deviceId also on every group join). Never stop capturing them — the flagging system depends on it. → [docs/10](10_security_and_anticheat.md)
13. **DB encryption.** The Room DB is SQLCipher-encrypted; the passphrase comes from `DatabaseKeyManager` (Keystore-wrapped). NEVER hardcode it. The passphrase key is intentionally NOT `setUserAuthenticationRequired(true)` — background workers must open the DB without a screen unlock. → [docs/10](10_security_and_anticheat.md), [docs/01](01_architecture_and_stack.md)
14. **`pending_hard_challenges` is local-only.** Never synced to Firestore. → [docs/01](01_architecture_and_stack.md)

## Conscious-opens core & overlays
15. **Conscious Opens.** NEVER use `UsageStatsManager` to count opens; only increment when the user taps "Ja, öffnen" in the overlay. → [docs/02](02_core_mechanics_and_soft_mode.md)
16. **Overlays.** ALWAYS use `FLAG_SECURE` and `TYPE_APPLICATION_OVERLAY`; show via `Handler(mainLooper).post{}`. → [docs/05](05_huawei_and_permissions.md), [docs/08](08_ui_design_system.md)
17. **`DateUtils.todayKey()` always** for the DailyLog date key — never inline `86400000`. → [docs/02](02_core_mechanics_and_soft_mode.md)
18. **`endDate = endOfDayMillis` (Last-Day-Loophole fix)** — 23:59:59.999 of the last day, not `now + N×86400000`. → [docs/04](04_group_challenges.md)

## Huawei compatibility
19. **Cloud Functions use `onRequest`** (NEVER `onCall`) for Huawei (no Play Services). → [docs/05](05_huawei_and_permissions.md), [docs/01](01_architecture_and_stack.md)
20. **No FCM push.** WorkManager / AlarmManager only. → [docs/05](05_huawei_and_permissions.md)
21. **Firestore array updates** use `FieldValue.arrayRemove` + `arrayUnion` — NEVER dot notation. → [docs/04](04_group_challenges.md)
22. **`DailyEvaluationWorker` network constraint** (`NetworkType.CONNECTED`) — NEVER remove it (Hard Mode refunds depend on it). → [docs/05](05_huawei_and_permissions.md)

## Lifecycle & config
23. **Logout order.** Clear ALL Room tables BEFORE `Firebase.signOut()`. → [docs/07](07_onboarding_and_auth.md), [docs/01](01_architecture_and_stack.md)
24. **AppConfig is FAIL-OPEN.** A missing config or network error keeps cached/safe defaults and NEVER locks the user out. → [docs/13](13_remote_config_and_flags.md)
25. **Groups disabled at launch via server config** (`config/app.groupChallengeEnabled=false`); the hardcoded `AppConfig` fallback stays `true` to honor fail-open. → [docs/13](13_remote_config_and_flags.md), [docs/04](04_group_challenges.md)
26. **Counters are best-effort.** `FieldValue.increment` only (never read-then-write); a counter failure NEVER blocks or fails a payment/challenge op. → [docs/11](11_admin_dashboard.md), [docs/09](09_payout_and_fees.md)

---

> **Maintenance:** when a new never-reverse rule is decided, add it here (with a link to its
> changelog entry for the dated rationale) AND keep CLAUDE.md §3 in sync.
