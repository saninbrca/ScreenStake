package com.finite.focus.domain.usecase

import com.finite.focus.domain.model.PaymentIntentData
import com.finite.focus.domain.repository.PaymentRepository
import javax.inject.Inject

/**
 * Prepares the Stripe payment for a Hard Mode challenge.
 *
 * Returns [PaymentIntentData] containing the client secret needed to present Stripe PaymentSheet.
 * The actual charge is deferred (pre-auth) unless the challenge is longer than 7 days.
 */
class ProcessPaymentUseCase @Inject constructor(
    private val paymentRepository: PaymentRepository
) {
    suspend operator fun invoke(
        amountCents: Int,
        durationDays: Int,
        challengeId: String
    ): Result<PaymentIntentData> {
        if (amountCents <= 0) {
            return Result.failure(IllegalArgumentException("Amount must be greater than 0"))
        }
        if (durationDays <= 0) {
            return Result.failure(IllegalArgumentException("Duration must be greater than 0"))
        }
        return paymentRepository.prepareHardModePayment(amountCents, durationDays, challengeId)
    }
}
