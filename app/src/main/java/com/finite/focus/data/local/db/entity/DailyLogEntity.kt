package com.finite.focus.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.finite.focus.domain.model.DailyLog

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
    /** Milliseconds of budget consumed today (TIME_BUDGET challenges only). Source of truth; minutes fields kept for schema compat. */
    @ColumnInfo(defaultValue = "0") val budgetUsedMs: Long = 0L,
    /** Milliseconds of budget remaining today (TIME_BUDGET only). */
    @ColumnInfo(defaultValue = "0") val budgetRemainingMs: Long = 0L,
    val pointsEarned: Int,
    val limitExceeded: Boolean,
    val moneyLostCents: Int,
)

/**
 * Domain [DailyLog] → [DailyLogEntity]. Single owner of this direction: it used to be duplicated
 * privately in `DailyLogRepositoryImpl` (write path) and `SyncRepositoryImpl` (sync-down path), and
 * the two drifted — the sync copy silently omitted `budgetUsedMs`/`budgetRemainingMs`, so every
 * sync-down REPLACE zeroed the live budget columns. Keep it in one place so the next added column
 * can't be forgotten on one side.
 *
 * Straight field-for-field copy, no defaulting: callers that need to protect a live-tracking column
 * from a full-row REPLACE must merge against the existing row themselves (see `SyncRepositoryImpl`
 * sync step 2) — a mapper cannot know what the current row holds.
 */
fun DailyLog.toEntity(): DailyLogEntity = DailyLogEntity(
    id = id,
    challengeId = challengeId,
    date = date,
    totalMinutes = totalMinutes,
    openCount = openCount,
    consciousOpens = consciousOpens,
    overlayPausedMs = overlayPausedMs,
    budgetUsedMinutes = budgetUsedMinutes,
    budgetRemainingMinutes = budgetRemainingMinutes,
    budgetUsedMs = budgetUsedMs,
    budgetRemainingMs = budgetRemainingMs,
    pointsEarned = pointsEarned,
    limitExceeded = limitExceeded,
    moneyLostCents = moneyLostCents,
)
