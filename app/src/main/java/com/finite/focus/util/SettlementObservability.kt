package com.finite.focus.util

import android.content.Context
import com.finite.focus.domain.model.ChallengeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Can this install's settlement verdict for a SOFT SOLO challenge be trusted at all?
 *
 * The settlement verdict (`SettleEndedSoftChallengesUseCase`) is derived from the challenge's
 * `DailyLog` history: FAILED iff any row has `limitExceeded`, else COMPLETED. That rule is sound
 * only while the history is complete. It is NOT complete after an uninstall: Room is destroyed,
 * the Firestore doc survives as `status: "active"` (no server pass settles Soft — solo
 * reconciliation queries `mode == "hard"`), and the reinstall re-pulls the doc onto an empty log
 * set. `any { limitExceeded }` on nothing is `false`, so the challenge settles COMPLETED and the
 * user is congratulated for a run the app never saw — possibly one they breached on day one.
 *
 * This predicate identifies exactly those rows so they can take
 * [com.finite.focus.domain.model.ChallengeStatus.ENDED_UNVERIFIED] instead of a fabricated win.
 *
 * ## The signal: install provenance, never evidence volume
 *
 * The trigger is `installedAt > endDate` — the challenge's last day was already over *before this
 * install existed*. Nothing on this device could have observed the window, so no amount of local
 * data could ever have produced an honest verdict.
 *
 * "The log set is empty" is deliberately NOT the signal, and this is the whole design decision. A
 * disciplined user legitimately generates few or zero log rows: a zero-usage day writes no
 * `DailyLog` at all on EMUI, and the nightly worker is routinely throttled. Keying on emptiness
 * would hand the neutral non-celebration to precisely the users who performed best, while a user
 * who breached once and then reinstalled would look identical. Install provenance separates
 * "we have no evidence because there was nothing to record" from "we have no evidence because the
 * evidence was destroyed" — which is the actual question.
 *
 * [installedAt] is `PackageInfo.firstInstallTime`, which survives app *updates* but resets on
 * uninstall + reinstall — exactly the boundary that destroys Room. It is also unaffected by
 * logout/login, which clears Room too (invariant #23) but leaves the install intact: a user who
 * logs back in on the same phone keeps the normal settlement path and their real verdict, because
 * their install predates the challenge.
 *
 * ## Fail-safe direction
 *
 * Every uncertainty resolves to `false` (settle normally). A missing/unreadable install time, a
 * zero end date, an open-ended sentinel — none of them may manufacture an unverified outcome. The
 * conservative answer here is to leave the existing behaviour alone, not to reach for the new one.
 *
 * ## Money fence
 *
 * `mode == SOFT`, no PaymentIntent, no group id — the same three guards
 * `SettleEndedSoftChallengesUseCase` and `isSoloSoftPermissionFailEligible` carry, for the same
 * reason. A row carrying a stake must never reach a money-free terminal state; Hard Mode
 * settlement and its capture/refund fail-open are untouched by this file.
 */
fun isUnobservedSoftChallenge(
    mode: ChallengeMode,
    stripePaymentIntentId: String?,
    groupChallengeId: String?,
    startDate: Long,
    endDate: Long,
    installedAt: Long,
): Boolean {
    // ── Money fence (identical to the settle use case's Soft-only filter) ────────
    if (mode != ChallengeMode.SOFT) return false
    if (stripePaymentIntentId != null) return false
    if (groupChallengeId != null) return false

    // Unusable dates → settle normally. Never invent an outcome from missing data.
    if (endDate <= 0L || startDate <= 0L) return false

    // Open-ended challenges never reach an end date, so they can never be "already over" at
    // install time. Guarded explicitly rather than relying on the sentinel arithmetic below.
    if (DateUtils.isOpenEnded(startDate, endDate)) return false

    // Unknown install time → settle normally (fail-safe).
    if (installedAt <= 0L) return false

    // The load-bearing comparison: the window closed before this install began.
    return installedAt > endDate
}

/**
 * `PackageInfo.firstInstallTime` for this app, or `0L` if it cannot be read.
 *
 * Returns `0L` rather than throwing or substituting `now`: `0L` is the value
 * [isUnobservedSoftChallenge] treats as "unknown → settle normally", so a PackageManager failure
 * degrades to the pre-existing behaviour instead of marking every synced challenge unverified.
 */
fun readInstalledAt(context: Context): Long = try {
    context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
} catch (e: Exception) {
    Timber.w(e, "readInstalledAt: could not read firstInstallTime — settlement stays on the normal path")
    0L
}

/**
 * Injectable wrapper over [readInstalledAt] so settlement logic can consult install provenance
 * without taking an Android [Context] dependency (and so tests can supply a value directly instead
 * of faking PackageManager).
 *
 * Cached: `firstInstallTime` cannot change while the process is alive, and the settle path runs on
 * every app open plus every tracked app-open, so re-querying PackageManager each time would be
 * pure overhead.
 */
@Singleton
open class InstallInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    open val installedAt: Long by lazy { readInstalledAt(context) }
}
