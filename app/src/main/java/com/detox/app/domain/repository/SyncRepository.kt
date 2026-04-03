package com.detox.app.domain.repository

interface SyncRepository {
    /** Downloads active challenges, their daily logs, and point transactions from
     *  Firestore and upserts them into the local Room database. Idempotent. */
    suspend fun syncUserData(): Result<Unit>
}
