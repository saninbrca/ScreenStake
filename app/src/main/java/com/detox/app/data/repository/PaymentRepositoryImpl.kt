package com.detox.app.data.repository

import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.domain.model.PaymentIntentData
import com.detox.app.domain.repository.PaymentRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentRepositoryImpl @Inject constructor(
    private val cloudFunctionsService: CloudFunctionsService
) : PaymentRepository {

    override suspend fun prepareHardModePayment(
        amountCents: Int,
        durationDays: Int,
        challengeId: String
    ): Result<PaymentIntentData> =
        cloudFunctionsService.createPaymentIntent(amountCents, durationDays, challengeId)

    override suspend fun capturePayment(paymentIntentId: String): Result<Unit> =
        cloudFunctionsService.capturePayment(paymentIntentId)

    override suspend fun cancelOrRefundPayment(
        paymentIntentId: String,
        wasImmediate: Boolean
    ): Result<Unit> =
        cloudFunctionsService.cancelOrRefundPayment(paymentIntentId, wasImmediate)
}
