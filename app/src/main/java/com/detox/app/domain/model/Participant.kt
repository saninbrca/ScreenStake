package com.detox.app.domain.model

data class Participant(
    val userId: String,
    val displayName: String,
    val paymentIntentId: String,
    val amountCents: Int,
    val status: ParticipantStatus,
    val opensToday: Int = 0,
    val timeUsedMinutes: Int = 0,
    /** Unix epoch ms when this participant joined (or 0 if unknown). */
    val joinedAt: Long = 0L,
)
