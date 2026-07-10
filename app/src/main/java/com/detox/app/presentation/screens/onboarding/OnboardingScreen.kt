package com.detox.app.presentation.screens.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import timber.log.Timber
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.detox.app.R
import com.detox.app.presentation.components.AccessibilityDisclosureDialog

@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isHuawei = Build.MANUFACTURER.lowercase() == "huawei"

    // Re-check permissions every time the screen resumes (user returns from Settings)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            Timber.d("Onboarding: screen RESUMED — checking permissions")
            viewModel.refreshPermissions()
        }
    }

    // Notification permission launcher (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }

    // Prominent disclosure gate for the AccessibilityService (Play policy): the settings intent
    // fires ONLY after the affirmative tap. Shown every time the enable flow is initiated.
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    if (showAccessibilityDisclosure) {
        AccessibilityDisclosureDialog(
            onAccept = {
                showAccessibilityDisclosure = false
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onDismiss = { showAccessibilityDisclosure = false },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            LinearProgressIndicator(
                progress = { state.currentStep.toFloat() / state.totalSteps },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(
                    R.string.onboarding_step_progress,
                    state.currentStep,
                    state.totalSteps
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(250)) { it * direction } + fadeIn(tween(250))) togetherWith
                            (slideOutHorizontally(tween(250)) { -it * direction } + fadeOut(tween(250)))
                },
                modifier = Modifier.fillMaxWidth(),
                label = "onboarding_step"
            ) { step ->
                when (step) {
                    // ── Step 0: Usage Stats ─────────────────────────────────────────
                    0 -> PermissionStep(
                        title = stringResource(R.string.permission_usage_title),
                        description = stringResource(R.string.permission_usage_description),
                        isGranted = state.usageStatsGranted,
                        onRequest = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        },
                        onNext = { viewModel.advanceStep() }
                    )

                    // ── Step 1: Overlay ─────────────────────────────────────────────
                    1 -> PermissionStep(
                        title = stringResource(R.string.permission_overlay_title),
                        description = stringResource(R.string.permission_overlay_description),
                        isGranted = state.overlayGranted,
                        onRequest = {
                            Timber.d("Onboarding: launching ACTION_MANAGE_OVERLAY_PERMISSION for ${context.packageName}")
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        },
                        onNext = { viewModel.advanceStep() }
                    )

                    // ── Step 2: Accessibility ───────────────────────────────────────
                    2 -> PermissionStep(
                        title = stringResource(R.string.permission_accessibility_title),
                        description = stringResource(R.string.permission_accessibility_description),
                        isGranted = state.accessibilityGranted,
                        onRequest = { showAccessibilityDisclosure = true },
                        onNext = { viewModel.advanceStep() }
                    )

                    // ── Step 3: Notifications (Android 13+) / Battery (Huawei <13) / done ──
                    3 -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PermissionStep(
                                title = stringResource(R.string.permission_notifications_title),
                                description = stringResource(R.string.permission_notifications_description),
                                isGranted = state.notificationsGranted,
                                onRequest = {
                                    notificationPermissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                },
                                onNext = {
                                    if (isHuawei) viewModel.advanceStep() else onOnboardingComplete()
                                }
                            )
                        } else if (isHuawei) {
                            HuaweiBatteryStep(
                                onRequest = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    )
                                },
                                onNext = { onOnboardingComplete() }
                            )
                        } else {
                            LaunchedEffect(Unit) { onOnboardingComplete() }
                        }
                    }

                    // ── Step 4: Battery (Android 13+ Huawei) / done otherwise ───────
                    4 -> {
                        if (isHuawei) {
                            HuaweiBatteryStep(
                                onRequest = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    )
                                },
                                onNext = { onOnboardingComplete() }
                            )
                        } else {
                            LaunchedEffect(Unit) { onOnboardingComplete() }
                        }
                    }

                    else -> LaunchedEffect(Unit) { onOnboardingComplete() }
                }
            }
        }
    }
}

// ── Huawei battery optimization step ──────────────────────────────────────────

@Composable
private fun HuaweiBatteryStep(
    onRequest: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.BatteryFull,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Text(
            text = stringResource(R.string.permission_huawei_battery_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.permission_huawei_battery_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.permission_huawei_battery_button))
        }

        Spacer(modifier = Modifier.height(4.dp))

        OutlinedButton(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.onboarding_skip))
        }
    }
}

// ── Permission step composable ─────────────────────────────────────────────────

@Composable
private fun PermissionStep(
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isGranted) {
            val haptic = LocalHapticFeedback.current
            Text(
                text = stringResource(R.string.permission_granted),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onNext()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.onboarding_next))
            }
        } else {
            Button(
                onClick = onRequest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.permission_grant_button))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.onboarding_skip))
            }
        }
    }
}
