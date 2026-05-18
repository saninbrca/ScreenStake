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
    /** URL path prefixes for feature-level partial blocking, e.g. "instagram.com/reels". */
    val partialBlockDomains: List<String> = emptyList(),
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
    /** Native app content sections blocked within the app (e.g. Instagram Reels). */
    val partialBlockSections: List<PartialBlockSection> = emptyList(),
    /** True when this challenge only blocks specific sections, not the full app. */
    val isPartialBlockOnly: Boolean = false,

    // ── Redemption Challenge fields ────────────────────────────────────────────

    /** True when a Redemption Challenge is available after this challenge failed (Hard Mode Solo only). */
    val redemptionEligible: Boolean = false,
    /** Timestamp after which the Redemption option expires (failedAt + 3 days). */
    val redemptionDeadline: Long? = null,
    /** Timestamp after which the Redemption banner/button is shown (failedAt + 24h). */
    val redemptionShowAfter: Long? = null,
    /** ID of the Redemption Challenge started for this original. Non-null = already used. */
    val redemptionChallengeId: String? = null,
    /** Amount to refund on Redemption win in cents (floor(originalAmountCents * 0.70)). */
    val redemptionRefundAmount: Int? = null,
    /** Duration in days for the Redemption Challenge (originalDays * 2). */
    val redemptionDays: Int? = null,
    /** Daily limit for the Redemption Challenge (floor(originalLimit / 2)). */
    val redemptionLimit: Int? = null,
    /** True when this challenge IS the Redemption Challenge (no new payment). */
    val isRedemption: Boolean = false,
    /** For Redemption Challenges: ID of the original failed Hard Mode challenge. */
    val originalChallengeId: String? = null,
    /** For Redemption Challenges: PaymentIntentId of the original challenge to partially refund on win. */
    val originalPaymentIntentId: String? = null,
    /** For Redemption Challenges: partial refund amount in cents on win. */
    val refundAmountCents: Int? = null,

    // ── Pending limit reduction ────────────────────────────────────────────────

    /** New limit value to apply at next midnight. Null = no pending change. */
    val pendingLimitValue: Int? = null,
    /** Timestamp (millis) when pendingLimitValue should be applied (= next midnight). */
    val pendingLimitAppliesAt: Long? = null,
)
