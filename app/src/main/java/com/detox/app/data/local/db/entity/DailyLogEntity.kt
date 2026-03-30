package com.detox.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "daily_logs",
    foreignKeys = [
        ForeignKey(
            entity = ChallengeEntity::class,
            parentColumns = ["id"],
            childColumns = ["challengeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["challengeId", "date"], unique = true),
        Index(value = ["challengeId"])
    ]
)
data class DailyLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "challengeId") val challengeId: String,
    val date: Long,
    val totalMinutes: Int,
    val openCount: Int,
    val pointsEarned: Int,
    val limitExceeded: Boolean,
    val moneyLostCents: Int
)
