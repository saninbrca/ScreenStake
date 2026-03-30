package com.detox.app.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
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
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.PointTransaction
import com.detox.app.domain.repository.PaymentRepository
import com.detox.app.domain.repository.PointsRepository
import com.detox.app.domain.usecase.CheckDailyLimitUseCase
import com.detox.app.domain.usecase.DailyLimitStatus
import com.detox.app.presentation.components.BlockingScreenOverlay
import com.detox.app.presentation.components.HardModeLockoutOverlay
import com.detox.app.presentation.components.LimitExceededOverlay
import com.detox.app.ui.theme.DetoxTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checkDailyLimitUseCase: CheckDailyLimitUseCase,
    private val pointsRepository: PointsRepository,
    private val paymentRepository: PaymentRepository
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var currentOverlayView: View? = null
    private var listeningJob: Job? = null
    private val exceededAppsToday = mutableSetOf<String>()

    /**
     * Packages permanently locked until midnight in Hard Mode.
     * Mapped packageName → ChallengeStatus snapshot (to access the emergency code).
     */
    private val hardLockedPackages = mutableMapOf<String, LockedAppInfo>()

    private var limitReachedTimerJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Public API ─────────────────────────────────────────────────────────────

    fun startListening(scope: CoroutineScope) {
        listeningJob = scope.launch {
            TrackedAppEventBus.appOpenEvents.collect { packageName ->
                handleAppOpen(packageName, scope)
            }
        }
        scheduleMidnightReset()
    }

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        limitReachedTimerJob?.cancel()
        limitReachedTimerJob = null
        dismissOverlay()
    }

    // ── Core dispatch ──────────────────────────────────────────────────────────

    private suspend fun handleAppOpen(packageName: String, scope: CoroutineScope) {
        if (currentOverlayView != null) return

        // Hard Mode permanent lockout takes priority
        hardLockedPackages[packageName]?.let { info ->
            showHardModeLockout(info, scope)
            return
        }

        val result = checkDailyLimitUseCase(packageName)
        if (result.isFailure) {
            Timber.w("Failed to check daily limit for $packageName: ${result.exceptionOrNull()}")
            return
        }

        val status = result.getOrThrow()

        when {
            status.limitExceeded || exceededAppsToday.contains(packageName) ->
                showLimitExceededOverlay(status, scope)
            else ->
                showBlockingOverlay(status, scope)
        }
    }

    // ── Blocking overlay (shown before every tracked-app open) ────────────────

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

    // ── Limit-exceeded overlay ─────────────────────────────────────────────────

    private fun showLimitExceededOverlay(status: DailyLimitStatus, scope: CoroutineScope) {
        val challenge = status.challenge
        val isHard = challenge.mode == ChallengeMode.HARD

        val composeView = createComposeView {
            DetoxTheme {
                LimitExceededOverlay(
                    appName = challenge.appDisplayName,
                    challengeMode = challenge.mode,
                    amountCents = challenge.amountCents,
                    onContinue = {
                        if (isHard && challenge.stripePaymentIntentId != null) {
                            // Hard Mode: capture payment and lock the app
                            scope.launch {
                                captureAndLock(status, scope)
                            }
                        } else {
                            // Soft Mode: mark exceeded, restart 5-min timer
                            exceededAppsToday.add(challenge.appPackageName)
                            dismissOverlay()
                            startLimitReachedTimer(challenge.appPackageName, scope)
                        }
                    },
                    onStop = {
                        dismissOverlay()
                        goHome()
                    },
                    onEmergencyCode = if (isHard) {
                        {
                            // Dismiss and show lockout with code input immediately
                            val info = LockedAppInfo(
                                appName = challenge.appDisplayName,
                                amountCents = challenge.amountCents ?: 0,
                                emergencyCode = challenge.emergencyCode ?: ""
                            )
                            dismissOverlay()
                            showHardModeLockoutDirect(info, scope)
                        }
                    } else null
                )
            }
        }
        showOverlay(composeView)
    }

    // ── Hard Mode: capture payment → lock app ──────────────────────────────────

    private suspend fun captureAndLock(status: DailyLimitStatus, scope: CoroutineScope) {
        val challenge = status.challenge
        val paymentIntentId = challenge.stripePaymentIntentId ?: return

        dismissOverlay()

        // Capture the payment (fire-and-forget — show lockout regardless of result)
        scope.launch {
            paymentRepository.capturePayment(paymentIntentId)
                .onSuccess { Timber.d("Payment captured for ${challenge.appDisplayName}") }
                .onFailure { e -> Timber.e(e, "Failed to capture payment") }
        }

        val info = LockedAppInfo(
            appName = challenge.appDisplayName,
            amountCents = challenge.amountCents ?: 0,
            emergencyCode = challenge.emergencyCode ?: ""
        )
        hardLockedPackages[challenge.appPackageName] = info
        showHardModeLockoutDirect(info, scope)
    }

    // ── Hard Mode lockout overlay ──────────────────────────────────────────────

    private fun showHardModeLockout(info: LockedAppInfo, scope: CoroutineScope) {
        showHardModeLockoutDirect(info, scope)
    }

    private fun showHardModeLockoutDirect(info: LockedAppInfo, scope: CoroutineScope) {
        val composeView = createComposeView {
            DetoxTheme {
                HardModeLockoutOverlay(
                    appName = info.appName,
                    amountCents = info.amountCents,
                    correctCode = info.emergencyCode,
                    onEmergencyUnlock = {
                        // Deduct 50 points as penalty
                        scope.launch {
                            pointsRepository.addPointTransaction(
                                PointTransaction(
                                    id = UUID.randomUUID().toString(),
                                    type = "penalty",
                                    amount = 50,
                                    reason = "emergency_unlock",
                                    challengeId = null,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                        // Remove from locked set so the app can be opened again today
                        hardLockedPackages.entries
                            .filter { it.value == info }
                            .forEach { hardLockedPackages.remove(it.key) }
                        dismissOverlay()
                    },
                    onExitHome = {
                        dismissOverlay()
                        goHome()
                    }
                )
            }
        }
        showOverlay(composeView)
    }

    // ── 5-minute re-trigger timer (Soft Mode) ─────────────────────────────────

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

    // ── Midnight reset ─────────────────────────────────────────────────────────

    private fun scheduleMidnightReset() {
        val now = System.currentTimeMillis()
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val delay = midnight - now
        mainHandler.postDelayed({
            Timber.d("Midnight reset — clearing exceeded/locked apps")
            exceededAppsToday.clear()
            hardLockedPackages.clear()
            scheduleMidnightReset() // reschedule for next midnight
        }, delay)
    }

    // ── WindowManager helpers ──────────────────────────────────────────────────

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

    // ── Data class ─────────────────────────────────────────────────────────────

    private data class LockedAppInfo(
        val appName: String,
        val amountCents: Int,
        val emergencyCode: String
    )

    // ── LifecycleOwner for overlays ────────────────────────────────────────────

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
