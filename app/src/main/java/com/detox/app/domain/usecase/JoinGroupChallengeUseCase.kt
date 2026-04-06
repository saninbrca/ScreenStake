package com.detox.app.domain.usecase

import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.PaymentIntentData
import com.detox.app.domain.repository.GroupChallengeRepository
import timber.log.Timber
import javax.inject.Inject

class JoinGroupChallengeUseCase @Inject constructor(
    private val groupChallengeRepository: GroupChallengeRepository,
    private val cloudFunctionsService: CloudFunctionsService
) {

    /**
     * Looks up a group challenge by its invite [code], validates it, and returns
     * the [PaymentIntentData] the caller needs to present the Stripe PaymentSheet.
     *
     * The participant is **not** added until Stripe payment is confirmed — that is
     * handled server-side by the `joinGroupChallenge` Cloud Function reacting to the
     * confirmed PaymentIntent webhook.
     */
    suspend fun fetchByCode(code: String): Result<GroupChallenge> {
        val gc = groupChallengeRepository.fetchGroupChallengeByCode(code)
            .getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalArgumentException("No challenge found for code \"$code\"."))

        return when {
            gc.status != com.detox.app.domain.model.GroupChallengeStatus.WAITING ->
                Result.failure(IllegalStateException("This challenge has already started or ended."))
            gc.participants.size >= gc.maxParticipants ->
                Result.failure(IllegalStateException("This challenge is full (${gc.maxParticipants}/${gc.maxParticipants})."))
            System.currentTimeMillis() >= gc.startDate ->
                Result.failure(IllegalStateException("The join window for this challenge has closed."))
            else -> Result.success(gc)
        }
    }

    /**
     * Initiates the buy-in payment for joining [groupId].
     * Returns [PaymentIntentData] with the Stripe client secret to show PaymentSheet.
     */
    suspend fun initiatePayment(
        groupId: String,
        userId: String,
        displayName: String
    ): Result<PaymentIntentData> {
        Timber.d("JoinGroupChallengeUseCase: initiatePayment groupId=%s userId=%s", groupId, userId)
        return cloudFunctionsService.joinGroupChallenge(groupId, userId, displayName)
    }
}
