package com.detox.app.presentation.screens.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.BuildConfig
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.repository.AppConfigRepository
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.DailyStats
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.DailyLogRepository
import com.detox.app.domain.usecase.GetChallengeStreakUseCase
import com.detox.app.domain.usecase.GetDailyStatsUseCase
import com.detox.app.domain.usecase.SyncUserDataUseCase
import com.detox.app.service.TrackedAppEventBus
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.detox.app.util.DateUtils
import timber.log.Timber
import javax.inject.Inject

private const val KEY_UPDATE_DISMISSED_AT = "update_banner_dismissed_at"
private const val UPDATE_BANNER_SNOOZE_MS = 3L * 24 * 60 * 60 * 1000 // 3 days

private const val BROADCAST_PREFS = "detox_broadcast"
private const val KEY_BROADCAST_LAST_SEEN = "last_seen_broadcast_id"

data class SuccessDialogState(
    val challenge: Challenge,
    val allLogs: List<DailyLog>,
    val streak: Int
)

/** State backing the unified RED loss dialog ([ChallengeFailedDialog]). */
data class FailedDialogState(
    val challenge: Challenge,
    val allLogs: List<DailyLog>
)

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

    /** Non-null while the success dialog should be shown (Soft or Hard Mode). Null = hidden. */
    private val _successDialogChallengeId = MutableStateFlow<String?>(null)
    val successDialogChallengeId: StateFlow<String?> = _successDialogChallengeId.asStateFlow()

    private val _successDialogState = MutableStateFlow<SuccessDialogState?>(null)
    val successDialogState: StateFlow<SuccessDialogState?> = _successDialogState.asStateFlow()

    /** Non-null while the Hard Mode loss dialog should be shown. Null = dialog hidden. */
    private val _failedHardChallenge = MutableStateFlow<FailedDialogState?>(null)
    val failedHardChallenge: StateFlow<FailedDialogState?> = _failedHardChallenge.asStateFlow()

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
            _uiState.value = DashboardUiState.Loading
            // DELAY FIX: surface a device-detected Hard Mode loss immediately from Room — the worker
            // already wrote status=failed locally, so we don't block this dialog on the sync round-trip.
            checkUnshownFailedHard()
            // Wait for the one-shot sync to finish before reading Room.
            // If it already completed this is a no-op.
            syncJob.join()
            refreshStats()
            // Check if there is a completed challenge (Hard or Soft) to show the success dialog
            if (_successDialogState.value == null) {
                val sp = appContext.getSharedPreferences("detox_win_popup", Context.MODE_PRIVATE)
                val completedHard = challengeRepository.getUnshownCompletedHardChallenge()
                    .getOrNull()
                val completedSoft = if (completedHard == null) {
                    challengeRepository.getUnshownCompletedSoftChallenge().getOrNull()
                } else null
                val completedChallenge = completedHard ?: completedSoft
                if (completedChallenge != null && !sp.getBoolean("win_shown_${completedChallenge.id}", false)) {
                    Timber.d("Dashboard: unseen completed challenge — ${completedChallenge.id} mode=${completedChallenge.mode}")
                    val logs = dailyLogRepository.getLogsForChallengeOnce(completedChallenge.id)
                    val streak = getChallengeStreakUseCase(completedChallenge)
                    _successDialogState.value = SuccessDialogState(completedChallenge, logs, streak)
                    _successDialogChallengeId.value = completedChallenge.id
                }
            }

            // Check if there is a Soft Mode challenge that failed while the app was closed
            challengeRepository.getUnshownFailedSoftChallenge()
                .onSuccess { challenge ->
                    if (challenge != null) {
                        Timber.d("Dashboard: unseen failed Soft Mode challenge found — ${challenge.id}")
                        challengeRepository.markCompletionShown(challenge.id)
                        val streak = getChallengeStreakUseCase(challenge)
                        TrackedAppEventBus.emitNavigateToSoftFailResult(challenge.id, streak)
                    }
                }
                .onFailure { e -> Timber.w(e, "Dashboard: failed to check failed Soft Mode challenge") }

            // Re-check after sync so a SERVER-detected loss (reconciled active→failed during sync) is
            // also caught this session. No-op if the early Room-first check already surfaced it.
            checkUnshownFailedHard()

            if (!redemptionBannerDismissed) {
                val now = System.currentTimeMillis()
                val available = database.challengeDao().getChallengesWithRedemptionAvailable(now)
                _redemptionChallenges.value = available
                Timber.d("Dashboard: ${available.size} challenge(s) with redemption available")
            }
        }
    }

    /**
     * Surfaces the unified RED loss dialog for the first unshown failed Hard Mode challenge, read
     * from Room. Marks `completionShown` ON SHOW (not on dismiss) so the dialog can never re-pop on a
     * later RESUME / sync and the dismiss-race is closed. No-op if a dialog is already showing or none
     * is found. (`completionShown` is shared with the WIN dialog, but the two gate on mutually
     * exclusive statuses — completed vs failed — so marking it here can never suppress a win.)
     */
    private suspend fun checkUnshownFailedHard() {
        if (_failedHardChallenge.value != null) return
        challengeRepository.getUnshownFailedHardChallenge()
            .onSuccess { challenge ->
                if (challenge != null) {
                    Timber.d("Dashboard: unseen failed Hard Mode challenge found — ${challenge.id} reason=${challenge.failReason}")
                    val logs = dailyLogRepository.getLogsForChallengeOnce(challenge.id)
                    _failedHardChallenge.value = FailedDialogState(challenge, logs)
                    challengeRepository.markCompletionShown(challenge.id)
                        .onFailure { e -> Timber.e(e, "Dashboard: failed to mark completionShown on-show for ${challenge.id}") }
                }
            }
            .onFailure { e -> Timber.w(e, "Dashboard: failed to check failed Hard Mode challenge") }
    }

    /**
     * Observes today's DailyLog rows in Room.  Whenever a conscious open is persisted
     * (or any other intra-day write happens), Room emits a new list and we re-query stats
     * without showing a Loading spinner — the existing data stays visible while it updates.
     * [drop(1)] skips the initial emission because [loadStats] already handles the first load.
     */
    private fun observeDailyLogChanges() {
        val today = DateUtils.todayKey()
        viewModelScope.launch {
            dailyLogRepository.observeLogsForDate(today)
                .drop(1)
                .collect { logs ->
                    Timber.d("Dashboard: DailyLog changed (${logs.size} rows for today) — refreshing stats")
                    logs.forEach { log ->
                        Timber.d("Reading DailyLog for ${log.challengeId} date=$today: opens=${log.consciousOpens}")
                    }
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
                    error.message ?: "Failed to load stats"
                )
            }
        )
    }

    fun dismissRedemptionBanner() {
        redemptionBannerDismissed = true
        _redemptionChallenges.value = emptyList()
    }

    /** Called when the user dismisses the success dialog (X button or "Zurück zum Dashboard"). */
    fun dismissSuccessDialog() {
        val challengeId = _successDialogChallengeId.value ?: return
        _successDialogState.value = null
        _successDialogChallengeId.value = null
        viewModelScope.launch {
            appContext.getSharedPreferences("detox_win_popup", Context.MODE_PRIVATE)
                .edit().putBoolean("win_shown_$challengeId", true).apply()
            challengeRepository.markCompletionShown(challengeId)
                .onFailure { e -> Timber.e(e, "Dashboard: failed to mark completionShown for $challengeId") }
        }
    }

    /** Called when the user dismisses or acts on the Hard Mode loss dialog (X, back link, or CTA). */
    fun dismissHardFailOverlay() {
        val state = _failedHardChallenge.value ?: return
        _failedHardChallenge.value = null
        viewModelScope.launch {
            challengeRepository.markCompletionShown(state.challenge.id)
                .onFailure { e -> Timber.e(e, "Dashboard: failed to mark completionShown for ${state.challenge.id}") }
        }
    }
}
