package com.detox.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import androidx.work.WorkManager
import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.data.remote.firebase.FirestoreService
import com.detox.app.data.repository.AppConfigRepository
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.domain.repository.GroupChallengeRepository
import com.detox.app.domain.usecase.SyncUserDataUseCase
import com.detox.app.presentation.navigation.DetoxNavGraph
import com.detox.app.presentation.navigation.Screen
import com.detox.app.presentation.screens.settings.KEY_DARK_MODE
import com.detox.app.service.TrackedAppEventBus
import com.detox.app.service.UsageTrackingService
import com.detox.app.ui.theme.DetoxTheme
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val PREFS_NAME = "detox_settings"
private const val KEY_LAST_RESUME_SYNC_AT = "last_resume_sync_at"
private const val RESUME_SYNC_THROTTLE_MS = 5L * 60 * 1000 // 5 minutes

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var challengeRepository: ChallengeRepository

    @Inject
    lateinit var groupChallengeRepository: GroupChallengeRepository

    @Inject
    lateinit var cloudFunctionsService: CloudFunctionsService

    @Inject
    lateinit var firebaseAuth: FirebaseAuth

    @Inject
    lateinit var firestoreService: FirestoreService

    @Inject
    lateinit var appConfigRepository: AppConfigRepository

    @Inject
    lateinit var syncUserDataUseCase: SyncUserDataUseCase

    private var startDestination by mutableStateOf<String?>(null)
    // The destination the app would normally start on, preserved so the Maintenance
    // screen can forward the user there once maintenance is cleared.
    private var maintenanceClearedDestination by mutableStateOf(Screen.Main.route)
    private var isDarkMode by mutableStateOf(false)
    private var hasActiveChallenge by mutableStateOf(false)
    private var overlayMissing by mutableStateOf(false)
    private var showPermissionBlock by mutableStateOf(false)
    private var snackbarMessage by mutableStateOf<String?>(null)
    private var accessibilityMissing by mutableStateOf(false)

    private lateinit var prefs: SharedPreferences
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_DARK_MODE) {
            isDarkMode = prefs.getBoolean(KEY_DARK_MODE, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isDarkMode = prefs.getBoolean(KEY_DARK_MODE, false)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        lifecycleScope.launch {
            val realDestination = determineStartDestination()
            maintenanceClearedDestination = realDestination

            // Remote control gating (Huawei-safe Firestore read). FAIL OPEN: refresh()
            // never throws and returns cached/default config on any error, so an offline
            // or no-GMS device proceeds straight into the app.
            val config = appConfigRepository.refresh()
            // Ban check (fail-open): only when a user is signed in. A read failure returns
            // false so a network problem never locks out a legitimate user; the Firebase
            // Auth disabled flag is the hard backstop.
            val uid = firebaseAuth.currentUser?.uid
            val accountDisabled = uid != null && firestoreService.isUserDisabled(uid)
            startDestination = when {
                BuildConfig.VERSION_CODE < config.minVersionCode -> {
                    Timber.w(
                        "Force update: versionCode=%d < minVersionCode=%d",
                        BuildConfig.VERSION_CODE, config.minVersionCode
                    )
                    Screen.ForceUpdate.route
                }
                config.maintenanceMode -> {
                    Timber.w("Maintenance mode active — blocking app entry")
                    Screen.Maintenance.route
                }
                accountDisabled -> {
                    Timber.w("Account disabled — blocking app entry for uid=%s", uid)
                    Screen.AccountDisabled.route
                }
                else -> realDestination
            }
            handleDeepLink(intent)

            val activeChallenges = challengeRepository.getActiveChallenges().first()
            hasActiveChallenge = activeChallenges.isNotEmpty()
            if (activeChallenges.isNotEmpty()) {
                UsageTrackingService.start(this@MainActivity)
            }

            if (firebaseAuth.currentUser != null) {
                firebaseAuth.currentUser?.uid?.let { uid ->
                    groupChallengeRepository.refreshFromFirestore(uid)
                }
                autoStartGroupChallenges()
                checkExpiredGroupChallenges()
            }
        }

        setContent {
            DetoxTheme(darkTheme = isDarkMode) {
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(snackbarMessage) {
                    val msg = snackbarMessage ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(msg)
                    snackbarMessage = null
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        startDestination?.let { destination ->
                            val navController = rememberNavController()
                            DetoxNavGraph(
                                navController = navController,
                                startDestination = destination,
                                permissionMissing = overlayMissing && hasActiveChallenge,
                                accessibilityMissing = accessibilityMissing,
                                onOpenPermissionSettings = ::openPermissionSettings,
                                onOpenAccessibilitySettings = ::openAccessibilitySettings,
                                maintenanceClearedDestination = maintenanceClearedDestination,
                            )
                        }
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }

                if (showPermissionBlock) {
                    BackHandler(enabled = true) { /* block back — user must fix permission */ }
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("🔴 Deine Challenge endet bald!") },
                        text = {
                            Text(
                                "Die erforderliche Erlaubnis fehlt seit zu langer Zeit. " +
                                "Behebe es jetzt oder deine Challenge wird automatisch beendet."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = ::openPermissionSettings) {
                                Text(stringResource(R.string.fix_now))
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Routes a tapped-notification intent to the correct screen via [TrackedAppEventBus].
     * If the user is not yet on the main app (logged out / onboarding), the target is stashed
     * in SharedPreferences and replayed by MainScreen once it is shown post-login.
     */
    private fun handleDeepLink(intent: Intent) {
        val target = intent.getStringExtra("nav_target") ?: return
        val arg = intent.getStringExtra("nav_arg")

        if (startDestination != Screen.Main.route) {
            prefs.edit()
                .putString("pending_deep_link_target", target)
                .putString("pending_deep_link_arg", arg)
                .apply()
            Timber.d("Deep link stashed (not on Main): target=$target arg=$arg")
            return
        }

        when (target) {
            "dashboard" -> TrackedAppEventBus.emitNavigateToDashboard()
            "profile" -> TrackedAppEventBus.emitNavigateToProfile()
            "group_detail" -> arg?.let { TrackedAppEventBus.emitNavigateToGroupDetail(it) }
            "challenge_detail" -> arg?.let { TrackedAppEventBus.emitNavigateToChallengeDetail(it) }
            "history_detail" -> arg?.let { TrackedAppEventBus.emitNavigateToHistoryDetail(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        trackPermissionIgnore()
        checkPermissionState()
        if (firebaseAuth.currentUser != null) {
            lifecycleScope.launch { checkExpiredGroupChallenges() }
            maybeResumeSync()
        }
    }

    /**
     * Pulls server-settled challenge state into Room on foreground (the dormant-return fix), so a
     * challenge already settled server-side is reflected on the dashboard. Throttled: skips if a
     * resume-sync ran within [RESUME_SYNC_THROTTLE_MS] to avoid wasteful reads/battery on rapid
     * background↔foreground toggles. The dormant case is hours/days, so the throttle never blocks
     * the fix. The DashboardViewModel-init sync is independent and unchanged.
     */
    private fun maybeResumeSync() {
        val now = System.currentTimeMillis()
        val lastSync = prefs.getLong(KEY_LAST_RESUME_SYNC_AT, 0L)
        if (now - lastSync < RESUME_SYNC_THROTTLE_MS) {
            Timber.d("MainActivity: resume-sync throttled (last ran ${(now - lastSync) / 1000}s ago)")
            return
        }
        prefs.edit().putLong(KEY_LAST_RESUME_SYNC_AT, now).apply()
        lifecycleScope.launch {
            syncUserDataUseCase()
                .onFailure { e -> Timber.w(e, "MainActivity: resume-sync failed (offline?)") }
        }
    }

    private suspend fun checkExpiredGroupChallenges() {
        val now = System.currentTimeMillis()
        val groups = runCatching { groupChallengeRepository.getGroupChallenges().first() }
            .getOrElse {
                Timber.w(it, "MainActivity: failed to load group challenges for end-date check")
                return
            }
        groups
            .filter { it.status == GroupChallengeStatus.ACTIVE && it.endDate in 1..now }
            .forEach { gc ->
                Timber.d("Group challenge expired: ${gc.groupId} endDate=${gc.endDate} now=$now")
                cloudFunctionsService.completeGroupChallenge(gc.groupId)
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private suspend fun autoStartGroupChallenges() {
        val userId = firebaseAuth.currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        try {
            groupChallengeRepository.getGroupChallenges().first()
                .filter { it.status == GroupChallengeStatus.WAITING && it.startDate > 0L && it.startDate <= now }
                .forEach { gc ->
                    if (gc.participants.size >= 2) {
                        cloudFunctionsService.startGroupChallenge(gc.groupId)
                            .onSuccess {
                                Timber.d("Auto-started group challenge: %s", gc.groupId)
                                groupChallengeRepository.syncGroupChallengeToLocalTracking(gc, userId)
                                    .onSuccess {
                                        Timber.d(
                                            "Group challenge %s started → synced to Room as ChallengeEntity",
                                            gc.groupId
                                        )
                                    }
                                    .onFailure { e ->
                                        Timber.e(e, "Auto-start sync failed for group: %s", gc.groupId)
                                    }
                            }
                            .onFailure { e -> Timber.e(e, "Auto-start failed for group: %s", gc.groupId) }
                    } else {
                        Timber.d("Auto-cancel group %s — only %d participant(s)", gc.groupId, gc.participants.size)
                        cloudFunctionsService.cancelGroupChallenge(gc.groupId)
                            .onSuccess {
                                Timber.d("Auto-cancelled group challenge: %s", gc.groupId)
                            }
                            .onFailure { e -> Timber.e(e, "Auto-cancel failed for group: %s", gc.groupId) }
                    }
                }
        } catch (e: Exception) {
            Timber.e(e, "autoStartGroupChallenges failed")
        }
    }

    private suspend fun determineStartDestination(): String {
        val onboardingCompleted = prefs.getBoolean("onboarding_completed", false)
        if (!onboardingCompleted) {
            Timber.d("First run — onboarding not completed → Welcome screen")
            return Screen.Welcome.route
        }

        val currentUser = firebaseAuth.currentUser
        Timber.d("determineStartDestination: FirebaseAuth.currentUser isNull=%s uid=%s",
            currentUser == null, currentUser?.uid)

        if (currentUser == null) {
            Timber.d("No authenticated user → Auth screen")
            return Screen.Auth.route
        }

        // Username gate: every verified account must have a unique @username. Existing
        // accounts created before the username system are routed through selection on
        // next launch. A SharedPreferences cache avoids a Firestore read on every start.
        if (currentUser.isEmailVerified) {
            val cachedUsername = prefs.getString("username", null)?.takeIf { it.isNotBlank() }
            if (cachedUsername == null) {
                val remoteUsername = firestoreService.getUsername(currentUser.uid)
                if (remoteUsername == null) {
                    Timber.d("Verified user without username → UsernameSelection")
                    return "username_selection?fromRegister=false"
                }
                // Found remotely — cache it so subsequent launches skip the read.
                prefs.edit().putString("username", remoteUsername).apply()
            }
        }

        if (!hasUsageStatsPermission()) {
            Timber.d("User authenticated but permissions missing → Onboarding")
            return Screen.Onboarding.route
        }

        Timber.d("Authenticated + permissions → Main")
        return Screen.Main.route
    }

    private fun checkPermissionState() {
        val permPrefs = getSharedPreferences("detox_permission", Context.MODE_PRIVATE)
        val lostAt = permPrefs.getLong("permissionLostAt", 0L)

        if (lostAt > 0L && Settings.canDrawOverlays(this)) {
            Timber.d("Permission restored: clearing all warnings")
            permPrefs.edit().clear().apply()
            val wm = WorkManager.getInstance(this)
            listOf("permission_warning_0", "permission_warning_2", "permission_warning_6", "permission_warning_12")
                .forEach { tag -> wm.cancelAllWorkByTag(tag) }
            overlayMissing = false
            showPermissionBlock = false
            snackbarMessage = "✅ Perfekt! Deine Challenge läuft weiter."
            UsageTrackingService.start(this)
        } else if (lostAt > 0L && !Settings.canDrawOverlays(this)) {
            overlayMissing = true
            val elapsed = System.currentTimeMillis() - lostAt
            if (elapsed >= 18 * 60 * 60 * 1000L) {
                showPermissionBlock = true
                Timber.d("Full screen block shown: elapsed=${elapsed / 3_600_000}h")
            }
        } else {
            overlayMissing = false
            showPermissionBlock = false
        }

        if (overlayMissing && hasActiveChallenge) {
            Timber.d("Permission banner shown")
        }

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val accessibilityOff = !enabledServices.contains(packageName)
        accessibilityMissing = accessibilityOff && hasActiveChallenge
        if (accessibilityMissing) {
            Timber.d("Huawei accessibility also missing: showing second banner")
        }
    }

    private fun openPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun trackPermissionIgnore() {
        val permPrefs = getSharedPreferences("detox_permission", Context.MODE_PRIVATE)
        if (!permPrefs.contains("permissionLostAt")) return
        if (Settings.canDrawOverlays(this)) return

        val elapsed = System.currentTimeMillis() - permPrefs.getLong("permissionLostAt", 0)
        if (elapsed < 12 * 60 * 60 * 1000L) {
            val ignored = permPrefs.getInt("userOpenedAndIgnored", 0)
            permPrefs.edit().putInt("userOpenedAndIgnored", ignored + 1).apply()
            Timber.d("User opened and ignored permission warning: count=${ignored + 1}")
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
