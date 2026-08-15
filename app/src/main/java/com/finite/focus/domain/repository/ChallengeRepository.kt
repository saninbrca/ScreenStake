package com.finite.focus.domain.repository

import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.ChallengeStatus
import kotlinx.coroutines.flow.Flow

interface ChallengeRepository {
    suspend fun createChallenge(challenge: Challenge): Result<Unit>
    suspend fun getChallengeById(id: String): Result<Challenge?>
    fun getActiveChallenges(): Flow<List<Challenge>>
    suspend fun getActiveChallengesList(): Result<List<Challenge>>
    suspend fun getActiveChallengeForApp(packageName: String): Result<Challenge?>
    /**
     * Updates the challenge status. For [ChallengeStatus.FAILED], [failReason] records the loss cause
     * (UX only): "limit_exceeded" | "abandon" | "permission_violation". It is written to the Room
     * `failReason` column and passed to the `markChallengeFailed` CF. Ignored for non-FAILED statuses.
     *
     * Firestore mirroring depends on the transition, because the client can never write `status`
     * itself (rules block it) — a Cloud Function does it with Admin rights:
     *  - FAILED → `markChallengeFailed` CF (all modes; unchanged).
     *  - COMPLETED / [ChallengeStatus.ENDED_UNVERIFIED] on a money-free SOFT SOLO row →
     *    `markChallengeSettled` CF. Without this the doc stays `active` forever and every reinstall
     *    re-pulls and re-celebrates it.
     *  - Everything else (notably HARD) → the pre-existing best-effort direct write, untouched.
     */
    suspend fun updateChallengeStatus(
        id: String,
        status: ChallengeStatus,
        failReason: String? = null
    ): Result<Unit>
    /**
     * Marks a GROUP shadow row [ChallengeStatus.ENDED] — LOCAL ONLY, money-free.
     *
     * Deliberately NOT [updateChallengeStatus]: that one fires a Firestore write, and for a group
     * shadow row (`id = "group_<groupId>"`) that would materialise a `users/{uid}/challenges/group_*`
     * doc which is supposed to never exist (see `PermissionCheckWorker.isSoloHardPermissionFailEligible`
     * for what materialising it breaks). This writes the Room `status` column and nothing else — no
     * Stripe, no Cloud Function, no delete, no `failReason`.
     */
    suspend fun endGroupChallengeLocally(id: String): Result<Unit>

    /** Returns all challenges (active + completed + failed + ended) ordered by createdAt DESC. */
    fun getAllChallenges(): Flow<List<Challenge>>
    /** Marks the congratulations overlay as shown so it won't appear again. */
    suspend fun markCompletionShown(id: String): Result<Unit>
    /**
     * ALL solo challenges in a terminal state whose result surface has not been shown yet, ordered
     * oldest-first. Backs the Dashboard's result queue — see
     * [com.finite.focus.data.local.db.dao.ChallengeDao.getUnshownTerminalChallenges] for the
     * selection and ordering rules.
     */
    suspend fun getUnshownTerminalChallenges(): Result<List<Challenge>>
    /** Writes pendingLimitValue + pendingLimitAppliesAt to Firestore first, then Room. */
    suspend fun updatePendingLimit(challengeId: String, pendingValue: Int, appliesAt: Long): Result<Unit>
}
