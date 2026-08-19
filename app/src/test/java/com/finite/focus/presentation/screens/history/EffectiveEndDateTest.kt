package com.finite.focus.presentation.screens.history

import com.finite.focus.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single end-date accessor and the two helpers that decide how it reaches the screen:
 * [displayableEndDate] (may this date be shown at all?) and [effectiveEndDate] itself (what is it?).
 *
 * The list now SORTS, GROUPS and DISPLAYS the same field, so a wrong value here is visible three
 * ways at once — which is the whole point of there being only one of it.
 */
class EffectiveEndDateTest {

    private val day = DateUtils.MILLIS_PER_DAY
    private val start = 1_000_000_000_000L

    // ── effectiveEndDate: fallbacks for rows with no recorded endedAt ──────────

    @Test
    fun `fixed-end challenge sorts by its real endDate and ignores logs`() {
        val end = start + 7 * day
        assertEquals(end, effectiveEndDate(start, end, lastLogDateMs = start + 2 * day))
    }

    @Test
    fun `open-ended challenge sorts by its last tracked log date, not the sentinel`() {
        val end = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)

        val result = effectiveEndDate(start, end, lastLogDateMs = start + 11 * day)

        assertEquals(start + 11 * day, result)
        assertTrue("must not leak the sentinel", result < end)
    }

    @Test
    fun `open-ended challenge with no logs falls back to startDate`() {
        val end = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        assertEquals(start, effectiveEndDate(start, end, lastLogDateMs = null))
    }

    @Test
    fun `abandoned open-ended challenge sorts below a fixed-end challenge that finished later`() {
        val sentinelEnd = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        val openEndedKey = effectiveEndDate(start, sentinelEnd, lastLogDateMs = start + 3 * day)
        val fixedKey = effectiveEndDate(start, start + 10 * day, lastLogDateMs = null)
        assertTrue(openEndedKey < fixedKey)
    }

    // ── endedAt takes precedence over every fallback ───────────────────────────

    @Test
    fun `a recorded endedAt wins over the planned endDate`() {
        // Abandoned on day 2 of a 30-day challenge. The planned end is 28 days in the FUTURE and
        // must not be what the row sorts, groups or reads by.
        val plannedEnd = start + 30 * day
        val reallyEnded = start + 2 * day

        val result = effectiveEndDate(start, plannedEnd, lastLogDateMs = null, endedAtMs = reallyEnded)

        assertEquals(reallyEnded, result)
        assertTrue("must not fall back to the planned end", result < plannedEnd)
    }

    @Test
    fun `a recorded endedAt wins over the open-ended log-max fallback`() {
        val sentinelEnd = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        val reallyEnded = start + 40 * day

        val result = effectiveEndDate(
            start, sentinelEnd, lastLogDateMs = start + 11 * day, endedAtMs = reallyEnded
        )

        assertEquals(reallyEnded, result)
    }

    @Test
    fun `an open-ended challenge with no logs but a recorded endedAt no longer collapses to start`() {
        val sentinelEnd = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        val reallyEnded = start + 90 * day

        assertEquals(
            reallyEnded,
            effectiveEndDate(start, sentinelEnd, lastLogDateMs = null, endedAtMs = reallyEnded)
        )
    }

    @Test
    fun `a null endedAt keeps the pre-existing fallback behaviour for un-backfilled rows`() {
        val end = start + 7 * day
        assertEquals(end, effectiveEndDate(start, end, lastLogDateMs = null, endedAtMs = null))
    }

    // ── displayableEndDate: what may actually be put in front of the user ──────

    private val now = 1_800_000_000_000L // 2027-01-15, comfortably after MIN_PLAUSIBLE

    @Test
    fun `a real past end date is displayable`() {
        val ended = now - 10 * day
        assertEquals(ended, displayableEndDate(ended, nowMs = now))
    }

    @Test
    fun `a future date is never displayable`() {
        // The fallback for a challenge abandoned early IS its planned end, which has not arrived.
        // Claiming the user finished something next month is worse than showing no date.
        assertNull(displayableEndDate(now + 25 * day, nowMs = now))
    }

    @Test
    fun `a legacy day-offset endDate is never displayable as 1970`() {
        assertNull(displayableEndDate(7L, nowMs = now))
        assertNull(displayableEndDate(0L, nowMs = now))
    }

    @Test
    fun `the open-ended sentinel is never displayable`() {
        val sentinel = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        assertNull(displayableEndDate(sentinel, nowMs = now))
    }

    @Test
    fun `an end date exactly now is displayable`() {
        assertEquals(now, displayableEndDate(now, nowMs = now))
    }
}
