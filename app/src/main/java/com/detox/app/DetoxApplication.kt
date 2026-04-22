package com.detox.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.detox.app.domain.repository.GroupChallengeRepository
import com.detox.app.service.DailyEvaluationWorker
import com.detox.app.service.DailyReminderWorker
import com.detox.app.service.GroupChallengeAutoStartWorker
import com.detox.app.service.PermissionCheckWorker
import com.detox.app.service.NotificationHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.stripe.android.PaymentConfiguration
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class DetoxApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var firebaseAuth: FirebaseAuth

    @Inject
    lateinit var groupChallengeRepository: GroupChallengeRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Must run before any Stripe PaymentSheet is created anywhere in the app.
        PaymentConfiguration.init(applicationContext, BuildConfig.STRIPE_PUBLISHABLE_KEY)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Create all notification channels up-front so they exist before any
        // notification is posted (required on Android 8+).
        NotificationHelper.createChannels(this)

        // Log the current FCM token for debugging.  On Huawei without GMS this
        // call will silently fail — the catch below ensures we don't crash.
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Timber.d("FCM token: ${task.result}")
                } else {
                    Timber.w(task.exception, "FCM token fetch failed (expected on Huawei/no-GMS devices)")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "FCM not available on this device — all notifications will be local-only")
        }

        scheduleDailyEvaluation()
        scheduleDailyReminder()
        scheduleGroupChallengeAutoStart()
        schedulePermissionCheck()
        startGroupChallengeSyncing()
    }

    private fun startGroupChallengeSyncing() {
        firebaseAuth.addAuthStateListener { auth ->
            val user = auth.currentUser
            if (user != null) {
                Timber.d("Auth state: signed in as %s — starting group challenge sync", user.uid)
                groupChallengeRepository.startSyncingForUser(user.uid)
            } else {
                Timber.d("Auth state: signed out — stopping group challenge sync")
                groupChallengeRepository.stopSyncing()
            }
        }
    }

    private fun scheduleDailyEvaluation() {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val initialDelayMs = target.timeInMillis - now.timeInMillis

        val workRequest = PeriodicWorkRequestBuilder<DailyEvaluationWorker>(
            24, TimeUnit.HOURS
        )
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .addTag(TAG_DAILY_EVALUATION)   // required so getWorkInfosByTag() can find it
            .build()

        val wm = WorkManager.getInstance(this)
        wm.enqueueUniquePeriodicWork(
            "daily_evaluation",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        Timber.d(
            "Daily evaluation scheduled — initial delay: ${initialDelayMs / 60_000} min " +
                    "(fires at ~23:59)"
        )

        // Log the current work state so we can verify scheduling in Logcat.
        // Uses a listener to avoid blocking the main thread.
        val future = wm.getWorkInfosByTag(TAG_DAILY_EVALUATION)
        future.addListener(
            {
                val infos = runCatching { future.get() }.getOrNull()
                if (infos.isNullOrEmpty()) {
                    Timber.w("WorkManager: no work found for tag '$TAG_DAILY_EVALUATION'")
                } else {
                    infos.forEach { info ->
                        Timber.d(
                            "WorkManager [$TAG_DAILY_EVALUATION] id=${info.id} " +
                                    "state=${info.state} " +
                                    "nextScheduleTime=${info.nextScheduleTimeMillis}"
                        )
                    }
                }
            },
            { runnable -> runnable.run() }   // inline executor — runs on whichever thread calls get()
        )
    }

    /**
     * Schedules (or re-confirms) the 20:00 daily reminder.
     *
     * Uses [ExistingPeriodicWorkPolicy.KEEP] so an existing enqueue survives
     * process restarts without resetting the timer.  [BootReceiver] calls
     * [WorkManager.cancelUniqueWork] + re-enqueues after a reboot so the
     * initial delay is recalculated from the new boot time.
     */
    fun scheduleDailyReminder() {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 20)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If 20:00 has already passed today, aim for tomorrow
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val initialDelayMs = target.timeInMillis - now.timeInMillis

        val request = PeriodicWorkRequestBuilder<DailyReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .addTag(TAG_DAILY_REMINDER)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME_DAILY_REMINDER,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Timber.d(
            "Daily reminder scheduled — initial delay: ${initialDelayMs / 60_000} min " +
                    "(fires at ~20:00)"
        )
    }

    private fun scheduleGroupChallengeAutoStart() {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val initialDelayMs = target.timeInMillis - now.timeInMillis

        val request = PeriodicWorkRequestBuilder<GroupChallengeAutoStartWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .addTag(TAG_GROUP_AUTO_START)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME_GROUP_AUTO_START,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Timber.d(
            "Group challenge auto-start scheduled — initial delay: ${initialDelayMs / 60_000} min " +
                    "(fires at ~00:01)"
        )
    }

    private fun schedulePermissionCheck() {
        val request = PeriodicWorkRequestBuilder<PermissionCheckWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PermissionCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Timber.d("Permission check worker scheduled (every 15 min)")
    }

    companion object {
        const val TAG_DAILY_EVALUATION  = "daily_evaluation"
        const val TAG_DAILY_REMINDER    = "daily_reminder"
        const val WORK_NAME_DAILY_REMINDER = "daily_reminder"
        const val TAG_GROUP_AUTO_START  = "group_auto_start"
        const val WORK_NAME_GROUP_AUTO_START = "group_auto_start"
    }
}
