package com.detox.app.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.detox.app.R

/**
 * Prominent-disclosure dialog for the AccessibilityService (Play policy requirement).
 *
 * Shown BEFORE the user is sent to the accessibility settings, on every enable attempt (no
 * one-time flag). States exactly what data the service accesses (foreground package name; and, for
 * browsers, the current URL) and why, plus the on-device / not-stored privacy note. Only [onAccept]
 * (the affirmative "Zustimmen & aktivieren" tap) may proceed to `Settings.ACTION_ACCESSIBILITY_SETTINGS`;
 * [onDismiss] (cancel / outside tap) navigates nowhere.
 *
 * Copy lives entirely in string resources so it stays maintainable and translatable. The body is
 * vertically scrollable so the full text renders without truncation on small screens.
 */
@Composable
fun AccessibilityDisclosureDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.accessibility_disclosure_intro))
                DisclosureBullet(stringResource(R.string.accessibility_disclosure_point_apps))
                DisclosureBullet(stringResource(R.string.accessibility_disclosure_point_url))
                Text(stringResource(R.string.accessibility_disclosure_privacy))
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.accessibility_disclosure_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.accessibility_disclosure_cancel))
            }
        },
    )
}

@Composable
private fun DisclosureBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•")
        Text(text)
    }
}
