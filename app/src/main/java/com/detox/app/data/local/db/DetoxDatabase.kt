package com.detox.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import timber.log.Timber
import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.DailyLogDao
import com.detox.app.data.local.db.dao.GroupChallengeDao
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.data.local.db.entity.GroupChallengeEntity

@Database(
    entities = [
        ChallengeEntity::class,
        DailyLogEntity::class,
        GroupChallengeEntity::class
    ],
    version = 16,
    exportSchema = false
)
abstract class DetoxDatabase : RoomDatabase() {
    abstract fun challengeDao(): ChallengeDao
    abstract fun dailyLogDao(): DailyLogDao
    abstract fun groupChallengeDao(): GroupChallengeDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE daily_logs ADD COLUMN consciousOpens INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Removes the `emergencyCode` column from the challenges table.
         * SQLite (on older Android) does not support DROP COLUMN, so we recreate the table
         * via CREATE + INSERT + DROP + RENAME.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE challenges_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        appPackageName TEXT NOT NULL,
                        appDisplayName TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        limitType TEXT NOT NULL,
                        limitValueMinutes INTEGER NOT NULL,
                        limitValueSessions INTEGER,
                        startDate INTEGER NOT NULL,
                        endDate INTEGER NOT NULL,
                        amountCents INTEGER,
                        stripePaymentIntentId TEXT,
                        customMotivation TEXT,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO challenges_new
                        (id, appPackageName, appDisplayName, mode, limitType,
                         limitValueMinutes, limitValueSessions, startDate, endDate,
                         amountCents, stripePaymentIntentId, customMotivation, status, createdAt)
                    SELECT
                        id, appPackageName, appDisplayName, mode, limitType,
                        limitValueMinutes, limitValueSessions, startDate, endDate,
                        amountCents, stripePaymentIntentId, customMotivation, status, createdAt
                    FROM challenges
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE challenges")
                database.execSQL("ALTER TABLE challenges_new RENAME TO challenges")
            }
        }

        /** Adds the overlayPausedMs column to daily_logs for screen-time attribution. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE daily_logs ADD COLUMN overlayPausedMs INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Adds the Daily Time Budget feature:
         * - [challenges] gets dailyBudgetMinutes (nullable INTEGER).
         * - [daily_logs] gets budgetUsedMinutes and budgetRemainingMinutes (both INTEGER DEFAULT 0).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN dailyBudgetMinutes INTEGER DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE daily_logs ADD COLUMN budgetUsedMinutes INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE daily_logs ADD COLUMN budgetRemainingMinutes INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Timber.d("DB migration 6→7 complete")
            }
        }

        /** Adds multi-app package list and website blocking domain columns. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN appPackageNames TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN blockedDomains TEXT DEFAULT NULL"
                )
                Timber.d("DB migration 7→8: added appPackageNames and blockedDomains columns")
            }
        }

        /** Adds schedule (time window + active days) columns. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN scheduleStartTime TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN scheduleEndTime TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN activeDays TEXT DEFAULT NULL"
                )
                Timber.d("DB migration 9→10: added scheduleStartTime, scheduleEndTime, activeDays columns")
            }
        }

        /** Adds blockingType and blockAdultContent columns for the APP/WEBSITE challenge type redesign. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN blockingType TEXT NOT NULL DEFAULT 'app'"
                )
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN blockAdultContent INTEGER NOT NULL DEFAULT 0"
                )
                Timber.d("DB migration 8→9: added blockingType and blockAdultContent columns")
            }
        }

        /** Creates the group_challenges table for the Friends / multiplayer feature. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS group_challenges (
                        groupId TEXT NOT NULL PRIMARY KEY,
                        code TEXT NOT NULL,
                        creatorUserId TEXT NOT NULL,
                        appPackageNames TEXT NOT NULL DEFAULT '',
                        appDisplayName TEXT NOT NULL,
                        limitType TEXT NOT NULL,
                        limitValueMinutes INTEGER NOT NULL,
                        limitValueSessions INTEGER,
                        durationDays INTEGER NOT NULL,
                        buyInCents INTEGER NOT NULL,
                        maxParticipants INTEGER NOT NULL,
                        startDate INTEGER NOT NULL,
                        endDate INTEGER NOT NULL,
                        bonusEnabled INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'waiting',
                        participantsJson TEXT NOT NULL DEFAULT '[]'
                    )
                    """.trimIndent()
                )
                Timber.d("DB migration 10→11: created group_challenges table")
            }
        }

        /**
         * Fixes the group_challenges table: devices that ran MIGRATION_10_11 before the
         * minBuyInCents → buyInCents rename have a stale schema. Since the table is new
         * (no real user data to preserve), we simply DROP + recreate it with the correct
         * column name, and also add the completionShown column to challenges.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS group_challenges")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS group_challenges (
                        groupId TEXT NOT NULL PRIMARY KEY,
                        code TEXT NOT NULL,
                        creatorUserId TEXT NOT NULL,
                        appPackageNames TEXT NOT NULL DEFAULT '',
                        appDisplayName TEXT NOT NULL,
                        limitType TEXT NOT NULL,
                        limitValueMinutes INTEGER NOT NULL,
                        limitValueSessions INTEGER,
                        durationDays INTEGER NOT NULL,
                        buyInCents INTEGER NOT NULL,
                        maxParticipants INTEGER NOT NULL,
                        startDate INTEGER NOT NULL,
                        endDate INTEGER NOT NULL,
                        bonusEnabled INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'waiting',
                        participantsJson TEXT NOT NULL DEFAULT '[]'
                    )
                    """.trimIndent()
                )
                Timber.d("DB migration 12→13: recreated group_challenges with buyInCents column")
            }
        }

        /**
         * Adds sessionDurationMinutes to group_challenges and groupChallengeId to challenges.
         * sessionDurationMinutes: countdown duration for SESSIONS group challenges.
         * groupChallengeId: links a local shadow challenge to its group challenge parent.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE group_challenges ADD COLUMN sessionDurationMinutes INTEGER NOT NULL DEFAULT 5")
                database.execSQL("ALTER TABLE challenges ADD COLUMN groupChallengeId TEXT DEFAULT NULL")
                Timber.d("DB migration 14→15: added sessionDurationMinutes to group_challenges, groupChallengeId to challenges")
            }
        }

        /** Drops the point_transactions table — points system removed. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS point_transactions")
                Timber.d("DB migration 15→16: dropped point_transactions table")
            }
        }

        /** Adds sessionDurationMinutes column for per-session countdown timer in SESSIONS challenges. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN sessionDurationMinutes INTEGER NOT NULL DEFAULT 5"
                )
                Timber.d("DB migration 13→14: added sessionDurationMinutes column (default 5 min)")
            }
        }

        /** Adds completionShown column to track whether the success overlay has been shown. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN completionShown INTEGER NOT NULL DEFAULT 0"
                )
                Timber.d("DB migration 11→12: added completionShown column")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
            CREATE TABLE challenges_new (
                id TEXT NOT NULL PRIMARY KEY,
                appPackageName TEXT NOT NULL,
                appDisplayName TEXT NOT NULL,
                mode TEXT NOT NULL,
                limitType TEXT NOT NULL,
                limitValueMinutes INTEGER NOT NULL,
                limitValueSessions INTEGER,
                startDate INTEGER NOT NULL,
                endDate INTEGER NOT NULL,
                amountCents INTEGER,
                stripePaymentIntentId TEXT,
                customMotivation TEXT,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                dailyBudgetMinutes INTEGER DEFAULT NULL
            )
        """.trimIndent())
                database.execSQL("""
            INSERT INTO challenges_new SELECT
                id, appPackageName, appDisplayName, mode, limitType,
                limitValueMinutes, limitValueSessions, startDate, endDate,
                amountCents, stripePaymentIntentId, customMotivation, status,
                createdAt, dailyBudgetMinutes
            FROM challenges
        """.trimIndent())
                database.execSQL("DROP TABLE challenges")
                database.execSQL("ALTER TABLE challenges_new RENAME TO challenges")
            }
        }
    }
}
