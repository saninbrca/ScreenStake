package com.finite.focus.presentation.screens.history

import com.finite.focus.data.local.db.entity.ChallengeEntity
import com.finite.focus.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Month sectioning of the History list: headers appear once per month over a newest-first list,
 * never over an empty section, and rows with no trustworthy date collect at the end instead of
 * being scattered into whichever month their fallback happens to hit.
 */
class HistoryGroupingTest {

    private val day = DateUtils.MILLIS_PER_DAY

    private fun at(year: Int, month: Int, dayOfMonth: Int): Long = Calendar.getInstance().apply {
        clear()
        set(year, month, dayOfMonth, 12, 0, 0)
    }.timeInMillis

    private fun entry(
        id: String,
        endedAt: Long?,
        status: String = "completed",
        startDate: Long = at(2026, Calendar.JANUARY, 1),
    ): SoloChallengeHistory {
        val effectiveEnd = endedAt ?: startDate
        return SoloChallengeHistory(
            entity = ChallengeEntity(
                id = id,
                appPackageName = "com.instagram.android",
                appDisplayName = "Instagram",
                mode = "soft",
                limitType = "time",
                limitValueMinutes = 30,
                limitValueSessions = null,
                startDate = startDate,
                endDate = effectiveEnd,
                amountCents = null,
                stripePaymentIntentId = null,
                customMotivation = null,
                status = status,
                createdAt = startDate,
                endedAt = endedAt,
            ),
            effectiveEndDate = effectiveEnd,
            displayEndDate = endedAt,
            durationDays = 1,
            plannedDurationDays = 1,
        )
    }

    private fun headers(rows: List<HistoryRow>) = rows.filterIsInstance<HistoryRow.MonthHeader>()
    private fun entries(rows: List<HistoryRow>) = rows.filterIsInstance<HistoryRow.Entry>()

    @Test
    fun `one header per month, in list order`() {
        val rows = groupByMonth(
            listOf(
                entry("a", at(2026, Calendar.AUGUST, 20)),
                entry("b", at(2026, Calendar.AUGUST, 3)),
                entry("c", at(2026, Calendar.JULY, 28)),
            )
        )

        assertEquals(2, headers(rows).size)
        assertEquals(3, entries(rows).size)
        assertEquals(5, rows.size)
        assertTrue("list must start with a header", rows.first() is HistoryRow.MonthHeader)
    }

    @Test
    fun `entries stay under their own month header`() {
        val rows = groupByMonth(
            listOf(
                entry("aug", at(2026, Calendar.AUGUST, 20)),
                entry("jul", at(2026, Calendar.JULY, 28)),
            )
        )

        assertEquals(monthStart(at(2026, Calendar.AUGUST, 1)), (rows[0] as HistoryRow.MonthHeader).monthStartMs)
        assertEquals("aug", (rows[1] as HistoryRow.Entry).item.entity.id)
        assertEquals(monthStart(at(2026, Calendar.JULY, 1)), (rows[2] as HistoryRow.MonthHeader).monthStartMs)
        assertEquals("jul", (rows[3] as HistoryRow.Entry).item.entity.id)
    }

    @Test
    fun `the same month across different years gets separate headers`() {
        val rows = groupByMonth(
            listOf(
                entry("new", at(2026, Calendar.AUGUST, 5)),
                entry("old", at(2025, Calendar.AUGUST, 5)),
            )
        )

        assertEquals(2, headers(rows).size)
    }

    @Test
    fun `undated rows collect in a single trailing section`() {
        val rows = groupByMonth(
            listOf(
                entry("dated", at(2026, Calendar.AUGUST, 20)),
                entry("undated1", null),
                entry("undated2", null),
            )
        )

        val last3 = rows.takeLast(3)
        assertEquals(null, (last3[0] as HistoryRow.MonthHeader).monthStartMs)
        assertEquals("undated1", (last3[1] as HistoryRow.Entry).item.entity.id)
        assertEquals("undated2", (last3[2] as HistoryRow.Entry).item.entity.id)
        assertEquals("only one undated header", 1, headers(rows).count { it.monthStartMs == null })
    }

    @Test
    fun `no undated header when every row has a date`() {
        val rows = groupByMonth(listOf(entry("a", at(2026, Calendar.AUGUST, 20))))
        assertTrue(headers(rows).none { it.monthStartMs == null })
    }

    @Test
    fun `an empty list produces no headers at all`() {
        assertEquals(emptyList<HistoryRow>(), groupByMonth(emptyList()))
    }

    @Test
    fun `keys are unique so LazyColumn can key on them`() {
        val rows = groupByMonth(
            listOf(
                entry("a", at(2026, Calendar.AUGUST, 20)),
                entry("b", at(2026, Calendar.JULY, 28)),
                entry("c", null),
            )
        )
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `monthStart normalises any instant in a month to the same key`() {
        assertEquals(
            monthStart(at(2026, Calendar.AUGUST, 1)),
            monthStart(at(2026, Calendar.AUGUST, 31))
        )
    }

    @Test
    fun `a month boundary one day apart lands in different sections`() {
        val julyLast = at(2026, Calendar.JULY, 31)
        val rows = groupByMonth(
            listOf(entry("aug", julyLast + day), entry("jul", julyLast))
        )
        assertEquals(2, headers(rows).size)
    }
}
