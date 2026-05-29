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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private val BgColor = Color(0xFFF2F2F7)
private val AccentGreen = Color(0xFF00C853)
private val ErrorRed = Color(0xFFFF3B30)
private val TextSecondary = Color(0xFF8E8E93)

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

    Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
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
                    .background(Color(0xFFE8F8EF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = AccentGreen,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.username_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.username_subtitle),
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0x0F000000))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = state.usernameInput,
                        onValueChange = viewModel::onInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        prefix = { Text("@", fontSize = 16.sp, color = TextSecondary) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            cursorColor = AccentGreen
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val (statusText, statusColor) = when (state.availability) {
                            UsernameAvailability.Checking ->
                                stringResource(R.string.username_checking) to TextSecondary
                            UsernameAvailability.Available ->
                                stringResource(R.string.username_available) to AccentGreen
                            UsernameAvailability.Taken ->
                                stringResource(R.string.username_taken) to ErrorRed
                            UsernameAvailability.TooShort ->
                                stringResource(R.string.username_too_short) to TextSecondary
                            UsernameAvailability.Idle ->
                                stringResource(R.string.username_hint) to TextSecondary
                        }
                        Text(text = statusText, fontSize = 12.sp, color = statusColor)
                        Text(
                            text = stringResource(R.string.username_counter, state.usernameInput.length),
                            fontSize = 12.sp,
                            color = TextSecondary
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
                    containerColor = AccentGreen,
                    contentColor = Color.White
                ),
                enabled = canSubmit
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                } else {
                    Text(stringResource(R.string.username_continue), fontSize = 16.sp)
                }
            }
        }
    }
}
