package com.detox.app.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.detox.app.R
import com.detox.app.data.remote.firebase.AnalyticsService
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.PaymentRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.domain.usecase.CheckDailyLimitUseCase
import com.detox.app.domain.usecase.DailyLimitStatus
import com.detox.app.presentation.components.BlockingScreenOverlay
import com.detox.app.presentation.components.BudgetSelectionOverlay
import com.detox.app.presentation.components.HardModeLockoutOverlay
import com.detox.app.presentation.components.LimitExceededOverlay
import com.detox.app.presentation.components.SessionIntentionOverlay
import com.detox.app.presentation.components.SessionLimitReachedOverlay
import com.detox.app.ui.theme.DetoxTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checkDailyLimitUseCase: CheckDailyLimitUseCase,
    private val paymentRepository: PaymentRepository,
    private val analyticsService: AnalyticsService,
    private val dailyLogRepository: DailyLogRepository,
    private val usageStatsRepository: UsageStatsRepository,
    private val challengeRepository: ChallengeRepository
) {

    companion object {
        const val SESSION_PREFS_NAME = "detox_session_timers"
        const val SESSION_END_KEY_PREFIX = "session_end_"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sessionPrefs: SharedPreferences =
        context.getSharedPreferences(SESSION_PREFS_NAME, Context.MODE_PRIVATE)
    private var currentOverlayView: View? = null

    /** True while an overlay is added to the WindowManager. Exposed for cross-component checks. */
    val isOverlayVisible: Boolean get() = currentOverlayView != null
    private var listeningJob: Job? = null
    private val exceededAppsToday = mutableSetOf<String>()

    /**
     * Packages permanently locked until midnight in Hard Mode.
     * Mapped packageName → LockedAppInfo (to access the emergency code).
     */
    private val hardLockedPackages = mutableMapOf<String, LockedAppInfo>()

    /**
     * Conscious-open count per package for session-limit challenges.
     * Incremented ONLY when the user deliberately taps "Yes, open it" in Stage 1.
     * Loaded lazily from Room on first access; persisted back after every increment.
     * Reset at midnight.
     */
    private val consciousOpensToday = mutableMapOf<String, Int>()

    /**
     * Epoch-ms timestamp of when the session countdown timer last ended for a package,
     * whether it expired naturally or was cancelled because the app left the foreground.
     * Passed into Stage 1 so the user can see "Last session ended Xm Ys ago".
     */
    private val lastSessionEndedAt = mutableMapOf<String, Long>()

    /**
     * Coroutine job for the active session countdown timer.
     * Only one timer can run at a time.
     */
    private var sessionTimerJob: Job? = null

    /** Package currently being counted down. Null when no timer is running. */
    private var sessionTimerPackage: String? = null

    /**
     * Packages whose session-limit challenge has been marked FAILED today (Soft Mode).
     * After failure the app opens freely for the rest of the day — no overlay is shown.
     * Hard Mode uses [hardLockedPackages] instead.
     * Reset at midnight.
     */
    private val failedSessionAppsToday = mutableSetOf<String>()

    private var limitReachedTimerJob: Job? = null
    private var foregroundListenerJob: Job? = null

    // ── Daily Time Budget tracking ─────────────────────────────────────────────

    /**
     * In-memory remaining budget per package for TIME_BUDGET challenges.
     * Loaded lazily from Room on first access; updated in-memory on each session start.
     * Reset at midnight.
     */
    private val budgetRemainingToday = mutableMapOf<String, Int>()

    /** Coroutine job for the active budget countdown timer. */
    private var budgetTimerJob: Job? = null

    /** Package currently being counted down for a budget session. */
    private var budgetTimerPackage: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Overlay time tracking (screen-time attribution) ────────────────────────

    /** Retained from [startListening] so [dismissOverlay] can launch persistence coroutines. */
    private var serviceScope: CoroutineScope? = null

    /** The challengeId of the challenge whose app is currently obscured by an overlay. */
    private var currentOverlayChallengeId: String? = null

    /** Epoch-ms when the current overlay was added to the WindowManager. */
    private var currentOverlayShownAt: Long? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    fun startListening(scope: CoroutineScope) {
        serviceScope = scope
        restorePersistedSessionTimers(scope)
        listeningJob = scope.launch {
            TrackedAppEventBus.appOpenEvents.collect { packageName ->
                handleAppOpen(packageName, scope)
            }
        }

        // Website blocking: show overlay when AccessibilityService detects a blocked domain
        scope.launch {
            TrackedAppEventBus.urlBlockedEvents.collect { domain ->
                showWebsiteBlockedOverlay(domain)
            }
        }

        // Home button detected while overlay is visible — dismiss overlay (user chose to go home)
        scope.launch {
            TrackedAppEventBus.homeDetectedEvents.collect {
                if (isOverlayVisible) {
                    Timber.d("Overlay visible=$isOverlayVisible, hiding=home_button")
                    dismissOverlay("home_button")
                }
            }
        }

        // Cancel budget timer if user navigates away; session timer persists across foreground changes
        foregroundListenerJob = scope.launch {
            TrackedAppEventBus.currentForegroundPackage.collect { current ->
                val timed = sessionTimerPackage
                if (timed != null && current != timed) {
                    Timber.d(
                        "OverlayManager: $timed left foreground (now: $current) " +
                                "— session timer continues running in background"
                    )
                }
                val budgetTimed = budgetTimerPackage
                if (budgetTimed != null && current != budgetTimed) {
                    Timber.d(
                        "OverlayManager: $budgetTimed left foreground (now: $current) " +
                                "— cancelling budget timer (budget already deducted)"
                    )
                    budgetTimerJob?.cancel()
                    budgetTimerJob = null
                    budgetTimerPackage = null
                }
            }
        }

        scheduleMidnightReset()
    }

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        limitReachedTimerJob?.cancel()
        limitReachedTimerJob = null
        sessionTimerJob?.cancel()
        sessionTimerJob = null
        sessionTimerPackage = null
        budgetTimerJob?.cancel()
        budgetTimerJob = null
        budgetTimerPackage = null
        foregroundListenerJob?.cancel()
        foregroundListenerJob = null
        serviceScope = null
        dismissOverlay()
    }

    // ── Core dispatch ──────────────────────────────────────────────────────────

    private suspend fun handleAppOpen(packageName: String, scope: CoroutineScope) {
        if (isOverlayVisible) {
            Timber.d("Overlay visible=$isOverlayVisible, skipping new overlay for $packageName")
            return
        }

        // App freed for the rest of the day — user already accepted the consequence
        if (TrackedAppEventBus.freedPackagesToday.value.contains(packageName)) {
            Timber.d("OverlayManager: $packageName is freed for today — skipping overlay")
            return
        }

        // Hard Mode permanent lockout takes priority
        hardLockedPackages[packageName]?.let { info ->
            showHardModeLockout(info, scope)
            return
        }

        val result = checkDailyLimitUseCase(packageName)
        if (result.isFailure) {
            Timber.w("Failed to check daily limit for $packageName: ${result.exceptionOrNull()}")
            return
        }

        val status = result.getOrThrow()

        // Session-limit challenges use the two-stage conscious-open flow
        if (status.challenge.limitType == LimitType.SESSIONS) {
            handleSessionLimitApp(status, scope)
            return
        }

        // Daily budget challenges use the budget-selection flow
        if (status.challenge.limitType == LimitType.TIME_BUDGET) {
            handleTimeBudgetApp(status, scope)
            return
        }

        // Time-limit challenges keep the existing blocking + limit-exceeded flow
        when {
            status.limitExceeded || exceededAppsToday.contains(packageName) ->
                showLimitExceededOverlay(status, scope)
            else ->
                showBlockingOverlay(status, scope)
        }
    }

    // ── Session-limit two-stage flow ───────────────────────────────────────────

    /**
     * Entry point for session-limit challenges.
     *
     * On first call today the conscious-open count is loaded from Room so the
     * counter survives service restarts within the same calendar day.
     * Dispatches to Stage 1 (intention check) or Stage 2 (limit reached) based on
     * how many conscious opens have been confirmed so far today.
     */
    private suspend fun handleSessionLimitApp(status: DailyLimitStatus, scope: CoroutineScope) {
        val challenge = status.challenge
        val packageName = (challenge.appPackageName ?: "")

        // If the user is returning to the app while a session is still running, let them in.
        // Primary guard lives in AppDetectionAccessibilityService; this is the defensive fallback
        // for paths that arrive here without going through the service (e.g. timer expiry re-check).
        val sessionEndTime = sessionPrefs.getLong("$SESSION_END_KEY_PREFIX$packageName", 0L)
        if (sessionEndTime > System.currentTimeMillis()) {
            val remaining = sessionEndTime - System.currentTimeMillis()
            Timber.d("User returned to $packageName, session remaining: ${remaining}ms")
            return
        }

        // After a Soft Mode failure the app opens freely for the rest of the day
        if (failedSessionAppsToday.contains(packageName)) {
            Timber.d("OverlayManager: session already failed for $packageName today — no overlay")
            return
        }

        // Lazy-load persisted count from Room on first access per day
        if (!consciousOpensToday.containsKey(packageName)) {
            val today = todayMidnightMs()
            val persisted = dailyLogRepository.getConsciousOpens(challenge.id, today).getOrElse { 0 }
            consciousOpensToday[packageName] = persisted
            Timber.d(
                "OverlayManager: loaded consciousOpens=$persisted for $packageName from Room"
            )
        }

        val confirmedOpens = consciousOpensToday.getOrDefault(packageName, 0)
        val maxOpens = challenge.limitValueSessions ?: 0

        val stage = if (confirmedOpens >= maxOpens) 2 else 1
        Timber.d(
            "OverlayManager: consciousOpens=$confirmedOpens limit=$maxOpens → stage=$stage " +
                    "for ${challenge.appDisplayName}"
        )
        if (stage == 2) {
            showSessionLimitReachedOverlay(status, scope)
        } else {
            showSessionIntentionOverlay(status, confirmedOpens, scope)
        }
    }

    /** Stage 1 — Intention Check: shown on every conscious open while limit not yet reached. */
    private suspend fun showSessionIntentionOverlay(
        status: DailyLimitStatus,
        confirmedOpens: Int,
        scope: CoroutineScope
    ) {
        val challenge = status.challenge
        val maxOpens = challenge.limitValueSessions ?: 0
        Timber.d(
            "OverlayManager: Stage 1 shown for ${challenge.appDisplayName} " +
                    "— $confirmedOpens/$maxOpens conscious opens used"
        )

        val motivationText = challenge.customMotivation
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.default_motivation_text)
        Timber.d(
            "OverlayManager: Stage 1 motivation text for ${challenge.appDisplayName}: " +
                    "\"$motivationText\" (custom=${challenge.customMotivation})"
        )

        val streak = getStreak(challenge.id)

        val composeView = createSessionComposeView(
            onBack = {
                Timber.d(
                    "OverlayManager: Stage 1 back button — going home " +
                            "for ${challenge.appDisplayName}"
                )
                dismissOverlay()
                goHome()
            }
        ) {
            DetoxTheme {
                SessionIntentionOverlay(
                    packageName = (challenge.appPackageName ?: ""),
                    appName = challenge.appDisplayName,
                    opensUsed = confirmedOpens,
                    maxOpens = maxOpens,
                    lastSessionEndedAt = lastSessionEndedAt[(challenge.appPackageName ?: "")],
                    motivationText = motivationText,
                    streak = streak,
                    onYes = {
                        val newCount =
                            consciousOpensToday.getOrDefault((challenge.appPackageName ?: ""), 0) + 1
                        consciousOpensToday[(challenge.appPackageName ?: "")] = newCount
                        Timber.d(
                            "OverlayManager: Stage 1 'Yes, open it' — " +
                                    "consciousOpens=$newCount/$maxOpens for ${challenge.appDisplayName}"
                        )

                        // Persist the updated count so it survives a service restart
                        scope.launch {
                            val today = todayMidnightMs()
                            dailyLogRepository.upsertConsciousOpens(challenge.id, today, newCount)
                                .onFailure { e ->
                                    Timber.e(
                                        e,
                                        "OverlayManager: failed to persist consciousOpens for ${challenge.id}"
                                    )
                                }
                        }

                        dismissOverlay()

                        // Always start the session timer — Stage 2 is shown on the NEXT open
                        // attempt once handleSessionLimitApp sees consciousOpens >= maxOpens.
                        // This way reaching the limit (e.g. open 5 of 5) still lets the user
                        // complete that session; Stage 2 appears only when they try to open again.
                        Timber.d(
                            "OverlayManager: consciousOpens=$newCount limit=$maxOpens " +
                                    "→ stage=${if (newCount >= maxOpens) "next_open_is_stage2" else "timer_started"} " +
                                    "for ${challenge.appDisplayName}"
                        )
                        startSessionTimer(
                            packageName = (challenge.appPackageName ?: ""),
                            durationMinutes = challenge.sessionDurationMinutes,
                            challengeId = challenge.id,
                            scope = scope
                        )
                    },
                    onNo = {
                        Timber.d(
                            "OverlayManager: Stage 1 'No, go back' — going home " +
                                    "for ${challenge.appDisplayName}"
                        )
                        dismissOverlay()
                        goHome()
                    }
                )
            }
        }
        showSessionOverlay(composeView, challenge.id)
    }

    /** Stage 2 — Limit Reached: shown when conscious opens have reached the daily limit. */
    private suspend fun showSessionLimitReachedOverlay(status: DailyLimitStatus, scope: CoroutineScope) {
        val challenge = status.challenge
        val isHard = challenge.mode == ChallengeMode.HARD
        val confirmedOpens = consciousOpensToday.getOrDefault((challenge.appPackageName ?: ""), 0)
        Timber.d(
            "OverlayManager: Stage 2 shown for ${challenge.appDisplayName} " +
                    "— $confirmedOpens/${challenge.limitValueSessions} conscious opens, " +
                    "mode=${challenge.mode}"
        )

        val streak = getStreak(challenge.id)

        val composeView = createSessionComposeView(
            onBack = {
                Timber.d(
                    "OverlayManager: Stage 2 back button — going home " +
                            "for ${challenge.appDisplayName}"
                )
                dismissOverlay()
                goHome()
            }
        ) {
            DetoxTheme {
                SessionLimitReachedOverlay(
                    packageName = (challenge.appPackageName ?: ""),
                    appName = challenge.appDisplayName,
                    challengeMode = challenge.mode,
                    amountCents = challenge.amountCents,
                    streak = streak,
                    onYesLose = {
                        Timber.d(
                            "OverlayManager: Stage 2 'Yes, I accept' tapped " +
                                    "for ${challenge.appDisplayName} (mode=${challenge.mode})"
                        )
                        // Mark package as free immediately — no more overlays for the rest of the day
                        failedSessionAppsToday.add((challenge.appPackageName ?: ""))
                        TrackedAppEventBus.markPackageFreeForToday((challenge.appPackageName ?: ""))

                        if (isHard && challenge.stripePaymentIntentId != null) {
                            // Hard Mode: capture payment only — app opens freely (no lockout)
                            analyticsService.logLimitExceeded("hard_session", (challenge.appPackageName ?: ""))
                            Timber.d(
                                "OverlayManager: Hard session FAILED — payment captured, " +
                                        "${challenge.appDisplayName} opens freely for rest of day"
                            )
                            scope.launch {
                                paymentRepository.capturePayment(challenge.stripePaymentIntentId)
                                    .onSuccess {
                                        Timber.d(
                                            "OverlayManager: payment captured for Hard session fail: " +
                                                    "${challenge.appDisplayName}"
                                        )
                                    }
                                    .onFailure { e ->
                                        Timber.e(
                                            e,
                                            "OverlayManager: payment capture failed for Hard session fail: " +
                                                    "${challenge.id}"
                                        )
                                    }
                                writeDailyLogForHardCapture(
                                    challengeId = challenge.id,
                                    packageName = (challenge.appPackageName ?: ""),
                                    amountCents = challenge.amountCents ?: 0
                                )
                            }
                            dismissOverlay()
                        } else {
                            // Soft Mode: write DailyLog (points = 0), app opens freely rest of day
                            analyticsService.logLimitExceeded("soft_session", (challenge.appPackageName ?: ""))
                            Timber.d(
                                "OverlayManager: Soft session FAILED — " +
                                        "${challenge.appDisplayName} opens freely for rest of day"
                            )
                            scope.launch {
                                writeDailyLogForSessionFailed(challenge.id, (challenge.appPackageName ?: ""))
                            }
                            dismissOverlay()
                        }
                    },
                    onNo = {
                        Timber.d(
                            "OverlayManager: Stage 2 'No, stop' tapped " +
                                    "— going home for ${challenge.appDisplayName}"
                        )
                        dismissOverlay()
                        goHome()
                    }
                )
            }
        }
        showSessionOverlay(composeView, challenge.id)
    }

    // ── Session countdown timer ────────────────────────────────────────────────

    /**
     * Starts a per-session countdown for [packageName] lasting [durationMinutes].
     *
     * When the timer expires and the tracked app is still in the foreground, Stage 1
     * is re-shown (with the updated open count) so the user must consciously confirm
     * each additional session.
     *
     * Only one timer runs at a time; any previous timer is cancelled first.
     */
    private fun startSessionTimer(
        packageName: String,
        durationMinutes: Int,
        challengeId: String,
        scope: CoroutineScope
    ) {
        cancelSessionTimer()
        sessionTimerPackage = packageName
        val durationMs = durationMinutes * 60_000L
        val endTimestamp = System.currentTimeMillis() + durationMs
        sessionPrefs.edit().putLong("$SESSION_END_KEY_PREFIX$packageName", endTimestamp).apply()
        Timber.d("OverlayManager: session timer started for $packageName ($durationMinutes min, ends at $endTimestamp)")

        sessionTimerJob = scope.launch {
            var remaining = endTimestamp - System.currentTimeMillis()
            while (remaining > 0) {
                val tick = minOf(remaining, 60_000L)
                delay(tick)
                remaining = endTimestamp - System.currentTimeMillis()
                if (remaining > 0) {
                    Timber.d("Session timer: ${remaining}ms remaining for $packageName")
                }
            }

            Timber.d("OverlayManager: session timer expired for $packageName")
            lastSessionEndedAt[packageName] = System.currentTimeMillis()
            sessionTimerPackage = null
            sessionPrefs.edit().remove("$SESSION_END_KEY_PREFIX$packageName").apply()

            if (currentOverlayView != null) {
                Timber.d("OverlayManager: overlay already showing on timer expiry — skipping re-show")
                return@launch
            }

            val currentForeground = TrackedAppEventBus.currentForegroundPackage.value
            if (currentForeground == packageName) {
                Timber.d(
                    "OverlayManager: $packageName still in foreground on timer expiry " +
                            "— re-showing Stage 1"
                )
                val result = checkDailyLimitUseCase(packageName)
                if (result.isSuccess) {
                    handleSessionLimitApp(result.getOrThrow(), scope)
                } else {
                    Timber.w(
                        "OverlayManager: failed to re-check limit on timer expiry for $packageName"
                    )
                }
            } else {
                Timber.d(
                    "OverlayManager: $packageName not in foreground on timer expiry — overlay will show on next open"
                )
            }
        }
    }

    private fun cancelSessionTimer() {
        sessionTimerJob?.let {
            Timber.d("OverlayManager: session timer cancelled for $sessionTimerPackage")
            it.cancel()
        }
        sessionTimerPackage?.let { pkg ->
            sessionPrefs.edit().remove("$SESSION_END_KEY_PREFIX$pkg").apply()
        }
        sessionTimerJob = null
        sessionTimerPackage = null
    }

    /**
     * On service (re)start, check SharedPreferences for any previously persisted session timer.
     * If the end timestamp is still in the future, reschedule the coroutine with the remaining delay.
     * If it already expired, the overlay will show naturally when the user next opens the tracked app.
     */
    private fun restorePersistedSessionTimers(scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        val allPrefs = sessionPrefs.all
        for ((key, value) in allPrefs) {
            if (key.startsWith(SESSION_END_KEY_PREFIX) && value is Long) {
                val packageName = key.removePrefix(SESSION_END_KEY_PREFIX)
                val endTimestamp = value
                val remaining = endTimestamp - now
                if (remaining > 0) {
                    Timber.d(
                        "OverlayManager: restoring session timer for $packageName " +
                                "(${remaining}ms remaining)"
                    )
                    sessionTimerPackage = packageName
                    sessionTimerJob = scope.launch {
                        var rem = endTimestamp - System.currentTimeMillis()
                        while (rem > 0) {
                            val tick = minOf(rem, 60_000L)
                            delay(tick)
                            rem = endTimestamp - System.currentTimeMillis()
                            if (rem > 0) {
                                Timber.d("Session timer: ${rem}ms remaining for $packageName")
                            }
                        }
                        Timber.d("OverlayManager: restored session timer expired for $packageName")
                        lastSessionEndedAt[packageName] = System.currentTimeMillis()
                        sessionTimerPackage = null
                        sessionPrefs.edit().remove(key).apply()

                        val currentForeground = TrackedAppEventBus.currentForegroundPackage.value
                        if (currentForeground == packageName && currentOverlayView == null) {
                            val result = checkDailyLimitUseCase(packageName)
                            if (result.isSuccess) handleSessionLimitApp(result.getOrThrow(), scope)
                        } else {
                            Timber.d(
                                "OverlayManager: $packageName not in foreground after restored " +
                                        "timer expiry — overlay will show on next open"
                            )
                        }
                    }
                } else {
                    Timber.d(
                        "OverlayManager: persisted session timer for $packageName already expired " +
                                "(${-remaining}ms ago) — clearing"
                    )
                    sessionPrefs.edit().remove(key).apply()
                    lastSessionEndedAt[packageName] = endTimestamp
                }
            }
        }
    }

    /**
     * Writes a DailyLog entry when a Soft Mode session-limit challenge is failed intra-day.
     * Idempotent: skips the insert if a log already exists for today.
     * Sets limitExceeded=true and pointsEarned=0 so [DailyEvaluationWorker] at 23:59
     * produces zero points for the day without running a second evaluation.
     */
    private suspend fun writeDailyLogForSessionFailed(challengeId: String, packageName: String) {
        val today = todayMidnightMs()

        val existing = dailyLogRepository.getLogForDate(challengeId, today)
        if (existing.isSuccess && existing.getOrNull() != null) {
            Timber.d("OverlayManager: DailyLog already exists for $challengeId — skipping")
            return
        }

        val todayUsage = usageStatsRepository.getTodayUsageForApp(packageName)
        val log = DailyLog(
            id = UUID.randomUUID().toString(),
            challengeId = challengeId,
            date = today,
            totalMinutes = todayUsage.minutes,
            openCount = todayUsage.opens,
            consciousOpens = consciousOpensToday.getOrDefault(packageName, 0),
            pointsEarned = 0,
            limitExceeded = true,
            moneyLostCents = 0
        )
        dailyLogRepository.insertDailyLog(log)
            .onSuccess {
                Timber.d(
                    "OverlayManager: DailyLog written for soft session-failed " +
                            "challengeId=$challengeId (consciousOpens=${log.consciousOpens}, " +
                            "minutes=${todayUsage.minutes}, opens=${todayUsage.opens})"
                )
            }
            .onFailure { e ->
                Timber.e(e, "OverlayManager: failed to write DailyLog for $challengeId")
            }
    }

    // ── Daily Time Budget flow ─────────────────────────────────────────────────

    /**
     * Entry point for TIME_BUDGET challenges.
     * Lazy-loads the remaining budget from Room on first access, then shows either the
     * budget selection screen (budget > 0) or the exhausted overlay (budget == 0).
     */
    private suspend fun handleTimeBudgetApp(status: DailyLimitStatus, scope: CoroutineScope) {
        val challenge = status.challenge
        val packageName = (challenge.appPackageName ?: "")

        if (failedSessionAppsToday.contains(packageName)) {
            Timber.d("OverlayManager: budget already exhausted for $packageName today — no overlay")
            return
        }

        // Lazy-load remaining budget from Room on first access today
        if (!budgetRemainingToday.containsKey(packageName)) {
            val today = todayMidnightMs()
            val log = dailyLogRepository.getLogForDate(challenge.id, today).getOrNull()
            val hasRealBudgetActivity = log != null &&
                    (log.budgetUsedMinutes > 0 || log.budgetRemainingMinutes > 0)
            val remaining = if (hasRealBudgetActivity) {
                log!!.budgetRemainingMinutes
            } else {
                challenge.dailyBudgetMinutes ?: 0
            }
            budgetRemainingToday[packageName] = remaining
            Timber.d(
                "OverlayManager: loaded budgetRemaining=${remaining}min for $packageName from Room"
            )
        }

        val remaining = budgetRemainingToday.getOrDefault(packageName, 0)

        if (remaining <= 0) {
            showBudgetExhaustedOverlay(status, scope)
        } else {
            showBudgetSelectionOverlay(status, remaining, scope)
        }
    }

    /** Shows the budget selection screen. User picks how many minutes to spend. */
    private fun showBudgetSelectionOverlay(
        status: DailyLimitStatus,
        remainingMinutes: Int,
        scope: CoroutineScope
    ) {
        val challenge = status.challenge
        val packageName = (challenge.appPackageName ?: "")
        Timber.d(
            "OverlayManager: showing BudgetSelectionOverlay for ${challenge.appDisplayName} " +
                    "(remaining=${remainingMinutes}min)"
        )

        val composeView = createSessionComposeView(
            onBack = {
                Timber.d(
                    "OverlayManager: budget overlay back — going home for ${challenge.appDisplayName}"
                )
                dismissOverlay()
                goHome()
            }
        ) {
            DetoxTheme {
                BudgetSelectionOverlay(
                    appName = challenge.appDisplayName,
                    remainingMinutes = remainingMinutes,
                    onStart = { selectedMinutes ->
                        val newRemaining = maxOf(0, remainingMinutes - selectedMinutes)
                        budgetRemainingToday[packageName] = newRemaining
                        val totalBudget = challenge.dailyBudgetMinutes ?: 0
                        val usedSoFar = totalBudget - newRemaining
                        Timber.d(
                            "OverlayManager: Budget selected: ${selectedMinutes}min, " +
                                    "remaining: ${newRemaining}min, timer started " +
                                    "for ${challenge.appDisplayName}"
                        )
                        scope.launch {
                            val today = todayMidnightMs()
                            dailyLogRepository.updateBudgetState(
                                challengeId = challenge.id,
                                date = today,
                                used = usedSoFar,
                                remaining = newRemaining
                            ).onFailure { e ->
                                Timber.e(
                                    e, "OverlayManager: failed to persist budget state " +
                                            "for ${challenge.id}"
                                )
                            }
                        }
                        dismissOverlay()
                        startBudgetTimer(packageName, selectedMinutes, challenge, scope)
                    },
                    onGoBack = {
                        Timber.d(
                            "OverlayManager: budget overlay 'No, go back' " +
                                    "for ${challenge.appDisplayName}"
                        )
                        dismissOverlay()
                        goHome()
                    }
                )
            }
        }
        showSessionOverlay(composeView, challenge.id)
    }

    /**
     * Shows the budget-exhausted overlay (Stage 2) — reuses [SessionLimitReachedOverlay].
     * Soft Mode: lose today's points. Hard Mode: capture Stripe payment.
     * After acceptance the app is freed for the rest of the day.
     */
    private fun showBudgetExhaustedOverlay(status: DailyLimitStatus, scope: CoroutineScope) {
        val challenge = status.challenge
        val isHard = challenge.mode == ChallengeMode.HARD
        Timber.d(
            "OverlayManager: BudgetExhausted overlay for ${challenge.appDisplayName} " +
                    "(mode=${challenge.mode})"
        )

        val composeView = createSessionComposeView(
            onBack = {
                dismissOverlay()
                goHome()
            }
        ) {
            DetoxTheme {
                SessionLimitReachedOverlay(
                    packageName = (challenge.appPackageName ?: ""),
                    appName = challenge.appDisplayName,
                    challengeMode = challenge.mode,
                    amountCents = challenge.amountCents,
                    onYesLose = {
                        failedSessionAppsToday.add((challenge.appPackageName ?: ""))
                        TrackedAppEventBus.markPackageFreeForToday((challenge.appPackageName ?: ""))

                        if (isHard && challenge.stripePaymentIntentId != null) {
                            analyticsService.logLimitExceeded("hard_budget", (challenge.appPackageName ?: ""))
                            scope.launch {
                                paymentRepository.capturePayment(challenge.stripePaymentIntentId)
                                    .onSuccess {
                                        Timber.d(
                                            "OverlayManager: payment captured for Hard budget " +
                                                    "exhausted: ${challenge.appDisplayName}"
                                        )
                                    }
                                    .onFailure { e ->
                                        Timber.e(
                                            e,
                                            "OverlayManager: payment capture failed for ${challenge.id}"
                                        )
                                    }
                                writeDailyLogForBudgetExhausted(challenge)
                            }
                            dismissOverlay()
                        } else {
                            analyticsService.logLimitExceeded("soft_budget", (challenge.appPackageName ?: ""))
                            scope.launch { writeDailyLogForBudgetExhausted(challenge) }
                            dismissOverlay()
                        }
                    },
                    onNo = {
                        dismissOverlay()
                        goHome()
                    }
                )
            }
        }
        showSessionOverlay(composeView, challenge.id)
    }

    /**
     * Starts a countdown for the selected budget session duration.
     * On expiry re-shows the appropriate overlay based on remaining budget.
     */
    private fun startBudgetTimer(
        packageName: String,
        durationMinutes: Int,
        challenge: com.detox.app.domain.model.Challenge,
        scope: CoroutineScope
    ) {
        budgetTimerJob?.cancel()
        budgetTimerPackage = packageName
        val durationMs = durationMinutes * 60_000L
        Timber.d("OverlayManager: budget timer started for $packageName ($durationMinutes min)")

        budgetTimerJob = scope.launch {
            delay(durationMs)
            Timber.d("OverlayManager: budget timer expired for $packageName")
            budgetTimerPackage = null

            if (currentOverlayView != null) {
                Timber.d("OverlayManager: overlay already showing on budget timer expiry — skipping")
                return@launch
            }

            val currentForeground = TrackedAppEventBus.currentForegroundPackage.value
            if (currentForeground != packageName) {
                Timber.d(
                    "OverlayManager: $packageName not in foreground on budget timer expiry — no re-show"
                )
                return@launch
            }

            val remaining = budgetRemainingToday.getOrDefault(packageName, 0)
            val result = checkDailyLimitUseCase(packageName)
            if (result.isFailure) {
                Timber.w(
                    "OverlayManager: failed to re-check limit on budget expiry for $packageName"
                )
                return@launch
            }
            val updatedStatus = result.getOrThrow()

            if (remaining <= 0) {
                Timber.d("OverlayManager: budget exhausted for $packageName — showing Stage 2")
                showBudgetExhaustedOverlay(updatedStatus, scope)
            } else {
                Timber.d(
                    "OverlayManager: budget remaining=${remaining}min for $packageName " +
                            "— re-showing budget selection"
                )
                showBudgetSelectionOverlay(updatedStatus, remaining, scope)
            }
        }
    }

    /** Writes a final DailyLog when the budget is exhausted and the user accepts consequences. */
    private suspend fun writeDailyLogForBudgetExhausted(
        challenge: com.detox.app.domain.model.Challenge
    ) {
        val today = todayMidnightMs()
        val existing = dailyLogRepository.getLogForDate(challenge.id, today)
        if (existing.isSuccess && existing.getOrNull()?.limitExceeded == true) {
            Timber.d(
                "OverlayManager: budget exhausted log already exists for ${challenge.id} — skipping"
            )
            return
        }
        val totalBudget = challenge.dailyBudgetMinutes ?: 0
        val remaining = budgetRemainingToday.getOrDefault((challenge.appPackageName ?: ""), 0)
        val used = totalBudget - remaining
        val amountCents = if (challenge.mode == ChallengeMode.HARD) challenge.amountCents ?: 0 else 0

        val log = DailyLog(
            id = UUID.randomUUID().toString(),
            challengeId = challenge.id,
            date = today,
            totalMinutes = used,
            openCount = 0,
            budgetUsedMinutes = used,
            budgetRemainingMinutes = remaining,
            pointsEarned = 0,
            limitExceeded = true,
            moneyLostCents = amountCents
        )
        dailyLogRepository.insertDailyLog(log)
            .onSuccess {
                Timber.d(
                    "OverlayManager: budget exhausted DailyLog written for ${challenge.id} " +
                            "(used=${used}min, amountCents=$amountCents)"
                )
            }
            .onFailure { e ->
                Timber.e(e, "OverlayManager: failed to write budget exhausted DailyLog for ${challenge.id}")
            }
    }

    // ── Blocking overlay (time-limit challenges only) ──────────────────────────

    // ── Website blocking overlay ───────────────────────────────────────────────

    private suspend fun showWebsiteBlockedOverlay(domain: String) {
        if (currentOverlayView != null) return

        // Find which active challenge is responsible for blocking this domain
        val challenges = challengeRepository.getActiveChallengesList().getOrElse { emptyList() }
        val blockingChallenge = challenges.firstOrNull { domain in it.blockedDomains }

        // Streak: how many consecutive completed days (before today) the user hasn't exceeded
        val streak = blockingChallenge?.let { challenge ->
            dailyLogRepository.getStreakForChallenge(challenge.id, todayMidnightMs()).getOrElse { 0 }
        } ?: 0

        val motivationText = blockingChallenge?.customMotivation?.takeIf { it.isNotBlank() }

        val composeView = createSessionComposeView(
            onBack = {
                dismissOverlay()
                goHome()
            }
        ) {
            DetoxTheme {
                com.detox.app.presentation.components.WebsiteBlockedOverlay(
                    domain = domain,
                    challengeName = blockingChallenge?.appDisplayName,
                    streak = streak,
                    motivationText = motivationText,
                    onGoBack = {
                        dismissOverlay()
                        goHome()
                    }
                )
            }
        }
        showOverlay(composeView)
    }

    private suspend fun showBlockingOverlay(status: DailyLimitStatus, scope: CoroutineScope) {
        val streak = getStreak(status.challenge.id)
        val composeView = createSessionComposeView(
            onBack = {
                analyticsService.logBlockingScreenAction("back_button")
                dismissOverlay("back")
                goHome()
            }
        ) {
            DetoxTheme {
                BlockingScreenOverlay(
                    status = status,
                    streak = streak,
                    onOpenAnyway = {
                        analyticsService.logBlockingScreenAction("opened_anyway")
                        dismissOverlay("open_anyway")
                    },
                    onSkip = {
                        analyticsService.logBlockingScreenAction("skipped")
                        dismissOverlay("skip")
                        goHome()
                    }
                )
            }
        }
        showOverlay(composeView, status.challenge.id)
    }

    // ── Limit-exceeded overlay (time-limit challenges only) ────────────────────

    private suspend fun showLimitExceededOverlay(status: DailyLimitStatus, scope: CoroutineScope) {
        val challenge = status.challenge
        val isHard = challenge.mode == ChallengeMode.HARD
        val streak = getStreak(challenge.id)

        val composeView = createSessionComposeView(
            onBack = {
                dismissOverlay("back")
                goHome()
            }
        ) {
            DetoxTheme {
                LimitExceededOverlay(
                    appName = challenge.appDisplayName,
                    challengeMode = challenge.mode,
                    amountCents = challenge.amountCents,
                    todayMinutes = status.todayMinutes,
                    limitMinutes = challenge.limitValueMinutes,
                    streak = streak,
                    onContinue = {
                        if (isHard && challenge.stripePaymentIntentId != null) {
                            scope.launch { captureAndLock(status, scope) }
                        } else {
                            analyticsService.logLimitExceeded("soft", (challenge.appPackageName ?: ""))
                            Timber.d(
                                "OverlayManager: Soft Mode limit exceeded — " +
                                        "${challenge.appDisplayName} marked exceeded, " +
                                        "5-min re-show timer started"
                            )
                            exceededAppsToday.add((challenge.appPackageName ?: ""))
                            dismissOverlay()
                            startLimitReachedTimer((challenge.appPackageName ?: ""), scope)
                        }
                    },
                    onStop = {
                        dismissOverlay()
                        goHome()
                    }
                )
            }
        }
        showOverlay(composeView, challenge.id)
    }

    // ── Hard Mode: capture payment → lock app ──────────────────────────────────

    private suspend fun captureAndLock(status: DailyLimitStatus, scope: CoroutineScope) {
        val challenge = status.challenge
        val paymentIntentId = challenge.stripePaymentIntentId ?: run {
            Timber.w("captureAndLock called but stripePaymentIntentId is null for ${(challenge.appPackageName ?: "")}")
            return
        }

        analyticsService.logLimitExceeded("hard", (challenge.appPackageName ?: ""))
        dismissOverlay()

        scope.launch {
            paymentRepository.capturePayment(paymentIntentId)
                .onSuccess { Timber.d("Payment captured for ${challenge.appDisplayName}") }
                .onFailure { e -> Timber.e(e, "Failed to capture payment") }
        }

        writeDailyLogForHardCapture(challenge.id, (challenge.appPackageName ?: ""), challenge.amountCents ?: 0)

        val info = LockedAppInfo(
            challengeId = challenge.id,
            appName = challenge.appDisplayName,
            amountCents = challenge.amountCents ?: 0
        )
        hardLockedPackages[(challenge.appPackageName ?: "")] = info
        Timber.d(
            "OverlayManager: Hard Mode lockout active for '${challenge.appDisplayName}' " +
                    "(€${(challenge.amountCents ?: 0) / 100f} captured)"
        )
        showHardModeLockoutDirect(info, scope)
    }

    /**
     * Writes a DailyLog entry immediately when Hard Mode money is captured intra-day.
     * Idempotent: skips the insert if a log already exists for today.
     */
    private suspend fun writeDailyLogForHardCapture(
        challengeId: String,
        packageName: String,
        amountCents: Int
    ) {
        val today = todayMidnightMs()

        val existing = dailyLogRepository.getLogForDate(challengeId, today)
        if (existing.isSuccess && existing.getOrNull() != null) {
            Timber.d("OverlayManager: DailyLog already exists for challengeId=$challengeId — skipping")
            return
        }

        val todayUsage = usageStatsRepository.getTodayUsageForApp(packageName)
        val log = DailyLog(
            id = UUID.randomUUID().toString(),
            challengeId = challengeId,
            date = today,
            totalMinutes = todayUsage.minutes,
            openCount = todayUsage.opens,
            consciousOpens = consciousOpensToday.getOrDefault(packageName, 0),
            pointsEarned = 0,
            limitExceeded = true,
            moneyLostCents = amountCents
        )
        dailyLogRepository.insertDailyLog(log)
            .onSuccess {
                Timber.d(
                    "OverlayManager: DailyLog written for challengeId=$challengeId " +
                            "(moneyLostCents=$amountCents, minutes=${todayUsage.minutes}, " +
                            "opens=${todayUsage.opens})"
                )
            }
            .onFailure { e ->
                Timber.e(e, "OverlayManager: Failed to write DailyLog for challengeId=$challengeId")
            }
    }

    // ── Hard Mode lockout overlay ──────────────────────────────────────────────

    private fun showHardModeLockout(info: LockedAppInfo, scope: CoroutineScope) {
        showHardModeLockoutDirect(info, scope)
    }

    private fun showHardModeLockoutDirect(info: LockedAppInfo, scope: CoroutineScope) {
        val composeView = createSessionComposeView(
            onBack = {
                dismissOverlay("back")
                goHome()
            }
        ) {
            DetoxTheme {
                HardModeLockoutOverlay(
                    appName = info.appName,
                    amountCents = info.amountCents,
                    onExitHome = {
                        dismissOverlay("exit_home")
                        goHome()
                    }
                )
            }
        }
        showOverlay(composeView, info.challengeId)
    }

    // ── 5-minute re-trigger timer (time-limit Soft Mode) ──────────────────────

    private fun startLimitReachedTimer(packageName: String, scope: CoroutineScope) {
        limitReachedTimerJob?.cancel()
        limitReachedTimerJob = scope.launch {
            delay(5 * 60 * 1000L)
            val tracked = TrackedAppEventBus.trackedPackages.value
            if (tracked.contains(packageName)) {
                val result = checkDailyLimitUseCase(packageName)
                if (result.isSuccess && result.getOrThrow().limitExceeded) {
                    showLimitExceededOverlay(result.getOrThrow(), scope)
                }
            }
        }
    }

    // ── Midnight reset ─────────────────────────────────────────────────────────

    private fun scheduleMidnightReset() {
        val now = System.currentTimeMillis()
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val delay = midnight - now
        mainHandler.postDelayed({
            Timber.d("Midnight reset — clearing all daily overlay state")
            exceededAppsToday.clear()
            hardLockedPackages.clear()
            consciousOpensToday.clear()
            lastSessionEndedAt.clear()
            failedSessionAppsToday.clear()
            budgetRemainingToday.clear()
            TrackedAppEventBus.clearFreePackages()
            sessionTimerJob?.cancel()
            sessionTimerJob = null
            sessionTimerPackage?.let { pkg ->
                sessionPrefs.edit().remove("$SESSION_END_KEY_PREFIX$pkg").apply()
            }
            sessionTimerPackage = null
            budgetTimerJob?.cancel()
            budgetTimerJob = null
            budgetTimerPackage = null
            scheduleMidnightReset()
        }, delay)
    }

    // ── WindowManager helpers ──────────────────────────────────────────────────

    private fun createComposeView(content: @Composable () -> Unit): ComposeView {
        val composeView = ComposeView(context)
        val lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        composeView.setContent(content)
        return composeView
    }

    /**
     * Creates a standard ComposeView for session overlays and intercepts the hardware back button.
     * Pressing the hardware back button is treated the same as tapping "No" —
     * [onBack] is invoked instead of propagating the key event to the app underneath.
     */
    private fun createSessionComposeView(
        onBack: () -> Unit,
        content: @Composable () -> Unit
    ): ComposeView {
        val composeView = ComposeView(context)

        // Intercept back button presses
        composeView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onBack()
                true
            } else {
                false
            }
        }

        val lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        composeView.setContent(content)

        return composeView
    }



    /**
     * Shows a full-screen opaque overlay.
     *
     * All overlay types now share identical [WindowManager.LayoutParams]:
     * - [PixelFormat.OPAQUE] — nothing bleeds through from the app underneath
     * - [FLAG_LAYOUT_IN_SCREEN] only — [FLAG_NOT_FOCUSABLE] is intentionally absent
     *   so buttons receive touch events reliably and the back-intercepting views work
     * - [challengeId] is used to attribute overlay-visible time for screen-time correction
     */
    private fun showOverlay(view: ComposeView, challengeId: String? = null) {
        if (isOverlayVisible) return

        currentOverlayChallengeId = challengeId
        currentOverlayShownAt = System.currentTimeMillis()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )

        try {
            windowManager.addView(view, params)
            currentOverlayView = view
            TrackedAppEventBus.setOverlayVisible(true)
            view.requestFocus()
            Timber.d("Overlay visible=true, showing (challengeId=$challengeId)")
        } catch (e: Exception) {
            Timber.e(e, "OverlayManager: failed to show overlay")
        }
    }

    /** Session overlays use the same params — delegates to [showOverlay]. */
    private fun showSessionOverlay(view: ComposeView, challengeId: String? = null) =
        showOverlay(view, challengeId)

    private fun dismissOverlay(reason: String = "") {
        val wasVisible = isOverlayVisible
        Timber.d("Overlay visible=$wasVisible, hiding=${reason.ifEmpty { "unknown" }}")
        currentOverlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Timber.e(e, "Failed to dismiss overlay")
            }
            currentOverlayView = null
            TrackedAppEventBus.setOverlayVisible(false)
        }

        // Persist the duration this overlay was visible so usage-time calculations
        // can subtract it (the tracked app was in the background during this time).
        val shownAt = currentOverlayShownAt
        val challengeId = currentOverlayChallengeId
        if (shownAt != null && challengeId != null) {
            val durationMs = System.currentTimeMillis() - shownAt
            if (durationMs > 0L) {
                val today = todayMidnightMs()
                serviceScope?.launch {
                    dailyLogRepository.addOverlayPausedMs(challengeId, today, durationMs)
                        .onFailure { e ->
                            Timber.e(e, "OverlayManager: failed to persist overlay pause for $challengeId")
                        }
                    Timber.d(
                        "OverlayManager: overlay dismissed after ${durationMs}ms " +
                                "— overlay pause persisted for challengeId=$challengeId"
                    )
                }
            }
        }
        currentOverlayShownAt = null
        currentOverlayChallengeId = null
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(homeIntent)
    }

    /**
     * Returns the current streak for [challengeId]: consecutive completed days (before today)
     * where the limit was NOT exceeded.  Returns 0 on any error.
     */
    private suspend fun getStreak(challengeId: String): Int =
        dailyLogRepository.getStreakForChallenge(challengeId, todayMidnightMs()).getOrElse { 0 }

    private fun todayMidnightMs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    // ── Data class ─────────────────────────────────────────────────────────────

    private data class LockedAppInfo(
        val challengeId: String,
        val appName: String,
        val amountCents: Int
    )

    // ── LifecycleOwner for overlays ────────────────────────────────────────────

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        init {
            savedStateRegistryController.performRestore(null)
        }

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }
    }
}
