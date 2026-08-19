package com.finite.focus.presentation.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finite.focus.R
import com.finite.focus.presentation.screens.dashboard.failReasonShortStringRes
import com.finite.focus.ui.theme.detoxColors
import java.text.SimpleDateFormat
import java.util.Date

// All colors come from MaterialTheme.colorScheme / detoxColors — no literals here.

/**
 * Loss marker. A language-neutral symbol, so it lives in code rather than strings.xml (§4b) and
 * pairs with the ✓ already baked into `verlauf_status_completed`.
 */
private const val LOSS_MARK = "✗ "

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onChallengeClick: (String) -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.history_screen_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                // Distinct from NoHistory so the "nothing finished yet" copy no longer flashes
                // during the disk read on every open.
                is HistoryUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                is HistoryUiState.NoHistory -> CenteredMessage(
                    text = stringResource(R.string.verlauf_empty)
                )

                is HistoryUiState.Content -> Column(modifier = Modifier.fillMaxSize()) {
                    HistoryFilterSelector(
                        selected = filter,
                        onSelect = viewModel::setFilter
                    )
                    if (state.rows.isEmpty()) {
                        CenteredMessage(text = stringResource(R.string.verlauf_filter_empty))
                    } else {
                        HistoryList(rows = state.rows, onChallengeClick = onChallengeClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun HistoryList(rows: List<HistoryRow>, onChallengeClick: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is HistoryRow.MonthHeader -> MonthHeader(monthStartMs = row.monthStartMs)
                is HistoryRow.Entry -> HistoryRowCard(
                    entry = row.item,
                    onClick = { onChallengeClick(row.item.entity.id) }
                )
            }
        }
    }
}

/**
 * Section heading over each month, e.g. "AUGUST 2026" — and "OHNE DATUM" for the trailing group of
 * rows whose end date could not be established.
 *
 * The pattern comes from [android.text.format.DateFormat.getBestDateTimePattern] rather than a
 * literal, so the month/year order follows the device locale instead of a German assumption.
 */
@Composable
private fun MonthHeader(monthStartMs: Long?) {
    val locale = LocalConfiguration.current.locales[0]
    // Both remembers are UNCONDITIONAL — a `remember` inside an if/else corrupts the slot table if
    // the branch ever flips, and "undated vs dated" is exactly the kind of condition that can.
    val formatter = remember(locale) {
        SimpleDateFormat(
            android.text.format.DateFormat.getBestDateTimePattern(locale, "LLLLy"),
            locale
        )
    }
    val monthLabel = remember(monthStartMs, formatter) {
        monthStartMs?.let { formatter.format(Date(it)) }
    }
    val label = monthLabel ?: stringResource(R.string.verlauf_section_undated)
    Text(
        text = label.uppercase(locale),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = detoxColors.subtext,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp)
    )
}

/**
 * iOS-style segmented control for the status filter, mirroring the Settings theme-mode selector
 * (insetSurface track, cardBackground selected segment) — all theme tokens, dark-mode safe.
 *
 * Segments are weighted by LABEL LENGTH rather than split into three equal thirds. Equal thirds are
 * what made "Fehlgeschlagen" wrap onto a second line and grow the control: the longest label got
 * exactly as much room as "Alle". Deriving the weight from the rendered string keeps this working
 * in any locale instead of hard-coding a ratio that happens to suit German. `maxLines`/`softWrap`
 * are the backstop for extreme font scales — the control clips rather than reflows.
 */
@Composable
private fun HistoryFilterSelector(selected: HistoryFilter, onSelect: (HistoryFilter) -> Unit) {
    val labels = HistoryFilter.entries.associateWith { filter ->
        stringResource(
            when (filter) {
                HistoryFilter.ALL -> R.string.verlauf_filter_all
                HistoryFilter.COMPLETED -> R.string.verlauf_filter_completed
                HistoryFilter.NOT_COMPLETED -> R.string.verlauf_filter_not_completed
            }
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .background(detoxColors.insetSurface, RoundedCornerShape(8.dp))
            .padding(2.dp)
    ) {
        HistoryFilter.entries.forEach { filter ->
            val isSelected = filter == selected
            val label = labels.getValue(filter)
            Box(
                modifier = Modifier
                    // Floor of 6 so a very short label ("Alle") still gets a tappable segment.
                    .weight(label.length.coerceAtLeast(6).toFloat())
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (isSelected) {
                            Modifier.background(detoxColors.cardBackground, RoundedCornerShape(6.dp))
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelect(filter) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) detoxColors.label else detoxColors.subtext,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HistoryRowCard(entry: SoloChallengeHistory, onClick: () -> Unit) {
    val entity = entry.entity
    val locale = LocalConfiguration.current.locales[0]
    // Locale-driven day+month pattern ("20. Aug." in DE, "Aug 20" in EN) — never a hardcoded German
    // one. The year is deliberately absent: the month header above the row already carries it.
    val dateFormat = remember(locale) {
        SimpleDateFormat(
            android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"),
            locale
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = detoxColors.cardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.appDisplayName,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = detoxColors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // The ACTUAL end date, the same value the list sorts and groups by. Omitted
                // entirely when no trustworthy date exists — never "Kein Enddatum" (a finished
                // open-ended challenge did end on a real day), never a future or 1970 date.
                entry.displayEndDate?.let { endedAt ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.verlauf_ended_on,
                            dateFormat.format(Date(endedAt))
                        ),
                        fontSize = 12.sp,
                        color = detoxColors.subtext
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // The mode pill ("SOFT MODE" / "HARD MODE" / "GROUP") used to sit here. It is
                // removed while every challenge is Soft, because an identical badge on every row
                // carries no information. Reinstate it for non-Soft rows once Hard/Group ship —
                // the distinction is still cheaply available and intentionally kept alive below.
                // See `TypeBadge` / `typeBadgeLabelRes`.
                StatusText(entry = entry)
                ContextLine(entry = entry)
            }
        }
    }
}

/**
 * Win / loss / "ended, not settled yet" / "ended, outcome unverifiable".
 *
 * A loss now names what actually happened — limit exceeded, given up, permission revoked, … — via
 * the shared [failReasonShortStringRes] classification. Previously every non-win fell into a single
 * "✗ Aufgegeben" else-branch, which told a user who blew their daily limit that they had quit.
 *
 * The two indeterminate states stay deliberately neutral, neither green nor red: for `ended` the
 * outcome genuinely is not known yet and the stake is still with the server, and for
 * `ended_unverified` the log history died with a previous install — green would celebrate an
 * unobserved run, red would accuse the user of a breach nobody recorded.
 */
@Composable
private fun StatusText(entry: SoloChallengeHistory) {
    val status = entry.entity.status
    val isCompleted = status == "completed"
    val isIndeterminate = status == "ended" || status == "ended_unverified"

    val color = when {
        isIndeterminate -> detoxColors.subtext
        isCompleted -> detoxColors.success
        else -> detoxColors.danger
    }
    val label = when {
        status == "ended_unverified" -> stringResource(R.string.verlauf_status_unverified)
        status == "ended" -> stringResource(R.string.verlauf_status_awaiting_settlement)
        isCompleted -> stringResource(R.string.verlauf_status_completed)
        else -> LOSS_MARK + stringResource(failReasonShortStringRes(entry.entity.failReason))
    }
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End
    )
}

/**
 * Muted one-liner under the status: how long the challenge actually ran.
 *
 * Every branch is omitted rather than guessed when its inputs are not clean:
 *  - a loss needs BOTH a real recorded end date and a knowable plan, otherwise "nach 30 von 30
 *    Tagen" would be printed over a challenge abandoned on day 2 (the fallback end date IS the
 *    planned end, so the ratio would silently read as a full run);
 *  - a win needs a positive day count.
 *
 * Day counts come from `DateUtils.calendarDurationDays` via the ViewModel — never from DailyLog row
 * counts, which undercount because a clean day often writes no row at all.
 */
@Composable
private fun ContextLine(entry: SoloChallengeHistory) {
    val text = when {
        entry.entity.status == "ended_unverified" ->
            stringResource(R.string.verlauf_context_not_installed)

        entry.entity.status == "completed" && entry.durationDays > 0 ->
            stringResource(R.string.verlauf_context_held_days, entry.durationDays)

        entry.entity.status == "failed" &&
            entry.entity.endedAt != null &&
            entry.plannedDurationDays != null &&
            entry.durationDays <= entry.plannedDurationDays ->
            stringResource(
                R.string.verlauf_context_after_days,
                entry.durationDays,
                entry.plannedDurationDays
            )

        else -> null
    } ?: return

    Text(
        text = text,
        fontSize = 11.sp,
        color = detoxColors.subtext,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End
    )
}

// ── Mode badge (currently unused — see HistoryRowCard) ────────────────────────
//
// Kept compiling, not deleted: the GROUP / HARD / SOFT distinction returns to the row the moment
// non-Soft challenges ship, and re-deriving it then is pointless churn. `@Suppress("unused")`
// rather than a comment block so it keeps type-checking against the theme tokens and string keys.

/** Which badge a row would carry: GROUP wins over HARD, HARD over SOFT. */
@Suppress("unused")
internal fun typeBadgeLabelRes(isGroup: Boolean, isHard: Boolean): Int = when {
    isGroup -> R.string.history_detail_mode_group
    isHard -> R.string.verlauf_mode_hard
    else -> R.string.verlauf_mode_soft
}

@Suppress("unused")
@Composable
private fun TypeBadge(isGroup: Boolean, isHard: Boolean) {
    val bg: Color
    val textColor: Color
    when {
        isGroup -> {
            bg = detoxColors.softPurpleBg
            textColor = detoxColors.softPurpleText
        }
        isHard -> {
            bg = detoxColors.softOrangeBg
            textColor = detoxColors.softOrangeText
        }
        else -> {
            bg = detoxColors.softGreenBg
            textColor = detoxColors.softGreenText
        }
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = stringResource(typeBadgeLabelRes(isGroup, isHard)),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}
