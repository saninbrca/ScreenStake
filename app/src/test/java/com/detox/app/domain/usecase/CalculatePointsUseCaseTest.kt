package com.detox.app.domain.usecase

import com.detox.app.domain.model.LimitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalculatePointsUseCaseTest {

    private lateinit var useCase: CalculatePointsUseCase

    @Before
    fun setUp() {
        useCase = CalculatePointsUseCase()
    }

    @Test
    fun `time limit respected with exact limit gives base points only`() {
        val result = useCase(
            limitType = LimitType.TIME,
            limitValueMinutes = 60,
            limitValueSessions = null,
            todayMinutes = 60,
            todayOpens = 10
        )

        assertTrue(result.limitExceeded)
        assertEquals(0, result.points)
    }

    @Test
    fun `time limit respected with 25 min under gives base plus bonus`() {
        val result = useCase(
            limitType = LimitType.TIME,
            limitValueMinutes = 60,
            limitValueSessions = null,
            todayMinutes = 35,
            todayOpens = 5
        )

        assertFalse(result.limitExceeded)
        assertEquals(10 + 5, result.points) // 10 base + 25/5 = 5 bonus
        assertEquals(5, result.bonusPoints)
    }

    @Test
    fun `time limit exceeded gives zero points`() {
        val result = useCase(
            limitType = LimitType.TIME,
            limitValueMinutes = 60,
            limitValueSessions = null,
            todayMinutes = 90,
            todayOpens = 10
        )

        assertTrue(result.limitExceeded)
        assertEquals(0, result.points)
        assertEquals(0, result.bonusPoints)
    }

    @Test
    fun `sessions limit respected`() {
        val result = useCase(
            limitType = LimitType.SESSIONS,
            limitValueMinutes = 5,
            limitValueSessions = 5,
            todayMinutes = 10,
            todayOpens = 3
        )

        assertFalse(result.limitExceeded)
        assertTrue(result.points >= 10)
    }

    @Test
    fun `sessions limit exceeded`() {
        val result = useCase(
            limitType = LimitType.SESSIONS,
            limitValueMinutes = 5,
            limitValueSessions = 5,
            todayMinutes = 30,
            todayOpens = 5
        )

        assertTrue(result.limitExceeded)
        assertEquals(0, result.points)
    }

    @Test
    fun `zero usage gives maximum bonus points`() {
        val result = useCase(
            limitType = LimitType.TIME,
            limitValueMinutes = 60,
            limitValueSessions = null,
            todayMinutes = 0,
            todayOpens = 0
        )

        assertFalse(result.limitExceeded)
        assertEquals(10 + 12, result.points) // 10 base + 60/5 = 12 bonus
        assertEquals(12, result.bonusPoints)
    }
}
