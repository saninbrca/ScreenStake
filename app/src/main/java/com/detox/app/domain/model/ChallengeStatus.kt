package com.detox.app.domain.model

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
    ENDED
}
