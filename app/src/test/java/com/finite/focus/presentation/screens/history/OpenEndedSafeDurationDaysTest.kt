package com.finite.focus.presentation.screens.history

import com.finite.focus.data.local.db.entity.DailyLogEntity
import com.finite.focus.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the History/HistoryDetail duration helper never renders the ~100-year open-ended
 * sentinel as a day count (Bug 2), while leaving fixed-end challenges' real span untouched.
 */
class OpenEndedSafeDurationDaysTest {

    private val day = DateUtils.MILLIS_PER_DAY
    private val start = 1_000_000_000_000L

    private fun log(date: Long) = DailyLogEntity(
        id = "log-$date",
        challengeId = "c1",
        date = date,
        totalMinutes = 0,
        openCount = 0,
        pointsEarned = 0,
        limitExceeded = false,
        moneyLostCents = 0
    )

    @Test
    fun `open-ended challenge returns real days survived, not the sentinel span`() {
        val end = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS) // ~36 500-day sentinel
        val logs = listOf(log(start), log(start + 5 * day), log(start + 11 * day)) // last active = day 11

        val result = openEndedSafeDurationDays(start, end, logs)

        assertEquals(12, result) // (11 days elapsed) + 1
        assertTrue("must not leak the sentinel span", result < DateUtils.NO_END_DATE_DAYS)
    }

    @Test
    fun `open-ended challenge with no logs returns at least 1 day`() {
        val end = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        assertEquals(1, openEndedSafeDurationDays(start, end, emptyList()))
    }

    @Test
    fun `fixed-end challenge returns its calendar duration and ignores logs`() {
        // endDate built the way production builds it (DateUtils.endOfDayMillis, invariant #18) —
        // NOT `start + 7 * day`, which is a day too far and only ever existed in test fixtures.
        val end = DateUtils.endOfDayMillis(start, 7)
        // Logs are irrelevant for a fixed-end challenge — the count comes straight from start→end.
        val result = openEndedSafeDurationDays(start, end, listOf(log(start + 2 * day)))
        assertEquals(7, result)
    }

    @Test
    fun `a 7-day challenge created mid-day still reads as 7, not 6`() {
        // The off-by-one this helper used to have: created at 10:00, a 7-day challenge ends on day 7
        // at 23:59 — a 6.58-day raw span that truncated to "6 Tage" in the History detail header and
        // handed computeStats a budget one day short.
        val startAtTen = DateUtils.dayKey(start) + 10 * 60 * 60 * 1000L
        val end = DateUtils.endOfDayMillis(startAtTen, 7)

        assertEquals(7, openEndedSafeDurationDays(startAtTen, end, emptyList()))
    }

    @Test
    fun `fixed-end challenge with zero-or-negative span is clamped to 1`() {
        assertEquals(1, openEndedSafeDurationDays(start, start, emptyList()))
    }
}
