package com.detox.app.domain.model

data class DailyStats(
    val challengeId: String,
    val appDisplayName: String,
    val appPackageName: String?,
    val limitType: LimitType,
    val limitValueMinutes: Int,
    val limitValueSessions: Int?,
    val todayMinutes: Int,
    val todayOpens: Int,
    val limitExceeded: Boolean,
    val customMotivation: String?,
    val daysRemaining: Int,
    val moneyLostCents: Int = 0,
    /** Total daily budget (TIME_BUDGET challenges only; null for TIME / SESSIONS). */
    val dailyBudgetMinutes: Int? = null,
    /** Remaining budget at time of last read (TIME_BUDGET challenges only). */
    val budgetRemainingMinutes: Int? = null,
    /** Custom + adult domains being blocked via VPN for this challenge. */
    val blockedDomains: List<String> = emptyList(),
    /** True when this challenge has adult-content blocking enabled. */
    val blockAdultContent: Boolean = false
)
