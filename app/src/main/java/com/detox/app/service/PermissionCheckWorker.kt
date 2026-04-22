package com.detox.app.service

import android.content.Context
import android.provider.Settings
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.repository.ChallengeRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class PermissionCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val challengeRepository: ChallengeRepository,
    private val cloudFunctionsService: CloudFunctionsService,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "permission_check"
        private const val PREFS = "detox_permission"
        private const val KEY_LOST_AT = "permissionLostAt"
        private const val KEY_IGNORED = "userOpenedAndIgnored"
        private const val DEADLINE_MS = 24 * 60 * 60 * 1_000L
        private const val ACCELERATE_THRESHOLD_MS = 12 * 60 * 60 * 1_000L
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (Settings.canDrawOverlays(applicationContext)) {
            if (prefs.contains(KEY_LOST_AT)) {
                prefs.edit().clear().apply()
                cancelPermissionWarnings()
                NotificationHelper.sendPermissionRestored(applicationContext)
                Timber.d("PermissionCheckWorker: permission restored — clearing failure state")
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
            now
        }

        val elapsed = now - lostAt
        val ignored = prefs.getInt(KEY_IGNORED, 0)

        Timber.d("Permission lost at $lostAt")
        Timber.d("Elapsed: ${elapsed / 3_600_000}h, ignored: $ignored")

        val effectiveDeadlineMs = calculateEffectiveDeadlineMs(elapsed, ignored)

        Timber.d("Effective deadline: ${effectiveDeadlineMs / 3_600_000}h")

        if (elapsed >= effectiveDeadlineMs) {
            Timber.d("Challenge failed: permission missing too long")
            failAllHardChallenges()
            prefs.edit().clear().apply()
            cancelPermissionWarnings()
            NotificationHelper.sendPermissionFailed(applicationContext)
        }

        return Result.success()
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

    private fun cancelPermissionWarnings() {
        WorkManager.getInstance(applicationContext).cancelUniqueWork("permission_warnings")
    }
}
