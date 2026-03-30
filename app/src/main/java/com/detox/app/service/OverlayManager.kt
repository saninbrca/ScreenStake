package com.detox.app.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.detox.app.domain.repository.PointsRepository
import com.detox.app.domain.usecase.CheckDailyLimitUseCase
import com.detox.app.domain.usecase.DailyLimitStatus
import com.detox.app.presentation.components.BlockingScreenOverlay
import com.detox.app.presentation.components.LimitExceededOverlay
import com.detox.app.ui.theme.DetoxTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checkDailyLimitUseCase: CheckDailyLimitUseCase,
    private val pointsRepository: PointsRepository
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var currentOverlayView: View? = null
    private var listeningJob: Job? = null
    private val exceededAppsToday = mutableSetOf<String>()
    private var limitReachedTimerJob: Job? = null

    fun startListening(scope: CoroutineScope) {
        listeningJob = scope.launch {
            TrackedAppEventBus.appOpenEvents.collect { packageName ->
                handleAppOpen(packageName, scope)
            }
        }
    }

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        limitReachedTimerJob?.cancel()
        limitReachedTimerJob = null
        dismissOverlay()
    }

    private suspend fun handleAppOpen(packageName: String, scope: CoroutineScope) {
        if (currentOverlayView != null) return

        val result = checkDailyLimitUseCase(packageName)
        if (result.isFailure) {
            Timber.w("Failed to check daily limit for $packageName: ${result.exceptionOrNull()}")
            return
        }

        val status = result.getOrThrow()

        if (status.limitExceeded || exceededAppsToday.contains(packageName)) {
            showLimitExceededOverlay(status, scope)
        } else {
            showBlockingOverlay(status, scope)
        }
    }

    private suspend fun showBlockingOverlay(status: DailyLimitStatus, scope: CoroutineScope) {
        val totalPoints = pointsRepository.getTotalPointsBalance().first()

        val composeView = createComposeView {
            DetoxTheme {
                BlockingScreenOverlay(
                    status = status,
                    totalPoints = totalPoints,
                    onOpenAnyway = { dismissOverlay() },
                    onSkip = {
                        dismissOverlay()
                        goHome()
                    }
                )
            }
        }
        showOverlay(composeView)
    }

    private fun showLimitExceededOverlay(status: DailyLimitStatus, scope: CoroutineScope) {
        val composeView = createComposeView {
            DetoxTheme {
                LimitExceededOverlay(
                    appName = status.challenge.appDisplayName,
                    onContinue = {
                        exceededAppsToday.add(status.challenge.appPackageName)
                        dismissOverlay()
                        startLimitReachedTimer(status.challenge.appPackageName, scope)
                    },
                    onStop = {
                        dismissOverlay()
                        goHome()
                    }
                )
            }
        }
        showOverlay(composeView)
    }

    private fun startLimitReachedTimer(packageName: String, scope: CoroutineScope) {
        limitReachedTimerJob?.cancel()
        limitReachedTimerJob = scope.launch {
            delay(5 * 60 * 1000L) // 5 minutes
            val tracked = TrackedAppEventBus.trackedPackages.value
            if (tracked.contains(packageName)) {
                val result = checkDailyLimitUseCase(packageName)
                if (result.isSuccess && result.getOrThrow().limitExceeded) {
                    showLimitExceededOverlay(result.getOrThrow(), scope)
                }
            }
        }
    }

    private fun createComposeView(content: @Composable () -> Unit): ComposeView {
        val composeView = ComposeView(context)
        val lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        composeView.setContent(content)
        return composeView
    }

    private fun showOverlay(view: ComposeView) {
        if (currentOverlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(view, params)
            currentOverlayView = view
        } catch (e: Exception) {
            Timber.e(e, "Failed to show overlay")
        }
    }

    private fun dismissOverlay() {
        currentOverlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Timber.e(e, "Failed to dismiss overlay")
            }
            currentOverlayView = null
        }
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(homeIntent)
    }

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        init {
            savedStateRegistryController.performRestore(null)
        }

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }
    }
}
