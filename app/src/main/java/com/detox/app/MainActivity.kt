package com.detox.app

import android.app.AppOpsManager
import android.content.Context
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
import com.detox.app.service.UsageTrackingService
import com.detox.app.ui.theme.DetoxTheme
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var challengeRepository: ChallengeRepository

    @Inject
    lateinit var firebaseAuth: FirebaseAuth

    private var startDestination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            startDestination = determineStartDestination()

            // Start tracking service if there are active challenges
            val activeChallenges = challengeRepository.getActiveChallenges().first()
            if (activeChallenges.isNotEmpty()) {
                UsageTrackingService.start(this@MainActivity)
            }
        }

        setContent {
            DetoxTheme {
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

    private suspend fun determineStartDestination(): String {
        val currentUser = firebaseAuth.currentUser
        Timber.d("determineStartDestination: FirebaseAuth.currentUser isNull=%s uid=%s",
            currentUser == null, currentUser?.uid)

        // Gate 1: must be authenticated
        if (currentUser == null) {
            Timber.d("No authenticated user → Auth screen")
            return Screen.Auth.route
        }

        // Gate 2: must have permissions granted
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
