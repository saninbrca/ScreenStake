package com.detox.app.presentation.screens.support

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.detox.app.R
import com.detox.app.ui.theme.detoxColors
import com.detox.app.util.FeatureFlags

// All colors come from MaterialTheme.colorScheme / detoxColors — no literals here.

@Composable
fun FaqScreen(onBack: () -> Unit) {
    // Money/Hard-Mode FAQ content is gated behind the build-level money floor. In the soft
    // build the money-only questions (Hard Mode, payout, Group) are dropped and the answers
    // that merely mention fees fall back to soft-mode variants with no money language.
    // Gate only — flipping BuildConfig.MONEY_FEATURES_ENABLED restores the full list.
    val moneyEnabled = FeatureFlags.moneyEnabled
    val faqs = buildList {
        if (moneyEnabled) add(R.string.faq_q1 to R.string.faq_a1)
        add(R.string.faq_q2 to if (moneyEnabled) R.string.faq_a2 else R.string.faq_a2_soft)
        if (moneyEnabled) add(R.string.faq_q3 to R.string.faq_a3)
        add(R.string.faq_q4 to R.string.faq_a4)
        if (moneyEnabled) add(R.string.faq_q5 to R.string.faq_a5)
        add(R.string.faq_q6 to if (moneyEnabled) R.string.faq_a6 else R.string.faq_a6_soft)
        add(R.string.faq_q7 to if (moneyEnabled) R.string.faq_a7 else R.string.faq_a7_soft)
        add(R.string.faq_q8 to R.string.faq_a8)
    }

    Scaffold(containerColor = detoxColors.screenBackground) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Header ──────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = detoxColors.label
                    )
                }
                Text(
                    text = stringResource(R.string.faq_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = detoxColors.label
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                faqs.forEach { (questionRes, answerRes) ->
                    FaqCard(
                        question = stringResource(questionRes),
                        answer = stringResource(answerRes)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun FaqCard(question: String, answer: String) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "chevronRotation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = detoxColors.cardBackground),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, detoxColors.cardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = question,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = detoxColors.label,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = detoxColors.hint,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = rotation }
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = answer,
                    fontSize = 14.sp,
                    color = detoxColors.subtext,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }
}
