package com.detox.app.presentation.screens.challengecreation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.detox.app.R
import com.detox.app.domain.model.AppUsageInfo
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.LimitType
import com.detox.app.ui.theme.DetoxTheme
import org.junit.Rule
import org.junit.Test

/**
 * Guards the copy BRANCHING of the Soft-only "How your challenge works" step.
 *
 * The branches are not cosmetic — they are the difference between telling the truth and lying to
 * the user about their own challenge:
 *  - TIME / SESSIONS / TIME_BUDGET record `limitExceeded` days, so the "one day over fails the
 *    whole challenge" rule applies.
 *  - TIME_WINDOW — and the Website/adult block path, which persists as TIME_WINDOW (see
 *    `ChallengeCreationViewModel.submissionFields`) — hit `DailyEvaluationWorker
 *    .computeLimitExceeded`'s `LimitType.TIME_WINDOW -> false` arm and can NEVER fail on usage.
 *    Showing them the fail rule would be false.
 *  - The "Open anyway" tip describes a TIME-only affordance.
 *
 * Copy is read back out of resources rather than hardcoded, so rewording the strings does not
 * break these tests — only re-wiring a branch does.
 */
class StepSoftInfoCopyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val apps = AppListState(
        isLoading = false,
        trackableApps = listOf(AppUsageInfo("com.instagram.android", "Instagram", 0L, 0, true)),
    )

    private fun show(state: ChallengeCreationState) {
        composeRule.setContent {
            DetoxTheme(darkTheme = false) {
                StepSoftInfo(state = state, appListState = apps)
            }
        }
    }

    private fun softAppState(limitType: LimitType) = ChallengeCreationState(
        selectedMode = ChallengeMode.SOFT,
        activeTab = 0,
        selectedApps = setOf("com.instagram.android"),
        limitType = limitType,
        limitValueMinutes = 60,
        durationDays = 14,
        scheduleStart = "18:00",
        scheduleEnd = "20:00",
    )

    private val failRule get() = context.getString(R.string.wizard_soft_info_fail)
    private val noUsageLimit get() = context.getString(R.string.wizard_soft_info_no_usage_limit)
    private val timeTip get() = context.getString(R.string.wizard_soft_info_tip_time)

    // ── Usage-limit paths: the fail rule applies ─────────────────────────────────

    @Test
    fun timeLimit_showsFailRuleAndTip() {
        show(softAppState(LimitType.TIME))
        composeRule.onNodeWithText(failRule).assertIsDisplayed()
        composeRule.onNodeWithText(timeTip).assertIsDisplayed()
        composeRule.onNodeWithText(noUsageLimit).assertDoesNotExist()
    }

    @Test
    fun sessionLimit_showsFailRuleButNoTip() {
        show(softAppState(LimitType.SESSIONS))
        composeRule.onNodeWithText(failRule).assertIsDisplayed()
        composeRule.onNodeWithText(timeTip).assertDoesNotExist()
    }

    @Test
    fun budgetLimit_showsFailRuleButNoTip() {
        show(softAppState(LimitType.TIME_BUDGET))
        composeRule.onNodeWithText(failRule).assertIsDisplayed()
        composeRule.onNodeWithText(timeTip).assertDoesNotExist()
    }

    // ── Paths that cannot fail on usage: the fail rule must NOT appear ───────────

    @Test
    fun timeWindow_neverClaimsTheChallengeCanFailOnUsage() {
        show(softAppState(LimitType.TIME_WINDOW))
        composeRule.onNodeWithText(noUsageLimit).assertIsDisplayed()
        composeRule.onNodeWithText(failRule).assertDoesNotExist()
        composeRule.onNodeWithText(timeTip).assertDoesNotExist()
    }

    @Test
    fun websiteBlock_neverClaimsTheChallengeCanFailOnUsage() {
        show(
            ChallengeCreationState(
                selectedMode = ChallengeMode.SOFT,
                activeTab = 1,
                manualDomains = listOf("reddit.com"),
                // A stale TIME pick from an earlier Apps-tab visit must not resurrect the fail
                // rule or the tip — the block path always persists as TIME_WINDOW.
                limitType = LimitType.TIME,
                durationDays = 14,
            )
        )
        composeRule.onNodeWithText(noUsageLimit).assertIsDisplayed()
        composeRule.onNodeWithText(failRule).assertDoesNotExist()
        composeRule.onNodeWithText(timeTip).assertDoesNotExist()
    }

    // ── End-date vs open-ended ───────────────────────────────────────────────────

    @Test
    fun openEnded_replacesTheResultDateLine() {
        show(softAppState(LimitType.TIME).copy(noEndDate = true))
        composeRule
            .onNodeWithText(context.getString(R.string.wizard_soft_info_open_ended))
            .assertIsDisplayed()
    }
}
