package com.detox.app.domain.model

data class DailyLog(
    val id: String,
    val challengeId: String,
    val date: Long,
    val totalMinutes: Int,
    val openCount: Int,
    val consciousOpens: Int = 0,
    /** Total milliseconds during which an overlay was visible over this challenge's app today.
     *  Subtracted from raw UsageStats time so overlay-while-waiting doesn't eat into the limit. */
    val overlayPausedMs: Long = 0L,
    /** Minutes of budget consumed today (TIME_BUDGET challenges only). */
    val budgetUsedMinutes: Int = 0,
    /** Minutes of budget remaining at end of day / when log was written (TIME_BUDGET only). */
    val budgetRemainingMinutes: Int = 0,
    val pointsEarned: Int,
    val limitExceeded: Boolean,
    val moneyLostCents: Int
)
