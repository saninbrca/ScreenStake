package com.detox.app.presentation.screens.username

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R
import com.detox.app.ui.theme.detoxColors

// All colors come from MaterialTheme.colorScheme / detoxColors — no literals here.

@Composable
fun UsernameSelectionScreen(
    onComplete: () -> Unit,
    viewModel: UsernameSelectionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Cannot leave this screen with the back button — a username is mandatory.
    BackHandler(enabled = true) { /* intentionally blocked */ }

    LaunchedEffect(state.saved, state.skip) {
        if (state.saved || state.skip) onComplete()
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
                    .size(96.dp)
                    .background(detoxColors.softGreenBg, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = detoxColors.softGreenIcon,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.username_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = detoxColors.label,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.username_subtitle),
                fontSize = 14.sp,
                color = detoxColors.subtext,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = detoxColors.cardBackground),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, detoxColors.cardBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = state.usernameInput,
                        onValueChange = viewModel::onInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        prefix = { Text("@", fontSize = 16.sp, color = detoxColors.subtext) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = detoxColors.accent,
                            cursorColor = detoxColors.accent
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val (statusText, statusColor) = when (state.availability) {
                            UsernameAvailability.Checking ->
                                stringResource(R.string.username_checking) to detoxColors.subtext
                            UsernameAvailability.Available ->
                                stringResource(R.string.username_available) to detoxColors.success
                            UsernameAvailability.Taken ->
                                stringResource(R.string.username_taken) to detoxColors.danger
                            UsernameAvailability.TooShort ->
                                stringResource(R.string.username_too_short) to detoxColors.subtext
                            UsernameAvailability.Idle ->
                                stringResource(R.string.username_hint) to detoxColors.subtext
                        }
                        Text(text = statusText, fontSize = 12.sp, color = statusColor)
                        Text(
                            text = stringResource(R.string.username_counter, state.usernameInput.length),
                            fontSize = 12.sp,
                            color = detoxColors.subtext
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            val canSubmit = state.availability == UsernameAvailability.Available &&
                state.usernameInput.length >= 3 && !state.isSaving
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                enabled = canSubmit
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                } else {
                    Text(stringResource(R.string.username_continue), fontSize = 16.sp)
                }
            }
        }
    }
}
