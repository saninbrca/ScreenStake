package com.detox.app.domain.usecase

import com.detox.app.domain.model.PaymentIntentData
import com.detox.app.domain.repository.PaymentRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProcessPaymentUseCaseTest {

    private lateinit var paymentRepository: PaymentRepository
    private lateinit var useCase: ProcessPaymentUseCase

    private val fakePaymentData = PaymentIntentData(
        paymentIntentId = "pi_test_123",
        clientSecret = "pi_test_123_secret_abc",
        isImmediateCapture = false
    )

    @Before
    fun setUp() {
        paymentRepository = mockk()
        useCase = ProcessPaymentUseCase(paymentRepository)
    }

    @Test
    fun `returns PaymentIntentData on success`() = runTest {
        coEvery {
            paymentRepository.prepareHardModePayment(2000, 7, "challenge-1")
        } returns Result.success(fakePaymentData)

        val result = useCase(amountCents = 2000, durationDays = 7, challengeId = "challenge-1")

        assertTrue(result.isSuccess)
        assertEquals(fakePaymentData, result.getOrNull())
    }

    @Test
    fun `returns failure when amount is zero`() = runTest {
        val result = useCase(amountCents = 0, durationDays = 7, challengeId = "challenge-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `returns failure when amount is negative`() = runTest {
        val result = useCase(amountCents = -500, durationDays = 7, challengeId = "challenge-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `returns failure when duration is zero`() = runTest {
        val result = useCase(amountCents = 2000, durationDays = 0, challengeId = "challenge-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `propagates repository failure`() = runTest {
        val exception = RuntimeException("Network error")
        coEvery {
            paymentRepository.prepareHardModePayment(any(), any(), any())
        } returns Result.failure(exception)

        val result = useCase(amountCents = 1000, durationDays = 14, challengeId = "challenge-2")

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    @Test
    fun `sets isImmediateCapture true for challenges over 7 days`() = runTest {
        val longChallengeData = fakePaymentData.copy(isImmediateCapture = true)
        coEvery {
            paymentRepository.prepareHardModePayment(5000, 30, "challenge-3")
        } returns Result.success(longChallengeData)

        val result = useCase(amountCents = 5000, durationDays = 30, challengeId = "challenge-3")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.isImmediateCapture)
    }
}
