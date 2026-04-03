package com.detox.app.data.remote.firebase

import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.LimitType
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

    // ── User document ──────────────────────────────────────────────────────────

    /**
     * Creates (or merges) the top-level user document at users/{userId}.
     * Called immediately after registration or first Google Sign-In.
     * Uses merge so it is safe to call again for returning users.
     */
    suspend fun createUserDocument(userId: String, email: String, displayName: String? = null) {
        try {
            val data = mutableMapOf<String, Any>(
                "email" to email,
                "totalPoints" to 0,
                "createdAt" to com.google.firebase.Timestamp.now()
            )
            displayName?.let { data["displayName"] = it }
            firestore
                .collection("users").document(userId)
                .set(data, com.google.firebase.firestore.SetOptions.merge())
                .await()
            Timber.d("User document created/merged for uid=$userId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to create user document for uid=$userId")
        }
    }

    /**
     * Saves (or updates) the FCM registration token for the given user.
     * Called by [DetoxFirebaseMessagingService.onNewToken] whenever the token changes.
     */
    suspend fun saveFcmToken(userId: String, token: String) {
        try {
            firestore.collection("users").document(userId)
                .update("fcmToken", token)
                .await()
            Timber.d("FCM token saved for uid=$userId")
        } catch (e: Exception) {
            // update() fails if the document doesn't exist yet — fall back to set(merge)
            try {
                firestore.collection("users").document(userId)
                    .set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge())
                    .await()
                Timber.d("FCM token saved (via merge) for uid=$userId")
            } catch (e2: Exception) {
                Timber.e(e2, "Failed to save FCM token for uid=$userId")
            }
        }
    }

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

    // ── Read / sync-down methods ───────────────────────────────────────────────

    /**
     * Fetches all challenges with status "active" for the given user.
     * Returns an empty list on any network failure so the caller can degrade gracefully.
     */
    suspend fun fetchActiveChallenges(userId: String): List<Challenge> {
        return try {
            val snapshot = firestore
                .collection("users").document(userId)
                .collection("challenges")
                .whereEqualTo("status", "active")
                .get()
                .await()
            snapshot.documents.mapNotNull { doc ->
                val d = doc.data ?: return@mapNotNull null
                try {
                    Challenge(
                        id = d["id"] as? String ?: doc.id,
                        appPackageName = d["appPackageName"] as? String ?: return@mapNotNull null,
                        appDisplayName = d["appDisplayName"] as? String ?: return@mapNotNull null,
                        mode = ChallengeMode.valueOf(
                            (d["mode"] as? String ?: "soft").uppercase()
                        ),
                        limitType = LimitType.valueOf(
                            (d["limitType"] as? String ?: "time").uppercase()
                        ),
                        limitValueMinutes = (d["limitValueMinutes"] as? Long)?.toInt() ?: 0,
                        limitValueSessions = (d["limitValueSessions"] as? Long)?.toInt(),
                        startDate = d["startDate"] as? Long ?: 0L,
                        endDate = d["endDate"] as? Long ?: 0L,
                        amountCents = (d["amountCents"] as? Long)?.toInt(),
                        stripePaymentIntentId = d["stripePaymentIntentId"] as? String,
                        customMotivation = d["customMotivation"] as? String,
                        status = ChallengeStatus.valueOf(
                            (d["status"] as? String ?: "active").uppercase()
                        ),
                        createdAt = d["createdAt"] as? Long ?: 0L
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse challenge document ${doc.id}")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch active challenges for uid=$userId")
            emptyList()
        }
    }

    /**
     * Fetches all daily log entries for the given challenge.
     */
    suspend fun fetchDailyLogs(userId: String, challengeId: String): List<DailyLog> {
        return try {
            val snapshot = firestore
                .collection("users").document(userId)
                .collection("challenges").document(challengeId)
                .collection("dailyLogs")
                .get()
                .await()
            snapshot.documents.mapNotNull { doc ->
                val d = doc.data ?: return@mapNotNull null
                try {
                    DailyLog(
                        id = d["id"] as? String ?: doc.id,
                        challengeId = d["challengeId"] as? String ?: challengeId,
                        date = d["date"] as? Long ?: 0L,
                        totalMinutes = (d["totalMinutes"] as? Long)?.toInt() ?: 0,
                        openCount = (d["openCount"] as? Long)?.toInt() ?: 0,
                        consciousOpens = (d["consciousOpens"] as? Long)?.toInt() ?: 0,
                        overlayPausedMs = d["overlayPausedMs"] as? Long ?: 0L,
                        pointsEarned = (d["pointsEarned"] as? Long)?.toInt() ?: 0,
                        limitExceeded = d["limitExceeded"] as? Boolean ?: false,
                        moneyLostCents = (d["moneyLostCents"] as? Long)?.toInt() ?: 0
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse daily log document ${doc.id}")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch daily logs for challengeId=$challengeId")
            emptyList()
        }
    }

    /**
     * Fetches all point transactions for the given user.
     */
    suspend fun fetchPointTransactions(userId: String): List<PointTransaction> {
        return try {
            val snapshot = firestore
                .collection("users").document(userId)
                .collection("pointTransactions")
                .get()
                .await()
            snapshot.documents.mapNotNull { doc ->
                val d = doc.data ?: return@mapNotNull null
                try {
                    PointTransaction(
                        id = d["id"] as? String ?: doc.id,
                        type = d["type"] as? String ?: return@mapNotNull null,
                        amount = (d["amount"] as? Long)?.toInt() ?: 0,
                        reason = d["reason"] as? String ?: "",
                        challengeId = d["challengeId"] as? String,
                        timestamp = d["timestamp"] as? Long ?: 0L
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse point transaction document ${doc.id}")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch point transactions for uid=$userId")
            emptyList()
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
        "consciousOpens" to consciousOpens,
        "overlayPausedMs" to overlayPausedMs,
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
