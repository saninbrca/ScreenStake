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
import com.detox.app.domain.repository.ChallengeRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
