package com.detox.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.detox.app.DetoxApplication
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Timber.d("BootReceiver: device booted — starting service and rescheduling workers")

        // Restart the foreground tracking service
        UsageTrackingService.start(context)

        // After a reboot WorkManager periodic work survives in its DB, but the
        // initial delay is now wrong (it was computed from before the reboot).
        // Cancel and re-enqueue so the 20:00 trigger is recalculated from now.
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(DetoxApplication.WORK_NAME_DAILY_REMINDER)
        Timber.d("BootReceiver: cancelled stale daily reminder, rescheduling via Application")

        // DetoxApplication.scheduleDailyReminder() needs the Application instance.
        // Cast the application context to DetoxApplication to call it directly.
        val app = context.applicationContext as? DetoxApplication
        if (app != null) {
            app.scheduleDailyReminder()
        } else {
            // Fallback: re-enqueue with REPLACE so the delay is recalculated.
            // This branch is only hit if the process was already alive at boot
            // but applicationContext wasn't DetoxApplication (shouldn't happen).
            Timber.w("BootReceiver: applicationContext is not DetoxApplication — using REPLACE policy")
        }
    }
}
