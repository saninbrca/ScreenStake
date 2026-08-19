package com.finite.focus.data.repository

import com.finite.focus.data.local.db.dao.ChallengeDao
import com.finite.focus.data.local.db.dao.markTerminal
import com.finite.focus.data.local.db.entity.ChallengeEntity
import com.finite.focus.data.remote.firebase.FirebaseAuthService
import com.finite.focus.data.remote.firebase.FirestoreService
import com.finite.focus.di.ApplicationScope
import com.finite.focus.domain.model.BlockingType
import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.model.LimitType
import com.finite.focus.domain.model.PartialBlockSection
import com.finite.focus.domain.repository.ChallengeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChallengeRepositoryImpl @Inject constructor(
    private val challengeDao: ChallengeDao,
    private val firestoreService: FirestoreService,
    private val firebaseAuthService: FirebaseAuthService,
    @ApplicationScope private val appScope: CoroutineScope
) : ChallengeRepository {

    private companion object {
        /** Bounded retry for the awaited Hard Mode Firestore mirror create. */
        const val HARD_SYNC_MAX_ATTEMPTS = 3
        const val HARD_SYNC_RETRY_BASE_MS = 500L
    }

    override suspend fun createChallenge(challenge: Challenge): Result<Unit> {
        return try {
            challengeDao.insertChallenge(challenge.toEntity())
            if (challenge.mode == ChallengeMode.HARD) {
                // MONEY-CRITICAL: the Firestore challenge doc is the server's source of truth for
                // win/refund validation (cancelOrRefundPayment re-reads it). It MUST land completely
                // before we report success — a missing/gutted doc means the server can never confirm
                // a win and the winner is never refunded. So AWAIT the sync (not fire-and-forget),
                // retry transient failures, and propagate failure so the ViewModel can surface it.
                firebaseAuthService.logAuthState("ChallengeRepo.createChallenge")
                val uid = firebaseAuthService.currentUserId()
                    ?: return Result.failure(IllegalStateException("Nicht authentifiziert"))
                var lastError: Exception? = null
                repeat(HARD_SYNC_MAX_ATTEMPTS) { attempt ->
                    try {
                        Timber.d("createChallenge: syncing Hard Mode challenge %s for uid=%s (attempt %d)", challenge.id, uid, attempt + 1)
                        firestoreService.saveChallenge(uid, challenge)
                        return Result.success(Unit)
                    } catch (e: Exception) {
                        lastError = e
                        Timber.w(e, "createChallenge: Hard Mode Firestore sync attempt %d failed", attempt + 1)
                        if (attempt < HARD_SYNC_MAX_ATTEMPTS - 1) delay(HARD_SYNC_RETRY_BASE_MS * (attempt + 1))
                    }
                }
                // TODO(reconciliation): a persistent failure here leaves Room populated but Firestore
                // missing. A follow-up Room→Firestore up-sync should re-create the doc on next launch
                // so a transient outage self-heals instead of stranding an auto-captured stake.
                Result.failure(lastError ?: IllegalStateException("Firestore sync failed"))
            } else {
                // Soft Mode: no createPaymentIntent pre-write, no withdrawal-waiver write, and no
                // server-side money authority — so there is no doc race and fire-and-forget is fine.
                appScope.launch {
                    firebaseAuthService.logAuthState("ChallengeRepo.createChallenge")
                    val uid = firebaseAuthService.currentUserId()
                    if (uid == null) {
                        Timber.w("createChallenge: skipping Firestore sync — user not signed in")
                    } else {
                        Timber.d("createChallenge: syncing challenge %s for uid=%s", challenge.id, uid)
                        try {
                            firestoreService.saveChallenge(uid, challenge)
                        } catch (e: Exception) {
                            Timber.e(e, "createChallenge: soft-mode Firestore sync failed (non-fatal)")
                        }
                    }
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getChallengeById(id: String): Result<Challenge?> {
        return try {
            Result.success(challengeDao.getChallengeById(id)?.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getActiveChallenges(): Flow<List<Challenge>> {
        return challengeDao.getActiveChallenges().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getActiveChallengesList(): Result<List<Challenge>> {
        return try {
            Result.success(challengeDao.getActiveChallengesList().map { it.toDomain() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getActiveChallengeForApp(packageName: String): Result<Challenge?> {
        return try {
            Result.success(challengeDao.getActiveChallengeForApp(packageName)?.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getAllChallenges(): Flow<List<Challenge>> {
        return challengeDao.getAllChallenges().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun markCompletionShown(id: String): Result<Unit> {
        return try {
            challengeDao.markCompletionShown(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updatePendingLimit(
        challengeId: String, pendingValue: Int, appliesAt: Long
    ): Result<Unit> = runCatching {
        val uid = firebaseAuthService.currentUserId() ?: error("Nicht authentifiziert")
        // Firestore FIRST (source of truth — survives reinstall)
        firestoreService.updateChallengePendingLimit(uid, challengeId, pendingValue, appliesAt)
        // Then Room
        challengeDao.updatePendingLimit(challengeId, pendingValue, appliesAt)
        Timber.d("updatePendingLimit: challengeId=$challengeId pendingValue=$pendingValue")
    }

    override suspend fun getUnshownTerminalChallenges(): Result<List<Challenge>> {
        return try {
            Result.success(challengeDao.getUnshownTerminalChallenges().map { it.toDomain() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateChallengeStatus(
        id: String,
        status: ChallengeStatus,
        failReason: String?
    ): Result<Unit> {
        return try {
            val statusStr = status.name.lowercase()
            // Terminal outcomes also stamp endedAt (once — COALESCE in the query keeps the first
            // date, so a re-settle never moves it). ACTIVE would be a caller bug, but route it
            // through the plain status write rather than dating a challenge that has not ended.
            if (status.isTerminal) {
                challengeDao.markTerminal(id, statusStr)
            } else {
                challengeDao.updateStatus(id, statusStr)
            }
            // For FAILED: persist the loss cause locally (UX only) so the loss dialog can show it
            // immediately, even before the Firestore round-trip. Falls back to "client_loss" for
            // callers that don't classify the cause.
            val effectiveReason = if (status == ChallengeStatus.FAILED) {
                failReason ?: "client_loss"
            } else null
            if (status == ChallengeStatus.FAILED) {
                challengeDao.updateFailReason(id, effectiveReason)
            }
            // Fire-and-forget Firestore sync.
            // For FAILED: call the markChallengeFailed Cloud Function, which writes status="failed"
            // (and failReason) in place via the Admin SDK (the client cannot write `status` itself —
            // Firestore rules block it). The doc and its dailyLogs are PRESERVED (not deleted),
            // keeping the audit trail and the Redemption refund path intact; the CF is idempotent on
            // already-terminal docs. fetchFinishedChallenges + sync Guard B restore it to Room on the
            // next sync. For other statuses: best-effort status update (may be blocked by rules).
            appScope.launch {
                firebaseAuthService.logAuthState("ChallengeRepo.updateChallengeStatus")
                val uid = firebaseAuthService.currentUserId()
                if (uid == null) {
                    Timber.w("updateChallengeStatus: skipping Firestore sync — user not signed in")
                } else if (status == ChallengeStatus.FAILED) {
                    Timber.d("updateChallengeStatus: marking Firestore doc FAILED for %s (status=%s reason=%s)", id, statusStr, effectiveReason)
                    firestoreService.markChallengeFailed(id, effectiveReason ?: "client_loss")
                } else if (isMoneyFreeSoftSettlement(id, status)) {
                    // SOFT SOLO terminal outcome. The direct write below is silently rejected by the
                    // Firestore rules (`status` is in the forbidden-key set), which is why these docs
                    // stayed "active" forever and were re-celebrated on every reinstall. Route through
                    // the Admin-SDK CF instead. Soft-only and money-free — Hard keeps the old path.
                    Timber.d("updateChallengeStatus: marking Firestore doc SETTLED for %s (status=%s)", id, statusStr)
                    firestoreService.markChallengeSettled(id, statusStr)
                } else {
                    Timber.d("updateChallengeStatus: challenge=%s status=%s uid=%s", id, statusStr, uid)
                    firestoreService.updateChallengeStatus(uid, id, statusStr)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * True when [status] is a terminal outcome the `markChallengeSettled` CF is allowed to persist
     * for [id]: COMPLETED or ENDED_UNVERIFIED on a SOFT SOLO row carrying no stake.
     *
     * Re-reads the row from Room rather than trusting the caller, and every uncertainty answers
     * `false` (fall through to the pre-existing direct write): an unreadable row, a missing row, a
     * Hard row, a group shadow row, or a row with a PaymentIntent. The CF re-derives the same fence
     * server-side, so this is the first of two independent checks, not the only one.
     *
     * Hard Mode is deliberately excluded: its terminal status is owned by the refund/capture paths
     * and the server reconciliation, and nothing here may pre-empt them.
     */
    private suspend fun isMoneyFreeSoftSettlement(id: String, status: ChallengeStatus): Boolean {
        if (status != ChallengeStatus.COMPLETED && status != ChallengeStatus.ENDED_UNVERIFIED) return false
        val entity = runCatching { challengeDao.getChallengeById(id) }.getOrNull() ?: return false
        return entity.mode.equals("soft", ignoreCase = true) &&
                entity.stripePaymentIntentId == null &&
                entity.groupChallengeId.isNullOrBlank()
    }

    override suspend fun endGroupChallengeLocally(id: String): Result<Unit> {
        return try {
            // Room status column (+ endedAt) ONLY — no Firestore write, no CF, no Stripe, no
            // delete. See the interface doc for why this must never go through
            // updateChallengeStatus.
            //
            // This is where a group challenge that ran to term gets its date, and it is the RIGHT
            // moment for it: the row goes ENDED the day after the last day, and the stamp clamps
            // back to endDate so it reads as the final day rather than the sweep's day. When
            // settlement later lands, finishLocalGroupChallenge keeps this date instead of
            // recording whenever the device happened to come back online.
            challengeDao.markTerminal(id, ChallengeStatus.ENDED.name.lowercase())
            Timber.i("endGroupChallengeLocally: %s → ended (enforcement stopped, settlement untouched)", id)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "endGroupChallengeLocally: failed for %s", id)
            Result.failure(e)
        }
    }

    private fun ChallengeEntity.toDomain(): Challenge {
        val type = runCatching { BlockingType.valueOf(blockingType.uppercase()) }
            .getOrDefault(BlockingType.APP)
        val packageNames = appPackageNames
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: if (appPackageName.isNotBlank()) listOf(appPackageName) else emptyList()
        val domains = blockedDomains
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val partialDomains = partialBlockDomains
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return Challenge(
            id = id,
            appPackageName = packageNames.firstOrNull(),
            appPackageNames = packageNames,
            appDisplayName = appDisplayName,
            mode = ChallengeMode.valueOf(mode.uppercase()),
            limitType = LimitType.valueOf(limitType.uppercase()),
            limitValueMinutes = limitValueMinutes,
            limitValueSessions = limitValueSessions,
            startDate = startDate,
            endDate = endDate,
            amountCents = amountCents,
            stripePaymentIntentId = stripePaymentIntentId,
            customMotivation = customMotivation,
            status = ChallengeStatus.valueOf(status.uppercase()),
            createdAt = createdAt,
            dailyBudgetMinutes = dailyBudgetMinutes,
            blockedDomains = domains,
            partialBlockDomains = partialDomains,
            blockingType = type,
            blockAdultContent = blockAdultContent != 0,
            scheduleStartTime = scheduleStartTime,
            scheduleEndTime = scheduleEndTime,
            activeDays = activeDays
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList(),
            completionShown = completionShown != 0,
            sessionDurationMinutes = sessionDurationMinutes,
            groupChallengeId = groupChallengeId,
            partialBlockSections = partialBlockSections
                .split(",")
                .filter { it.isNotBlank() }
                .mapNotNull { PartialBlockSection.fromId(it.trim()) },
            isPartialBlockOnly = isPartialBlockOnly != 0,
            redemptionEligible = redemptionEligible != 0,
            redemptionDeadline = redemptionDeadline,
            redemptionShowAfter = redemptionShowAfter,
            redemptionChallengeId = redemptionChallengeId,
            redemptionRefundAmount = redemptionRefundAmount,
            redemptionDays = redemptionDays,
            redemptionLimit = redemptionLimit,
            isRedemption = isRedemption != 0,
            originalChallengeId = originalChallengeId,
            originalPaymentIntentId = originalPaymentIntentId,
            refundAmountCents = refundAmountCents,
            pendingLimitValue = pendingLimitValue,
            pendingLimitAppliesAt = pendingLimitAppliesAt,
            failReason = failReason,
            endedAt = endedAt,
        )
    }

    private fun Challenge.toEntity(): ChallengeEntity = ChallengeEntity(
        id = id,
        appPackageName = appPackageNames.firstOrNull() ?: "",
        appDisplayName = appDisplayName,
        mode = mode.name.lowercase(),
        limitType = limitType.name.lowercase(),
        limitValueMinutes = limitValueMinutes,
        limitValueSessions = limitValueSessions,
        startDate = startDate,
        endDate = endDate,
        amountCents = amountCents,
        stripePaymentIntentId = stripePaymentIntentId,
        customMotivation = customMotivation,
        status = status.name.lowercase(),
        createdAt = createdAt,
        dailyBudgetMinutes = dailyBudgetMinutes,
        appPackageNames = appPackageNames.joinToString(",").ifEmpty { null },
        blockedDomains = blockedDomains.joinToString(",").ifEmpty { null },
        partialBlockDomains = partialBlockDomains.joinToString(",").ifEmpty { null },
        blockingType = blockingType.name.lowercase(),
        blockAdultContent = if (blockAdultContent) 1 else 0,
        scheduleStartTime = scheduleStartTime,
        scheduleEndTime = scheduleEndTime,
        activeDays = activeDays.joinToString(",").ifEmpty { null },
        completionShown = if (completionShown) 1 else 0,
        sessionDurationMinutes = sessionDurationMinutes,
        groupChallengeId = groupChallengeId,
        partialBlockSections = partialBlockSections.joinToString(",") { it.id },
        isPartialBlockOnly = if (isPartialBlockOnly) 1 else 0,
        redemptionEligible = if (redemptionEligible) 1 else 0,
        redemptionDeadline = redemptionDeadline,
        redemptionShowAfter = redemptionShowAfter,
        redemptionChallengeId = redemptionChallengeId,
        redemptionRefundAmount = redemptionRefundAmount,
        redemptionDays = redemptionDays,
        redemptionLimit = redemptionLimit,
        isRedemption = if (isRedemption) 1 else 0,
        originalChallengeId = originalChallengeId,
        originalPaymentIntentId = originalPaymentIntentId,
        refundAmountCents = refundAmountCents,
        pendingLimitValue = pendingLimitValue,
        pendingLimitAppliesAt = pendingLimitAppliesAt,
        failReason = failReason,
    )
}
