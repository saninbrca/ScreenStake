package com.detox.app.data.repository

import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.domain.model.PaymentIntentData
import com.detox.app.domain.repository.PaymentRepository
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
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
        challengeId: String?,
        userId: String?,
        amountCents: Int?,
        partialRefundCents: Int?
    ): Result<Unit> {
        // Central chokepoint for every Hard Mode refund/cancel — gives crash reports context.
        Sentry.addBreadcrumb(Breadcrumb().apply {
            category = "Stripe"
            message = "Initiating payment operation"
            level = SentryLevel.INFO
            setData("operation", "cancelOrRefund")
            setData("challengeId", challengeId ?: "")
            setData("amountCents", amountCents ?: 0)
        })
        return cloudFunctionsService.cancelOrRefundPayment(paymentIntentId, challengeId, userId, amountCents, partialRefundCents)
    }

    override suspend fun setupPayoutAccount(
        iban: String,
        accountHolderName: String,
        userId: String
    ): Result<Unit> =
        cloudFunctionsService.setupPayoutAccount(iban, accountHolderName, userId)
}
