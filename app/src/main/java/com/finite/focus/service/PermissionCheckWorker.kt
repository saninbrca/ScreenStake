package com.finite.focus.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.finite.focus.R
import com.finite.focus.data.remote.firebase.CloudFunctionsService
import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.model.GroupChallengeStatus
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.domain.repository.GroupChallengeRepository
import com.finite.focus.util.DateUtils
import com.finite.focus.util.FeatureFlags
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.flow.first
import timber.log.Timber

@HiltWorker
class PermissionCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val challengeRepository: ChallengeRepository,
    private val cloudFunctionsService: CloudFunctionsService,
    private val groupChallengeRepository: GroupChallengeRepository,
    private val challengeSettlementGuard: ChallengeSettlementGuard,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "permission_check"
        private const val PREFS = "detox_permission"
        private const val KEY_LOST_AT = "permissionLostAt"
        private const val KEY_IGNORED = "userOpenedAndIgnored"
        private const val USAGE_VIOLATION_PREFS = "detox_usage_violation"
        private const val KEY_USAGE_VIOLATION_AT = "usageViolationDetectedAt"
        private const val FOREGROUND_NOTIF_ID = 9101

        // ── Heartbeat ("device went dark = forfeit") ──────────────────────────────
        // A separate prefs file from PREFS so the permission-restore clear() (which wipes
        // PREFS) never erases the last-beat timestamp used for the best-effort throttle nudge.
        private const val HEARTBEAT_PREFS = "detox_heartbeat"
        private const val KEY_LAST_BEAT_AT = "lastBeatAt"
        // Best-effort local nudge fired at ~half the server-side GRACE (server default 72h →
        // warn at 36h) when EMUI has clearly throttled this worker since its last successful
        // beat. The server config/app.wentDarkGraceMs is authoritative for the actual forfeit;
        // this is only an early "open the app to stay safe" reminder and is purely device-side.
        private const val HEARTBEAT_WARN_THRESHOLD_MS = 36L * 60 * 60 * 1_000L
    }

    /**
     * Required when WorkManager runs this worker as a foreground/expedited job (e.g. when it
     * resumes pending work after connectivity returns on some EMUI/Android versions). Without
     * this override CoroutineWorker throws IllegalStateException("Not implemented"). Reuses the
     * existing [NotificationHelper.CHANNEL_REMINDERS] channel — no schedule/logic change.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        NotificationHelper.createChannels(applicationContext)
        val notification = NotificationCompat.Builder(
            applicationContext, NotificationHelper.CHANNEL_REMINDERS
        )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.notification_worker_checking_permissions))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        return ForegroundInfo(FOREGROUND_NOTIF_ID, notification)
    }

    override suspend fun doWork(): Result {
        // Heartbeat FIRST — before any early return below — so an installed, honest device
        // proves liveness every cycle even when overlay permission is fine (the early
        // `return Result.success()` path). The server treats a stale/absent heartbeat as a
        // went-dark forfeit (uninstall/disable), so this write is what keeps honest users safe.
        writeHeartbeatIfHardActive()

        // Unattended half of the blocked-promotion retry (the primary hook is
        // MainActivity.onResume). Runs BEFORE the early `return Result.success()` below so a
        // healthy device still recovers enforcement. Best-effort by nature: on Android 12+ this
        // start is only legal when the app holds an FGS-start exemption — which the enforcement
        // population does, since blocking overlays require SYSTEM_ALERT_WINDOW. When it is not
        // legal the attempt is absorbed and re-latched, never a crash.
        retryBlockedForegroundStart()

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        checkAccessibilityPermission()
        checkAndReportUsageViolation()
        checkExpiredGroupChallenges()

        if (Settings.canDrawOverlays(applicationContext)) {
            if (prefs.contains(KEY_LOST_AT)) {
                prefs.edit().clear().apply()
                cancelPermissionWarnings()
                Timber.d("PermissionCheckWorker: permission restored — clearing failure state")
                // Mirror restore to Firestore so server knows the deadline is cancelled
                FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                    FirebaseFirestore.getInstance()
                        .collection("users").document(uid)
                        .collection("permissionStatus").document("current")
                        .update(mapOf(
                            "permissionLostAt" to null,
                            "permissionRestoredAt" to System.currentTimeMillis()
                        ))
                }
            }
            return Result.success()
        }

        // Permission missing — set timestamp if not already recorded
        val now = System.currentTimeMillis()
        val lostAt = if (prefs.contains(KEY_LOST_AT)) {
            prefs.getLong(KEY_LOST_AT, now)
        } else {
            prefs.edit()
                .putLong(KEY_LOST_AT, now)
                .putInt(KEY_IGNORED, 0)
                .apply()
            // Mirror to Firestore so server can capture if app is later uninstalled
            val accessibilityEnabled = isAccessibilityServiceEnabled()
            val missingPermission = when {
                !Settings.canDrawOverlays(applicationContext) && !accessibilityEnabled -> "both"
                !Settings.canDrawOverlays(applicationContext) -> "overlay"
                else -> "accessibility"
            }
            FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                FirebaseFirestore.getInstance()
                    .collection("users").document(uid)
                    .collection("permissionStatus").document("current")
                    .set(buildPermissionLostUpdate(now, missingPermission), SetOptions.merge())
            }
            now
        }

        val elapsed = now - lostAt
        val ignored = prefs.getInt(KEY_IGNORED, 0)

        Timber.d("Permission lost at $lostAt")
        Timber.d("Elapsed: ${elapsed / 3_600_000}h, ignored: $ignored")

        val permissionType = when {
            !Settings.canDrawOverlays(applicationContext) && !isAccessibilityServiceEnabled() -> "both"
            !Settings.canDrawOverlays(applicationContext) -> "overlay"
            else -> "accessibility"
        }
        Sentry.addBreadcrumb(Breadcrumb().apply {
            category = "Permissions"
            message = "Permission loss detected"
            level = SentryLevel.WARNING
            setData("permissionType", permissionType)
            setData("elapsed", elapsed)
        })

        // ONE snapshot per cycle, read BEFORE any fail pass (afterwards the rows this affects are
        // gone from the active list). It drives three things that must never disagree about who is
        // affected: which escalation copy is true, whether the deadline may be halved, and what
        // actually gets failed.
        val activeChallenges = challengeRepository.getActiveChallengesList().getOrElse { e ->
            Timber.e(e, "PermissionCheckWorker: could not load active challenges")
            emptyList()
        }
        val audience = permissionAudience(activeChallenges)

        val effectiveDeadlineMs = calculateEffectiveDeadlineMs(elapsed, ignored, audience.hasHard)

        Timber.d("Effective deadline: ${effectiveDeadlineMs / 3_600_000}h (audience=$audience)")

        // Staged escalation notifications, addressed per challenge type. Hard keeps its stake
        // wording; Soft gets money-free copy in the "will fail" direction. Both are posted when the
        // user holds both (separate notification ids), exactly as the deadline notices below do —
        // fixing the Soft copy must never mute the Hard warning. A user with NO active challenge
        // now gets nothing, because there is nothing to warn them about.
        val stage = when {
            elapsed >= 23 * 3_600_000L -> "23h"
            elapsed >= 12 * 3_600_000L -> "12h"
            elapsed >= 6  * 3_600_000L -> "6h"
            else -> null
        }
        if (stage != null) {
            if (audience.hasHard) NotificationHelper.sendPermissionEscalation(applicationContext, stage)
            if (audience.hasSoft) NotificationHelper.sendPermissionEscalationSoft(applicationContext, stage)
        }

        if (elapsed >= effectiveDeadlineMs) {
            Timber.d("Permission deadline reached: permission missing too long")

            val softOutcome = failAllSoftChallenges(activeChallenges, lostAt, now)
            failAllHardChallenges()
            prefs.edit().clear().apply()
            cancelPermissionWarnings()

            // Tell each challenge type the truth about ITSELF. This used to be one unconditional
            // "❌ Challenge failed — your stake has been charged", which was false for everyone
            // except a solo Hard holder.
            //
            // Several can be posted in the same pass on purpose — Hard and Soft challenges coexist
            // freely (creation only blocks re-using the same PACKAGE, see the wizard's
            // conflictingPackages), and each message is then independently true. The two Soft
            // notices are mutually exclusive PER CHALLENGE but not per user: someone holding two
            // Soft challenges can genuinely have one failed (they used its apps) and one survived.
            if (audience.hasHard) {
                NotificationHelper.sendPermissionFailed(applicationContext)
            }
            if (softOutcome.failed.isNotEmpty()) {
                NotificationHelper.sendPermissionSoftFailed(applicationContext)
            }
            if (softOutcome.survived.isNotEmpty()) {
                NotificationHelper.sendPermissionEnforcementPaused(applicationContext)
            }
            if (!audience.hasHard && softOutcome.failed.isEmpty() && softOutcome.survived.isEmpty()) {
                Timber.d("PermissionCheckWorker: deadline reached with no affected challenge — no notice")
            }
        }

        return Result.success()
    }

    /**
     * Writes `lastSeenAt = now` into `users/{uid}/permissionStatus/current` (owner-writable,
     * merge) to prove the app is still installed and running. Gated on "user has >= 1 active
     * HARD challenge" so Soft-only / idle users never trigger needless writes — the went-dark
     * forfeit only concerns money-staked Hard Mode.
     *
     * Also fires a best-effort throttle nudge: if EMUI suspended this worker for longer than
     * [HEARTBEAT_WARN_THRESHOLD_MS] since the last successful beat, warn the user to open the
     * app before the server forfeits the stake. (Necessarily best-effort — it can only fire
     * once the throttled worker finally runs again.)
     */
    private suspend fun writeHeartbeatIfHardActive() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val hasActiveHard = challengeRepository.getActiveChallengesList()
            .getOrElse {
                Timber.w(it, "writeHeartbeatIfHardActive: could not load challenges — skipping beat")
                return
            }
            .any { it.mode == ChallengeMode.HARD }
        if (!hasActiveHard) return

        val now = System.currentTimeMillis()

        // Best-effort throttle warning based on the locally-stored previous beat.
        val hbPrefs = applicationContext.getSharedPreferences(HEARTBEAT_PREFS, Context.MODE_PRIVATE)
        val prevBeat = hbPrefs.getLong(KEY_LAST_BEAT_AT, 0L)
        if (prevBeat > 0L && (now - prevBeat) > HEARTBEAT_WARN_THRESHOLD_MS) {
            Timber.w("writeHeartbeatIfHardActive: throttled ${(now - prevBeat) / 3_600_000}h since last beat — nudging user")
            NotificationHelper.createChannels(applicationContext)
            NotificationHelper.sendHeartbeatWarning(applicationContext)
        }
        hbPrefs.edit().putLong(KEY_LAST_BEAT_AT, now).apply()

        // Fire-and-forget merge, matching the other permissionStatus writes in this worker.
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("permissionStatus").document("current")
            .set(mapOf("lastSeenAt" to now), SetOptions.merge())
        Timber.d("writeHeartbeatIfHardActive: lastSeenAt=$now written")
    }

    /**
     * Retries a foreground promotion the platform refused, gated on there still being something to
     * enforce. No-ops on the normal path — one boolean prefs read, no Room query.
     */
    private suspend fun retryBlockedForegroundStart() {
        if (!UsageTrackingService.isForegroundStartPending(applicationContext)) return
        Timber.d("PermissionCheckWorker: foreground start pending — retrying")
        UsageTrackingService.startIfActiveChallenge(applicationContext, challengeRepository)
    }

    private fun isAccessibilityServiceEnabled(): Boolean =
        Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(applicationContext.packageName) == true

    /**
     * Builds the permission-lost mirror written to `permissionStatus/current`. The anti-cheat
     * `deviceId` (ANDROID_ID) is a Hard-Mode multi-account signal for the server-side
     * `checkPermissionViolations` capture; it has NO consumer in a soft-only build (no money to
     * protect, and the capture CF only acts on `mode=="hard"`). So it is included ONLY when money
     * features are enabled — a soft-only release never collects the device identifier here. The
     * `permissionLostAt`/`permissionType` markers are always written: they are harmless timestamps
     * and the server acts on them solely for Hard challenges. Restoring money (flip the build flag)
     * resumes the exact prior write.
     */
    private fun buildPermissionLostUpdate(lostAt: Long, permissionType: String): Map<String, Any?> =
        buildMap {
            put("permissionLostAt", lostAt)
            put("permissionType", permissionType)
            if (FeatureFlags.moneyEnabled) {
                put(
                    "deviceId",
                    Settings.Secure.getString(
                        applicationContext.contentResolver, Settings.Secure.ANDROID_ID
                    )
                )
            }
        }

    private suspend fun checkAndReportUsageViolation() {
        // Only relevant when accessibility is disabled — it's the backup detection path.
        // If accessibility is working, blocked apps can't be opened, so nothing to check.
        if (isAccessibilityServiceEnabled()) {
            applicationContext
                .getSharedPreferences(USAGE_VIOLATION_PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
            return
        }

        val challenges = challengeRepository.getActiveChallengesList().getOrElse {
            Timber.w(it, "checkAndReportUsageViolation: could not load challenges")
            return
        }
        val blockedPackages = challenges.flatMap { it.appPackageNames }.filter { it.isNotBlank() }
        if (blockedPackages.isEmpty()) return

        val violatingPackage = detectUsageViolation(blockedPackages) ?: return

        val violationPrefs = applicationContext
            .getSharedPreferences(USAGE_VIOLATION_PREFS, Context.MODE_PRIVATE)
        if (violationPrefs.contains(KEY_USAGE_VIOLATION_AT)) return // already reported

        val now = System.currentTimeMillis()
        violationPrefs.edit().putLong(KEY_USAGE_VIOLATION_AT, now).apply()

        val usageStatsManager = applicationContext
            .getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST, now - 3_600_000L, now
        )
        val usageMinutes = stats
            .firstOrNull { it.packageName == violatingPackage }
            ?.totalTimeInForeground
            ?.div(60_000L) ?: 0L

        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("permissionStatus").document("current")
                .set(
                    mapOf(
                        "usageViolationDetectedAt" to now,
                        "violatingPackage" to violatingPackage,
                        "usageMinutes" to usageMinutes
                    ),
                    SetOptions.merge()
                )
            Timber.d("checkAndReportUsageViolation: violation written — pkg=$violatingPackage usage=${usageMinutes}min")
        }

        NotificationHelper.createChannels(applicationContext)
        NotificationHelper.sendUsageViolationDetected(applicationContext, getAppDisplayName(violatingPackage))
    }

    private fun detectUsageViolation(blockedPackages: List<String>): String? = try {
        val usageStatsManager = applicationContext
            .getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST, now - 3_600_000L, now
        )
        stats.firstOrNull { stat ->
            stat.packageName in blockedPackages && stat.totalTimeInForeground > 60_000L
        }?.packageName
    } catch (e: Exception) {
        Timber.e(e, "detectUsageViolation: failed")
        null
    }

    private fun getAppDisplayName(packageName: String): String = try {
        applicationContext.packageManager
            .getApplicationLabel(
                applicationContext.packageManager.getApplicationInfo(packageName, 0)
            )
            .toString()
    } catch (e: Exception) {
        packageName
    }

    /**
     * The deadline this cycle enforces. Delegates to [effectiveDeadlineMs] — kept as an instance
     * method so the worker reads the same way it always did; the logic lives at file scope so it is
     * unit-testable without a WorkManager harness.
     */
    private fun calculateEffectiveDeadlineMs(elapsed: Long, ignored: Int, hardInPlay: Boolean): Long =
        effectiveDeadlineMs(elapsed, ignored, hardInPlay)

    private suspend fun failAllHardChallenges() {
        val challenges = challengeRepository.getActiveChallengesList().getOrElse { e ->
            Timber.e(e, "PermissionCheckWorker: could not load active challenges")
            return
        }

        for (challenge in challenges) {
            // Solo Hard Mode ONLY. Group shadow rows are routed out here, exactly as
            // DailyEvaluationWorker routes them out before its Hard path — see
            // [isSoloHardPermissionFailEligible] for why the previous mode-only filter was a
            // double-capture landmine.
            if (!isSoloHardPermissionFailEligible(challenge)) continue

            val paymentIntentId = challenge.stripePaymentIntentId
            if (paymentIntentId != null) {
                // ── Money-safety gate ────────────────────────────────────────────────
                // Defer to the server before capturing: if it already settled this challenge,
                // the guard pulls the terminal status into Room (DAO-only) and we skip; if the
                // server state can't be confirmed (offline), we leave it active for next cycle.
                // The server reconciliation / checkPermissionViolations net is the backstop.
                if (challengeSettlementGuard.resolveBeforeLocalSettlement(challenge.id)
                    == SettlementDecision.SKIP) {
                    Timber.w(
                        "PermissionCheckWorker: '${challenge.id}' deferred to server settlement — " +
                                "skipping local capture/fail"
                    )
                    continue
                }

                // Mirror DailyEvaluationWorker's loss path: flip FAILED ONLY after a confirmed
                // capture. Result.success means the stake WAS taken (fresh capture OR an
                // already-captured `succeeded` PI). Result.failure — 409 not-capturable, Stripe
                // 5xx, or offline — means NO money was taken; leave the challenge ACTIVE for the
                // next cycle / server reconciliation net. NEVER mark FAILED here on a failed
                // capture: updateChallengeStatus(FAILED) calls the markChallengeFailed CF, which
                // writes status:"failed" on the Firestore doc (doc + dailyLogs retained), so that
                // would record a loss without the stake being captured.
                cloudFunctionsService.capturePayment(paymentIntentId)
                    .onSuccess {
                        challengeRepository.updateChallengeStatus(challenge.id, ChallengeStatus.FAILED, "permission_violation")
                            .onFailure { e ->
                                Timber.e(e, "PermissionCheckWorker: status update failed for ${challenge.id}")
                            }
                    }
                    .onFailure { e ->
                        Timber.e(e, "PermissionCheckWorker: capturePayment failed for ${challenge.id} — leaving ACTIVE")
                    }
            } else {
                // No PaymentIntent on this Hard doc (malformed/legacy) — out of scope for the
                // capture gate; preserve prior behavior and mark FAILED.
                challengeRepository.updateChallengeStatus(challenge.id, ChallengeStatus.FAILED, "permission_violation")
                    .onFailure { e ->
                        Timber.e(e, "PermissionCheckWorker: status update failed for ${challenge.id}")
                    }
            }
        }
    }

    /**
     * Soft counterpart to [failAllHardChallenges], run on the SAME deadline branch and the same
     * [effectiveDeadlineMs]. Money-free end to end: no `capturePayment`, no
     * [ChallengeSettlementGuard], no Stripe, no server call.
     *
     * The settlement guard is deliberately absent. It exists to defer to a SERVER settlement before
     * a CLIENT capture; there is no server settlement for Soft (`runPermissionViolationCheck` skips
     * `mode !== "hard"`). Adding it would put a Firestore read on a money-free path and — worse —
     * make the fail unable to land while offline, which is the one property the write path was
     * chosen for.
     *
     * Two guards stand between an eligible challenge and a FAILED write:
     *
     *  1. **Already ended** ⇒ skip. A Soft challenge whose end date passed while the app was closed
     *     is still `active` in Room (the worker was EMUI-throttled, the app was not opened). Failing
     *     it here would stamp a fabricated loss on a challenge the user had already WON. It belongs
     *     to `SettleEndedSoftChallengesUseCase`, which settles it on its real verdict. Uses
     *     [DateUtils.hasPassedEnd] (`>`, strictly after the last day) behind an [DateUtils.isOpenEnded]
     *     check, exactly as the enforcement-stop path does — open-ended challenges never end and so
     *     stay eligible.
     *  2. **No usage evidence** ⇒ survive. See [usedBlockedPackagesInWindow].
     *
     * Website / adult-block challenges (`appPackageNames` empty — `BlockingType.WEBSITE` persists no
     * packages) have NO observable package, so guard 2 has nothing to look at. They fall back to the
     * time-only rule and fail on the deadline alone. This is disclosed up front: the Soft info step
     * shows those users a different permission row than app-target users, saying exactly that.
     */
    private suspend fun failAllSoftChallenges(
        challenges: List<Challenge>,
        permissionLostAt: Long,
        now: Long,
    ): SoftDeadlineOutcome {
        val failed = mutableListOf<String>()
        val survived = mutableListOf<String>()

        for (challenge in challenges) {
            if (!isSoloSoftPermissionFailEligible(challenge)) continue

            if (!DateUtils.isOpenEnded(challenge.startDate, challenge.endDate) &&
                DateUtils.hasPassedEnd(challenge.endDate, now)
            ) {
                Timber.d(
                    "failAllSoftChallenges: '${challenge.id}' already past its end date — " +
                            "leaving it for settlement, not failing it"
                )
                continue
            }

            val watched = challenge.appPackageNames.filter { it.isNotBlank() }.toSet()
            val hasEvidence = if (watched.isEmpty()) {
                // No observable package (website / adult-block): time-only rule.
                true
            } else {
                usedBlockedPackagesInWindow(
                    applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager,
                    watched,
                    permissionLostAt,
                    now,
                )
            }

            if (!hasEvidence) {
                Timber.i(
                    "failAllSoftChallenges: '${challenge.id}' unprotected but its apps were never " +
                            "opened in the window — NOT failing"
                )
                survived += challenge.id
                continue
            }

            challengeRepository
                .updateChallengeStatus(challenge.id, ChallengeStatus.FAILED, "permission_violation")
                .onSuccess {
                    Timber.i("failAllSoftChallenges: '${challenge.id}' → FAILED (permission_violation)")
                    failed += challenge.id
                }
                .onFailure { e ->
                    Timber.e(e, "failAllSoftChallenges: status update failed for ${challenge.id}")
                }
        }

        return SoftDeadlineOutcome(failed = failed, survived = survived)
    }

    private suspend fun checkAccessibilityPermission() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        Timber.d("Accessibility check: enabled=$accessibilityEnabled")

        val accessibilityPrefs = applicationContext.getSharedPreferences("detox_accessibility", Context.MODE_PRIVATE)

        if (!accessibilityEnabled) {
            val hasActive = challengeRepository.getActiveChallengesList()
                .getOrElse { emptyList() }.isNotEmpty()
            if (hasActive && !accessibilityPrefs.contains("accessibilityLostAt")) {
                accessibilityPrefs.edit().putLong("accessibilityLostAt", System.currentTimeMillis()).apply()
                Timber.d("Accessibility lost — timer started")

                // Mirror to Firestore so server-side CF can capture after 24h
                FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                    FirebaseFirestore.getInstance()
                        .collection("users").document(uid)
                        .collection("permissionStatus").document("current")
                        .set(
                            buildPermissionLostUpdate(System.currentTimeMillis(), "accessibility"),
                            SetOptions.merge()
                        )
                }
            }
        } else {
            if (accessibilityPrefs.contains("accessibilityLostAt")) {
                accessibilityPrefs.edit().clear().apply()
                Timber.d("Accessibility restored — cleared timer")

                // Clear Firestore timer so server does not capture after restore
                FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                    FirebaseFirestore.getInstance()
                        .collection("users").document(uid)
                        .collection("permissionStatus").document("current")
                        .set(
                            mapOf(
                                "permissionLostAt" to FieldValue.delete(),
                                "permissionRestoredAt" to System.currentTimeMillis()
                            ),
                            SetOptions.merge()
                        )
                }
            }
        }
    }

    private fun cancelPermissionWarnings() {
        WorkManager.getInstance(applicationContext).cancelUniqueWork("permission_warnings")
    }

    private suspend fun checkExpiredGroupChallenges() {
        val now = System.currentTimeMillis()
        val groups = runCatching { groupChallengeRepository.getGroupChallenges().first() }
            .getOrElse {
                Timber.w(it, "checkExpiredGroupChallenges: failed to load group challenges")
                return
            }
        groups
            .filter { it.status == GroupChallengeStatus.ACTIVE && it.endDate in 1..now }
            .forEach { gc ->
                Timber.d("Group challenge expired: ${gc.groupId} endDate=${gc.endDate} now=$now")
                cloudFunctionsService.completeGroupChallenge(gc.groupId)
            }
    }
}

/**
 * Selection predicate for the 24h-permission-loss capture path (`failAllHardChallenges`):
 * **SOLO Hard Mode only** — `mode == HARD && groupChallengeId == null`. Same intent as invariant
 * #5 (abandon) and as [DailyEvaluationWorker], which routes group shadow rows out before its Hard
 * path.
 *
 * Why the `groupChallengeId == null` half is load-bearing: a group shadow row
 * (`id = "group_<groupId>"`, written by `GroupChallengeRepositoryImpl.syncToTracking`) carries
 * `mode = "hard"` and the participant's **buy-in** PaymentIntent, so a mode-only filter puts it
 * straight into the capture branch. Nothing today depends on that — group permission-loss
 * enforcement belongs to the group settlement CFs (`failParticipant`), never to this worker.
 *
 * Before this predicate the only thing stopping a group capture was an accident:
 * [ChallengeSettlementGuard] reads `users/{uid}/challenges/group_<groupId>`, that doc does not
 * exist (group state lives in the top-level `groupChallenges` collection), and the fail-safe
 * "unconfirmed" branch returns SKIP. The day anything materializes that parent doc (a sync, a
 * debug tool, a schema change) the skip flips into: capture the buy-in + mark the shadow FAILED,
 * while the group doc still lists the participant as active — so `completeGroupChallenge` later
 * refunds them as a winner (captured stake + refund + pot share). The exclusion is now explicit,
 * not incidental.
 */
internal fun isSoloHardPermissionFailEligible(challenge: Challenge): Boolean =
    challenge.mode == ChallengeMode.HARD && challenge.groupChallengeId == null

/**
 * Selection predicate for the Soft side of the permission deadline (`failAllSoftChallenges`):
 * **SOLO Soft Mode only**.
 *
 * Mirrors [isSoloHardPermissionFailEligible] clause for clause, with one addition:
 * `stripePaymentIntentId == null`. That clause is the money fence. This path is money-free by
 * construction — it never calls `capturePayment` and never consults [ChallengeSettlementGuard] —
 * so a row that somehow carries a PaymentIntent must not be able to reach it and record a loss
 * while the stake sits uncaptured. Same guard, same reason, as
 * `SettleEndedSoftChallengesUseCase`'s Soft-only filter.
 *
 * `groupChallengeId == null` keeps group shadow rows with the group settlement CFs, exactly as on
 * the Hard side.
 */
internal fun isSoloSoftPermissionFailEligible(challenge: Challenge): Boolean =
    challenge.mode == ChallengeMode.SOFT &&
            challenge.groupChallengeId == null &&
            challenge.stripePaymentIntentId == null

/** Who is affected by this permission-loss window — see [permissionAudience]. */
internal data class PermissionAudience(
    /** At least one solo Hard challenge is in play: money is at risk, stake wording is true. */
    val hasHard: Boolean,
    /** At least one solo Soft challenge is in play: money-free "will fail" wording is true. */
    val hasSoft: Boolean,
)

/**
 * Resolves who the permission-loss messaging is actually addressed to, from the challenges that
 * were active when the cycle ran.
 *
 * Built on the two fail-selection predicates rather than a second set of mode checks, so the
 * warnings a user receives can never describe a different population than the one the deadline
 * actually acts on. Both flags can be true at once and both message sets are then sent: Hard and
 * Soft challenges coexist freely (creation only rejects re-using the same PACKAGE), so the Soft
 * copy must never mute the Hard copy.
 *
 * Group shadow rows are excluded from BOTH: their permission handling belongs to the group
 * settlement CFs.
 */
internal fun permissionAudience(challenges: List<Challenge>) = PermissionAudience(
    hasHard = challenges.any { isSoloHardPermissionFailEligible(it) },
    hasSoft = challenges.any { isSoloSoftPermissionFailEligible(it) },
)

/**
 * The deadline a permission-loss window is judged against.
 *
 * The dismissal-halving is **Hard-only**. It exists as an anti-cheat accelerator: a user who keeps
 * opening the app while the permission is off is assumed to be stalling, so the remaining grace is
 * halved. But [MainActivity.trackPermissionIgnore] counts *"opened the app while the permission was
 * off"* — NOT *"saw the warning and refused"*. On EMUI/Huawei, where the OS itself kills the
 * accessibility service, a Soft user who opens the app to find out what happened would have their
 * grace cut to as little as ~12h for something they did not do, and Soft has no stake to justify
 * the trade. A Hard user accepted that trade at creation (the uninstall-forfeit consent) and there
 * is real money to protect, so Hard keeps it unchanged.
 *
 * When BOTH are in play there is still exactly ONE deadline, and it is the Hard one — Soft rides
 * along on the halved window. That is deliberate: two deadlines would mean two `permissionLostAt`
 * clocks and two prefs-clear points, which is precisely the kind of divergence this worker has
 * been consolidated to avoid.
 */
internal fun effectiveDeadlineMs(elapsed: Long, ignored: Int, hardInPlay: Boolean): Long =
    if (hardInPlay && ignored >= 1 && elapsed < PERMISSION_ACCELERATE_THRESHOLD_MS) {
        // Cut the REMAINING time in half to accelerate the deadline.
        elapsed + (PERMISSION_DEADLINE_MS - elapsed) / 2
    } else {
        PERMISSION_DEADLINE_MS
    }

internal const val PERMISSION_DEADLINE_MS = 24 * 60 * 60 * 1_000L
internal const val PERMISSION_ACCELERATE_THRESHOLD_MS = 12 * 60 * 60 * 1_000L

/** What the Soft pass actually did, per challenge — see [PermissionCheckWorker.failAllSoftChallenges]. */
internal data class SoftDeadlineOutcome(
    /** Ids marked FAILED: evidence showed the blocked apps were used while unprotected. */
    val failed: List<String>,
    /** Ids left ACTIVE: eligible and still running, but no evidence of use. */
    val survived: List<String>,
)

/**
 * The fairness gate for the Soft permission-fail: did the user actually USE a blocked package
 * during the unprotected window?
 *
 * The exploit being closed is "turn the permission off, use the blocked app freely, still win" —
 * and that exploit *requires usage*. Failing on elapsed time alone would also punish the honest
 * EMUI case, where the OS kills the accessibility service on its own and the user never touches
 * the blocked app. This predicate is what separates the two.
 *
 * Counts `ACTIVITY_RESUMED` events for the challenge's OWN packages, over exactly
 * [since]..[now] — the unprotected window. `queryEvents` is used rather than `queryUsageStats`
 * because event streams are clipped to the requested range, whereas `queryUsageStats` returns
 * whole DAILY buckets that can spill usage from BEFORE the permission was lost into the window and
 * manufacture evidence against a user who stopped in time.
 *
 * FAIL-SAFE in the honest direction, matching invariant #8's stance on missing data: a thrown
 * query, a revoked `PACKAGE_USAGE_STATS` grant, or an empty stream all read as "no evidence" and
 * the challenge SURVIVES. Unreadable evidence must never manufacture a loss.
 *
 * Note this is NOT the `USAGE_VIOLATION_PREFS` latch written by
 * [PermissionCheckWorker.checkAndReportUsageViolation]. That latch is the right *signal* but the
 * wrong *shape* to gate on: it is global rather than per-challenge (it would fail an innocent
 * challenge because a different challenge's app was used — the exact unfairness this gate exists
 * to remove), it only ever runs when ACCESSIBILITY is off (so an overlay-only revocation records
 * nothing), it latches once and never updates, and it is cleared on every cycle in which
 * accessibility is enabled. This queries the same underlying UsageStats source directly, scoped to
 * one challenge and one window.
 */
internal fun usedBlockedPackagesInWindow(
    usageStatsManager: UsageStatsManager,
    watchedPackages: Set<String>,
    since: Long,
    now: Long,
): Boolean = hasUsageEvidence(watchedPackages, since, now) {
    queryForegroundedPackages(usageStatsManager, since, now)
}

/**
 * The decision half of [usedBlockedPackagesInWindow], split out from the framework query so the
 * rule that costs a user their challenge is unit-testable without an Android runtime.
 *
 * [foregroundedPackages] is evaluated LAZILY and only once every cheap guard has passed, so an
 * ineligible window never issues a usage query.
 */
internal fun hasUsageEvidence(
    watchedPackages: Set<String>,
    since: Long,
    now: Long,
    foregroundedPackages: () -> Sequence<String>,
): Boolean {
    if (watchedPackages.isEmpty()) return false
    // A non-positive or inverted window is unusable (a clock change, or prefs written this cycle).
    // Treat it as "no evidence" rather than querying an undefined range.
    if (since <= 0L || now <= since) return false
    return foregroundedPackages().any { it in watchedPackages }
}

/**
 * Packages brought to the foreground in [since]..[now]. Returns an EMPTY sequence on any failure —
 * a thrown query or a revoked `PACKAGE_USAGE_STATS` grant reads as "no evidence", never as a loss.
 */
private fun queryForegroundedPackages(
    usageStatsManager: UsageStatsManager,
    since: Long,
    now: Long,
): Sequence<String> = try {
    val events = usageStatsManager.queryEvents(since, now)
    sequence {
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                yield(event.packageName)
            }
        }
    }
} catch (e: Exception) {
    Timber.e(e, "queryForegroundedPackages: usage query failed — treating as NO evidence")
    emptySequence()
}
