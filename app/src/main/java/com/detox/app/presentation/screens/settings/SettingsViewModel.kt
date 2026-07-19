package com.detox.app.presentation.screens.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.detox.app.R
import com.detox.app.service.DailyEvaluationWorker
import com.detox.app.util.ErrorMessages
import com.detox.app.BuildConfig
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.presentation.screens.profile.IbanData
import com.detox.app.presentation.screens.profile.IbanSaveState
import com.detox.app.ui.theme.ThemeMode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val isLoading: Boolean = false,
    val showDeleteConfirmDialog: Boolean = false,
    val showLogoutConfirmDialog: Boolean = false,
    val passwordResetMessage: String? = null,
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
                usageStatsGranted = isUsageStatsGranted()
            )
        }
    }

    private fun isAccessibilityGranted(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "${context.packageName}/com.detox.app.service.AppDetectionAccessibilityService"
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
            firebaseAuthService.sendPasswordReset(email)
            // Always confirm inline (success regardless, to avoid user enumeration).
            _state.update {
                it.copy(
                    passwordResetMessage = context.getString(
                        R.string.settings_password_reset_confirm, email
                    )
                )
            }
            startPasswordResetCooldown()
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
                _events.send(SettingsEvent.ShowSnackbar("Could not verify challenges. Try again."))
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
                    SettingsEvent.ShowSnackbar(
                        "You have an active Hard Mode challenge. Complete or cancel it before deleting your account."
                    )
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
                _events.send(SettingsEvent.ShowSnackbar("Account deletion failed: $msg. Please re-sign in and try again."))
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
     * Builds a JSON string of all challenges + daily logs and returns it
     * for the caller to share via a system share sheet.
     */
    suspend fun buildExportJson(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("{\n  \"challenges\": [\n")
        val challengesResult = challengeRepository.getActiveChallengesList()
        val challenges = challengesResult.getOrNull() ?: emptyList()
        challenges.forEachIndexed { idx, c ->
            sb.append("    {")
            sb.append("\"id\":\"${c.id}\",")
            sb.append("\"app\":\"${c.appDisplayName}\",")
            sb.append("\"mode\":\"${c.mode.name}\",")
            sb.append("\"limitType\":\"${c.limitType.name}\",")
            sb.append("\"limitValueMinutes\":${c.limitValueMinutes},")
            sb.append("\"status\":\"${c.status.name}\",")
            sb.append("\"startDate\":${c.startDate},")
            sb.append("\"endDate\":${c.endDate}")
            sb.append("}")
            if (idx < challenges.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n}")
        sb.toString()
    }
}
