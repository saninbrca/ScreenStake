package com.detox.app.data.remote.firebase

import com.detox.app.domain.model.PaymentIntentData
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudFunctionsService @Inject constructor(
    private val functions: FirebaseFunctions
) {

    /**
     * Calls the `createPaymentIntent` Cloud Function.
     * Returns a [PaymentIntentData] with the client secret needed to present Stripe PaymentSheet.
     */
    suspend fun createPaymentIntent(
        amountCents: Int,
        durationDays: Int,
        challengeId: String
    ): Result<PaymentIntentData> {
        return try {
            val data = mapOf(
                "amountCents" to amountCents,
                "durationDays" to durationDays,
                "challengeId" to challengeId
            )
            val result = functions
                .getHttpsCallable("createPaymentIntent")
                .call(data)
                .await()

            @Suppress("UNCHECKED_CAST")
            val response = result.data as Map<String, Any>
            val paymentIntentId = response["paymentIntentId"] as String
            val clientSecret = response["clientSecret"] as String
            val isImmediate = response["isImmediateCapture"] as? Boolean ?: (durationDays > 7)

            Timber.d("Created PaymentIntent $paymentIntentId (immediate=$isImmediate)")
            Result.success(
                PaymentIntentData(
                    paymentIntentId = paymentIntentId,
                    clientSecret = clientSecret,
                    isImmediateCapture = isImmediate
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to create PaymentIntent")
            Result.failure(e)
        }
    }

    /**
     * Calls the `capturePayment` Cloud Function.
     * Used when a Hard Mode limit is exceeded — charges the user's card.
     */
    suspend fun capturePayment(paymentIntentId: String): Result<Unit> {
        return try {
            functions
                .getHttpsCallable("capturePayment")
                .call(mapOf("paymentIntentId" to paymentIntentId))
                .await()
            Timber.d("Captured payment for $paymentIntentId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to capture payment $paymentIntentId")
            Result.failure(e)
        }
    }

    /**
     * Calls the `cancelOrRefundPayment` Cloud Function.
     * Used when a Hard Mode challenge is completed successfully.
     *
     * @param wasImmediate if true the payment was captured immediately (challenge > 7 days)
     *                     and a refund must be issued; otherwise cancel the pre-auth.
     */
    suspend fun cancelOrRefundPayment(
        paymentIntentId: String,
        wasImmediate: Boolean
    ): Result<Unit> {
        return try {
            functions
                .getHttpsCallable("cancelOrRefundPayment")
                .call(
                    mapOf(
                        "paymentIntentId" to paymentIntentId,
                        "wasImmediate" to wasImmediate
                    )
                )
                .await()
            Timber.d("Cancelled/refunded payment $paymentIntentId (wasImmediate=$wasImmediate)")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to cancel/refund payment $paymentIntentId")
            Result.failure(e)
        }
    }
}
