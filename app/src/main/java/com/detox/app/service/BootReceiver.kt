package com.detox.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import timber.log.Timber
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Timber.d("BootReceiver: device booted — starting service and rescheduling workers")

        // Restart the foreground tracking service
        UsageTrackingService.start(context)

        // Ensure permission check worker survives reboots — KEEP so we don't reset
        // any in-progress permissionLostAt timer that was already ticking.
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PermissionCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PermissionCheckWorker>(15, TimeUnit.MINUTES).build()
        )
        Timber.d("BootReceiver: permission check worker re-enqueued")
    }
}
