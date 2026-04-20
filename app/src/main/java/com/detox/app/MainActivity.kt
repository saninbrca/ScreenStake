package com.detox.app

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.detox.app.domain.repository.ChallengeRepository
import com.detox.app.presentation.navigation.DetoxNavGraph
import com.detox.app.presentation.navigation.Screen
import com.detox.app.presentation.screens.settings.KEY_DARK_MODE
import com.detox.app.service.UsageTrackingService
import com.detox.app.ui.theme.DetoxTheme
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val PREFS_NAME = "detox_settings"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var challengeRepository: ChallengeRepository

    @Inject
    lateinit var firebaseAuth: FirebaseAuth

    private var startDestination by mutableStateOf<String?>(null)
    private var isDarkMode by mutableStateOf(false)

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
            startDestination = determineStartDestination()

            val activeChallenges = challengeRepository.getActiveChallenges().first()
            if (activeChallenges.isNotEmpty()) {
                UsageTrackingService.start(this@MainActivity)
            }
        }

        setContent {
            DetoxTheme(darkTheme = isDarkMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    startDestination?.let { destination ->
                        val navController = rememberNavController()
                        DetoxNavGraph(
                            navController = navController,
                            startDestination = destination
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private suspend fun determineStartDestination(): String {
        val currentUser = firebaseAuth.currentUser
        Timber.d("determineStartDestination: FirebaseAuth.currentUser isNull=%s uid=%s",
            currentUser == null, currentUser?.uid)

        if (currentUser == null) {
            Timber.d("No authenticated user → Auth screen")
            return Screen.Auth.route
        }

        if (!hasUsageStatsPermission()) {
            Timber.d("User authenticated but permissions missing → Onboarding")
            return Screen.Onboarding.route
        }

        Timber.d("Authenticated + permissions → Main")
        return Screen.Main.route
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
