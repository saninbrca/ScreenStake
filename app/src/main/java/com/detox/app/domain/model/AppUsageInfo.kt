package com.detox.app.domain.model

import android.graphics.drawable.Drawable

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val avgDailyMinutes: Long,
    val avgDailyOpens: Int,
    val isTrackable: Boolean
)
