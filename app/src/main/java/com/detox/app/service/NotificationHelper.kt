package com.detox.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
    private const val NOTIF_ID_REDEMPTION_BASE   = 8000
    // Per-app IDs derived from package name hash so each app gets its own slot
    private const val NOTIF_ID_USAGE_80_BASE     = 5000
    private const val NOTIF_ID_USAGE_50_BASE     = 5100
    private const val NOTIF_ID_USAGE_75_BASE     = 5200
    private const val NOTIF_ID_USAGE_90_BASE     = 5300
    private const val NOTIF_ID_DAY_CONGRATS_BASE = 6000
    private const val NOTIF_ID_GROUP_BASE        = 7000
    private const val NOTIF_ID_PERMISSION_RESTORED    = 9001
    private const val NOTIF_ID_PERMISSION_FAILED      = 9002
    private const val NOTIF_ID_PERMISSION_WARNING_BASE = 9010  // 9010..9013 for levels 0-3
    private const val NOTIF_ID_ACCESSIBILITY_LOST     = 9020
    private const val NOTIF_ID_ACCESSIBILITY_RESTORED = 9021
    private const val NOTIF_ID_USAGE_VIOLATION        = 9040

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
     *
     * @param refundCents amount in cents being refunded to user (80% of original stake)
     * @param feeCents    amount in cents kept by app as fee (20% of original stake)
     */
    fun sendHardModeCompleted(context: Context, appName: String, refundCents: Int, feeCents: Int = 0) {
        val title = context.getString(R.string.notif_hard_mode_completed_title)
        val body = if (feeCents > 0) {
            context.getString(
                R.string.notif_hard_mode_completed_body_fee,
                refundCents / 100,
                feeCents / 100
            )
        } else {
            context.getString(R.string.notif_hard_mode_completed_body, appName, refundCents / 100)
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(
                NOTIF_ID_MILESTONE_BASE + appName.hashCode() + 2, notification
            )
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS permission not granted, skipping hard mode completed notification")
        }
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

    // ── Overlay permission notifications ──────────────────────────────────────

    fun sendPermissionWarning(context: Context, level: Int, actionIntent: PendingIntent) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val (title, body) = when (level) {
            0 -> "⚠️ Deine Challenge ist in Gefahr!" to
                    "Detox braucht eine Erlaubnis um dich zu schützen. Handle jetzt!"
            1 -> "🚨 Deine Challenge pausiert!" to
                    "Je länger du wartest, desto mehr riskierst du. Tippe hier um es zu beheben."
            2 -> "⏰ Zeit läuft ab..." to
                    "Deine Challenge und dein Einsatz sind in Gefahr. Handle JETZT bevor es zu spät ist!"
            3 -> "🔴 Letzte Warnung!" to
                    "Deine Challenge wird bald automatisch beendet. Öffne Detox sofort."
            else -> return
        }
        val notifId = NOTIF_ID_PERMISSION_WARNING_BASE + level
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(actionIntent)
            .addAction(0, "Jetzt beheben →", actionIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Permission warning notification posted: level=$level")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping permission warning notification")
        }
    }

    fun sendPermissionRestored(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("✅ Alles gut!")
            .setContentText("Deine Challenge läuft weiter.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_PERMISSION_RESTORED, notification)
            Timber.d("Permission restored notification posted")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping permission restored notification")
        }
    }

    fun sendPermissionFailed(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("❌ Challenge fehlgeschlagen")
            .setContentText("Die Overlay-Berechtigung war zu lange deaktiviert. Dein Einsatz wurde eingezogen.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Die Overlay-Berechtigung war zu lange deaktiviert. Dein Einsatz wurde eingezogen."
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_PERMISSION_FAILED, notification)
            Timber.d("Permission failed notification posted")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping permission failed notification")
        }
    }

    // ── Accessibility service notifications ───────────────────────────────────

    fun sendPermissionEscalation(context: Context, stage: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val (title, body) = when (stage) {
            "6h"  -> context.getString(R.string.notif_perm_warn_6h_title)  to context.getString(R.string.notif_perm_warn_6h_body)
            "12h" -> context.getString(R.string.notif_perm_warn_12h_title) to context.getString(R.string.notif_perm_warn_12h_body)
            "23h" -> context.getString(R.string.notif_perm_warn_23h_title) to context.getString(R.string.notif_perm_warn_23h_body)
            else  -> return
        }
        val notifId = NOTIF_ID_PERMISSION_WARNING_BASE + when (stage) { "6h" -> 10; "12h" -> 11; else -> 12 }
        val intent = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, notifId, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, context.getString(R.string.notif_accessibility_fix_action), pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Permission escalation notification posted: stage=$stage")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping permission escalation notification")
        }
    }

    fun sendAccessibilityLost(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val baseBody = context.getString(R.string.notif_accessibility_lost_body)
        val body = if (Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)) {
            "$baseBody\n${context.getString(R.string.notif_accessibility_huawei_hint)}"
        } else {
            baseBody
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_accessibility_lost_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, context.getString(R.string.notif_accessibility_fix_action), pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_ACCESSIBILITY_LOST, notification)
            Timber.d("Accessibility lost notification posted")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping accessibility lost notification")
        }
    }

    fun sendAccessibilityRestored(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val body = if (Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)) {
            "${context.getString(R.string.notif_accessibility_restored_body)}\n${context.getString(R.string.notif_accessibility_huawei_hint)}"
        } else {
            context.getString(R.string.notif_accessibility_restored_body)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_accessibility_restored_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_ACCESSIBILITY_RESTORED, notification)
            Timber.d("Accessibility restored notification posted")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping accessibility restored notification")
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
     * Fired 24 h after a manual-start group challenge is created, reminding the creator
     * to start the challenge once everyone has joined.
     *
     * @param groupId used to generate a stable, unique notification ID
     */
    fun sendGroupStartReminder(context: Context, groupId: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_GROUP_BASE + groupId.hashCode() + 20
        val notification = NotificationCompat.Builder(context, CHANNEL_GROUP_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_group_start_reminder_title))
            .setContentText(context.getString(R.string.notif_group_start_reminder_body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Group start reminder notification posted for groupId=%s", groupId)
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping group start reminder")
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

    fun sendGroupChallengePayoutReceived(
        context: Context,
        stakeRefundCents: Int,
        prizeShareCents: Int,
        hasPendingIban: Boolean
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val totalCents = stakeRefundCents + prizeShareCents
        val totalFormatted = "€%s".format("%.2f".format(totalCents / 100.0).replace(".", ","))
        val title = context.getString(R.string.notif_payout_available_title, totalFormatted)
        val body = if (hasPendingIban) {
            context.getString(R.string.notif_payout_available_no_iban_body)
        } else {
            context.getString(R.string.notif_payout_available_has_iban_body)
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "profile")
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_GROUP_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        if (pendingIntent != null) builder.setContentIntent(pendingIntent)
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_GROUP_BASE + 51, builder.build())
            Timber.d("Group payout notification posted: total=€${totalCents / 100} noIban=$hasPendingIban")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping group payout notification")
        }
    }

    fun sendGroupChallengeExpired(context: Context, appName: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_GROUP_BASE + appName.hashCode() + 10
        val notification = NotificationCompat.Builder(context, CHANNEL_GROUP_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_group_expired_title))
            .setContentText(context.getString(R.string.notif_group_expired_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_group_expired_body)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Group expired notification posted for $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping group expired notification")
        }
    }

    fun sendGroupChallengeLeft(context: Context, groupId: String, amountCents: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_GROUP_BASE + groupId.hashCode() + 30
        val body = context.getString(R.string.notif_group_left_body, amountCents / 100)
        val notification = NotificationCompat.Builder(context, CHANNEL_GROUP_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_group_left_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Group left notification posted for groupId=%s", groupId)
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping group left notification")
        }
    }

    fun sendGroupChallengeDeleted(context: Context, groupId: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_GROUP_BASE + groupId.hashCode() + 31
        val notification = NotificationCompat.Builder(context, CHANNEL_GROUP_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_group_deleted_title))
            .setContentText(context.getString(R.string.notif_group_deleted_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_group_deleted_body)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Group deleted notification posted for groupId=%s", groupId)
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping group deleted notification")
        }
    }

    fun sendGroupChallengeStartWarning(context: Context, appName: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_GROUP_BASE + appName.hashCode() + 11
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_group_start_warning_title))
            .setContentText(context.getString(R.string.notif_group_start_warning_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_group_start_warning_body)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Group start warning notification posted for $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping group start warning notification")
        }
    }

    fun sendRedemptionAvailable(
        context: Context,
        appName: String,
        refundCents: Int,
        originalCents: Int
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val refundEuros = refundCents / 100
        val originalEuros = originalCents / 100
        val notification = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_redemption_available_title))
            .setContentText(context.getString(R.string.notif_redemption_available_body, refundEuros, originalEuros))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.notif_redemption_available_body, refundEuros, originalEuros)
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(
                NOTIF_ID_REDEMPTION_BASE + appName.hashCode(), notification
            )
            Timber.d("Redemption available notification posted for $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping redemption notification")
        }
    }

    fun sendRedemptionCompleted(context: Context, appName: String, refundCents: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val refundEuros = refundCents / 100
        val notification = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_redemption_completed_title))
            .setContentText(context.getString(R.string.notif_redemption_completed_body, refundEuros))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(
                NOTIF_ID_REDEMPTION_BASE + appName.hashCode() + 1, notification
            )
            Timber.d("Redemption completed notification posted for $appName: €$refundEuros refunded")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping redemption completed notification")
        }
    }

    fun sendRedemptionFailed(context: Context, appName: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_redemption_failed_title))
            .setContentText(context.getString(R.string.notif_redemption_failed_body, appName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(
                NOTIF_ID_REDEMPTION_BASE + appName.hashCode() + 2, notification
            )
            Timber.d("Redemption failed notification posted for $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping redemption failed notification")
        }
    }

    fun sendPayoutReceived(context: Context, amountCents: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val euros = amountCents / 100
        val notification = NotificationCompat.Builder(context, CHANNEL_GROUP_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_payout_received_title))
            .setContentText(context.getString(R.string.notif_payout_received_body, euros))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_GROUP_BASE + 50, notification)
            Timber.d("Payout received notification posted: €$euros")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping payout notification")
        }
    }

    fun sendUsageViolationDetected(context: Context, appName: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val body = context.getString(R.string.notif_usage_violation_body, appName)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_usage_violation_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_USAGE_VIOLATION, notification)
            Timber.d("Usage violation notification posted for $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping usage violation notification")
        }
    }

    fun showTauntNotification(context: Context, message: String) {
        val notifId = 9030
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Taunt notification posted: $message")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping taunt notification")
        }
    }
}
