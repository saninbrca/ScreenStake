package com.finite.focus.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Timber.d("BootReceiver: device booted — starting service and rescheduling workers")

        // Guarded: BOOT_COMPLETED is a foreground-service-start exemption, so the start itself is
        // legal here — but starting with NOTHING to enforce is what put the service under
        // START_STICKY on phones that never finished onboarding, where the system later recreated
        // it in the background and refused the promotion. Room is read off the main thread behind
        // goAsync(); the worker below is enqueued synchronously and is unaffected.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Boundary catch, not a swallow: this is a broadcast on the boot path, where opening
            // the encrypted database can fail outright (Keystore not ready). An unhandled throw in
            // this scope would take the process down — the exact failure mode being fixed.
            runCatching { UsageTrackingService.startIfActiveChallenge(appContext) }
                .onFailure { e -> Timber.e(e, "BootReceiver: gated service start failed") }
            pendingResult.finish()
        }

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
