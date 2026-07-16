package com.detox.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the completion-trigger and open-ended helpers that back the soft-challenge fixes:
 *  - [DateUtils.hasReachedEnd] — the single end-of-challenge condition shared by the worker and the
 *    on-app-open backstop. Contract: `now >= endMs || durationDays == 1`, where
 *    `durationDays == ((endMs - startMs) / MILLIS_PER_DAY).toInt()`.
 *  - [DateUtils.isOpenEnded] — the ~100-year sentinel guard used across the display surfaces.
 */
class DateUtilsTest {

    private val day = DateUtils.MILLIS_PER_DAY
    private val start = 1_000_000_000_000L

    // ── hasReachedEnd ────────────────────────────────────────────────────────────

    @Test
    fun `hasReachedEnd is false before endDate for a multi-day challenge`() {
        val end = start + 7 * day            // durationDays == 7
        val now = start + 3 * day            // day 3, still running
        assertFalse(DateUtils.hasReachedEnd(start, end, now))
    }

    @Test
    fun `hasReachedEnd is true one ms after endDate`() {
        val end = start + 7 * day
        assertTrue(DateUtils.hasReachedEnd(start, end, end + 1))
    }

    @Test
    fun `hasReachedEnd is true exactly at endDate (boundary, uses greater-or-equal)`() {
        val end = start + 7 * day
        assertTrue(DateUtils.hasReachedEnd(start, end, end))
    }

    @Test
    fun `hasReachedEnd is true when durationDays computes to 1 even though now is before end`() {
        val end = start + day                // (end - start) / day == 1
        val now = start + 60_000L            // one minute in, well before end
        assertFalse(now >= end)              // sanity: not past end by timestamp…
        assertTrue(DateUtils.hasReachedEnd(start, end, now)) // …but the durationDays==1 branch fires
    }

    // ── isOpenEnded ──────────────────────────────────────────────────────────────

    @Test
    fun `isOpenEnded is true for the sentinel span`() {
        val end = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        assertTrue(DateUtils.isOpenEnded(start, end))
    }

    @Test
    fun `isOpenEnded is false for a normal 365-day challenge`() {
        val end = start + 365 * day
        assertFalse(DateUtils.isOpenEnded(start, end))
    }

    @Test
    fun `isOpenEnded is false for non-positive timestamps`() {
        assertFalse(DateUtils.isOpenEnded(0L, 0L))
        assertFalse(DateUtils.isOpenEnded(1_000L, 0L))
    }

    @Test
    fun `hasReachedEnd never fires for an open-ended challenge at present time`() {
        val end = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        val now = start + 30 * day           // a month in — still ~100 years from end
        assertFalse(DateUtils.hasReachedEnd(start, end, now))
        // The raw span is enormous — this is exactly the value the display guard must suppress.
        assertTrue(((end - start) / day).toInt() >= DateUtils.NO_END_DATE_DAYS - 1)
    }
}
