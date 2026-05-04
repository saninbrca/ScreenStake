package com.detox.app.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.di.ApplicationScope
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.repository.GroupChallengeRepository
import com.detox.app.domain.repository.PaymentRepository
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.domain.usecase.CheckDailyLimitUseCase
import com.detox.app.domain.usecase.DailyLimitStatus
import com.detox.app.domain.usecase.GetChallengeStreakUseCase
import com.detox.app.presentation.components.BlockingScreenOverlay
import com.detox.app.presentation.components.BudgetSelectionOverlay
import com.detox.app.presentation.components.GroupChallengeFailOverlay
import com.detox.app.presentation.components.HardModeLockoutOverlay
import com.detox.app.presentation.components.LimitExceededOverlay
import com.detox.app.presentation.components.SessionIntentionOverlay
import com.detox.app.presentation.components.SessionLimitReachedOverlay
import com.detox.app.presentation.components.TauntOverlay
import com.detox.app.ui.theme.DetoxTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.detox.app.util.DateUtils
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
    private val challengeRepository: ChallengeRepository,
    private val groupChallengeRepository: GroupChallengeRepository,
    private val firebaseAuthService: FirebaseAuthService,
    private val cloudFunctionsService: CloudFunctionsService,
    private val getChallengeStreakUseCase: GetChallengeStreakUseCase,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    companion object {
        const val SESSION_PREFS_NAME = "detox_session_timers"
        const val SESSION_END_KEY_PREFIX = "session_end_"

        // Shared preferences keys for DAILY_BUDGET sessions (read by UsageTrackingService)
        const val BUDGET_SESSION_PREFS_NAME    = "detox_budget_session"
        const val BUDGET_SESSION_END_TIME_KEY  = "budget_session_end_time"
        const val BUDGET_SESSION_START_TIME_KEY= "budget_session_start_time"
        const val BUDGET_SESSION_CHALLENGE_KEY = "budget_session_challenge_id"
        const val BUDGET_SESSION_PACKAGE_KEY   = "budget_session_package"
        const val BUDGET_COMMITTED_MS_KEY      = "budget_committed_ms"
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

    /**
     * Group challenge IDs for which the current user has already been marked as failed.
     * Once a fail overlay is shown, no further overlays are shown for this group challenge.
     * Reset at midnight.
     */
    private val failedGroupChallengeIds = mutableSetOf<String>()

    private var limitReachedTimerJob: Job? = null
    private var foregroundListenerJob: Job? = null

    // ── Daily Time Budget tracking ─────────────────────────────────────────────

    /** SharedPreferences backing the active budget session (read by UsageTrackingService). */
    private val budgetSessionPrefs: android.content.SharedPreferences =
        context.getSharedPreferences(BUDGET_SESSION_PREFS_NAME, Context.MODE_PRIVATE)

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Taunt overlay (non-blocking, shown on top of any app) ─────────────────
    private var tauntOverlayView: View? = null
    private val tauntListenerJobs = mutableMapOf<String, Job>()
    private val processedTauntIds = mutableSetOf<String>()

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

        // Home button detected while overlay is visible — dismiss overlay (user chose to go home).
        // Guard: ignore events fired <1s after the overlay appeared to avoid Recents animation
        // race where the launcher fires a state-changed event right after the overlay opens.
        scope.launch {
            TrackedAppEventBus.homeDetectedEvents.collect {
                val shownAt = currentOverlayShownAt
                if (isOverlayVisible && (shownAt == null || System.currentTimeMillis() - shownAt > 1_000L)) {
                    Timber.d("Overlay visible=$isOverlayVisible, hiding=home_button")
                    dismissOverlay("home_button")
                } else if (isOverlayVisible) {
                    Timber.d("OverlayManager: home detected <1s after overlay shown — ignoring (Recents animation race)")
                }
            }
        }

        foregroundListenerJob = scope.launch {
            TrackedAppEventBus.currentForegroundPackage.collect { current ->
                val timed = sessionTimerPackage
                if (timed != null && current != timed) {
                    Timber.d(
                        "OverlayManager: $timed left foreground (now: $current) " +
                                "— session timer continues running in background"
                    )
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
        foregroundListenerJob?.cancel()
        foregroundListenerJob = null
        tauntListenerJobs.values.forEach { it.cancel() }
        tauntListenerJobs.clear()
        processedTauntIds.clear()
        serviceScope = null
        dismissOverlay()
        dismissTauntOverlay()
    }

    // ── Budget session expiry: show overlay immediately ────────────────────────

    /**
     * Called by [com.detox.app.service.UsageTrackingService] when a DAILY_BUDGET session timer
     * expires. Shows [BudgetSelectionOverlay] (budget remaining) or [SessionLimitReachedOverlay]
     * (budget exhausted) over whatever is currently on screen, including the home screen.
     *
     * Must be called from any thread — dispatches to main via [mainHandler].
     */
    fun onBudgetSessionExpired(packageName: String) {
        mainHandler.post {
            val scope = serviceScope ?: return@post
            scope.launch {
                if (isOverlayVisible) {
                    Timber.d("OverlayManager: budget expired for $packageName but overlay already visible — skip")
                    return@launch
                }
                Timber.d("OverlayManager: budget session expired for $packageName — showing overlay")
                val result = checkDailyLimitUseCase(packageName)
                if (result.isFailure) {
                    Timber.w("OverlayManager: budget expiry — could not check limit for $packageName")
                    return@launch
                }
                val status = result.getOrThrow()

                // Guard: don't re-trigger for an already-failed Group Challenge.
                // onBudgetSessionExpired bypasses handleAppOpen so we must check here.
                val groupChallengeId = status.challenge.groupChallengeId
                if (groupChallengeId != null && failedGroupChallengeIds.contains(groupChallengeId)) {
                    Timber.d("OverlayManager: budget expired for $packageName but group $groupChallengeId already failed — skip")
                    return@launch
                }

                if (status.challenge.limitType == LimitType.TIME_BUDGET) {
                    handleTimeBudgetApp(status, scope)
                }
            }
        }
    }

    // ── Core dispatch ──────────────────────────────────────────────────────────

    private suspend fun handleAppOpen(packageName: String, scope: CoroutineScope) {
        val tEnter = android.os.SystemClock.elapsedRealtime()
        Timber.d("Blocking chain: handleAppOpen entered pkg=$packageName")
        if (isOverlayVisible) {
            Timber.d("Overlay visible=$isOverlayVisible, skipping new overlay for $packageName (after ${android.os.SystemClock.elapsedRealtime() - tEnter}ms)")
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
        val challenge = status.challenge
        val groupChallengeId = challenge.groupChallengeId

        // Unified guard: applies to ALL limit types.
        // Previously this came AFTER the SESSIONS/TIME_BUDGET early-returns, leaving both
        // limit types unguarded when a Group Challenge had already been failed.
        if (groupChallengeId != null && failedGroupChallengeIds.contains(groupChallengeId)) {
            Timber.d("OverlayManager: $packageName — group $groupChallengeId already failed, skipping overlay")
            return
        }

        // Diagnostic log for Group Challenge DAILY_BUDGET
        if (groupChallengeId != null) {
            Timber.d("Group challenge check: pkg=$packageName challengeType=GROUP limitType=${challenge.limitType}")
        }

        // Single unified dispatch for ALL limit types AND ALL challenge types
        when (challenge.limitType) {
            LimitType.SESSIONS    -> handleSessionLimitApp(status, scope)
            LimitType.TIME_BUDGET -> handleTimeBudgetApp(status, scope)
            LimitType.TIME -> {
                when {
                    status.limitExceeded || exceededAppsToday.contains(packageName) ->
                        if (groupChallengeId != null) showSessionLimitReachedOverlay(status, scope)
                        else showLimitExceededOverlay(status, scope)
                    else ->
                        showBlockingOverlay(status, scope)
                }
            }
            LimitType.TIME_WINDOW -> showBlockingOverlay(status, scope)
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

        // Source of truth for opensToday:
        //  - Group challenges: read from Firestore participants (live, authoritative)
        //  - Solo challenges: in-memory map → TrackedAppEventBus → DailyLog (fallback chain)
        val isGroup = challenge.groupChallengeId != null
        if (isGroup) {
            val groupId = challenge.groupChallengeId!!
            val uid = firebaseAuthService.currentUserId()
            val gc = groupChallengeRepository.getGroupChallengeById(groupId)
            val opens = gc?.participants?.firstOrNull { it.userId == uid }?.opensToday ?: 0
            consciousOpensToday[packageName] = opens
            Timber.d("Group overlay: opensToday=$opens from Firestore (groupId=$groupId)")
        } else if (!consciousOpensToday.containsKey(packageName)) {
            val busOpens = TrackedAppEventBus.groupSessionInfos.value[packageName]?.opensToday
            if (busOpens != null) {
                consciousOpensToday[packageName] = busOpens
                Timber.d("Group overlay: opensToday=$busOpens from DailyLog (TrackedAppEventBus fast-path)")
            } else {
                val today = todayKey()
                val persisted = dailyLogRepository.getConsciousOpens(challenge.id, today).getOrElse { 0 }
                consciousOpensToday[packageName] = persisted
                Timber.d("Group overlay: opensToday=$persisted from DailyLog")
            }
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

        val streak = getStreak(challenge)

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

                        // Keep in-memory event bus in sync so AccessibilityService can check synchronously
                        TrackedAppEventBus.incrementGroupSessionOpens(challenge.appPackageName ?: "")

                        // Atomically increment opensToday in Firestore — only on conscious "Ja, öffnen" tap
                        challenge.groupChallengeId?.let { groupId ->
                            val uid = firebaseAuthService.currentUserId()
                            if (uid != null) {
                                scope.launch {
                                    groupChallengeRepository.incrementParticipantOpensToday(groupId, uid)
                                }
                            }
                        }

                        challenge.appPackageName?.let {
                            AppDetectionAccessibilityService.allowTemporarily(it)
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
        val confirmedOpens = consciousOpensToday.getOrDefault((challenge.appPackageName ?: ""), 0)
        Timber.d(
            "OverlayManager: Stage 2 shown for ${challenge.appDisplayName} " +
                    "— $confirmedOpens/${challenge.limitValueSessions} conscious opens, " +
                    "mode=${challenge.mode}"
        )

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
                    onNo = {
                        Timber.d(
                            "OverlayManager: Stage 2 'Stark bleiben' tapped " +
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
        val effectiveDuration = maxOf(1, durationMinutes)
        Timber.d("Session timer started: ${effectiveDuration}min for $packageName")

        // Persist consciousOpens here — after dismissOverlay() has already fired its own
        // coroutine — so both writes are never racing on a missing row at the same time.
        val countToSave = consciousOpensToday.getOrDefault(packageName, 0)
        if (countToSave > 0) {
            scope.launch {
                val today = todayKey()
                dailyLogRepository.upsertConsciousOpens(challengeId, today, countToSave)
                    .onSuccess {
                        Timber.d("DailyLog: consciousOpens incremented for $challengeId = $countToSave")
                    }
                    .onFailure { e ->
                        Timber.e(e, "OverlayManager: failed to persist consciousOpens for $challengeId")
                    }
            }
        }

        cancelSessionTimer()
        sessionTimerPackage = packageName
        val durationMs = effectiveDuration * 60_000L
        val endTimestamp = System.currentTimeMillis() + durationMs
        sessionPrefs.edit().putLong("$SESSION_END_KEY_PREFIX$packageName", endTimestamp).apply()
        Timber.d("OverlayManager: session timer started for $packageName ($effectiveDuration min, ends at $endTimestamp)")

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
        val today = todayKey()

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
     * Reads remaining budget in milliseconds from Room on every call (source of truth is
     * UsageTrackingService, which writes to Room when a session expires).
     */
    private suspend fun handleTimeBudgetApp(status: DailyLimitStatus, scope: CoroutineScope) {
        val challenge = status.challenge
        val packageName = (challenge.appPackageName ?: "")

        if (failedSessionAppsToday.contains(packageName)) {
            Timber.d("OverlayManager: budget already exhausted for $packageName today — no overlay")
            return
        }

        // Defensive: if a budget session is still active for this package, allow re-entry without overlay.
        // Primary guard is in AppDetectionAccessibilityService; this is the fallback.
        val budgetEndTime = budgetSessionPrefs.getLong(BUDGET_SESSION_END_TIME_KEY, 0L)
        val budgetPkg = budgetSessionPrefs.getString(BUDGET_SESSION_PACKAGE_KEY, null)
        if (budgetEndTime > System.currentTimeMillis() && budgetPkg == packageName) {
            Timber.d("OverlayManager: active budget session for $packageName — skipping overlay")
            AppDetectionAccessibilityService.allowTemporarily(packageName)
            return
        }

        val todayKey = todayKey()
        val totalBudgetMs = (challenge.dailyBudgetMinutes ?: 0) * 60_000L
        val log = dailyLogRepository.getLogForDate(challenge.id, todayKey).getOrNull()
        // Room is the single source of truth for budgetRemainingMs.
        // Only trust it when actual usage has been persisted (budgetUsedMs > 0).
        // If budgetUsedMs = 0, the periodic write hasn't run yet (race with service restart)
        // or the row was created as a placeholder by addOverlayPausedMs — fall back to the
        // full budget so the user is never incorrectly shown "budget exhausted".
        val remainingMs = if (log != null && log.budgetUsedMs > 0L) log.budgetRemainingMs else totalBudgetMs
        val remainingMinutes = (remainingMs / 60_000L).toInt()

        Timber.d("budgetRemaining read from Room: ${remainingMs}ms for ${challenge.id}")
        Timber.d("OverlayManager: budgetRemaining=${remainingMinutes}min (${remainingMs}ms) for $packageName")

        if (remainingMs <= 0L) {
            showBudgetExhaustedOverlay(status, scope)
        } else {
            showBudgetSelectionOverlay(status, remainingMinutes, scope)
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
                    packageName = packageName,
                    appName = challenge.appDisplayName,
                    remainingMinutes = remainingMinutes,
                    onStart = { selectedMinutes ->
                        Timber.d(
                            "OverlayManager: Budget session starting: ${selectedMinutes}min " +
                                    "for ${challenge.appDisplayName} (remaining before=${remainingMinutes}min)"
                        )
                        persistBudgetSession(packageName, selectedMinutes, challenge)
                        challenge.appPackageName?.let {
                            AppDetectionAccessibilityService.allowTemporarily(it)
                        }
                        dismissOverlay()
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
     * Shows the budget-exhausted overlay (Stage 2).
     * Both Group Challenge and Solo Soft Mode: shows [SessionLimitReachedOverlay], app stays blocked.
     * Group Challenge NEVER auto-fails here — Stripe only captured on manual "Aufgeben".
     */
    private fun showBudgetExhaustedOverlay(status: DailyLimitStatus, scope: CoroutineScope) {
        val challenge = status.challenge
        val groupChallengeId = challenge.groupChallengeId
        val packageName = challenge.appPackageName ?: ""
        Timber.d(
            "OverlayManager: BudgetExhausted for ${challenge.appDisplayName} " +
                    "(mode=${challenge.mode}, groupId=$groupChallengeId)"
        )

        if (groupChallengeId != null) {
            // Group Challenge: never auto-fail. Show SessionLimitReachedOverlay (identical to Solo
            // Soft Mode blocking). Stripe only captured on manual "Aufgeben" in Detail screen.
            val composeView = createSessionComposeView(
                onBack = {
                    dismissOverlay()
                    goHome()
                }
            ) {
                DetoxTheme {
                    SessionLimitReachedOverlay(
                        onNo = {
                            dismissOverlay()
                            goHome()
                        }
                    )
                }
            }
            showSessionOverlay(composeView, challenge.id)
            return
        }

        val composeView = createSessionComposeView(
            onBack = {
                dismissOverlay()
                goHome()
            }
        ) {
            DetoxTheme {
                SessionLimitReachedOverlay(
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
     * Persists the active budget session to SharedPreferences so UsageTrackingService can
     * detect expiry and write to Room even if the app is killed during the session.
     */
    private fun persistBudgetSession(
        packageName: String,
        selectedMinutes: Int,
        challenge: com.detox.app.domain.model.Challenge
    ) {
        val now = System.currentTimeMillis()
        budgetSessionPrefs.edit()
            .putLong(BUDGET_SESSION_START_TIME_KEY, now)
            .putLong(BUDGET_SESSION_END_TIME_KEY, now + selectedMinutes * 60_000L)
            .putString(BUDGET_SESSION_CHALLENGE_KEY, challenge.id)
            .putString(BUDGET_SESSION_PACKAGE_KEY, packageName)
            .apply()
        Timber.d(
            "OverlayManager: budget session persisted for $packageName " +
                    "(${selectedMinutes}min, ends at ${now + selectedMinutes * 60_000L})"
        )
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
            dailyLogRepository.getStreakForChallenge(challenge.id, todayKey()).getOrElse { 0 }
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
        val streak = getStreak(status.challenge)
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
                        status.challenge.appPackageName?.let {
                            AppDetectionAccessibilityService.allowTemporarily(it)
                        }
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
        val streak = getStreak(challenge)

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
        val today = todayKey()

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

    // ── Group challenge fail overlay ───────────────────────────────────────────

    /**
     * Called when the current user exceeds their limit in a group challenge.
     * Shows a full-screen fail overlay, calls the Cloud Function to capture their payment,
     * updates the local Room status, and prevents any future overlays for this group.
     */
    private fun handleGroupChallengeFail(
        status: DailyLimitStatus,
        groupId: String,
        packageName: String,
        scope: CoroutineScope,
    ) {
        Timber.d("User failed group challenge %s — entering read only mode", groupId)
        val challenge = status.challenge

        failedGroupChallengeIds.add(groupId)
        TrackedAppEventBus.markPackageFreeForToday(packageName)

        scope.launch {
            val userId = firebaseAuthService.currentUserId()
            if (userId != null) {
                cloudFunctionsService.failGroupParticipant(groupId, userId)
                    .onSuccess { Timber.d("failGroupParticipant succeeded for group %s", groupId) }
                    .onFailure { e -> Timber.e(e, "failGroupParticipant failed for group %s", groupId) }
            }
            // Mark the local shadow challenge as failed
            groupChallengeRepository.finishLocalGroupChallenge(groupId, succeeded = false)
                .onFailure { e -> Timber.e(e, "finishLocalGroupChallenge failed for group %s", groupId) }
        }

        val composeView = createSessionComposeView(
            onBack = {
                dismissOverlay("back")
                goHome()
            }
        ) {
            DetoxTheme {
                GroupChallengeFailOverlay(
                    buyInCents = challenge.amountCents ?: 0,
                    onLeaderboard = {
                        dismissOverlay("leaderboard")
                        TrackedAppEventBus.emitNavigateToGroupDetail(groupId)
                        val launchIntent = context.packageManager
                            .getLaunchIntentForPackage(context.packageName)
                            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
                        if (launchIntent != null) context.startActivity(launchIntent)
                    },
                    onGoHome = {
                        dismissOverlay("go_home")
                        goHome()
                    }
                )
            }
        }
        showOverlay(composeView, challenge.id)
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
            failedGroupChallengeIds.clear()
            TrackedAppEventBus.clearFreePackages()
            sessionTimerJob?.cancel()
            sessionTimerJob = null
            sessionTimerPackage?.let { pkg ->
                sessionPrefs.edit().remove("$SESSION_END_KEY_PREFIX$pkg").apply()
            }
            sessionTimerPackage = null
            budgetSessionPrefs.edit().clear().apply()
            scheduleMidnightReset()
        }, delay)
    }

    // ── Taunt listener + overlay ───────────────────────────────────────────────

    fun startTauntListening(scope: CoroutineScope) {
        val userId = firebaseAuthService.currentUserId() ?: return
        scope.launch {
            groupChallengeRepository.getGroupChallenges().collect { all ->
                val activeGroupIds = all.filter { gc ->
                    gc.status == GroupChallengeStatus.ACTIVE &&
                        gc.participants.any { it.userId == userId }
                }.map { it.groupId }.toSet()

                // Start a listener for each newly active group challenge
                activeGroupIds.filter { !tauntListenerJobs.containsKey(it) }.forEach { groupId ->
                    tauntListenerJobs[groupId] = scope.launch {
                        groupChallengeRepository.observeUnshownTaunts(groupId, userId)
                            .collect { taunts ->
                                taunts.filter { it.id !in processedTauntIds }.forEach { taunt ->
                                    processedTauntIds.add(taunt.id)
                                    Timber.d("Taunt received from ${taunt.fromDisplayName}, showing overlay")
                                    val inTrackedApp = TrackedAppEventBus.currentForegroundPackage.value in
                                        TrackedAppEventBus.trackedPackages.value
                                    if (inTrackedApp) {
                                        showTauntOverlay(taunt.message)
                                    } else {
                                        NotificationHelper.showTauntNotification(context, taunt.message)
                                    }
                                    scope.launch {
                                        groupChallengeRepository.markTauntShown(groupId, taunt.id)
                                    }
                                }
                            }
                    }
                }

                // Cancel listeners for challenges that are no longer active
                tauntListenerJobs.keys.filter { it !in activeGroupIds }.forEach { groupId ->
                    tauntListenerJobs.remove(groupId)?.cancel()
                }
            }
        }
    }

    private fun showTauntOverlay(message: String) {
        mainHandler.post {
            dismissTauntOverlay()
            val composeView = createComposeView {
                DetoxTheme {
                    TauntOverlay(
                        message = message,
                        onDismiss = { mainHandler.post { dismissTauntOverlay() } },
                    )
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP
            }
            try {
                windowManager.addView(composeView, params)
                tauntOverlayView = composeView
                Timber.d("Taunt overlay shown")
            } catch (e: Exception) {
                Timber.e(e, "Failed to show taunt overlay")
                tauntOverlayView = null
            }
        }
    }

    private fun dismissTauntOverlay() {
        tauntOverlayView?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) {}
            tauntOverlayView = null
        }
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
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.OPAQUE
        )

        try {
            val tAdd = android.os.SystemClock.elapsedRealtime()
            windowManager.addView(view, params)
            currentOverlayView = view
            TrackedAppEventBus.setOverlayVisible(true)
            view.requestFocus()
            Timber.d("Blocking chain: overlay added in ${android.os.SystemClock.elapsedRealtime() - tAdd}ms (challengeId=$challengeId)")
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
                val today = todayKey()
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
     * Returns the current streak for [challenge]:
     *  - No-end-date Soft challenges: days since startDate.
     *  - Otherwise: consecutive completed days (before today) where the limit was NOT exceeded.
     */
    private suspend fun getStreak(challenge: Challenge): Int =
        getChallengeStreakUseCase(challenge)

    /**
     * Marks a Soft Mode challenge as FAILED, syncs to Firestore, suppresses overlays for the
     * tracked package(s) for the rest of the day, and launches the fail-result screen.
     */
    private suspend fun markSoftChallengeFailed(challenge: Challenge, reason: String) {
        val streak = getStreak(challenge)
        Timber.i("Soft Mode failed: challengeId=${challenge.id} streak=$streak days reason=$reason")

        challengeRepository.updateChallengeStatus(challenge.id, ChallengeStatus.FAILED)
            .onFailure { Timber.e(it, "markSoftChallengeFailed: failed to update status for ${challenge.id}") }

        challenge.appPackageName?.let {
            failedSessionAppsToday.add(it)
            exceededAppsToday.add(it)
            TrackedAppEventBus.markPackageFreeForToday(it)
            AppDetectionAccessibilityService.allowTemporarily(it)
        }
        challenge.appPackageNames.forEach {
            failedSessionAppsToday.add(it)
            exceededAppsToday.add(it)
            TrackedAppEventBus.markPackageFreeForToday(it)
        }

        analyticsService.logLimitExceeded("soft_${reason}", challenge.appPackageName ?: "")

        TrackedAppEventBus.emitNavigateToSoftFailResult(challenge.id, streak)
        val launchIntent = Intent(context, com.detox.app.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(launchIntent)
        dismissOverlay()
    }

    private fun todayKey(): Long = DateUtils.todayKey()

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
