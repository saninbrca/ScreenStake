package com.detox.app.domain.repository

import com.detox.app.domain.model.PaymentIntentData

interface PaymentRepository {

    /**
     * Calls the backend to create a Stripe PaymentIntent for a Hard Mode challenge.
     * Returns [PaymentIntentData] containing the client secret for Stripe PaymentSheet
     * and whether the payment was captured immediately.
     */
    suspend fun prepareHardModePayment(
        amountCents: Int,
        durationDays: Int,
        challengeId: String
    ): Result<PaymentIntentData>

    /**
     * Captures a previously pre-authorised payment (used when the user exceeds their limit).
     */
    suspend fun capturePayment(paymentIntentId: String): Result<Unit>

    /**
     * Cancels a pre-auth or refunds an immediately captured payment (used on challenge success).
     *
     * @param wasImmediate true if the charge was captured immediately and a refund is needed.
     */
    suspend fun cancelOrRefundPayment(
        paymentIntentId: String,
        wasImmediate: Boolean
    ): Result<Unit>
}
