package com.detox.app.presentation.screens.challenge

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.detox.app.R

private val ScreenBg      = Color(0xFF0A0A0A)
private val CardBg        = Color(0xFFFFFFFF)
private val TextWhite     = Color(0xFFFFFFFF)
private val TextGrey       = Color(0xFF666666)
private val LabelGrey     = Color(0xFF8E8E93)
private val RedPrimary    = Color(0xFFFF3B30)

@Composable
fun HardModeFailScreen(
    onBackToDashboard: () -> Unit,
    onNewChallenge: () -> Unit,
    viewModel: HardModeFailViewModel = hiltViewModel(),
) {
    val amountCents by viewModel.amountCents.collectAsStateWithLifecycle()

    // Dark screen — light icons on the status bar.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "💸", fontSize = 64.sp)

        Spacer(Modifier.height(24.dp))

        Row {
            Text(
                text = stringResource(R.string.hard_fail_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
            )
            Text(
                text = ".",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = RedPrimary,
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.hard_fail_screen_subtitle),
            fontSize = 14.sp,
            color = TextGrey,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.hard_fail_captured_label),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LabelGrey,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatEuros(amountCents ?: 0),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = RedPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.hard_fail_captured_subtext),
                    fontSize = 12.sp,
                    color = LabelGrey,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.hard_fail_encouragement),
            fontSize = 13.sp,
            color = TextGrey,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(40.dp))

        Button(
            onClick = onBackToDashboard,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = TextWhite,
                contentColor = Color.Black,
            ),
        ) {
            Text(
                text = stringResource(R.string.hard_fail_back_to_dashboard),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.hard_fail_new_challenge),
            fontSize = 13.sp,
            color = LabelGrey,
            modifier = Modifier.clickable { onNewChallenge() },
        )
    }
}

/** Formats integer cents to "€X,XX" without rounding up (never overstates the captured amount). */
private fun formatEuros(cents: Int): String = "€%d,%02d".format(cents / 100, cents % 100)
