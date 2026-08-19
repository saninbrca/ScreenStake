package com.finite.focus.domain.model

enum class ChallengeStatus {
    ACTIVE,
    COMPLETED,
    FAILED,

    /**
     * LOCAL-ONLY, MONEY-FREE terminal state for a **group challenge shadow row** whose end date has
     * passed on-device while settlement has not landed yet (typically: no connectivity).
     *
     * What it does: takes the row out of `status = 'active'`, which is the single switch every
     * enforcement path reads (`getActiveChallengeForApp` / `getActiveChallengesList`) — so the
     * overlay stops, tracking stops, and the monitored app is usable, all with no network.
     *
     * What it explicitly does NOT do: it settles, captures, refunds and deletes NOTHING. The buy-in
     * and the server's settlement obligation are untouched. Settlement still runs from its own
     * triggers, which key on the `group_challenges` table (`MainActivity.checkExpiredGroupChallenges`
     * / `PermissionCheckWorker.checkExpiredGroupChallenges`), NOT on this row — so ending enforcement
     * here can never strand a payout. Once settlement lands, the normal group finish path overwrites
     * this with COMPLETED or FAILED.
     *
     * Never written to Firestore (group shadow rows are local-only) and never set on a solo
     * challenge.
     */
    ENDED,

    /**
     * MONEY-FREE terminal state for a **SOFT SOLO** challenge whose active window this install
     * could never have observed: it was synced down for the first time with an end date that had
     * already passed *before the app was installed* (see `isUnobservedSoftChallenge`).
     *
     * Why it is NOT [COMPLETED]: the win/loss verdict is derived from `DailyLog.limitExceeded`
     * rows, and an uninstall destroys Room. A reinstall therefore re-pulls the still-`active`
     * Firestore doc onto an empty log set, and the settlement verdict fail-opens to "clean" —
     * celebrating a win the app never actually observed, on a challenge that may well have been
     * breached. This status is the honest answer: the challenge is over, and we do not know how
     * it went.
     *
     * Deliberately NOT keyed on log emptiness. A disciplined user legitimately produces few or no
     * log rows (a zero-usage day writes no row on EMUI), so "no logs" would punish exactly the
     * users who did best. The trigger is install provenance, never evidence volume.
     *
     * Money-free by construction: only ever set on `mode == SOFT` rows with no PaymentIntent and
     * no group id, so it can never stand in for a Hard Mode settlement or strand a stake.
     *
     * Unlike [ENDED] this IS persisted to Firestore — via the `markChallengeSettled` CF, since the
     * client cannot write `status` itself. That write is what stops the next reinstall from
     * re-pulling the doc as `active` and re-running this whole cycle.
     */
    ENDED_UNVERIFIED;

    /**
     * True for every state in which the challenge is over and no longer enforces — i.e. everything
     * except [ACTIVE]. This is exactly the set of statuses
     * [com.finite.focus.data.local.db.dao.ChallengeDao.getFinishedSoloChallenges] returns, and the
     * set that may stamp `ChallengeEntity.endedAt`.
     *
     * Deliberately derived as "not ACTIVE" rather than listed: a future terminal state added to
     * this enum is then terminal here by default, which fails safe (it gets an ended-at date) in a
     * way an allow-list would not (it would silently get none).
     */
    val isTerminal: Boolean get() = this != ACTIVE
}
