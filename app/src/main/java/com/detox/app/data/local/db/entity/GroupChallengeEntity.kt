package com.detox.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "group_challenges")
data class GroupChallengeEntity(
    @PrimaryKey val groupId: String,
    val code: String,
    val creatorUserId: String,
    /** Comma-separated app package names. */
    @ColumnInfo(defaultValue = "") val appPackageNames: String,
    val appDisplayName: String,
    val limitType: String,
    val limitValueMinutes: Int,
    val limitValueSessions: Int?,
    /** Duration of each allowed session in minutes (SESSIONS challenges only). Default 5. */
    @ColumnInfo(defaultValue = "5") val sessionDurationMinutes: Int = 5,
    val durationDays: Int,
    @ColumnInfo(name = "buyInCents") val buyInCents: Int,
    val maxParticipants: Int,
    val startDate: Long,
    val endDate: Long,
    /** 0 = false, 1 = true. */
    @ColumnInfo(defaultValue = "0") val bonusEnabled: Int,
    @ColumnInfo(defaultValue = "waiting") val status: String,
    /** JSON array of participant objects. */
    @ColumnInfo(defaultValue = "[]") val participantsJson: String,
    /** Comma-separated blocked website domains. */
    @ColumnInfo(name = "blockedDomains", defaultValue = "NULL") val blockedDomains: String? = null,
    /** 0 = false, 1 = true. Enforces the adult blocklist (global flag, mirrors Solo/Hard). */
    @ColumnInfo(defaultValue = "0") val blockAdultContent: Int = 0,
    /** Unix ms when the 5-day authorization window expires. 0 = not set (legacy). */
    @ColumnInfo(defaultValue = "0") val authorizationExpiresAt: Long = 0L,
)
