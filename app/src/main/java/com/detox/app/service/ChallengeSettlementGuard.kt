package com.detox.app.service

import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of the pre-settlement server check. */
enum class SettlementDecision {
    /** Server doc is still active & unsettled — the worker may settle locally exactly as before. */
    PROCEED,

    /**
     * Do NOT settle locally. Either the server already settled this challenge (its terminal status
     * has been pulled into Room) or the server state could not be confirmed (offline/error/no uid),
     * in which case the challenge is left active for the next cycle — the server reconciliation net
     * is the backstop. Either way the worker must NOT capture/refund.
     */
    SKIP,
}

/**
 * Money-safety gate that MUST precede every client-side capturePayment()/refund in the workers
 * (DailyEvaluationWorker, PermissionCheckWorker). It re-reads the challenge's server doc once and
 * decides whether the worker may re-derive win/loss locally.
 *
 * Branch order (fail-safe money stance — never settle on a read we couldn't confirm):
 *  - server settled (payoutStatus set OR status != "active") → pull terminal status into Room
 *    (DAO-only, single column) and return [SettlementDecision.SKIP].
 *  - server check failed / offline / no uid → return [SettlementDecision.SKIP] (leave active).
 *  - server still active & unsettled → return [SettlementDecision.PROCEED].
 *
 * CRITICAL: Room status is written via [ChallengeDao.updateStatus] DIRECTLY — NEVER the repo
 * wrapper (ChallengeRepositoryImpl.updateChallengeStatus), which on FAILED calls the
 * markChallengeFailed CF (writes status:"failed" + failReason:"client_loss" in-place; doc +
 * dailyLogs retained) and would clobber the server's just-written payoutStatus/payout record. Only
 * the `status` column is touched; live-tracking fields (opens/time/dailyLogs) are never overwritten.
 */
@Singleton
class ChallengeSettlementGuard @Inject constructor(
    private val firestoreService: FirestoreService,
    private val firebaseAuthService: FirebaseAuthService,
    private val challengeDao: ChallengeDao,
) {
    suspend fun resolveBeforeLocalSettlement(challengeId: String): SettlementDecision {
        val uid = firebaseAuthService.currentUserId()
        if (uid == null) {
            Timber.w("SettlementGuard: no uid — leaving %s active (fail-safe)", challengeId)
            return SettlementDecision.SKIP
        }
        val settlement = firestoreService.fetchChallengeSettlement(uid, challengeId)
        if (settlement == null) {
            // Read failed / offline / doc missing — fail-safe: do NOT settle locally.
            Timber.w("SettlementGuard: server state unconfirmed for %s — leaving active (fail-safe)", challengeId)
            return SettlementDecision.SKIP
        }
        if (settlement.isSettled) {
            // Derive the terminal Room status. Prefer the server's own status field when it is
            // already terminal; otherwise infer from payoutStatus (refunded → completed, any other
            // payout outcome → failed). Room-only single-column write.
            val terminalStatus = when {
                settlement.status != null && settlement.status != "active" -> settlement.status
                settlement.payoutStatus == "refunded" -> "completed"
                settlement.payoutStatus != null -> "failed"
                else -> null
            }
            if (terminalStatus != null) {
                challengeDao.updateStatus(challengeId, terminalStatus)
                Timber.i(
                    "SettlementGuard: server already settled %s (status=%s payout=%s) → Room=%s, skipping local settlement",
                    challengeId, settlement.status, settlement.payoutStatus, terminalStatus
                )
            }
            return SettlementDecision.SKIP
        }
        return SettlementDecision.PROCEED
    }
}
