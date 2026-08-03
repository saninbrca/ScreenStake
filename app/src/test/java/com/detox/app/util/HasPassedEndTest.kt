package com.detox.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [DateUtils.hasPassedEnd] — the offline "is this challenge over?" predicate.
 *
 * Two properties matter and neither is obvious:
 *  1. It is STRICTLY after the last day, unlike [DateUtils.hasReachedEnd]'s `>=`. Settlement fires
 *     ON the final day (the 23:59 worker settles that day's own usage); enforcement has to cover
 *     that whole day. A `>=` here would unlock the app at 00:00 of the last day.
 *  2. It compares DAY KEYS, not milliseconds — `endDate` is 23:59:59.999 local (invariant #18), so
 *     a raw millis compare is one DST shift away from misfiring in either direction.
 */
class HasPassedEndTest {

    private val day = DateUtils.MILLIS_PER_DAY
    private val now = System.currentTimeMillis()

    @Test
    fun `not passed on the challenge's own last day`() {
        val end = DateUtils.endOfDayMillis(now, 1) // 23:59:59.999 today
        assertFalse(DateUtils.hasPassedEnd(end, now))
    }

    @Test
    fun `not passed at one second past midnight ON the last day`() {
        val end = DateUtils.endOfDayMillis(now, 1)
        assertFalse(DateUtils.hasPassedEnd(end, DateUtils.dayKey(now) + 1_000L))
    }

    @Test
    fun `not passed one millisecond before the last day ends`() {
        val end = DateUtils.endOfDayMillis(now, 1)
        assertFalse(DateUtils.hasPassedEnd(end, end - 1))
    }

    @Test
    fun `passed just after midnight the following day - the 00 30 incident`() {
        val end = DateUtils.endOfDayMillis(now - day, 1)      // ended yesterday 23:59:59.999
        val at0030 = DateUtils.dayKey(now) + 30 * 60_000L     // today, 00:30
        assertTrue(DateUtils.hasPassedEnd(end, at0030))
    }

    @Test
    fun `passed for a challenge that ended days ago`() {
        assertTrue(DateUtils.hasPassedEnd(now - 5 * day, now))
    }

    @Test
    fun `not passed for a challenge still days from its end`() {
        assertTrue(!DateUtils.hasPassedEnd(DateUtils.endOfDayMillis(now, 5), now))
    }

    @Test
    fun `a zero or negative endDate never counts as passed`() {
        // Fail-safe: a missing/garbage end date must never be read as "over" — that would silently
        // switch enforcement off for a live challenge.
        assertFalse(DateUtils.hasPassedEnd(0L, now))
        assertFalse(DateUtils.hasPassedEnd(-1L, now))
    }
}
