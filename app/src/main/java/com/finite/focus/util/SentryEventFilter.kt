package com.finite.focus.util

import java.util.concurrent.CancellationException

/**
 * The `beforeSend` predicate that decides whether a Sentry event is worth reporting.
 *
 * Pure Kotlin on purpose (no Android, no Sentry types) so it can be unit-tested directly —
 * [com.finite.focus.util.SentryEventFilterTest] — instead of only being exercisable through a
 * real `SentryAndroid.init`. Wired up in `DetoxApplication.onCreate`.
 *
 * ## Why coroutine cancellation is dropped
 *
 * Cancellation is normal structured-concurrency control flow, not a failure. When a coroutine's
 * scope goes away — an Activity/ViewModel being destroyed, a `CoroutineWorker` stopped by
 * WorkManager, the process being torn down as the user leaves the app — every suspension point
 * still in flight throws a [CancellationException]. The app is full of broad
 * `catch (e: Exception)` blocks around suspending work, and a handful of those report to Sentry,
 * so a perfectly healthy "user closed the app mid-startup" turns into an issue in production.
 *
 * That is exactly what release 1.0.1 (build 3) showed: three `JobCancellationException` events,
 * one per user, all handled (no `mechanism`), all ~170 ms after SDK init on installs that had
 * never finished onboarding. Nothing crashed; a startup job was simply cancelled.
 *
 * Filtering here at the SDK level is deliberate: it is the one place that covers *every* current
 * and future reporter without deleting or restructuring any catch block. The catch blocks that
 * swallow cancellation are a separate (real) concern — see `docs/invariants.md` — but they are
 * not fixed by making the noise louder.
 *
 * ## Why this is a type check and not `addIgnoredExceptionForType`
 *
 * `SentryOptions.addIgnoredExceptionForType` exists in sentry-android-core 7.14.0, but its
 * matcher is exact-class only:
 *
 * ```java
 * // io.sentry.SentryOptions (7.14.0)
 * boolean containsIgnoredExceptionForType(final @NotNull Throwable throwable) {
 *   return this.ignoredExceptionsForType.contains(throwable.getClass());
 * }
 * ```
 *
 * A `Set.contains(throwable.getClass())` never matches a subclass, so registering
 * `CancellationException::class.java` would ignore *literally nothing we ever see*:
 * kotlinx-coroutines only ever throws the subclasses `JobCancellationException` and
 * `TimeoutCancellationException`. An `is` check covers the whole hierarchy in one line and
 * survives kotlinx adding another subtype.
 *
 * Note that `kotlinx.coroutines.CancellationException` is a `typealias` for
 * [java.util.concurrent.CancellationException] on the JVM, so this single check covers both
 * spellings.
 *
 * ## Scope: the reported throwable only, never the cause chain
 *
 * Only the throwable the event was captured with is inspected. Walking `cause` would risk
 * dropping a genuine failure that merely happens to carry a cancellation somewhere below it,
 * and it buys nothing in practice: when a child coroutine fails for a real reason, the parent
 * rethrows that original exception — a [CancellationException] surfacing at a catch site really
 * does mean "this was cancelled".
 */
object SentryEventFilter {

    /**
     * True when [throwable] is coroutine/future cancellation and the event should be dropped.
     *
     * Covers [CancellationException] and every subclass, notably
     * `kotlinx.coroutines.JobCancellationException` and
     * `kotlinx.coroutines.TimeoutCancellationException`.
     */
    fun isCancellation(throwable: Throwable?): Boolean = throwable is CancellationException
}
