package com.detox.app.presentation.screens.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.detox.app.service.DailyEvaluationWorker
import com.detox.app.BuildConfig
import com.detox.app.DetoxApplication
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.service.DailyReminderWorker
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
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

// ── SharedPreferences keys ─────────────────────────────────────────────────────
private const val PREFS_NAME = "detox_settings"
private const val KEY_DAILY_REMINDER_ENABLED = "daily_reminder_enabled"
private const val KEY_DAILY_REMINDER_HOUR = "daily_reminder_hour"
private const val KEY_DAILY_REMINDER_MINUTE = "daily_reminder_minute"
private const val KEY_CHALLENGE_UPDATES = "challenge_updates_enabled"
private const val KEY_FRIEND_ALERTS = "friend_alerts_enabled"
const val KEY_DARK_MODE = "dark_mode_enabled"

data class SettingsState(
    val displayName: String = "",
    val email: String = "",
    val appVersion: String = BuildConfig.VERSION_NAME,
    val dailyReminderEnabled: Boolean = true,
    val dailyReminderHour: Int = 20,
    val dailyReminderMinute: Int = 0,
    val challengeUpdatesEnabled: Boolean = true,
    val friendAlertsEnabled: Boolean = true,
    val accessibilityGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val usageStatsGranted: Boolean = false,
    val darkModeEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val showDeleteConfirmDialog: Boolean = false
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        val currentUser = firebaseAuthService.currentUser()
        _state.update { s ->
            s.copy(
                displayName = currentUser?.displayName ?: "",
                email = currentUser?.email ?: "",
                dailyReminderEnabled = prefs.getBoolean(KEY_DAILY_REMINDER_ENABLED, true),
                dailyReminderHour = prefs.getInt(KEY_DAILY_REMINDER_HOUR, 20),
                dailyReminderMinute = prefs.getInt(KEY_DAILY_REMINDER_MINUTE, 0),
                challengeUpdatesEnabled = prefs.getBoolean(KEY_CHALLENGE_UPDATES, true),
                friendAlertsEnabled = prefs.getBoolean(KEY_FRIEND_ALERTS, true),
                darkModeEnabled = prefs.getBoolean(KEY_DARK_MODE, false)
            )
        }
        refreshPermissions()
    }

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
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val result = firebaseAuthService.sendPasswordReset(email)
            _state.update { it.copy(isLoading = false) }
            if (result.isSuccess) {
                _events.send(SettingsEvent.ShowSnackbar("Password reset email sent to $email"))
            } else {
                _events.send(SettingsEvent.ShowSnackbar("Failed to send reset email. Try again."))
            }
        }
    }

    fun logOut() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { database.clearAllTables() }
            firebaseAuthService.signOut()
            _events.send(SettingsEvent.NavigateToLogin)
        }
    }

    fun showDeleteConfirmDialog() {
        _state.update { it.copy(showDeleteConfirmDialog = true) }
    }

    fun dismissDeleteConfirmDialog() {
        _state.update { it.copy(showDeleteConfirmDialog = false) }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, showDeleteConfirmDialog = false) }

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
                val msg = authDeleteResult.exceptionOrNull()?.message ?: "Deletion failed"
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

    fun setDailyReminderEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DAILY_REMINDER_ENABLED, enabled).apply()
        _state.update { it.copy(dailyReminderEnabled = enabled) }
        if (enabled) {
            rescheduleReminder(
                hour = _state.value.dailyReminderHour,
                minute = _state.value.dailyReminderMinute
            )
        } else {
            WorkManager.getInstance(context)
                .cancelUniqueWork(DetoxApplication.WORK_NAME_DAILY_REMINDER)
            Timber.d("Daily reminder cancelled")
        }
    }

    fun setReminderTime(hour: Int, minute: Int) {
        prefs.edit()
            .putInt(KEY_DAILY_REMINDER_HOUR, hour)
            .putInt(KEY_DAILY_REMINDER_MINUTE, minute)
            .apply()
        _state.update { it.copy(dailyReminderHour = hour, dailyReminderMinute = minute) }
        if (_state.value.dailyReminderEnabled) {
            rescheduleReminder(hour, minute)
        }
    }

    private fun rescheduleReminder(hour: Int, minute: Int) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val initialDelayMs = target.timeInMillis - now.timeInMillis

        val request = PeriodicWorkRequestBuilder<DailyReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .addTag(DetoxApplication.TAG_DAILY_REMINDER)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DetoxApplication.WORK_NAME_DAILY_REMINDER,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request
        )
        Timber.d("Daily reminder rescheduled for %02d:%02d (delay: %d min)", hour, minute, initialDelayMs / 60_000)
    }

    fun setChallengeUpdatesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHALLENGE_UPDATES, enabled).apply()
        _state.update { it.copy(challengeUpdatesEnabled = enabled) }
    }

    fun setFriendAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FRIEND_ALERTS, enabled).apply()
        _state.update { it.copy(friendAlertsEnabled = enabled) }
    }

    fun setDarkModeEnabled(enabled: Boolean) {
        Timber.d("Dark mode toggled: $enabled")
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply()
        _state.update { it.copy(darkModeEnabled = enabled) }
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
