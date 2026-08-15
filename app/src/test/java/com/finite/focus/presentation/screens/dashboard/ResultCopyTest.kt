package com.finite.focus.presentation.screens.dashboard

import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.model.DailyLog
import com.finite.focus.domain.model.LimitType
import com.finite.focus.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the derivations behind the result surfaces' copy: WHY a challenge was lost
 * ([firstBreachDetail]), for HOW LONG it was held ([daysHeldCalendar]), and how long it ran in total
 * ([calendarDurationDays] — the win subtitle and the loss line's denominator).
 *
 * Both are display-only, but both are the kind of number a user checks against their own memory —
 * a wrong day index or an undercounted streak reads as the app not having watched at all.
 */
class ResultCopyTest {

    private val day = DateUtils.MILLIS_PER_DAY
    private val start = DateUtils.dayKey(System.currentTimeMillis()) - 6 * day

    private fun challenge(
        limitType: LimitType = LimitType.TIME,
        limitValueMinutes: Int = 30,
        limitValueSessions: Int? = null,
        dailyBudgetMinutes: Int? = null,
        failReason: String? = "limit_exceeded",
        startDate: Long = start,
        endDate: Long = start + 7 * day,
    ) = Challenge(
        id = "c1",
        appPackageName = "com.tiktok",
        appPackageNames = listOf("com.tiktok"),
        appDisplayName = "TikTok",
        mode = ChallengeMode.HARD,
        limitType = limitType,
        limitValueMinutes = limitValueMinutes,
        limitValueSessions = limitValueSessions,
        startDate = startDate,
        endDate = endDate,
        amountCents = 800,
        stripePaymentIntentId = "pi_1",
        customMotivation = null,
        status = ChallengeStatus.FAILED,
        createdAt = startDate,
        dailyBudgetMinutes = dailyBudgetMinutes,
        failReason = failReason,
    )

    private fun log(
        dayOffset: Int,
        totalMinutes: Int = 0,
        consciousOpens: Int = 0,
        budgetUsedMs: Long = 0L,
        limitExceeded: Boolean = false,
    ) = DailyLog(
        id = "l$dayOffset",
        challengeId = "c1",
        date = start + dayOffset * day,
        totalMinutes = totalMinutes,
        openCount = consciousOpens,
        consciousOpens = consciousOpens,
        budgetUsedMs = budgetUsedMs,
        pointsEarned = 0,
        limitExceeded = limitExceeded,
        moneyLostCents = 0,
    )

    // ── firstBreachDetail ────────────────────────────────────────────────────────────────────

    @Test
    fun `names the FIRST breached day, not the last`() {
        // Soft Mode settles at the end, so a lost challenge can carry several breached days. The
        // challenge was lost on the first one — blaming a later day would misreport what happened.
        val detail = firstBreachDetail(
            challenge(),
            listOf(
                log(dayOffset = 4, totalMinutes = 90, limitExceeded = true),
                log(dayOffset = 1, totalMinutes = 47, limitExceeded = true),
                log(dayOffset = 0, totalMinutes = 12),
            )
        )

        assertEquals(2, detail?.dayNumber)   // day index is 1-based: offset 1 == day 2
        assertEquals(47, detail?.actualValue)
        assertEquals(30, detail?.limitValue)
    }

    @Test
    fun `reads the limit from the field that owns it, per type`() {
        val sessions = firstBreachDetail(
            challenge(limitType = LimitType.SESSIONS, limitValueSessions = 3),
            listOf(log(dayOffset = 0, consciousOpens = 5, limitExceeded = true))
        )
        assertEquals(5, sessions?.actualValue)
        assertEquals(3, sessions?.limitValue)

        // Budget is stored in millis and rounded UP: 30.2 min against a 30 min budget is over.
        val budget = firstBreachDetail(
            challenge(limitType = LimitType.TIME_BUDGET, dailyBudgetMinutes = 30),
            listOf(log(dayOffset = 2, budgetUsedMs = 30 * 60_000L + 12_000L, limitExceeded = true))
        )
        assertEquals(31, budget?.actualValue)
        assertEquals(30, budget?.limitValue)
        assertEquals(3, budget?.dayNumber)
    }

    @Test
    fun `no breach detail for a loss that was not a limit breach`() {
        // A Soft challenge can carry a breached day AND still end by abandon or permission loss.
        // Narrating the breach as the cause would be a lie the user can spot.
        val logs = listOf(log(dayOffset = 1, totalMinutes = 47, limitExceeded = true))

        assertNull(firstBreachDetail(challenge(failReason = "abandon"), logs))
        assertNull(firstBreachDetail(challenge(failReason = "permission_violation"), logs))
        assertNull(firstBreachDetail(challenge(failReason = "usage_violation"), logs))
    }

    @Test
    fun `no breach detail when the numbers do not tell a clean story`() {
        // No flagged log at all …
        assertNull(firstBreachDetail(challenge(), listOf(log(dayOffset = 1, totalMinutes = 12))))
        // … a flagged log whose usage does not actually exceed the limit (stale row after a limit
        // change) …
        assertNull(
            firstBreachDetail(
                challenge(),
                listOf(log(dayOffset = 1, totalMinutes = 20, limitExceeded = true))
            )
        )
        // … and TIME_WINDOW, which has no daily number to compare against at all.
        assertNull(
            firstBreachDetail(
                challenge(limitType = LimitType.TIME_WINDOW),
                listOf(log(dayOffset = 1, totalMinutes = 500, limitExceeded = true))
            )
        )
    }

    @Test
    fun `a legacy null failReason may still be explained by its breach log`() {
        // Rows written before failReason existed: the breach log is the only evidence there is.
        val detail = firstBreachDetail(
            challenge(failReason = null),
            listOf(log(dayOffset = 3, totalMinutes = 55, limitExceeded = true))
        )
        assertEquals(4, detail?.dayNumber)
    }

    // ── daysHeldCalendar ─────────────────────────────────────────────────────────────────────

    @Test
    fun `days held counts CALENDAR days, not DailyLog rows`() {
        // The EMUI case this exists for: clean days write no row, so a row count would say "1 day"
        // for a challenge that ran four.
        val held = daysHeldCalendar(
            challenge(),
            listOf(log(dayOffset = 4, totalMinutes = 90, limitExceeded = true)),
            nowMs = start + 6 * day,
        )
        assertEquals(4, held)
    }

    @Test
    fun `without a breach log it counts up to now, clamped to the end date`() {
        val c = challenge(startDate = start, endDate = start + 3 * day)

        assertEquals(2, daysHeldCalendar(c, emptyList(), nowMs = start + 2 * day))
        // Opening the result days later must not keep the figure growing past the challenge.
        assertEquals(3, daysHeldCalendar(c, emptyList(), nowMs = start + 30 * day))
    }

    @Test
    fun `held-vs-total can never read as a full run on a loss`() {
        // The trap: durationDays measures the raw span, so a 7-day challenge created at 10:00 spans
        // 6.58 days and truncates to 6 — a final-day breach would then render "6 of 6 days held",
        // which reads as a win on the loss dialog.
        val startAtTen = DateUtils.dayKey(System.currentTimeMillis()) + 10 * 60 * 60 * 1000L
        val c = challenge(
            startDate = startAtTen,
            endDate = DateUtils.endOfDayMillis(startAtTen, 7),
        )
        assertEquals(7, c.calendarDurationDays)

        val heldUntilFinalDay = daysHeldCalendar(
            c,
            listOf(log(dayOffset = 6, totalMinutes = 90, limitExceeded = true).copy(date = startAtTen + 6 * day)),
            nowMs = startAtTen + 6 * day,
        )
        assertEquals(6, heldUntilFinalDay)
    }

    // ── calendarDurationDays ─────────────────────────────────────────────────────────────────

    @Test
    fun `the win subtitle states the full calendar duration of a mid-day challenge`() {
        // ChallengeSuccessDialog's "Du hast %d Tage durchgehalten" reads this. It used to read a
        // raw-span variant that truncated 6.58 days to 6, so winning a 7-day challenge congratulated
        // the user on 6.
        val startAtTen = DateUtils.dayKey(System.currentTimeMillis()) + 10 * 60 * 60 * 1000L
        val c = challenge(startDate = startAtTen, endDate = DateUtils.endOfDayMillis(startAtTen, 7))

        assertEquals(7, c.calendarDurationDays)
    }

    @Test
    fun `an open-ended challenge never renders its 100-year sentinel as a day count`() {
        val startAtTen = DateUtils.dayKey(System.currentTimeMillis()) - 3 * day
        val c = challenge(
            startDate = startAtTen,
            endDate = DateUtils.endOfDayMillis(startAtTen, DateUtils.NO_END_DATE_DAYS),
        )

        // Clamped to days elapsed so far (start day + 3), not ~36 500.
        assertEquals(4, c.calendarDurationDays)
    }

    @Test
    fun `a day-one fail reads as zero days held, never negative`() {
        val held = daysHeldCalendar(
            challenge(),
            listOf(log(dayOffset = 0, totalMinutes = 90, limitExceeded = true)),
            nowMs = start + day,
        )
        assertEquals(0, held)
    }
}
