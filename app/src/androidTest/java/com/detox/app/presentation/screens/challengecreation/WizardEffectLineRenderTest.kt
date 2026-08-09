package com.detox.app.presentation.screens.challengecreation

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.detox.app.R
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.LimitType
import com.detox.app.ui.theme.DetoxTheme
import com.detox.app.ui.theme.detoxColors
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The wizard's derived "what this means per day" lines: asserts the numbers, and writes a PNG per
 * variant in LIGHT and DARK so the theming rule in CLAUDE.md §4b can actually be checked.
 *
 * The lines are display only. Nothing here feeds enforcement or settlement — SESSIONS is still
 * gated on the conscious-open count alone (`OverlayManager`, `DailyEvaluationWorker`), and the
 * daily total these tests assert is never persisted.
 */
class WizardEffectLineRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val apps = AppListState(
        isLoading = false,
        trackableApps = listOf(AppUsageInfo("com.instagram.android", "Instagram", 0L, 0, true)),
    )

    private fun capture(name: String, dark: Boolean) {
        composeRule.waitForIdle()
        val bitmap: Bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "wizard-effect"
        ).apply { mkdirs() }
        File(dir, "$name-${if (dark) "dark" else "light"}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun showStep4(dark: Boolean, state: ChallengeCreationState) {
        composeRule.setContent {
            DetoxTheme(darkTheme = dark) {
                Box(modifier = Modifier.fillMaxSize().background(detoxColors.screenBackground)) {
                    Step4LimitValues(
                        state = state,
                        appListState = apps,
                        onUpdateLimitMinutes = {},
                        onUpdateLimitSessions = {},
                        onUpdateSessionDuration = {},
                        onUpdateDailyBudget = {},
                    )
                }
            }
        }
    }

    private fun showStep5(dark: Boolean, start: String, end: String, isWindow: Boolean) {
        composeRule.setContent {
            DetoxTheme(darkTheme = dark) {
                Box(modifier = Modifier.fillMaxSize().background(detoxColors.screenBackground)) {
                    Step5Schedule(
                        scheduleStart = start,
                        scheduleEnd = end,
                        activeDays = emptySet(),
                        isRequired = isWindow,
                        onStartChange = {},
                        onEndChange = {},
                        onToggleDay = {},
                        onClearSchedule = {},
                        onSkip = {},
                    )
                }
            }
        }
    }

    private fun sessionsState(sessions: Int, minutes: Int) = ChallengeCreationState(
        selectedMode = ChallengeMode.SOFT,
        activeTab = 0,
        selectedApps = setOf("com.instagram.android"),
        limitType = LimitType.SESSIONS,
        limitValueSessions = sessions,
        sessionDurationMinutes = minutes,
    )

    private fun sessionsTotal(sessions: Int, minutes: Int) = context.getString(
        R.string.wizard_set_limit_sessions_total, sessions, minutes, sessions * minutes
    )

    // ── SESSIONS: opens × minutes = the daily total ──────────────────────────────

    @Test
    fun sessions_totalIsTheProduct_light() {
        showStep4(dark = false, state = sessionsState(3, 3))
        composeRule.onNodeWithText(sessionsTotal(3, 3)).assertIsDisplayed()
        capture("sessions-3x3", dark = false)
    }

    @Test
    fun sessions_totalIsTheProduct_dark() {
        showStep4(dark = true, state = sessionsState(5, 10))
        composeRule.onNodeWithText(sessionsTotal(5, 10)).assertIsDisplayed()
        capture("sessions-5x10", dark = true)
    }

    // ── TIME / TIME_BUDGET: the current-average line, never a restated total ─────

    @Test
    fun time_showsCurrentAverageForTheMeasuredApp() {
        showStep4(
            dark = false,
            state = ChallengeCreationState(
                selectedMode = ChallengeMode.SOFT,
                activeTab = 0,
                selectedApps = setOf("com.instagram.android"),
                limitType = LimitType.TIME,
                limitValueMinutes = 60,
                avgDailyMinutes = 96,
                avgDailyMinutesPackage = "com.instagram.android",
            ),
        )
        composeRule
            .onNodeWithText(context.getString(R.string.wizard_set_limit_current_average, "Instagram", 96))
            .assertIsDisplayed()
        capture("time-average", dark = false)
    }

    /** No measured figure (0 = never loaded / no history) ⇒ the row must not render at all. */
    @Test
    fun time_withoutAnAverage_showsNoLine() {
        showStep4(
            dark = true,
            state = ChallengeCreationState(
                selectedMode = ChallengeMode.SOFT,
                activeTab = 0,
                selectedApps = setOf("com.instagram.android"),
                limitType = LimitType.TIME,
                limitValueMinutes = 60,
            ),
        )
        composeRule
            .onNodeWithText(context.getString(R.string.wizard_set_limit_current_average, "Instagram", 96))
            .assertDoesNotExist()
        capture("time-no-average", dark = true)
    }

    @Test
    fun budget_showsCurrentAverage_dark() {
        showStep4(
            dark = true,
            state = ChallengeCreationState(
                selectedMode = ChallengeMode.SOFT,
                activeTab = 0,
                selectedApps = setOf("com.instagram.android"),
                limitType = LimitType.TIME_BUDGET,
                dailyBudgetMinutes = 30,
                avgDailyMinutes = 96,
                avgDailyMinutesPackage = "com.instagram.android",
            ),
        )
        composeRule
            .onNodeWithText(context.getString(R.string.wizard_set_limit_current_average, "Instagram", 96))
            .assertIsDisplayed()
        capture("budget-average", dark = true)
    }

    // ── TIME_WINDOW: the window's length in hours ────────────────────────────────

    private fun windowLine(start: String, end: String, length: String) =
        context.getString(R.string.wizard_window_length_format, start, end, length)

    private fun hours(n: Int) = context.resources
        .getQuantityString(R.plurals.wizard_window_length_hours, n, n)

    @Test
    fun window_showsWholeHours_light() {
        showStep5(dark = false, start = "09:00", end = "22:00", isWindow = true)
        composeRule.onNodeWithText(windowLine("09:00", "22:00", hours(13))).assertIsDisplayed()
        capture("window-13h", dark = false)
    }

    @Test
    fun window_showsHoursAndMinutes_dark() {
        showStep5(dark = true, start = "09:00", end = "22:30", isWindow = true)
        composeRule
            .onNodeWithText(
                windowLine(
                    "09:00", "22:30",
                    context.getString(R.string.wizard_window_length_hours_minutes, 13, 30)
                )
            )
            .assertIsDisplayed()
        capture("window-13h30", dark = true)
    }

    /** Optional schedule on a usage-limit type: the window is not the limit, so no length line. */
    @Test
    fun optionalSchedule_showsNoWindowLine() {
        showStep5(dark = false, start = "09:00", end = "22:00", isWindow = false)
        composeRule.onNodeWithText(windowLine("09:00", "22:00", hours(13))).assertDoesNotExist()
        capture("schedule-optional", dark = false)
    }

    /** An inverted window is an error state — the length line must stay hidden, never go negative. */
    @Test
    fun invertedWindow_showsNoWindowLine() {
        showStep5(dark = false, start = "22:00", end = "09:00", isWindow = true)
        composeRule.onNodeWithText(windowLine("22:00", "09:00", hours(-13))).assertDoesNotExist()
        capture("window-inverted", dark = false)
    }
}
