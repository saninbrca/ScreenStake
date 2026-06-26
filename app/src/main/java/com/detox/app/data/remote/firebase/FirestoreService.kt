package com.detox.app.data.remote.firebase

import com.detox.app.domain.model.BlockingType
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.PartialBlockSection
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Terminal settlement snapshot of a challenge doc (server source of truth).
 * "Settled" = [payoutStatus] is non-null OR [status] is anything other than "active".
 */
data class ChallengeSettlement(
    val status: String?,
    val payoutStatus: String?,
) {
    /** True once the server has recorded a payout outcome or moved the doc off "active". */
    val isSettled: Boolean
        get() = payoutStatus != null || (status != null && status != "active")
}

/**
 * Handles all Firestore read/write operations.
 * All writes are fire-and-forget — callers should invoke these inside a background scope
 * so failures never block the local Room operations.
 */
@Singleton
class FirestoreService @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val cloudFunctionsService: CloudFunctionsService
) {

    // ── User document ──────────────────────────────────────────────────────────

    /**
     * Creates (or merges) the top-level user document at users/{userId}.
     * Called immediately after registration or first Google Sign-In.
     * Uses merge so it is safe to call again for returning users.
     */
    suspend fun createUserDocument(
        userId: String,
        email: String,
        displayName: String? = null,
        consentAGB: Boolean = false,
        consentDatenschutz: Boolean = false,
        consentAge18: Boolean = false
    ) {
        try {
            val data = mutableMapOf<String, Any>(
                "email" to email,
                "createdAt" to com.google.firebase.Timestamp.now()
            )
            displayName?.let { data["displayName"] = it }
            // Legal proof of consent — only written when explicitly granted at registration.
            if (consentAGB || consentDatenschutz || consentAge18) {
                data["consentAGB"] = consentAGB
                data["consentDatenschutz"] = consentDatenschutz
                data["consentAge18"] = consentAge18
                data["consentTimestamp"] = com.google.firebase.Timestamp.now()
            }
            firestore
                .collection("users").document(userId)
                .set(data, com.google.firebase.firestore.SetOptions.merge())
                .await()
            Timber.d("User document created/merged for uid=$userId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to create user document for uid=$userId")
        }
    }

    // ── Account moderation (ban) ─────────────────────────────────────────────

    /**
     * Reads the `disabled` flag at users/{uid}. FAIL-OPEN: returns false on any error
     * (offline / read failure) so a network problem never locks out a legitimate user.
     * The Firebase Auth `disabled` flag (set by setUserBanStatus) is the hard backstop.
     */
    suspend fun isUserDisabled(uid: String): Boolean {
        return try {
            firestore.collection("users").document(uid).get().await()
                .getBoolean("disabled") ?: false
        } catch (e: Exception) {
            Timber.w(e, "isUserDisabled read failed — failing open (not disabled) for uid=$uid")
            false
        }
    }

    /** Reads the human-readable ban reason at users/{uid}.disabledReason, or null. */
    suspend fun getDisabledReason(uid: String): String? {
        return try {
            firestore.collection("users").document(uid).get().await()
                .getString("disabledReason")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Timber.w(e, "getDisabledReason read failed for uid=$uid")
            null
        }
    }

    // ── Username (unique handle) ─────────────────────────────────────────────

    /** Reads the permanent username stored at users/{uid}.username, or null if unset. */
    suspend fun getUsername(uid: String): String? {
        return try {
            firestore.collection("users").document(uid).get().await()
                .getString("username")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read username for uid=$uid")
            null
        }
    }

    /** Returns true if the (lowercased) username is not yet claimed in usernames/{username}. */
    suspend fun isUsernameAvailable(username: String): Boolean {
        return try {
            val key = username.lowercase()
            !firestore.collection("usernames").document(key).get().await().exists()
        } catch (e: Exception) {
            Timber.e(e, "Failed to check username availability for $username")
            // On network failure, report unavailable so the user can't proceed blindly.
            false
        }
    }

    /**
     * Atomically claims a unique username. Inside a single transaction it verifies the
     * usernames/{username} doc does not exist, then writes both the claim doc and mirrors
     * the value onto users/{uid}.username + .displayName.
     * Fails with message "username_taken" if the handle is already claimed.
     */
    suspend fun saveUsername(uid: String, username: String): Result<Unit> {
        val key = username.lowercase()
        return try {
            firestore.runTransaction { txn ->
                val usernameRef = firestore.collection("usernames").document(key)
                val snapshot = txn.get(usernameRef)
                if (snapshot.exists()) {
                    throw IllegalStateException("username_taken")
                }
                // New username doc — merge not needed (created exactly once, only if it does not exist).
                txn.set(
                    usernameRef,
                    mapOf(
                        "uid" to uid,
                        "createdAt" to com.google.firebase.Timestamp.now()
                    )
                )
                val userRef = firestore.collection("users").document(uid)
                txn.set(
                    userRef,
                    mapOf("username" to key, "displayName" to key),
                    com.google.firebase.firestore.SetOptions.merge()
                )
                null
            }.await()
            Timber.d("Username '$key' claimed for uid=$uid")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to claim username '$key' for uid=$uid")
            Result.failure(e)
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

    /**
     * Persists the challenge doc. This is a full CREATE on a fresh unified cid (createPaymentIntent
     * no longer pre-writes the doc, so the create lands with ALL fields — Firestore rules only
     * restrict fields on an UPDATE). SetOptions.merge() is a belt-and-suspenders guard so a future
     * re-sync can never wipe CF-owned fields.
     *
     * MONEY-CRITICAL: exceptions are intentionally NOT swallowed — they propagate so the Hard Mode
     * caller (ChallengeRepositoryImpl.createChallenge) can retry and surface failure. A silently
     * failed mirror previously left Room healthy while Firestore stayed gutted, which broke
     * server-side win validation.
     */
    suspend fun saveChallenge(userId: String, challenge: Challenge) {
        firestore
            .collection("users").document(userId)
            .collection("challenges").document(challenge.id)
            .set(challenge.toMap(), com.google.firebase.firestore.SetOptions.merge())
            .await()
        Timber.d("Synced challenge ${challenge.id} to Firestore")
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

    /**
     * Deletes the challenge document from Firestore.
     * Called when a Solo challenge is marked failed/cancelled so it cannot reappear
     * on the next sync (fetchActiveChallenges filters status="active"; if the document
     * still exists with status="active" it would be re-inserted into Room on restart).
     * Firestore rules block client status updates but allow the owner to delete.
     */
    suspend fun deleteChallenge(userId: String, challengeId: String) {
        try {
            firestore
                .collection("users").document(userId)
                .collection("challenges").document(challengeId)
                .delete()
                .await()
            Timber.d("FirestoreService: deleted challenge $challengeId for uid=$userId")
        } catch (e: Exception) {
            Timber.e(e, "FirestoreService: failed to delete challenge $challengeId")
        }
    }

    /**
     * Marks a challenge FAILED server-side via the markChallengeFailed Cloud Function. Unlike
     * [deleteChallenge], the doc and its nested dailyLogs are PRESERVED — the CF does an in-place
     * Admin-SDK `status: "failed"` write (the client cannot write `status` itself; Firestore rules
     * block it). This keeps the audit trail and the Redemption refund path (which reads the original
     * challenge's stored stake) intact for client-detected losses. The CF is idempotent and derives
     * the uid from the auth token, so no userId arg is needed. Fire-and-forget: failures are logged.
     */
    suspend fun markChallengeFailed(challengeId: String, failReason: String) {
        cloudFunctionsService.markChallengeFailed(challengeId, failReason)
            .onFailure { e -> Timber.e(e, "FirestoreService: markChallengeFailed failed for $challengeId") }
    }

    suspend fun updateChallengePayoutStatus(userId: String, challengeId: String, amountCents: Int) {
        try {
            firestore
                .collection("users").document(userId)
                .collection("challenges").document(challengeId)
                .set(
                    mapOf(
                        "payoutStatus" to "refunded",
                        "payoutAmount" to amountCents,
                        "payoutDate" to System.currentTimeMillis()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )
                .await()
            Timber.d("Payout status updated for challenge $challengeId: refunded €${amountCents / 100f}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update payout status for challenge $challengeId")
        }
    }

    /**
     * Reads ONLY the terminal settlement fields of a single challenge doc.
     *
     * Used by the sync-down / pre-settlement guard paths (syncUserData reconcile,
     * DailyEvaluationWorker, PermissionCheckWorker) to learn whether the server has already
     * settled a challenge before the client re-derives win/loss locally. Read-only — never
     * writes. Returns `null` on a missing document OR any error/offline so callers can apply the
     * fail-safe money stance (when in doubt, do NOT settle locally; leave the challenge active).
     * Huawei-safe: a plain Firestore get() works without Google Play Services.
     */
    suspend fun fetchChallengeSettlement(userId: String, challengeId: String): ChallengeSettlement? {
        return try {
            val snap = firestore
                .collection("users").document(userId)
                .collection("challenges").document(challengeId)
                .get()
                .await()
            if (!snap.exists()) return null
            ChallengeSettlement(
                status = snap.getString("status"),
                payoutStatus = snap.getString("payoutStatus"),
            )
        } catch (e: Exception) {
            Timber.w(e, "fetchChallengeSettlement failed for $challengeId — treating as unknown")
            null
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
                .set(log.toMap(), com.google.firebase.firestore.SetOptions.merge())
                .await()
            Timber.d("Synced daily log ${log.id} to Firestore")
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync daily log ${log.id}")
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
                    val primaryPkg = d["appPackageName"] as? String ?: return@mapNotNull null
                    // appPackageNames may be stored as a comma-separated string or be absent in
                    // old documents; fall back to the single appPackageName field.
                    val pkgNamesRaw = d["appPackageNames"] as? String
                    val packageNames = pkgNamesRaw
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.takeIf { it.isNotEmpty() }
                        ?: listOf(primaryPkg)
                    val domainsRaw = d["blockedDomains"] as? String
                    val domains = domainsRaw
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList()
                    Challenge(
                        id = d["id"] as? String ?: doc.id,
                        appPackageName = packageNames.firstOrNull(),
                        appPackageNames = packageNames,
                        appDisplayName = d["appDisplayName"] as? String ?: return@mapNotNull null,
                        mode = ChallengeMode.valueOf(
                            (d["mode"] as? String ?: "soft").uppercase()
                        ),
                        limitType = LimitType.valueOf(
                            (d["limitType"] as? String ?: "time").uppercase()
                        ),
                        limitValueMinutes = (d["limitValueMinutes"] as? Long)?.toInt() ?: 0,
                        limitValueSessions = (d["limitValueSessions"] as? Long)?.toInt(),
                        sessionDurationMinutes = (d["sessionDurationMinutes"] as? Long)?.toInt() ?: 5,
                        startDate = d["startDate"] as? Long ?: 0L,
                        endDate = d["endDate"] as? Long ?: 0L,
                        amountCents = (d["amountCents"] as? Long)?.toInt(),
                        stripePaymentIntentId = d["stripePaymentIntentId"] as? String,
                        customMotivation = d["customMotivation"] as? String,
                        status = ChallengeStatus.valueOf(
                            (d["status"] as? String ?: "active").uppercase()
                        ),
                        createdAt = d["createdAt"] as? Long ?: 0L,
                        dailyBudgetMinutes = (d["dailyBudgetMinutes"] as? Long)?.toInt(),
                        blockedDomains = domains,
                        partialBlockDomains = (d["partialBlockDomains"] as? String)
                            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                            ?: emptyList(),
                        blockingType = runCatching {
                            BlockingType.valueOf(
                                (d["blockingType"] as? String ?: "app").uppercase()
                            )
                        }.getOrDefault(BlockingType.APP),
                        blockAdultContent = d["blockAdultContent"] as? Boolean ?: false,
                        scheduleStartTime = d["scheduleStartTime"] as? String,
                        scheduleEndTime = d["scheduleEndTime"] as? String,
                        activeDays = (d["activeDays"] as? String)
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotBlank() }
                            ?: emptyList(),
                        partialBlockSections = (d["partialBlockSections"] as? List<*>)
                            ?.filterIsInstance<String>()
                            ?.mapNotNull { PartialBlockSection.fromId(it) }
                            ?: emptyList(),
                        isPartialBlockOnly = d["isPartialBlockOnly"] as? Boolean ?: false,
                        pendingLimitValue = (d["pendingLimitValue"] as? Long)?.toInt(),
                        pendingLimitAppliesAt = d["pendingLimitAppliesAt"] as? Long,
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
     * Fetches the user's FINISHED (completed + failed) challenges.
     *
     * ADDITIVE history-restore path — intentionally a separate method from [fetchActiveChallenges]
     * (its money-critical sibling) so it can NEVER affect the active-sync path. syncUserData()
     * resyncs only ACTIVE challenges, and the History screen reads finished challenges from Room
     * only, so without this they are lost whenever Room is cleared (logout, or the SQLCipher
     * plaintext-DB drop). The parser is duplicated (not shared) deliberately to avoid touching the
     * working active path.
     */
    suspend fun fetchFinishedChallenges(userId: String): List<Challenge> {
        return try {
            val snapshot = firestore
                .collection("users").document(userId)
                .collection("challenges")
                .whereIn("status", listOf("completed", "failed"))
                .get()
                .await()
            snapshot.documents.mapNotNull { doc ->
                val d = doc.data ?: return@mapNotNull null
                try {
                    val primaryPkg = d["appPackageName"] as? String ?: return@mapNotNull null
                    val pkgNamesRaw = d["appPackageNames"] as? String
                    val packageNames = pkgNamesRaw
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.takeIf { it.isNotEmpty() }
                        ?: listOf(primaryPkg)
                    val domainsRaw = d["blockedDomains"] as? String
                    val domains = domainsRaw
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList()
                    Challenge(
                        id = d["id"] as? String ?: doc.id,
                        appPackageName = packageNames.firstOrNull(),
                        appPackageNames = packageNames,
                        appDisplayName = d["appDisplayName"] as? String ?: return@mapNotNull null,
                        mode = ChallengeMode.valueOf((d["mode"] as? String ?: "soft").uppercase()),
                        limitType = LimitType.valueOf((d["limitType"] as? String ?: "time").uppercase()),
                        limitValueMinutes = (d["limitValueMinutes"] as? Long)?.toInt() ?: 0,
                        limitValueSessions = (d["limitValueSessions"] as? Long)?.toInt(),
                        sessionDurationMinutes = (d["sessionDurationMinutes"] as? Long)?.toInt() ?: 5,
                        startDate = d["startDate"] as? Long ?: 0L,
                        endDate = d["endDate"] as? Long ?: 0L,
                        amountCents = (d["amountCents"] as? Long)?.toInt(),
                        stripePaymentIntentId = d["stripePaymentIntentId"] as? String,
                        customMotivation = d["customMotivation"] as? String,
                        status = ChallengeStatus.valueOf((d["status"] as? String ?: "active").uppercase()),
                        createdAt = d["createdAt"] as? Long ?: 0L,
                        dailyBudgetMinutes = (d["dailyBudgetMinutes"] as? Long)?.toInt(),
                        blockedDomains = domains,
                        partialBlockDomains = (d["partialBlockDomains"] as? String)
                            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                            ?: emptyList(),
                        blockingType = runCatching {
                            BlockingType.valueOf((d["blockingType"] as? String ?: "app").uppercase())
                        }.getOrDefault(BlockingType.APP),
                        blockAdultContent = d["blockAdultContent"] as? Boolean ?: false,
                        scheduleStartTime = d["scheduleStartTime"] as? String,
                        scheduleEndTime = d["scheduleEndTime"] as? String,
                        activeDays = (d["activeDays"] as? String)
                            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                            ?: emptyList(),
                        partialBlockSections = (d["partialBlockSections"] as? List<*>)
                            ?.filterIsInstance<String>()
                            ?.mapNotNull { PartialBlockSection.fromId(it) }
                            ?: emptyList(),
                        isPartialBlockOnly = d["isPartialBlockOnly"] as? Boolean ?: false,
                        pendingLimitValue = (d["pendingLimitValue"] as? Long)?.toInt(),
                        pendingLimitAppliesAt = d["pendingLimitAppliesAt"] as? Long,
                        failReason = d["failReason"] as? String,
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse finished challenge document ${doc.id}")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch finished challenges for uid=$userId")
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
     * Merges only the consciousOpens field into the flat dailyLogs collection at
     * users/{userId}/dailyLogs/{challengeId}_{date}. Used for cross-reinstall persistence
     * of conscious-open counts so the overlay session limit survives app reinstalls.
     */
    suspend fun updateDailyLogConsciousOpens(
        userId: String,
        challengeId: String,
        date: Long,
        consciousOpens: Int
    ) {
        try {
            firestore
                .collection("users").document(userId)
                .collection("dailyLogs").document("${challengeId}_${date}")
                .set(
                    mapOf(
                        "challengeId" to challengeId,
                        "date" to date,
                        "consciousOpens" to consciousOpens,
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )
                .await()
            Timber.d("DailyLog: consciousOpens=$consciousOpens synced to Firestore for $challengeId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync consciousOpens to Firestore for $challengeId")
        }
    }

    /**
     * Merges budgetUsedMs and budgetRemainingMs into the flat dailyLogs collection at
     * users/{userId}/dailyLogs/{challengeId}_{date}. Called on every 10-second budget tick.
     * Uses SetOptions.merge() so it never overwrites consciousOpens or other fields.
     * Callers must invoke this inside a background scope — do NOT await on the main path.
     */
    suspend fun updateDailyLogBudget(
        userId: String,
        challengeId: String,
        date: Long,
        usedMs: Long,
        remainingMs: Long
    ) {
        try {
            firestore
                .collection("users").document(userId)
                .collection("dailyLogs").document("${challengeId}_${date}")
                .set(
                    mapOf(
                        "challengeId" to challengeId,
                        "date" to date,
                        "budgetUsedMs" to usedMs,
                        "budgetRemainingMs" to remainingMs,
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )
                .await()
            Timber.d("DailyLog: budgetUsedMs=$usedMs synced to Firestore for $challengeId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync budget to Firestore for $challengeId")
        }
    }

    /**
     * Fetches flat dailyLog entries written by [updateDailyLogConsciousOpens] for today,
     * keyed by {challengeId}_{date}. Used by [SyncRepositoryImpl] on startup to restore
     * consciousOpens into Room after a reinstall.
     */
    suspend fun fetchTodayDailyLogs(userId: String, startOfToday: Long): List<Map<String, Any>> {
        return try {
            val snapshot = firestore
                .collection("users").document(userId)
                .collection("dailyLogs")
                .whereGreaterThanOrEqualTo("date", startOfToday)
                .get()
                .await()
            snapshot.documents.mapNotNull { it.data }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch today's dailyLogs from Firestore for uid=$userId")
            emptyList()
        }
    }

    /**
     * Reads a single flat dailyLog document for the given challenge and date.
     * Returns null on network failure or if the document does not exist.
     * Used by SyncRepositoryImpl to restore Group Challenge budget on app start.
     */
    suspend fun fetchDailyLogDocument(
        userId: String,
        challengeId: String,
        date: Long
    ): Map<String, Any>? {
        return try {
            firestore
                .collection("users").document(userId)
                .collection("dailyLogs").document("${challengeId}_${date}")
                .get()
                .await()
                .data
        } catch (e: Exception) {
            Timber.e(e, "FirestoreService: fetchDailyLogDocument failed for $challengeId")
            null
        }
    }

    // ── Redemption Challenge ───────────────────────────────────────────────────

    suspend fun updateChallengeRedemptionInfo(
        userId: String,
        challengeId: String,
        eligible: Boolean,
        deadline: Long,
        showAfter: Long,
        refundAmount: Int,
        redemptionDays: Int,
        redemptionLimit: Int
    ) {
        try {
            firestore
                .collection("users").document(userId)
                .collection("challenges").document(challengeId)
                .set(
                    mapOf(
                        "redemptionEligible" to eligible,
                        "redemptionDeadline" to deadline,
                        "redemptionShowAfter" to showAfter,
                        "redemptionRefundAmount" to refundAmount,
                        "redemptionDays" to redemptionDays,
                        "redemptionLimit" to redemptionLimit
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )
                .await()
            Timber.d("Redemption info updated for challenge $challengeId: eligible=$eligible")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update redemption info for challenge $challengeId")
        }
    }

    suspend fun updateChallengeRedemptionChallengeId(
        userId: String,
        challengeId: String,
        redemptionChallengeId: String
    ) {
        try {
            firestore
                .collection("users").document(userId)
                .collection("challenges").document(challengeId)
                .set(
                    mapOf("redemptionChallengeId" to redemptionChallengeId),
                    com.google.firebase.firestore.SetOptions.merge()
                )
                .await()
            Timber.d("redemptionChallengeId=$redemptionChallengeId set on challenge $challengeId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update redemptionChallengeId for $challengeId")
        }
    }

    // ── Account deletion ──────────────────────────────────────────────────────

    /**
     * Deletes all Firestore data for a user: iterates every challenge document,
     * deletes all nested dailyLogs sub-documents, then the challenge itself,
     * and finally the top-level user document.
     *
     * This is a best-effort client-side deletion. For a production app, prefer
     * a Cloud Function with admin SDK for atomicity; this covers the common case.
     */
    suspend fun deleteUserData(userId: String) {
        try {
            val challengesRef = firestore.collection("users").document(userId)
                .collection("challenges")
            val challenges = challengesRef.get().await()
            for (challengeDoc in challenges.documents) {
                try {
                    // Nested dailyLogs are cleared server-side by the onChallengeDeleted
                    // Cloud Function trigger (client deletes are blocked by Firestore rules).
                    challengeDoc.reference.delete().await()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to delete challenge ${challengeDoc.id} for uid=$userId")
                }
            }
            firestore.collection("users").document(userId).delete().await()
            Timber.d("Deleted all Firestore data for uid=$userId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete Firestore data for uid=$userId")
            throw e
        }
    }

    suspend fun updateChallengePendingLimit(
        userId: String, challengeId: String, pendingValue: Int, appliesAt: Long
    ) {
        try {
            firestore.collection("users").document(userId)
                .collection("challenges").document(challengeId)
                .set(
                    mapOf(
                        "pendingLimitValue" to pendingValue,
                        "pendingLimitAppliesAt" to appliesAt
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )
                .await()
            Timber.d("updateChallengePendingLimit: challengeId=$challengeId pendingValue=$pendingValue appliesAt=$appliesAt")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update pending limit for challengeId=$challengeId")
            throw e
        }
    }

    // ── Mapping helpers ────────────────────────────────────────────────────────

    private fun Challenge.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "appPackageName" to (appPackageName ?: ""),
        "appPackageNames" to appPackageNames.joinToString(",").ifEmpty { null },
        "appDisplayName" to appDisplayName,
        "mode" to mode.name.lowercase(),
        "limitType" to limitType.name.lowercase(),
        "limitValueMinutes" to limitValueMinutes,
        "limitValueSessions" to limitValueSessions,
        "sessionDurationMinutes" to sessionDurationMinutes,
        "startDate" to startDate,
        "endDate" to endDate,
        "amountCents" to amountCents,
        "stripePaymentIntentId" to stripePaymentIntentId,
        "customMotivation" to customMotivation,
        "status" to status.name.lowercase(),
        "createdAt" to createdAt,
        "dailyBudgetMinutes" to dailyBudgetMinutes,
        "blockedDomains" to blockedDomains.joinToString(",").ifEmpty { null },
        "partialBlockDomains" to partialBlockDomains.joinToString(",").ifEmpty { null },
        "blockingType" to blockingType.name.lowercase(),
        "blockAdultContent" to blockAdultContent,
        "scheduleStartTime" to scheduleStartTime,
        "scheduleEndTime" to scheduleEndTime,
        "activeDays" to activeDays.joinToString(",").ifEmpty { null },
        "partialBlockSections" to partialBlockSections.map { it.id },
        "isPartialBlockOnly" to isPartialBlockOnly,
        "isRedemption" to isRedemption,
        "originalChallengeId" to originalChallengeId,
        "originalPaymentIntentId" to originalPaymentIntentId,
        "refundAmountCents" to refundAmountCents,
        "pendingLimitValue" to pendingLimitValue,
        "pendingLimitAppliesAt" to pendingLimitAppliesAt,
        // Anti-cheat metadata — only populated for Hard Mode creation (null otherwise).
        "deviceId" to deviceId,
        "isRooted" to isRooted,
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
        "budgetUsedMinutes" to budgetUsedMinutes,
        "budgetRemainingMinutes" to budgetRemainingMinutes,
        "pointsEarned" to pointsEarned,
        "limitExceeded" to limitExceeded,
        "moneyLostCents" to moneyLostCents
    )

}
