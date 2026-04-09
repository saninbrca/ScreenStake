package com.detox.app.domain.model

data class Challenge(
    val id: String,
    /** Primary package name (first in [appPackageNames]); null for WEBSITE type challenges. */
    val appPackageName: String?,
    /** All tracked package names for this challenge. Empty for WEBSITE type. */
    val appPackageNames: List<String>,
    val appDisplayName: String,
    val mode: ChallengeMode,
    val limitType: LimitType,
    val limitValueMinutes: Int,
    val limitValueSessions: Int?,
    val startDate: Long,
    val endDate: Long,
    val amountCents: Int?,
    val stripePaymentIntentId: String?,
    val customMotivation: String?,
    val status: ChallengeStatus,
    val createdAt: Long,
    /** Total minutes the user may spend per day on this app (TIME_BUDGET challenges only). */
    val dailyBudgetMinutes: Int? = null,
    /** All blocked domains (from presets + custom) for website blocking. */
    val blockedDomains: List<String> = emptyList(),
    /** Whether this challenge blocks an app or a website directly. */
    val blockingType: BlockingType = BlockingType.APP,
    /** Whether adult content domains are blocked alongside the main target. */
    val blockAdultContent: Boolean = false,
    /** Start of the active enforcement window (inclusive), e.g. "09:00". Null = always active. */
    val scheduleStartTime: String? = null,
    /** End of the active enforcement window (inclusive), e.g. "22:00". Null = always active. */
    val scheduleEndTime: String? = null,
    /** Days on which the challenge enforces; empty = every day. E.g. ["MON","TUE","WED"]. */
    val activeDays: List<String> = emptyList(),
    /** True once the in-app congratulations overlay has been shown for a completed Hard Mode challenge. */
    val completionShown: Boolean = false,
    /** Duration of each allowed session in minutes (SESSIONS challenges only). */
    val sessionDurationMinutes: Int = 5,
    /** Non-null when this challenge was auto-created to track a group challenge locally. */
    val groupChallengeId: String? = null,
)
