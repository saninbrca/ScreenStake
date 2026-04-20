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
    /** Conscious opens: incremented only when the user taps "Yes, open it" in the session overlay. */
    @ColumnInfo(defaultValue = "0") val consciousOpens: Int = 0,
    /** Total milliseconds during which an overlay was shown over this app today.
     *  Subtracted from raw UsageStats time so overlay wait-time doesn't count against the limit. */
    @ColumnInfo(defaultValue = "0") val overlayPausedMs: Long = 0L,
    /** Minutes of budget consumed today (TIME_BUDGET challenges only). */
    @ColumnInfo(defaultValue = "0") val budgetUsedMinutes: Int = 0,
    /** Minutes of budget remaining at end of day / when log was last written (TIME_BUDGET only). */
    @ColumnInfo(defaultValue = "0") val budgetRemainingMinutes: Int = 0,
    val pointsEarned: Int,
    val limitExceeded: Boolean,
    val moneyLostCents: Int,
    /** True once the 50 % threshold notification has been shown for this day. */
    @ColumnInfo(defaultValue = "0") val notified50: Boolean = false,
    /** True once the 75 % threshold notification has been shown for this day. */
    @ColumnInfo(defaultValue = "0") val notified75: Boolean = false,
    /** True once the 90 % threshold notification has been shown for this day. */
    @ColumnInfo(defaultValue = "0") val notified90: Boolean = false,
)
