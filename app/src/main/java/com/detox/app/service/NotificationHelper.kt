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
import com.detox.app.util.DateUtils
import com.detox.app.util.FeatureFlags
import timber.log.Timber

/**
 * Centralised helper for building and posting all non-tracking notifications.
 * The foreground-service tracking notification is managed separately by [UsageTrackingService].
 */
object NotificationHelper {

    private const val CHANNEL_MILESTONES    = "milestones"
    // High-importance channel for proactive reminders: 80% usage warning.
    // Must be IMPORTANCE_HIGH so the notification shows as a heads-up banner on Huawei EMUI.
    const val CHANNEL_REMINDERS             = "detox_reminders"
    private const val CHANNEL_GROUP_EVENTS  = "group_events"

    private const val NOTIF_ID_MILESTONE_BASE    = 3000
    private const val NOTIF_ID_REDEMPTION_BASE   = 8000
    // Per-app IDs derived from package name hash so each app gets its own slot
    private const val NOTIF_ID_USAGE_80_BASE     = 5000
    private const val NOTIF_ID_GROUP_BASE        = 7000
    private const val NOTIF_ID_PERMISSION_FAILED      = 9002
    private const val NOTIF_ID_PERMISSION_PAUSED      = 9003
    private const val NOTIF_ID_PERMISSION_WARNING_BASE = 9010  // 9010..9013 for levels 0-3
    private const val NOTIF_ID_USAGE_VIOLATION        = 9040
    private const val NOTIF_ID_HEARTBEAT_WARNING      = 9050

    // ── Toggle / dedup preferences ────────────────────────────────────────────

    /** Per-notification switches owned by this helper (already backs the group toggle below). */
    private const val NOTIF_PREFS_NAME = "detox_notifications"

    /**
     * The app-wide settings file. `challenge_updates_enabled` is WRITTEN by `SettingsViewModel`
     * and lives HERE, not in [NOTIF_PREFS_NAME] — relocating the key would silently reset every
     * existing user's toggle back to the default, so the reader comes to the key instead.
     */
    private const val SETTINGS_PREFS_NAME   = "detox_settings"
    private const val KEY_CHALLENGE_UPDATES = "challenge_updates_enabled"

    /**
     * Day stamp of the last 80 % warning per challenge (`warn80_<challengeId>` → [DateUtils.todayKey]).
     * Same lazy day-key shape as `OverlayManager.ensureCommittedBudgetFresh`: no Room column and
     * no migration, it survives process death (the deciding property on Huawei, where an in-memory
     * set would re-fire the warning after every service restart), and it self-heals at midnight —
     * a stamp that is no longer today simply reads as "not warned yet".
     */
    private const val WARN_80_KEY_PREFIX = "warn80_"

    /**
     * The user-facing "Challenge updates" switch (Settings → Notifications).
     *
     * Gates ONLY challenge-progress notifications: the 80 % approach warning and the three
     * completion/result senders. Deliberately NOT consulted by any permission, heartbeat,
     * usage-violation, redemption or payout sender — muting progress updates must never cost a
     * user a refund or a stake-protecting warning.
     *
     * Fail-open on a missing key: default `true`, matching `SettingsViewModel`'s own read.
     */
    private fun challengeUpdatesEnabled(context: Context): Boolean =
        context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CHALLENGE_UPDATES, true)

    /** Must be called before posting any notification — safe to call repeatedly. */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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

        // Money-floor gated: the Group Challenges channel is only ever used by group (buy-in)
        // notifications, which can't fire in the soft-only release. Skipping its registration keeps
        // a dead "Group Challenges" entry out of the system notification settings. The senders that
        // target this channel are never reached when money is gated off; flipping the build flag
        // (next launch calls createChannels again) recreates it. MILESTONES/REMINDERS are shared
        // with soft features, so they are always registered.
        if (FeatureFlags.moneyEnabled) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_GROUP_EVENTS,
                    context.getString(R.string.notification_group_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notification_group_channel_description)
                }
            )
        }
    }

    /**
     * Builds a [PendingIntent] that re-launches the app and tells [MainActivity] which screen
     * to deep-link to (via the `nav_target` / `nav_arg` extras consumed in `handleDeepLink`).
     *
     * @param notifId used as the unique request code so each notification keeps its own intent
     */
    private fun buildDeepLinkIntent(
        context: Context,
        notifId: Int,
        navTarget: String,
        navArg: String? = null
    ): PendingIntent {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)!!.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("nav_target", navTarget)
                navArg?.let { putExtra("nav_arg", it) }
            }
        return PendingIntent.getActivity(
            context, notifId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Posts a milestone notification when a challenge ends successfully.
     */
    fun sendChallengeCompleted(context: Context, appName: String, challengeId: String? = null) {
        if (!challengeUpdatesEnabled(context)) return
        val notifId = NOTIF_ID_MILESTONE_BASE + appName.hashCode()
        postMilestone(
            context = context,
            notifId = notifId,
            title = context.getString(R.string.notif_challenge_completed_title),
            body = context.getString(R.string.notif_challenge_completed_body, appName),
            contentIntent = challengeId?.let {
                buildDeepLinkIntent(context, notifId, "history_detail", it)
            }
        )
    }

    /**
     * Posts a milestone notification when a Hard Mode challenge is completed successfully.
     *
     * @param refundCents amount in cents being refunded to user (80% of original stake)
     * @param feeCents    amount in cents kept by app as fee (20% of original stake)
     */
    fun sendHardModeCompleted(
        context: Context,
        appName: String,
        refundCents: Int,
        feeCents: Int = 0,
        challengeId: String? = null
    ) {
        if (!challengeUpdatesEnabled(context)) return
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
        val notifId = NOTIF_ID_MILESTONE_BASE + appName.hashCode() + 2
        val builder = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        challengeId?.let {
            builder.setContentIntent(buildDeepLinkIntent(context, notifId, "history_detail", it))
        }
        val notification = builder.build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS permission not granted, skipping hard mode completed notification")
        }
    }

    private fun postMilestone(
        context: Context,
        notifId: Int,
        title: String,
        body: String,
        contentIntent: PendingIntent? = null
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        val notification = builder.build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS permission not granted, skipping milestone notification")
        }
    }

    // ── Local-only notifications (work offline, no FCM needed) ─────────────────

    /**
     * Warns the user once per (challenge, day) as they approach 80 % of a daily limit, so the
     * limit is a decision they see coming rather than a wall they hit.
     *
     * The toggle gate, the threshold predicate and the dedup stamp all live here — the three
     * measuring seams that call this ([OverlayManager] for `TIME` and `SESSIONS`,
     * [UsageTrackingService] for `TIME_BUDGET`) only supply a used/limit pair, so they cannot
     * disagree about what "80 %" means or about how often it may fire.
     *
     * `used`/`limit` are whatever unit the limit type counts in — minutes, conscious opens, or
     * budget milliseconds — and are only ever compared to each other, never mixed across types.
     * `TIME_WINDOW` has no usage limit and never calls this.
     *
     * @param used  usage so far today, in the limit's own unit
     * @param limit the daily limit, same unit; `<= 0` (an unset/degenerate limit) never warns
     */
    fun maybeSendUsage80Percent(
        context: Context,
        challengeId: String,
        appName: String,
        used: Long,
        limit: Long
    ) {
        if (!challengeUpdatesEnabled(context)) return

        // Integer math, deliberately: `used * 100 >= limit * 80` instead of a Float ratio, so the
        // threshold can't drift with rounding. `used < limit` keeps this a warning about the
        // APPROACH — at or past the limit the overlay/settlement owns the moment, not a nudge.
        if (limit <= 0L || used >= limit || used * 100L < limit * 80L) return

        val prefs = context.getSharedPreferences(NOTIF_PREFS_NAME, Context.MODE_PRIVATE)
        val key = "$WARN_80_KEY_PREFIX$challengeId"
        val today = DateUtils.todayKey()
        if (prefs.getLong(key, 0L) == today) return

        sendUsage80Percent(context, appName, challengeId)
        prefs.edit().putLong(key, today).apply()
        Timber.d("80%% warning fired for challenge=$challengeId ($used/$limit) — stamped day $today")
    }

    /**
     * Posts the 80 % warning itself. PRIVATE on purpose: every send must go through
     * [maybeSendUsage80Percent], which owns the "Challenge updates" toggle gate and the
     * once-per-day dedup. A direct caller would silently bypass both.
     *
     * @param appName human-readable app name shown in the notification title
     */
    private fun sendUsage80Percent(context: Context, appName: String, challengeId: String? = null) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_USAGE_80_BASE + appName.hashCode()
        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_usage_80_title, appName))
            .setContentText(context.getString(R.string.notif_usage_80_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        challengeId?.let {
            builder.setContentIntent(buildDeepLinkIntent(context, notifId, "challenge_detail", it))
        }
        val notification = builder.build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("80%% usage notification posted for $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping 80%% usage notification")
        }
    }

    // ── Overlay permission notifications ──────────────────────────────────────

    fun sendPermissionWarning(context: Context, level: Int, actionIntent: PendingIntent) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val (title, body) = when (level) {
            0 -> context.getString(R.string.notification_permission_warning_0_title) to context.getString(R.string.notification_permission_warning_0_body)
            1 -> context.getString(R.string.notification_permission_warning_1_title) to context.getString(R.string.notification_permission_warning_1_body)
            2 -> context.getString(R.string.notification_permission_warning_2_title) to context.getString(R.string.notification_permission_warning_2_body)
            3 -> context.getString(R.string.notification_permission_warning_3_title) to context.getString(R.string.notification_permission_warning_3_body)
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
            .addAction(0, context.getString(R.string.notification_fix_now), actionIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Permission warning notification posted: level=$level")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping permission warning notification")
        }
    }

    /**
     * Money-loss notice for the permission deadline. Only for users who actually had a solo Hard
     * challenge in play — the body says the stake was charged, which is false for anyone else.
     * See [sendPermissionEnforcementPaused] for the Soft counterpart and [PermissionCheckWorker]
     * for the branch.
     */
    fun sendPermissionFailed(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_permission_failed_title))
            .setContentText(context.getString(R.string.notif_overlay_captured_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.notif_overlay_captured_body)
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildDeepLinkIntent(context, NOTIF_ID_PERMISSION_FAILED, "profile"))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_PERMISSION_FAILED, notification)
            Timber.d("Permission failed notification posted")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping permission failed notification")
        }
    }

    /**
     * Soft-challenge counterpart to [sendPermissionFailed], posted when the permission deadline
     * passes and the user has an active SOFT challenge.
     *
     * A Soft challenge is NOT failed by permission loss — `PermissionCheckWorker
     * .failAllHardChallenges` only touches solo Hard rows, so the challenge stays `active` and
     * enforcement merely stops happening. The old behaviour posted [sendPermissionFailed]'s
     * "❌ Challenge failed / your stake has been charged" to these users, which was false twice
     * over (nothing failed, and Soft has no stake). The copy here says what is actually true and
     * explicitly denies the failure, because that is the wrong belief being corrected.
     *
     * Its own notification id, so a user holding BOTH a Hard and a Soft challenge gets both
     * messages instead of one overwriting the other.
     */
    fun sendPermissionEnforcementPaused(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val body = context.getString(R.string.notif_permission_paused_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_permission_paused_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildDeepLinkIntent(context, NOTIF_ID_PERMISSION_PAUSED, "profile"))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_PERMISSION_PAUSED, notification)
            Timber.d("Permission enforcement-paused notification posted")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping permission paused notification")
        }
    }

    // ── Heartbeat (went-dark forfeit) notifications ───────────────────────────

    /**
     * Best-effort nudge fired by [PermissionCheckWorker] when EMUI has clearly throttled the
     * heartbeat worker. Tells the user to open the app so their stake is not forfeited as
     * "device went dark". Deep-links to the dashboard.
     */
    fun sendHeartbeatWarning(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_heartbeat_warn_title))
            .setContentText(context.getString(R.string.notif_heartbeat_warn_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.notif_heartbeat_warn_body)
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(buildDeepLinkIntent(context, NOTIF_ID_HEARTBEAT_WARNING, "dashboard"))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_HEARTBEAT_WARNING, notification)
            Timber.d("Heartbeat warning notification posted")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping heartbeat warning notification")
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

    // ── Group Challenge notifications ──────────────────────────────────────────

    /**
     * Fired when another participant in a group challenge exceeds their limit.
     * Encourages the remaining participants to keep going.
     *
     * @param failedDisplayName the display name of the participant who was eliminated
     * @param appName           human-readable name of the tracked app
     */
    fun sendGroupParticipantFailed(
        context: Context,
        failedDisplayName: String,
        appName: String,
        groupId: String? = null
    ) {
        val prefs = context.getSharedPreferences("detox_notifications", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_group_participant_failed", true)) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_GROUP_BASE + failedDisplayName.hashCode()
        val builder = NotificationCompat.Builder(context, CHANNEL_GROUP_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_group_participant_failed_title, failedDisplayName))
            .setContentText(context.getString(R.string.notif_group_participant_failed_body, failedDisplayName, appName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        groupId?.let {
            builder.setContentIntent(buildDeepLinkIntent(context, notifId, "group_detail", it))
        }
        val notification = builder.build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Group participant failed notification posted: $failedDisplayName in $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping group participant failed notification")
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
        refundCents: Int,
        groupId: String? = null
    ) {
        if (!challengeUpdatesEnabled(context)) return
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
        val builder = NotificationCompat.Builder(context, CHANNEL_GROUP_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        groupId?.let {
            builder.setContentIntent(buildDeepLinkIntent(context, notifId, "group_detail", it))
        }
        val notification = builder.build()
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
        val notifId = NOTIF_ID_GROUP_BASE + 51
        val builder = NotificationCompat.Builder(context, CHANNEL_GROUP_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildDeepLinkIntent(context, notifId, "profile"))
        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
            Timber.d("Group payout notification posted: total=€${totalCents / 100} noIban=$hasPendingIban")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping group payout notification")
        }
    }

    fun sendRedemptionAvailable(
        context: Context,
        appName: String,
        refundCents: Int,
        originalCents: Int,
        challengeId: String? = null
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val refundEuros = refundCents / 100
        val originalEuros = originalCents / 100
        val notifId = NOTIF_ID_REDEMPTION_BASE + appName.hashCode()
        val builder = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_redemption_available_title))
            .setContentText(context.getString(R.string.notif_redemption_available_body, refundEuros, originalEuros))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.notif_redemption_available_body, refundEuros, originalEuros)
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        challengeId?.let {
            builder.setContentIntent(buildDeepLinkIntent(context, notifId, "challenge_detail", it))
        }
        val notification = builder.build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Redemption available notification posted for $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping redemption notification")
        }
    }

    fun sendRedemptionCompleted(context: Context, appName: String, refundCents: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val refundEuros = refundCents / 100
        val notifId = NOTIF_ID_REDEMPTION_BASE + appName.hashCode() + 1
        val notification = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_redemption_completed_title))
            .setContentText(context.getString(R.string.notif_redemption_completed_body, refundEuros))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(buildDeepLinkIntent(context, notifId, "profile"))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            Timber.d("Redemption completed notification posted for $appName: €$refundEuros refunded")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping redemption completed notification")
        }
    }

    fun sendRedemptionFailed(context: Context, appName: String, challengeId: String? = null) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notifId = NOTIF_ID_REDEMPTION_BASE + appName.hashCode() + 2
        val builder = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_redemption_failed_title))
            .setContentText(context.getString(R.string.notif_redemption_failed_body, appName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        challengeId?.let {
            builder.setContentIntent(buildDeepLinkIntent(context, notifId, "challenge_detail", it))
        }
        val notification = builder.build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
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
            .setContentIntent(buildDeepLinkIntent(context, NOTIF_ID_USAGE_VIOLATION, "dashboard"))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_USAGE_VIOLATION, notification)
            Timber.d("Usage violation notification posted for $appName")
        } catch (e: SecurityException) {
            Timber.w("POST_NOTIFICATIONS not granted, skipping usage violation notification")
        }
    }

}
