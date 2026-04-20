package com.detox.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.detox.app.R
import timber.log.Timber

/**
 * Centralised helper for building and posting all non-tracking notifications.
 * The foreground-service tracking notification is managed separately by [UsageTrackingService].
 */
object NotificationHelper {

    private const val CHANNEL_DAILY_REPORT  = "daily_report"
    private const val CHANNEL_MILESTONES    = "milestones"
    // High-importance channel for proactive reminders: 20:00 daily nudge + 80% usage warning.
    // Must be IMPORTANCE_HIGH so the notification shows as a heads-up banner on Huawei EMUI.
    const val CHANNEL_REMINDERS             = "detox_reminders"
    private const val CHANNEL_GROUP_EVENTS  = "group_events"

    private const val NOTIF_ID_DAILY_REPORT      = 2001
    private const val NOTIF_ID_MILESTONE_BASE    = 3000
    private const val NOTIF_ID_DAILY_REMINDER    = 4001
    // Per-app IDs derived from package name hash so each app gets its own slot
    private const val NOTIF_ID_USAGE_80_BASE     = 5000
    private const val NOTIF_ID_USAGE_50_BASE     = 5100
    private const val NOTIF_ID_USAGE_75_BASE     = 5200
    private const val NOTIF_ID_USAGE_90_BASE     = 5300
    private const val NOTIF_ID_DAY_CONGRATS_BASE = 6000
    private const val NOTIF_ID_GROUP_BASE        = 7000

    /** Must be called before posting any notification — safe to call repeatedly. */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DAILY_REPORT,
                context.getString(R.string.notif_channel_daily_report_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_daily_report_desc)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MILESTONES,
                context.getString(R.string.notif_channel_milestones_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_milestones_desc)
            }
        )

        // Reminders channel: IMPORTANCE_HIGH ensures heads-up banners even on Huawei EMUI.
        // This is the channel used for the 20:00 daily nudge and 80% usage warnings.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.notif_channel_reminders_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_reminders_desc)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GROUP_EVENTS,
                "Group Challenges",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Updates from your group challenges — failures, completions, and cancellations"
            }
        )
    }

    /**
     * Posts the end-of-day summary notification.
     *
     * @param onTrackCount  number of challenges where the limit was NOT exceeded today
     * @param totalCount    total number of active challenges evaluated
     */
    fun sendDailyReport(
        context: Context,
        onTrackCount: Int,
        totalCount: Int
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Timber.d("Notifications disabled — skipping daily report")
            return
        }

        val title = context.getString(R.string.notif_daily_report_title)
        val body = context.getString(R.string.notif_daily_report_body, onTrackCount, totalCount)

        val notification = NotificationCompat.Builder(context, CHANNEL_DAILY_REPORT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_DAILY_REPORT, notification)
            Timber.d("Daily report notification posted: $body")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS permission not granted, skipping daily report notification")
        }
    }

    /**
     * Posts a milestone notification when a challenge ends successfully.
     */
    fun sendChallengeCompleted(context: Context, appName: String) {
        postMilestone(
            context = context,
            notifId = NOTIF_ID_MILESTONE_BASE + appName.hashCode(),
            title = context.getString(R.string.notif_challenge_completed_title),
            body = context.getString(R.string.notif_challenge_completed_body, appName)
        )
    }

    /**
     * Posts a milestone notification when a Hard Mode challenge is completed successfully.
     * Includes the refunded amount so the user knows their money is coming back.
     *
     * @param amountCents amount in cents that was refunded (e.g. 1000 = €10)
     */
    fun sendHardModeCompleted(context: Context, appName: String, amountCents: Int) {
        postMilestone(
            context = context,
            notifId = NOTIF_ID_MILESTONE_BASE + appName.hashCode() + 2,
            title = context.getString(R.string.notif_hard_mode_completed_title),
            body = context.getString(
                R.string.notif_hard_mode_completed_body,
                appName,
                amountCents / 100
            )
        )
    }

    /**
     * Posts a milestone notification when a Hard Mode challenge is lost.
     */
    fun sendChallengeFailed(context: Context, appName: String) {
        postMilestone(
            context = context,
            notifId = NOTIF_ID_MILESTONE_BASE + appName.hashCode() + 1,
            title = context.getString(R.string.notif_challenge_failed_title),
            body = context.getString(R.string.notif_challenge_failed_body, appName)
        )
    }

    private fun postMilestone(context: Context, notifId: Int, title: String, body: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS permission not granted, skipping milestone notification")
        }
    }

    // ── Local-only notifications (work offline, no FCM needed) ─────────────────

    /**
     * 20:00 daily nudge fired by [DailyReminderWorker] via WorkManager.
     * Uses the high-importance [CHANNEL_REMINDERS] channel so it surfaces as a
     * heads-up banner even on Huawei EMUI with aggressive battery optimisation.
     */
    fun sendDailyReminder(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Timber.d("Notifications disabled — skipping daily reminder")
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_daily_reminder_title))
            .setContentText(context.getString(R.string.notif_daily_reminder_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_DAILY_REMINDER, notification)
            Timber.d("Daily reminder notification posted")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping daily reminder")
        }
    }

    /**
     * Fired by [UsageTrackingService] when an app reaches 80 % of its daily limit.
     * Triggered at most once per app per calendar day (dedup is done in the service).
     *
     * @param appName human-readable app name shown in the notification body
     */
    fun sendUsage80Percent(context: Context, appName: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_USAGE_80_BASE + appName.hashCode()
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_usage_80_title, appName))
            .setContentText(context.getString(R.string.notif_usage_80_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("80%% usage notification posted for $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping 80%% usage notification")
        }
    }

    /**
     * Fired by [UsageTrackingService] when an app reaches a usage threshold (50 / 75 / 90 %).
     * Each threshold is posted with its own notification ID so they stack in the shade
     * rather than replacing each other.
     *
     * @param appName  human-readable app name shown in the notification title
     * @param percent  threshold that was crossed: 50, 75 or 90
     */
    fun sendUsageThreshold(context: Context, appName: String, percent: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val (title, body, notifIdBase) = when (percent) {
            50 -> Triple(
                context.getString(R.string.notif_usage_50_title, appName),
                context.getString(R.string.notif_usage_threshold_body),
                NOTIF_ID_USAGE_50_BASE
            )
            75 -> Triple(
                context.getString(R.string.notif_usage_75_title, appName),
                context.getString(R.string.notif_usage_threshold_body),
                NOTIF_ID_USAGE_75_BASE
            )
            90 -> Triple(
                context.getString(R.string.notif_usage_90_title, appName),
                context.getString(R.string.notif_usage_threshold_body),
                NOTIF_ID_USAGE_90_BASE
            )
            else -> return
        }

        val notifId = notifIdBase + appName.hashCode()
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Usage threshold notification posted: $percent%% for $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping usage threshold notification")
        }
    }

    /**
     * Fired by [DailyEvaluationWorker] for each challenge where the user stayed
     * within their limit that day (i.e. points were awarded).
     *
     * @param appName human-readable app name shown in the notification body
     */
    fun sendDayCongratulations(context: Context, appName: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_DAY_CONGRATS_BASE + appName.hashCode()
        val notification = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_day_congrats_title))
            .setContentText(context.getString(R.string.notif_day_congrats_body, appName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Day congratulations notification posted for $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping congratulations notification")
        }
    }

    // ── Group Challenge notifications ──────────────────────────────────────────

    /**
     * Fired when another participant in a group challenge exceeds their limit.
     * Encourages the remaining participants to keep going.
     *
     * @param failedDisplayName the display name of the participant who was eliminated
     * @param appName           human-readable name of the tracked app
     */
    fun sendGroupParticipantFailed(context: Context, failedDisplayName: String, appName: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_GROUP_BASE + failedDisplayName.hashCode()
        val notification = NotificationCompat.Builder(context, CHANNEL_GROUP_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_group_participant_failed_title, failedDisplayName))
            .setContentText(context.getString(R.string.notif_group_participant_failed_body, failedDisplayName, appName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Group participant failed notification posted: $failedDisplayName in $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping group participant failed notification")
        }
    }

    /**
     * Fired when a group challenge is cancelled because not enough players joined.
     * Informs the user that their buy-in will be refunded.
     *
     * @param appName human-readable name of the tracked app
     */
    fun sendGroupChallengeCancelled(context: Context, appName: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_GROUP_BASE + appName.hashCode() + 1
        val notification = NotificationCompat.Builder(context, CHANNEL_GROUP_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_group_cancelled_title))
            .setContentText(context.getString(R.string.notif_group_cancelled_body, appName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Group challenge cancelled notification posted for $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping group cancelled notification")
        }
    }

    /**
     * Fired at the end of a group challenge to inform the user of the result.
     *
     * @param appName     human-readable name of the tracked app
     * @param succeeded   true if the current user succeeded; false if they were eliminated
     * @param refundCents amount in cents being refunded (only meaningful when [succeeded] is true)
     */
    fun sendGroupChallengeCompleted(
        context: Context,
        appName: String,
        succeeded: Boolean,
        refundCents: Int
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_GROUP_BASE + appName.hashCode() + 2
        val title: String
        val body: String
        if (succeeded) {
            title = context.getString(R.string.notif_group_completed_success_title)
            body = context.getString(R.string.notif_group_completed_success_body, appName, refundCents / 100)
        } else {
            title = context.getString(R.string.notif_group_completed_failed_title)
            body = context.getString(R.string.notif_group_completed_failed_body, appName)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_GROUP_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Group challenge completed notification posted: $appName succeeded=$succeeded refund=${refundCents / 100}€")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping group completed notification")
        }
    }
}
