package com.detox.app.domain.usecase

import android.content.Context
import com.detox.app.R
import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.util.DateUtils
import com.detox.app.util.UserFacingException
import com.detox.app.domain.repository.GroupChallengeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/** Returned from [CreateGroupChallengeUseCase.initiatePayment] — used to show PaymentSheet. */
data class CreateGroupChallengePaymentData(
    val groupId: String,
    val code: String,
    val clientSecret: String,
    val paymentIntentId: String,
)

/** Returned from [CreateGroupChallengeUseCase.invoke] after Firestore doc is created. */
data class GroupChallengeCreatedData(
    val groupId: String,
    val code: String,
)

class CreateGroupChallengeUseCase @Inject constructor(
    private val groupChallengeRepository: GroupChallengeRepository,
    private val challengeRepository: ChallengeRepository,
    private val cloudFunctionsService: CloudFunctionsService,
    @ApplicationContext private val context: Context,
) {

    /**
     * Step 1 — validates the form, generates local IDs, and creates a Stripe PaymentIntent
     * via the [createGroupChallengePaymentIntent] Cloud Function.
     *
     * Does NOT write to Firestore. Call [invoke] only after [PaymentSheetResult.Completed].
     */
    suspend fun initiatePayment(
        appPackageNames: List<String>,
        buyInCents: Int,
        durationDays: Int,
        limitType: LimitType,
        limitValueMinutes: Int,
        limitValueSessions: Int?,
    ): Result<CreateGroupChallengePaymentData> {
        if (appPackageNames.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one app to block."))
        }
        if (durationDays !in 3..365) {
            return Result.failure(IllegalArgumentException("Duration must be between 3 and 365 days."))
        }
        if (buyInCents !in 1000..5000) {
            return Result.failure(IllegalArgumentException("Buy-in must be between €10 and €50."))
        }
        if (limitType != LimitType.TIME_BUDGET && limitValueMinutes <= 0) {
            return Result.failure(IllegalArgumentException("Time limit must be greater than 0."))
        }
        if (limitType == LimitType.SESSIONS && (limitValueSessions == null || limitValueSessions <= 0)) {
            return Result.failure(IllegalArgumentException("Session count must be greater than 0."))
        }

        val activeChallenges = challengeRepository.getActiveChallengesList().getOrElse { emptyList() }
        val activePackages = activeChallenges.flatMap { it.appPackageNames }.toSet()
        val conflictPkg = appPackageNames.firstOrNull { it in activePackages }
        if (conflictPkg != null) {
            val conflictName = activeChallenges
                .first { conflictPkg in it.appPackageNames }
                .appDisplayName
            return Result.failure(
                UserFacingException(context.getString(R.string.uc_create_group_conflict, conflictName))
            )
        }

        val groupId = UUID.randomUUID().toString()
        val code = generateCode()

        return cloudFunctionsService.createPaymentIntent(
            amountCents = buyInCents,
            durationDays = durationDays,
            challengeId = groupId,
            isGroupChallenge = true,
        ).map { paymentData ->
            Timber.d("CreateGroupChallengeUseCase: payment intent created groupId=%s", groupId)
            CreateGroupChallengePaymentData(
                groupId = groupId,
                code = code,
                clientSecret = paymentData.clientSecret,
                paymentIntentId = paymentData.paymentIntentId,
            )
        }
    }

    /**
     * Step 2 — called only after [PaymentSheetResult.Completed].
     * Creates the Firestore document using the pre-authorized [paymentIntentId].
     */
    suspend operator fun invoke(
        creatorUserId: String,
        creatorDisplayName: String,
        appPackageNames: List<String>,
        appDisplayName: String,
        limitType: LimitType,
        limitValueMinutes: Int,
        limitValueSessions: Int?,
        sessionDurationMinutes: Int = 5,
        durationDays: Int,
        buyInCents: Int,
        maxParticipants: Int,
        startDateMs: Long,
        bonusEnabled: Boolean,
        blockedDomains: List<String> = emptyList(),
        groupId: String,
        code: String,
        paymentIntentId: String,
    ): Result<GroupChallengeCreatedData> {
        val baseMs = if (startDateMs > 0L) startDateMs else System.currentTimeMillis()
        val endDateMs = DateUtils.endOfDayMillis(baseMs, durationDays)
        Timber.d("endDate stored as: $endDateMs = ${java.util.Date(endDateMs)}")

        val groupDataMap = mapOf(
            "groupId" to groupId,
            "creatorUserId" to creatorUserId,
            "creatorDisplayName" to creatorDisplayName,
            "appPackageNames" to appPackageNames.joinToString(","),
            "appDisplayName" to appDisplayName,
            "limitType" to limitType.name.lowercase(),
            "limitValueMinutes" to limitValueMinutes,
            "limitValueSessions" to limitValueSessions,
            "sessionDurationMinutes" to sessionDurationMinutes,
            "durationDays" to durationDays,
            "buyInCents" to buyInCents,
            "maxParticipants" to maxParticipants,
            "startDate" to startDateMs,
            "endDate" to endDateMs,
            "bonusEnabled" to bonusEnabled,
            "status" to "waiting",
            "blockedDomains" to blockedDomains.joinToString(",").ifEmpty { null }
        )

        val cfResult = cloudFunctionsService.createGroupChallenge(groupId, code, groupDataMap, paymentIntentId)
        if (cfResult.isFailure) {
            Timber.e(cfResult.exceptionOrNull(), "CreateGroupChallengeUseCase: cloud function failed")
            return Result.failure(cfResult.exceptionOrNull()!!)
        }

        val finalCode = cfResult.getOrThrow().code

        // Fetch from Firestore to populate Room — do NOT call saveGroupChallenge which would
        // overwrite the document and erase the creator participant added by the CF.
        Timber.d("CreateGroupChallengeUseCase: challenge created groupId=%s — fetching from Firestore", groupId)
        groupChallengeRepository.fetchAndCacheById(groupId)
            .onFailure { e ->
                Timber.w(e, "CreateGroupChallengeUseCase: fetchAndCacheById failed — continuing without local cache")
            }

        return Result.success(GroupChallengeCreatedData(groupId = groupId, code = finalCode))
    }

    /** Generates a random 6-character uppercase alphanumeric code. */
    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I to avoid confusion
        return (1..6).map { chars.random() }.joinToString("")
    }
}
