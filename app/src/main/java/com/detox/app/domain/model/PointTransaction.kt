package com.detox.app.domain.model

data class PointTransaction(
    val id: String,
    val type: String,
    val amount: Int,
    val reason: String,
    val challengeId: String?,
    val timestamp: Long
)
