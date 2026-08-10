package com.finite.focus.domain.model

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val avgDailyMinutes: Long,
    val avgDailyOpens: Int,
    val isTrackable: Boolean
)
