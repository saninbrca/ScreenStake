package com.detox.app.service

import android.app.usage.UsageStatsManager
import android.content.Context
import android.provider.Settings
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
    }

    override suspend fun doWork(): Result {
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
                // TODO(perm-worker-fail-gate): this sets FAILED below even when capturePayment FAILS
                // (we only log the failure). Unlike the worker/abandon paths, the status flip is not
                // gated on a confirmed capture, so a transient capture error can mark FAILED without the
                // stake being taken. Gate updateChallengeStatus on capture success in a follow-up.
                cloudFunctionsService.capturePayment(paymentIntentId).onFailure { e ->
                    Timber.e(e, "PermissionCheckWorker: capturePayment failed for ${challenge.id}")
                }
            }

            challengeRepository.updateChallengeStatus(challenge.id, ChallengeStatus.FAILED)
                .onFailure { e ->
                    Timber.e(e, "PermissionCheckWorker: status update failed for ${challenge.id}")
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
