package com.detox.app.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.detox.app.util.FeatureFlags
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class RedemptionNotificationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_CHALLENGE_ID = "challengeId"
        const val KEY_APP_NAME = "appName"
        const val KEY_REFUND_CENTS = "refundCents"
        const val KEY_ORIGINAL_AMOUNT = "originalAmountCents"
    }

    override suspend fun doWork(): Result {
        // Money-floor guard: the Redemption ("comeback") offer is a real-money surface. In the
        // soft-only release, never post the notification. Succeed silently so WorkManager doesn't
        // retry. (Belt-and-suspenders: a comeback can only be scheduled after a Hard loss, which
        // cannot happen when money is gated off.)
        if (!FeatureFlags.moneyEnabled) {
            Timber.d("RedemptionNotificationWorker: money features gated off — skipping notification")
            return Result.success()
        }

        val challengeId = inputData.getString(KEY_CHALLENGE_ID) ?: return Result.failure()
        val appName = inputData.getString(KEY_APP_NAME) ?: return Result.failure()
        val refundCents = inputData.getInt(KEY_REFUND_CENTS, 0)
        val originalCents = inputData.getInt(KEY_ORIGINAL_AMOUNT, 0)

        Timber.d("RedemptionNotificationWorker: sending notification for challengeId=$challengeId")

        NotificationHelper.createChannels(applicationContext)
        NotificationHelper.sendRedemptionAvailable(
            context = applicationContext,
            appName = appName,
            refundCents = refundCents,
            originalCents = originalCents,
            challengeId = challengeId
        )

        return Result.success()
    }
}
