package com.detox.app.presentation.screens.support

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.MaterialTheme
import com.detox.app.R
import com.detox.app.ui.theme.detoxColors
import com.detox.app.util.FeatureFlags

// All colors come from MaterialTheme.colorScheme / detoxColors — no literals here.
// (Color.Transparent below is absence of color, not a color choice.)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    viewModel: SupportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                    text = stringResource(R.string.support_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = detoxColors.label
                )
            }

            if (state.submitted) {
                SupportConfirmation(onBack = onBack)
            } else {
                SupportForm(state = state, viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupportForm(
    state: SupportFormState,
    viewModel: SupportViewModel
) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    val categoryLabels = mapOf(
        SupportCategory.BUG to stringResource(R.string.support_category_bug),
        SupportCategory.QUESTION to stringResource(R.string.support_category_question),
        SupportCategory.COMPLAINT to stringResource(R.string.support_category_complaint),
        SupportCategory.PAYOUT to stringResource(R.string.support_category_payout),
        SupportCategory.OTHER to stringResource(R.string.support_category_other)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = detoxColors.cardBackground),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, detoxColors.cardBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // ── Kategorie dropdown ──────────────────────────────────────────
                FieldLabel(stringResource(R.string.support_label_category))
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = state.category?.let { categoryLabels[it] }
                            ?: stringResource(R.string.support_category_placeholder),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = detoxColors.subtext
                            )
                        },
                        isError = state.showValidationErrors && !state.categoryValid,
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        // Money-floor gated: "Auszahlung" (PAYOUT) is a payout-only support topic —
                        // dropped from the picker in the soft-only release so no dead category shows.
                        SupportCategory.entries
                            .filter { it != SupportCategory.PAYOUT || FeatureFlags.moneyEnabled }
                            .forEach { category ->
                            DropdownMenuItem(
                                text = { Text(categoryLabels[category] ?: "") },
                                onClick = {
                                    viewModel.onCategorySelected(category)
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }
                if (state.showValidationErrors && !state.categoryValid) {
                    ErrorText(stringResource(R.string.support_error_category))
                }

                Spacer(Modifier.height(16.dp))

                // ── Betreff ─────────────────────────────────────────────────────
                FieldLabel(stringResource(R.string.support_label_subject))
                OutlinedTextField(
                    value = state.subject,
                    onValueChange = viewModel::onSubjectChanged,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.support_subject_placeholder), color = detoxColors.subtext) },
                    isError = state.showValidationErrors && !state.subjectValid,
                    colors = fieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.showValidationErrors && !state.subjectValid) {
                    ErrorText(stringResource(R.string.support_error_subject))
                }

                Spacer(Modifier.height(16.dp))

                // ── Nachricht ───────────────────────────────────────────────────
                FieldLabel(stringResource(R.string.support_label_message))
                OutlinedTextField(
                    value = state.message,
                    onValueChange = viewModel::onMessageChanged,
                    placeholder = { Text(stringResource(R.string.support_message_placeholder), color = detoxColors.subtext) },
                    isError = state.showValidationErrors && !state.messageValid,
                    colors = fieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                )
                if (state.showValidationErrors && !state.messageValid) {
                    ErrorText(stringResource(R.string.support_error_message))
                }

                if (state.hasError) {
                    Spacer(Modifier.height(12.dp))
                    ErrorText(stringResource(R.string.support_error_submit))
                }

                Spacer(Modifier.height(20.dp))

                // ── Absenden ────────────────────────────────────────────────────
                Button(
                    onClick = { viewModel.submit() },
                    enabled = !state.isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (state.isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.support_submit),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.support_response_time),
            fontSize = 13.sp,
            color = detoxColors.subtext,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun SupportConfirmation(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = detoxColors.success,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.support_success_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = detoxColors.label,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.support_response_time),
            fontSize = 14.sp,
            color = detoxColors.subtext,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                text = stringResource(R.string.support_back),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = detoxColors.subtext,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = detoxColors.insetSurface,
    unfocusedContainerColor = detoxColors.insetSurface,
    errorContainerColor = detoxColors.insetSurface,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = MaterialTheme.colorScheme.error,
    focusedTextColor = detoxColors.label,
    unfocusedTextColor = detoxColors.label
)
