package com.detox.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.DailyLogDao
import com.detox.app.data.local.db.dao.PointTransactionDao
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.data.local.db.entity.PointTransactionEntity

@Database(
    entities = [
        ChallengeEntity::class,
        DailyLogEntity::class,
        PointTransactionEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class DetoxDatabase : RoomDatabase() {
    abstract fun challengeDao(): ChallengeDao
    abstract fun dailyLogDao(): DailyLogDao
    abstract fun pointTransactionDao(): PointTransactionDao

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
                    "ALTER TABLE challenges ADD COLUMN dailyBudgetMinutes INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE daily_logs ADD COLUMN budgetUsedMinutes INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE daily_logs ADD COLUMN budgetRemainingMinutes INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
