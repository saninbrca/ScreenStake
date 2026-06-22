package com.detox.app.service

import android.app.usage.UsageStatsManager
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.detox.app.R
import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.GroupChallengeRepository
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
        private const val DEADLINE_MS = 24 * 60 * 60 * 1_000L
        private const val ACCELERATE_THRESHOLD_MS = 12 * 60 * 60 * 1_000L
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
            .setContentText("Berechtigungen werden geprüft…")
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
            val deviceId = Settings.Secure.getString(
                applicationContext.contentResolver, Settings.Secure.ANDROID_ID
            )
            FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                FirebaseFirestore.getInstance()
                    .collection("users").document(uid)
                    .collection("permissionStatus").document("current")
                    .set(mapOf(
                        "permissionLostAt" to now,
                        "permissionType" to missingPermission,
                        "deviceId" to deviceId
                    ), SetOptions.merge())
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

        val effectiveDeadlineMs = calculateEffectiveDeadlineMs(elapsed, ignored)

        Timber.d("Effective deadline: ${effectiveDeadlineMs / 3_600_000}h")

        // Send staged escalation notifications based on elapsed time
        when {
            elapsed >= 23 * 3_600_000L -> NotificationHelper.sendPermissionEscalation(applicationContext, "23h")
            elapsed >= 12 * 3_600_000L -> NotificationHelper.sendPermissionEscalation(applicationContext, "12h")
            elapsed >= 6  * 3_600_000L -> NotificationHelper.sendPermissionEscalation(applicationContext, "6h")
        }

        if (elapsed >= effectiveDeadlineMs) {
            Timber.d("Challenge failed: permission missing too long")
            failAllHardChallenges()
            prefs.edit().clear().apply()
            cancelPermissionWarnings()
            NotificationHelper.sendPermissionFailed(applicationContext)
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

    private fun isAccessibilityServiceEnabled(): Boolean =
        Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(applicationContext.packageName) == true

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

    private fun calculateEffectiveDeadlineMs(elapsed: Long, ignored: Int): Long {
        // If user has already ignored a warning and we're still within the first 12h,
        // cut the remaining time in half to accelerate the deadline.
        return if (ignored >= 1 && elapsed < ACCELERATE_THRESHOLD_MS) {
            val remaining = (DEADLINE_MS - elapsed) / 2
            elapsed + remaining
        } else {
            DEADLINE_MS
        }
    }

    private suspend fun failAllHardChallenges() {
        val challenges = challengeRepository.getActiveChallengesList().getOrElse { e ->
            Timber.e(e, "PermissionCheckWorker: could not load active challenges")
            return
        }

        for (challenge in challenges) {
            if (challenge.mode != ChallengeMode.HARD) continue

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
                            mapOf(
                                "permissionLostAt" to System.currentTimeMillis(),
                                "permissionType" to "accessibility",
                                "deviceId" to Settings.Secure.getString(
                                    applicationContext.contentResolver,
                                    Settings.Secure.ANDROID_ID
                                )
                            ),
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
