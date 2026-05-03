package com.detox.app.domain.usecase

import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.model.PaymentIntentData
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.GroupChallengeRepository
import timber.log.Timber
import javax.inject.Inject

/** Returned after payment is initiated so the ViewModel can present Stripe PaymentSheet. */
data class JoinPaymentData(
    val paymentData: PaymentIntentData,
    val groupId: String,
)

class JoinGroupChallengeUseCase @Inject constructor(
    private val groupChallengeRepository: GroupChallengeRepository,
    private val challengeRepository: ChallengeRepository,
    private val cloudFunctionsService: CloudFunctionsService
) {

    /**
     * Looks up a group challenge by its invite [code], validates it is joinable,
     * and returns the [GroupChallenge] for preview.
     *
     * @param currentUserId Firebase UID of the user attempting to join — used to check
     *                      that they are not the creator and have not already joined.
     */
    suspend fun fetchByCode(code: String, currentUserId: String): Result<GroupChallenge> {
        val gc = groupChallengeRepository.fetchGroupChallengeByCode(code)
            .getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalArgumentException("No challenge found for code \"$code\"."))

        return when {
            gc.creatorUserId == currentUserId ->
                Result.failure(IllegalStateException("You created this challenge — you're already the first participant!"))
            gc.participants.any { it.userId == currentUserId } ->
                Result.failure(IllegalStateException("You have already joined this challenge."))
            gc.status != GroupChallengeStatus.WAITING ->
                Result.failure(IllegalStateException("This challenge has already started or ended."))
            gc.participants.size >= gc.maxParticipants ->
                Result.failure(IllegalStateException("This challenge is full (${gc.maxParticipants}/${gc.maxParticipants})."))
            gc.startDate > 0L && System.currentTimeMillis() >= gc.startDate ->
                Result.failure(IllegalStateException("The join window for this challenge has closed."))
            else -> {
                // Cross-check: block if user already has an active challenge for any of these apps
                val activeChallenges = challengeRepository.getActiveChallengesList().getOrElse { emptyList() }
                val activePackages = activeChallenges.flatMap { it.appPackageNames }.toSet()
                val conflictPkg = gc.appPackageNames.firstOrNull { it in activePackages }
                if (conflictPkg != null) {
                    val conflictName = activeChallenges
                        .first { conflictPkg in it.appPackageNames }
                        .appDisplayName
                    Result.failure(
                        IllegalStateException(
                            "You already have an active challenge for $conflictName. " +
                                "You cannot join this group challenge."
                        )
                    )
                } else {
                    Result.success(gc)
                }
            }
        }
    }

    /**
     * Confirms the join after [PaymentSheetResult.Completed] — adds the user to the
     * participants array in Firestore via the confirmGroupJoin Cloud Function.
     */
    suspend fun confirmJoin(
        groupId: String,
        userId: String,
        paymentIntentId: String
    ): Result<Unit> = cloudFunctionsService.confirmGroupJoin(groupId, userId, paymentIntentId)

    /**
     * Initiates the buy-in payment for joining [groupId].
     * Returns [JoinPaymentData] with the Stripe client secret to show PaymentSheet.
     */
    suspend fun initiatePayment(
        groupId: String,
        userId: String,
        displayName: String
    ): Result<JoinPaymentData> {
        Timber.d("JoinGroupChallengeUseCase: initiatePayment groupId=%s userId=%s", groupId, userId)
        return cloudFunctionsService.joinGroupChallenge(groupId, userId, displayName)
            .map { paymentData -> JoinPaymentData(paymentData, groupId) }
    }
}
