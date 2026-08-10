package com.finite.focus.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finite.focus.R
import com.finite.focus.ui.theme.DetoxAvatarPalette
import com.finite.focus.ui.theme.detoxColors

/**
 * Monogram avatar. A participant's circle colour is hashed from their name, so the SAME person
 * keeps the SAME colour on every surface — see [DetoxAvatarPalette] (design-fixed, never recolour).
 *
 * Extracted from `GroupChallengeDetailScreen`, which held the only reusable copy.
 * `GroupChallengeResultsScreen` keeps its own private copy on purpose: that screen is a frozen
 * always-dark overlay with its own palette, exempted in docs/design_inconsistencies.md.
 */
@Composable
fun AvatarCircle(name: String, size: Dp = 32.dp) {
    val initials = name.trim()
        .split(" ")
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }
    val avatarColors = DetoxAvatarPalette.Colors
    // `and(0x7FFFFFFF)` and NOT `Math.abs`: abs(Int.MIN_VALUE) is Int.MIN_VALUE — still negative —
    // so a name hashing to exactly that would index the palette negatively and crash. Masking the
    // sign bit cannot. (The pre-extraction copy used Math.abs; this is the one behaviour change.)
    val bgColor = avatarColors[name.hashCode().and(0x7FFFFFFF) % avatarColors.size]
    Box(
        modifier = Modifier
            .size(size)
            .background(bgColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = detoxColors.onSolid
        )
    }
}

/**
 * "Du" pill marking the viewing user's own row.
 *
 * Value-preserving extraction — translucent `accent` rather than the design system's canonical
 * opaque softGreen pair. That divergence is logged as open inconsistency #10 in
 * docs/design_inconsistencies.md and is deliberately NOT resolved here.
 */
@Composable
fun DuBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = detoxColors.accent.copy(alpha = 0.15f)
    ) {
        Text(
            text = stringResource(R.string.group_detail_du_badge),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = detoxColors.accent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * One row of a group roster — a joined participant, or a free slot.
 *
 * Deliberately dumb: no `GroupChallenge`, no `Participant`, no rank, no stats. That is what lets it
 * render BEFORE a challenge starts, where `LeaderboardRow` cannot: rank is arbitrary while every
 * participant has identical zero stats, its stat and clean-days columns would read 0, its status
 * sub-label would say "active" for people who are waiting, and it takes a non-null `Participant`
 * so it cannot express an empty slot at all.
 *
 * [emptySlotLabel] is supplied by the caller rather than baked in so the same row can serve a
 * "your spot" framing on another surface without refitting.
 *
 * @param displayName the participant's name, or `null` for an unfilled slot.
 * @param subLabel small line under the name — currently the creator marker.
 */
@Composable
fun GroupParticipantRow(
    displayName: String?,
    emptySlotLabel: String,
    modifier: Modifier = Modifier,
    isCurrentUser: Boolean = false,
    subLabel: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (displayName != null) {
            AvatarCircle(name = displayName, size = 32.dp)
        } else {
            // Free slot: a recessed neutral circle. `insetSurface` + `hint` let empties read as
            // "space remaining" and sit a step back from filled rows without a new colour.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(detoxColors.insetSurface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.PersonAddAlt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = detoxColors.hint
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (displayName != null) "@$displayName" else emptySlotLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W500,
                    color = if (displayName != null) detoxColors.label else detoxColors.hint
                )
                if (isCurrentUser) DuBadge()
            }
            if (subLabel != null) {
                Text(text = subLabel, fontSize = 11.sp, color = detoxColors.subtext)
            }
        }
    }
}
