package com.detox.app.domain.model

data class DailyStats(
    val challengeId: String,
    val appDisplayName: String,
    val appPackageName: String,
    val limitType: LimitType,
    val limitValueMinutes: Int,
    val limitValueSessions: Int?,
    val todayMinutes: Int,
    val todayOpens: Int,
    val pointsEarnedToday: Int,
    val limitExceeded: Boolean,
    val customMotivation: String?,
    val daysRemaining: Int,
    val moneyLostCents: Int = 0
)
