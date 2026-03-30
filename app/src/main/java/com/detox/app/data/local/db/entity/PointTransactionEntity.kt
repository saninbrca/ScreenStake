package com.detox.app.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "point_transactions",
    indices = [Index(value = ["challengeId"])]
)
data class PointTransactionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val amount: Int,
    val reason: String,
    val challengeId: String?,
    val timestamp: Long
)
