package com.finite.focus.util

import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import timber.log.Timber

/**
 * The Timber tree that makes production logging visible in Sentry.
 *
 * ## The gap this closes
 *
 * `DetoxApplication.onCreate` used to plant a tree only under `BuildConfig.DEBUG`. Timber with
 * zero planted trees is a silent no-op — every `Timber.e`/`Timber.w` in the app formatted its
 * message, walked its arguments and then wrote to nothing. In the Play build that meant the
 * diligent logging throughout this codebase produced exactly no evidence, which is why a
 * Google Sign-In failure whose `ApiException.statusCode` was computed, branched on and logged
 * still cost a full investigation instead of a query.
 *
 * ## What is forwarded, and what deliberately is not
 *
 *  - **WARN and ERROR → breadcrumb.** Breadcrumbs are free until an event is sent, at which
 *    point they become the trail leading up to it. This is where the log lines earn their keep.
 *  - **ERROR *with a throwable* → Sentry event.** An error worth an exception object is worth an
 *    issue. An `ERROR` with no throwable (a `Timber.e("...")` string) stays a breadcrumb only:
 *    the sites that genuinely want an event of their own call [Sentry.captureException] or
 *    [Sentry.captureMessage] explicitly, which keeps the decision at the call site rather than
 *    having this tree guess.
 *  - **DEBUG, INFO and VERBOSE → nothing.** Rejected in [isLoggable], so they never reach [log]
 *    and cost nothing beyond the priority comparison. This codebase logs at DEBUG very freely
 *    (auth state, WorkManager scheduling, FCM tokens); forwarding it would burn the Sentry quota
 *    for noise, and much of it is exactly the identifying data that must not leave the device.
 *
 * ## Duplicate events
 *
 * Several call sites already pair `Timber.e(e, ...)` with an explicit `Sentry.captureException(e)`
 * — see `ChallengeCreationViewModel` and `DailyEvaluationWorker`. Both now report the *same*
 * throwable instance, and Sentry's `DuplicateEventDetectionEventProcessor` (on by default via
 * `SentryOptions.isEnableDeduplication`) drops the second. The explicit call sites are left
 * exactly as they are: they are the load-bearing ones, and this tree is the safety net beneath
 * them, not a replacement.
 *
 * ## Privacy
 *
 * Every message is passed through [LogRedaction] before it becomes a breadcrumb, so an email
 * address or token in a log line written by future code cannot reach Sentry. That is the safety
 * net; the auth-path call sites were separately fixed to log `uid` rather than `email`, which is
 * the actual fix. See [LogRedaction] for why both halves are needed.
 *
 * ## Why this is planted in debug builds too
 *
 * Planting it only in release would make the release path untestable without a Play build. It is
 * planted unconditionally instead, and the existing `beforeSend` hook in `DetoxApplication`
 * already nulls every event from a debug build unless it carries the `test_crash` tag. So in
 * debug this tree accumulates breadcrumbs that are never transmitted (breadcrumbs only leave the
 * device attached to an event), and the Debug Panel's Sentry section can drive a tagged failure
 * through this exact code path to prove it end to end.
 */
class SentryTimberTree : Timber.Tree() {

    /**
     * Gate DEBUG/INFO/VERBOSE out here rather than in [log], so Timber skips formatting the
     * message and boxing its arguments entirely for the levels we discard.
     */
    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val isError = priority >= Log.ERROR

        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                category = "log"
                this.message = LogRedaction.redact(message)
                level = if (isError) SentryLevel.ERROR else SentryLevel.WARNING
                if (tag != null) setData("tag", tag)
            }
        )

        if (isError && t != null) {
            Sentry.captureException(t)
        }
    }
}
