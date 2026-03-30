package com.detox.app.domain.model

data class Challenge(
    val id: String,
    val appPackageName: String,
    val appDisplayName: String,
    val mode: ChallengeMode,
    val limitType: LimitType,
    val limitValueMinutes: Int,
    val limitValueSessions: Int?,
    val startDate: Long,
    val endDate: Long,
    val amountCents: Int?,
    val stripePaymentIntentId: String?,
    val emergencyCode: String?,
    val customMotivation: String?,
    val status: ChallengeStatus,
    val createdAt: Long
)
