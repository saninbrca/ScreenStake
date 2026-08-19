package com.finite.focus.presentation.screens.history

import com.finite.focus.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The day count History shows ("Dauer: X Tage", "nach N von M Tagen").
 *
 * There is no dedicated duration helper any more: the count is
 * `DateUtils.calendarDurationDays(startDate, effectiveEndDate(...))`, i.e. the one day-count
 * definition applied to the one end-date accessor. That composition replaced
 * `openEndedSafeDurationDays`, whose separate open-ended branch became redundant once the accessor
 * itself stopped returning the sentinel — and which counted to the PLANNED end, so a 30-day
 * challenge abandoned on day 2 reported "30 Tage". Every case that helper covered is kept below.
 */
class HistoryDurationDaysTest {

    private val day = DateUtils.MILLIS_PER_DAY
    // A plausible modern instant (2025-08-12). Must sit above DateUtils.MIN_PLAUSIBLE_TIMESTAMP_MS,
    // since plannedDurationDays treats anything below it as the legacy day-offset endDate shape.
    private val start = 1_755_000_000_000L

    /** Exactly what the ViewModel does, so the test cannot drift from production. */
    private fun durationDays(
        startMs: Long,
        endMs: Long,
        lastLogDateMs: Long? = null,
        endedAtMs: Long? = null,
    ): Int = DateUtils.calendarDurationDays(
        startMs,
        effectiveEndDate(startMs, endMs, lastLogDateMs, endedAtMs)
    )

    // ── Cases inherited from openEndedSafeDurationDays ─────────────────────────

    @Test
    fun `open-ended challenge returns real days survived, not the sentinel span`() {
        val end = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS) // ~36 500-day sentinel

        val result = durationDays(start, end, lastLogDateMs = start + 11 * day)

        assertEquals(12, result) // (11 days elapsed) + 1
        assertTrue("must not leak the sentinel span", result < DateUtils.NO_END_DATE_DAYS)
    }

    @Test
    fun `open-ended challenge with no logs returns at least 1 day`() {
        val end = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        assertEquals(1, durationDays(start, end))
    }

    @Test
    fun `fixed-end challenge returns its calendar duration and ignores logs`() {
        // endDate built the way production builds it (DateUtils.endOfDayMillis, invariant #18) —
        // NOT `start + 7 * day`, which is a day too far and only ever existed in test fixtures.
        val end = DateUtils.endOfDayMillis(start, 7)

        assertEquals(7, durationDays(start, end, lastLogDateMs = start + 2 * day))
    }

    @Test
    fun `a 7-day challenge created mid-day still reads as 7, not 6`() {
        // Created at 10:00, a 7-day challenge ends on day 7 at 23:59 — a 6.58-day raw span that
        // truncated to "6 Tage" in the History detail header.
        val startAtTen = DateUtils.dayKey(start) + 10 * 60 * 60 * 1000L
        val end = DateUtils.endOfDayMillis(startAtTen, 7)

        assertEquals(7, durationDays(startAtTen, end))
    }

    @Test
    fun `fixed-end challenge with zero-or-negative span is clamped to 1`() {
        assertEquals(1, durationDays(start, start))
    }

    // ── What the endedAt column changed ───────────────────────────────────────

    @Test
    fun `an early-abandoned challenge counts the days it actually ran, not the plan`() {
        // 30-day challenge abandoned on day 2. This is the "Dauer: 30 Tage" bug.
        val plannedEnd = DateUtils.endOfDayMillis(start, 30)
        val reallyEnded = start + 1 * day

        assertEquals(2, durationDays(start, plannedEnd, endedAtMs = reallyEnded))
    }

    @Test
    fun `a challenge that ran to term still counts its full planned length`() {
        val plannedEnd = DateUtils.endOfDayMillis(start, 30)

        assertEquals(30, durationDays(start, plannedEnd, endedAtMs = plannedEnd))
    }

    // ── plannedDurationDays: the denominator of "after N of M days" ────────────

    @Test
    fun `planned duration is the full plan even for a challenge abandoned early`() {
        val plannedEnd = DateUtils.endOfDayMillis(start, 30)
        assertEquals(30, plannedDurationDays(start, plannedEnd))
    }

    @Test
    fun `planned duration is null for an open-ended challenge`() {
        val end = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        assertNull("36500 must never become a denominator", plannedDurationDays(start, end))
    }

    @Test
    fun `planned duration is null for a legacy day-offset endDate`() {
        assertNull(plannedDurationDays(start, 7L))
    }
}
