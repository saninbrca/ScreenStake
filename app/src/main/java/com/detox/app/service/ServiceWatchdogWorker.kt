package com.detox.app.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.detox.app.domain.repository.ChallengeRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class ServiceWatchdogWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val challengeRepository: ChallengeRepository,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "service_watchdog"
    }

    override suspend fun doWork(): Result {
        val running = isAccessibilityServiceRunning()
        Timber.d("ServiceWatchdogWorker: accessibility service running=$running")
        if (running) return Result.success()

        val hasActive = challengeRepository.getActiveChallengesList()
            .getOrElse { emptyList() }.isNotEmpty()
        if (!hasActive) return Result.success()

        NotificationHelper.createChannels(applicationContext)
        NotificationHelper.sendAccessibilityLost(applicationContext)
        return Result.success()
    }

    private fun isAccessibilityServiceRunning(): Boolean {
        val am = applicationContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return false
        val targetClass = AppDetectionAccessibilityService::class.java.name
        val pkg = applicationContext.packageName
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { info ->
                info.resolveInfo?.serviceInfo?.let { si ->
                    si.packageName == pkg && si.name == targetClass
                } == true
            }
    }
}
