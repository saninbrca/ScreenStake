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
     * Cancels a pre-auth or refunds a captured payment (used on challenge success).
     * PI status is auto-detected by the Cloud Function — no wasImmediate flag needed.
     * Optional params trigger a Firestore payout-status update on the challenge document.
     * Pass [partialRefundCents] to issue a partial refund (Redemption Challenge win).
     */
    suspend fun cancelOrRefundPayment(
        paymentIntentId: String,
        challengeId: String? = null,
        userId: String? = null,
        amountCents: Int? = null,
        partialRefundCents: Int? = null
    ): Result<Unit>

    /**
     * Creates a Stripe Custom Connected Account with the given IBAN for prize payouts.
     * Stores stripeConnectedAccountId + payoutIban in Firestore.
     */
    suspend fun setupPayoutAccount(iban: String, accountHolderName: String, userId: String): Result<Unit>
}
