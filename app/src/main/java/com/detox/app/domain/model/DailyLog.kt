package com.detox.app.domain.model

data class DailyLog(
    val id: String,
    val challengeId: String,
    val date: Long,
    val totalMinutes: Int,
    val openCount: Int,
    val pointsEarned: Int,
    val limitExceeded: Boolean,
    val moneyLostCents: Int
)
