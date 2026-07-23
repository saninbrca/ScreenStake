package com.detox.app.domain.usecase

import com.detox.app.domain.model.InstalledAppInfo
import com.detox.app.domain.repository.UsageStatsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun stub(
        launchable: List<InstalledAppInfo>,
        neverBlockable: Set<String> = emptySet(),
        usage: Map<String, Pair<Long, Int>> = emptyMap(),
    ) {
        coEvery { usageStatsRepository.getLaunchableApps() } returns launchable
        coEvery { usageStatsRepository.getNeverBlockablePackages() } returns neverBlockable
        coEvery { usageStatsRepository.getUsageByPackage(14) } returns usage
    }

    @Test
    fun `apps are sorted alphabetically by name, case-insensitive, regardless of usage`() = runTest {
        stub(
            launchable = listOf(
                InstalledAppInfo("com.cherry", "Cherry"),
                InstalledAppInfo("com.apple", "apple"),
                InstalledAppInfo("com.banana", "Banana"),
                InstalledAppInfo("com.apricot", "apricot"),
            ),
            // Highest usage by far — must NOT influence ordering; it sorts last by name.
            usage = mapOf("com.cherry" to (999L to 99)),
        )

        val result = useCase()

        assertTrue(result.isSuccess)
        val apps = result.getOrThrow().trackableApps.map { it.packageName }
        // Pure A–Z by display name, case-insensitive: apple, apricot, Banana, Cherry.
        assertEquals(listOf("com.apple", "com.apricot", "com.banana", "com.cherry"), apps)
    }

    @Test
    fun `never-blockable and excluded-prefix packages are filtered out`() = runTest {
        stub(
            launchable = listOf(
                InstalledAppInfo("com.tiktok", "TikTok"),
                InstalledAppInfo("com.huawei.android.launcher", "Launcher"), // dynamic deny
                InstalledAppInfo("com.android.settings", "Settings"),        // static prefix
                InstalledAppInfo("com.detox.app", "ScreenStake"),            // self (prefix)
            ),
            neverBlockable = setOf("com.huawei.android.launcher"),
        )

        val result = useCase()

        val apps = result.getOrThrow().trackableApps.map { it.packageName }
        assertEquals(listOf("com.tiktok"), apps)
        assertFalse("com.huawei.android.launcher" in apps)
        assertFalse("com.android.settings" in apps)
        assertFalse("com.detox.app" in apps)
    }

    @Test
    fun `OEM clock apps are filtered out by the static fallback prefixes`() = runTest {
        // Regression guard: before the alarm-role fix these were selectable and blockable, so a
        // user could block their own alarm clock. The dynamic ACTION_SHOW_ALARMS / ACTION_SET_ALARM
        // resolver is the primary defense; these prefixes must still catch them when it misses,
        // which is what this test pins (neverBlockable is deliberately empty here).
        stub(
            launchable = listOf(
                InstalledAppInfo("com.huawei.deskclock", "Uhr"),              // P30 test device
                InstalledAppInfo("com.sec.android.app.clockpackage", "Clock"), // Samsung
                InstalledAppInfo("com.coloros.alarmclock", "Clock"),           // Oppo / Realme
                InstalledAppInfo("com.android.BBKClock", "Clock"),             // vivo
                InstalledAppInfo("com.instagram.android", "Instagram"),
            ),
            neverBlockable = emptySet(),
        )

        val apps = useCase().getOrThrow().trackableApps.map { it.packageName }

        assertEquals(listOf("com.instagram.android"), apps)
    }

    @Test
    fun `dynamically resolved alarm package is filtered out`() = runTest {
        // An OEM clock matching no prefix at all — only the dynamic alarm-role resolver catches it.
        stub(
            launchable = listOf(
                InstalledAppInfo("com.unknownoem.myclock", "Clock"),
                InstalledAppInfo("com.tiktok", "TikTok"),
            ),
            neverBlockable = setOf("com.unknownoem.myclock"),
        )

        val apps = useCase().getOrThrow().trackableApps.map { it.packageName }

        assertEquals(listOf("com.tiktok"), apps)
    }

    @Test
    fun `list populates even with no usage data (usage access off)`() = runTest {
        stub(
            launchable = listOf(
                InstalledAppInfo("com.beta", "Beta"),
                InstalledAppInfo("com.alpha", "Alpha"),
            ),
            usage = emptyMap(),
        )

        val result = useCase()

        assertTrue(result.isSuccess)
        // All never-used → pure alphabetical by label.
        assertEquals(
            listOf("com.alpha", "com.beta"),
            result.getOrThrow().trackableApps.map { it.packageName },
        )
    }

    @Test
    fun `returns failure on repository exception`() = runTest {
        coEvery { usageStatsRepository.getLaunchableApps() } throws RuntimeException("boom")
        coEvery { usageStatsRepository.getNeverBlockablePackages() } returns emptySet()
        coEvery { usageStatsRepository.getUsageByPackage(14) } returns emptyMap()

        val result = useCase()

        assertTrue(result.isFailure)
    }

    @Test
    fun `empty launchable list returns empty result`() = runTest {
        stub(launchable = emptyList())

        val result = useCase()

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertTrue(data.trackableApps.isEmpty())
        assertTrue(data.nonTrackableApps.isEmpty())
    }
}
