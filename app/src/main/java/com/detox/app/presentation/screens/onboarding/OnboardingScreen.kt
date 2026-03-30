package com.detox.app.presentation.screens.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.detox.app.BuildConfig
import com.detox.app.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh permissions/sign-in state whenever the screen resumes
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshPermissions()
        }
    }

    // Google Sign-In launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            account.idToken?.let { token ->
                viewModel.signInWithGoogle(token)
            }
        } catch (e: ApiException) {
            // Sign-in was cancelled or failed — viewModel will show error
        }
    }

    // Notification permission launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
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

            when (state.currentStep) {
                // ── Step 0: Google Sign-In ──────────────────────────────────────
                0 -> GoogleSignInStep(
                    isSignedIn = state.isSignedIn,
                    isSigningIn = state.isSigningIn,
                    error = state.signInError,
                    onSignIn = {
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                            .requestEmail()
                            .build()
                        val client = GoogleSignIn.getClient(context, gso)
                        googleSignInLauncher.launch(client.signInIntent)
                    },
                    onNext = { viewModel.advanceStep() }
                )

                // ── Step 1: Usage Stats ─────────────────────────────────────────
                1 -> PermissionStep(
                    title = stringResource(R.string.permission_usage_title),
                    description = stringResource(R.string.permission_usage_description),
                    isGranted = state.usageStatsGranted,
                    onRequest = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    onNext = { viewModel.advanceStep() }
                )

                // ── Step 2: Overlay ─────────────────────────────────────────────
                2 -> PermissionStep(
                    title = stringResource(R.string.permission_overlay_title),
                    description = stringResource(R.string.permission_overlay_description),
                    isGranted = state.overlayGranted,
                    onRequest = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    onNext = { viewModel.advanceStep() }
                )

                // ── Step 3: Accessibility ───────────────────────────────────────
                3 -> PermissionStep(
                    title = stringResource(R.string.permission_accessibility_title),
                    description = stringResource(R.string.permission_accessibility_description),
                    isGranted = state.accessibilityGranted,
                    onRequest = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onNext = { viewModel.advanceStep() }
                )

                // ── Step 4: Notifications (Android 13+) ────────────────────────
                4 -> {
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

// ── Step 0 composable ──────────────────────────────────────────────────────────

@Composable
private fun GoogleSignInStep(
    isSignedIn: Boolean,
    isSigningIn: Boolean,
    error: String?,
    onSignIn: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.onboarding_signin_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.onboarding_signin_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isSignedIn) {
            Text(
                text = stringResource(R.string.onboarding_signed_in),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.onboarding_next))
            }
        } else {
            error?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSigningIn
            ) {
                if (isSigningIn) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.height(20.dp)
                    )
                } else {
                    Text(text = stringResource(R.string.onboarding_signin_button))
                }
            }
        }
    }
}

// ── Permission step composable (steps 1–4) ────────────────────────────────────

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
            Text(
                text = stringResource(R.string.permission_granted),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNext,
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
