package com.detox.app.presentation.screens.challengecreation

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.LimitType
import com.detox.app.ui.theme.DetoxTheme
import com.detox.app.ui.theme.detoxColors
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Screenshot harness for the Soft info step — renders every copy branch in LIGHT and DARK and
 * writes a PNG per variant so the theming rule in CLAUDE.md §4b can actually be checked.
 *
 * Not a pass/fail test (the assertions live in [StepSoftInfoCopyTest]); it is a design tool, run
 * on demand via `adb shell am instrument` and pulled from the app's external files dir. Kept in
 * the tree so the next visual change has a before/after harness ready.
 */
class StepSoftInfoRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val apps = AppListState(
        isLoading = false,
        trackableApps = listOf(
            AppUsageInfo("com.instagram.android", "Instagram", 0L, 0, true),
            AppUsageInfo("com.zhiliaoapp.musically", "TikTok", 0L, 0, true),
        ),
    )

    private fun render(name: String, dark: Boolean, state: ChallengeCreationState) {
        composeRule.setContent {
            DetoxTheme(darkTheme = dark) {
                Box(modifier = Modifier.fillMaxSize().background(detoxColors.screenBackground)) {
                    StepSoftInfo(state = state, appListState = apps)
                }
            }
        }
        composeRule.waitForIdle()
        val bitmap: Bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "softinfo"
        ).apply { mkdirs() }
        File(dir, "$name-${if (dark) "dark" else "light"}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun timeState() = ChallengeCreationState(
        selectedMode = ChallengeMode.SOFT,
        activeTab = 0,
        selectedApps = setOf("com.instagram.android", "com.zhiliaoapp.musically"),
        limitType = LimitType.TIME,
        limitValueMinutes = 60,
        durationDays = 14,
    )

    @Test fun time_light() = render("time", false, timeState())
    @Test fun time_dark() = render("time", true, timeState())

    private fun sessionsState() = ChallengeCreationState(
        selectedMode = ChallengeMode.SOFT,
        activeTab = 0,
        selectedApps = setOf("com.instagram.android"),
        limitType = LimitType.SESSIONS,
        limitValueSessions = 5,
        sessionDurationMinutes = 10,
        durationDays = 30,
    )

    @Test fun sessions_light() = render("sessions", false, sessionsState())
    @Test fun sessions_dark() = render("sessions", true, sessionsState())

    private fun windowState() = ChallengeCreationState(
        selectedMode = ChallengeMode.SOFT,
        activeTab = 0,
        selectedApps = setOf("com.instagram.android"),
        limitType = LimitType.TIME_WINDOW,
        scheduleStart = "18:00",
        scheduleEnd = "20:00",
        durationDays = 7,
    )

    @Test fun window_light() = render("window", false, windowState())
    @Test fun window_dark() = render("window", true, windowState())

    private fun blockOpenEndedState() = ChallengeCreationState(
        selectedMode = ChallengeMode.SOFT,
        activeTab = 1,
        manualDomains = listOf("reddit.com", "news.ycombinator.com"),
        noEndDate = true,
    )

    @Test fun blockOpenEnded_light() = render("block-openended", false, blockOpenEndedState())
    @Test fun blockOpenEnded_dark() = render("block-openended", true, blockOpenEndedState())
}
