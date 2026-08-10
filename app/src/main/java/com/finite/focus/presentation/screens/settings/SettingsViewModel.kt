package com.finite.focus.presentation.screens.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.finite.focus.R
import com.finite.focus.service.DailyEvaluationWorker
import com.finite.focus.util.ErrorMessages
import com.finite.focus.BuildConfig
import com.finite.focus.data.local.db.DetoxDatabase
import com.finite.focus.data.remote.firebase.FirebaseAuthService
import com.finite.focus.data.remote.firebase.FirestoreService
import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.domain.repository.DailyLogRepository
import com.finite.focus.presentation.screens.profile.IbanData
import com.finite.focus.presentation.screens.profile.IbanSaveState
import com.finite.focus.ui.theme.ThemeMode
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

// ── SharedPreferences keys ─────────────────────────────────────────────────────
// Theme mode keys live in ui.theme.ThemeMode (tri-state + legacy Boolean migration).
private const val PREFS_NAME = "detox_settings"
private const val KEY_CHALLENGE_UPDATES = "challenge_updates_enabled"
private const val KEY_FRIEND_ALERTS = "friend_alerts_enabled"

// ── Notification toggle prefs (separate file, read by NotificationHelper) ───────
private const val NOTIF_PREFS_NAME = "detox_notifications"
private const val KEY_GROUP_PARTICIPANT_FAILED = "notif_group_participant_failed"

data class SettingsState(
    val displayName: String = "",
    val email: String = "",
    val appVersion: String = BuildConfig.VERSION_NAME,
    val groupParticipantFailedEnabled: Boolean = true,
    val challengeUpdatesEnabled: Boolean = true,
    val friendAlertsEnabled: Boolean = true,
    val accessibilityGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val usageStatsGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val isLoading: Boolean = false,
    val showDeleteConfirmDialog: Boolean = false,
    val showLogoutConfirmDialog: Boolean = false,
    /**
     * Whether the account actually has an email/password credential. Google-Sign-In-only
     * accounts have no password, so the "change password" row must stay hidden for them —
     * a reset email would never arrive.
     */
    val hasPasswordProvider: Boolean = false,
    val passwordResetMessage: String? = null,
    val passwordResetError: String? = null,
    val passwordResetCooldownSeconds: Int = 0,
    val deleteReauthError: String? = null,
    val deleteReauthLoading: Boolean = false
)

sealed interface SettingsEvent {
    data class ShowSnackbar(val message: String) : SettingsEvent
    data object NavigateToLogin : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreService: FirestoreService,
    private val challengeRepository: ChallengeRepository,
    private val dailyLogRepository: DailyLogRepository,
    private val database: DetoxDatabase,
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val notifPrefs: SharedPreferences =
        context.getSharedPreferences(NOTIF_PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _ibanData = MutableStateFlow<IbanData?>(null)
    val ibanData: StateFlow<IbanData?> = _ibanData.asStateFlow()

    private val _ibanSaveState = MutableStateFlow<IbanSaveState>(IbanSaveState.Idle)
    val ibanSaveState: StateFlow<IbanSaveState> = _ibanSaveState.asStateFlow()

    private var passwordResetCooldownJob: kotlinx.coroutines.Job? = null

    init {
        val currentUser = firebaseAuthService.currentUser()
        _state.update { s ->
            s.copy(
                displayName = currentUser?.displayName ?: "",
                email = currentUser?.email ?: "",
                // Same providerData source the GDPR export reads. An account may have both
                // (Google linked to an email/password login), so look for "password" rather
                // than assuming a single provider.
                hasPasswordProvider = currentUser?.providerData
                    ?.any { it.providerId == EmailAuthProvider.PROVIDER_ID } == true,
                groupParticipantFailedEnabled = notifPrefs.getBoolean(KEY_GROUP_PARTICIPANT_FAILED, true),
                challengeUpdatesEnabled = prefs.getBoolean(KEY_CHALLENGE_UPDATES, true),
                friendAlertsEnabled = prefs.getBoolean(KEY_FRIEND_ALERTS, true),
                themeMode = ThemeMode.fromPrefs(prefs)
            )
        }
        refreshPermissions()
        viewModelScope.launch { fetchIban() }
    }

    // ── IBAN / Payout Account ──────────────────────────────────────────────────

    private suspend fun fetchIban() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        runCatching {
            firestore.collection("users").document(uid).get().await()
        }.onSuccess { doc ->
            val iban = doc.getString("payoutIban")?.takeIf { it.isNotBlank() } ?: return@onSuccess
            val name = doc.getString("payoutName") ?: ""
            _ibanData.value = IbanData(iban, name)
        }
    }

    fun saveIban(iban: String, name: String) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        if (_ibanSaveState.value is IbanSaveState.Loading) return
        _ibanSaveState.value = IbanSaveState.Loading
        viewModelScope.launch {
            runCatching {
                firestore.collection("users").document(uid)
                    .set(
                        mapOf("payoutIban" to iban.trim(), "payoutName" to name.trim()),
                        com.google.firebase.firestore.SetOptions.merge()
                    ).await()
            }.onSuccess {
                _ibanData.value = IbanData(iban.trim(), name.trim())
                _ibanSaveState.value = IbanSaveState.Success
            }.onFailure { e ->
                _ibanSaveState.value = IbanSaveState.Error(ErrorMessages.from(context, e))
            }
        }
    }

    fun clearIbanSaveState() { _ibanSaveState.value = IbanSaveState.Idle }

    // ── Permissions ────────────────────────────────────────────────────────────

    fun refreshPermissions() {
        _state.update { s ->
            s.copy(
                accessibilityGranted = isAccessibilityGranted(),
                overlayGranted = Settings.canDrawOverlays(context),
                usageStatsGranted = isUsageStatsGranted(),
                notificationsGranted = areNotificationsGranted()
            )
        }
    }

    /**
     * Whether the app may actually post notifications — the same question every sender asks
     * before building one, so the Settings row can't claim "enabled" while notifications
     * silently no-op.
     *
     * Deliberately NOT an API-33 `POST_NOTIFICATIONS` check: this is correct on every level.
     * Below 33 there is no runtime permission and this reflects a user who switched the app's
     * notifications off in system settings; from 33 up it additionally reflects the runtime
     * grant. Either way, false means nothing we post will be shown.
     */
    private fun areNotificationsGranted(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun isAccessibilityGranted(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "${context.packageName}/com.finite.focus.service.AppDetectionAccessibilityService"
        return enabledServices.split(":").any { it.equals(target, ignoreCase = true) }
    }

    private fun isUsageStatsGranted(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    // ── Account ────────────────────────────────────────────────────────────────

    fun sendPasswordReset() {
        val email = _state.value.email.ifBlank { return }
        if (_state.value.passwordResetCooldownSeconds > 0) return
        viewModelScope.launch {
            // Unlike the logged-out auth screen, there is no account-enumeration concern here:
            // the user is already authenticated and this is their own address. Report the real
            // outcome instead of claiming an email was sent that Firebase may never have sent.
            firebaseAuthService.sendPasswordReset(email)
                .onSuccess {
                    _state.update {
                        it.copy(
                            passwordResetMessage = context.getString(
                                R.string.settings_password_reset_confirm, email
                            ),
                            passwordResetError = null
                        )
                    }
                    startPasswordResetCooldown()
                }
                .onFailure { e ->
                    // FirebaseAuthException maps to "session expired" in ErrorMessages, which is
                    // wrong copy for a failed send — use the dedicated message for those and let
                    // the shared mapper handle the network/offline case.
                    val message =
                        if (e is FirebaseAuthException) {
                            context.getString(R.string.settings_password_reset_failed)
                        } else {
                            ErrorMessages.from(context, e, R.string.settings_password_reset_failed)
                        }
                    // No cooldown on failure — the user must be able to retry immediately.
                    _state.update {
                        it.copy(passwordResetMessage = null, passwordResetError = message)
                    }
                }
        }
    }

    private fun startPasswordResetCooldown() {
        passwordResetCooldownJob?.cancel()
        passwordResetCooldownJob = viewModelScope.launch {
            var remaining = 60
            while (remaining > 0) {
                _state.update { it.copy(passwordResetCooldownSeconds = remaining) }
                kotlinx.coroutines.delay(1000)
                remaining--
            }
            _state.update { it.copy(passwordResetCooldownSeconds = 0) }
        }
    }

    fun logOut() {
        _state.update { it.copy(showLogoutConfirmDialog = false) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { database.clearAllTables() }
            firebaseAuthService.signOut()
            _events.send(SettingsEvent.NavigateToLogin)
        }
    }

    fun showDeleteConfirmDialog() {
        _state.update { it.copy(showDeleteConfirmDialog = true, deleteReauthError = null) }
    }

    fun dismissDeleteConfirmDialog() {
        _state.update {
            it.copy(showDeleteConfirmDialog = false, deleteReauthError = null, deleteReauthLoading = false)
        }
    }

    fun showLogoutConfirmDialog() {
        _state.update { it.copy(showLogoutConfirmDialog = true) }
    }

    fun dismissLogoutConfirmDialog() {
        _state.update { it.copy(showLogoutConfirmDialog = false) }
    }

    /**
     * Re-authenticates with the supplied password (Firebase requirement for account
     * deletion), then runs the deletion flow. Wrong password → inline dialog error.
     */
    fun deleteAccount(password: String) {
        viewModelScope.launch {
            _state.update { it.copy(deleteReauthLoading = true, deleteReauthError = null) }

            val reauthResult = firebaseAuthService.reauthenticateWithPassword(password)
            if (reauthResult.isFailure) {
                _state.update {
                    it.copy(
                        deleteReauthLoading = false,
                        deleteReauthError = context.getString(R.string.settings_delete_reauth_wrong_password)
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    deleteReauthLoading = false,
                    isLoading = true,
                    showDeleteConfirmDialog = false,
                    deleteReauthError = null
                )
            }

            // Stripe safety: block deletion if any active Hard Mode challenge exists
            val activeChallengesResult = challengeRepository.getActiveChallengesList()
            if (activeChallengesResult.isFailure) {
                _state.update { it.copy(isLoading = false) }
                _events.send(SettingsEvent.ShowSnackbar(context.getString(R.string.settings_delete_verify_failed)))
                return@launch
            }

            val activeHardChallenge = activeChallengesResult.getOrNull()?.firstOrNull { challenge ->
                challenge.mode == ChallengeMode.HARD &&
                        challenge.status == ChallengeStatus.ACTIVE &&
                        challenge.stripePaymentIntentId != null
            }

            if (activeHardChallenge != null) {
                _state.update { it.copy(isLoading = false) }
                _events.send(
                    SettingsEvent.ShowSnackbar(context.getString(R.string.settings_delete_active_hard))
                )
                return@launch
            }

            // Delete Firestore data first, then Auth account, then local DB
            val uid = firebaseAuthService.currentUserId()
            if (uid != null) {
                try {
                    firestoreService.deleteUserData(uid)
                } catch (e: Exception) {
                    Timber.w(e, "Firestore data deletion failed — proceeding with Auth deletion")
                }
            }

            val authDeleteResult = firebaseAuthService.deleteAccount()
            if (authDeleteResult.isFailure) {
                _state.update { it.copy(isLoading = false) }
                val msg = authDeleteResult.exceptionOrNull()?.let { ErrorMessages.from(context, it) }
                    ?: context.getString(R.string.error_generic)
                // Firebase requires recent sign-in for sensitive ops — surface a helpful message
                _events.send(SettingsEvent.ShowSnackbar(context.getString(R.string.settings_delete_failed, msg)))
                return@launch
            }

            withContext(Dispatchers.IO) { database.clearAllTables() }
            _state.update { it.copy(isLoading = false) }
            _events.send(SettingsEvent.NavigateToLogin)
        }
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    fun setGroupParticipantFailedEnabled(enabled: Boolean) {
        notifPrefs.edit().putBoolean(KEY_GROUP_PARTICIPANT_FAILED, enabled).apply()
        _state.update { it.copy(groupParticipantFailedEnabled = enabled) }
    }

    fun setChallengeUpdatesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHALLENGE_UPDATES, enabled).apply()
        _state.update { it.copy(challengeUpdatesEnabled = enabled) }
    }

    fun setFriendAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FRIEND_ALERTS, enabled).apply()
        _state.update { it.copy(friendAlertsEnabled = enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        Timber.d("Theme mode selected: %s", mode)
        // Only WRITES the mode — MainActivity's prefs listener applies it to the theme.
        mode.saveTo(prefs)
        _state.update { it.copy(themeMode = mode) }
    }

    // ── Debug ──────────────────────────────────────────────────────────────────

    fun runEvaluationNow() {
        Timber.d("Settings: manually triggering DailyEvaluationWorker")
        val request = OneTimeWorkRequestBuilder<DailyEvaluationWorker>()
            .addTag("manual_evaluation")
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    // ── Data Export ────────────────────────────────────────────────────────────

    /**
     * Builds the GDPR (Art. 15) data export and returns it for the caller to share via the system
     * share sheet. READ-ONLY throughout — it queries and serialises, and writes nothing anywhere.
     *
     * It used to export active challenges only and — despite saying otherwise — no daily logs at
     * all, so a user exercising their right of access got back a fraction of what the privacy
     * policy discloses. It now covers every category the policy lists that is reachable from the
     * client, and names the ones that are not in `notIncluded` rather than quietly dropping them.
     *
     * Every read is scoped to the signed-in uid. The one place co-participant data could leak —
     * `group_challenges.participantsJson` — is dropped in [DataExportJson].
     *
     * Firestore reads are best-effort: offline or rules-denied, the export still returns everything
     * held locally. A partial export beats a failed one, and the gap is visible because the section
     * is `null` rather than absent.
     */
    suspend fun buildExportJson(): String = withContext(Dispatchers.IO) {
        val uid = firebaseAuthService.currentUserId()
        val authUser = firebaseAuth.currentUser

        // ── Challenges: ALL statuses (active + completed + failed + ended) ──────
        val challenges = runCatching { challengeRepository.getAllChallenges().first() }
            .getOrElse { e ->
                Timber.w(e, "Export: could not read challenges")
                emptyList()
            }

        // ── Daily logs: every log of every challenge above ──────────────────────
        val dailyLogs = challenges.flatMap { c ->
            runCatching { dailyLogRepository.getLogsForChallengeOnce(c.id) }
                .getOrElse { e ->
                    Timber.w(e, "Export: could not read daily logs for %s", c.id)
                    emptyList()
                }
        }

        val groupChallenges = runCatching { database.groupChallengeDao().getAllList() }
            .getOrElse { e ->
                Timber.w(e, "Export: could not read group challenges")
                emptyList()
            }

        // ── Account + consent record, from users/{uid} ──────────────────────────
        val userDoc: Map<String, Any?> = if (uid == null) emptyMap() else
            runCatching {
                firestore.collection("users").document(uid).get().await().data.orEmpty()
            }.getOrElse { e ->
                Timber.w(e, "Export: could not read user document")
                emptyMap()
            }

        val account = linkedMapOf<String, Any?>(
            "firebaseUid" to uid,
            "email" to (authUser?.email ?: userDoc["email"]?.toString()),
            "displayName" to (authUser?.displayName ?: userDoc["displayName"]?.toString()),
            "username" to userDoc["username"]?.toString(),
            "signInProviders" to (authUser?.providerData?.map { it.providerId } ?: emptyList()),
            "accountCreatedAt" to userDoc["createdAt"]?.toString(),
            "fcmToken" to userDoc["fcmToken"]?.toString(),
            "consent" to linkedMapOf<String, Any?>(
                "acceptedTerms" to userDoc["consentAGB"],
                "acceptedPrivacyPolicy" to userDoc["consentDatenschutz"],
                "confirmedAge18" to userDoc["consentAge18"],
                "consentTimestamp" to userDoc["consentTimestamp"]?.toString(),
            ),
        )

        // ── Permission status + circumvention detection ─────────────────────────
        // Both privacy-policy categories live in this one doc: the permission-loss/restore
        // timestamps and the heartbeat, plus `usageViolationDetectedAt`, which is what the policy
        // calls circumvention detection.
        val permissionStatus: Map<String, Any?>? = if (uid == null) null else
            runCatching {
                firestore.collection("users").document(uid)
                    .collection("permissionStatus").document("current")
                    .get().await().data?.mapValues { it.value?.toString() }
            }.getOrElse { e ->
                Timber.w(e, "Export: could not read permission status")
                null
            }

        DataExportJson.build(
            generatedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            account = account,
            challenges = challenges,
            dailyLogs = dailyLogs,
            groupChallenges = groupChallenges,
            permissionStatus = permissionStatus,
            notIncluded = NOT_INCLUDED_CATEGORIES,
            selfUserId = uid,
        )
    }

    private companion object {
        /**
         * Disclosed-as-collected categories the app cannot put in a client-side export, each with
         * the reason. Listed rather than silently omitted so the export never overstates itself —
         * and so the user knows what to ask for by email.
         */
        val NOT_INCLUDED_CATEGORIES: List<Map<String, Any?>> = listOf(
            linkedMapOf(
                "category" to "Support requests",
                "reason" to "Held in the supportTickets collection. Firestore rules allow an " +
                    "owner to read a ticket by id but not to list their own, so the app cannot " +
                    "enumerate them. Request them via the contact address in the privacy policy.",
            ),
            linkedMapOf(
                "category" to "Firebase Installations ID",
                "reason" to "A technical installation identifier issued and rotated by Firebase " +
                    "itself. It is not stored in your account data and has no stable value to " +
                    "export.",
            ),
            linkedMapOf(
                "category" to "Crash diagnostics (Sentry)",
                "reason" to "Crash and error reports are sent to Sentry and are not readable " +
                    "from the app.",
            ),
            linkedMapOf(
                "category" to "Other group participants",
                "reason" to "Usernames and progress of the other members of your group " +
                    "challenges are their personal data, not yours, so they are excluded by " +
                    "design. Your own participation appears under challenges and dailyLogs.",
            ),
        )
    }
}
