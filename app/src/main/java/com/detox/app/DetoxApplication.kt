package com.detox.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.detox.app.service.DailyEvaluationWorker
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
        scheduleDailyEvaluation()
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
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_evaluation",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        Timber.d("Daily evaluation scheduled with initial delay: ${initialDelayMs / 60000} minutes")
    }
}
