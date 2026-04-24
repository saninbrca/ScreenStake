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
)
