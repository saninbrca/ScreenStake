package com.detox.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import timber.log.Timber
import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.DailyLogDao
import com.detox.app.data.local.db.dao.GroupChallengeDao
import com.detox.app.data.local.db.dao.PendingHardChallengeDao
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.data.local.db.entity.GroupChallengeEntity
import com.detox.app.data.local.db.entity.PendingHardChallengeEntity

@Database(
    entities = [
        ChallengeEntity::class,
        DailyLogEntity::class,
        GroupChallengeEntity::class,
        PendingHardChallengeEntity::class
    ],
    version = 28,
    exportSchema = false
)
abstract class DetoxDatabase : RoomDatabase() {
    abstract fun challengeDao(): ChallengeDao
    abstract fun dailyLogDao(): DailyLogDao
    abstract fun groupChallengeDao(): GroupChallengeDao
    abstract fun pendingHardChallengeDao(): PendingHardChallengeDao

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

        /**
         * Adds usage-threshold notification flags to daily_logs so each threshold
         * (50 / 75 / 90 %) fires at most once per challenge per calendar day,
         * surviving service restarts.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE daily_logs ADD COLUMN notified50 INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE daily_logs ADD COLUMN notified75 INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE daily_logs ADD COLUMN notified90 INTEGER NOT NULL DEFAULT 0"
                )
                Timber.d("DB migration 16→17: added notified50/75/90 threshold columns to daily_logs")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE group_challenges ADD COLUMN blockedDomains TEXT DEFAULT NULL"
                )
                Timber.d("DB migration 17→18: added blockedDomains column to group_challenges")
            }
        }

        /** Adds partial-block URL path column for feature-level blocking (e.g. Instagram Reels). */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN partialBlockDomains TEXT DEFAULT NULL"
                )
                Timber.d("DB migration 18→19: added partialBlockDomains column to challenges")
            }
        }

        /** Adds native in-app section blocking columns to challenges. */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN partial_block_sections TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN partial_block_only INTEGER NOT NULL DEFAULT 0"
                )
                Timber.d("DB migration 20→21: added partial_block_sections and partial_block_only columns")
            }
        }

        /** Adds Redemption Challenge fields to challenges table. */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE challenges ADD COLUMN redemptionEligible INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE challenges ADD COLUMN redemptionDeadline INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE challenges ADD COLUMN redemptionShowAfter INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE challenges ADD COLUMN redemptionChallengeId TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE challenges ADD COLUMN redemptionRefundAmount INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE challenges ADD COLUMN redemptionDays INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE challenges ADD COLUMN redemptionLimit INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE challenges ADD COLUMN isRedemption INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE challenges ADD COLUMN originalChallengeId TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE challenges ADD COLUMN originalPaymentIntentId TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE challenges ADD COLUMN refundAmountCents INTEGER DEFAULT NULL")
                Timber.d("DB migration 21→22: added Redemption Challenge columns")
            }
        }

        /** Adds pending limit reduction fields to challenges. */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN pending_limit_value INTEGER DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN pending_limit_applies_at INTEGER DEFAULT NULL"
                )
                Timber.d("DB migration 23→24: added pending_limit_value and pending_limit_applies_at columns")
            }
        }

        /**
         * Removes the now-unused usage-threshold notification columns
         * (notified50 / notified75 / notified90) from daily_logs. The
         * `sendUsageThreshold` notifications were removed, so these flags are dead.
         * SQLite (on older Android) has no DROP COLUMN, so recreate the table
         * via CREATE + INSERT + DROP + RENAME, preserving all remaining data.
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE daily_logs_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        challengeId TEXT NOT NULL,
                        date INTEGER NOT NULL,
                        totalMinutes INTEGER NOT NULL,
                        openCount INTEGER NOT NULL,
                        consciousOpens INTEGER NOT NULL DEFAULT 0,
                        overlayPausedMs INTEGER NOT NULL DEFAULT 0,
                        budgetUsedMinutes INTEGER NOT NULL DEFAULT 0,
                        budgetRemainingMinutes INTEGER NOT NULL DEFAULT 0,
                        budgetUsedMs INTEGER NOT NULL DEFAULT 0,
                        budgetRemainingMs INTEGER NOT NULL DEFAULT 0,
                        pointsEarned INTEGER NOT NULL,
                        limitExceeded INTEGER NOT NULL,
                        moneyLostCents INTEGER NOT NULL,
                        FOREIGN KEY(challengeId) REFERENCES challenges(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO daily_logs_new
                        (id, challengeId, date, totalMinutes, openCount, consciousOpens,
                         overlayPausedMs, budgetUsedMinutes, budgetRemainingMinutes,
                         budgetUsedMs, budgetRemainingMs, pointsEarned, limitExceeded, moneyLostCents)
                    SELECT
                        id, challengeId, date, totalMinutes, openCount, consciousOpens,
                        overlayPausedMs, budgetUsedMinutes, budgetRemainingMinutes,
                        budgetUsedMs, budgetRemainingMs, pointsEarned, limitExceeded, moneyLostCents
                    FROM daily_logs
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE daily_logs")
                database.execSQL("ALTER TABLE daily_logs_new RENAME TO daily_logs")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_daily_logs_challengeId_date ON daily_logs(challengeId, date)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_daily_logs_challengeId ON daily_logs(challengeId)"
                )
                Timber.d("DB migration 24→25: dropped notified50/75/90 columns from daily_logs")
            }
        }

        /** Adds 5-day authorization expiry field to group_challenges. */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE group_challenges ADD COLUMN authorizationExpiresAt INTEGER NOT NULL DEFAULT 0"
                )
                Timber.d("DB migration 22→23: added authorizationExpiresAt to group_challenges")
            }
        }

        /** Adds millisecond-precision budget tracking columns to daily_logs. */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE daily_logs ADD COLUMN budgetUsedMs INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE daily_logs ADD COLUMN budgetRemainingMs INTEGER NOT NULL DEFAULT 0"
                )
                Timber.d("DB migration 19→20: added budgetUsedMs, budgetRemainingMs columns")
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

        /**
         * Creates the pending_hard_challenges table — durable payload for a Hard Mode challenge whose
         * PaymentIntent was created but whose challenge doc may not yet be persisted (money-critical
         * recovery after ViewModel/process recreation during the Stripe flow). New table only.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_hard_challenges` (
                        `challengeId` TEXT NOT NULL,
                        `paymentIntentId` TEXT NOT NULL,
                        `paymentIntentCreatedAt` INTEGER NOT NULL,
                        `isImmediateCapture` INTEGER NOT NULL,
                        `appDisplayName` TEXT NOT NULL,
                        `appPackageNames` TEXT NOT NULL,
                        `limitType` TEXT NOT NULL,
                        `limitValueMinutes` INTEGER NOT NULL,
                        `limitValueSessions` INTEGER,
                        `durationDays` INTEGER NOT NULL,
                        `amountCents` INTEGER NOT NULL,
                        `customMotivation` TEXT,
                        `blockedDomains` TEXT NOT NULL,
                        `partialBlockDomains` TEXT NOT NULL,
                        `blockingType` TEXT NOT NULL,
                        `blockAdultContent` INTEGER NOT NULL,
                        `scheduleStartTime` TEXT,
                        `scheduleEndTime` TEXT,
                        `activeDays` TEXT NOT NULL,
                        `sessionDurationMinutes` INTEGER NOT NULL,
                        `dailyBudgetMinutes` INTEGER,
                        `partialBlockSections` TEXT NOT NULL,
                        `isPartialBlockOnly` INTEGER NOT NULL,
                        `deviceId` TEXT,
                        `isRooted` INTEGER,
                        PRIMARY KEY(`challengeId`)
                    )
                    """.trimIndent()
                )
                Timber.d("DB migration 26→27: created pending_hard_challenges table")
            }
        }

        /**
         * Adds the blockAdultContent column to group_challenges so adult-block enforces for Group
         * challenges exactly like Solo/Hard. Default 0 (off) for existing rows.
         * group_challenges is NOT a money table — no capture/settlement data touched.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE group_challenges ADD COLUMN blockAdultContent INTEGER NOT NULL DEFAULT 0"
                )
                Timber.d("DB migration 27→28: added blockAdultContent column to group_challenges")
            }
        }

        /**
         * Adds the failReason column to challenges — used only by the loss result dialog (UX/data,
         * not money logic). Nullable; existing rows default to NULL → generic loss text.
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE challenges ADD COLUMN failReason TEXT DEFAULT NULL"
                )
                Timber.d("DB migration 25→26: added failReason column to challenges")
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
