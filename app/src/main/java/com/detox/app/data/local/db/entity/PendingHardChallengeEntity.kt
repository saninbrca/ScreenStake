package com.detox.app.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.detox.app.domain.model.BlockingType
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.PartialBlockSection
import com.detox.app.util.DateUtils

/**
 * MONEY-CRITICAL durable record of a Hard Mode challenge whose PaymentIntent has been created but
 * whose challenge doc has NOT yet been confirmed-persisted.
 *
 * Written the instant `createPaymentIntent` succeeds (before the Stripe PaymentSheet even shows), so
 * the full creation payload survives ViewModel/Activity/process recreation during the payment round
 * trip. Without it, a recreated ViewModel lost its in-memory `confirmedPaymentIntentId` and silently
 * aborted challenge creation AFTER the stake was already captured (>7-day challenges use automatic
 * capture). `onPaymentConfirmed()` rebuilds from this record; a startup recovery net re-creates any
 * doc that was still never written. Re-creating uses the SAME [challengeId]/[paymentIntentId] (a
 * merge-set under the unified cid) so it is fully idempotent and NEVER mints a new PaymentIntent.
 *
 * The row is deleted as soon as the challenge doc is confirmed written.
 */
@Entity(tableName = "pending_hard_challenges")
data class PendingHardChallengeEntity(
    /** Unified challenge id (== Stripe metadata.challengeId). Primary key. */
    @PrimaryKey val challengeId: String,
    val paymentIntentId: String,
    /** When the PaymentIntent was created — used to skip manual-capture pre-auths past their window. */
    val paymentIntentCreatedAt: Long,
    /** 1 when durationDays > 7 → Stripe automatic capture (stake taken immediately, never expires). */
    val isImmediateCapture: Int,
    val appDisplayName: String,
    /** Comma-separated tracked package names (may be empty for WEBSITE challenges). */
    val appPackageNames: String,
    val limitType: String,
    val limitValueMinutes: Int,
    val limitValueSessions: Int?,
    val durationDays: Int,
    val amountCents: Int,
    val customMotivation: String?,
    val blockedDomains: String,
    val partialBlockDomains: String,
    val blockingType: String,
    val blockAdultContent: Int,
    val scheduleStartTime: String?,
    val scheduleEndTime: String?,
    val activeDays: String,
    val sessionDurationMinutes: Int,
    val dailyBudgetMinutes: Int?,
    val partialBlockSections: String,
    val isPartialBlockOnly: Int,
    val deviceId: String?,
    val isRooted: Int?,
)

private fun String.toCsvList(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotBlank() }

/**
 * Rebuilds the domain [Challenge] from the durable record. [now] is the start instant — the challenge
 * runs from when it is actually persisted (mirrors `CreateChallengeUseCase`). HARD mode + ACTIVE
 * status are implied (this table only ever holds Hard Mode payment payloads).
 */
fun PendingHardChallengeEntity.toChallenge(now: Long): Challenge {
    val packageNames = appPackageNames.toCsvList()
    val type = LimitType.valueOf(limitType.uppercase())
    return Challenge(
        id = challengeId,
        appPackageName = packageNames.firstOrNull(),
        appPackageNames = packageNames,
        appDisplayName = appDisplayName,
        mode = ChallengeMode.HARD,
        limitType = type,
        limitValueMinutes = limitValueMinutes,
        limitValueSessions = limitValueSessions,
        startDate = now,
        endDate = DateUtils.endOfDayMillis(now, durationDays),
        amountCents = amountCents,
        stripePaymentIntentId = paymentIntentId,
        customMotivation = customMotivation,
        status = ChallengeStatus.ACTIVE,
        createdAt = now,
        dailyBudgetMinutes = if (type == LimitType.TIME_BUDGET) dailyBudgetMinutes else null,
        blockedDomains = blockedDomains.toCsvList(),
        partialBlockDomains = partialBlockDomains.toCsvList(),
        blockingType = runCatching { BlockingType.valueOf(blockingType.uppercase()) }
            .getOrDefault(BlockingType.APP),
        blockAdultContent = blockAdultContent != 0,
        scheduleStartTime = scheduleStartTime,
        scheduleEndTime = scheduleEndTime,
        activeDays = activeDays.toCsvList(),
        sessionDurationMinutes = if (type == LimitType.SESSIONS) sessionDurationMinutes else 5,
        partialBlockSections = partialBlockSections.toCsvList()
            .mapNotNull { PartialBlockSection.fromId(it) },
        isPartialBlockOnly = isPartialBlockOnly != 0,
        deviceId = deviceId,
        isRooted = isRooted?.let { it != 0 },
    )
}
