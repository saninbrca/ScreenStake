package com.detox.app.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object TrackedAppEventBus {

    /**
     * Carry-all for per-challenge schedule constraints so [AppDetectionAccessibilityService]
     * can gate overlays without hitting the database.
     */
    data class ScheduleInfo(
        val scheduleStartTime: String?,  // "HH:mm", null = always active
        val scheduleEndTime: String?,    // "HH:mm", null = always active
        val activeDays: List<String>     // ["MON","TUE",...], empty = every day
    )

    private val _appOpenEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val appOpenEvents: SharedFlow<String> = _appOpenEvents.asSharedFlow()

    private val _trackedPackages = MutableStateFlow<Set<String>>(emptySet())
    val trackedPackages: StateFlow<Set<String>> = _trackedPackages.asStateFlow()

    /**
     * The package name currently visible in the foreground, updated on every
     * [TYPE_WINDOW_STATE_CHANGED] event regardless of whether the package is tracked.
     * Used by [OverlayManager] to cancel the session countdown timer when the
     * tracked app leaves the foreground.
     */
    private val _currentForegroundPackage = MutableStateFlow<String?>(null)
    val currentForegroundPackage: StateFlow<String?> = _currentForegroundPackage.asStateFlow()

    /**
     * Packages that have been freed for the rest of the day — either because the user
     * accepted the Stage 2 consequence (session-limit challenges) or the challenge
     * evaluation ended. No overlay will be shown for these packages until midnight.
     */
    private val _freedPackagesToday = MutableStateFlow<Set<String>>(emptySet())
    val freedPackagesToday: StateFlow<Set<String>> = _freedPackagesToday.asStateFlow()

    // ── Website / adult blocking ───────────────────────────────────────────────

    /**
     * True when at least one active challenge has blockAdultContent = true.
     * Written by [UsageTrackingService]; read by [AppDetectionAccessibilityService]
     * as a fast in-memory flag — no Room query on every accessibility event.
     */
    private val _adultBlockingActive = MutableStateFlow(false)
    val adultBlockingActive: StateFlow<Boolean> = _adultBlockingActive.asStateFlow()

    /** All blocked domains aggregated across active challenges. */
    private val _blockedDomains = MutableStateFlow<Set<String>>(emptySet())
    val blockedDomains: StateFlow<Set<String>> = _blockedDomains.asStateFlow()

    /**
     * Fires when the AccessibilityService detects a blocked domain in a browser address bar.
     * Carries the matched domain string.
     */
    private val _urlBlockedEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val urlBlockedEvents: SharedFlow<String> = _urlBlockedEvents.asSharedFlow()

    /**
     * Fires when the AccessibilityService blocks an adult domain. The service has already
     * redirected the browser to about:blank (goHome fallback); [OverlayManager] shows an
     * explanatory overlay so the redirect doesn't read as a browser crash. Carries the
     * matched host.
     */
    private val _adultBlockedEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val adultBlockedEvents: SharedFlow<String> = _adultBlockedEvents.asSharedFlow()

    /** Domains freed for the rest of the day after the user tapped "Visit anyway". */
    private val _freedDomainsToday = MutableStateFlow<Set<String>>(emptySet())
    val freedDomainsToday: StateFlow<Set<String>> = _freedDomainsToday.asStateFlow()

    // ── Schedule info ──────────────────────────────────────────────────────────

    /**
     * Maps each tracked package name to the schedule of its active challenge so that
     * [AppDetectionAccessibilityService] can skip overlays outside the active window.
     */
    private val _packageSchedules = MutableStateFlow<Map<String, ScheduleInfo>>(emptyMap())
    val packageSchedules: StateFlow<Map<String, ScheduleInfo>> = _packageSchedules.asStateFlow()

    fun emitAppOpen(packageName: String) {
        _appOpenEvents.tryEmit(packageName)
    }

    fun updateTrackedPackages(packages: Set<String>) {
        _trackedPackages.value = packages
    }

    fun updateForegroundPackage(packageName: String) {
        _currentForegroundPackage.value = packageName
    }

    fun markPackageFreeForToday(packageName: String) {
        _freedPackagesToday.value = _freedPackagesToday.value + packageName
    }

    fun updateAdultBlockingActive(active: Boolean) {
        _adultBlockingActive.value = active
    }

    fun updateBlockedDomains(domains: Set<String>) {
        _blockedDomains.value = domains
    }

    fun emitUrlBlocked(domain: String) {
        _urlBlockedEvents.tryEmit(domain)
    }

    fun emitAdultBlocked(domain: String) {
        _adultBlockedEvents.tryEmit(domain)
    }

    fun markDomainFreeForToday(domain: String) {
        _freedDomainsToday.value = _freedDomainsToday.value + domain
    }

    fun updatePackageSchedules(schedules: Map<String, ScheduleInfo>) {
        _packageSchedules.value = schedules
    }

    // ── Overlay visibility ─────────────────────────────────────────────────────

    /** True while any overlay is visible in the WindowManager. Set by [OverlayManager]. */
    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    /**
     * Fires when the home screen (launcher) comes to the foreground while an overlay is visible.
     * Emitted by [AppDetectionAccessibilityService]; consumed by [OverlayManager] to dismiss.
     */
    private val _homeDetectedEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val homeDetectedEvents: SharedFlow<Unit> = _homeDetectedEvents.asSharedFlow()

    fun setOverlayVisible(visible: Boolean) {
        _overlayVisible.value = visible
    }

    fun emitHomeDetected() {
        _homeDetectedEvents.tryEmit(Unit)
    }

    // ── Group challenge session limit info ────────────────────────────────────

    /**
     * Maps each tracked package name to the current session-limit state for its active
     * group challenge. Populated by [UsageTrackingService] from Firestore participant data
     * and incremented by [OverlayManager] on every conscious "Ja öffnen" tap.
     * Read synchronously by [AppDetectionAccessibilityService] on every app-open event.
     */
    data class GroupSessionInfo(val opensToday: Int, val limitValueSessions: Int)

    private val _groupSessionInfos = MutableStateFlow<Map<String, GroupSessionInfo>>(emptyMap())
    val groupSessionInfos: StateFlow<Map<String, GroupSessionInfo>> = _groupSessionInfos.asStateFlow()

    fun updateGroupSessionInfos(infos: Map<String, GroupSessionInfo>) {
        _groupSessionInfos.value = infos
    }

    fun incrementGroupSessionOpens(packageName: String) {
        val current = _groupSessionInfos.value[packageName] ?: return
        _groupSessionInfos.value = _groupSessionInfos.value + (packageName to current.copy(opensToday = current.opensToday + 1))
    }

    // ── Group-challenge fail set ───────────────────────────────────────────────

    /**
     * Packages whose group challenge the current user has already failed today.
     * No overlay is shown for these — the app is unblocked for the rest of the day.
     */
    private val _failedPackagesToday = MutableStateFlow<Set<String>>(emptySet())
    val failedPackagesToday: StateFlow<Set<String>> = _failedPackagesToday.asStateFlow()

    fun markPackagesFailedForUser(packages: Set<String>) {
        _failedPackagesToday.value = _failedPackagesToday.value + packages
    }

    fun clearFreePackages() {
        _freedPackagesToday.value = emptySet()
        _freedDomainsToday.value = emptySet()
        _failedPackagesToday.value = emptySet()
    }

    // ── In-app navigation requests from overlay ────────────────────────────────

    /**
     * Fires when the user taps "Leaderboard ansehen" on the group challenge fail overlay.
     * Replay=1 so the event survives the brief delay before MainScreen collects it.
     */
    private val _navigateToGroupDetail = MutableSharedFlow<String>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val navigateToGroupDetail: SharedFlow<String> = _navigateToGroupDetail.asSharedFlow()

    fun emitNavigateToGroupDetail(groupId: String) {
        _navigateToGroupDetail.tryEmit(groupId)
    }

    fun clearGroupDetailNavigation() {
        _navigateToGroupDetail.resetReplayCache()
    }

    /**
     * Fires when a Soft Mode challenge has just been marked FAILED from an overlay.
     * Payload is (challengeId, streakDays). Replay=1 so the event survives until MainScreen is up.
     */
    private val _navigateToSoftFailResult = MutableSharedFlow<Pair<String, Int>>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val navigateToSoftFailResult: SharedFlow<Pair<String, Int>> = _navigateToSoftFailResult.asSharedFlow()

    fun emitNavigateToSoftFailResult(challengeId: String, streak: Int) {
        _navigateToSoftFailResult.tryEmit(challengeId to streak)
    }

    fun clearSoftFailResultNavigation() {
        _navigateToSoftFailResult.resetReplayCache()
    }

    // ── Notification deep-link navigation ──────────────────────────────────────
    // Fired when the user taps a notification. Replay=1 so the event survives until
    // MainScreen collects it (e.g. when the app was killed and is still starting up).

    private val _navigateToDashboard = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val navigateToDashboard: SharedFlow<Unit> = _navigateToDashboard.asSharedFlow()

    fun emitNavigateToDashboard() {
        _navigateToDashboard.tryEmit(Unit)
    }

    fun clearDashboardNavigation() {
        _navigateToDashboard.resetReplayCache()
    }

    private val _navigateToProfile = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val navigateToProfile: SharedFlow<Unit> = _navigateToProfile.asSharedFlow()

    fun emitNavigateToProfile() {
        _navigateToProfile.tryEmit(Unit)
    }

    fun clearProfileNavigation() {
        _navigateToProfile.resetReplayCache()
    }

    private val _navigateToChallengeDetail = MutableSharedFlow<String>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val navigateToChallengeDetail: SharedFlow<String> = _navigateToChallengeDetail.asSharedFlow()

    fun emitNavigateToChallengeDetail(challengeId: String) {
        _navigateToChallengeDetail.tryEmit(challengeId)
    }

    fun clearChallengeDetailNavigation() {
        _navigateToChallengeDetail.resetReplayCache()
    }

    private val _navigateToHistoryDetail = MutableSharedFlow<String>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val navigateToHistoryDetail: SharedFlow<String> = _navigateToHistoryDetail.asSharedFlow()

    fun emitNavigateToHistoryDetail(challengeId: String) {
        _navigateToHistoryDetail.tryEmit(challengeId)
    }

    fun clearHistoryDetailNavigation() {
        _navigateToHistoryDetail.resetReplayCache()
    }
}
