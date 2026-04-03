package com.detox.app.domain.usecase

import com.detox.app.domain.model.PointTransaction
import com.detox.app.domain.repository.PointsRepository
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Redeems a shop reward by deducting points.
 *
 * Returns [Result.failure] with a descriptive message if the user has insufficient points.
 * Returns [Result.success] on successful deduction.
 */
class RedeemRewardUseCase @Inject constructor(
    private val pointsRepository: PointsRepository
) {
    suspend operator fun invoke(rewardId: String, pointsCost: Int): Result<Unit> {
        require(pointsCost > 0) { "pointsCost must be positive" }

        val currentBalance = pointsRepository.getTotalPointsBalance().first()
        if (currentBalance < pointsCost) {
            Timber.d("Redemption rejected: balance=$currentBalance < cost=$pointsCost")
            return Result.failure(
                IllegalStateException("Not enough points. You need $pointsCost pts but have $currentBalance pts.")
            )
        }

        return pointsRepository.addPointTransaction(
            PointTransaction(
                id = UUID.randomUUID().toString(),
                type = "spent",
                amount = pointsCost,
                reason = rewardId,
                challengeId = null,
                timestamp = System.currentTimeMillis()
            )
        ).also { result ->
            result.onSuccess {
                Timber.d("Reward redeemed: $rewardId for $pointsCost pts")
            }
        }
    }
}
