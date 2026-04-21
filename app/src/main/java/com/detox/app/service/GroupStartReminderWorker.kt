package com.detox.app.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class GroupStartReminderWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_GROUP_ID = "groupId"
    }

    override suspend fun doWork(): Result {
        val groupId = inputData.getString(KEY_GROUP_ID) ?: return Result.failure()
        Timber.d("GroupStartReminderWorker: firing for groupId=%s", groupId)
        return try {
            NotificationHelper.createChannels(context)
            NotificationHelper.sendGroupStartReminder(context, groupId)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "GroupStartReminderWorker: failed for groupId=%s", groupId)
            Result.retry()
        }
    }
}
