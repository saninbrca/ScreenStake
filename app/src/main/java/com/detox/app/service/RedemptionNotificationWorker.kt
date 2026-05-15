package com.detox.app.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
            originalCents = originalCents
        )

        return Result.success()
    }
}
