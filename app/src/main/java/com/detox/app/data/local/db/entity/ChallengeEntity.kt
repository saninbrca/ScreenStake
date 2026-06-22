package com.detox.app.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "challenges")
data class ChallengeEntity(
    @PrimaryKey val id: String,
    /** Empty string ("") is used as a sentinel for WEBSITE-type challenges (no app). */
    val appPackageName: String,
    val appDisplayName: String,
    val mode: String,
    val limitType: String,
    val limitValueMinutes: Int,
    val limitValueSessions: Int?,
    val startDate: Long,
    val endDate: Long,
    val amountCents: Int?,
    val stripePaymentIntentId: String?,
    val customMotivation: String?,
    val status: String,
    val createdAt: Long,
    /** Total daily budget in minutes (TIME_BUDGET challenges only). */
    @androidx.room.ColumnInfo(name = "dailyBudgetMinutes", defaultValue = "NULL") val dailyBudgetMinutes: Int? = null,
    /** Comma-separated list of all tracked package names (replaces the single appPackageName for multi-app challenges). */
    @androidx.room.ColumnInfo(name = "appPackageNames", defaultValue = "NULL") val appPackageNames: String? = null,
    /** Comma-separated blocked domains for website blocking (preset + custom). */
    @androidx.room.ColumnInfo(name = "blockedDomains", defaultValue = "NULL") val blockedDomains: String? = null,
    /** "app" or "website" — determines the challenge blocking strategy. */
    @androidx.room.ColumnInfo(name = "blockingType", defaultValue = "app") val blockingType: String = "app",
    /** 1 if adult content domains are blocked alongside the main target, 0 otherwise. */
    @androidx.room.ColumnInfo(name = "blockAdultContent", defaultValue = "0") val blockAdultContent: Int = 0,
    /** Start of the active enforcement window (HH:mm), e.g. "09:00". Null = always active. */
    @androidx.room.ColumnInfo(name = "scheduleStartTime", defaultValue = "NULL") val scheduleStartTime: String? = null,
    /** End of the active enforcement window (HH:mm), e.g. "22:00". Null = always active. */
    @androidx.room.ColumnInfo(name = "scheduleEndTime", defaultValue = "NULL") val scheduleEndTime: String? = null,
    /** Comma-separated days this challenge enforces, e.g. "MON,TUE,WED". Null = every day. */
    @androidx.room.ColumnInfo(name = "activeDays", defaultValue = "NULL") val activeDays: String? = null,
    /** 1 once the in-app congratulations overlay has been shown for a completed Hard Mode challenge. */
    @androidx.room.ColumnInfo(name = "completionShown", defaultValue = "0") val completionShown: Int = 0,
    /** Duration of each allowed session in minutes (SESSIONS challenges only). Default 5 min for existing rows. */
    @androidx.room.ColumnInfo(name = "sessionDurationMinutes", defaultValue = "5") val sessionDurationMinutes: Int = 5,
    /** Non-null when this row was auto-created to locally track a group challenge. Stores the groupId. */
    @androidx.room.ColumnInfo(name = "groupChallengeId", defaultValue = "NULL") val groupChallengeId: String? = null,
    /** Comma-separated URL path prefixes for feature-level partial blocking, e.g. "instagram.com/reels". */
    @androidx.room.ColumnInfo(name = "partialBlockDomains", defaultValue = "NULL") val partialBlockDomains: String? = null,
    /** Comma-separated PartialBlockSection IDs for native in-app section blocking, e.g. "instagram_reels,youtube_shorts". */
    @androidx.room.ColumnInfo(name = "partial_block_sections", defaultValue = "") val partialBlockSections: String = "",
    /** 1 when only sections are blocked, not the full app (package excluded from full-block overlay logic). */
    @androidx.room.ColumnInfo(name = "partial_block_only", defaultValue = "0") val isPartialBlockOnly: Int = 0,

    // ── Redemption Challenge fields ────────────────────────────────────────────

    /** 1 when a Redemption Challenge is available after this challenge failed (Hard Mode Solo only). */
    @androidx.room.ColumnInfo(name = "redemptionEligible", defaultValue = "0") val redemptionEligible: Int = 0,
    /** Timestamp after which the Redemption Challenge option expires (failedAt + 3 days). */
    @androidx.room.ColumnInfo(name = "redemptionDeadline", defaultValue = "NULL") val redemptionDeadline: Long? = null,
    /** Timestamp after which the Redemption banner/button is shown (failedAt + 24h). */
    @androidx.room.ColumnInfo(name = "redemptionShowAfter", defaultValue = "NULL") val redemptionShowAfter: Long? = null,
    /** ID of the Redemption Challenge that was started for this original challenge. Non-null = already used. */
    @androidx.room.ColumnInfo(name = "redemptionChallengeId", defaultValue = "NULL") val redemptionChallengeId: String? = null,
    /** Amount in cents to refund on Redemption win (floor(originalAmountCents * 0.60)). */
    @androidx.room.ColumnInfo(name = "redemptionRefundAmount", defaultValue = "NULL") val redemptionRefundAmount: Int? = null,
    /** Duration in days for the Redemption Challenge (originalDays * 2). */
    @androidx.room.ColumnInfo(name = "redemptionDays", defaultValue = "NULL") val redemptionDays: Int? = null,
    /** Daily limit for the Redemption Challenge (floor(originalLimit / 2), minimum 1). */
    @androidx.room.ColumnInfo(name = "redemptionLimit", defaultValue = "NULL") val redemptionLimit: Int? = null,
    /** 1 when this challenge IS the Redemption Challenge (no new payment, fights for refund). */
    @androidx.room.ColumnInfo(name = "isRedemption", defaultValue = "0") val isRedemption: Int = 0,
    /** For Redemption Challenges: the ID of the original failed Hard Mode challenge. */
    @androidx.room.ColumnInfo(name = "originalChallengeId", defaultValue = "NULL") val originalChallengeId: String? = null,
    /** For Redemption Challenges: the Stripe PaymentIntentId of the original challenge to partially refund on win. */
    @androidx.room.ColumnInfo(name = "originalPaymentIntentId", defaultValue = "NULL") val originalPaymentIntentId: String? = null,
    /** For Redemption Challenges: the partial refund amount in cents (floor(originalAmount * 0.60)). */
    @androidx.room.ColumnInfo(name = "refundAmountCents", defaultValue = "NULL") val refundAmountCents: Int? = null,

    // ── Pending limit reduction ────────────────────────────────────────────────

    /** New limit value to apply at next midnight. Null = no pending change. */
    @androidx.room.ColumnInfo(name = "pending_limit_value", defaultValue = "NULL") val pendingLimitValue: Int? = null,
    /** Timestamp (millis) when pendingLimitValue should be applied (= next midnight). */
    @androidx.room.ColumnInfo(name = "pending_limit_applies_at", defaultValue = "NULL") val pendingLimitAppliesAt: Long? = null,

    /**
     * Why the challenge failed, for the loss result dialog only (UX, not money logic).
     * "limit_exceeded" | "abandon" | "permission_violation" | "usage_violation" | "reconciliation".
     * Null when active/completed or unknown. Written locally at loss time and/or pulled from Firestore
     * on sync; never overwritten by sync once the row is already terminal locally.
     */
    @androidx.room.ColumnInfo(name = "failReason", defaultValue = "NULL") val failReason: String? = null,
)
