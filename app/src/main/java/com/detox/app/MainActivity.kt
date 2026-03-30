package com.detox.app

import android.os.Bundle
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
import com.detox.app.domain.repository.UsageStatsRepository
import com.detox.app.presentation.navigation.DetoxNavGraph
import com.detox.app.presentation.navigation.Screen
import com.detox.app.service.UsageTrackingService
import com.detox.app.ui.theme.DetoxTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var usageStatsRepository: UsageStatsRepository

    @Inject
    lateinit var challengeRepository: ChallengeRepository

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
        if (!usageStatsRepository.hasUsageStatsPermission()) {
            return Screen.Onboarding.route
        }

        val activeChallenges = challengeRepository.getActiveChallenges().first()
        return if (activeChallenges.isNotEmpty()) {
            Screen.Dashboard.route
        } else {
            Screen.AppSelection.route
        }
    }
}
