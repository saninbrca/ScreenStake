package com.detox.app.presentation.screens.auth

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.ui.theme.detoxColors
import kotlinx.coroutines.delay

// All colors come from MaterialTheme.colorScheme / detoxColors — no literals here.

@Composable
fun EmailVerificationScreen(
    onVerified: () -> Unit,
    onBackToRegister: () -> Unit,
    viewModel: EmailVerificationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Auto-poll every 5 seconds until verified.
    LaunchedEffect(state.verified) {
        if (!state.verified) {
            while (true) {
                delay(5000)
                viewModel.checkVerification(manual = false)
            }
        }
    }

    LaunchedEffect(state.verified) {
        if (state.verified) onVerified()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = detoxColors.screenBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(detoxColors.softGreenBg, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MailOutline,
                    contentDescription = null,
                    tint = detoxColors.softGreenIcon,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.email_verify_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = detoxColors.label,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.email_verify_subtitle, state.email),
                fontSize = 14.sp,
                color = detoxColors.subtext,
                textAlign = TextAlign.Center
            )

            if (state.showNotVerifiedError) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.email_verify_not_yet),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { viewModel.checkVerification(manual = true) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                enabled = !state.checking
            ) {
                if (state.checking) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                } else {
                    Text(stringResource(R.string.email_verify_confirmed_button), fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val cooldown = state.resendCooldownSeconds
            OutlinedButton(
                onClick = {
                    viewModel.resendVerificationEmail()
                    Toast.makeText(
                        context,
                        context.getString(R.string.email_verify_resent_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                enabled = cooldown == 0,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = detoxColors.cardBackground,
                    contentColor = detoxColors.accent
                )
            ) {
                Text(
                    text = if (cooldown > 0) {
                        stringResource(R.string.email_verify_resend_cooldown, cooldown)
                    } else {
                        stringResource(R.string.email_verify_resend_button)
                    },
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.email_verify_back_to_register),
                fontSize = 13.sp,
                color = detoxColors.subtext,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableNoRipple {
                        viewModel.signOut()
                        onBackToRegister()
                    }
            )
        }
    }
}
