package com.detox.app.data.remote.firebase

import com.detox.app.domain.model.PaymentIntentData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudFunctionsService @Inject constructor(
    private val functions: FirebaseFunctions,
    private val firebaseAuth: FirebaseAuth
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
        // Log auth state before calling the function — the Cloud Function rejects the call
        // with UNAUTHENTICATED if no valid Firebase ID token is attached to the request.
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null) {
            Timber.w("createPaymentIntent: FirebaseAuth.currentUser is NULL — call will be rejected as UNAUTHENTICATED")
        } else {
            Timber.d("createPaymentIntent: auth OK — uid=%s email=%s", currentUser.uid, currentUser.email)
        }

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

    // ── Group Challenge Cloud Functions ────────────────────────────────────────

    /**
     * Creates a group challenge document server-side, validates the generated code for
     * uniqueness, and persists all settings to Firestore.
     *
     * Expected input:  { groupId, code, groupData: {...} }
     * Returns:         { code: String }
     */
    suspend fun createGroupChallenge(
        groupId: String,
        code: String,
        groupData: Map<String, Any?>
    ): Result<String> {
        return try {
            val result = functions
                .getHttpsCallable("createGroupChallenge")
                .call(mapOf("groupId" to groupId, "code" to code, "groupData" to groupData))
                .await()
            @Suppress("UNCHECKED_CAST")
            val response = result.data as Map<String, Any>
            val returnedCode = response["code"] as String
            Timber.d("createGroupChallenge: groupId=%s code=%s", groupId, returnedCode)
            Result.success(returnedCode)
        } catch (e: Exception) {
            Timber.e(e, "createGroupChallenge failed groupId=%s", groupId)
            Result.failure(e)
        }
    }

    /**
     * Validates the group code, creates a Stripe PaymentIntent for the buy-in, and
     * adds the participant to the group challenge.
     *
     * Expected input:  { groupId, userId, displayName }
     * Returns:         { paymentIntentId, clientSecret }
     */
    suspend fun joinGroupChallenge(
        groupId: String,
        userId: String,
        displayName: String
    ): Result<com.detox.app.domain.model.PaymentIntentData> {
        return try {
            val result = functions
                .getHttpsCallable("joinGroupChallenge")
                .call(mapOf("groupId" to groupId, "userId" to userId, "displayName" to displayName))
                .await()
            @Suppress("UNCHECKED_CAST")
            val response = result.data as Map<String, Any>
            val paymentIntentId = response["paymentIntentId"] as String
            val clientSecret = response["clientSecret"] as String
            Timber.d("joinGroupChallenge: groupId=%s userId=%s", groupId, userId)
            Result.success(
                com.detox.app.domain.model.PaymentIntentData(
                    paymentIntentId = paymentIntentId,
                    clientSecret = clientSecret,
                    isImmediateCapture = true
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "joinGroupChallenge failed groupId=%s", groupId)
            Result.failure(e)
        }
    }

    /**
     * Checks if a group challenge has enough participants (≥ 2) and either activates it
     * or cancels it with full refunds. Should be called at `startDate`.
     */
    suspend fun startGroupChallenge(groupId: String): Result<Unit> {
        return try {
            functions
                .getHttpsCallable("startGroupChallenge")
                .call(mapOf("groupId" to groupId))
                .await()
            Timber.d("startGroupChallenge: groupId=%s", groupId)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "startGroupChallenge failed groupId=%s", groupId)
            Result.failure(e)
        }
    }

    /**
     * Marks a participant as FAILED, captures their Stripe payment, and sends
     * push notifications to all other participants.
     */
    suspend fun failGroupParticipant(groupId: String, userId: String): Result<Unit> {
        return try {
            functions
                .getHttpsCallable("failParticipant")
                .call(mapOf("groupId" to groupId, "userId" to userId))
                .await()
            Timber.d("failGroupParticipant: groupId=%s userId=%s", groupId, userId)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "failGroupParticipant failed groupId=%s userId=%s", groupId, userId)
            Result.failure(e)
        }
    }

    /**
     * Runs end-of-challenge payout logic: refunds winners, distributes pot from
     * failed participants, optionally awards bonus to best performer.
     */
    suspend fun completeGroupChallenge(groupId: String): Result<Unit> {
        return try {
            functions
                .getHttpsCallable("completeGroupChallenge")
                .call(mapOf("groupId" to groupId))
                .await()
            Timber.d("completeGroupChallenge: groupId=%s", groupId)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "completeGroupChallenge failed groupId=%s", groupId)
            Result.failure(e)
        }
    }
}
