package com.detox.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.detox.app.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class PermissionWarningWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_LEVEL = "level"
        private const val PREFS = "detox_permission"
    }

    override suspend fun doWork(): Result {
        val level = inputData.getInt(KEY_LEVEL, -1)
        if (level !in 0..3) {
            Timber.w("PermissionWarningWorker: invalid level=$level, skipping")
            return Result.success()
        }

        if (Settings.canDrawOverlays(applicationContext)) {
            Timber.d("PermissionWarningWorker: permission restored — skipping warning level=$level")
            return Result.success()
        }

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lostAt = prefs.getLong("permissionLostAt", System.currentTimeMillis())
        val elapsed = System.currentTimeMillis() - lostAt
        Timber.d("Warning sent: level=$level, elapsed=${elapsed / 3_600_000}h")

        val actionIntent = buildMainActivityIntent(level)
        NotificationHelper.sendPermissionWarning(applicationContext, level, actionIntent)

        return Result.success()
    }

    private fun buildMainActivityIntent(level: Int): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            applicationContext,
            level,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
