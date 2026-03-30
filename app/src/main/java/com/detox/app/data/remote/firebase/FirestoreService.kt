package com.detox.app.data.remote.firebase

import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.PointTransaction
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles all Firestore read/write operations.
 * All writes are fire-and-forget — callers should invoke these inside a background scope
 * so failures never block the local Room operations.
 */
@Singleton
class FirestoreService @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    // ── Challenges ─────────────────────────────────────────────────────────────

    suspend fun saveChallenge(userId: String, challenge: Challenge) {
        try {
            firestore
                .collection("users").document(userId)
                .collection("challenges").document(challenge.id)
                .set(challenge.toMap())
                .await()
            Timber.d("Synced challenge ${challenge.id} to Firestore")
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync challenge ${challenge.id}")
        }
    }

    suspend fun updateChallengeStatus(userId: String, challengeId: String, status: String) {
        try {
            firestore
                .collection("users").document(userId)
                .collection("challenges").document(challengeId)
                .update("status", status, "syncedAt", com.google.firebase.Timestamp.now())
                .await()
            Timber.d("Updated challenge $challengeId status to $status in Firestore")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update challenge $challengeId status")
        }
    }

    // ── Daily Logs ─────────────────────────────────────────────────────────────

    suspend fun saveDailyLog(userId: String, log: DailyLog) {
        try {
            val dateKey = log.date.toString()
            firestore
                .collection("users").document(userId)
                .collection("challenges").document(log.challengeId)
                .collection("dailyLogs").document(dateKey)
                .set(log.toMap())
                .await()
            Timber.d("Synced daily log ${log.id} to Firestore")
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync daily log ${log.id}")
        }
    }

    // ── Point Transactions ─────────────────────────────────────────────────────

    suspend fun savePointTransaction(userId: String, transaction: PointTransaction) {
        try {
            firestore
                .collection("users").document(userId)
                .collection("pointTransactions").document(transaction.id)
                .set(transaction.toMap())
                .await()
            Timber.d("Synced point transaction ${transaction.id} to Firestore")
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync point transaction ${transaction.id}")
        }
    }

    // ── Mapping helpers ────────────────────────────────────────────────────────

    private fun Challenge.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "appPackageName" to appPackageName,
        "appDisplayName" to appDisplayName,
        "mode" to mode.name.lowercase(),
        "limitType" to limitType.name.lowercase(),
        "limitValueMinutes" to limitValueMinutes,
        "limitValueSessions" to limitValueSessions,
        "startDate" to startDate,
        "endDate" to endDate,
        "amountCents" to amountCents,
        "stripePaymentIntentId" to stripePaymentIntentId,
        "customMotivation" to customMotivation,
        "status" to status.name.lowercase(),
        "createdAt" to createdAt,
        "syncedAt" to com.google.firebase.Timestamp.now()
    )

    private fun DailyLog.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "challengeId" to challengeId,
        "date" to date,
        "totalMinutes" to totalMinutes,
        "openCount" to openCount,
        "pointsEarned" to pointsEarned,
        "limitExceeded" to limitExceeded,
        "moneyLostCents" to moneyLostCents
    )

    private fun PointTransaction.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to type,
        "amount" to amount,
        "reason" to reason,
        "challengeId" to challengeId,
        "timestamp" to timestamp
    )
}
