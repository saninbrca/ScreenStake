package com.detox.app.domain.model

/**
 * Holds the data returned from the `createPaymentIntent` Cloud Function.
 *
 * @param paymentIntentId the Stripe PaymentIntent ID (stored in [Challenge.stripePaymentIntentId])
 * @param clientSecret    the client secret used to present Stripe PaymentSheet
 * @param isImmediateCapture true when the payment was captured immediately because the challenge
 *                           duration exceeds 7 days (pre-auth would expire). In this case a refund
 *                           is issued on success instead of a cancellation.
 */
data class PaymentIntentData(
    val paymentIntentId: String,
    val clientSecret: String,
    val isImmediateCapture: Boolean
)
