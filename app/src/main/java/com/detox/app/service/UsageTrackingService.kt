package com.detox.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.detox.app.R
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.UsageStatsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class UsageTrackingService : Service() {

    companion object {
        private const val CHANNEL_ID = "detox_tracking"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, UsageTrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsageTrackingService::class.java))
        }
    }

    @Inject
    lateinit var challengeRepository: ChallengeRepository

    @Inject
    lateinit var overlayManager: OverlayManager

    @Inject
    lateinit var usageStatsRepository: UsageStatsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Tracks which apps have already had their 80% notification fired today
    // so we don't spam the user. Cleared at midnight along with the overlay state.
    private val alerted80PercentApps = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        Timber.d("UsageTrackingService created")
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        serviceScope.launch {
            challengeRepository.getActiveChallenges().collect { challenges ->
                val packageNames = challenges.map { it.appPackageName }.toSet()
                TrackedAppEventBus.updateTrackedPackages(packageNames)
                Timber.d("Updated tracked packages: $packageNames")
            }
        }

        overlayManager.startListening(serviceScope)
        startUsagePolling()
        scheduleMidnightAlertReset()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        overlayManager.stopListening()
        serviceScope.cancel()
        Timber.d("UsageTrackingService destroyed")
    }

    // ── 80% usage polling ──────────────────────────────────────────────────────

    /**
     * Checks every 15 minutes whether any active challenge has reached 80% of
     * its daily limit.  Fires [NotificationHelper.sendUsage80Percent] at most
     * once per app per calendar day — the [alerted80PercentApps] set is cleared
     * at midnight by [scheduleMidnightAlertReset].
     *
     * 15-minute granularity is a good trade-off: fine enough that the user gets
     * warned before they hit 100%, coarse enough to avoid unnecessary wakeups.
     * WorkManager is NOT used here because we need live access to the
     * [UsageStatsRepository] already injected into the foreground service.
     */
    private fun startUsagePolling() {
        serviceScope.launch(Dispatchers.IO) {
            Timber.d("UsageTrackingService: 80%% usage polling started (15-min interval)")
            while (isActive) {
                delay(15 * 60 * 1_000L) // 15 minutes
                checkUsageThresholds()
            }
        }
    }

    private suspend fun checkUsageThresholds() {
        val challenges = runCatching {
            challengeRepository.getActiveChallengesList().getOrThrow()
        }.getOrElse { e ->
            Timber.w(e, "UsageTrackingService: could not load challenges for threshold check")
            return
        }

        for (challenge in challenges) {
            if (alerted80PercentApps.contains(challenge.appPackageName)) continue

            val usage = runCatching {
                usageStatsRepository.getTodayUsageForApp(challenge.appPackageName)
            }.onFailure { e ->
                Timber.w(e, "UsageTrackingService: could not get usage for ${challenge.appPackageName}")
            }.getOrNull()

            if (usage == null) continue

            val usedFraction = when (challenge.limitType) {
                LimitType.TIME -> {
                    if (challenge.limitValueMinutes > 0)
                        usage.minutes.toFloat() / challenge.limitValueMinutes
                    else 0f
                }
                LimitType.SESSIONS -> {
                    val maxSessions = challenge.limitValueSessions ?: 0
                    if (maxSessions > 0) usage.opens.toFloat() / maxSessions else 0f
                }
            }

            Timber.d(
                "UsageTrackingService: ${challenge.appDisplayName} usage fraction = " +
                        "${"%.0f".format(usedFraction * 100)}%%"
            )

            if (usedFraction >= 0.8f) {
                Timber.d(
                    "UsageTrackingService: 80%% threshold reached for " +
                            "${challenge.appDisplayName} — posting notification"
                )
                NotificationHelper.createChannels(applicationContext)
                NotificationHelper.sendUsage80Percent(applicationContext, challenge.appDisplayName)
                alerted80PercentApps.add(challenge.appPackageName)
            }
        }
    }

    /** Clears [alerted80PercentApps] at the stroke of midnight so the warning
     *  can fire again the next day. */
    private fun scheduleMidnightAlertReset() {
        serviceScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val midnight = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                delay(midnight - now)
                Timber.d("UsageTrackingService: midnight — clearing 80%% alert set")
                alerted80PercentApps.clear()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText(getString(R.string.notification_tracking_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }
}
