package com.detox.app.domain.model

data class Taunt(
    val id: String,
    val fromUserId: String,
    val fromDisplayName: String,
    val toUserId: String,
    val message: String,
    val createdAt: Long,
    val shown: Boolean = false,
)
