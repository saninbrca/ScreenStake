package com.detox.app.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Fires the 20:00 "check your progress" local notification every day.
 *
 * Scheduled as a [androidx.work.PeriodicWorkRequest] with a 24-hour interval and
 * an initial delay calculated to land at 20:00 local time.  Rescheduled on every
 * device boot by [BootReceiver] so the timing stays accurate after reboots.
 *
 * Does NOT require Google Play Services — 100 % offline, no FCM involved.
 */
@HiltWorker
class DailyReminderWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("DailyReminderWorker: ▶ firing 20:00 reminder")
        return try {
            NotificationHelper.createChannels(context)
            NotificationHelper.sendDailyReminder(context)
            Timber.d("DailyReminderWorker: ■ done")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "DailyReminderWorker: failed")
            FirebaseCrashlytics.getInstance().recordException(e)
            Result.retry()
        }
    }
}
