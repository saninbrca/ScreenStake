package com.detox.app.presentation.screens.history

import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the History sort key never uses the ~100-year open-ended sentinel endDate — an
 * abandoned open-ended challenge must sort by its real last-active date, not float to the top
 * of the newest-first list forever.
 */
class EffectiveEndDateTest {

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
    fun `fixed-end challenge sorts by its real endDate and ignores logs`() {
        val end = start + 7 * day
        assertEquals(end, effectiveEndDate(start, end, listOf(log(start + 2 * day))))
    }

    @Test
    fun `open-ended challenge sorts by its last tracked log date, not the sentinel`() {
        val end = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS) // sentinel
        val logs = listOf(log(start), log(start + 11 * day), log(start + 5 * day))

        val result = effectiveEndDate(start, end, logs)

        assertEquals(start + 11 * day, result)
        assertTrue("must not leak the sentinel", result < end)
    }

    @Test
    fun `open-ended challenge with no logs falls back to startDate`() {
        val end = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        assertEquals(start, effectiveEndDate(start, end, emptyList()))
    }

    @Test
    fun `abandoned open-ended challenge sorts below a fixed-end challenge that finished later`() {
        val sentinelEnd = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        val openEndedKey = effectiveEndDate(start, sentinelEnd, listOf(log(start + 3 * day)))
        val fixedKey = effectiveEndDate(start, start + 10 * day, emptyList())
        assertTrue(openEndedKey < fixedKey)
    }
}
