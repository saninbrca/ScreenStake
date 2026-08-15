package com.finite.focus.presentation.screens.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finite.focus.BuildConfig
import com.finite.focus.data.local.db.DetoxDatabase
import com.finite.focus.data.local.db.entity.ChallengeEntity
import com.finite.focus.data.repository.AppConfigRepository
import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.model.DailyLog
import com.finite.focus.domain.model.DailyStats
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.domain.repository.DailyLogRepository
import com.finite.focus.domain.usecase.EndExpiredGroupChallengesUseCase
import com.finite.focus.domain.usecase.GetChallengeStreakUseCase
import com.finite.focus.domain.usecase.GetDailyStatsUseCase
import com.finite.focus.domain.usecase.SettleEndedSoftChallengesUseCase
import com.finite.focus.domain.usecase.SyncUserDataUseCase
import com.finite.focus.service.TrackedAppEventBus
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.finite.focus.util.DateUtils
import com.finite.focus.util.ErrorMessages
import timber.log.Timber
import javax.inject.Inject

private const val KEY_UPDATE_DISMISSED_AT = "update_banner_dismissed_at"
private const val UPDATE_BANNER_SNOOZE_MS = 3L * 24 * 60 * 60 * 1000 // 3 days

private const val BROADCAST_PREFS = "detox_broadcast"
private const val KEY_BROADCAST_LAST_SEEN = "last_seen_broadcast_id"

/**
 * Trailing-edge debounce for the Dashboard's Room observer. A sync write-burst (syncUserData
 * REPLACE/upsert of many today rows) fires many rapid emissions; debouncing collapses them into a
 * single refreshStats() after the burst settles, so the UI no longer renders intermediate zeroed
 * frames ("all cards blank then refill"). Long enough to swallow a tight write burst, short enough
 * to be imperceptible for a genuine single update. Display-only — does not change reads/writes.
 */
private const val DASHBOARD_REFRESH_DEBOUNCE_MS = 250L

/**
 * One result surface waiting to be shown, fully resolved and ready to render.
 *
 * The Dashboard drains a QUEUE of these on dismiss (see [DashboardViewModel.resultQueue]). Before
 * this existed the Dashboard could show at most ONE result per visit — it read a single row with
 * `LIMIT 1`, dismissing rendered nothing new, and the only way to reach the next result was a tab
 * round-trip that recreated the whole ViewModel. A user whose challenges all settled at once (the
 * normal case after a reinstall, or after a permission-deadline sweep) had to leave and re-enter
 * the Dashboard once per challenge to see them.
 *
 * The Soft Mode loss is NOT represented here on purpose: it is a full screen reached by navigation,
 * not a dialog, so it is dispatched directly in [DashboardViewModel.presentNextResult].
 */
/**
 * Which result surface a terminal challenge belongs on. Split out as a pure function so the mapping
 * that decides whether a user is shown green, red, neutral, or a full-screen loss can be tested
 * without standing up a ViewModel.
 */
internal enum class ResultSurface {
    /** Green [ChallengeSuccessDialog]. */
    WIN,
    /** Red [ChallengeFailedDialog] — solo Hard Mode. */
    HARD_LOSS,
    /** Full-screen `SoftFailResultScreen`, reached by navigation rather than a dialog. */
    SOFT_LOSS_SCREEN,
    /** Neutral [ChallengeUnverifiedDialog]. */
    UNVERIFIED,
    /** Not a result-bearing state — nothing to show. */
    NONE,
}

/**
 * Maps a challenge's terminal state to its result surface.
 *
 * `ACTIVE` and `ENDED` map to [ResultSurface.NONE] deliberately: `ENDED` is the group-local
 * "awaiting settlement" state, whose outcome is not known yet and which has its own surfaces.
 */
internal fun resultSurfaceFor(status: ChallengeStatus, mode: ChallengeMode): ResultSurface = when {
    status == ChallengeStatus.COMPLETED -> ResultSurface.WIN
    status == ChallengeStatus.ENDED_UNVERIFIED -> ResultSurface.UNVERIFIED
    status == ChallengeStatus.FAILED && mode == ChallengeMode.SOFT -> ResultSurface.SOFT_LOSS_SCREEN
    status == ChallengeStatus.FAILED -> ResultSurface.HARD_LOSS
    else -> ResultSurface.NONE
}

sealed interface PendingResult {
    val challenge: Challenge

    /** Green win dialog ([ChallengeSuccessDialog]) — Soft or Hard. */
    data class Win(
        override val challenge: Challenge,
        val allLogs: List<DailyLog>,
        val streak: Int,
    ) : PendingResult

    /** Unified RED loss dialog ([ChallengeFailedDialog]) — solo Hard Mode. */
    data class HardLoss(
        override val challenge: Challenge,
        val allLogs: List<DailyLog>,
    ) : PendingResult

    /** Neutral acknowledgement ([ChallengeUnverifiedDialog]) — outcome could not be established. */
    data class Unverified(
        override val challenge: Challenge,
    ) : PendingResult
}

/** A remote admin broadcast shown once on the Dashboard. */
data class BroadcastMessage(
    val id: String,
    val title: String,
    val message: String
)

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Success(
        val activeChallenges: List<DailyStats>
    ) : DashboardUiState
    data object Empty : DashboardUiState
    data class Error(val message: String) : DashboardUiState
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val getDailyStatsUseCase: GetDailyStatsUseCase,
    private val syncUserDataUseCase: SyncUserDataUseCase,
    private val challengeRepository: ChallengeRepository,
    private val dailyLogRepository: DailyLogRepository,
    private val database: DetoxDatabase,
    private val getChallengeStreakUseCase: GetChallengeStreakUseCase,
    private val appConfigRepository: AppConfigRepository,
    private val firestore: FirebaseFirestore,
    private val settleEndedSoftChallengesUseCase: SettleEndedSoftChallengesUseCase,
    private val endExpiredGroupChallengesUseCase: EndExpiredGroupChallengesUseCase,
) : ViewModel() {

    // ── Soft update banner ──────────────────────────────────────────────────────
    private val updatePrefs =
        appContext.getSharedPreferences("detox_update_banner", Context.MODE_PRIVATE)

    /** URL opened by the "Aktualisieren" button on the soft-update banner. */
    val updateUrl: StateFlow<String> = MutableStateFlow("").also { flow ->
        viewModelScope.launch {
            appConfigRepository.config.collect { flow.value = it.updateUrl }
        }
    }.asStateFlow()

    private val _showUpdateBanner = MutableStateFlow(false)
    /** True when a newer version is available, not force-blocking, and not recently dismissed. */
    val showUpdateBanner: StateFlow<Boolean> = _showUpdateBanner.asStateFlow()

    // ── Admin broadcast ───────────────────────────────────────────────────────────
    private val broadcastPrefs =
        appContext.getSharedPreferences(BROADCAST_PREFS, Context.MODE_PRIVATE)

    private val _broadcast = MutableStateFlow<BroadcastMessage?>(null)
    /** Non-null while an unseen active broadcast should be shown (once). Null = hidden. */
    val broadcast: StateFlow<BroadcastMessage?> = _broadcast.asStateFlow()

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    // True once the authoritative first load (loadStats, after syncJob.join + refreshStats) has
    // established the initial Ui state. Until then the Room DailyLog observer must NOT publish a
    // state — a mid-sync emission (challenge row written, opens row not yet) would otherwise paint
    // a partial/zero-valued Success before the real numbers arrive. loadStats owns the first state.
    // Only touched from viewModelScope (Main dispatcher), so no synchronization is needed.
    private var initialLoadComplete = false

    /**
     * The result surface currently on screen, or null when none is showing.
     * Dismissing it pops the next one off [resultQueue] — see [dismissCurrentResult].
     */
    private val _currentResult = MutableStateFlow<PendingResult?>(null)
    val currentResult: StateFlow<PendingResult?> = _currentResult.asStateFlow()

    /**
     * Terminal challenges still waiting for their result surface, oldest first.
     *
     * Rebuilt from Room by [refreshResultQueue] and drained by [presentNextResult]. Only ever
     * touched from `viewModelScope` (Main dispatcher), so no synchronisation is needed — same
     * stance as [initialLoadComplete].
     */
    private var resultQueue: ArrayDeque<Challenge> = ArrayDeque()

    /**
     * The day the DailyLog observer is currently subscribed to.
     *
     * A StateFlow rather than a value captured once at construction, and that is the entire point:
     * the observer used to pin `todayKey()` at ViewModel-construction time and keep it forever, so
     * an app left open across midnight stayed subscribed to YESTERDAY's key — new-day writes emitted
     * nothing, `refreshStats()` never ran, and the cards showed stale data until the ViewModel
     * happened to be recreated. Re-keying this flow re-subscribes the observer to the new day.
     *
     * Updated from two independent places, deliberately: [observeDayRollover] (a timer for the app
     * sitting open through midnight) and [loadStats] (a RESUMED backstop, because coroutine `delay`
     * is not guaranteed to fire while the device is dozing).
     */
    private val currentDayKey = MutableStateFlow(DateUtils.todayKey())

    /** Failed Hard Mode challenges with an active redemption window. Empty = banner hidden. */
    private val _redemptionChallenges = MutableStateFlow<List<ChallengeEntity>>(emptyList())
    val redemptionChallenges: StateFlow<List<ChallengeEntity>> = _redemptionChallenges.asStateFlow()

    /** Set to true when the user dismisses the redemption banner for this session. */
    private var redemptionBannerDismissed = false

    // Kicked off immediately on ViewModel creation. loadStats() awaits this before
    // reading Room, ensuring re-login always sees up-to-date data. On subsequent
    // loadStats() calls (tab switches, etc.) join() returns instantly.
    private val syncJob: Job = viewModelScope.launch {
        Timber.d("Dashboard: starting Firestore sync")
        syncUserDataUseCase()
            .onSuccess { Timber.d("Dashboard: sync completed") }
            .onFailure { e -> Timber.w(e, "Dashboard: sync failed (offline?)") }
    }

    init {
        observeDailyLogChanges()
        observeDayRollover()
        observeUpdateBanner()
        loadLatestBroadcast()
    }

    /**
     * Reads the newest active broadcast from `broadcasts` and surfaces it once. The last-seen
     * broadcast id is stored in SharedPreferences so a given message shows only a single time;
     * a brand-new active broadcast (different id) re-triggers. Fail-open: any read error simply
     * leaves the banner hidden — a broadcast is never critical.
     */
    private fun loadLatestBroadcast() {
        viewModelScope.launch {
            try {
                val snap = firestore.collection("broadcasts")
                    .whereEqualTo("active", true)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(1)
                    .get()
                    .await()
                val doc = snap.documents.firstOrNull() ?: return@launch
                val lastSeen = broadcastPrefs.getString(KEY_BROADCAST_LAST_SEEN, null)
                if (doc.id == lastSeen) return@launch // already acknowledged
                _broadcast.value = BroadcastMessage(
                    id = doc.id,
                    title = doc.getString("title") ?: "",
                    message = doc.getString("message") ?: ""
                )
            } catch (e: Exception) {
                Timber.w(e, "Broadcast load failed (ignored)")
            }
        }
    }

    /** Acknowledges the current broadcast; it will not show again (per-id, stored in prefs). */
    fun dismissBroadcast() {
        _broadcast.value?.let { b ->
            broadcastPrefs.edit().putString(KEY_BROADCAST_LAST_SEEN, b.id).apply()
        }
        _broadcast.value = null
    }

    /**
     * Shows a dismissible "update available" banner when the installed version is older than
     * [AppConfig.latestVersionCode]. A force-update (below minVersionCode) is handled by the
     * blocking ForceUpdateScreen before the user ever reaches the Dashboard, so here we only
     * surface the *soft* prompt. Dismissal is remembered for [UPDATE_BANNER_SNOOZE_MS].
     */
    private fun observeUpdateBanner() {
        viewModelScope.launch {
            appConfigRepository.config.collect { config ->
                val newerAvailable = BuildConfig.VERSION_CODE < config.latestVersionCode
                val dismissedAt = updatePrefs.getLong(KEY_UPDATE_DISMISSED_AT, 0L)
                val snoozed =
                    System.currentTimeMillis() - dismissedAt < UPDATE_BANNER_SNOOZE_MS
                _showUpdateBanner.value = newerAvailable && !snoozed
            }
        }
    }

    /** Dismisses the soft-update banner; it will not reappear for 3 days. */
    fun dismissUpdateBanner() {
        updatePrefs.edit()
            .putLong(KEY_UPDATE_DISMISSED_AT, System.currentTimeMillis())
            .apply()
        _showUpdateBanner.value = false
    }

    fun loadStats() {
        viewModelScope.launch {
            // Only show Loading for the very first load. On a RESUME (already showing Success) keep
            // the existing cards on screen and let refreshStats() update them in place — no spinner
            // flash and no re-run of the card entrance animation on every reopen.
            if (_uiState.value !is DashboardUiState.Success) {
                _uiState.value = DashboardUiState.Loading
            }
            // RESUMED day backstop: the app may have been in the background across midnight, and a
            // dozing device is not guaranteed to have run observeDayRollover's timer. Re-keying here
            // costs nothing when the day is unchanged (StateFlow drops equal values).
            currentDayKey.value = DateUtils.todayKey()
            // DELAY FIX (generalised): surface everything ALREADY terminal in Room without waiting
            // on the network. This used to cover only a device-detected Hard Mode loss; the queue
            // makes it apply to every result kind for free. The settle below can only ADD results,
            // never retract one, so showing these early is safe — and it deliberately does NOT
            // change the settle-needs-sync dependency, which stays exactly where it was.
            refreshResultQueue()
            presentNextResult()
            // Wait for the one-shot sync to finish before reading Room.
            // If it already completed this is a no-op.
            syncJob.join()
            // On-app-open backstop: finalise any fixed-end Soft challenge whose endDate passed while
            // the app was closed (EMUI throttles the periodic worker). Runs BEFORE refreshStats and
            // the completed/failed dialog checks below, so a just-finalised challenge drops off the
            // active cards and surfaces its success/fail dialog this same session. Open-ended
            // challenges are never touched (they run indefinitely).
            settleEndedSoftChallengesUseCase()
            // Same backstop for GROUP challenges, and deliberately not conditional on the sync above
            // having reached the network: a group challenge past its end date stops enforcing from
            // the local clock alone. Money-free — it never settles, captures, refunds or deletes.
            endExpiredGroupChallengesUseCase()
            refreshStats()
            // First authoritative state is now set: the Room observer may update it from here on.
            initialLoadComplete = true

            // Re-read after the sync + settle: this picks up challenges the settle just finalised
            // AND server-detected losses reconciled active→failed during the sync. Anything already
            // surfaced above is excluded automatically — presenting marks `completionShown`, which
            // is what the query filters on.
            refreshResultQueue()
            presentNextResult()

            if (!redemptionBannerDismissed) {
                val now = System.currentTimeMillis()
                val available = database.challengeDao().getChallengesWithRedemptionAvailable(now)
                _redemptionChallenges.value = available
                Timber.d("Dashboard: ${available.size} challenge(s) with redemption available")
            }
        }
    }

    /**
     * Rebuilds [resultQueue] from Room, oldest first.
     *
     * A full rebuild rather than an append, and it is safe precisely because presenting a result
     * marks `completionShown`: the query therefore never returns anything already shown or on
     * screen, so the rebuild is exactly "everything still owed, in chronological order". That also
     * means calling it repeatedly (early, then again after the settle) can never duplicate an entry.
     *
     * A read failure leaves the existing queue untouched — a transient Room error must not silently
     * discard results the user is still owed.
     */
    private suspend fun refreshResultQueue() {
        challengeRepository.getUnshownTerminalChallenges()
            .onSuccess { pending ->
                resultQueue = ArrayDeque(pending)
                if (pending.isNotEmpty()) {
                    Timber.d("Dashboard: ${pending.size} unshown result(s) queued — ${pending.map { it.id }}")
                }
            }
            .onFailure { e -> Timber.w(e, "Dashboard: failed to read unshown results — keeping current queue") }
    }

    /**
     * Pops the next result off [resultQueue] and presents it. No-op while one is already on screen.
     *
     * `completionShown` is written ON SHOW, never on dismiss — a process kill or EMUI swipe-kill
     * between showing and dismissing must not re-pop the same result on the next launch. The
     * dismiss-time write in [dismissCurrentResult] stays as an idempotent backstop.
     *
     * The Soft Mode loss is dispatched by NAVIGATION (it is a full screen, not a dialog) and stops
     * the drain: the user is leaving the Dashboard, so anything still queued would be rendering
     * behind a screen they can't see. The remaining results resume on the next RESUMED pass, which
     * rebuilds the queue from Room — nothing is lost, because unshown results are still unshown.
     */
    private suspend fun presentNextResult() {
        if (_currentResult.value != null) return
        val winPrefs = appContext.getSharedPreferences("detox_win_popup", Context.MODE_PRIVATE)

        while (resultQueue.isNotEmpty()) {
            val challenge = resultQueue.removeFirst()

            val presentation = when (resultSurfaceFor(challenge.status, challenge.mode)) {
                // Soft Mode loss → its own full screen. Mark shown, navigate, and stop draining.
                ResultSurface.SOFT_LOSS_SCREEN -> {
                    Timber.d("Dashboard: unseen failed Soft Mode challenge — ${challenge.id}")
                    markResultShown(challenge.id)
                    TrackedAppEventBus.emitNavigateToSoftFailResult(
                        challenge.id, getChallengeStreakUseCase(challenge)
                    )
                    return
                }

                ResultSurface.WIN -> {
                    // Legacy belt-and-braces gate kept from the pre-queue code. A row flagged here
                    // but not in Room is stale state from an older build: mark it shown so it drops
                    // out of the query for good instead of being re-queued on every pass.
                    if (winPrefs.getBoolean("win_shown_${challenge.id}", false)) {
                        markResultShown(challenge.id)
                        continue
                    }
                    winPrefs.edit().putBoolean("win_shown_${challenge.id}", true).apply()
                    PendingResult.Win(
                        challenge = challenge,
                        allLogs = dailyLogRepository.getLogsForChallengeOnce(challenge.id),
                        streak = getChallengeStreakUseCase(challenge),
                    )
                }

                ResultSurface.UNVERIFIED -> PendingResult.Unverified(challenge)

                ResultSurface.HARD_LOSS -> PendingResult.HardLoss(
                    challenge = challenge,
                    allLogs = dailyLogRepository.getLogsForChallengeOnce(challenge.id),
                )

                // The query only returns result-bearing statuses; anything else is a data
                // inconsistency. Skip it rather than render an empty dialog.
                ResultSurface.NONE -> {
                    Timber.w("Dashboard: unexpected status ${challenge.status} queued for ${challenge.id} — skipping")
                    continue
                }
            }

            Timber.d("Dashboard: presenting ${presentation::class.simpleName} for ${challenge.id} (reason=${challenge.failReason})")
            _currentResult.value = presentation
            markResultShown(challenge.id)
            return
        }
    }

    private suspend fun markResultShown(challengeId: String) {
        challengeRepository.markCompletionShown(challengeId)
            .onFailure { e -> Timber.e(e, "Dashboard: failed to mark completionShown for $challengeId") }
    }

    /**
     * Re-subscribes the DailyLog observer when the calendar day changes while the app sits open.
     *
     * Without this the Dashboard silently stops updating at midnight: the observer stays bound to
     * yesterday's day key, so today's writes emit nothing. It used to be masked by an accident —
     * the bottom nav recreated the ViewModel on every tab return, re-pinning the day — but that
     * mask disappears the moment results stop depending on ViewModel recreation, and the bug was
     * always real for anyone who simply left the app on the Dashboard overnight.
     *
     * Timer-based so it fires WITHOUT any user interaction; [loadStats] re-keys as well to cover a
     * dozing device, where `delay` may not fire on time.
     */
    private fun observeDayRollover() {
        viewModelScope.launch {
            while (isActive) {
                // Never busy-spin: a clock landing a hair before midnight re-arms for ~1s.
                val waitMs = (DateUtils.nextMidnightTimestamp() - System.currentTimeMillis())
                    .coerceAtLeast(1_000L)
                delay(waitMs)
                val newDay = DateUtils.todayKey()
                if (currentDayKey.value != newDay) {
                    Timber.i("Dashboard: day rollover — re-keying DailyLog observer to $newDay")
                    currentDayKey.value = newDay
                }
            }
        }
    }

    /**
     * Observes the CURRENT day's DailyLog rows in Room. Whenever a conscious open is persisted
     * (or any other intra-day write happens), Room emits a new list and we re-query stats
     * without showing a Loading spinner — the existing data stays visible while it updates.
     *
     * Keyed off [currentDayKey] via `flatMapLatest` rather than a day captured once at construction:
     * when the day rolls over, the old day's subscription is cancelled and a new one starts, so the
     * Dashboard follows the user into the new day instead of freezing on yesterday's rows.
     *
     * `drop(1)` skips only the very FIRST emission of the merged stream, which [loadStats] already
     * owns. It does not swallow day-change emissions: those arrive later, and refreshing on them is
     * exactly the point — that emission is what repaints the cards at midnight.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class) // debounce(Long)/flatMapLatest in coroutines 1.7.3
    private fun observeDailyLogChanges() {
        viewModelScope.launch {
            currentDayKey
                .flatMapLatest { day ->
                    Timber.d("Dashboard: observing DailyLogs for day=$day")
                    dailyLogRepository.observeLogsForDate(day)
                }
                .drop(1)
                // Collapse a sync write-burst into ONE trailing recompute (after 250ms of quiet)
                // so the cards don't flicker through intermediate zeroed frames. Trailing-edge:
                // the latest emission always wins, so the final state is never dropped.
                .debounce(DASHBOARD_REFRESH_DEBOUNCE_MS)
                .collect { logs ->
                    // Don't let the observer publish the FIRST state — loadStats (post syncJob.join)
                    // owns it. Before that, a mid-sync emission could paint a partial/zero card.
                    if (!initialLoadComplete) {
                        Timber.d("Dashboard: DailyLog changed before initial load — skipping (loadStats owns first state)")
                        return@collect
                    }
                    Timber.d("Dashboard: DailyLog changed (${logs.size} rows) — refreshing stats")
                    refreshStats()
                }
        }
    }

    private suspend fun refreshStats() {
        getDailyStatsUseCase().fold(
            onSuccess = { stats ->
                Timber.d("Dashboard loaded: ${stats.size} challenges, first challenge opens=${stats.firstOrNull()?.todayOpens}")
                if (stats.isEmpty()) {
                    _uiState.value = DashboardUiState.Empty
                } else {
                    _uiState.value = DashboardUiState.Success(
                        activeChallenges = stats
                    )
                }
            },
            onFailure = { error ->
                _uiState.value = DashboardUiState.Error(
                    ErrorMessages.from(appContext, error)
                )
            }
        )
    }

    fun dismissRedemptionBanner() {
        redemptionBannerDismissed = true
        _redemptionChallenges.value = emptyList()
    }

    /** "Im Verlauf ansehen" CTA: dismisses the win dialog and deep-links to its history detail. */
    fun openSuccessChallengeHistory() {
        val challengeId = (_currentResult.value as? PendingResult.Win)?.challenge?.id ?: return
        dismissCurrentResult()
        TrackedAppEventBus.emitNavigateToHistoryDetail(challengeId)
    }

    /**
     * Dismisses the result on screen and immediately presents the next queued one — the drain step
     * that lets several results be seen in a single Dashboard visit instead of one per tab
     * round-trip.
     *
     * The shown-markers were already written ON SHOW ([presentNextResult]); the writes here are
     * idempotent backstops, kept so a marker can still land if the on-show write failed.
     */
    fun dismissCurrentResult() {
        val dismissed = _currentResult.value ?: return
        _currentResult.value = null
        viewModelScope.launch {
            if (dismissed is PendingResult.Win) {
                appContext.getSharedPreferences("detox_win_popup", Context.MODE_PRIVATE)
                    .edit().putBoolean("win_shown_${dismissed.challenge.id}", true).apply()
            }
            markResultShown(dismissed.challenge.id)
            presentNextResult()
        }
    }
}
