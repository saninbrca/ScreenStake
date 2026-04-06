package com.detox.app.domain.model

data class GroupChallenge(
    val groupId: String,
    val code: String,
    val creatorUserId: String,
    val appPackageNames: List<String>,
    val appDisplayName: String,
    val limitType: LimitType,
    val limitValueMinutes: Int,
    val limitValueSessions: Int?,
    val durationDays: Int,
    val buyInCents: Int,
    val maxParticipants: Int,
    val startDate: Long,
    val endDate: Long,
    val bonusEnabled: Boolean,
    val status: GroupChallengeStatus,
    val participants: List<Participant>
)
