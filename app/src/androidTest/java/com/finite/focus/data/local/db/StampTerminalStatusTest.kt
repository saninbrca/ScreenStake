package com.finite.focus.data.local.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finite.focus.data.local.db.dao.ChallengeDao
import com.finite.focus.data.local.db.dao.markTerminal
import com.finite.focus.data.local.db.entity.ChallengeEntity
import com.finite.focus.util.DateUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ChallengeDao.stampTerminalStatus` — the single write every terminal path funnels through.
 *
 * Runs against a real Room database and the real DAO (no mirrored SQL), because the two properties
 * under test are properties of the STATEMENT: the `COALESCE` that makes it stamp-once, and the
 * `CASE` that clamps the date to the planned end. Both are invisible to a Kotlin-level test.
 */
@RunWith(AndroidJUnit4::class)
class StampTerminalStatusTest {

    private lateinit var db: DetoxDatabase
    private lateinit var dao: ChallengeDao

    private val day = DateUtils.MILLIS_PER_DAY
    private val now = System.currentTimeMillis()

    private fun daysAgo(n: Int) = now - n * day

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            DetoxDatabase::class.java
        ).build()
        dao = db.challengeDao()
    }

    @After
    fun tearDown() = db.close()

    private fun challenge(
        id: String,
        startDate: Long,
        endDate: Long,
        status: String = "active",
        endedAt: Long? = null,
    ) = ChallengeEntity(
        id = id,
        appPackageName = "com.instagram.android",
        appDisplayName = "Instagram",
        mode = "soft",
        limitType = "time",
        limitValueMinutes = 30,
        limitValueSessions = null,
        startDate = startDate,
        endDate = endDate,
        amountCents = null,
        stripePaymentIntentId = null,
        customMotivation = null,
        status = status,
        createdAt = startDate,
        endedAt = endedAt,
    )

    private suspend fun row(id: String) = dao.getChallengeById(id)!!

    // ── The clamp: which date the stamp records ───────────────────────────────

    @Test
    fun ended_early_records_now() = runBlocking {
        // Abandoned on day 2 of 30 — the planned end is in the FUTURE and is not the answer.
        dao.insertChallenge(challenge("c1", daysAgo(5), now + 25 * day))

        dao.markTerminal("c1", "failed", nowMs = now)

        assertEquals(now, row("c1").endedAt)
    }

    @Test
    fun ran_to_term_clamps_back_to_the_planned_end() = runBlocking {
        // The group sweep fires the day AFTER the last day; a raw `now` would read one day late.
        val end = daysAgo(1)
        dao.insertChallenge(challenge("c1", daysAgo(8), end))

        dao.markTerminal("c1", "ended", nowMs = now)

        assertEquals(end, row("c1").endedAt)
    }

    @Test
    fun open_ended_records_now_never_the_sentinel() = runBlocking {
        val start = daysAgo(40)
        val sentinel = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        dao.insertChallenge(challenge("c1", start, sentinel))

        dao.markTerminal("c1", "failed", nowMs = now)

        assertEquals(now, row("c1").endedAt)
    }

    @Test
    fun legacy_day_offset_endDate_records_now_rather_than_1970() = runBlocking {
        dao.insertChallenge(challenge("c1", daysAgo(9), endDate = 7L))

        dao.markTerminal("c1", "completed", nowMs = now)

        assertEquals(now, row("c1").endedAt)
    }

    // ── Stamp once ────────────────────────────────────────────────────────────

    @Test
    fun a_second_terminal_write_advances_status_but_never_moves_the_date() = runBlocking {
        val firstStamp = daysAgo(2)
        dao.insertChallenge(challenge("c1", daysAgo(9), now + 20 * day))

        dao.markTerminal("c1", "ended", nowMs = firstStamp)
        dao.markTerminal("c1", "completed", nowMs = now) // settlement lands days later

        val result = row("c1")
        assertEquals("a re-settle must not move the date", firstStamp, result.endedAt)
        assertEquals("status must still advance", "completed", result.status)
    }

    @Test
    fun re_running_the_same_settlement_is_idempotent() = runBlocking {
        dao.insertChallenge(challenge("c1", daysAgo(9), now + 20 * day))

        dao.markTerminal("c1", "failed", nowMs = daysAgo(3))
        dao.markTerminal("c1", "failed", nowMs = daysAgo(2))
        dao.markTerminal("c1", "failed", nowMs = now)

        assertEquals(daysAgo(3), row("c1").endedAt)
    }

    @Test
    fun a_backfilled_date_survives_a_later_terminal_write() = runBlocking {
        val backfilled = daysAgo(13)
        dao.insertChallenge(
            challenge("c1", daysAgo(20), daysAgo(13), status = "completed", endedAt = backfilled)
        )

        dao.markTerminal("c1", "completed", nowMs = now)

        assertEquals(backfilled, row("c1").endedAt)
    }

    // ── endDate is never touched ──────────────────────────────────────────────

    @Test
    fun the_planned_endDate_is_never_rewritten() = runBlocking {
        // Settlement gating reads endDate. This statement must add a column, not move one.
        val plannedEnd = now + 25 * day
        dao.insertChallenge(challenge("c1", daysAgo(5), plannedEnd))

        dao.markTerminal("c1", "failed", nowMs = now)

        assertEquals(plannedEnd, row("c1").endDate)
    }

    // ── Non-terminal rows ─────────────────────────────────────────────────────

    @Test
    fun an_untouched_active_challenge_has_no_endedAt() = runBlocking {
        dao.insertChallenge(challenge("c1", daysAgo(3), now + 4 * day))

        assertNull(row("c1").endedAt)
    }

    @Test
    fun updateStatus_alone_does_not_stamp_a_date() = runBlocking {
        // The non-terminal branch of updateChallengeStatus must not date anything.
        dao.insertChallenge(challenge("c1", daysAgo(3), now + 4 * day))

        dao.updateStatus("c1", "active")

        assertNull(row("c1").endedAt)
    }
}
