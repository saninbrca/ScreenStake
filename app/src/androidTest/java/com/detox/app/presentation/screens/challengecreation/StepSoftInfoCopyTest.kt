package com.finite.focus.presentation.screens.challengecreation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.finite.focus.R
import com.finite.focus.domain.model.AppUsageInfo
import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.LimitType
import com.finite.focus.ui.theme.DetoxTheme
import org.junit.Rule
import org.junit.Test

/**
 * Guards the copy BRANCHING of the Soft-only "How your challenge works" step.
 *
 * The branches are not cosmetic — they are the difference between telling the truth and lying to
 * the user about their own challenge:
 *  - TIME / TIME_BUDGET record `limitExceeded` days, so the "one day over fails the whole
 *    challenge" rule applies.
 *  - SESSIONS has a daily limit but cannot exceed it: `OverlayManager.handleSessionLimitApp` caps
 *    conscious opens AT the limit and `DailyEvaluationWorker` settles on
 *    `consciousOpens > maxSessions`. The generic fail rule would warn about an unreachable case,
 *    so this path gets its own rule row.
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

    /**
     * Asserts [text] is present and displayable, scrolling the step to it first.
     *
     * The step is a `verticalScroll` Column whose row count varies by branch, so on a smaller
     * device (the P30 this suite runs on) the last rows sit below the fold — a bare
     * `assertIsDisplayed` would then fail on copy that is perfectly correct. `performScrollTo`
     * still fails loudly if the node does not exist at all, so the assertion keeps its teeth.
     */
    private fun assertShown(text: String) {
        composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
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
    private val sessionsRule get() = context.getString(R.string.wizard_soft_info_sessions)
    private val timeTip get() = context.getString(R.string.wizard_soft_info_tip_time)
    private val permissionApps get() = context.getString(R.string.wizard_soft_info_permission_apps)
    private val permissionBlock get() = context.getString(R.string.wizard_soft_info_permission_block)

    private fun websiteBlockState() = ChallengeCreationState(
        selectedMode = ChallengeMode.SOFT,
        activeTab = 1,
        manualDomains = listOf("reddit.com"),
        // A stale TIME pick from an earlier Apps-tab visit must not resurrect the fail
        // rule or the tip — the block path always persists as TIME_WINDOW.
        limitType = LimitType.TIME,
        durationDays = 14,
    )

    // ── Exceedable limits: the fail rule applies ─────────────────────────────────

    @Test
    fun timeLimit_showsFailRuleAndTip() {
        show(softAppState(LimitType.TIME))
        assertShown(failRule)
        assertShown(timeTip)
        composeRule.onNodeWithText(noUsageLimit).assertDoesNotExist()
        composeRule.onNodeWithText(sessionsRule).assertDoesNotExist()
    }

    @Test
    fun budgetLimit_showsFailRuleButNoTip() {
        show(softAppState(LimitType.TIME_BUDGET))
        assertShown(failRule)
        composeRule.onNodeWithText(timeTip).assertDoesNotExist()
        composeRule.onNodeWithText(sessionsRule).assertDoesNotExist()
    }

    // ── Capped limit: the fail rule would warn about an unreachable case ─────────

    @Test
    fun sessionLimit_showsTheCappedRuleInsteadOfTheFailRule() {
        show(softAppState(LimitType.SESSIONS))
        assertShown(sessionsRule)
        composeRule.onNodeWithText(failRule).assertDoesNotExist()
        composeRule.onNodeWithText(timeTip).assertDoesNotExist()
        composeRule.onNodeWithText(noUsageLimit).assertDoesNotExist()
    }

    // ── Paths that cannot fail on usage: the fail rule must NOT appear ───────────

    @Test
    fun timeWindow_neverClaimsTheChallengeCanFailOnUsage() {
        show(softAppState(LimitType.TIME_WINDOW))
        assertShown(noUsageLimit)
        composeRule.onNodeWithText(failRule).assertDoesNotExist()
        composeRule.onNodeWithText(sessionsRule).assertDoesNotExist()
        composeRule.onNodeWithText(timeTip).assertDoesNotExist()
    }

    @Test
    fun websiteBlock_neverClaimsTheChallengeCanFailOnUsage() {
        show(websiteBlockState())
        assertShown(noUsageLimit)
        composeRule.onNodeWithText(failRule).assertDoesNotExist()
        composeRule.onNodeWithText(sessionsRule).assertDoesNotExist()
        composeRule.onNodeWithText(timeTip).assertDoesNotExist()
    }

    // ── Permission loss: the one fail cause that crosses EVERY branch ────────────
    // Prolonged permission loss now fails a Soft challenge. On the three branches above that show
    // reassurance rows ("nothing to fail on", "your opens are capped") this is the FIRST and ONLY
    // way to lose, so omitting it would leave those users believing the challenge cannot be lost.

    private fun assertAppTargetPermissionRule(limitType: LimitType) {
        show(softAppState(limitType))
        assertShown(permissionApps)
        composeRule.onNodeWithText(permissionBlock).assertDoesNotExist()
    }

    @Test
    fun permissionRule_showsOnTimeLimit() = assertAppTargetPermissionRule(LimitType.TIME)

    @Test
    fun permissionRule_showsOnBudgetLimit() = assertAppTargetPermissionRule(LimitType.TIME_BUDGET)

    /** The capped path: its own rule row says time cannot fail it — this is what CAN. */
    @Test
    fun permissionRule_showsOnSessionLimit() = assertAppTargetPermissionRule(LimitType.SESSIONS)

    /** The "nothing to fail on" path: permission loss is its only fail cause. */
    @Test
    fun permissionRule_showsOnTimeWindow() = assertAppTargetPermissionRule(LimitType.TIME_WINDOW)

    @Test
    fun permissionRule_websiteBlockGetsTheDeadlineOnlyVariant() {
        // No observable package ⇒ the usage-evidence gate has nothing to check ⇒ these challenges
        // fail on the deadline alone. Promising them the app-target safety net would be false.
        show(websiteBlockState())
        assertShown(permissionBlock)
        composeRule.onNodeWithText(permissionApps).assertDoesNotExist()
    }

    // ── End-date vs open-ended ───────────────────────────────────────────────────

    @Test
    fun openEnded_replacesTheResultDateLine() {
        show(softAppState(LimitType.TIME).copy(noEndDate = true))
        assertShown(context.getString(R.string.wizard_soft_info_open_ended))
    }
}
