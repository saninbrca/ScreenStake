package com.detox.app.domain.model

data class GroupChallenge(
    val groupId: String,
    val code: String,
    val creatorUserId: String,
    val creatorDisplayName: String = "",
    val appPackageNames: List<String>,
    val appDisplayName: String,
    val limitType: LimitType,
    val limitValueMinutes: Int,
    val limitValueSessions: Int?,
    /** Duration of each allowed session in minutes (SESSIONS challenges only). Default 5. */
    val sessionDurationMinutes: Int = 5,
    val durationDays: Int,
    val buyInCents: Int,
    val maxParticipants: Int,
    val startDate: Long,
    val endDate: Long,
    val bonusEnabled: Boolean,
    val status: GroupChallengeStatus,
    val participants: List<Participant>,
    val blockedDomains: List<String> = emptyList(),
    /** When true, the 133k-domain adult blocklist is enforced (global flag, mirrors Solo/Hard). */
    val blockAdultContent: Boolean = false,
    /** Bonus transferred to each winner in cents (set after completion). */
    val perWinnerBonus: Int = 0,
    /** Unix ms when the 5-day authorization window expires. 0 = not set (legacy). */
    val authorizationExpiresAt: Long = 0L,
)
