package com.detox.app.domain.model

data class DailyStats(
    val challengeId: String,
    val appDisplayName: String,
    val appPackageName: String?,
    val limitType: LimitType,
    val limitValueMinutes: Int,
    val limitValueSessions: Int?,
    /**
     * Length of ONE allowed session in minutes (SESSIONS challenges only) — mirrors
     * [Challenge.sessionDurationMinutes], the same value the overlay's session countdown enforces.
     *
     * Read this for "je N Min.", NEVER [limitValueMinutes]: that field is the TIME-limit cap and is
     * meaningless on a SESSIONS challenge (the group wizard writes its untouched 60-minute default
     * there). Solo happens to store the session length in both fields, which is exactly why reading
     * the wrong one stayed invisible until a group challenge rendered it.
     */
    val sessionDurationMinutes: Int = 5,
    val todayMinutes: Int,
    val todayOpens: Int,
    val limitExceeded: Boolean,
    val customMotivation: String?,
    val daysRemaining: Int,
    /** True for the open-ended ("Kein Enddatum") sentinel — UI shows a label instead of a day count. */
    val isOpenEnded: Boolean = false,
    /** Consecutive-success streak — shown on the card badge for open-ended challenges only (0 otherwise). */
    val streak: Int = 0,
    val moneyLostCents: Int = 0,
    /** Total daily budget (TIME_BUDGET challenges only; null for TIME / SESSIONS). */
    val dailyBudgetMinutes: Int? = null,
    /** Remaining budget at time of last read (TIME_BUDGET challenges only). */
    val budgetRemainingMinutes: Int? = null,
    /** Custom + adult domains being blocked via VPN for this challenge. */
    val blockedDomains: List<String> = emptyList(),
    /** URL path prefixes for feature-level partial blocking, e.g. "instagram.com/reels". */
    val partialBlockDomains: List<String> = emptyList(),
    /** True when this challenge has adult-content blocking enabled. */
    val blockAdultContent: Boolean = false,
    val mode: ChallengeMode = ChallengeMode.SOFT,
    val isGroup: Boolean = false,
    val participantCount: Int = 0,
    val maxParticipants: Int = 0,
    val userRank: Int? = null,
    val appPackageNames: List<String> = emptyList(),
)
