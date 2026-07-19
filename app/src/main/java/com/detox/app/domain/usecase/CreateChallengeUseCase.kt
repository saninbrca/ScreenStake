package com.detox.app.domain.usecase

import android.content.Context
import com.detox.app.BuildConfig
import com.detox.app.domain.model.BlockingType
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.PartialBlockSection
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.GroupChallengeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import com.detox.app.R
import com.detox.app.util.DateUtils
import com.detox.app.util.UserFacingException
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

data class ChallengeCreationResult(
    val challengeId: String
)

class CreateChallengeUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val groupChallengeRepository: GroupChallengeRepository,
    @ApplicationContext private val context: Context,
) {
    suspend operator fun invoke(
        appPackageName: String?,
        appDisplayName: String,
        limitType: LimitType,
        limitValueMinutes: Int,
        limitValueSessions: Int?,
        durationDays: Int,
        customMotivation: String?,
        mode: ChallengeMode = ChallengeMode.SOFT,
        amountCents: Int? = null,
        stripePaymentIntentId: String? = null,
        dailyBudgetMinutes: Int? = null,
        appPackageNames: List<String> = if (appPackageName != null) listOf(appPackageName) else emptyList(),
        blockedDomains: List<String> = emptyList(),
        partialBlockDomains: List<String> = emptyList(),
        blockingType: BlockingType = BlockingType.APP,
        blockAdultContent: Boolean = false,
        scheduleStartTime: String? = null,
        scheduleEndTime: String? = null,
        activeDays: List<String> = emptyList(),
        sessionDurationMinutes: Int = 5,
        partialBlockSections: List<PartialBlockSection> = emptyList(),
        isPartialBlockOnly: Boolean = false,
        /** Anti-cheat: Settings.Secure.ANDROID_ID — passed only for Hard Mode creation. */
        deviceId: String? = null,
        /** Anti-cheat: RootBeer result — passed only for Hard Mode creation (true AND false). */
        isRooted: Boolean? = null,
        /**
         * Money-critical (Hard Mode): the id already sent to createPaymentIntent (Stripe
         * metadata.challengeId). When provided, the challenge is persisted under THIS id so the
         * PaymentIntent and the challenge doc share one cid. When null (Soft Mode), a fresh id is
         * minted. Never let Hard Mode mint its own id — it would orphan the payment.
         */
        challengeId: String? = null,
    ): Result<ChallengeCreationResult> {
        // TIME_BUDGET and TIME_WINDOW challenges don't use limitValueMinutes as a usage cap
        if (limitType != LimitType.TIME_BUDGET && limitType != LimitType.TIME_WINDOW && limitValueMinutes <= 0) {
            return Result.failure(IllegalArgumentException("Limit minutes must be greater than 0"))
        }
        if (limitType == LimitType.TIME_BUDGET && (dailyBudgetMinutes == null || dailyBudgetMinutes <= 0)) {
            return Result.failure(IllegalArgumentException("Daily budget must be greater than 0"))
        }
        // NO_END_DATE_DAYS is the canonical open-ended sentinel (drives a far-future endDate);
        // recognise it here so the open-ended Soft path validates instead of failing the 1..365 gate.
        if (durationDays != DateUtils.NO_END_DATE_DAYS && durationDays !in 1..365) {
            return Result.failure(IllegalArgumentException("Duration must be between 1 and 365 days"))
        }
        if (limitType == LimitType.SESSIONS && (limitValueSessions == null || limitValueSessions <= 0)) {
            return Result.failure(IllegalArgumentException("Session limit must be greater than 0"))
        }
        if (mode == ChallengeMode.HARD && (amountCents == null || amountCents <= 0)) {
            return Result.failure(IllegalArgumentException("Hard Mode requires a positive amount"))
        }
        if (blockingType == BlockingType.APP && !isPartialBlockOnly) {
            require(appPackageNames.isNotEmpty()) { "appPackageNames must not be empty for APP challenges" }
            // Check for conflicting solo challenge (includes synced active group challenges)
            val existingChallenge = challengeRepository.getActiveChallengeForApp(appPackageNames.first())
            if (existingChallenge.isSuccess && existingChallenge.getOrNull() != null) {
                val name = existingChallenge.getOrNull()!!.appDisplayName
                return Result.failure(
                    UserFacingException(context.getString(R.string.uc_conflict_active_challenge, name))
                )
            }
            // Also check group_challenges table directly (safety net for recently-started group challenges)
            for (pkg in appPackageNames) {
                val groupConflict = groupChallengeRepository.getActiveGroupChallengeForApp(pkg)
                if (groupConflict != null) {
                    return Result.failure(
                        UserFacingException(
                            context.getString(
                                R.string.uc_conflict_active_group_challenge,
                                groupConflict.appDisplayName
                            )
                        )
                    )
                }
            }
        }
        // WEBSITE challenges must carry ≥1 active blocking source: at least one custom domain OR
        // adult-content blocking. (An adult-only challenge with no domains is valid — do NOT require
        // non-empty blockedDomains.) Defense-in-depth backstop for the wizard's tab-aware Step-2 gate;
        // catches any non-wizard path. NOTE: for Hard Mode this runs AFTER payment, so the wizard gate
        // is the real pre-payment guard — this only prevents persisting a blocks-nothing doc.
        if (blockingType == BlockingType.WEBSITE && blockedDomains.isEmpty() && !blockAdultContent) {
            return Result.failure(
                IllegalArgumentException("Website challenges must block at least one domain or adult content")
            )
        }

        val id = challengeId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val durationMultiplier = if (BuildConfig.DEBUG) {
            val prefs = context.getSharedPreferences("detox_settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("debug_use_minutes_as_days", false)) 60_000L else DateUtils.MILLIS_PER_DAY
        } else {
            DateUtils.MILLIS_PER_DAY
        }
        val endDate = if (BuildConfig.DEBUG && durationMultiplier != DateUtils.MILLIS_PER_DAY) {
            now + durationDays * durationMultiplier
        } else {
            DateUtils.endOfDayMillis(now, durationDays)
        }
        val diffMs = endDate - now
        Timber.d("Challenge created: durationDays=$durationDays startDate=$now endDate=$endDate diff=${diffMs}ms = ${diffMs / DateUtils.MILLIS_PER_DAY}days (${diffMs / 60_000}min)")

        val challenge = Challenge(
            id = id,
            appPackageName = appPackageNames.firstOrNull(),
            appPackageNames = appPackageNames,
            appDisplayName = appDisplayName,
            mode = mode,
            limitType = limitType,
            limitValueMinutes = limitValueMinutes,
            limitValueSessions = limitValueSessions,
            startDate = now,
            endDate = endDate,
            amountCents = if (mode == ChallengeMode.HARD) amountCents else null,
            stripePaymentIntentId = if (mode == ChallengeMode.HARD) stripePaymentIntentId else null,
            customMotivation = customMotivation,
            status = ChallengeStatus.ACTIVE,
            createdAt = now,
            dailyBudgetMinutes = if (limitType == LimitType.TIME_BUDGET) dailyBudgetMinutes else null,
            blockedDomains = blockedDomains,
            partialBlockDomains = partialBlockDomains,
            blockingType = blockingType,
            blockAdultContent = blockAdultContent,
            scheduleStartTime = scheduleStartTime,
            scheduleEndTime = scheduleEndTime,
            activeDays = activeDays,
            sessionDurationMinutes = if (limitType == LimitType.SESSIONS) sessionDurationMinutes else 5,
            partialBlockSections = partialBlockSections,
            isPartialBlockOnly = isPartialBlockOnly,
            // Anti-cheat metadata — only set for Hard Mode (null on Soft Mode).
            deviceId = if (mode == ChallengeMode.HARD) deviceId else null,
            isRooted = if (mode == ChallengeMode.HARD) isRooted else null,
        )

        Timber.d(
            "Creating challenge: limitValueSessions=$limitValueSessions sessionDurationMinutes=$sessionDurationMinutes"
        )
        Timber.d(
            "CreateChallengeUseCase: type=$blockingType domains=$blockedDomains adult=$blockAdultContent"
        )

        return challengeRepository.createChallenge(challenge).map {
            ChallengeCreationResult(challengeId = id)
        }
    }
}
