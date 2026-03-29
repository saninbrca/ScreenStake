package com.detox.app.domain.usecase

import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.repository.UsageStatsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetAddictiveAppsUseCaseTest {

    private lateinit var usageStatsRepository: UsageStatsRepository
    private lateinit var useCase: GetAddictiveAppsUseCase

    @Before
    fun setUp() {
        usageStatsRepository = mockk()
        useCase = GetAddictiveAppsUseCase(usageStatsRepository)
    }

    @Test
    fun `trackable apps have high usage time`() = runTest {
        val apps = listOf(
            createAppUsageInfo("com.tiktok", avgMinutes = 60, avgOpens = 5, trackable = true),
            createAppUsageInfo("com.lowuse", avgMinutes = 10, avgOpens = 2, trackable = false)
        )
        coEvery { usageStatsRepository.getAppUsageStats(14) } returns apps

        val result = useCase()

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(1, data.trackableApps.size)
        assertEquals("com.tiktok", data.trackableApps[0].packageName)
        assertEquals(1, data.nonTrackableApps.size)
        assertEquals("com.lowuse", data.nonTrackableApps[0].packageName)
    }

    @Test
    fun `trackable apps have high open count`() = runTest {
        val apps = listOf(
            createAppUsageInfo("com.instagram", avgMinutes = 30, avgOpens = 25, trackable = true),
            createAppUsageInfo("com.lowuse", avgMinutes = 10, avgOpens = 5, trackable = false)
        )
        coEvery { usageStatsRepository.getAppUsageStats(14) } returns apps

        val result = useCase()

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(1, data.trackableApps.size)
        assertEquals("com.instagram", data.trackableApps[0].packageName)
    }

    @Test
    fun `returns failure on repository exception`() = runTest {
        coEvery { usageStatsRepository.getAppUsageStats(14) } throws RuntimeException("No permission")

        val result = useCase()

        assertTrue(result.isFailure)
    }

    @Test
    fun `empty list returns empty result`() = runTest {
        coEvery { usageStatsRepository.getAppUsageStats(14) } returns emptyList()

        val result = useCase()

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertTrue(data.trackableApps.isEmpty())
        assertTrue(data.nonTrackableApps.isEmpty())
    }

    private fun createAppUsageInfo(
        packageName: String,
        avgMinutes: Long,
        avgOpens: Int,
        trackable: Boolean
    ) = AppUsageInfo(
        packageName = packageName,
        appName = packageName.substringAfterLast('.'),
        icon = null,
        avgDailyMinutes = avgMinutes,
        avgDailyOpens = avgOpens,
        isTrackable = trackable
    )
}
