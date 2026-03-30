package com.detox.app.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "challenges")
data class ChallengeEntity(
    @PrimaryKey val id: String,
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
    val emergencyCode: String?,
    val customMotivation: String?,
    val status: String,
    val createdAt: Long
)
