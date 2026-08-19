package com.finite.focus.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finite.focus.util.DateUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `endedAt` column on the UPGRADE path — installing over existing data, which is the only path
 * that runs `migrate()` at all. A fresh install builds the schema straight from the entities and
 * would pass no matter what the migration did, so testing that proves nothing.
 *
 * Room's `MigrationTestHelper` is not usable here: the database is declared `exportSchema = false`,
 * so there are no schema JSONs to build a v28 database from. Instead this creates the v28 shape by
 * hand (only the columns the migration actually reads), runs the real `MIGRATION_28_29` object
 * against it, and asserts on the result — same SQL, same SQLite, real upgrade semantics.
 */
@RunWith(AndroidJUnit4::class)
class EndedAtMigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    private val day = DateUtils.MILLIS_PER_DAY
    private val now = System.currentTimeMillis()

    /** A plausible past timestamp: 60 days ago. */
    private fun daysAgo(n: Int) = now - n * day

    @Before
    fun setUp() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
        createV28Schema()
    }

    @After
    fun tearDown() {
        helper.close()
    }

    /** The pre-migration shape, limited to what MIGRATION_28_29 reads or asserts on. */
    private fun createV28Schema() {
        db.execSQL(
            """
            CREATE TABLE challenges (
                id TEXT NOT NULL PRIMARY KEY,
                appDisplayName TEXT NOT NULL,
                startDate INTEGER NOT NULL,
                endDate INTEGER NOT NULL,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE daily_logs (
                id TEXT NOT NULL PRIMARY KEY,
                challengeId TEXT NOT NULL,
                date INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun insertChallenge(id: String, startDate: Long, endDate: Long, status: String) {
        db.execSQL(
            "INSERT INTO challenges (id, appDisplayName, startDate, endDate, status, createdAt) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(id, "Instagram", startDate, endDate, status, startDate)
        )
    }

    private fun insertLog(challengeId: String, date: Long) {
        db.execSQL(
            "INSERT INTO daily_logs (id, challengeId, date) VALUES (?, ?, ?)",
            arrayOf<Any>("$challengeId-$date", challengeId, date)
        )
    }

    private fun migrate() = DetoxDatabase.MIGRATION_28_29.migrate(db)

    private fun read(id: String, column: String): Long? =
        db.query("SELECT $column FROM challenges WHERE id = ?", arrayOf(id)).use { c ->
            c.moveToFirst()
            if (c.isNull(0)) null else c.getLong(0)
        }

    private fun endedAt(id: String) = read(id, "endedAt")

    // ── The migration itself ──────────────────────────────────────────────────

    @Test
    fun migration_does_not_crash_and_preserves_existing_data() {
        val start = daysAgo(20)
        val end = daysAgo(13)
        insertChallenge("c1", start, end, "completed")

        migrate()

        assertEquals("startDate must survive", start, read("c1", "startDate"))
        assertEquals("endDate must survive UNCHANGED", end, read("c1", "endDate"))
        db.query("SELECT appDisplayName FROM challenges WHERE id = 'c1'").use {
            it.moveToFirst()
            assertEquals("Instagram", it.getString(0))
        }
    }

    @Test
    fun endDate_is_never_rewritten_by_the_migration() {
        // Settlement gating reads endDate. If the migration moved it, real money decisions move.
        val futureEnd = now + 25 * day
        insertChallenge("abandoned", daysAgo(5), futureEnd, "failed")
        val sentinelEnd = DateUtils.endOfDayMillis(daysAgo(30), DateUtils.NO_END_DATE_DAYS)
        insertChallenge("openended", daysAgo(30), sentinelEnd, "failed")

        migrate()

        assertEquals(futureEnd, read("abandoned", "endDate"))
        assertEquals(sentinelEnd, read("openended", "endDate"))
    }

    // ── Backfill ──────────────────────────────────────────────────────────────

    @Test
    fun backfills_a_ran_to_term_challenge_from_its_endDate() {
        val end = daysAgo(13)
        insertChallenge("c1", daysAgo(20), end, "completed")

        migrate()

        assertEquals(end, endedAt("c1"))
    }

    @Test
    fun backfills_an_early_abandoned_challenge_from_its_last_log_not_its_future_endDate() {
        // 30-day challenge abandoned on day 2 — endDate is 28 days in the FUTURE and unusable.
        val start = daysAgo(5)
        val lastLog = daysAgo(4)
        insertChallenge("c1", start, now + 25 * day, "failed")
        insertLog("c1", daysAgo(5))
        insertLog("c1", lastLog)

        migrate()

        assertEquals(lastLog, endedAt("c1"))
    }

    @Test
    fun backfills_an_open_ended_challenge_from_its_last_log_not_the_sentinel() {
        val start = daysAgo(40)
        val lastLog = daysAgo(11)
        val sentinelEnd = DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS)
        insertChallenge("c1", start, sentinelEnd, "failed")
        insertLog("c1", daysAgo(30))
        insertLog("c1", lastLog)

        migrate()

        assertEquals(lastLog, endedAt("c1"))
    }

    @Test
    fun backfill_takes_the_earlier_of_endDate_and_last_log() {
        // A log dated after the planned end (clock change, late write) must not win.
        val end = daysAgo(13)
        insertChallenge("c1", daysAgo(20), end, "completed")
        insertLog("c1", daysAgo(11))

        migrate()

        assertEquals(end, endedAt("c1"))
    }

    @Test
    fun backfill_ignores_a_legacy_day_offset_endDate() {
        // Early rows stored endDate as a day COUNT (7), not a timestamp. Using it dates 1970.
        val lastLog = daysAgo(9)
        insertChallenge("c1", daysAgo(16), 7L, "completed")
        insertLog("c1", lastLog)

        migrate()

        assertEquals(lastLog, endedAt("c1"))
    }

    @Test
    fun backfill_leaves_null_when_nothing_is_derivable() {
        // Open-ended, no logs → no honest date exists. A wrong date is worse than a missing one.
        val start = daysAgo(30)
        insertChallenge("c1", start, DateUtils.endOfDayMillis(start, DateUtils.NO_END_DATE_DAYS), "failed")

        migrate()

        assertNull(endedAt("c1"))
    }

    @Test
    fun backfill_never_dates_an_active_challenge() {
        insertChallenge("c1", daysAgo(3), now + 4 * day, "active")
        insertLog("c1", daysAgo(1))

        migrate()

        assertNull(endedAt("c1"))
    }

    @Test
    fun backfill_covers_every_terminal_status() {
        val end = daysAgo(13)
        listOf("completed", "failed", "ended", "ended_unverified").forEach { status ->
            insertChallenge(status, daysAgo(20), end, status)
        }

        migrate()

        listOf("completed", "failed", "ended", "ended_unverified").forEach { status ->
            assertEquals("status=$status", end, endedAt(status))
        }
    }
}
