package com.detox.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the completion-trigger and open-ended helpers that back the soft-challenge fixes:
 *  - [DateUtils.hasReachedEnd] — the single end-of-challenge condition shared by the worker and the
 *    on-app-open backstop. Contract: `dayKey(now) >= dayKey(endMs)` — CALENDAR DAYS, not
 *    milliseconds, so the final day's own 23:59 run settles the challenge instead of slipping to
 *    day N+1. The former `|| durationDays == 1` escape hatch is gone: it fired on a 2-day challenge
 *    (raw span ≈ 1.x days → 1) at its very first evaluation.
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
    fun `hasReachedEnd is false on day 1 of a span that ends on the NEXT calendar day`() {
        // Raw-ms arithmetic: same clock time tomorrow. The old `durationDays == 1` arm read this
        // span as 1 and reported "ended" on the very first evaluation — a 2-day challenge settling
        // on day 1. Day-key comparison keeps it running until tomorrow.
        val end = start + day                // (end - start) / day == 1, but spans two day keys
        val now = start + 60_000L            // one minute in
        assertFalse(now >= end)              // sanity: not past end by timestamp…
        assertFalse(DateUtils.hasReachedEnd(start, end, now)) // …and not ended by day key either
    }

    @Test
    fun `hasReachedEnd is true all through a genuine 1-day challenge`() {
        // How production actually builds a 1-day challenge: endOfDayMillis adds durationDays - 1
        // days, so the end is 23:59:59.999 of the START day and both fall on the same day key.
        val end = DateUtils.endOfDayMillis(start, 1)
        assertTrue(DateUtils.hasReachedEnd(start, end, start + 60_000L))
        assertTrue(DateUtils.hasReachedEnd(start, end, end))
    }

    @Test
    fun `hasReachedEnd is true on the final day before the 23-59-59 end instant`() {
        // The reason for day-key comparison: the worker fires at ~23:59:00, endOfDayMillis resolves
        // to 23:59:59.999, so a raw `now >= endMs` was still false on the final day and settlement
        // slipped to 23:59 of day N+1 — a day whose usage is not part of the challenge at all.
        val end = DateUtils.endOfDayMillis(start, 7)
        val workerRun = end - 59_999L        // ~23:59:00 of the final day
        assertFalse(workerRun >= end)        // sanity: the old millisecond test would miss it…
        assertTrue(DateUtils.hasReachedEnd(start, end, workerRun)) // …the day-key test catches it
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
