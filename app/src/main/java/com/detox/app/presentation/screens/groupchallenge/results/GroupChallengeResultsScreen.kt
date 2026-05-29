package com.detox.app.presentation.screens.groupchallenge.results

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.detox.app.R
import com.detox.app.domain.model.Participant
import com.detox.app.domain.model.ParticipantStatus
import com.detox.app.util.HapticManager
import kotlinx.coroutines.delay
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Angle
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.Spread
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit

private val PodiumGold = Color(0xFFFFD700)
private val PodiumSilver = Color(0xFFC0C0C0)
private val PodiumBronze = Color(0xFFCD7F32)
private val DarkBg = Color(0xFF0A0A0A)
private val CardSurface = Color(0xFF111111)
private val AccentGreen = Color(0xFF00C853)
private val AccentRed = Color(0xFFFF3B30)
private val TextSecondary = Color(0xFF8E8E93)

private val AvatarColors = listOf(
    Color(0xFF1A6B4A),
    Color(0xFF5B3A8E),
    Color(0xFF8E3A3A),
    Color(0xFF3A5B8E),
    Color(0xFF8E7A1A),
    Color(0xFF1A4D8E),
)

private data class PodiumSlot(val rank: Int, val participant: Participant?)

private fun buildPodiumSlots(sortedWinners: List<Participant>): List<PodiumSlot> = when {
    sortedWinners.isEmpty() -> emptyList()
    sortedWinners.size == 1 -> listOf(PodiumSlot(1, sortedWinners[0]))
    sortedWinners.size == 2 -> listOf(
        PodiumSlot(2, sortedWinners[1]),
        PodiumSlot(1, sortedWinners[0]),
    )
    else -> listOf(
        PodiumSlot(3, sortedWinners.getOrNull(2)),
        PodiumSlot(1, sortedWinners[0]),
        PodiumSlot(2, sortedWinners[1]),
    )
}

@Composable
fun GroupChallengeResultsScreen(
    onWeiter: () -> Unit,
    viewModel: GroupChallengeResultsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var titleVisible by remember { mutableStateOf(false) }
    var podium3Visible by remember { mutableStateOf(false) }
    var podium2Visible by remember { mutableStateOf(false) }
    var podium1Visible by remember { mutableStateOf(false) }
    var confettiVisible by remember { mutableStateOf(false) }
    var statsVisible by remember { mutableStateOf(false) }
    var buttonVisible by remember { mutableStateOf(false) }
    val hapticFired = remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) {
            titleVisible = true
            delay(500); podium3Visible = true
            delay(300); podium2Visible = true
            delay(400); podium1Visible = true
            delay(300)
            confettiVisible = true
            if (!hapticFired.value) {
                hapticFired.value = true
                HapticManager.success(context)
            }
            delay(500); statsVisible = true
            delay(500); buttonVisible = true
        }
    }

    val titleAlpha by animateFloatAsState(if (titleVisible) 1f else 0f, tween(400), label = "title")
    val podium3Alpha by animateFloatAsState(if (podium3Visible) 1f else 0f, tween(400), label = "p3a")
    val podium2Alpha by animateFloatAsState(if (podium2Visible) 1f else 0f, tween(400), label = "p2a")
    val podium1Alpha by animateFloatAsState(if (podium1Visible) 1f else 0f, tween(400), label = "p1a")
    val statsAlpha by animateFloatAsState(if (statsVisible) 1f else 0f, tween(400), label = "stats")
    val buttonAlpha by animateFloatAsState(if (buttonVisible) 1f else 0f, tween(400), label = "btn")

    val podium3OffsetY by animateFloatAsState(
        if (podium3Visible) 0f else 80f,
        tween(500, easing = FastOutSlowInEasing),
        label = "p3o"
    )
    val podium2OffsetY by animateFloatAsState(
        if (podium2Visible) 0f else 80f,
        tween(500, easing = FastOutSlowInEasing),
        label = "p2o"
    )
    val podium1OffsetY by animateFloatAsState(
        if (podium1Visible) 0f else 80f,
        tween(500, easing = FastOutSlowInEasing),
        label = "p1o"
    )

    val lottieComposition by rememberLottieComposition(
        LottieCompositionSpec.Asset("lottie/trophy.json")
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        // Konfetti rendered first so content renders on top, keeping button clickable
        if (confettiVisible) {
            KonfettiView(
                modifier = Modifier.fillMaxSize(),
                parties = listOf(
                    Party(
                        speed = 0f,
                        maxSpeed = 30f,
                        damping = 0.9f,
                        angle = Angle.BOTTOM,
                        spread = Spread.ROUND,
                        colors = listOf(
                            0xFFFFD700.toInt(),
                            0xFF00C853.toInt(),
                            0xFFFFFFFF.toInt(),
                            0xFFFF9500.toInt(),
                        ),
                        emitter = Emitter(duration = 3, TimeUnit.SECONDS).perSecond(100),
                        position = Position.Relative(0.0, 0.0)
                            .between(Position.Relative(1.0, 0.0)),
                    )
                ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            Text(
                text = stringResource(R.string.results_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(titleAlpha),
            )

            Spacer(Modifier.height(48.dp))

            if (!uiState.isLoading) {
                val slots = buildPodiumSlots(uiState.sortedWinners)
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    slots.forEach { slot ->
                        val alpha = when (slot.rank) {
                            1 -> podium1Alpha
                            2 -> podium2Alpha
                            else -> podium3Alpha
                        }
                        val offsetY = when (slot.rank) {
                            1 -> podium1OffsetY
                            2 -> podium2OffsetY
                            else -> podium3OffsetY
                        }
                        PodiumSlotItem(
                            slot = slot,
                            alpha = alpha,
                            offsetYDp = offsetY,
                            showLottie = slot.rank == 1,
                            lottieComposition = lottieComposition,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            if (!uiState.isLoading && uiState.failedParticipants.isNotEmpty()) {
                FailedSection(failed = uiState.failedParticipants)
                Spacer(Modifier.height(16.dp))
            }

            if (!uiState.isLoading) {
                StatsCard(
                    participant = uiState.currentUserParticipant,
                    modifier = Modifier.alpha(statsAlpha),
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onWeiter,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .alpha(buttonAlpha),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                enabled = buttonVisible,
            ) {
                Text(
                    text = stringResource(R.string.results_weiter),
                    color = Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PodiumSlotItem(
    slot: PodiumSlot,
    alpha: Float,
    offsetYDp: Float,
    showLottie: Boolean,
    lottieComposition: com.airbnb.lottie.LottieComposition?,
    modifier: Modifier = Modifier,
) {
    val platformHeight = when (slot.rank) { 1 -> 140.dp; 2 -> 100.dp; else -> 80.dp }
    val platformColor = when (slot.rank) { 1 -> PodiumGold; 2 -> PodiumSilver; else -> PodiumBronze }
    val medalEmoji = when (slot.rank) {
        1 -> stringResource(R.string.results_medal_1)
        2 -> stringResource(R.string.results_medal_2)
        else -> stringResource(R.string.results_medal_3)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
        modifier = modifier
            .width(90.dp)
            .alpha(alpha)
            .offset(y = offsetYDp.dp),
    ) {
        Text(medalEmoji, fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))

        if (showLottie) {
            if (lottieComposition != null) {
                val progress by animateLottieCompositionAsState(
                    composition = lottieComposition,
                    iterations = 1,
                )
                LottieAnimation(
                    composition = lottieComposition,
                    progress = { progress },
                    modifier = Modifier.size(80.dp),
                )
            } else {
                Text("🏆", fontSize = 48.sp)
            }
            Spacer(Modifier.height(4.dp))
        }

        AvatarCircle(
            displayName = slot.participant?.displayName ?: "?",
            size = 52.dp,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = slot.participant?.displayName?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "",
            fontSize = 13.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 86.dp),
        )

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .width(90.dp)
                .height(platformHeight)
                .background(
                    color = platformColor,
                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = slot.rank.toString(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun AvatarCircle(displayName: String, size: Dp) {
    val bgColor = AvatarColors[displayName.hashCode().and(0x7FFFFFFF) % AvatarColors.size]
    val initial = displayName.firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = Modifier
            .size(size)
            .background(color = bgColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Composable
private fun FailedSection(failed: List<Participant>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        failed.take(3).forEach { p ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AvatarCircle(displayName = p.displayName, size = 32.dp)
                Text(
                    text = p.displayName.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "",
                    fontSize = 13.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.results_failed_label),
                    fontSize = 12.sp,
                    color = AccentRed,
                )
            }
        }
    }
}

@Composable
private fun StatsCard(participant: Participant?, modifier: Modifier = Modifier) {
    val isWinner = participant?.status != ParticipantStatus.FAILED
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isWinner) {
                Text(
                    text = stringResource(R.string.results_winner_headline),
                    fontSize = 17.sp,
                    fontWeight = FontWeight(600),
                    color = AccentGreen,
                )
                Text(
                    text = stringResource(R.string.results_winner_sub),
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
                val payout = participant?.finalPayout ?: 0
                if (payout > 0) {
                    val prize = String.format("%.2f", payout / 100f)
                    Text(
                        text = stringResource(R.string.results_winner_prize, prize),
                        fontSize = 15.sp,
                        color = AccentGreen,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.results_loser_headline),
                    fontSize = 17.sp,
                    fontWeight = FontWeight(600),
                    color = AccentRed,
                )
                Text(
                    text = stringResource(R.string.results_loser_sub),
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
            }
        }
    }
}
